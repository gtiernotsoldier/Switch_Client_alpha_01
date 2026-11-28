//! Deterministic RNG shared with the JVM side.
//!
//! Why this exists: the Kotlin core calls `Random.Default` through
//! `MathUtils.randomFloat` / `randomInt`, which is seeded from the system clock
//! and cannot be reproduced. That is fine for playing, fatal for the golden
//! replay gate in PROTECTION.md 9.5.5 ("same seed, same decisions, diff == 0").
//!
//! So the boundary introduces its own generator: PCG32, ~20 lines, identical on
//! both sides down to the bit. The JVM twin lives in
//! `io.doppel.core.ipc.DecisionRng` and both are pinned by the same golden
//! vectors (`rng_golden_vectors` test here, `DecisionRngTest` there) — if the two
//! ever drift, one of the two test suites goes red immediately instead of the
//! replay diverging 4000 ticks later.
//!
//! All arithmetic is wrapping and 32-bit where it matters, so `u32 -> i32`
//! reinterpretation is the same on both sides. `next_float` uses only the top 24
//! bits, which is exactly representable in f32 — no rounding surprises.

pub struct Pcg32 {
    state: u64,
    inc: u64,
}

const MULT: u64 = 6364136223846793005;
/// Fixed stream selector. Any odd constant works; this keeps one stream so a
/// seed fully determines the sequence.
const INC: u64 = 1442695040888963407;

impl Pcg32 {
    /// The historical single-stream constructor: one fixed increment, so a seed
    /// fully determines the sequence. Golden vectors pin this one; do not
    /// change its behaviour.
    pub fn new(seed: u64) -> Pcg32 {
        Pcg32::with_inc(seed, INC)
    }

    /// Seeded constructor with an explicit stream selector.
    ///
    /// PCG's own stream mechanism: the increment *is* the stream id. Two
    /// generators with the same seed and different (odd) increments walk the
    /// same state lattice through different additive cosets, which decorrelates
    /// them after the two seeding advances — that is the whole reason PCG
    /// exposes `inc` at all. `with_stream(seed, k)` uses `inc = 2k+1`, so
    /// distinct small ids give distinct odd increments.
    ///
    /// Why this matters here: the session seeds all twenty module streams from
    /// one session seed. With the fixed-increment constructor those twenty
    /// streams were the *same sequence* consumed at different rates — replay
    /// isolation, but not statistical independence. Two modules drawing in
    /// lockstep would receive identical values. One increment bit per stream
    /// buys actual independence for the price of nothing.
    pub fn with_stream(seed: u64, stream: u64) -> Pcg32 {
        Pcg32::with_inc(seed, (stream << 1) | 1)
    }

    fn with_inc(seed: u64, inc: u64) -> Pcg32 {
        let mut r = Pcg32 { state: 0, inc };
        // Standard PCG seeding: two steps so the first output is not a function
        // of the seed alone.
        r.state = r.state.wrapping_add(seed);
        r.next_u32();
        r.state = r.state.wrapping_add(seed);
        r.next_u32();
        r
    }

    pub fn next_u32(&mut self) -> u32 {
        let old = self.state;
        self.state = old.wrapping_mul(MULT).wrapping_add(self.inc);
        let xorshifted = (((old >> 18) ^ old) >> 27) as u32;
        let rot = (old >> 59) as u32;
        xorshifted.rotate_right(rot)
    }

    /// Uniform in `[0, bound)`.
    ///
    /// Modulo, not Lemire. The bias is ~2^-32 * bound — for `bound <= 1000` that
    /// is 2e-7, far below anything a human run-length test can see, and modulo
    /// has one unambiguous definition to port instead of two rejection loops that
    /// must agree on their retry order.
    pub fn next_int(&mut self, bound: u32) -> u32 {
        if bound == 0 {
            return 0;
        }
        self.next_u32() % bound
    }

    /// Uniform in `[0, 1)`, 24-bit resolution.
    pub fn next_float(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 / 16_777_216.0f32
    }

    /// Uniform in `[0, 1)`, 32-bit resolution.
    ///
    /// Deliberately coarser than Kotlin's `Random.nextDouble` (53-bit): the
    /// Kotlin twin `DecisionRng.nextDouble` uses the same 32-bit form, so the
    /// two agree bit for bit. Matching Kotlin's *default* generator instead
    /// would be unreproducible by construction.
    pub fn next_double(&mut self) -> f64 {
        self.next_u32() as f64 / 4_294_967_296.0
    }

    /// `MathUtils.randomFloat` semantics, including swapped-bound ordering.
    pub fn random_float(&mut self, a: f32, b: f32) -> f32 {
        let lo = a.min(b);
        let hi = a.max(b);
        lo + self.next_float() * (hi - lo)
    }

    /// `MathUtils.randomInt` semantics: inclusive on both ends.
    pub fn random_int(&mut self, a: i32, b: i32) -> i32 {
        let lo = a.min(b);
        let hi = a.max(b);
        let span = (hi as i64 - lo as i64 + 1) as u32;
        lo + self.next_int(span) as i32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Cross-language golden vectors. These exact numbers are asserted in
    /// `DecisionRngTest.kt`; change one side and the other fails.
    #[test]
    fn rng_golden_vectors() {
        let mut r = Pcg32::new(0x0123_4567_89AB_CDEF);
        assert_eq!(r.next_u32(), 3_983_312_133);
        assert_eq!(r.next_u32(), 118_466_452);
        assert_eq!(r.next_int(100), 62);
        assert_eq!(r.next_int(100), 25);
        let f = r.next_float();
        assert!((f - 0.546_510_16).abs() < 1e-6, "got {}", f);
    }

    /// Stream-selector golden vectors: same seed, stream ids 1 and 2. These
    /// exact numbers are asserted in `DecisionRngTest.kt` on the JVM side.
    /// They are deliberately *different* from the fixed-increment vectors
    /// above — that difference is the point of the stream mechanism.
    #[test]
    fn stream_golden_vectors() {
        let mut s1 = Pcg32::with_stream(0x0123_4567_89AB_CDEF, 1);
        assert_eq!(s1.next_u32(), 440_953_551);
        assert_eq!(s1.next_u32(), 4_112_438_068);
        let mut s2 = Pcg32::with_stream(0x0123_4567_89AB_CDEF, 2);
        assert_eq!(s2.next_u32(), 2_922_926_312);
        assert_eq!(s2.next_u32(), 3_361_374_836);
    }

    #[test]
    fn distinct_streams_are_distinct_sequences() {
        let seed = 42;
        let mut streams: Vec<Pcg32> = (1..=20).map(|k| Pcg32::with_stream(seed, k)).collect();
        // First draw: pairwise distinct across all twenty.
        let firsts: Vec<u32> = streams.iter_mut().map(|r| r.next_u32()).collect();
        for i in 0..firsts.len() {
            for j in (i + 1)..firsts.len() {
                assert_ne!(firsts[i], firsts[j], "streams {} and {} share a first draw", i + 1, j + 1);
            }
        }
        // And none of them equals the fixed-increment sequence's first draw.
        let fixed = Pcg32::new(seed).next_u32();
        assert!(firsts.iter().all(|v| *v != fixed), "a stream matched the legacy sequence");
        // Sustained: the first 64 draws of stream 3 never match stream 17's.
        let mut a = Pcg32::with_stream(seed, 3);
        let mut b = Pcg32::with_stream(seed, 17);
        let mut overlap = 0;
        for _ in 0..64 {
            if a.next_u32() == b.next_u32() {
                overlap += 1;
            }
        }
        assert_eq!(overlap, 0, "streams 3 and 17 aligned within 64 draws");
    }

    #[test]
    fn same_stream_same_sequence() {
        let mut a = Pcg32::with_stream(7, 5);
        let mut b = Pcg32::with_stream(7, 5);
        for _ in 0..1000 {
            assert_eq!(a.next_u32(), b.next_u32());
        }
    }

    #[test]
    fn same_seed_same_sequence() {
        let mut a = Pcg32::new(42);
        let mut b = Pcg32::new(42);
        for _ in 0..1000 {
            assert_eq!(a.next_u32(), b.next_u32());
        }
    }

    #[test]
    fn float_stays_in_unit_interval() {
        let mut r = Pcg32::new(7);
        for _ in 0..100_000 {
            let f = r.next_float();
            assert!((0.0..1.0).contains(&f));
        }
    }

    #[test]
    fn swapped_bounds_are_ordered() {
        let mut r = Pcg32::new(1);
        for _ in 0..1000 {
            let v = r.random_float(0.6, 0.4);
            assert!((0.4..=0.6).contains(&v));
            let i = r.random_int(9, 3);
            assert!((3..=9).contains(&i));
        }
    }

    #[test]
    fn int_range_is_inclusive() {
        let mut r = Pcg32::new(99);
        let mut saw_lo = false;
        let mut saw_hi = false;
        for _ in 0..10_000 {
            let v = r.random_int(2, 5);
            assert!((2..=5).contains(&v));
            saw_lo |= v == 2;
            saw_hi |= v == 5;
        }
        assert!(saw_lo && saw_hi);
    }
}
