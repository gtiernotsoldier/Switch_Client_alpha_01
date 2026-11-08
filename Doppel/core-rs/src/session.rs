//! Session: one connected stub, its config, its long-lived strategy state.
//!
//! Two rules from 9.5.3 are enforced here rather than in the transport:
//!
//!  - **a panic degrades, it does not kill.** Every strategy call goes through
//!    `catch_unwind`. A poisoned `&mut State` is the one case we cannot recover
//!    from (Rust guarantees nothing about state after a panic), so the state is
//!    replaced wholesale and the tick is reported `Degraded` — the stub then
//!    holds last tick's command. Losing the chain state is a visible quality
//!    regression, not a crash.
//!  - **the snapshot is the heartbeat.** There is no separate keepalive: if
//!    snapshots stop arriving, the stub restarts us; if we stop answering, the
//!    stub degrades and restarts. Neither side waits on the other.

use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::codec::{self, op, ClickConfigKind};
use crate::model::*;
use crate::rng::Pcg32;
use crate::strategy::aim::{self, AimConfig, AimState};
use crate::strategy::click_assist;
use crate::strategy::click::{self, ClickConfig, ClickState, CooldownClickConfig, CritState};
use crate::strategy::backtrack;
use crate::strategy::combat::{self, BlockArbiterState, PredictorConfig, PredictorState, RangeGateConfig, RangeGateState};
use crate::strategy::eagle;
use crate::strategy::eagle_place;
use crate::strategy::hit_select;
use crate::strategy::jump_reset;
use crate::strategy::knockback_displace;
use crate::strategy::reach_sample;
use crate::strategy::sprint_reset;
use crate::strategy::super_knockback;
use crate::strategy::tap_sample;
use crate::strategy::movement::{self, SocdResolver};
use crate::strategy::reach;
use crate::strategy::tap::{self, TapStateMachine};
use crate::strategy::velocity;
use crate::wire::{Action as WireAction, Frame, Kind, Module, Reader, Reason, Status, Writer};

pub const SLOT_SNAPSHOT: usize = 232;
pub const SLOT_CONFIG: usize = 104;
pub const SLOT_DECISION: usize = 40;

/// Which click strategy the session is currently configured for.
///
/// The two variants have different state types and are selected by the first
/// byte of the config frame, so the session keeps both slots and only the live
/// one is advanced. Switching modes mid-session resets the other — a half-run
/// crit phase must not survive into a probabilistic run.
pub enum ClickSlot {
    Probabilistic(ClickConfig, ClickState),
    Cooldown(CooldownClickConfig, CritState),
}

pub struct Session {
    pub id: u64,
    pub seed: u64,
    /// One stream per module, all seeded from the session seed.
    ///
    /// A single shared stream would make a module's decisions depend on whether
    /// the other modules happened to be enabled and in what order they ran — so
    /// toggling aim would quietly change click timing. Six streams cost six
    /// 32-byte counters and make each module's sequence reproducible in
    /// isolation, which is also what lets a per-module parity test start from
    /// the same seed as the in-process strategy.
    pub rng_velocity: Pcg32,
    pub rng_aim: Pcg32,
    pub rng_click: Pcg32,
    pub rng_tap: Pcg32,
    /// WTap / STap range draws. Each module owns one stream so a WTap tap can
    /// never shift STap's replay (and neither can touch `rng_tap`, which the
    /// side-selection ops still use).
    pub rng_wtap: Pcg32,
    pub rng_stap: Pcg32,
    pub rng_combat: Pcg32,
    /// Eagle's hold-delay roll. Its own stream for the same reason as the rest:
    /// toggling another module must not move this one's sequence.
    pub rng_player: Pcg32,
    /// Backtrack's window roll: 80..250 ms, re-rolled on every hold.
    pub rng_backtrack: Pcg32,
    /// KnockbackDisplace's `Random` direction.
    pub rng_kbd: Pcg32,
    /// Reach's chance roll and distance jitter.
    pub rng_reach: Pcg32,
    /// ClickAssist's chance roll and `Random` mode.
    pub rng_click_assist: Pcg32,
    /// HitSelect's `chance` and `missedSwing` rolls. Gate-only: the tick half
    /// never draws, so the stream's order is the order clicks arrived in.
    pub rng_hit_select: Pcg32,
    /// Eagle AutoPlace's placement cooldown draw. Its own stream: the draw is
    /// consumed only on a CLICK, and must not interleave with the sneak delay
    /// stream (`rng_player`) or a replay of the two would diverge.
    pub rng_eagle_place: Pcg32,
    /// SprintReset's fire-probability roll. Gate-only draws (chance < 100 and
    /// only at the fire point) must not interleave with any other module's.
    pub rng_sprint_reset: Pcg32,
    /// JumpReset's chance roll. Drawn only on a knock that cleared its gates,
    /// never on a pump tick — the velocity and tick threads must not be able
    /// to shift each other's stream by scheduling differences.
    pub rng_jump_reset: Pcg32,
    /// SuperKnockback's fire-probability roll. Drawn only at the fire point;
    /// the state machine's idle / delayed / coordinating ticks never advance it.
    pub rng_super_knockback: Pcg32,
    pub config: VelocityConfig,
    pub velocity: velocity::State,

    pub aim_config: AimConfig,
    pub aim: AimState,

    pub click: ClickSlot,

    pub socd: SocdResolver,

    pub tap_config: TapWireConfig,
    pub tap: TapStateMachine,

    pub arbiter: BlockArbiterState,
    pub predictor_config: PredictorConfig,
    pub predictor: PredictorState,
    pub range_config: RangeGateConfig,
    pub range_state: RangeGateState,

    /// Eagle: the sneak-ownership bit and the release countdown.
    pub eagle: eagle::State,

    /// Backtrack: the hold window and its timestamps.
    pub backtrack: backtrack::State,

    /// KnockbackDisplace: the last flick time and the last strafe side.
    pub kbd: knockback_displace::State,

    /// Reach: the press counter and the cooldown.
    pub reach_sample: reach_sample::State,

    /// ClickAssist: the 500 ms edge history behind the "above 5 CPS" gate.
    pub click_assist: click_assist::State,

    /// HitSelect: the target windows shared by the tick and the click gate.
    pub hit_select: hit_select::State,

    /// Eagle AutoPlace: the aim in flight, the ticks spent waiting, and the
    /// cooldown left before the next placement.
    pub eagle_place: eagle_place::State,

    /// SprintReset: the rising-edge bit, the hit-count throttle, and the
    /// delayed-send deadline.
    pub sprint_reset: sprint_reset::State,

    /// JumpReset: the cooldown counter and the armed delayed jump. Shared by
    /// the velocity (knock) and tick (pump) call sites.
    pub jump_reset: jump_reset::State,

    /// SuperKnockback: the phase machine, the delay deadline, the coordination
    /// offset, the SprintTap force sequence and the per-target hit throttle.
    pub super_knockback: super_knockback::State,

    pub ticks: u64,
    pub decisions: u64,
    pub degraded: u64,
}

/// The scalars a tap request cannot infer from the snapshot.
#[derive(Debug, Clone, Copy)]
pub struct TapWireConfig {
    pub repeat_chance_percent: i32,
    pub lead_ticks: i32,
}

impl Default for TapWireConfig {
    fn default() -> Self {
        TapWireConfig {
            repeat_chance_percent: 0,
            lead_ticks: 0,
        }
    }
}

impl Session {
    pub fn new(id: u64, seed: u64) -> Session {
        Session {
            id,
            seed,
            rng_velocity: Pcg32::new(seed),
            rng_aim: Pcg32::new(seed),
            rng_click: Pcg32::new(seed),
            rng_tap: Pcg32::new(seed),
            rng_wtap: Pcg32::new(seed),
            rng_stap: Pcg32::new(seed),
            rng_combat: Pcg32::new(seed),
            rng_player: Pcg32::new(seed),
            rng_backtrack: Pcg32::new(seed),
            rng_kbd: Pcg32::new(seed),
            rng_reach: Pcg32::new(seed),
            rng_click_assist: Pcg32::new(seed),
            rng_hit_select: Pcg32::new(seed),
            rng_eagle_place: Pcg32::new(seed),
            rng_sprint_reset: Pcg32::new(seed),
            rng_jump_reset: Pcg32::new(seed),
            rng_super_knockback: Pcg32::new(seed),
            config: VelocityConfig::default(),
            velocity: velocity::State::new(),
            aim_config: AimConfig::default(),
            aim: AimState::default(),
            click: ClickSlot::Probabilistic(ClickConfig::default(), ClickState::default()),
            socd: SocdResolver::default(),
            tap_config: TapWireConfig::default(),
            tap: TapStateMachine::default(),
            arbiter: BlockArbiterState::default(),
            predictor_config: PredictorConfig::default(),
            predictor: PredictorState::default(),
            range_config: RangeGateConfig::default(),
            range_state: RangeGateState { engaged: false },
            eagle: eagle::State::default(),
            backtrack: backtrack::State::new(),
            kbd: knockback_displace::State::new(),
            reach_sample: reach_sample::State::new(),
            click_assist: click_assist::State::new(),
            hit_select: hit_select::State::new(),
            eagle_place: eagle_place::State::default(),
            sprint_reset: sprint_reset::State::new(),
            jump_reset: jump_reset::State::new(),
            super_knockback: super_knockback::State::new(),
            ticks: 0,
            decisions: 0,
            degraded: 0,
        }
    }

    pub fn handle(&mut self, frame: &Frame) -> Frame {
        match frame.header.kind {
            Kind::Snapshot => self.on_snapshot(frame),
            Kind::Config => self.on_config(frame),
            Kind::Reset => self.on_reset(frame),
            Kind::Ping => {
                let mut r = Frame::new(
                    Kind::Pong,
                    frame.header.seq,
                    frame.header.tick,
                    Module::from_u8(frame.header.module).unwrap_or(Module::Velocity),
                );
                r.header.correlation = frame.header.correlation;
                r
            }
            _ => Frame::new(Kind::Error, frame.header.seq, frame.header.tick, Module::Velocity)
                .with_status(Status::UnknownKind),
        }
    }

    /// Forget one module's cross-tick memory, keeping its configuration.
    ///
    /// Configuration and state are reset in different places on purpose: the
    /// user's settings survive a module toggle, but a held key, a half-elapsed
    /// tap or a queued velocity slot must not. A stale `SocdResolver` would
    /// believe the opposite key was pressed later than it was; a stale tap
    /// timer would fire an instant key press on the tick the module comes back.
    fn on_reset(&mut self, frame: &Frame) -> Frame {
        let module = match Module::from_u8(frame.header.module) {
            Some(m) => m,
            None => return self.bad(frame, Status::UnknownKind),
        };
        match module {
            Module::Velocity => self.velocity = velocity::State::new(),
            Module::Aim => self.aim = AimState::default(),
            Module::Click => {
                self.click = match self.click {
                    ClickSlot::Probabilistic(c, _) => {
                        ClickSlot::Probabilistic(c, ClickState::default())
                    }
                    ClickSlot::Cooldown(c, _) => ClickSlot::Cooldown(c, CritState::default()),
                };
            }
            Module::Movement => self.socd = SocdResolver::default(),
            Module::Tap => self.tap = TapStateMachine::default(),
            Module::Combat => {
                self.arbiter = BlockArbiterState::default();
                self.predictor = PredictorState::default();
                self.range_state = RangeGateState { engaged: false };
            }
            // Reach keeps nothing between calls; the geometry is in the request.
            Module::Reach => {}
            // Eagle forgets its ownership: a sneak held across a disable/enable
            // would otherwise be released by a module that no longer owns it.
            // Backtrack drops its window for the same reason — a hold that spans
            // a toggle would keep packets queued for a module that is off.
            Module::Player => {
                self.eagle = eagle::State::default();
                self.backtrack = backtrack::State::new();
                self.kbd = knockback_displace::State::new();
                self.reach_sample = reach_sample::State::new();
                self.click_assist = click_assist::State::new();
                self.hit_select = hit_select::State::new();
                self.eagle_place = eagle_place::State::default();
                self.sprint_reset = sprint_reset::State::new();
                self.jump_reset = jump_reset::State::new();
                self.super_knockback = super_knockback::State::new();
            }
        }
        Frame::new(Kind::Reset, frame.header.seq, frame.header.tick, module)
    }

    fn on_config(&mut self, frame: &Frame) -> Frame {
        let len = frame.header.payload_len as usize;
        let module = match Module::from_u8(frame.header.module) {
            Some(m) => m,
            None => return self.bad(frame, Status::UnknownKind),
        };
        let result = match module {
            Module::Velocity => decode_config(&frame.payload, len).map(|c| {
                self.config = c;
            }),
            Module::Aim => codec::decode_aim_config(&frame.payload, len).map(|c| {
                self.aim_config = c;
            }),
            Module::Click => codec::decode_click_config(&frame.payload, len).map(|k| match k {
                ClickConfigKind::Probabilistic(c) => {
                    self.click = ClickSlot::Probabilistic(c, ClickState::default());
                }
                ClickConfigKind::Cooldown(c) => {
                    self.click = ClickSlot::Cooldown(c, CritState::default());
                }
            }),
            Module::Tap => decode_tap_config(&frame.payload, len).map(|c| {
                self.tap_config = c;
            }),
            Module::Combat => decode_combat_config(&frame.payload, len).map(|(p, r)| {
                self.predictor_config = p;
                self.range_config = r;
                self.arbiter = BlockArbiterState::default();
                self.predictor = PredictorState::default();
            }),
            // Movement and reach have no configuration: every argument rides on
            // the request, so there is nothing to latch. Eagle is the same — its
            // only settings are the roll bounds, which ride on each tick.
            Module::Movement | Module::Reach | Module::Player => Ok(()),
        };
        match result {
            Ok(()) => Frame::new(Kind::Config, frame.header.seq, frame.header.tick, module),
            Err(s) => self.bad(frame, s),
        }
    }

    fn on_snapshot(&mut self, frame: &Frame) -> Frame {
        let len = frame.header.payload_len as usize;
        let module = match Module::from_u8(frame.header.module) {
            Some(m) => m,
            None => return self.bad(frame, Status::UnknownKind),
        };
        self.ticks += 1;

        // Every arm below is guarded independently: a panic in one module must
        // leave the others' state untouched. That is the whole reason the states
        // are separate fields rather than one blob.
        match module {
            Module::Velocity => self.on_velocity(frame, len),
            Module::Aim => self.on_aim(frame, len),
            Module::Click => self.on_click(frame, len),
            Module::Movement => self.on_movement(frame, len),
            Module::Tap => self.on_tap(frame, len),
            Module::Combat => self.on_combat(frame, len),
            Module::Reach => self.on_reach(frame, len),
            Module::Player => self.on_player(frame, len),
        }
    }

    /// Velocity's tick-level questions, which ride on a snapshot plus an op byte.
    fn on_velocity_op(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_generic(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let seq = frame.header.seq;
        let tick = frame.header.tick;
        let mut run = || -> Result<Frame, Status> {
            match req.op {
                op::VEL_BRACE => {
                    let keys =
                        velocity::brace_keys(req.ctx.player.yaw, req.arg(0)?, &mut self.velocity);
                    let corr = movement::StrafeCorrection {
                        suppress_forward: keys.0,
                        suppress_back: keys.1,
                        suppress_left: keys.2,
                        suppress_right: keys.3,
                        before: 0.0,
                        after: 0.0,
                    };
                    Ok(codec::encode_suppress(seq, tick, Module::Velocity, &corr))
                }
                op::VEL_DELAY_ENQUEUE => {
                    self.velocity.tick_counter = req.i32(0)?;
                    let cfg = VelocityConfig {
                        delay_chance: req.i32(1)?,
                        max_delay_ticks: req.i32(2)?,
                        require_weapon: req.bool(3)?,
                        trigger: codec::trigger_from_packed(
                            req.arg(4)?,
                            req.arg(5)?,
                            req.arg(6)?,
                            req.arg(7)?,
                            req.arg(8)?,
                        ),
                        ..VelocityConfig::default()
                    };
                    let swallowed = velocity::delay_enqueue(
                        &cfg,
                        &mut self.velocity,
                        &req.ctx,
                        &mut self.rng_velocity,
                    );
                    Ok(codec::encode_bool(
                        seq,
                        tick,
                        Module::Velocity,
                        WireAction::Bool,
                        swallowed,
                    ))
                }
                op::VEL_DELAY_PUMP => {
                    let released = velocity::delay_pump(&mut self.velocity, req.i32(0)?);
                    Ok(codec::encode_motions(seq, tick, &released))
                }
                _ => Err(Status::BadPayload),
            }
        };
        match run() {
            Ok(f) => {
                self.decisions += 1;
                f
            }
            Err(s) => self.bad(frame, s),
        }
    }

    fn degrade(&mut self, frame: &Frame) -> Frame {
        self.degraded += 1;
        let m = Module::from_u8(frame.header.module).unwrap_or(Module::Velocity);
        Frame::new(Kind::Decision, frame.header.seq, frame.header.tick, m)
            .with_status(Status::Degraded)
    }

    fn bad(&self, frame: &Frame, s: Status) -> Frame {
        let m = Module::from_u8(frame.header.module).unwrap_or(Module::Velocity);
        Frame::new(Kind::Error, frame.header.seq, frame.header.tick, m).with_status(s)
    }

    fn on_velocity(&mut self, frame: &Frame, len: usize) -> Frame {
        // A payload longer than the snapshot carries an op byte after it. Velocity's
        // per-hit path is a bare snapshot; only the tick-level brace asks a question,
        // and it must not re-run the reduction that the hit's own frame already ran.
        if len > SLOT_SNAPSHOT {
            return self.on_velocity_op(frame, len);
        }
        let ctx = match decode_snapshot(&frame.payload, len) {
            Ok(c) => c,
            Err(s) => return self.bad(frame, s),
        };
        self.velocity.tick_counter = frame.header.tick as i32;

        // Delay release runs before the new hit is considered: a packet held for
        // N ticks must come out on tick T+N even if T+N also carries a fresh hit,
        // otherwise the queue drifts under sustained pressure.
        let released = velocity::delay_pump(&mut self.velocity, frame.header.tick as i32);

        let result = catch_unwind(AssertUnwindSafe(|| {
            velocity::execute(&self.config, &mut self.velocity, &ctx, &mut self.rng_velocity)
        }));

        match result {
            Ok(decision) => {
                self.decisions += 1;
                encode_decision(&decision, frame.header.seq, frame.header.tick, released.len() as u8)
            }
            Err(_) => {
                // Strategy panicked. The state may be half-mutated; drop it and
                // start the chain over rather than trust it.
                self.velocity = velocity::State::new();
                let mut d = Decision::noop(Reason::Panic as u8);
                d.action = Action::NoOp;
                encode_decision(&d, frame.header.seq, frame.header.tick, 0)
                    .with_status(Status::Degraded)
            }
        }
    }

    fn on_aim(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_aim_request(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        // `sensitivity` is carried for the adapter, which scales the mouse delta
        // before it reaches here; the strategy itself does not read it.
        let input = req.input();
        let r = catch_unwind(AssertUnwindSafe(|| {
            aim::execute(&self.aim_config, &mut self.aim, &input, &mut self.rng_aim)
        }));
        match r {
            Ok(result) => {
                self.decisions += 1;
                codec::encode_aim_decision(&result, frame.header.seq, frame.header.tick)
            }
            Err(_) => {
                self.aim = AimState::default();
                self.degrade(frame)
            }
        }
    }

    fn on_click(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_click_request(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let input = req.input();
        // Take the slot by value so the borrow checker allows `&mut self.rng_click`
        // in the same expression; it is put back unconditionally.
        let slot = std::mem::replace(
            &mut self.click,
            ClickSlot::Probabilistic(ClickConfig::default(), ClickState::default()),
        );
        let (result, back) = match slot {
            ClickSlot::Probabilistic(cfg, mut st) => {
                let out = catch_unwind(AssertUnwindSafe(|| {
                    click::probabilistic_tick(&cfg, &mut st, &input, &mut self.rng_click)
                }));
                match out {
                    Ok(r) => (Some(r), ClickSlot::Probabilistic(cfg, st)),
                    Err(_) => (None, ClickSlot::Probabilistic(cfg, ClickState::default())),
                }
            }
            ClickSlot::Cooldown(cfg, mut st) => {
                let out = catch_unwind(AssertUnwindSafe(|| {
                    click::cooldown_tick(&cfg, &mut st, &input, &mut self.rng_click)
                }));
                match out {
                    Ok(r) => (Some(r), ClickSlot::Cooldown(cfg, st)),
                    Err(_) => (None, ClickSlot::Cooldown(cfg, CritState::default())),
                }
            }
        };
        self.click = back;
        match result {
            Some(r) => {
                self.decisions += 1;
                codec::encode_click_decision(&r, frame.header.seq, frame.header.tick)
            }
            None => self.degrade(frame),
        }
    }

    fn on_movement(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_generic(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let seq = frame.header.seq;
        let tick = frame.header.tick;
        let run = || -> Result<Frame, Status> {
            match req.op {
                op::MOV_SOCD => {
                    let c = self.socd.resolve(
                        req.bool(0)?,
                        req.bool(1)?,
                        req.bool(2)?,
                        req.bool(3)?,
                    );
                    let corr = movement::StrafeCorrection {
                        suppress_forward: c.suppress_forward,
                        suppress_back: c.suppress_back,
                        suppress_left: c.suppress_left,
                        suppress_right: c.suppress_right,
                        before: 0.0,
                        after: 0.0,
                    };
                    Ok(codec::encode_suppress(seq, tick, Module::Movement, &corr))
                }
                op::MOV_STRAFE => {
                    let input = movement::StrafeInput {
                        forward: req.bool(0)?,
                        back: req.bool(1)?,
                        left: req.bool(2)?,
                        right: req.bool(3)?,
                    };
                    let state = movement::StrafeState {
                        distance: req.arg(4)?,
                        bearing_rad: req.arg(5)?,
                        yaw_deg: req.arg(6)?,
                        reach: req.arg(7)?,
                    };
                    let corr = movement::strafe_correct(input, state);
                    Ok(codec::encode_suppress(seq, tick, Module::Movement, &corr))
                }
                op::MOV_SLIDING => {
                    let v = movement::still_sliding(
                        req.arg(0)?,
                        req.arg(1)?,
                        req.f32(2)?,
                        req.bool(3)?,
                        req.arg(4)?,
                    );
                    Ok(codec::encode_bool(seq, tick, Module::Movement, WireAction::Bool, v))
                }
                op::PLAYER_HIT_TICK => {
                    let input = hit_select::TickInput {
                        now_ns: req.arg(0)? as i64,
                        can_crit: req.bool(1)?,
                        player_hurt_time: req.i32(2)?,
                        left_mouse_down: req.bool(3)?,
                        has_target: req.bool(4)?,
                        target_id: req.i32(5)?,
                        target_hurt_time: req.i32(6)?,
                        counter_hit: req.bool(7)?,
                        counter_cd_ms: req.i32(8)?,
                    };
                    let fire = hit_select::tick(&mut self.hit_select, input);
                    Ok(codec::encode_bool(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Bool,
                        fire,
                    ))
                }
                op::PLAYER_HIT_GATE => {
                    let input = hit_select::GateInput {
                        now_ns: req.arg(0)? as i64,
                        gui_open: req.bool(1)?,
                        looking_at_block: req.bool(2)?,
                        chance: req.i32(3)?,
                        criticals: req.bool(4)?,
                        missed_swing: req.i32(5)?,
                        has_forward_target: req.bool(6)?,
                        forward_hurt_resistant_time: req.i32(7)?,
                        wait_first_ms: req.i32(8)?,
                        retime_at: req.i32(9)?,
                        pause_ms: req.i32(10)?,
                    };
                    let rng = &mut self.rng_hit_select;
                    let mut roll = || rng.random_int(0, 99);
                    let pass = hit_select::gate(&self.hit_select, input, &mut roll);
                    Ok(codec::encode_bool(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Bool,
                        pass,
                    ))
                }
                _ => Err(Status::BadPayload),
            }
        };
        match catch_unwind(AssertUnwindSafe(run)) {
            Ok(Ok(f)) => {
                self.decisions += 1;
                f
            }
            Ok(Err(s)) => self.bad(frame, s),
            Err(_) => self.degrade(frame),
        }
    }

    fn on_tap(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_generic(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let seq = frame.header.seq;
        let tick = frame.header.tick;
        let run = || -> Result<Frame, Status> {
            match req.op {
                op::TAP_CHASE => {
                    let target = match req.ctx.target {
                        Some(t) => t,
                        None => {
                            return Ok(codec::encode_scalar(
                                seq,
                                tick,
                                Module::Tap,
                                WireAction::TapSide,
                                -1.0,
                            ))
                        }
                    };
                    let side = tap::chase_side(req.f32(0)?, req.ctx.player.position, &target, req.i32(1)?);
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Tap,
                        WireAction::TapSide,
                        codec::side_to_f64(side),
                    ))
                }
                op::TAP_SEVEN => {
                    let side = tap::seven_tap_side(
                        codec::f64_to_side(req.arg(0)?),
                        req.i32(1)?,
                        &mut self.rng_tap,
                    );
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Tap,
                        WireAction::TapSide,
                        codec::side_to_f64(side),
                    ))
                }
                op::TAP_RANDOM => {
                    let side = tap::random_side(&mut self.rng_tap);
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Tap,
                        WireAction::TapSide,
                        codec::side_to_f64(side),
                    ))
                }
                op::TAP_WRAP => Ok(codec::encode_scalar(
                    seq,
                    tick,
                    Module::Tap,
                    WireAction::Scalar,
                    tap::wrap180(req.f32(0)?) as f64,
                )),
                op::TAP_TICK => {
                    let e = self.tap.tick(req.arg(0)? as i64);
                    let v = match e {
                        tap::TapEvent::None => 0.0,
                        tap::TapEvent::EndTap => 1.0,
                        tap::TapEvent::ShouldStartTap => 2.0,
                    };
                    // Phase rides along in the same word: `event + 4 * phase`. The
                    // module needs both every tick, and a second round trip to ask
                    // "what phase are we in" would be one more chance to interleave
                    // with the tap timer it is asking about.
                    let phase = match self.tap.phase {
                        tap::TapPhase::Idle => 0.0,
                        tap::TapPhase::PostDelay => 1.0,
                        tap::TapPhase::Tapping => 2.0,
                    };
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Tap,
                        WireAction::Scalar,
                        v + 4.0 * phase,
                    ))
                }
                op::TAP_POST_DELAY => {
                    self.tap.begin_post_delay(req.arg(0)? as i64, req.i32(1)?);
                    Ok(codec::encode_scalar(seq, tick, Module::Tap, WireAction::NoOp, 0.0))
                }
                op::TAP_BEGIN => {
                    self.tap.begin_tap(req.arg(0)? as i64, req.i32(1)?);
                    Ok(codec::encode_scalar(seq, tick, Module::Tap, WireAction::NoOp, 0.0))
                }
                op::TAP_WTAP_SAMPLE | op::TAP_STAP_SAMPLE => {
                    let kind = tap_sample::Kind::from_code(req.i32(0)?)
                        .ok_or(Status::BadPayload)?;
                    let input = tap_sample::Input {
                        kind,
                        post_delay_min: req.i32(1)?,
                        post_delay_max: req.i32(2)?,
                        action_min: req.i32(3)?,
                        action_max: req.i32(4)?,
                        once_every_min: req.i32(5)?,
                        once_every_max: req.i32(6)?,
                    };
                    let rng = if req.op == op::TAP_WTAP_SAMPLE {
                        &mut self.rng_wtap
                    } else {
                        &mut self.rng_stap
                    };
                    // Kotlin's `Random.nextInt(min, max + 1)` is inclusive on
                    // both ends; `random_int` is the same closed interval.
                    let out = tap_sample::sample(input, &mut |lo, hi| rng.random_int(lo, hi));
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Tap,
                        WireAction::Scalar,
                        tap_sample::pack(out) as f64,
                    ))
                }
                _ => Err(Status::BadPayload),
            }
        };
        match catch_unwind(AssertUnwindSafe(run)) {
            Ok(Ok(f)) => {
                self.decisions += 1;
                f
            }
            Ok(Err(s)) => self.bad(frame, s),
            Err(_) => {
                self.tap = TapStateMachine::default();
                self.degrade(frame)
            }
        }
    }

    fn on_combat(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_generic(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let seq = frame.header.seq;
        let tick = frame.header.tick;
        let run = || -> Result<Frame, Status> {
            match req.op {
                op::CBT_TRIGGER => {
                    let mode = match req.i32(0)? {
                        0 => combat::TriggerMode::Post,
                        1 => combat::TriggerMode::Pre,
                        2 => combat::TriggerMode::Equal,
                        _ => return Err(Status::BadPayload),
                    };
                    let r = combat::combat_trigger_evaluate(
                        mode,
                        req.i32(1)?,
                        req.i32(2)?,
                        req.i32(3)?,
                        req.i32(4)?,
                        req.i32(5)?,
                        req.i32(6)?,
                        req.i32(7)?,
                        req.bool(8)?,
                        &mut self.rng_combat,
                    );
                    Ok(codec::encode_bool(seq, tick, Module::Combat, WireAction::Bool, r.fire))
                }
                op::CBT_ARBITER => {
                    let weights = combat::BlockWeights {
                        lock: req.arg(4)?,
                        threat: req.arg(5)?,
                        sustain: req.arg(6)?,
                        reflex: req.arg(7)?,
                    };
                    let duty = self.arbiter.duty();
                    let v = combat::block_arbiter_evaluate(
                        req.bool(0)?,
                        req.bool(1)?,
                        req.bool(2)?,
                        req.bool(3)?,
                        weights,
                        req.arg(8)?,
                        duty,
                        req.arg(9)?,
                    );
                    self.arbiter.record(v.block);
                    Ok(codec::encode_verdict(seq, tick, v.block, v.score, 0, false, false))
                }
                op::CBT_PREDICT => {
                    let cfg = PredictorConfig {
                        max_hurt_resistant_time: req.i32(0)?,
                        rtt_ticks: req.i32(1)?,
                        lead_ticks: req.i32(2)?,
                        predict_attacks: req.bool(3)?,
                        predict_window_ticks: req.i32(4)?,
                        predict_chance: req.i32(5)?,
                        interval_alpha: req.arg(6)?,
                        seed_interval_ticks: req.arg(7)?,
                    };
                    let d = combat::block_predictor_evaluate(
                        req.ctx.target.map(|t| t.hurt_time),
                        req.i32(8)?,
                        req.bool(9)?,
                        &mut self.predictor,
                        &cfg,
                        &mut self.rng_combat,
                    );
                    Ok(codec::encode_verdict(
                        seq,
                        tick,
                        d.block,
                        0.0,
                        d.effective_hurt,
                        d.locked,
                        d.threat,
                    ))
                }
                op::CBT_RANGE => {
                    let mode = match req.i32(0)? {
                        0 => combat::RangeMode::InRange,
                        1 => combat::RangeMode::OnEnter,
                        _ => return Err(Status::BadPayload),
                    };
                    let cfg = RangeGateConfig {
                        mode,
                        min_distance: req.f32(1)?,
                        max_distance: req.f32(2)?,
                        hysteresis: req.f32(3)?,
                    };
                    let distance = if req.bool(4)? {
                        Some(req.f32(5)?)
                    } else {
                        None
                    };
                    let v = combat::range_gate_compute(Some(&mut self.range_state), &cfg, distance);
                    Ok(codec::encode_bool(seq, tick, Module::Combat, WireAction::Bool, v))
                }
                op::CBT_DECIDE => {
                    let opts = codec::trigger_from_packed(
                        req.arg(0)?,
                        req.arg(1)?,
                        req.arg(2)?,
                        req.arg(3)?,
                        req.arg(4)?,
                    );
                    let mode_code = req.i32(5)?;
                    let hurt_gate = req.bool(6)?;
                    let max_hurt = req.i32(7)?;
                    let target_hurt = req.i32(8)?;
                    let counter = req.i32(9)?;
                    let threshold = req.i32(10)?;
                    let per_min = req.i32(11)?;
                    let per_max = req.i32(12)?;
                    let trigger_chance = req.i32(13)?;

                    // Gate first, trigger second — the order the JVM path used, so that the
                    // trigger's one or two draws land at the same offsets in the stream. Reversing
                    // these still passes a single-tick test and breaks every replay.
                    let blocked = crate::condition::check(
                        &opts,
                        &req.ctx.player,
                        req.ctx.target.as_ref(),
                        &mut self.rng_combat,
                    )
                    .is_some();
                    if blocked {
                        return Ok(codec::encode_tristate(
                            seq, tick, Module::Combat, WireAction::Bool, false, counter, threshold,
                        ));
                    }
                    if mode_code < 0 {
                        return Ok(codec::encode_tristate(
                            seq, tick, Module::Combat, WireAction::Bool, true, counter, threshold,
                        ));
                    }
                    let mode = match mode_code {
                        0 => combat::TriggerMode::Post,
                        1 => combat::TriggerMode::Pre,
                        2 => combat::TriggerMode::Equal,
                        _ => return Err(Status::BadPayload),
                    };
                    let r = combat::combat_trigger_evaluate(
                        mode,
                        target_hurt,
                        max_hurt,
                        counter,
                        threshold,
                        per_min,
                        per_max,
                        trigger_chance,
                        hurt_gate,
                        &mut self.rng_combat,
                    );
                    Ok(codec::encode_tristate(
                        seq,
                        tick,
                        Module::Combat,
                        WireAction::Bool,
                        r.fire,
                        r.hit_counter,
                        r.hit_threshold,
                    ))
                }
                _ => Err(Status::BadPayload),
            }
        };
        match catch_unwind(AssertUnwindSafe(run)) {
            Ok(Ok(f)) => {
                self.decisions += 1;
                f
            }
            Ok(Err(s)) => self.bad(frame, s),
            Err(_) => self.degrade(frame),
        }
    }

    fn on_reach(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_generic(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let seq = frame.header.seq;
        let tick = frame.header.tick;
        let run = || -> Result<Frame, Status> {
            match req.op {
                op::REACH_INTERSECT => {
                    let min = Vec3::new(req.arg(0)?, req.arg(1)?, req.arg(2)?);
                    let max = Vec3::new(req.arg(3)?, req.arg(4)?, req.arg(5)?);
                    let origin = Vec3::new(req.arg(6)?, req.arg(7)?, req.arg(8)?);
                    let dir = Vec3::new(req.arg(9)?, req.arg(10)?, req.arg(11)?);
                    let hit = reach::intersect_box(origin, dir, min, max, req.arg(12)?);
                    Ok(codec::encode_bool(
                        seq,
                        tick,
                        Module::Reach,
                        WireAction::Bool,
                        hit.is_some(),
                    ))
                }
                _ => Err(Status::BadPayload),
            }
        };
        match catch_unwind(AssertUnwindSafe(run)) {
            Ok(Ok(f)) => {
                self.decisions += 1;
                f
            }
            Ok(Err(s)) => self.bad(frame, s),
            Err(_) => self.degrade(frame),
        }
    }

    /// Eagle's sneak machine, one call per tick.
    ///
    /// The three predicates are the ones only the game side can answer (edge
    /// query, pitch gate, real key state). The ownership bit and the countdown
    /// are core state, so they are not on the wire — the reply is packed flags
    /// instead: bit0 press, bit1 release, bit2 `auto_sneak_active` (the stub
    /// needs it to tell its own sneak from the player's).
    fn on_player(&mut self, frame: &Frame, len: usize) -> Frame {
        let req = match codec::decode_generic(&frame.payload, len) {
            Ok(r) => r,
            Err(s) => return self.bad(frame, s),
        };
        let seq = frame.header.seq;
        let tick = frame.header.tick;
        let run = || -> Result<Frame, Status> {
            match req.op {
                op::PLAYER_EAGLE_TICK => {
                    let input = eagle::Input {
                        on_edge: req.bool(0)?,
                        settings_met: req.bool(1)?,
                        sneak_key_held: req.bool(2)?,
                    };
                    // Kotlin's `Random.nextInt(lo, hi + 1)`: the caller already
                    // normalised min/max, but an inverted pair must clamp rather
                    // than panic inside the draw.
                    let (lo, hi) = {
                        let a = req.i32(3)?;
                        let b = req.i32(4)?;
                        if a <= b {
                            (a, b)
                        } else {
                            (b, a)
                        }
                    };
                    let state = self.eagle;
                    let rng = &mut self.rng_player;
                    let (d, next) = eagle::tick(state, input, &mut || rng.random_int(lo, hi));
                    self.eagle = next;
                    let flags = (d.press as u8)
                        | ((d.release as u8) << 1)
                        | ((next.auto_sneak_active as u8) << 2);
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        flags as f64,
                    ))
                }
                op::PLAYER_BACKTRACK_TICK => {
                    let input = backtrack::Input {
                        // f64 holds nanosecond timestamps exactly to ~0.1 ns at the
                        // magnitudes System.nanoTime() produces, so the round trip
                        // costs at most an ulp — far below the 80 ms window.
                        now_ns: req.arg(0)? as i64,
                        modern: req.bool(1)?,
                        pulse: req.bool(2)?,
                        smart: req.bool(3)?,
                        min_delay_ms: req.i32(4)?,
                        max_delay_ms: req.i32(5)?,
                        distance: req.f32(6)?,
                        legacy_distance: req.f32(7)?,
                        must_release: req.bool(8)?,
                    };
                    let rng = &mut self.rng_backtrack;
                    let hold = backtrack::tick(&mut self.backtrack, input, &mut |lo, hi| {
                        rng.random_int(lo, hi)
                    });
                    Ok(codec::encode_bool(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Bool,
                        hold,
                    ))
                }
                op::PLAYER_KBD_ATTACK => {
                    let input = knockback_displace::Input {
                        now_ns: req.arg(0)? as i64,
                        direction: knockback_displace::Direction::from_u8(req.arg(1)? as u8),
                        use_last_strafe: req.bool(2)?,
                        cooldown_ms: req.i32(3)?,
                        angle: req.f32(4)?,
                        not_taking_knockback: req.bool(5)?,
                        velocity_this_tick: req.bool(6)?,
                        key_left: req.bool(7)?,
                        key_right: req.bool(8)?,
                    };
                    let offset =
                        knockback_displace::attack(&mut self.kbd, input, &mut self.rng_kbd);
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        offset as f64,
                    ))
                }
                op::PLAYER_REACH_SAMPLE => {
                    let input = reach_sample::Input {
                        now_ns: req.arg(0)? as i64,
                        conditions_ok: req.bool(1)?,
                        gui_open: req.bool(2)?,
                        chance: req.i32(3)?,
                        hit_per: req.i32(4)?,
                        delay_ms: req.i32(5)?,
                        hit_through_blocks: req.bool(6)?,
                        looking_at_block: req.bool(7)?,
                        reach_min: req.f32(8)?,
                        reach_max: req.f32(9)?,
                    };
                    let d = reach_sample::sample(
                        &mut self.reach_sample,
                        input,
                        &mut self.rng_reach,
                    );
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        d,
                    ))
                }
                op::PLAYER_CLICK_ASSIST => {
                    let input = click_assist::Input {
                        now_ns: req.arg(0)? as i64,
                        flags: req.arg(1)? as u32,
                        probability: req.i32(2)?,
                        mode: click_assist::Mode::from_u8(req.arg(3)? as u8),
                    };
                    let extra = click_assist::evaluate(
                        &mut self.click_assist,
                        input,
                        &mut self.rng_click_assist,
                    );
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        extra as f64,
                    ))
                }
                op::PLAYER_EAGLE_PLACE => {
                    let input = eagle_place::Input {
                        gates_open: req.bool(0)?,
                        pitch_in_tolerance: req.bool(1)?,
                        bridge_assist_enabled: req.bool(2)?,
                        max_wait_ticks: req.i32(3)?,
                        cooldown_min: req.i32(4)?,
                        cooldown_max: req.i32(5)?,
                    };
                    // Kotlin's `Random.nextInt(lo, hi + 1)`: normalise an
                    // inverted pair here rather than panic inside the draw.
                    let (lo, hi) = {
                        let a = input.cooldown_min;
                        let b = input.cooldown_max;
                        if a <= b { (a, b) } else { (b, a) }
                    };
                    let state = self.eagle_place;
                    let rng = &mut self.rng_eagle_place;
                    let (step, next) =
                        eagle_place::tick(state, input, &mut || rng.random_int(lo, hi));
                    self.eagle_place = next;
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        step.code() as f64,
                    ))
                }
                op::PLAYER_SPRINT_RESET => {
                    let input = sprint_reset::Input {
                        now_ns: req.arg(0)? as i64,
                        has_target: req.bool(1)?,
                        in_range: req.bool(2)?,
                        gate_ok: req.bool(3)?,
                        is_hurt: req.bool(4)?,
                        entity_id: req.i32(5)?,
                        chance: req.i32(6)?,
                        hit_count: req.i32(7)?,
                        delay_ms: req.i32(8)?,
                        mode: req.i32(9)?,
                    };
                    let state = self.sprint_reset;
                    let rng = &mut self.rng_sprint_reset;
                    // Kotlin's `Random.nextInt(100)` is [0, 100); the closure is
                    // invoked only when chance < 100 and all gates passed.
                    let (step, next) =
                        sprint_reset::tick(state, input, &mut || rng.random_int(0, 99));
                    self.sprint_reset = next;
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        step.code() as f64,
                    ))
                }
                op::PLAYER_JUMP_KNOCK => {
                    let input = jump_reset::KnockInput {
                        gates_open: req.bool(0)?,
                        now_ns: req.arg(1)? as i64,
                        chance: req.i32(2)?,
                        cooldown_mode: jump_reset::CooldownMode::from_code(req.i32(3)?),
                        ticks_until: req.i32(4)?,
                        hits_until: req.i32(5)?,
                        delay_ms: req.i32(6)?,
                    };
                    let state = self.jump_reset;
                    let rng = &mut self.rng_jump_reset;
                    // Kotlin's `Random.nextInt(100)` is [0, 100); the closure
                    // is invoked only when chance < 100 and the gates passed.
                    let (step, next) =
                        jump_reset::knock(state, input, &mut || rng.random_int(0, 99));
                    self.jump_reset = next;
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        step.code() as f64,
                    ))
                }
                op::PLAYER_JUMP_PUMP => {
                    let input = jump_reset::PumpInput {
                        now_ns: req.arg(0)? as i64,
                        cooldown_mode: jump_reset::CooldownMode::from_code(req.i32(1)?),
                        ticks_until: req.i32(2)?,
                    };
                    // No roll: the pump half only advances the clock.
                    let (step, next) = jump_reset::pump(self.jump_reset, input);
                    self.jump_reset = next;
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        step.code() as f64,
                    ))
                }
                op::PLAYER_SUPER_KNOCKBACK => {
                    let input = super_knockback::Input {
                        now_ns: req.arg(0)? as i64,
                        has_target: req.bool(1)?,
                        trigger_ok: req.bool(2)?,
                        in_range: req.bool(3)?,
                        gate_ok: req.bool(4)?,
                        entity_id: req.i32(5)?,
                        chance: req.i32(6)?,
                        hit_count: req.i32(7)?,
                        delay_ms: req.i32(8)?,
                        mode: req.i32(9)?,
                        sprint_tap_allowed: req.bool(10)?,
                        coordination_active: req.bool(11)?,
                    };
                    let state = self.super_knockback;
                    let rng = &mut self.rng_super_knockback;
                    // Kotlin's `Random.nextInt(100)` is [0, 100); the closure
                    // is invoked only when chance < 100 and the fire point is
                    // actually reached.
                    let (code, next) =
                        super_knockback::tick(state, input, &mut || rng.random_int(0, 99));
                    self.super_knockback = next;
                    Ok(codec::encode_scalar(
                        seq,
                        tick,
                        Module::Player,
                        WireAction::Scalar,
                        code as f64,
                    ))
                }
                _ => Err(Status::BadPayload),
            }
        };
        match catch_unwind(AssertUnwindSafe(run)) {
            Ok(Ok(f)) => {
                self.decisions += 1;
                f
            }
            Ok(Err(s)) => self.bad(frame, s),
            Err(_) => self.degrade(frame),
        }
    }
}

pub fn decode_snapshot(buf: &[u8], len: usize) -> Result<VelocityContext, Status> {
    if len < SLOT_SNAPSHOT {
        return Err(Status::BadPayload);
    }
    let mut r = Reader::new(&buf[..len]);

    let _mode = r.u8()?;
    let is_knockback_hit = r.u8()? != 0;
    let flags = r.u16()?;
    // Position and motion travel as f64. They feed the multiplier directly, so
    // an f32 hop would shift the product by a few ulp and break replay parity
    // without changing anything a human could see.
    let ox = r.f64()?;
    let oy = r.f64()?;
    let oz = r.f64()?;
    let px = r.f64()?;
    let py = r.f64()?;
    let pz = r.f64()?;
    let mx = r.f64()?;
    let my = r.f64()?;
    let mz = r.f64()?;
    let yaw = r.f32()?;
    let pitch = r.f32()?;
    let health = r.f32()?;
    let hurt_time = r.i32()?;
    let hurt_resistant_time = r.i32()?;
    let weapon = WeaponType::from_u8(r.u8()?);
    let has_target = r.u8()? != 0;
    r.skip(2)?;

    let target = if has_target {
        let id = r.i32()?;
        let tx = r.f64()?;
        let ty = r.f64()?;
        let tz = r.f64()?;
        // The target's own velocity and box. Aim's predictive lead reads the
        // first and the multipoint reads the second; a zero motion or a default
        // box would make the core answer differently from the in-process one.
        let tmx = r.f64()?;
        let tmy = r.f64()?;
        let tmz = r.f64()?;
        let distance = r.f32()?;
        let t_health = r.f32()?;
        let t_hurt = r.i32()?;
        let moving_backward = r.u8()? != 0;
        let moving_towards = r.u8()? != 0;
        r.skip(2)?;
        let pos = Vec3::new(tx, ty, tz);
        let mut hb = crate::geom::Hitbox::new(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        hb.min_x = r.f64()?;
        hb.min_y = r.f64()?;
        hb.min_z = r.f64()?;
        hb.max_x = r.f64()?;
        hb.max_y = r.f64()?;
        hb.max_z = r.f64()?;
        Some(TargetState {
            id,
            position: pos,
            motion: Vec3::new(tmx, tmy, tmz),
            hitbox: hb,
            distance,
            health: t_health,
            hurt_time: t_hurt,
            is_moving_backward: moving_backward,
            is_moving_towards_player: moving_towards,
        })
    } else {
        r.skip(4 + 24 + 24 + 4 + 4 + 4 + 1 + 1 + 2 + 48)?;
        None
    };

    let ticks = r.u32()?;
    let packet_id = r.u32()?;
    let _config_revision = r.u32()?;
    r.skip(4)?;

    Ok(VelocityContext {
        original_motion: Vec3::new(ox, oy, oz),
        player: PlayerState {
            position: Vec3::new(px, py, pz),
            motion: Vec3::new(mx, my, mz),
            yaw,
            pitch,
            health,
            hurt_time,
            hurt_resistant_time,
            weapon,
            flags,
            ticks,
        },
        target,
        is_knockback_hit,
        packet_id,
    })
}

pub fn decode_config(buf: &[u8], len: usize) -> Result<VelocityConfig, Status> {
    if len < SLOT_CONFIG {
        return Err(Status::BadPayload);
    }
    let mut r = Reader::new(&buf[..len]);

    let mode = VelocityMode::from_u8(r.u8()?);
    let only_on_hit_frame = r.u8()? != 0;
    let when_facing_enemy_only = r.u8()? != 0;
    let require_weapon = r.u8()? != 0;

    let horizontal_min = r.f32()?;
    let horizontal_max = r.f32()?;
    let vertical_min = r.f32()?;
    let vertical_max = r.f32()?;

    let click_range = r.f32()?;
    let max_angle_difference = r.f32()?;
    let sprint_sector = r.f32()?;

    let reduce_given_reduced = r.i32()?;
    let reduce_given_skipped = r.i32()?;
    let click_burst_min = r.i32()?;
    let click_burst_max = r.i32()?;
    let hurt_time_to_click = r.i32()?;
    let sprint_hold_ticks = r.i32()?;
    let sprint_gate_braced = r.i32()?;
    let sprint_gate_idle = r.i32()?;
    let delay_chance = r.i32()?;
    let max_delay_ticks = r.i32()?;

    let tf = r.u16()?;
    r.skip(2)?;

    let min_distance = r.f32()?;
    let max_distance = r.f32()?;
    let trigger_chance = r.i32()?;
    let delay_ticks = r.i32()?;
    let delay_ms = r.i32()?;
    let look_angle_threshold = r.f32()?;

    Ok(VelocityConfig {
        mode,
        horizontal_min,
        horizontal_max,
        vertical_min,
        vertical_max,
        reduce_given_reduced,
        reduce_given_skipped,
        only_on_hit_frame,
        trigger: TriggerOptions {
            only_ground: tf & (1 << 0) != 0,
            only_air: tf & (1 << 1) != 0,
            only_move: tf & (1 << 2) != 0,
            only_move_forward: tf & (1 << 3) != 0,
            only_move_backward: tf & (1 << 4) != 0,
            only_strafe: tf & (1 << 5) != 0,
            only_when_target_goes_back: tf & (1 << 6) != 0,
            only_when_target_approaches: tf & (1 << 7) != 0,
            on_look: tf & (1 << 8) != 0,
            only_current_view: tf & (1 << 9) != 0,
            disable_on_mine: tf & (1 << 10) != 0,
            only_on_click: tf & (1 << 11) != 0,
            disabled_in_air: tf & (1 << 12) != 0,
            min_distance,
            max_distance,
            chance: trigger_chance,
            delay_ticks,
            delay_ms,
            look_angle_threshold,
        },
        click_burst_min,
        click_burst_max,
        hurt_time_to_click,
        when_facing_enemy_only,
        max_angle_difference,
        click_range,
        sprint_hold_ticks,
        sprint_gate_braced,
        sprint_gate_idle,
        sprint_sector,
        delay_chance,
        max_delay_ticks,
        require_weapon,
    })
}

// ── small configs ──────────────────────────────────────────────────────────
//
// Tap and combat each need only a handful of scalars. Giving them a bespoke
// struct codec would be more code than the fields themselves, so they are
// written inline here next to the dispatcher that reads them.

/// Tap config: `repeat_chance_percent i32`, `lead_ticks i32`.
pub fn decode_tap_config(buf: &[u8], len: usize) -> Result<TapWireConfig, Status> {
    if len < 8 {
        return Err(Status::BadPayload);
    }
    let mut r = Reader::new(&buf[..len]);
    Ok(TapWireConfig {
        repeat_chance_percent: r.i32()?,
        lead_ticks: r.i32()?,
    })
}

/// Combat config, one frame for both state machines:
/// `max_hurt i32, rtt i32, lead i32, predict u8, window i32, chance i32,
///  alpha f64, seed f64, range_mode u8, min f32, max f32, hysteresis f32`.
pub fn decode_combat_config(buf: &[u8], len: usize) -> Result<(PredictorConfig, RangeGateConfig), Status> {
    if len < 50 {
        return Err(Status::BadPayload);
    }
    let mut r = Reader::new(&buf[..len]);
    let predictor = PredictorConfig {
        max_hurt_resistant_time: r.i32()?,
        rtt_ticks: r.i32()?,
        lead_ticks: r.i32()?,
        predict_attacks: r.u8()? != 0,
        predict_window_ticks: r.i32()?,
        predict_chance: r.i32()?,
        interval_alpha: r.f64()?,
        seed_interval_ticks: r.f64()?,
    };
    let mode = match r.u8()? {
        0 => combat::RangeMode::InRange,
        1 => combat::RangeMode::OnEnter,
        _ => return Err(Status::BadPayload),
    };
    let range = RangeGateConfig {
        mode,
        min_distance: r.f32()?,
        max_distance: r.f32()?,
        hysteresis: r.f32()?,
    };
    Ok((predictor, range))
}

/// `released` is the low byte of the delay queue that came due this tick — the
/// stub needs it because a released packet is a *second* decision for the same
/// tick and must be applied too. 0 means nothing was released.
pub fn encode_decision(d: &Decision, seq: u32, tick: u32, released: u8) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, Module::Velocity);
    let mut buf = [0u8; SLOT_DECISION];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(match d.action {
            Action::NoOp => WireAction::NoOp as u8,
            Action::Pass => WireAction::Pass as u8,
            Action::Modify => WireAction::Modify as u8,
            Action::ClickBurst => WireAction::ClickBurst as u8,
            Action::Cancel => WireAction::Cancel as u8,
        });
        w.u8(d.reason);
        w.u16(released as u16);
        w.f64(d.motion.x);
        w.f64(d.motion.y);
        w.f64(d.motion.z);
        w.i32(d.target_id);
        w.i32(d.clicks);
        w.u32(tick);
    }
    f.payload[..SLOT_DECISION].copy_from_slice(&buf);
    f.set_len(SLOT_DECISION);
    f
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_snapshot(motion: [f64; 3], hurt_time: i32, flags: u16) -> (Vec<u8>, usize) {
        let mut f = Frame::new(Kind::Snapshot, 1, 100, Module::Velocity);
        {
            let p = f.payload_mut();
            let mut w = Writer::new(p);
            w.u8(0); // mode
            w.u8(1); // is_knockback_hit
            w.u16(flags);
            w.f64(motion[0]);
            w.f64(motion[1]);
            w.f64(motion[2]);
            // player position
            w.f64(0.0);
            w.f64(64.0);
            w.f64(0.0);
            // player motion
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f32(0.0); // yaw
            w.f32(0.0); // pitch
            w.f32(20.0); // health
            w.i32(hurt_time);
            w.i32(hurt_time);
            w.u8(1); // weapon
            w.u8(0); // has_target
            w.u16(0);
            // the target block is always present, zeroed: 116 bytes
            for _ in 0..29 {
                w.u32(0);
            }
            w.u32(100); // ticks
            w.u32(7); // packet id
            w.u32(0); // config revision
            w.u32(0);
            let n = w.pos();
            assert_eq!(n, SLOT_SNAPSHOT, "snapshot layout drifted from the wire table");
            f.set_len(n);
        }
        let bytes = f.encode();
        (bytes.to_vec(), SLOT_SNAPSHOT)
    }

    /// A player-family request: the velocity snapshot with an op byte and five
    /// f64 arguments appended, which is exactly what `decode_generic` expects.
    fn eagle_frame(seq: u32, on_edge: bool, settings_met: bool, sneak_held: bool, lo: i32, hi: i32) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Player);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op::PLAYER_EAGLE_TICK);
            w.u8(5);
            w.f64(on_edge as u8 as f64);
            w.f64(settings_met as u8 as f64);
            w.f64(sneak_held as u8 as f64);
            w.f64(lo as f64);
            w.f64(hi as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    /// The scalar answer sits at offset 4 in the decision payload (after
    /// action, reason and flags), stored as f64 — reading its low byte would
    /// only ever return zero for the small integers packed here.
    fn eagle_flags(f: &Frame) -> u32 {
        assert_eq!(f.header.kind, Kind::Decision, "expected a decision, got {:?}", f.header.kind);
        let mut r = Reader::new(&f.payload[4..12]);
        let v = r.f64().expect("scalar answer");
        v as u32
    }

    #[test]
    fn eagle_presses_on_the_first_edge_and_keeps_ownership() {
        let mut s = Session::new(1, 42);
        let f = s.handle(&eagle_frame(1, true, true, false, 3, 3));
        let flags = eagle_flags(&f);
        assert_eq!(flags & 0b001, 1, "press");
        assert_eq!(flags & 0b100, 4, "the core owns the sneak now");
        assert_eq!(s.eagle.unsneak_delay_ticks, 3);

        // Still on the edge: no second press, delay unchanged.
        let f = s.handle(&eagle_frame(2, true, true, false, 3, 3));
        assert_eq!(eagle_flags(&f) & 0b001, 0);
        assert_eq!(s.eagle.unsneak_delay_ticks, 3);
    }

    #[test]
    fn eagle_counts_the_delay_down_off_the_edge_and_releases() {
        let mut s = Session::new(1, 42);
        s.handle(&eagle_frame(1, true, true, false, 1, 1));
        assert_eq!(s.eagle.unsneak_delay_ticks, 1);

        let f = s.handle(&eagle_frame(2, false, true, false, 1, 1));
        assert_eq!(eagle_flags(&f) & 0b010, 0, "still holding");
        assert_eq!(s.eagle.unsneak_delay_ticks, 0);

        let f = s.handle(&eagle_frame(3, false, true, false, 1, 1));
        assert_eq!(eagle_flags(&f) & 0b010, 2, "release");
        assert!(!s.eagle.auto_sneak_active);
    }

    #[test]
    fn eagle_reset_forgets_a_held_sneak() {
        let mut s = Session::new(1, 42);
        s.handle(&eagle_frame(1, true, true, false, 3, 3));
        assert!(s.eagle.auto_sneak_active);

        let mut r = Frame::new(Kind::Reset, 2, 100, Module::Player);
        r.set_len(0);
        s.handle(&r);
        assert!(!s.eagle.auto_sneak_active);
        assert_eq!(s.eagle.unsneak_delay_ticks, 0);
    }

    #[test]
    fn eagle_never_rolls_while_idle() {
        // Same seed, one session idles for five ticks first: the delay armed on
        // the first edge must still be the first draw of its stream. If an idle
        // tick consumed a value the two would disagree, and the difference would
        // only show up as a slightly wrong hold a few hundred ticks later.
        let mut a = Session::new(1, 7);
        for i in 0..5 {
            a.handle(&eagle_frame(i, false, true, false, 1, 5));
        }
        a.handle(&eagle_frame(9, true, true, false, 1, 5));

        let mut b = Session::new(1, 7);
        b.handle(&eagle_frame(0, true, true, false, 1, 5));

        assert_eq!(a.eagle.unsneak_delay_ticks, b.eagle.unsneak_delay_ticks);
        assert_eq!(a.rng_player.next_u32(), b.rng_player.next_u32());
    }

    /// An Eagle AutoPlace request: six f64 args after the op byte.
    fn eagle_place_frame(
        seq: u32,
        gates_open: bool,
        pitch_ok: bool,
        bridge_assist: bool,
        max_wait: i32,
        cd_min: i32,
        cd_max: i32,
    ) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Player);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op::PLAYER_EAGLE_PLACE);
            w.u8(6);
            w.f64(gates_open as u8 as f64);
            w.f64(pitch_ok as u8 as f64);
            w.f64(bridge_assist as u8 as f64);
            w.f64(max_wait as f64);
            w.f64(cd_min as f64);
            w.f64(cd_max as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    fn place_step(f: &Frame) -> u32 {
        eagle_flags(f) // same scalar layout: the step code sits at offset 4
    }

    #[test]
    fn eagle_place_round_trips_over_the_wire() {
        // Full chain: decode_generic → on_player dispatch → eagle_place::tick →
        // encode_scalar. A pitch already at the angle clicks and arms a cooldown.
        let mut s = Session::new(1, 42);
        let f = s.handle(&eagle_place_frame(1, true, true, false, 3, 1, 3));
        assert_eq!(place_step(&f), 2, "CLICK");
        assert!(s.eagle_place.cooldown_ticks >= 1 && s.eagle_place.cooldown_ticks <= 3);

        // Cooling down: NONE, and the cooldown counts down without a new draw.
        let cd = s.eagle_place.cooldown_ticks;
        let f = s.handle(&eagle_place_frame(2, true, false, false, 3, 1, 3));
        assert_eq!(place_step(&f), 0, "NONE while cooling");
        assert_eq!(s.eagle_place.cooldown_ticks, cd - 1);

        // A closed gate cancels a pending aim and forgets the cooldown. Start
        // from a fresh session so no cooldown from the click above is in the way.
        let mut s2 = Session::new(1, 42);
        let f = s2.handle(&eagle_place_frame(1, true, false, false, 3, 1, 3));
        assert_eq!(place_step(&f), 1, "AIM");
        assert!(s2.eagle_place.aiming);
        let f = s2.handle(&eagle_place_frame(2, false, false, false, 3, 1, 3));
        assert_eq!(place_step(&f), 0);
        assert_eq!(s2.eagle_place, eagle_place::State::default());
    }

    #[test]
    fn eagle_place_reset_forgets_a_pending_aim() {
        let mut s = Session::new(1, 42);
        s.handle(&eagle_place_frame(1, true, false, false, 3, 1, 3));
        assert!(s.eagle_place.aiming);

        let mut r = Frame::new(Kind::Reset, 2, 100, Module::Player);
        r.set_len(0);
        s.handle(&r);
        assert_eq!(s.eagle_place, eagle_place::State::default());
    }

    #[test]
    fn eagle_place_has_its_own_rng_stream() {
        // A sneak edge press must not move the placement cooldown stream, and
        // vice versa: the two are separate decisions on separate streams.
        let mut a = Session::new(1, 7);
        a.handle(&eagle_place_frame(1, true, true, false, 3, 1, 5)); // one placement draw
        let mut b = Session::new(1, 7);
        b.handle(&eagle_frame(1, true, true, false, 1, 5)); // one sneak draw
        b.handle(&eagle_place_frame(2, true, true, false, 3, 1, 5));

        assert_eq!(
            a.eagle_place.cooldown_ticks, b.eagle_place.cooldown_ticks,
            "a sneak draw must not shift the placement stream"
        );
        assert_eq!(a.rng_eagle_place.next_u32(), b.rng_eagle_place.next_u32());
    }

    /// A SprintReset request: ten f64 args after the op byte.
    fn sprint_reset_frame(
        seq: u32,
        now_ns: i64,
        has_target: bool,
        in_range: bool,
        gate_ok: bool,
        is_hurt: bool,
        entity_id: i32,
        chance: i32,
        hit_count: i32,
        delay_ms: i32,
        mode: i32,
    ) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Player);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op::PLAYER_SPRINT_RESET);
            w.u8(10);
            w.f64(now_ns as f64);
            w.f64(has_target as u8 as f64);
            w.f64(in_range as u8 as f64);
            w.f64(gate_ok as u8 as f64);
            w.f64(is_hurt as u8 as f64);
            w.f64(entity_id as f64);
            w.f64(chance as f64);
            w.f64(hit_count as f64);
            w.f64(delay_ms as f64);
            w.f64(mode as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    fn sprint_step(f: &Frame) -> u32 {
        eagle_flags(f) // same scalar layout: the step code sits at offset 4
    }

    #[test]
    fn sprint_reset_round_trips_over_the_wire() {
        // Full chain: decode_generic → on_player dispatch → sprint_reset::tick →
        // encode_scalar. chance=100 skips the roll, so a fresh rising edge with
        // delay=0 sends immediately.
        let mut s = Session::new(1, 42);
        // tick 1: hurt low (no edge); tick 2: rising edge -> SEND_NOSTOP.
        s.handle(&sprint_reset_frame(1, 1_000_000_000, true, true, true, false, 7, 100, 1, 0, 0));
        let f = s.handle(&sprint_reset_frame(2, 1_050_000_000, true, true, true, true, 7, 100, 1, 0, 0));
        assert_eq!(sprint_step(&f), 1, "SEND_NOSTOP on the rising edge");

        // A delayed send arms on the edge, then fires at its deadline. Start a
        // fresh session and use a new target id so the edge is fresh.
        let mut s2 = Session::new(2, 42);
        let armed = s2.handle(&sprint_reset_frame(1, 2_000_000_000, true, true, true, true, 9, 100, 1, 100, 1));
        assert_eq!(sprint_step(&armed), 0, "armed, nothing sent yet");
        assert!(s2.sprint_reset.sending);
        let before = s2.handle(&sprint_reset_frame(2, 2_050_000_000, true, true, true, true, 9, 100, 1, 100, 1));
        assert_eq!(sprint_step(&before), 0, "deadline not reached");
        let fired = s2.handle(&sprint_reset_frame(3, 2_100_000_000, true, true, true, true, 9, 100, 1, 100, 1));
        assert_eq!(sprint_step(&fired), 2, "SEND_SILENT fires at the deadline");
        assert!(!s2.sprint_reset.sending);
    }

    #[test]
    fn sprint_reset_no_target_forgets_the_edge() {
        let mut s = Session::new(1, 42);
        s.handle(&sprint_reset_frame(1, 1_000_000_000, true, true, true, true, 7, 100, 3, 0, 0));
        assert!(s.sprint_reset.prev_hurt);
        s.handle(&sprint_reset_frame(2, 1_050_000_000, false, false, false, false, 7, 100, 3, 0, 0));
        assert!(!s.sprint_reset.prev_hurt);
        assert_eq!(s.sprint_reset.last_target_id, -1);
    }

    #[test]
    fn sprint_reset_has_its_own_rng_stream() {
        // An eagle sneak draw must not shift the sprint-reset chance stream.
        let mut a = Session::new(1, 7);
        a.handle(&sprint_reset_frame(1, 1_000_000_000, true, true, true, false, 7, 50, 1, 0, 0));
        a.handle(&sprint_reset_frame(2, 1_050_000_000, true, true, true, true, 7, 50, 1, 0, 0));

        let mut b = Session::new(1, 7);
        b.handle(&eagle_frame(1, true, true, false, 1, 5)); // one sneak draw (rng_player)
        b.handle(&sprint_reset_frame(2, 1_000_000_000, true, true, true, false, 7, 50, 1, 0, 0));
        b.handle(&sprint_reset_frame(3, 1_050_000_000, true, true, true, true, 7, 50, 1, 0, 0));

        assert_eq!(a.rng_sprint_reset.next_u32(), b.rng_sprint_reset.next_u32());
    }

    /// A JumpReset knock request: seven f64 args after the op byte.
    fn jump_knock_frame(
        seq: u32,
        gates_open: bool,
        now_ns: i64,
        chance: i32,
        cooldown_mode: i32,
        ticks_until: i32,
        hits_until: i32,
        delay_ms: i32,
    ) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Player);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op::PLAYER_JUMP_KNOCK);
            w.u8(7);
            w.f64(gates_open as u8 as f64);
            w.f64(now_ns as f64);
            w.f64(chance as f64);
            w.f64(cooldown_mode as f64);
            w.f64(ticks_until as f64);
            w.f64(hits_until as f64);
            w.f64(delay_ms as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    /// A JumpReset pump request: three f64 args after the op byte.
    fn jump_pump_frame(seq: u32, now_ns: i64, cooldown_mode: i32, ticks_until: i32) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Player);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op::PLAYER_JUMP_PUMP);
            w.u8(3);
            w.f64(now_ns as f64);
            w.f64(cooldown_mode as f64);
            w.f64(ticks_until as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    fn jump_step(f: &Frame) -> u32 {
        eagle_flags(f) // same scalar layout: the step code sits at offset 4
    }

    #[test]
    fn jump_reset_knock_and_pump_round_trip_over_the_wire() {
        // Full chain: decode_generic → on_player dispatch → jump_reset::knock /
        // pump → encode_scalar. chance=100 skips the draw; ReceivedHits with
        // hits_until=1 reaches the fire point immediately.
        let mut s = Session::new(1, 42);
        let fired = s.handle(&jump_knock_frame(1, true, 1_000_000_000, 100, 1, 0, 1, 0));
        assert_eq!(jump_step(&fired), 1, "JUMP now over the wire");
        assert_eq!(s.jump_reset.cooldown_counter, 0);

        // A delayed jump arms on knock and fires at its deadline on pump. Both
        // halves share the same session state.
        let mut s2 = Session::new(2, 42);
        let armed = s2.handle(&jump_knock_frame(1, true, 1_000_000_000, 100, 1, 0, 1, 100));
        assert_eq!(jump_step(&armed), 0, "armed, nothing yet");
        assert!(s2.jump_reset.pending);
        assert_eq!(
            s2.jump_reset.deadline_ns,
            1_000_000_000 + 100 * 1_000_000
        );

        let before = s2.handle(&jump_pump_frame(2, 1_050_000_000, 0, 4));
        assert_eq!(jump_step(&before), 0, "deadline not reached");
        let fired = s2.handle(&jump_pump_frame(3, 1_100_000_000, 0, 4));
        assert_eq!(jump_step(&fired), 1, "JUMP at the deadline over the wire");
        assert!(!s2.jump_reset.pending);
    }

    #[test]
    fn jump_reset_closed_gates_and_pumps_never_draw() {
        // A closed gate returns before the roll; pumps never roll at all. Both
        // must leave the RNG stream exactly where a fresh session has it.
        let mut a = Session::new(1, 7);
        a.handle(&jump_knock_frame(1, false, 1_000_000_000, 40, 1, 0, 1, 0));
        // ReceivedHits mode, so the pump advances no counter either. This
        // test is about the stream, not the clock: Ticks-mode pumping is
        // covered by jump_reset_ticks_cooldown_advances_on_pump_only below.
        a.handle(&jump_pump_frame(2, 1_050_000_000, 1, 4));
        assert_eq!(a.jump_reset, jump_reset::State::new());

        let mut b = Session::new(1, 7);
        assert_eq!(
            a.rng_jump_reset.next_u32(),
            b.rng_jump_reset.next_u32(),
            "a closed gate or a pump must not advance the stream"
        );
    }

    #[test]
    fn jump_reset_ticks_cooldown_advances_on_pump_only() {
        let mut s = Session::new(1, 42);
        // Fresh state in Ticks mode: a knock is blocked until four pumps.
        let blocked = s.handle(&jump_knock_frame(1, true, 1_000_000_000, 100, 0, 4, 2, 0));
        assert_eq!(jump_step(&blocked), 0, "Ticks cooldown not met yet");
        for i in 0..4 {
            s.handle(&jump_pump_frame(
                i + 2,
                1_000_000_000 + (i as i64 + 1) * 50_000_000,
                0,
                4,
            ));
        }
        assert_eq!(s.jump_reset.cooldown_counter, 4);
        let fired = s.handle(&jump_knock_frame(6, true, 1_250_000_000, 100, 0, 4, 2, 0));
        assert_eq!(jump_step(&fired), 1, "Ticks cooldown met on the wire");
        assert_eq!(s.jump_reset.cooldown_counter, 0);
    }

    #[test]
    fn jump_reset_has_its_own_rng_stream() {
        // A sneak draw must not shift the JumpReset chance stream, and vice
        // versa: the two are separate decisions on separate streams.
        let mut a = Session::new(1, 7);
        a.handle(&jump_knock_frame(1, true, 1_000_000_000, 50, 1, 0, 1, 0));

        let mut b = Session::new(1, 7);
        b.handle(&eagle_frame(1, true, true, false, 1, 5)); // one sneak draw (rng_player)
        b.handle(&jump_knock_frame(2, true, 1_000_000_000, 50, 1, 0, 1, 0));

        assert_eq!(a.rng_jump_reset.next_u32(), b.rng_jump_reset.next_u32());
    }

    /// A SuperKnockback request: twelve f64 args after the op byte.
    #[allow(clippy::too_many_arguments)]
    fn super_knockback_frame(
        seq: u32,
        now_ns: i64,
        has_target: bool,
        trigger_ok: bool,
        in_range: bool,
        gate_ok: bool,
        entity_id: i32,
        chance: i32,
        hit_count: i32,
        delay_ms: i32,
        mode: i32,
        sprint_tap_allowed: bool,
        coordination_active: bool,
    ) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Player);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op::PLAYER_SUPER_KNOCKBACK);
            w.u8(12);
            w.f64(now_ns as f64);
            w.f64(has_target as u8 as f64);
            w.f64(trigger_ok as u8 as f64);
            w.f64(in_range as u8 as f64);
            w.f64(gate_ok as u8 as f64);
            w.f64(entity_id as f64);
            w.f64(chance as f64);
            w.f64(hit_count as f64);
            w.f64(delay_ms as f64);
            w.f64(mode as f64);
            w.f64(sprint_tap_allowed as u8 as f64);
            w.f64(coordination_active as u8 as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    #[test]
    fn super_knockback_round_trips_over_the_wire() {
        // Full chain: decode_generic → on_player dispatch →
        // super_knockback::tick → encode_scalar. chance=100 skips the draw;
        // Old's fire step (5) sits in the high nibble.
        let mut s = Session::new(1, 42);
        let f = s.handle(&super_knockback_frame(
            1, 1_000_000_000, true, true, true, true, 7, 100, 1, 0, 1, false, false,
        ));
        assert_eq!(jump_step(&f), 80, "OLD fire over the wire");
        assert_eq!(s.super_knockback.hit_counter, 0);

        // A closed gate neither counts the hit nor draws.
        let f = s.handle(&super_knockback_frame(
            2, 1_050_000_000, true, true, true, false, 7, 50, 1, 0, 1, false, false,
        ));
        assert_eq!(jump_step(&f), 0);
        assert_eq!(s.super_knockback.hit_counter, 0);
    }

    #[test]
    fn super_knockback_delay_and_sprint_tap_share_one_state() {
        let mut s = Session::new(2, 42);
        // SprintTap arms the force sequence (fire step 4 in the high nibble).
        let armed = s.handle(&super_knockback_frame(
            1, 1_000_000_000, true, true, true, true, 7, 100, 1, 0, 0, true, false,
        ));
        assert_eq!(jump_step(&armed), 64);
        assert_eq!(s.super_knockback.phase, super_knockback::Phase::SprintTap);

        // The next tick advances the SprintTap machine in the low nibble while
        // the trigger stays quiet: two independent writes, one scalar answer.
        let advancing = s.handle(&super_knockback_frame(
            2, 1_050_000_000, true, false, true, true, 7, 100, 1, 0, 0, false, false,
        ));
        assert_eq!(jump_step(&advancing), 1, "BREAK in the low nibble");

        // A delayed SneakPacket fire arms on the hit and fires at its deadline.
        let delayed = s.handle(&super_knockback_frame(
            3, 1_100_000_000, true, true, true, true, 7, 100, 1, 100, 2, false, false,
        ));
        assert_eq!(jump_step(&delayed), 0);
        assert_eq!(s.super_knockback.phase, super_knockback::Phase::Delay);
        let before = s.handle(&super_knockback_frame(
            4, 1_150_000_000, true, false, true, true, 7, 100, 1, 100, 2, false, false,
        ));
        assert_eq!(jump_step(&before), 0, "deadline not reached");
        let fired = s.handle(&super_knockback_frame(
            5, 1_200_000_000, true, false, true, true, 7, 100, 1, 100, 2, false, false,
        ));
        assert_eq!(jump_step(&fired), 96, "SNEAK_PACKET fire at the deadline");
        assert_eq!(s.super_knockback.phase, super_knockback::Phase::Idle);
    }

    #[test]
    fn super_knockback_has_its_own_rng_stream() {
        let mut a = Session::new(1, 7);
        a.handle(&super_knockback_frame(
            1, 1_000_000_000, true, true, true, true, 7, 50, 1, 0, 1, false, false,
        ));
        let mut b = Session::new(1, 7);
        b.handle(&eagle_frame(1, true, true, false, 1, 5)); // one sneak draw (rng_player)
        b.handle(&super_knockback_frame(
            2, 1_000_000_000, true, true, true, true, 7, 50, 1, 0, 1, false, false,
        ));
        assert_eq!(
            a.rng_super_knockback.next_u32(),
            b.rng_super_knockback.next_u32()
        );
    }

    /// A WTap / STap range-sampling request: seven f64 args after the op byte.
    #[allow(clippy::too_many_arguments)]
    fn tap_sample_frame(
        seq: u32,
        op: u8,
        kind: i32,
        pd_min: i32,
        pd_max: i32,
        ac_min: i32,
        ac_max: i32,
        oe_min: i32,
        oe_max: i32,
    ) -> Frame {
        let (snap, len) = write_snapshot([0.0; 3], 0, 0);
        let mut f = Frame::new(Kind::Snapshot, seq, 100, Module::Tap);
        let total = {
            let p = f.payload_mut();
            p[..len].copy_from_slice(&snap[32..32 + len]);
            let mut w = Writer::new(&mut p[len..]);
            w.u8(op);
            w.u8(7);
            w.f64(kind as f64);
            w.f64(pd_min as f64);
            w.f64(pd_max as f64);
            w.f64(ac_min as f64);
            w.f64(ac_max as f64);
            w.f64(oe_min as f64);
            w.f64(oe_max as f64);
            len + w.pos()
        };
        f.set_len(total);
        f
    }

    #[test]
    fn tap_sample_round_trips_over_the_wire() {
        // Full chain: decode_generic → on_tap dispatch → tap_sample::sample →
        // encode_scalar. Degenerate ranges are deterministic (no RNG draw), so
        // the packed code is stable across the two different generators.
        let mut s = Session::new(1, 42);
        let f = s.handle(&tap_sample_frame(1, op::TAP_WTAP_SAMPLE, 0, 0, 0, 40, 40, 1, 1));
        assert_eq!(jump_step(&f), 40 * 1024, "post-delay 0, duration 40");

        let a = s.handle(&tap_sample_frame(2, op::TAP_WTAP_SAMPLE, 1, 0, 0, 25, 25, 1, 1));
        assert_eq!(jump_step(&a), 25 * 1024, "ACTION draws only the duration");

        let i = s.handle(&tap_sample_frame(3, op::TAP_STAP_SAMPLE, 2, 0, 0, 0, 0, 3, 3));
        assert_eq!(jump_step(&i), 3 * 1024 * 1024, "INIT seeds the threshold");
    }

    #[test]
    fn wtap_and_stap_own_independent_rng_streams() {
        let mut a = Session::new(1, 7);
        a.handle(&tap_sample_frame(1, op::TAP_WTAP_SAMPLE, 0, 0, 60, 1, 80, 1, 4));
        a.handle(&tap_sample_frame(2, op::TAP_WTAP_SAMPLE, 0, 0, 60, 1, 80, 1, 4));

        let mut b = Session::new(1, 7);
        // An STap draw interleaved between two WTap draws must not shift the
        // WTap stream — that is the whole point of two separate Pcg32s.
        b.handle(&tap_sample_frame(1, op::TAP_STAP_SAMPLE, 0, 0, 60, 1, 80, 1, 4));
        b.handle(&tap_sample_frame(2, op::TAP_WTAP_SAMPLE, 0, 0, 60, 1, 80, 1, 4));
        b.handle(&tap_sample_frame(3, op::TAP_WTAP_SAMPLE, 0, 0, 60, 1, 80, 1, 4));

        assert_eq!(a.rng_wtap.next_u32(), b.rng_wtap.next_u32(), "STap must not shift the WTap stream");
        assert_ne!(a.rng_stap.next_u32(), b.rng_stap.next_u32(), "STap drew once in b");
    }

    #[test]
    fn snapshot_roundtrip_preserves_fields() {
        let (buf, len) = write_snapshot([0.5, 0.4, -0.5], 8, F_ON_GROUND | F_MOVING);
        let c = decode_snapshot(&buf[32..], len).unwrap();
        assert_eq!(c.original_motion.x, 0.5);
        assert_eq!(c.player.hurt_time, 8);
        assert!(c.player.on_ground());
        assert!(c.player.is_moving());
        assert!(c.target.is_none());
    }

    #[test]
    fn motion_survives_without_precision_loss() {
        // A value that is not representable in f32: if the layout ever regresses
        // to f32, this comes back changed and the replay parity test fails at
        // tick 0 with no obvious cause.
        let exact = 0.123_456_789_012_345_f64;
        let (buf, len) = write_snapshot([exact, 0.0, 0.0], 0, 0);
        let c = decode_snapshot(&buf[32..], len).unwrap();
        assert_eq!(c.original_motion.x.to_bits(), exact.to_bits());
    }

    #[test]
    fn short_snapshot_is_rejected_not_panicked() {
        let buf = [0u8; 256];
        assert_eq!(decode_snapshot(&buf, 10).unwrap_err(), Status::BadPayload);
    }

    #[test]
    fn panic_inside_strategy_degrades_but_keeps_process_alive() {
        // A config that makes the strategy divide by zero would panic; we cannot
        // inject one, so this test pins the recovery path directly.
        let mut s = Session::new(1, 42);
        s.velocity = velocity::State::new();
        let d = Decision::noop(Reason::Panic as u8);
        let f = encode_decision(&d, 1, 1, 0).with_status(Status::Degraded);
        assert_eq!(f.header.status, Status::Degraded);
        assert_eq!(f.header.kind, Kind::Decision);
    }

    #[test]
    fn config_roundtrip_preserves_trigger_flags() {
        let mut buf = [0u8; SLOT_CONFIG];
        {
            let mut w = Writer::new(&mut buf);
            w.u8(0);
            w.u8(0);
            w.u8(0);
            w.u8(0);
            for _ in 0..7 {
                w.f32(0.5);
            }
            for _ in 0..10 {
                w.i32(0);
            }
            w.u16((1 << 0) | (1 << 8));
            w.u16(0);
            w.f32(0.0);
            w.f32(3.0);
            w.i32(100);
            w.i32(0);
            w.i32(0);
            w.f32(30.0);
        }
        let c = decode_config(&buf, SLOT_CONFIG).unwrap();
        assert!(c.trigger.only_ground);
        assert!(c.trigger.on_look);
        assert!(!c.trigger.only_air);
        assert_eq!(c.trigger.max_distance, 3.0);
    }
}
