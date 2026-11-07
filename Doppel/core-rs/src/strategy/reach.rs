//! Port of `io.doppel.core.strategy.reach`.

use crate::model::Vec3;

/// Slab test of a ray segment against an AABB, returning the entry distance.
///
/// No `abs(d) < eps` guard on the axis: the Kotlin divides unconditionally and
/// lets IEEE infinities sort it out. Adding a guard would change the answer for
/// the exactly-axis-aligned ray, which is the case a wall of blocks produces.
fn slab(o: f64, d: f64, lo: f64, hi: f64, tmin: &mut f64, tmax: &mut f64) -> bool {
    let mut t1 = (lo - o) / d;
    let mut t2 = (hi - o) / d;
    if t1 > t2 {
        std::mem::swap(&mut t1, &mut t2);
    }
    if t1 > *tmin {
        *tmin = t1;
    }
    if t2 < *tmax {
        *tmax = t2;
    }
    *tmin <= *tmax
}

pub fn intersect_box(
    origin: Vec3,
    dir: Vec3,
    min: Vec3,
    max: Vec3,
    max_dist: f64,
) -> Option<f64> {
    let mut tmin = 0.0;
    let mut tmax = max_dist;

    if !slab(origin.x, dir.x, min.x, max.x, &mut tmin, &mut tmax) {
        return None;
    }
    if !slab(origin.y, dir.y, min.y, max.y, &mut tmin, &mut tmax) {
        return None;
    }
    if !slab(origin.z, dir.z, min.z, max.z, &mut tmin, &mut tmax) {
        return None;
    }

    if tmin >= 0.0 {
        Some(tmin)
    } else {
        Some(0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn straight_ahead_hits_at_expected_distance() {
        let origin = Vec3::new(0.0, 0.0, 0.0);
        let dir = Vec3::new(0.0, 0.0, 1.0);
        let min = Vec3::new(-0.5, -0.5, 3.0);
        let max = Vec3::new(0.5, 0.5, 4.0);
        let t = intersect_box(origin, dir, min, max, 6.0).unwrap();
        assert!((t - 3.0).abs() < 1e-12);
    }

    #[test]
    fn misses_when_offset() {
        let origin = Vec3::new(0.0, 0.0, 0.0);
        let dir = Vec3::new(0.0, 0.0, 1.0);
        let min = Vec3::new(2.0, -0.5, 3.0);
        let max = Vec3::new(3.0, 0.5, 4.0);
        assert!(intersect_box(origin, dir, min, max, 6.0).is_none());
    }

    #[test]
    fn beyond_max_distance_is_a_miss() {
        let origin = Vec3::new(0.0, 0.0, 0.0);
        let dir = Vec3::new(0.0, 0.0, 1.0);
        let min = Vec3::new(-0.5, -0.5, 5.0);
        let max = Vec3::new(0.5, 0.5, 6.0);
        assert!(intersect_box(origin, dir, min, max, 3.0).is_none());
    }

    #[test]
    fn starts_inside_returns_zero() {
        let origin = Vec3::new(0.0, 0.0, 3.5);
        let dir = Vec3::new(0.0, 0.0, 1.0);
        let min = Vec3::new(-0.5, -0.5, 3.0);
        let max = Vec3::new(0.5, 0.5, 4.0);
        assert_eq!(intersect_box(origin, dir, min, max, 6.0), Some(0.0));
    }
}
