//! AutoBlock's two chance gates: the per-tick Chance roll and the Lag
//! window's start roll.
//!
//! AutoBlock used to roll both with `kotlin.random.Random` on the JVM. A roll
//! is an algorithm, not a judgement, so both live here behind AutoBlock's own
//! RNG stream. The module keeps the conditions (the unified trigger engine,
//! the distance gate, the mode state machines) and marshals only the
//! configured percentage.
//!
//! The two call sites are the same algorithm — `chance >= 100 || roll <
//! chance` — so there is one sampler and no `kind`: the per-tick roll happens
//! once per tick the module is armed (GUI closed, sword held) and the Lag roll
//! happens only when a lag window wants to start, and the stream order is the
//! call order, exactly as the JVM's single shared stream saw it.
//!
//! The rest of the module was already in the core (`CBT_RANGE`, `CBT_PREDICT`,
//! `CBT_ARBITER`, and `CBT_DECIDE` for BlockHit's trigger); this file closes
//! AutoBlock's `kotlin.random` hole.
//!
//! Roll discipline, preserved from the module:
//!  - `chance >= 100` never rolls (the original `||` short-circuit);
//!  - `chance == 0` still rolls and always fails;
//!  - the roll is `nextInt(100)` — [0, 100) — mirrored as an inclusive
//!    `roll(0, 99)`, so a middle chance passes on `roll < chance` and fails
//!    on `roll >= chance`.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Input {
    pub chance_percent: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Output {
    /// True when the gate passed (or never needed to roll).
    pub passed: bool,
}

/// `roll(lo, hi)` is an **inclusive** uniform draw in `[lo, hi]`, mirroring
/// Kotlin `Random.nextInt(hi + 1)`. It is invoked exactly once per gate that
/// actually rolls: `chance >= 100` never advances the stream.
pub fn sample(input: Input, roll: &mut impl FnMut(i32, i32) -> i32) -> Output {
    // The original gate: `chance < 100 && Random.nextInt(100) >= chance`
    // fails. chance == 0 still rolls and always fails, exactly as before.
    let passed = input.chance_percent >= 100 || roll(0, 99) < input.chance_percent;
    Output { passed }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(chance: i32) -> Input {
        Input { chance_percent: chance }
    }

    #[test]
    fn a_full_chance_never_rolls() {
        let mut draws = 0;
        let out = sample(input(100), &mut |_, _| {
            draws += 1;
            0
        });
        assert!(out.passed);
        assert_eq!(draws, 0, "chance == 100 skips the roll entirely");
    }

    #[test]
    fn a_zero_chance_still_rolls_and_always_fails() {
        // The original gate short-circuits on `chance < 100`, so chance == 0
        // does roll — and `roll >= 0` means it can never pass.
        let mut seen = Vec::new();
        let out = sample(input(0), &mut |lo, hi| {
            seen.push((lo, hi));
            0
        });
        assert!(!out.passed);
        assert_eq!(seen, vec![(0, 99)], "chance == 0 still rolls");
    }

    #[test]
    fn a_middle_chance_passes_below_and_fails_at_the_chance() {
        // `nextInt(100) < chance` — 59 passes a 60 gate, 60 and 99 fail it.
        assert!(sample(input(60), &mut |_, _| 59).passed);
        assert!(!sample(input(60), &mut |_, _| 60).passed);
        assert!(!sample(input(60), &mut |_, _| 99).passed);
    }

    #[test]
    fn the_roll_spans_zero_to_ninety_nine() {
        let mut seen = Vec::new();
        sample(input(50), &mut |lo, hi| {
            seen.push((lo, hi));
            49
        });
        assert_eq!(seen, vec![(0, 99)], "the roll mirrors nextInt(100)");
    }

    #[test]
    fn a_chance_above_100_never_rolls_either() {
        // Defensive: the option layer bounds chance at 100, but the sampler
        // must not depend on that to skip its roll.
        let mut draws = 0;
        let out = sample(input(150), &mut |_, _| {
            draws += 1;
            99
        });
        assert!(out.passed);
        assert_eq!(draws, 0);
    }
}
