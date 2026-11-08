//! BlockHit's uniform draws: the post-delay, the block duration and the
//! initial hit threshold.
//!
//! BlockHit used to draw all three with `kotlin.random.Random` on the JVM.
//! Those draws are algorithms, not judgements, so they live here behind
//! BlockHit's own RNG stream. The module keeps the conditions (physical /
//! synthetic click state, target guards, the sword filter) and marshals only
//! the configured ranges.
//!
//! The CombatTrigger verdict (`CBT_DECIDE`) — which already rolls BlockHit's
//! `Chance` on `rng_combat` — was already in the core; this file only closes
//! the last `kotlin.random` hole in the module.
//!
//! Draw order at the fire point is the module's original order: the
//! post-delay first, then the block duration. `Init` runs once per enable and
//! draws only the hit threshold, clamped to at least one hit exactly as the
//! module's `coerceAtLeast(1)` did.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Fire point: draw the post-delay and the block duration. The module
    /// arms a delayed press when the post-delay is non-zero, otherwise it
    /// presses immediately and holds for the duration.
    Fire,
    /// Module enabled: seed the hit throttle with a random threshold.
    Init,
}

impl Kind {
    pub fn from_code(c: i32) -> Option<Kind> {
        match c {
            0 => Some(Kind::Fire),
            1 => Some(Kind::Init),
            _ => None,
        }
    }

    pub fn code(self) -> i32 {
        match self {
            Kind::Fire => 0,
            Kind::Init => 1,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Input {
    pub kind: Kind,
    pub post_delay_min: i32,
    pub post_delay_max: i32,
    pub wait_min: i32,
    pub wait_max: i32,
    pub hit_per_min: i32,
    pub hit_per_max: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Output {
    pub post_delay_ms: i32,
    pub wait_ms: i32,
    pub threshold: i32,
}

/// `roll(min, max)` is an **inclusive** uniform draw in `[min, max]`, mirroring
/// Kotlin `Random.nextInt(min, max + 1)`. It is invoked exactly once per value
/// the caller actually needs: a kind that does not use a range never advances
/// the stream for it, and a degenerate range (`min == max`) never draws at all.
pub fn sample(input: Input, roll: &mut impl FnMut(i32, i32) -> i32) -> Output {
    let mut out = Output::default();
    match input.kind {
        Kind::Fire => {
            out.post_delay_ms = draw(roll, input.post_delay_min, input.post_delay_max);
            out.wait_ms = draw(roll, input.wait_min, input.wait_max);
        }
        Kind::Init => {
            out.threshold = draw(roll, input.hit_per_min, input.hit_per_max).max(1);
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
/// (post-delay 0..500 ms, wait 1..500 ms, hit threshold 1..10), so base-1024
/// packing is exact in an f64.
pub fn pack(o: Output) -> i32 {
    o.post_delay_ms + 1024 * o.wait_ms + 1024 * 1024 * o.threshold
}

pub fn unpack(code: i32) -> Output {
    Output {
        post_delay_ms: code % 1024,
        wait_ms: (code / 1024) % 1024,
        threshold: code / (1024 * 1024),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(kind: Kind) -> Input {
        Input {
            kind,
            post_delay_min: 10,
            post_delay_max: 40,
            wait_min: 110,
            wait_max: 150,
            hit_per_min: 1,
            hit_per_max: 10,
        }
    }

    #[test]
    fn pack_unpack_roundtrips() {
        let o = Output {
            post_delay_ms: 23,
            wait_ms: 137,
            threshold: 4,
        };
        assert_eq!(unpack(pack(o)), o);
    }

    #[test]
    fn a_fire_point_draws_the_post_delay_then_the_wait() {
        let mut seen = Vec::new();
        let mut rolls = [20, 128].iter().copied();
        let out = sample(input(Kind::Fire), &mut |lo, hi| {
            seen.push((lo, hi));
            rolls.next().unwrap()
        });
        assert_eq!(out.post_delay_ms, 20);
        assert_eq!(out.wait_ms, 128);
        assert_eq!(out.threshold, 0);
        assert_eq!(
            seen,
            vec![(10, 40), (110, 150)],
            "post-delay first, then the block duration"
        );
    }

    #[test]
    fn an_init_draws_only_the_threshold_and_clamps_to_one() {
        let mut seen = Vec::new();
        let out = sample(input(Kind::Init), &mut |lo, hi| {
            seen.push((lo, hi));
            0
        });
        assert_eq!(out.threshold, 1, "coerceAtLeast(1) survives the port");
        assert_eq!(seen, vec![(1, 10)]);
        assert_eq!(out.post_delay_ms + out.wait_ms, 0);
    }

    #[test]
    fn a_degenerate_range_never_draws() {
        let mut i = input(Kind::Fire);
        i.post_delay_min = 0;
        i.post_delay_max = 0;
        i.wait_min = 120;
        i.wait_max = 120;
        let mut draws = 0;
        let out = sample(i, &mut |_, _| {
            draws += 1;
            99
        });
        assert_eq!(draws, 0, "no roll for min == max");
        assert_eq!(out.post_delay_ms, 0);
        assert_eq!(out.wait_ms, 120);
    }

    #[test]
    fn inverted_ranges_are_normalised_before_the_roll() {
        let mut i = input(Kind::Fire);
        i.wait_min = 150;
        i.wait_max = 110;
        let mut seen = Vec::new();
        sample(i, &mut |lo, hi| {
            seen.push((lo, hi));
            110
        });
        assert_eq!(seen, vec![(10, 40), (110, 150)]);
    }

    #[test]
    fn kind_codes_roundtrip() {
        for k in [Kind::Fire, Kind::Init] {
            assert_eq!(Kind::from_code(k.code()), Some(k));
        }
        assert_eq!(Kind::from_code(2), None);
    }
}
