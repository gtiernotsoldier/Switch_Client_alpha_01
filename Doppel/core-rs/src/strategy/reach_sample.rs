//! Port of `Reach` — the distance a press is extended to, when it is extended.
//!
//! Five gates in a fixed order: the caller's conditions, an open screen, the
//! chance roll, the `HitPer` throttle, the delay cooldown, and the
//! hit-through-blocks check. Order matters and is the reason this is one call
//! instead of six: the throttle counter advances only for presses that got past
//! the chance roll, so moving that line changes how often the module fires.
//!
//! The RNG draws happen in the same order as the Kotlin: the chance roll first,
//! then the distance. Two draws per accepted press, and none on a rejected one
//! before the roll.

use crate::rng::Pcg32;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct State {
    /// Presses seen since the last extension, for `HitPer`.
    pub click_count: i32,
    /// Last extension, for the delay cooldown. -1 so a clock reading 0 is valid.
    pub last_extend_ns: i64,
}

impl State {
    pub fn new() -> State {
        State {
            click_count: 0,
            last_extend_ns: -1,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub now_ns: i64,
    /// The module's own gates: enabled, weapon, moving, sprinting.
    pub conditions_ok: bool,
    /// A screen is open — not fighting, and clicks belong to the GUI.
    pub gui_open: bool,
    /// Percent; 100 means always.
    pub chance: i32,
    /// Extend only every N presses; 1 means every press.
    pub hit_per: i32,
    /// Minimum spacing between extensions.
    pub delay_ms: i32,
    /// Extend while the crosshair is on a block, or not.
    pub hit_through_blocks: bool,
    pub looking_at_block: bool,
    pub reach_min: f32,
    pub reach_max: f32,
}

impl Default for Input {
    fn default() -> Self {
        Input {
            now_ns: 0,
            conditions_ok: false,
            gui_open: false,
            chance: 100,
            hit_per: 1,
            delay_ms: 0,
            hit_through_blocks: true,
            looking_at_block: false,
            reach_min: 3.0,
            reach_max: 3.0,
        }
    }
}

/// One press. Returns the distance to use, or 0.0 to leave the press alone.
pub fn sample(state: &mut State, input: Input, rng: &mut Pcg32) -> f64 {
    if !input.conditions_ok || input.gui_open {
        return 0.0;
    }
    if input.chance < 100 && rng.random_int(0, 99) >= input.chance {
        return 0.0;
    }

    // HitPer throttle: extend only every N presses.
    state.click_count += 1;
    if state.click_count < input.hit_per {
        return 0.0;
    }
    state.click_count = 0;

    // Delay cooldown: skip if we extended too recently.
    if input.delay_ms > 0
        && state.last_extend_ns >= 0
        && input.now_ns - state.last_extend_ns < input.delay_ms as i64 * 1_000_000
    {
        return 0.0;
    }

    // Hit-through-blocks: skip when the crosshair currently hits a block.
    if !input.hit_through_blocks && input.looking_at_block {
        return 0.0;
    }

    state.last_extend_ns = input.now_ns;

    let lo = input.reach_min;
    let hi = input.reach_max;
    if hi > lo {
        (lo + rng.next_float() * (hi - lo)) as f64
    } else {
        lo as f64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: i64 = 1_000_000;

    fn ok(now_ns: i64) -> Input {
        Input {
            now_ns,
            conditions_ok: true,
            ..Default::default()
        }
    }

    fn rng() -> Pcg32 {
        Pcg32::new(11)
    }

    #[test]
    fn a_plain_press_returns_the_minimum_when_the_range_is_empty() {
        let mut s = State::new();
        let mut r = rng();
        assert_eq!(sample(&mut s, ok(0), &mut r), 3.0);
    }

    #[test]
    fn a_range_draws_between_its_bounds() {
        let mut s = State::new();
        let mut r = rng();
        for i in 0..64 {
            let v = sample(
                &mut s,
                Input {
                    now_ns: i * 1000 * MS,
                    reach_min: 3.0,
                    reach_max: 4.5,
                    ..ok(0)
                },
                &mut r,
            );
            assert!((3.0..=4.5).contains(&v), "out of range: {}", v);
        }
    }

    #[test]
    fn conditions_off_means_nothing_happens() {
        let mut s = State::new();
        let mut r = rng();
        let off = Input {
            conditions_ok: false,
            ..ok(0)
        };
        assert_eq!(sample(&mut s, off, &mut r), 0.0);
        assert_eq!(s.click_count, 0, "a gated press does not advance the throttle");
    }

    #[test]
    fn an_open_screen_means_nothing_happens() {
        let mut s = State::new();
        let mut r = rng();
        let gui = Input { gui_open: true, ..ok(0) };
        assert_eq!(sample(&mut s, gui, &mut r), 0.0);
    }

    #[test]
    fn the_chance_roll_rejects_before_the_throttle_advances() {
        let mut s = State::new();
        let mut r = rng();
        let rejected = Input { chance: 0, ..ok(0) };
        assert_eq!(sample(&mut s, rejected, &mut r), 0.0);
        assert_eq!(s.click_count, 0, "a failed roll must not count as a press");
    }

    #[test]
    fn hit_per_extends_every_nth_press() {
        let mut s = State::new();
        let mut r = rng();
        let every3 = Input { hit_per: 3, ..ok(0) };
        assert_eq!(sample(&mut s, every3, &mut r), 0.0, "press 1 of 3");
        assert_eq!(sample(&mut s, Input { now_ns: 1 * MS, ..every3 }, &mut r), 0.0, "press 2 of 3");
        let third = sample(&mut s, Input { now_ns: 2 * MS, ..every3 }, &mut r);
        assert_eq!(third, 3.0, "press 3 of 3 extends");
    }

    #[test]
    fn the_delay_cooldown_blocks_a_quick_second_extension() {
        let mut s = State::new();
        let mut r = rng();
        assert_eq!(sample(&mut s, Input { delay_ms: 500, ..ok(0) }, &mut r), 3.0);
        assert_eq!(
            sample(&mut s, Input { now_ns: 100 * MS, delay_ms: 500, ..ok(0) }, &mut r),
            0.0,
            "inside the cooldown"
        );
        assert_eq!(
            sample(&mut s, Input { now_ns: 500 * MS, delay_ms: 500, ..ok(0) }, &mut r),
            3.0,
            "exactly at the cooldown"
        );
    }

    #[test]
    fn a_blocked_crosshair_skips_unless_hit_through_blocks_is_on() {
        let mut s = State::new();
        let mut r = rng();
        let blocked = Input {
            hit_through_blocks: false,
            looking_at_block: true,
            ..ok(0)
        };
        assert_eq!(sample(&mut s, blocked, &mut r), 0.0);

        let allowed = Input {
            hit_through_blocks: true,
            looking_at_block: true,
            ..ok(100 * MS)
        };
        assert_eq!(sample(&mut s, allowed, &mut r), 3.0);
    }

    #[test]
    fn a_skipped_extension_does_not_spend_the_cooldown() {
        let mut s = State::new();
        let mut r = rng();
        let blocked = Input {
            hit_through_blocks: false,
            looking_at_block: true,
            ..ok(0)
        };
        sample(&mut s, blocked, &mut r);
        assert_eq!(s.last_extend_ns, -1, "nothing was extended, so nothing was spent");

        let allowed = Input { now_ns: 1, ..ok(0) };
        assert_eq!(sample(&mut s, allowed, &mut r), 3.0);
    }
}
