//! ADTap's uniform draws: the fire-point chance roll, the tap duration and the
//! inter-tap gap.
//!
//! ADTap used to draw all three with `kotlin.random.Random` on the JVM. Those
//! draws are algorithms, not judgements, so they live here behind ADTap's own
//! RNG stream. The module keeps the conditions (physical A/D keys, target
//! guards, the strafe-pattern mode) and marshals only the configured values.
//!
//! The tap *state machine* (`TAP_TICK` / `TAP_BEGIN`) and the side selection
//! (`TAP_SEVEN` / `TAP_RANDOM` / `TAP_CHASE`, on `rng_tap`) were already in the
//! core; this file only closes the last `kotlin.random` hole in the module.
//!
//! Draw order at the fire point is exactly the module's original order: the
//! chance roll comes first and is skipped entirely when `chance >= 100`; a pass
//! draws the duration, a fail draws the retry gap. The side selection sits
//! between the chance roll and the duration draw in the original module, but it
//! runs on `rng_tap` — a different stream — so collapsing chance + duration
//! into one op does not change either stream's sequence.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Fire point: the chance roll, then the tap duration (a pass) or the
    /// retry gap (a fail). A pass with a zero duration still starts the tap;
    /// the module decides what to do with the values, not the sampler.
    Fire,
    /// The core's state machine ended a tap: only the gap draw. The module
    /// arms a post-delay of that length before the next fire point.
    Gap,
}

impl Kind {
    pub fn from_code(c: i32) -> Option<Kind> {
        match c {
            0 => Some(Kind::Fire),
            1 => Some(Kind::Gap),
            _ => None,
        }
    }

    pub fn code(self) -> i32 {
        match self {
            Kind::Fire => 0,
            Kind::Gap => 1,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Input {
    pub kind: Kind,
    pub chance_percent: i32,
    pub tap_min_ms: i32,
    pub tap_max_ms: i32,
    pub gap_min_ms: i32,
    pub gap_max_ms: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Output {
    /// 1 when the fire point passed its chance roll (or never needed one).
    pub passed: i32,
    pub duration_ms: i32,
    pub gap_ms: i32,
}

/// `roll(lo, hi)` is an **inclusive** uniform draw in `[lo, hi]`, mirroring
/// Kotlin `Random.nextInt(min, max + 1)`. It is invoked exactly once per value
/// the caller actually needs: `chance >= 100` never rolls, a degenerate range
/// (`min == max`) never draws at all, and a kind that does not use a range
/// never advances the stream for it.
pub fn sample(input: Input, roll: &mut impl FnMut(i32, i32) -> i32) -> Output {
    let mut out = Output::default();
    match input.kind {
        Kind::Fire => {
            // The original gate: `chance < 100 && Random.nextInt(100) >= chance`
            // fails. chance == 0 still rolls and always fails, exactly as before.
            let passed =
                input.chance_percent >= 100 || roll(0, 99) < input.chance_percent;
            out.passed = passed as i32;
            if passed {
                out.duration_ms = draw(roll, input.tap_min_ms, input.tap_max_ms);
            } else {
                out.gap_ms = draw(roll, input.gap_min_ms, input.gap_max_ms);
            }
        }
        Kind::Gap => {
            out.gap_ms = draw(roll, input.gap_min_ms, input.gap_max_ms);
        }
    }
    out
}

fn draw(roll: &mut impl FnMut(i32, i32) -> i32, min: i32, max: i32) -> i32 {
    let lo = min.min(max);
    let hi = min.max(max);
    if lo == hi {
        lo
    } else {
        roll(lo, hi)
    }
}

/// One scalar carries all three fields. Ranges are bounded by the option layer
/// (tap 10..400 ms, gap 0..500 ms), so base-1024 packing is exact in an f64.
pub fn pack(o: Output) -> i32 {
    o.duration_ms + 1024 * o.gap_ms + 1024 * 1024 * o.passed
}

pub fn unpack(code: i32) -> Output {
    Output {
        duration_ms: code % 1024,
        gap_ms: (code / 1024) % 1024,
        passed: code / (1024 * 1024),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(kind: Kind, chance: i32) -> Input {
        Input {
            kind,
            chance_percent: chance,
            tap_min_ms: 70,
            tap_max_ms: 140,
            gap_min_ms: 40,
            gap_max_ms: 110,
        }
    }

    #[test]
    fn pack_unpack_roundtrips() {
        let o = Output {
            passed: 1,
            duration_ms: 97,
            gap_ms: 0,
        };
        assert_eq!(unpack(pack(o)), o);
        let failed = Output {
            passed: 0,
            duration_ms: 0,
            gap_ms: 63,
        };
        assert_eq!(unpack(pack(failed)), failed);
    }

    #[test]
    fn a_passing_fire_rolls_chance_then_the_duration() {
        let mut seen = Vec::new();
        let mut rolls = [49, 88].iter().copied();
        let out = sample(input(Kind::Fire, 60), &mut |lo, hi| {
            seen.push((lo, hi));
            rolls.next().unwrap()
        });
        assert_eq!(out.passed, 1);
        assert_eq!(out.duration_ms, 88);
        assert_eq!(out.gap_ms, 0);
        assert_eq!(seen, vec![(0, 99), (70, 140)], "chance first, then duration");
    }

    #[test]
    fn a_failing_fire_rolls_chance_then_the_retry_gap() {
        let mut seen = Vec::new();
        let mut rolls = [50, 77].iter().copied();
        let out = sample(input(Kind::Fire, 50), &mut |lo, hi| {
            seen.push((lo, hi));
            rolls.next().unwrap()
        });
        assert_eq!(out.passed, 0);
        assert_eq!(out.duration_ms, 0);
        assert_eq!(out.gap_ms, 77);
        assert_eq!(seen, vec![(0, 99), (40, 110)], "chance first, then the gap");
    }

    #[test]
    fn a_full_chance_never_rolls() {
        let mut draws = 0;
        let out = sample(input(Kind::Fire, 100), &mut |lo, hi| {
            draws += 1;
            assert_eq!((lo, hi), (70, 140), "only the duration may draw");
            99
        });
        assert_eq!(out.passed, 1);
        assert_eq!(out.duration_ms, 99);
        assert_eq!(draws, 1, "chance == 100 skips the roll entirely");
    }

    #[test]
    fn a_zero_chance_still_rolls_and_always_fails() {
        // The original gate short-circuits on `chance < 100`, so chance == 0
        // does roll — and `roll >= 0` means it can never pass.
        let mut seen = Vec::new();
        let mut rolls = [0, 65].iter().copied();
        let out = sample(input(Kind::Fire, 0), &mut |lo, hi| {
            seen.push((lo, hi));
            rolls.next().unwrap()
        });
        assert_eq!(out.passed, 0);
        assert_eq!(out.gap_ms, 65, "the fail path draws the retry gap");
        assert_eq!(seen, vec![(0, 99), (40, 110)], "chance == 0 still rolls");
    }

    #[test]
    fn a_gap_draws_only_the_gap() {
        let mut seen = Vec::new();
        let out = sample(input(Kind::Gap, 100), &mut |lo, hi| {
            seen.push((lo, hi));
            55
        });
        assert_eq!(out.gap_ms, 55);
        assert_eq!(out.passed + out.duration_ms, 0);
        assert_eq!(seen, vec![(40, 110)]);
    }

    #[test]
    fn degenerate_ranges_never_draw() {
        let mut i = input(Kind::Fire, 100);
        i.tap_min_ms = 120;
        i.tap_max_ms = 120;
        let mut draws = 0;
        let out = sample(i, &mut |_, _| {
            draws += 1;
            99
        });
        assert_eq!(draws, 0, "no roll for min == max and no chance roll");
        assert_eq!(out.duration_ms, 120);
        assert_eq!(out.passed, 1);
    }

    #[test]
    fn inverted_ranges_are_normalised_before_the_roll() {
        let mut i = input(Kind::Gap, 100);
        i.gap_min_ms = 110;
        i.gap_max_ms = 40;
        let mut seen = None;
        sample(i, &mut |lo, hi| {
            seen = Some((lo, hi));
            40
        });
        assert_eq!(seen, Some((40, 110)));
    }

    #[test]
    fn kind_codes_roundtrip() {
        for k in [Kind::Fire, Kind::Gap] {
            assert_eq!(Kind::from_code(k.code()), Some(k));
        }
        assert_eq!(Kind::from_code(2), None);
    }
}
