//! Port of `Backtrack` — the position-hold window.
//!
//! One deviation from LiquidBounce, kept from the Kotlin: only position updates
//! are held, so chat, sounds, health and the world keep flowing. That is a
//! platform decision, not a strategy one — this module only says *whether* to
//! hold, the adapter decides what "hold" means.
//!
//! Time is passed in as nanoseconds rather than derived from a tick counter.
//! The window is rolled between 80 and 250 ms, which is finer than a 50 ms tick:
//! rounding it to ticks would turn "no fixed period" back into a fixed one, and
//! an evenly spaced hold is the thing this module exists to avoid.

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct State {
    /// Start of the current hold, or -1 when not holding.
    ///
    /// Not 0: the caller's clock is allowed to read 0 (a synthetic replay does),
    /// and a 0 sentinel would then look like a hold that started at the epoch.
    pub hold_start_ns: i64,
    /// Pulse style: stay quiet until this timestamp before the next hold.
    pub pause_until_ns: i64,
    /// Length of the current hold, rolled when it starts.
    pub window_ns: i64,
}

impl State {
    pub fn new() -> State {
        State {
            hold_start_ns: -1,
            pause_until_ns: -1,
            window_ns: 150_000_000,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Input {
    /// Caller's clock. Only differences matter, so any monotonic nanosecond
    /// source works — the two processes never compare absolute values.
    pub now_ns: i64,
    /// Modern rolls a window per hold; Legacy uses a fixed 250 ms.
    pub modern: bool,
    /// Pulse holds once per window then pauses for the same length.
    pub pulse: bool,
    /// Modern: only hold when the target is close. Ignored in Legacy.
    pub smart: bool,
    pub min_delay_ms: i32,
    pub max_delay_ms: i32,
    /// Distance to the target, or a negative value when there is none.
    pub distance: f32,
    /// Legacy mode's threshold. Modern ignores it and uses the fixed 6 blocks
    /// (or no limit at all when `smart` is off).
    pub legacy_distance: f32,
    /// GUI open, dead, or being hit — the reference's "do not delay" list.
    pub must_release: bool,
}

impl Default for Input {
    fn default() -> Self {
        Input {
            now_ns: 0,
            modern: true,
            pulse: false,
            smart: true,
            min_delay_ms: 80,
            max_delay_ms: 250,
            distance: -1.0,
            legacy_distance: 3.5,
            must_release: true,
        }
    }
}

/// One tick. Returns whether position updates should be held this tick.
///
/// `roll(lo, hi)` supplies the window length in milliseconds. It is a closure
/// rather than a `Pcg32` so the parity fixture can hand in a fixed sequence —
/// the two sides use different generators, and a fixture that depended on that
/// would be testing the RNG instead of the window logic.
pub fn tick(state: &mut State, input: Input, roll: &mut impl FnMut(i32, i32) -> i32) -> bool {
    let has_target = input.distance >= 0.0;
    let in_range = if input.modern {
        has_target && (!input.smart || input.distance <= 6.0)
    } else {
        // Legacy compares against the configured distance, not the modern 6.
        has_target && input.distance <= input.legacy_distance
    };

    if input.must_release || !in_range {
        release(state);
        return false;
    }

    // Pulse: after a window closes, stay quiet for the same length before the
    // next hold, so the pattern is hold-pause-hold rather than a solid block.
    if input.modern && input.pulse && input.now_ns < state.pause_until_ns {
        release(state);
        return false;
    }

    if state.hold_start_ns < 0 {
        state.hold_start_ns = input.now_ns;
        if input.modern {
            // A fresh window every time: an evenly spaced hold is the one thing
            // a real connection never produces, and it is what makes a pattern
            // detectable.
            let lo = input.min_delay_ms.min(input.max_delay_ms);
            let hi = input.min_delay_ms.max(input.max_delay_ms);
            state.window_ns = roll(lo, hi) as i64 * 1_000_000;
        } else {
            state.window_ns = 250_000_000;
        }
    }

    if input.now_ns - state.hold_start_ns >= state.window_ns {
        release(state);
        if input.modern && input.pulse {
            state.pause_until_ns = input.now_ns + state.window_ns;
        }
        return false;
    }

    true
}

fn release(state: &mut State) {
    state.hold_start_ns = -1;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rng::Pcg32;

    /// The tests only care about the machine, so the window roll is wired
    /// straight to a seeded Pcg32 here instead of being scripted per case.
    fn t(s: &mut State, i: Input, rng: &mut Pcg32) -> bool {
        tick(s, i, &mut |lo, hi| rng.random_int(lo, hi))
    }

    const MS: i64 = 1_000_000;

    fn input(now_ns: i64) -> Input {
        Input {
            now_ns,
            ..Default::default()
        }
    }

    fn in_range(now_ns: i64) -> Input {
        Input {
            distance: 3.0,
            must_release: false,
            ..input(now_ns)
        }
    }

    #[test]
    fn a_target_in_range_starts_a_hold() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        assert!(t(&mut s, in_range(0), &mut rng));
        assert!(s.hold_start_ns >= 0);
    }

    #[test]
    fn the_hold_ends_when_its_window_elapses() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        assert!(t(&mut s, in_range(0), &mut rng));
        let w = s.window_ns;
        assert!(t(&mut s, in_range(w - 1), &mut rng), "still inside the window");
        assert!(!t(&mut s, in_range(w), &mut rng), "the window is spent");
        assert_eq!(s.hold_start_ns, -1);
    }

    #[test]
    fn no_target_never_holds() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        assert!(!t(&mut s, input(0), &mut rng));
    }

    #[test]
    fn a_far_target_is_out_of_range_in_modern_mode() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        let far = Input {
            distance: 7.0,
            must_release: false,
            ..input(0)
        };
        assert!(!t(&mut s, far, &mut rng));
    }

    #[test]
    fn smart_off_holds_at_any_distance() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        let far = Input {
            distance: 30.0,
            must_release: false,
            smart: false,
            ..input(0)
        };
        assert!(t(&mut s, far, &mut rng));
    }

    #[test]
    fn being_hit_releases_at_once() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        assert!(t(&mut s, in_range(0), &mut rng));

        let hit = Input {
            must_release: true,
            ..in_range(1)
        };
        assert!(!t(&mut s, hit, &mut rng));
        assert_eq!(s.hold_start_ns, -1);
    }

    #[test]
    fn legacy_uses_a_fixed_quarter_second() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        let legacy = Input {
            modern: false,
            distance: 3.0,
            must_release: false,
            ..input(0)
        };
        assert!(t(&mut s, legacy, &mut rng));
        assert_eq!(s.window_ns, 250 * MS);
    }

    #[test]
    fn modern_rolls_a_different_window_each_hold() {
        // Not a statistical test: just that the value is not the fixed legacy
        // one and that consecutive holds can differ.
        let mut s = State::new();
        let mut rng = Pcg32::new(99);
        let mut seen = std::collections::BTreeSet::new();
        for i in 0..20 {
            t(&mut s, in_range(i * 400 * MS), &mut rng);
            seen.insert(s.window_ns);
            // Close the hold so the next one rolls again.
            let w = s.window_ns;
            t(&mut s, in_range(i * 400 * MS + w), &mut rng);
        }
        assert!(seen.len() > 1, "the window never changed: {:?}", seen);
        for w in &seen {
            assert!(*w >= 80 * MS && *w <= 250 * MS, "window out of range: {}", w);
        }
    }

    #[test]
    fn pulse_pauses_for_one_window_after_releasing() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        let pulse = Input {
            pulse: true,
            distance: 3.0,
            must_release: false,
            ..input(0)
        };
        assert!(t(&mut s, pulse, &mut rng));
        let w = s.window_ns;

        // The window closes: release and arm the pause.
        assert!(!t(&mut s, Input { now_ns: w, ..pulse }, &mut rng));
        assert_eq!(s.pause_until_ns, w + s.window_ns);

        // Inside the pause: nothing, and no new window is armed.
        assert!(!t(&mut s, Input { now_ns: w + 1, ..pulse }, &mut rng));
        assert_eq!(s.hold_start_ns, -1);

        // After the pause a new hold starts.
        let resume = s.pause_until_ns;
        assert!(t(&mut s, Input { now_ns: resume, ..pulse }, &mut rng));
    }

    #[test]
    fn smooth_style_restarts_immediately() {
        let mut s = State::new();
        let mut rng = Pcg32::new(1);
        assert!(t(&mut s, in_range(0), &mut rng));
        let w = s.window_ns;
        assert!(!t(&mut s, in_range(w), &mut rng));
        // The very next tick starts a fresh hold instead of waiting.
        assert!(t(&mut s, in_range(w + 1), &mut rng));
    }
}
