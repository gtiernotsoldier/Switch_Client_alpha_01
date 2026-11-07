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
use crate::strategy::click::{self, ClickConfig, ClickState, CooldownClickConfig, CritState};
use crate::strategy::combat::{self, BlockArbiterState, PredictorConfig, PredictorState, RangeGateConfig, RangeGateState};
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
    pub rng_combat: Pcg32,
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
            rng_combat: Pcg32::new(seed),
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
            ticks: 0,
            decisions: 0,
            degraded: 0,
        }
    }

    pub fn handle(&mut self, frame: &Frame) -> Frame {
        match frame.header.kind {
            Kind::Snapshot => self.on_snapshot(frame),
            Kind::Config => self.on_config(frame),
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
            // the request, so there is nothing to latch.
            Module::Movement | Module::Reach => Ok(()),
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
                    Ok(codec::encode_scalar(seq, tick, Module::Tap, WireAction::Scalar, v))
                }
                op::TAP_POST_DELAY => {
                    self.tap.begin_post_delay(req.arg(0)? as i64, req.i32(1)?);
                    Ok(codec::encode_scalar(seq, tick, Module::Tap, WireAction::NoOp, 0.0))
                }
                op::TAP_BEGIN => {
                    self.tap.begin_tap(req.arg(0)? as i64, req.i32(1)?);
                    Ok(codec::encode_scalar(seq, tick, Module::Tap, WireAction::NoOp, 0.0))
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
                    Ok(codec::encode_verdict(seq, tick, v.block, v.score, 0))
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
                    Ok(codec::encode_verdict(seq, tick, d.block, 0.0, d.effective_hurt))
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
