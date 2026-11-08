//! Port of `KnockbackDisplace` — the one-tick yaw flick on a landed hit.
//!
//! The server records the attacker's yaw at the moment of the attack, so an
//! angle offset written for exactly one tick changes where the knockback pushes
//! without changing what the player sees afterwards.
//!
//! `last_strafe_left` is updated here, on the attack, not on every tick like the
//! Kotlin did. That is not a behaviour change: the field is only ever read by
//! `direction()`, which only runs on an attack, and a tick that would have
//! updated it without an attack landing leaves exactly the same value behind
//! (the update is conditional on exactly one key being held, and the value is
//! only consumed at the next attack).

use crate::rng::Pcg32;

/// Which way the flick goes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Direction {
    Left = 0,
    Right = 1,
    Random = 2,
    Strafe = 3,
    StrafeInverted = 4,
}

impl Direction {
    pub fn from_u8(v: u8) -> Direction {
        match v {
            0 => Direction::Left,
            1 => Direction::Right,
            2 => Direction::Random,
            3 => Direction::Strafe,
            4 => Direction::StrafeInverted,
            // Unknown values fall back to Left rather than erroring: a stale stub
            // sending a new mode should still flick somewhere sane.
            _ => Direction::Left,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct State {
    /// Which way the player last strafed, for `UseLastStrafe`.
    pub last_strafe_left: bool,
    /// Last flick, for the cooldown. -1 so a clock reading 0 is a valid attack.
    pub last_flick_ns: i64,
}

impl State {
    pub fn new() -> State {
        State {
            last_strafe_left: false,
            last_flick_ns: -1,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub now_ns: i64,
    pub direction: Direction,
    pub use_last_strafe: bool,
    pub cooldown_ms: i32,
    pub angle: f32,
    /// "Do not flick while taking knockback" — the reference's `NotTakingKnockback`.
    pub not_taking_knockback: bool,
    /// Whether a velocity packet arrived this tick.
    pub velocity_this_tick: bool,
    pub key_left: bool,
    pub key_right: bool,
}

impl Default for Input {
    fn default() -> Self {
        Input {
            now_ns: 0,
            direction: Direction::Left,
            use_last_strafe: true,
            cooldown_ms: 100,
            angle: 90.0,
            not_taking_knockback: false,
            velocity_this_tick: false,
            key_left: false,
            key_right: false,
        }
    }
}

/// A landed hit. Returns the yaw offset in degrees, or 0 for "no flick".
pub fn attack(state: &mut State, input: Input, rng: &mut Pcg32) -> f32 {
    // Track the strafe direction even when the cooldown swallows this attack —
    // the Kotlin did it on every tick, and "use last strafe" is about what the
    // player was doing, not about whether a flick happened.
    if input.key_left != input.key_right {
        state.last_strafe_left = input.key_left;
    }

    if state.last_flick_ns >= 0
        && input.now_ns - state.last_flick_ns < input.cooldown_ms as i64 * 1_000_000
    {
        return 0.0;
    }
    if input.not_taking_knockback && input.velocity_this_tick {
        return 0.0;
    }

    let sign = match input.direction {
        Direction::Left => 1.0,
        Direction::Right => -1.0,
        Direction::Random => {
            if rng.next_u32() & 1 == 0 {
                1.0
            } else {
                -1.0
            }
        }
        Direction::Strafe | Direction::StrafeInverted => {
            let base = if input.key_left && !input.key_right {
                1.0
            } else if input.key_right && !input.key_left {
                -1.0
            } else if input.use_last_strafe {
                if state.last_strafe_left {
                    1.0
                } else {
                    -1.0
                }
            } else {
                0.0
            };
            if input.direction == Direction::StrafeInverted {
                -base
            } else {
                base
            }
        }
    };

    if sign == 0.0 {
        return 0.0;
    }

    state.last_flick_ns = input.now_ns;
    sign * input.angle
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: i64 = 1_000_000;

    fn hit(now_ns: i64) -> Input {
        Input {
            now_ns,
            ..Default::default()
        }
    }

    fn rng() -> Pcg32 {
        Pcg32::new(7)
    }

    #[test]
    fn left_flicks_positive_and_right_negative() {
        let mut s = State::new();
        let mut r = rng();
        assert_eq!(attack(&mut s, hit(0), &mut r), 90.0);
        assert_eq!(
            attack(&mut s, Input { now_ns: 1000 * MS, direction: Direction::Right, ..hit(0) }, &mut r),
            -90.0
        );
    }

    #[test]
    fn the_cooldown_swallows_a_second_flick() {
        let mut s = State::new();
        let mut r = rng();
        assert_eq!(attack(&mut s, hit(0), &mut r), 90.0);
        assert_eq!(attack(&mut s, hit(50 * MS), &mut r), 0.0, "inside the 100 ms cooldown");
        assert_eq!(attack(&mut s, hit(100 * MS), &mut r), 90.0, "exactly at the cooldown");
    }

    #[test]
    fn taking_knockback_can_veto_the_flick() {
        let mut s = State::new();
        let mut r = rng();
        let vetoed = Input {
            not_taking_knockback: true,
            velocity_this_tick: true,
            ..hit(0)
        };
        assert_eq!(attack(&mut s, vetoed, &mut r), 0.0);
        assert_eq!(s.last_flick_ns, -1, "a vetoed attack does not spend the cooldown");
    }

    #[test]
    fn strafe_follows_the_held_key() {
        let mut s = State::new();
        let mut r = rng();
        let left = Input {
            direction: Direction::Strafe,
            key_left: true,
            ..hit(0)
        };
        assert_eq!(attack(&mut s, left, &mut r), 90.0);
        assert!(s.last_strafe_left);
    }

    #[test]
    fn strafe_inverted_flips_it() {
        let mut s = State::new();
        let mut r = rng();
        let left = Input {
            direction: Direction::StrafeInverted,
            key_left: true,
            ..hit(0)
        };
        assert_eq!(attack(&mut s, left, &mut r), -90.0);
    }

    #[test]
    fn strafe_without_a_key_falls_back_to_the_last_direction() {
        let mut s = State::new();
        let mut r = rng();
        // Establish "last strafed left".
        attack(&mut s, Input { key_left: true, ..hit(0) }, &mut r);

        let idle = Input {
            direction: Direction::Strafe,
            now_ns: 500 * MS,
            ..hit(0)
        };
        assert_eq!(attack(&mut s, idle, &mut r), 90.0);
    }

    #[test]
    fn strafe_without_a_key_and_without_the_fallback_does_nothing() {
        let mut s = State::new();
        let mut r = rng();
        let idle = Input {
            direction: Direction::Strafe,
            use_last_strafe: false,
            ..hit(0)
        };
        assert_eq!(attack(&mut s, idle, &mut r), 0.0);
        assert_eq!(s.last_flick_ns, -1, "nothing happened, so nothing was spent");
    }

    #[test]
    fn random_picks_a_direction_and_stays_within_the_angle() {
        let mut s = State::new();
        let mut r = rng();
        let mut seen_positive = false;
        let mut seen_negative = false;
        for i in 0..32 {
            let v = attack(
                &mut s,
                Input {
                    now_ns: i * 1000 * MS,
                    direction: Direction::Random,
                    ..hit(0)
                },
                &mut r,
            );
            assert!(v == 90.0 || v == -90.0, "unexpected random flick {}", v);
            if v > 0.0 {
                seen_positive = true;
            } else {
                seen_negative = true;
            }
        }
        assert!(seen_positive && seen_negative, "random never produced both directions");
    }

    #[test]
    fn both_keys_held_is_not_a_strafe() {
        let mut s = State::new();
        let mut r = rng();
        let both = Input {
            direction: Direction::Strafe,
            key_left: true,
            key_right: true,
            use_last_strafe: false,
            ..hit(0)
        };
        assert_eq!(attack(&mut s, both, &mut r), 0.0);
    }
}
