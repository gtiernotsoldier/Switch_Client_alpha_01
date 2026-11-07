//! Port of `io.doppel.core.strategy.movement`.

use crate::geom::{java_max, to_radians};
use crate::trig;
use crate::model::Vec3;

// ── SOCD ───────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SocdCorrection {
    pub suppress_forward: bool,
    pub suppress_back: bool,
    pub suppress_left: bool,
    pub suppress_right: bool,
}

impl SocdCorrection {
    pub const NONE: SocdCorrection = SocdCorrection {
        suppress_forward: false,
        suppress_back: false,
        suppress_left: false,
        suppress_right: false,
    };

    pub fn any(&self) -> bool {
        self.suppress_forward || self.suppress_back || self.suppress_left || self.suppress_right
    }
}

/// Most-recently-pressed wins, per axis pair.
///
/// Key state arrives once per tick, so "most recent" means "the later sample".
/// Two keys that rise in the same sample share a sequence number and stay tied —
/// the resolver then does nothing rather than guessing an order it cannot know.
#[derive(Debug, Clone, Copy, Default)]
pub struct SocdResolver {
    counter: i64,
    prev_forward: bool,
    prev_back: bool,
    prev_left: bool,
    prev_right: bool,
    forward_seq: i64,
    back_seq: i64,
    left_seq: i64,
    right_seq: i64,
}

impl SocdResolver {
    pub fn reset(&mut self) {
        *self = SocdResolver::default();
    }

    pub fn resolve(
        &mut self,
        forward: bool,
        back: bool,
        left: bool,
        right: bool,
    ) -> SocdCorrection {
        let was_forward = self.prev_forward;
        let was_back = self.prev_back;
        let was_left = self.prev_left;
        let was_right = self.prev_right;

        self.prev_forward = forward;
        self.prev_back = back;
        self.prev_left = left;
        self.prev_right = right;

        self.counter += 1;
        if forward && !was_forward {
            self.forward_seq = self.counter;
        }
        if back && !was_back {
            self.back_seq = self.counter;
        }
        if left && !was_left {
            self.left_seq = self.counter;
        }
        if right && !was_right {
            self.right_seq = self.counter;
        }

        let suppress_forward = forward && back && self.forward_seq < self.back_seq;
        let suppress_back = forward && back && self.back_seq < self.forward_seq;
        let suppress_left = left && right && self.left_seq < self.right_seq;
        let suppress_right = left && right && self.right_seq < self.left_seq;

        if !suppress_forward && !suppress_back && !suppress_left && !suppress_right {
            return SocdCorrection::NONE;
        }
        SocdCorrection {
            suppress_forward,
            suppress_back,
            suppress_left,
            suppress_right,
        }
    }
}

// ── Instant stop ───────────────────────────────────────────────────────────

/// Below this horizontal speed there is nothing left to brake (blocks/tick).
pub const MIN_SPEED: f64 = 0.02;

/// True while the player is still sliding along the direction they released.
pub fn still_sliding(
    motion_x: f64,
    motion_z: f64,
    yaw_degrees: f32,
    released_forward: bool,
    min_speed: f64,
) -> bool {
    let yaw = to_radians(yaw_degrees as f64);
    let forward_x = -trig::sin(yaw);
    let forward_z = trig::cos(yaw);
    let (dir_x, dir_z) = if released_forward {
        (forward_x, forward_z)
    } else {
        (-forward_x, -forward_z)
    };
    motion_x * dir_x + motion_z * dir_z > min_speed
}

// ── strafe corrector ───────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, Default)]
pub struct StrafeInput {
    pub forward: bool,
    pub back: bool,
    pub left: bool,
    pub right: bool,
}

impl StrafeInput {
    pub fn any(&self) -> bool {
        self.forward || self.back || self.left || self.right
    }
}

#[derive(Debug, Clone, Copy)]
pub struct StrafeState {
    pub distance: f64,
    pub bearing_rad: f64,
    pub yaw_deg: f64,
    pub reach: f64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StrafeCorrection {
    pub suppress_forward: bool,
    pub suppress_back: bool,
    pub suppress_left: bool,
    pub suppress_right: bool,
    pub before: f64,
    pub after: f64,
}

impl StrafeCorrection {
    pub fn changed(&self) -> bool {
        self.suppress_forward || self.suppress_back || self.suppress_left || self.suppress_right
    }
}

/// How much better a neighbour must score before a key is damped.
pub const MIN_GAIN: f64 = 0.15;

pub fn strafe_score(input: StrafeInput, s: StrafeState) -> f64 {
    if !input.any() {
        return 0.0;
    }

    let yaw = to_radians(s.yaw_deg);
    let fx = -trig::sin(yaw);
    let fz = trig::cos(yaw);
    let rx = trig::cos(yaw);
    let rz = trig::sin(yaw);

    let mut vx = 0.0;
    let mut vz = 0.0;
    if input.forward {
        vx += fx;
        vz += fz;
    }
    if input.back {
        vx -= fx;
        vz -= fz;
    }
    if input.right {
        vx += rx;
        vz += rz;
    }
    if input.left {
        vx -= rx;
        vz -= rz;
    }

    // sqrt, not hypot — see the Kotlin twin: the two libms round differently and
    // these values cannot overflow.
    let len = (vx * vx + vz * vz).sqrt();
    if len < 1e-6 {
        return 0.0;
    }
    vx /= len;
    vz /= len;

    let bx = trig::cos(s.bearing_rad);
    let bz = trig::sin(s.bearing_rad);

    let tangential = (vx * bz - vz * bx).abs();
    let radial = vx * bx + vz * bz;

    let omega = tangential / java_max(s.distance, 0.5);

    let radial_penalty = if s.distance <= s.reach && radial > 0.0 {
        radial * 2.0
    } else {
        0.0
    };

    omega - radial_penalty
}

pub fn strafe_correct(input: StrafeInput, s: StrafeState) -> StrafeCorrection {
    if !input.any() {
        return StrafeCorrection {
            suppress_forward: false,
            suppress_back: false,
            suppress_left: false,
            suppress_right: false,
            before: 0.0,
            after: 0.0,
        };
    }

    let before = strafe_score(input, s);

    // Only "release one key" neighbours — adding input is an assist, not a correction.
    let mut neighbours: Vec<(StrafeInput, usize)> = Vec::new();
    if input.forward {
        neighbours.push((StrafeInput { forward: false, ..input }, 0));
    }
    if input.back {
        neighbours.push((StrafeInput { back: false, ..input }, 1));
    }
    if input.left {
        neighbours.push((StrafeInput { left: false, ..input }, 2));
    }
    if input.right {
        neighbours.push((StrafeInput { right: false, ..input }, 3));
    }
    if neighbours.is_empty() {
        return StrafeCorrection {
            suppress_forward: false,
            suppress_back: false,
            suppress_left: false,
            suppress_right: false,
            before,
            after: before,
        };
    }

    // `maxByOrNull` keeps the first maximum, so a strict `>` here is required.
    let mut best = &neighbours[0];
    let mut best_score = strafe_score(best.0, s);
    for n in neighbours.iter().skip(1) {
        let sc = strafe_score(n.0, s);
        if sc > best_score {
            best = n;
            best_score = sc;
        }
    }

    if best_score < before + MIN_GAIN {
        return StrafeCorrection {
            suppress_forward: false,
            suppress_back: false,
            suppress_left: false,
            suppress_right: false,
            before,
            after: before,
        };
    }

    StrafeCorrection {
        suppress_forward: best.1 == 0,
        suppress_back: best.1 == 1,
        suppress_left: best.1 == 2,
        suppress_right: best.1 == 3,
        before,
        after: best_score,
    }
}

/// Convenience: world position helper used by callers building `StrafeState`.
pub fn bearing_to(from: Vec3, to: Vec3) -> f64 {
    trig::atan2(to.z - from.z, to.x - from.x)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socd_newer_key_wins() {
        let mut r = SocdResolver::default();
        // A D held across two samples: D is newer.
        r.resolve(false, false, true, false);
        let c = r.resolve(false, false, true, true);
        assert!(c.suppress_left && !c.suppress_right);
    }

    #[test]
    fn socd_simultaneous_keys_do_nothing() {
        let mut r = SocdResolver::default();
        let c = r.resolve(false, false, true, true);
        assert!(!c.any(), "same-sample presses are ambiguous, not a guess");
    }

    #[test]
    fn socd_release_and_repress_flips_the_winner() {
        let mut r = SocdResolver::default();
        r.resolve(false, false, true, false);
        r.resolve(false, false, true, true); // left older, right newer
        r.resolve(false, false, false, true); // left released
        r.resolve(false, false, true, true); // left pressed again → left newer
        let c = r.resolve(false, false, true, true);
        assert!(c.suppress_right && !c.suppress_left);
    }

    #[test]
    fn still_sliding_forward_at_yaw_zero() {
        // yaw 0 → forward is +Z
        assert!(still_sliding(0.0, 0.3, 0.0, true, MIN_SPEED));
        assert!(!still_sliding(0.0, -0.3, 0.0, true, MIN_SPEED));
        assert!(still_sliding(0.0, -0.3, 0.0, false, MIN_SPEED));
    }

    #[test]
    fn corrector_does_not_add_input() {
        let s = StrafeState {
            distance: 3.0,
            bearing_rad: 0.0,
            yaw_deg: 0.0,
            reach: 3.0,
        };
        let c = strafe_correct(StrafeInput { forward: true, ..Default::default() }, s);
        // Releasing the only key leaves nothing; the corrector must not invent a key.
        if c.changed() {
            assert!(!c.suppress_left && !c.suppress_right);
        }
    }

    #[test]
    fn closing_in_inside_reach_is_penalised() {
        let s = StrafeState {
            distance: 3.0,
            bearing_rad: 0.0,
            yaw_deg: 0.0,
            reach: 3.0,
        };
        let straight = strafe_score(StrafeInput { forward: true, ..Default::default() }, s);
        let diagonal = strafe_score(
            StrafeInput { forward: true, right: true, ..Default::default() },
            s,
        );
        assert!(diagonal < straight, "inside reach, closing in is penalised");
    }
}
