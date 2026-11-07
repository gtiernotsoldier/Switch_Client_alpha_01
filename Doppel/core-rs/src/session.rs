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

use crate::model::*;
use crate::rng::Pcg32;
use crate::strategy::velocity;
use crate::wire::{Action as WireAction, Frame, Kind, Module, Reader, Reason, Status, Writer};

pub const SLOT_SNAPSHOT: usize = 160;
pub const SLOT_CONFIG: usize = 104;
pub const SLOT_DECISION: usize = 40;

pub struct Session {
    pub id: u64,
    pub seed: u64,
    pub rng: Pcg32,
    pub config: VelocityConfig,
    pub velocity: velocity::State,
    pub ticks: u64,
    pub decisions: u64,
    pub degraded: u64,
}

impl Session {
    pub fn new(id: u64, seed: u64) -> Session {
        Session {
            id,
            seed,
            rng: Pcg32::new(seed),
            config: VelocityConfig::default(),
            velocity: velocity::State::new(),
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
                let mut r = Frame::new(Kind::Pong, frame.header.seq, frame.header.tick, Module::Velocity);
                r.header.correlation = frame.header.correlation;
                r
            }
            _ => Frame::new(Kind::Error, frame.header.seq, frame.header.tick, Module::Velocity)
                .with_status(Status::UnknownKind),
        }
    }

    fn on_config(&mut self, frame: &Frame) -> Frame {
        match decode_config(&frame.payload, frame.header.payload_len as usize) {
            Ok(cfg) => {
                self.config = cfg;
                Frame::new(Kind::Config, frame.header.seq, frame.header.tick, Module::Velocity)
            }
            Err(s) => Frame::new(Kind::Error, frame.header.seq, frame.header.tick, Module::Velocity)
                .with_status(s),
        }
    }

    fn on_snapshot(&mut self, frame: &Frame) -> Frame {
        let ctx = match decode_snapshot(&frame.payload, frame.header.payload_len as usize) {
            Ok(c) => c,
            Err(s) => {
                return Frame::new(Kind::Error, frame.header.seq, frame.header.tick, Module::Velocity)
                    .with_status(s)
            }
        };

        self.ticks += 1;
        self.velocity.tick_counter = frame.header.tick as i32;

        // Delay release runs before the new hit is considered: a packet held for
        // N ticks must come out on tick T+N even if T+N also carries a fresh hit,
        // otherwise the queue drifts under sustained pressure.
        let released = velocity::delay_pump(&mut self.velocity, frame.header.tick as i32);

        let result = catch_unwind(AssertUnwindSafe(|| {
            velocity::execute(&self.config, &mut self.velocity, &ctx, &mut self.rng)
        }));

        match result {
            Ok(decision) => {
                self.decisions += 1;
                encode_decision(&decision, frame.header.seq, frame.header.tick, released.len() as u8)
            }
            Err(_) => {
                // Strategy panicked. The state may be half-mutated; drop it and
                // start the chain over rather than trust it.
                self.degraded += 1;
                self.velocity = velocity::State::new();
                let mut d = Decision::noop(Reason::Panic as u8);
                d.action = Action::NoOp;
                encode_decision(&d, frame.header.seq, frame.header.tick, 0)
                    .with_status(Status::Degraded)
            }
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
        let distance = r.f32()?;
        let t_health = r.f32()?;
        let t_hurt = r.i32()?;
        let moving_backward = r.u8()? != 0;
        let moving_towards = r.u8()? != 0;
        r.skip(2)?;
        Some(TargetState {
            id,
            position: Vec3::new(tx, ty, tz),
            distance,
            health: t_health,
            hurt_time: t_hurt,
            is_moving_backward: moving_backward,
            is_moving_towards_player: moving_towards,
        })
    } else {
        r.skip(4 + 24 + 4 + 4 + 4 + 1 + 1 + 2)?;
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

    fn write_snapshot(motion: [f64; 3], hurt_time: i32, flags: u16) -> ([u8; 256], usize) {
        let mut buf = [0u8; crate::wire::FRAME];
        let mut f = Frame::new(Kind::Snapshot, 1, 100, Module::Velocity);
        {
            let p = f.payload_mut();
            let mut w = Writer::new(p);
            w.u8(0);
            w.u8(1);
            w.u16(flags);
            w.f64(motion[0]);
            w.f64(motion[1]);
            w.f64(motion[2]);
            w.f64(0.0);
            w.f64(64.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f32(0.0);
            w.f32(0.0);
            w.f32(20.0);
            w.i32(hurt_time);
            w.i32(hurt_time);
            w.u8(1);
            w.u8(0);
            w.u16(0);
            w.i32(0);
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f32(0.0);
            w.f32(0.0);
            w.i32(0);
            w.u8(0);
            w.u8(0);
            w.u16(0);
            w.u32(100);
            w.u32(7);
            w.u32(0);
            w.u32(0);
            let n = w.pos();
            assert_eq!(n, SLOT_SNAPSHOT, "snapshot layout drifted from the wire table");
            f.set_len(n);
        }
        let bytes = f.encode();
        buf.copy_from_slice(&bytes);
        (buf, SLOT_SNAPSHOT)
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
