//! Port of `io.doppel.core.strategy.tap`.

use crate::model::{TargetState, Vec3};
use crate::rng::Pcg32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Left,
    Right,
}

impl Side {
    pub fn flipped(self) -> Side {
        match self {
            Side::Left => Side::Right,
            Side::Right => Side::Left,
        }
    }
}

/// Wrap an angle into [-180, 180).
///
/// Single-pass modulo, not a subtraction loop. The historical form — still in
/// the Kotlin twin — subtracts 360 in a `while` loop, and a loop is a hang,
/// not merely a wrong answer, on inputs where `a -= 360.0` cannot move the
/// value: ±Inf, or any finite magnitude above ~1e17 where 360 sits below one
/// ulp. This shape reaches the core through the untrusted `TAP_WRAP` op, so a
/// bad frame used to be able to freeze the whole process outside the
/// `catch_unwind` safety net. `fmod` is exact, so for every finite input this
/// returns bit-for-bit what the loop returned: 540 lands on -180 (plain `%`
/// alone would stop at 180), -540 → -180, 180 → -180, NaN → NaN. ±Inf now
/// yields NaN instead of a hung core.
pub fn wrap180(angle: f32) -> f32 {
    let mut a = angle % 360.0;
    if a >= 180.0 {
        a -= 360.0;
    }
    if a < -180.0 {
        a += 360.0;
    }
    a
}

/// Which strafe key leads toward the target's linearly predicted position.
pub fn chase_side(yaw: f32, player_pos: Vec3, target: &TargetState, lead_ticks: i32) -> Side {
    let k = lead_ticks as f64 / 20.0;
    let ax = target.position.x + target.motion.x * k;
    let az = target.position.z + target.motion.z * k;
    let dx = ax - player_pos.x;
    let dz = az - player_pos.z;
    let bearing = crate::geom::to_degrees(crate::trig::atan2(-dx, dz)) as f32;
    let delta = wrap180(bearing - yaw);
    if delta < 0.0 {
        Side::Left
    } else {
        Side::Right
    }
}

pub fn seven_tap_side(last_side: Side, repeat_chance_percent: i32, rng: &mut Pcg32) -> Side {
    if repeat_chance_percent > 0 && (rng.next_int(100) as i32) < repeat_chance_percent {
        last_side
    } else {
        last_side.flipped()
    }
}

pub fn random_side(rng: &mut Pcg32) -> Side {
    if rng.next_int(2) == 0 {
        Side::Left
    } else {
        Side::Right
    }
}

// ── tap state machine ──────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TapPhase {
    Idle,
    PostDelay,
    Tapping,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TapEvent {
    None,
    EndTap,
    ShouldStartTap,
}

#[derive(Debug, Clone, Copy)]
pub struct TapStateMachine {
    pub phase: TapPhase,
    pub tap_end_nano: i64,
    post_delay_end_nano: i64,
}

impl Default for TapStateMachine {
    fn default() -> Self {
        TapStateMachine {
            phase: TapPhase::Idle,
            tap_end_nano: 0,
            post_delay_end_nano: 0,
        }
    }
}

impl TapStateMachine {
    pub fn tick(&mut self, now_ns: i64) -> TapEvent {
        match self.phase {
            TapPhase::Tapping => {
                if now_ns >= self.tap_end_nano {
                    self.phase = TapPhase::Idle;
                    TapEvent::EndTap
                } else {
                    TapEvent::None
                }
            }
            TapPhase::PostDelay => {
                if now_ns >= self.post_delay_end_nano {
                    TapEvent::ShouldStartTap
                } else {
                    TapEvent::None
                }
            }
            TapPhase::Idle => TapEvent::None,
        }
    }

    pub fn begin_post_delay(&mut self, now_ns: i64, delay_ms: i32) {
        self.post_delay_end_nano = now_ns + (delay_ms as i64) * 1_000_000;
        self.phase = TapPhase::PostDelay;
    }

    pub fn begin_tap(&mut self, now_ns: i64, duration_ms: i32) {
        self.tap_end_nano = now_ns + (duration_ms as i64) * 1_000_000;
        self.phase = TapPhase::Tapping;
    }

    pub fn reset(&mut self) {
        self.phase = TapPhase::Idle;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wrap180_edges_and_non_finite_inputs() {
        assert_eq!(wrap180(180.0), -180.0);
        assert_eq!(wrap180(-180.0), -180.0);
        // 540 lands on -180, not the +180 plain `%` would leave.
        assert_eq!(wrap180(540.0), -180.0);
        assert_eq!(wrap180(-540.0), -180.0);
        // Non-finite and ulp-immune magnitudes: the old loop hung here forever,
        // which froze the whole core from a single bad TAP_WRAP frame.
        assert!(wrap180(f32::INFINITY).is_nan());
        assert!(wrap180(f32::NEG_INFINITY).is_nan());
        assert!(wrap180(f32::NAN).is_nan());
        assert!(wrap180(1.0e20).abs() <= 180.0);
        assert!(wrap180(-1.0e20).abs() <= 180.0);
        assert_eq!(wrap180(-0.0), 0.0);
    }

    #[test]
    fn tap_lifecycle() {
        let mut m = TapStateMachine::default();
        assert_eq!(m.tick(0), TapEvent::None);
        m.begin_tap(0, 50);
        assert_eq!(m.tick(10_000_000), TapEvent::None);
        assert_eq!(m.tick(50_000_000), TapEvent::EndTap);
        assert_eq!(m.phase, TapPhase::Idle);
    }

    #[test]
    fn post_delay_then_should_start() {
        let mut m = TapStateMachine::default();
        m.begin_post_delay(0, 30);
        assert_eq!(m.tick(20_000_000), TapEvent::None);
        assert_eq!(m.tick(30_000_000), TapEvent::ShouldStartTap);
    }

    #[test]
    fn seven_tap_always_flips_at_zero_repeat() {
        let mut rng = Pcg32::new(9);
        let mut s = Side::Left;
        for _ in 0..20 {
            let n = seven_tap_side(s, 0, &mut rng);
            assert_ne!(n, s);
            s = n;
        }
    }

    #[test]
    fn chase_side_puts_east_on_the_left_at_yaw_zero() {
        // yaw 0 faces +Z; a target at +X is to the player's left.
        let p = Vec3::new(0.0, 64.0, 0.0);
        let t = TargetState {
            id: 1,
            position: Vec3::new(3.0, 64.0, 0.0),
            motion: Vec3::new(0.0, 0.0, 0.0),
            hitbox: crate::geom::Hitbox::around(Vec3::new(3.0, 64.0, 0.0), 0.3, 1.8),
            distance: 3.0,
            health: 20.0,
            hurt_time: 0,
            is_moving_backward: false,
            is_moving_towards_player: false,
        };
        assert_eq!(chase_side(0.0, p, &t, 0), Side::Left);
    }
}
