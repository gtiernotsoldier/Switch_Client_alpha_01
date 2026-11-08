//! Uniform range sampling for the W/S tap modules.
//!
//! WTap and STap used to draw their post-delay, tap duration and the initial
//! hit-threshold with `kotlin.random.Random` on the JVM. Those draws are
//! algorithms, not judgements, so they live here behind two per-module RNG
//! streams. The module keeps the conditions (physical keys, target guards,
//! the CombatTrigger verdict) and marshals only the configured ranges.
//!
//! The tap *state machine* (`TAP_TICK` / `TAP_BEGIN`) and the CombatTrigger
//! verdict (`CBT_DECIDE`) were already in the core; this file only closes the
//! last `kotlin.random` hole in these two modules.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Fire point: draw the post-delay and the tap duration. The module arms a
    /// delayed start when the post-delay is non-zero, otherwise it starts the
    /// tap immediately with the duration.
    PostDelayOrStart,
    /// The core's state machine said "start the tap now" (a post-delay just
    /// elapsed). Only the duration is needed.
    Action,
    /// Module enabled: seed the throttle with a random hit threshold.
    Init,
}

impl Kind {
    pub fn from_code(c: i32) -> Option<Kind> {
        match c {
            0 => Some(Kind::PostDelayOrStart),
            1 => Some(Kind::Action),
            2 => Some(Kind::Init),
            _ => None,
        }
    }

    pub fn code(self) -> i32 {
        match self {
            Kind::PostDelayOrStart => 0,
            Kind::Action => 1,
            Kind::Init => 2,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Input {
    pub kind: Kind,
    pub post_delay_min: i32,
    pub post_delay_max: i32,
    pub action_min: i32,
    pub action_max: i32,
    pub once_every_min: i32,
    pub once_every_max: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Output {
    pub post_delay_ms: i32,
    pub action_ms: i32,
    pub threshold: i32,
}

/// `roll(min, max)` is an **inclusive** uniform draw in `[min, max]`, mirroring
/// Kotlin `Random.nextInt(min, max + 1)`. It is invoked exactly once per value
/// the caller actually needs: a kind that does not use a range never advances
/// the stream for it, and a degenerate range (`min == max`) never draws at all.
pub fn sample(input: Input, roll: &mut impl FnMut(i32, i32) -> i32) -> Output {
    let mut out = Output::default();
    match input.kind {
        Kind::PostDelayOrStart => {
            out.post_delay_ms = draw(roll, input.post_delay_min, input.post_delay_max);
            out.action_ms = draw(roll, input.action_min, input.action_max);
        }
        Kind::Action => {
            out.action_ms = draw(roll, input.action_min, input.action_max);
        }
        Kind::Init => {
            out.threshold = draw(roll, input.once_every_min, input.once_every_max).max(1);
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
/// (0..500 ms, 1..10 hits), so base-1024 packing is exact in an f64.
pub fn pack(o: Output) -> i32 {
    o.post_delay_ms + 1024 * o.action_ms + 1024 * 1024 * o.threshold
}

pub fn unpack(code: i32) -> Output {
    Output {
        post_delay_ms: code % 1024,
        action_ms: (code / 1024) % 1024,
        threshold: code / (1024 * 1024),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(kind: Kind) -> Input {
        Input {
            kind,
            post_delay_min: 25,
            post_delay_max: 55,
            action_min: 25,
            action_max: 55,
            once_every_min: 1,
            once_every_max: 3,
        }
    }

    #[test]
    fn pack_unpack_roundtrips() {
        let o = Output {
            post_delay_ms: 37,
            action_ms: 41,
            threshold: 2,
        };
        assert_eq!(unpack(pack(o)), o);
    }

    #[test]
    fn a_fire_point_draws_post_delay_then_duration() {
        let mut seen = Vec::new();
        let mut rolls = [30, 44].iter().copied();
        let out = sample(input(Kind::PostDelayOrStart), &mut |lo, hi| {
            seen.push((lo, hi));
            rolls.next().unwrap()
        });
        assert_eq!(out.post_delay_ms, 30);
        assert_eq!(out.action_ms, 44);
        assert_eq!(out.threshold, 0);
        assert_eq!(seen, vec![(25, 55), (25, 55)]);
    }

    #[test]
    fn an_action_draws_only_the_duration() {
        let mut seen = Vec::new();
        let out = sample(input(Kind::Action), &mut |lo, hi| {
            seen.push((lo, hi));
            48
        });
        assert_eq!(out.post_delay_ms, 0);
        assert_eq!(out.action_ms, 48);
        assert_eq!(seen, vec![(25, 55)]);
    }

    #[test]
    fn init_draws_only_the_threshold_and_clamps_to_one() {
        let mut seen = Vec::new();
        let out = sample(input(Kind::Init), &mut |lo, hi| {
            seen.push((lo, hi));
            0
        });
        assert_eq!(out.threshold, 1);
        assert_eq!(seen, vec![(1, 3)]);
        assert_eq!(out.post_delay_ms + out.action_ms, 0);
    }

    #[test]
    fn a_degenerate_range_never_draws() {
        let mut i = input(Kind::PostDelayOrStart);
        i.post_delay_min = 0;
        i.post_delay_max = 0;
        i.action_min = 40;
        i.action_max = 40;
        let mut draws = 0;
        let out = sample(i, &mut |_, _| {
            draws += 1;
            99
        });
        assert_eq!(draws, 0, "no roll for min == max");
        assert_eq!(out.post_delay_ms, 0);
        assert_eq!(out.action_ms, 40);
    }

    #[test]
    fn inverted_ranges_are_normalised_before_the_roll() {
        let mut i = input(Kind::Action);
        i.action_min = 55;
        i.action_max = 25;
        let mut seen = None;
        sample(i, &mut |lo, hi| {
            seen = Some((lo, hi));
            25
        });
        assert_eq!(seen, Some((25, 55)));
    }

    #[test]
    fn kind_codes_roundtrip() {
        for k in [Kind::PostDelayOrStart, Kind::Action, Kind::Init] {
            assert_eq!(Kind::from_code(k.code()), Some(k));
        }
        assert_eq!(Kind::from_code(3), None);
    }
}
