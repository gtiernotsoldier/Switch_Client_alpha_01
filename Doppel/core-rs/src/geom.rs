//! Port of `io.doppel.core.algorithm.RotationCalculator` and the small vector
//! helpers it stands on.
//!
//! Arithmetic is transcribed, not paraphrased. Three things that look like
//! cosmetic choices are load-bearing for bit-exact parity with the Kotlin:
//!
//!  - **Degrees to radians is `deg / 180.0 * PI`, never `to_radians()`.**
//!    Java's `Math.toRadians` divides first, Rust's `to_radians` multiplies by
//!    the pre-divided constant, and they disagree on ~27% of inputs by one ulp.
//!    One ulp in a sine is a different unit vector, which is a different
//!    crosshair, which is a different decision.
//!  - **`coerceAtLeast` is written as `if x < 0 { 0 } else { x }`.** Rust's
//!    `f64::max` returns the other operand when one is NaN; Kotlin propagates
//!    the NaN. The difference only shows up on degenerate geometry, which is
//!    exactly the case nobody tests by hand.
//!  - **Angles stay f32, coordinates stay f64.** Kotlin mixes them the same way.
//!    Widening an angle to f64 silently changes every `abs()` comparison.

use crate::model::Vec3;
use crate::rng::Pcg32;
use crate::trig;

/// Eye offset above the feet, as Minecraft defines it.
pub const EYE_HEIGHT: f64 = 1.62;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Vec2 {
    pub yaw: f32,
    pub pitch: f32,
}

impl Vec2 {
    pub fn new(yaw: f32, pitch: f32) -> Vec2 {
        Vec2 { yaw, pitch }
    }

    pub const ZERO: Vec2 = Vec2 { yaw: 0.0, pitch: 0.0 };
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Hitbox {
    pub min_x: f64,
    pub min_y: f64,
    pub min_z: f64,
    pub max_x: f64,
    pub max_y: f64,
    pub max_z: f64,
}

impl Hitbox {
    pub fn new(min_x: f64, min_y: f64, min_z: f64, max_x: f64, max_y: f64, max_z: f64) -> Hitbox {
        Hitbox { min_x, min_y, min_z, max_x, max_y, max_z }
    }

    /// Axis-aligned box of the given size centred on `feet`.
    pub fn around(feet: Vec3, half_width: f64, height: f64) -> Hitbox {
        Hitbox {
            min_x: feet.x - half_width,
            min_y: feet.y,
            min_z: feet.z - half_width,
            max_x: feet.x + half_width,
            max_y: feet.y + height,
            max_z: feet.z + half_width,
        }
    }
}

// ── scalar helpers ─────────────────────────────────────────────────────────

/// Java's `Math.toRadians`: divide, then multiply.
#[inline]
pub fn to_radians(deg: f64) -> f64 {
    deg / 180.0 * std::f64::consts::PI
}

/// Java's `Math.toDegrees`: multiply, then divide.
#[inline]
pub fn to_degrees(rad: f64) -> f64 {
    rad * 180.0 / std::f64::consts::PI
}

/// Java's `Math.max` for two doubles: NaN wins, otherwise the larger value.
///
/// Rust's `f64::max` is the opposite — it returns the *other* operand when one
/// side is NaN. The two agree everywhere except on NaN, which is exactly the
/// input a degenerate hitbox produces and a hand-written test never tries.
#[inline]
pub fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a >= b {
        a
    } else {
        b
    }
}

#[inline]
fn coerce_in(v: f64, lo: f64, hi: f64) -> f64 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

#[inline]
fn coerce_at_least(v: f64, lo: f64) -> f64 {
    if v < lo {
        lo
    } else {
        v
    }
}

/// Normalize an angle to -180..180.
pub fn normalize_angle(angle: f32) -> f32 {
    let mut n = angle % 360.0;
    if n > 180.0 {
        n -= 360.0;
    }
    if n < -180.0 {
        n += 360.0;
    }
    n
}

// ── rotation maths ─────────────────────────────────────────────────────────

pub fn calculate_difference(current: Vec2, target: Vec2) -> Vec2 {
    let yaw_diff = normalize_angle(target.yaw - current.yaw);
    let pitch_diff = target.pitch - current.pitch;
    Vec2::new(yaw_diff, pitch_diff)
}

pub fn is_within_fov(diff: Vec2, horizontal_fov: f32, vertical_fov: f32) -> bool {
    diff.yaw.abs() <= horizontal_fov / 2.0 && diff.pitch.abs() <= vertical_fov / 2.0
}

/// Spherical FOV: angle between the current aim direction and the direction to
/// the target must be within `fov / 2`.
pub fn is_within_fov_3d(origin: Vec3, aim: Vec2, target_point: Vec3, fov: f32) -> bool {
    let to_target = Vec3::new(
        target_point.x - origin.x,
        target_point.y - origin.y,
        target_point.z - origin.z,
    );
    let dist = to_target.length();
    if dist < 1e-6 {
        return true;
    }
    let dir = aim_to_direction(aim);
    let cos_angle = coerce_in(dir.dot(&to_target) / dist, -1.0, 1.0);
    let angle = to_degrees(trig::acos(cos_angle)) as f32;
    angle <= fov / 2.0
}

pub fn interpolate(current: Vec2, target: Vec2, factor: f32) -> Vec2 {
    let f = factor.clamp(0.0, 1.0);
    Vec2::new(
        current.yaw + (target.yaw - current.yaw) * f,
        current.pitch + (target.pitch - current.pitch) * f,
    )
}

pub fn interpolate_axes(current: Vec2, target: Vec2, yaw_factor: f32, pitch_factor: f32) -> Vec2 {
    let yf = yaw_factor.clamp(0.0, 1.0);
    let pf = pitch_factor.clamp(0.0, 1.0);
    Vec2::new(
        current.yaw + (target.yaw - current.yaw) * yf,
        current.pitch + (target.pitch - current.pitch) * pf,
    )
}

/// Aim rotation to unit direction. Minecraft convention: yaw 0 faces +Z
/// (south), yaw 90 faces -X (west) — forward is `(-sin yaw, cos yaw)`, same
/// as every movement module in this crate.
pub fn aim_to_direction(aim: Vec2) -> Vec3 {
    let yaw_rad = to_radians(aim.yaw as f64);
    let pitch_rad = to_radians(aim.pitch as f64);
    let cos_pitch = trig::cos(pitch_rad);
    Vec3::new(
        -trig::sin(yaw_rad) * cos_pitch,
        -trig::sin(pitch_rad),
        trig::cos(yaw_rad) * cos_pitch,
    )
}

/// Horizontal unit direction for a yaw angle.
pub fn yaw_to_direction(yaw: f32) -> Vec3 {
    let rad = to_radians(yaw as f64);
    Vec3::new(-trig::sin(rad), 0.0, trig::cos(rad))
}

/// Rotation from one world point to another.
pub fn calculate_rotation(from: Vec3, to: Vec3) -> Vec2 {
    let diff_x = to.x - from.x;
    let diff_y = to.y - from.y;
    let diff_z = to.z - from.z;

    let distance = (diff_x * diff_x + diff_z * diff_z).sqrt();
    let yaw = ((trig::atan2(diff_z, diff_x) as f32) * (180.0f32 / std::f32::consts::PI)) - 90.0;
    let pitch = -((trig::atan2(diff_y, distance) as f32) * (180.0f32 / std::f32::consts::PI));
    Vec2::new(yaw, pitch)
}

/// Raven's `smoothRotation`: a fixed angular step per tick, applied to both
/// axes equally so the aim travels in a straight line to the target.
pub fn step_scale(delta_yaw: f32, delta_pitch: f32, speed: i32, smoothness: f32) -> f32 {
    let magnitude = (delta_yaw * delta_yaw + delta_pitch * delta_pitch).sqrt();
    if magnitude < 0.001 {
        return 1.0;
    }
    if speed <= 0 {
        return 0.0;
    }
    if speed >= 30 {
        return 1.0;
    }
    let t = speed as f32 / 30.0;
    let step_degrees = t * t * 180.0 * smoothness.clamp(0.0, 1.0);
    // Divide first, then clamp — the Kotlin does `min(1f, step / magnitude)`.
    // Reordering to `min(step, magnitude) / magnitude` is mathematically the same
    // and numerically different on the rounding boundary.
    (step_degrees / magnitude).min(1.0)
}

/// Move at most `diff` degrees toward `target`, with the small dead zone that
/// keeps the assist from micro-jittering at the edge.
pub fn rot_move(target: f32, current: f32, diff: f32, rng: &mut Pcg32) -> f32 {
    if diff <= 0.0 {
        return current;
    }
    let delta = normalize_angle(target - current);
    // `0.1f * nextFloat() + 0.1f` — one draw even when the delta is large,
    // because the Kotlin evaluates the threshold before the comparison.
    let threshold = 0.1f32 * rng.next_float() + 0.1;
    if delta.abs() < threshold {
        return current;
    }
    if delta.abs() <= diff {
        return current + delta;
    }
    current + if delta < 0.0 { -diff } else { diff }
}

// ── hitbox geometry ────────────────────────────────────────────────────────

pub fn hitbox_center(box_: &Hitbox) -> Vec3 {
    Vec3::new(
        (box_.min_x + box_.max_x) / 2.0,
        (box_.min_y + box_.max_y) / 2.0,
        (box_.min_z + box_.max_z) / 2.0,
    )
}

pub fn hitbox_corners(box_: &Hitbox) -> [Vec3; 8] {
    [
        Vec3::new(box_.min_x, box_.min_y, box_.min_z),
        Vec3::new(box_.min_x, box_.min_y, box_.max_z),
        Vec3::new(box_.min_x, box_.max_y, box_.min_z),
        Vec3::new(box_.min_x, box_.max_y, box_.max_z),
        Vec3::new(box_.max_x, box_.min_y, box_.min_z),
        Vec3::new(box_.max_x, box_.min_y, box_.max_z),
        Vec3::new(box_.max_x, box_.max_y, box_.min_z),
        Vec3::new(box_.max_x, box_.max_y, box_.max_z),
    ]
}

/// Entry parameter t of a ray into the AABB, slab method. `None` when missed.
pub fn ray_aabb_entry(origin: Vec3, dir: Vec3, box_: &Hitbox) -> Option<f64> {
    let mut tmin = f64::NEG_INFINITY;
    let mut tmax = f64::INFINITY;

    let axes = [
        (origin.x, dir.x, box_.min_x, box_.max_x),
        (origin.y, dir.y, box_.min_y, box_.max_y),
        (origin.z, dir.z, box_.min_z, box_.max_z),
    ];

    for (o, d, lo, hi) in axes {
        if d.abs() < 1e-8 {
            if o < lo || o > hi {
                return None;
            }
        } else {
            let inv_d = 1.0 / d;
            let mut t1 = (lo - o) * inv_d;
            let mut t2 = (hi - o) * inv_d;
            if t1 > t2 {
                std::mem::swap(&mut t1, &mut t2);
            }
            if t1 > tmin {
                tmin = t1;
            }
            if t2 < tmax {
                tmax = t2;
            }
            if tmin > tmax {
                return None;
            }
        }
    }

    if tmax < 0.0 {
        None
    } else if tmin < 0.0 {
        Some(0.0)
    } else {
        Some(tmin)
    }
}

pub fn ray_intersects_aabb(origin: Vec3, dir: Vec3, box_: &Hitbox) -> bool {
    let mut tmin = f64::NEG_INFINITY;
    let mut tmax = f64::INFINITY;

    let axes = [
        (origin.x, dir.x, box_.min_x, box_.max_x),
        (origin.y, dir.y, box_.min_y, box_.max_y),
        (origin.z, dir.z, box_.min_z, box_.max_z),
    ];

    for (o, d, lo, hi) in axes {
        if d.abs() < 1e-8 {
            if o < lo || o > hi {
                return false;
            }
        } else {
            let inv_d = 1.0 / d;
            let mut t1 = (lo - o) * inv_d;
            let mut t2 = (hi - o) * inv_d;
            if t1 > t2 {
                std::mem::swap(&mut t1, &mut t2);
            }
            if t1 > tmin {
                tmin = t1;
            }
            if t2 < tmax {
                tmax = t2;
            }
            if tmin > tmax {
                return false;
            }
        }
    }

    tmax >= 0.0 && tmin <= tmax
}

pub fn is_inside_hitbox(player_pos: Vec3, current_aim: Vec2, hitbox: &Hitbox) -> bool {
    let eye = Vec3::new(player_pos.x, player_pos.y + EYE_HEIGHT, player_pos.z);
    let dir = aim_to_direction(current_aim);
    ray_intersects_aabb(eye, dir, hitbox)
}

pub fn ray_hit_point(origin: Vec3, aim: Vec2, hitbox: &Hitbox) -> Option<Vec3> {
    let dir = aim_to_direction(aim);
    let t = ray_aabb_entry(origin, dir, hitbox)?;
    Some(Vec3::new(
        origin.x + dir.x * t,
        origin.y + dir.y * t,
        origin.z + dir.z * t,
    ))
}

#[derive(Debug, Clone, Copy)]
pub struct BoxEdgeTarget {
    pub world: Vec3,
    pub rotation: Vec2,
}

pub fn get_box_edge_target(origin: Vec3, aim: Vec2, hitbox: &Hitbox) -> Option<BoxEdgeTarget> {
    let dir = aim_to_direction(aim);
    if ray_aabb_entry(origin, dir, hitbox).is_some() {
        return None; // already inside
    }
    let surface = closest_surface_point(origin, dir, hitbox);
    Some(BoxEdgeTarget {
        world: surface,
        rotation: calculate_rotation(origin, surface),
    })
}

#[derive(Debug, Clone, Copy)]
pub struct BoxAngleRange {
    pub yaw_min: f32,
    pub yaw_max: f32,
    pub pitch_min: f32,
    pub pitch_max: f32,
}

impl BoxAngleRange {
    pub fn is_degenerate(&self) -> bool {
        self.yaw_min.is_nan()
            || self.yaw_max.is_nan()
            || self.pitch_min.is_nan()
            || self.pitch_max.is_nan()
    }
}

pub fn get_box_angle_range(eye: Vec3, box_: &Hitbox) -> BoxAngleRange {
    let corners = hitbox_corners(box_);
    let mut yaw_min = f32::MAX;
    let mut yaw_max = -f32::MAX;
    let mut pitch_min = f32::MAX;
    let mut pitch_max = -f32::MAX;

    for c in corners {
        let rot = calculate_rotation(eye, c);
        let yaw = normalize_angle(rot.yaw);
        // Values below -90 are lifted by 360 to keep the span continuous.
        let yaw_wrapped = if yaw < -90.0 { yaw + 360.0 } else { yaw };
        if yaw_wrapped < yaw_min {
            yaw_min = yaw_wrapped;
        }
        if yaw_wrapped > yaw_max {
            yaw_max = yaw_wrapped;
        }
        if rot.pitch < pitch_min {
            pitch_min = rot.pitch;
        }
        if rot.pitch > pitch_max {
            pitch_max = rot.pitch;
        }
    }

    yaw_min = normalize_angle(yaw_min);
    yaw_max = normalize_angle(yaw_max);
    if yaw_min > yaw_max {
        // Wrapped across ±180 — the target surrounds the view line.
        yaw_min = -180.0;
        yaw_max = 180.0;
    }

    BoxAngleRange { yaw_min, yaw_max, pitch_min, pitch_max }
}

pub fn multipoint_aim_point(
    eye: Vec3,
    box_: &Hitbox,
    multipoint_x: f32,
    multipoint_y: f32,
) -> Vec3 {
    let center = hitbox_center(box_);
    let dir = Vec3::new(center.x - eye.x, center.y - eye.y, center.z - eye.z).normalize();
    let closest = closest_surface_point(eye, dir, box_);
    let fx = multipoint_x.clamp(0.0, 1.0);
    let fy = multipoint_y.clamp(0.0, 1.0);
    Vec3::new(
        center.x + (closest.x - center.x) * fx as f64,
        center.y + (closest.y - center.y) * fy as f64,
        center.z + (closest.z - center.z) * fx as f64,
    )
}

/// Aim at the box centre, or a uniformly random point inside it.
pub fn calculate_target_point(
    player_pos: Vec3,
    hitbox: &Hitbox,
    lock_on_crosshair: bool,
    rng: &mut Pcg32,
) -> Vec2 {
    let eye = Vec3::new(player_pos.x, player_pos.y + EYE_HEIGHT, player_pos.z);
    let target = if lock_on_crosshair {
        hitbox_center(hitbox)
    } else {
        Vec3::new(
            random_in_range(hitbox.min_x, hitbox.max_x, rng),
            random_in_range(hitbox.min_y, hitbox.max_y, rng),
            random_in_range(hitbox.min_z, hitbox.max_z, rng),
        )
    };
    calculate_rotation(eye, target)
}

fn random_in_range(min: f64, max: f64, rng: &mut Pcg32) -> f64 {
    min + rng.next_double() * (max - min)
}

fn origin_coordinate(origin: Vec3, axis: usize) -> f64 {
    match axis {
        0 => origin.x,
        1 => origin.y,
        2 => origin.z,
        _ => 0.0,
    }
}

fn closest_point_on_face_to_ray(
    origin: Vec3,
    dir: Vec3,
    box_: &Hitbox,
    axis: usize,
    value: f64,
) -> Option<Vec3> {
    let d = match axis {
        0 => dir.x,
        1 => dir.y,
        2 => dir.z,
        _ => 0.0,
    };
    if d.abs() < 1e-9 {
        return None; // parallel
    }
    let t = (value - origin_coordinate(origin, axis)) / d;
    if t < 0.0 {
        return None; // behind
    }
    let px = origin.x + dir.x * t;
    let py = origin.y + dir.y * t;
    let pz = origin.z + dir.z * t;
    let x = if axis == 0 { value } else { coerce_in(px, box_.min_x, box_.max_x) };
    let y = if axis == 1 { value } else { coerce_in(py, box_.min_y, box_.max_y) };
    let z = if axis == 2 { value } else { coerce_in(pz, box_.min_z, box_.max_z) };
    Some(Vec3::new(x, y, z))
}

fn distance_point_to_ray(p: Vec3, origin: Vec3, dir: Vec3) -> f64 {
    let dx = p.x - origin.x;
    let dy = p.y - origin.y;
    let dz = p.z - origin.z;
    let t = dx * dir.x + dy * dir.y + dz * dir.z;
    if t < 0.0 {
        return (dx * dx + dy * dy + dz * dz).sqrt();
    }
    let proj = Vec3::new(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t);
    p.distance_to(&proj)
}

fn closest_surface_point(origin: Vec3, dir: Vec3, box_: &Hitbox) -> Vec3 {
    let faces = [
        (0usize, box_.min_x),
        (0, box_.max_x),
        (1, box_.min_y),
        (1, box_.max_y),
        (2, box_.min_z),
        (2, box_.max_z),
    ];
    let mut best: Option<Vec3> = None;
    let mut best_dist = f64::MAX;
    for (axis, value) in faces {
        let p = match closest_point_on_face_to_ray(origin, dir, box_, axis, value) {
            Some(p) => p,
            None => continue,
        };
        let d = distance_point_to_ray(p, origin, dir);
        if d < best_dist {
            best_dist = d;
            best = Some(p);
        }
    }
    best.unwrap_or_else(|| hitbox_center(box_))
}

/// Closest point on segment AB to a ray (origin + dir*t, t>=0).
fn closest_point_on_segment_to_ray(a: Vec3, b: Vec3, ray_origin: Vec3, ray_dir: Vec3) -> Vec3 {
    let seg = Vec3::new(b.x - a.x, b.y - a.y, b.z - a.z);
    let seg_len_sq = seg.dot(&seg);
    if seg_len_sq < 1e-12 {
        return a;
    }

    let r = Vec3::new(ray_origin.x - a.x, ray_origin.y - a.y, ray_origin.z - a.z);
    let a_dot_a = seg_len_sq;
    let a_dot_b = seg.dot(&ray_dir);
    let b_dot_b = ray_dir.dot(&ray_dir);
    let a_dot_r = seg.dot(&r);
    let b_dot_r = ray_dir.dot(&r);

    let denom = a_dot_a * b_dot_b - a_dot_b * a_dot_b;
    let mut s: f64;

    if denom.abs() < 1e-12 {
        s = 0.0;
    } else {
        s = (a_dot_r * b_dot_b - b_dot_r * a_dot_b) / denom;
    }

    s = coerce_in(s, 0.0, 1.0);

    Vec3::new(a.x + seg.x * s, a.y + seg.y * s, a.z + seg.z * s)
}

// ── box edge (corner) aiming ───────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Face {
    MinX,
    MaxX,
    MinY,
    MaxY,
    MinZ,
    MaxZ,
}

const ALL_FACES: [Face; 6] = [
    Face::MinX,
    Face::MaxX,
    Face::MinY,
    Face::MaxY,
    Face::MinZ,
    Face::MaxZ,
];

/// Corner indices match `hitbox_corners`.
fn face_topology(face: Face) -> &'static [(usize, usize)] {
    match face {
        Face::MinX => &[(0, 1), (0, 2), (1, 3), (2, 3)],
        Face::MaxX => &[(4, 5), (4, 6), (5, 7), (6, 7)],
        Face::MinY => &[(0, 1), (0, 4), (1, 5), (4, 5)],
        Face::MaxY => &[(2, 3), (2, 6), (3, 7), (6, 7)],
        Face::MinZ => &[(0, 2), (0, 4), (2, 6), (4, 6)],
        Face::MaxZ => &[(1, 3), (1, 5), (3, 7), (5, 7)],
    }
}

fn visible_faces(eye: Vec3, hitbox: &Hitbox) -> Vec<Face> {
    let mut faces = Vec::with_capacity(6);
    let axes = [
        (eye.x, hitbox.min_x, hitbox.max_x, Face::MinX, Face::MaxX),
        (eye.y, hitbox.min_y, hitbox.max_y, Face::MinY, Face::MaxY),
        (eye.z, hitbox.min_z, hitbox.max_z, Face::MinZ, Face::MaxZ),
    ];
    for (p, lo, hi, min_face, max_face) in axes {
        if p < lo {
            faces.push(min_face);
        } else if p > hi {
            faces.push(max_face);
        } else {
            // Inside the slab on this axis: the nearer face wins, and ties push
            // both (the Kotlin uses `<=` on both sides).
            let dist_min = (p - lo).abs();
            let dist_max = (p - hi).abs();
            if dist_min <= dist_max {
                faces.push(min_face);
            }
            if dist_max <= dist_min {
                faces.push(max_face);
            }
        }
    }
    if faces.is_empty() {
        faces.extend_from_slice(&ALL_FACES);
    }
    faces
}

pub fn get_closest_box_edge(player_pos: Vec3, current_aim: Vec2, hitbox: &Hitbox) -> Vec2 {
    let eye = Vec3::new(player_pos.x, player_pos.y + EYE_HEIGHT, player_pos.z);
    let faces = visible_faces(eye, hitbox);
    let corners = hitbox_corners(hitbox);
    let aim_dir = aim_to_direction(current_aim);

    let mut best_point: Option<Vec3> = None;
    let mut best_angular = f64::MAX;

    for face in faces {
        for (i, j) in face_topology(face) {
            let closest = closest_point_on_segment_to_ray(corners[*i], corners[*j], eye, aim_dir);
            let rot = calculate_rotation(eye, closest);
            let yaw_diff = normalize_angle(rot.yaw - current_aim.yaw).abs();
            let pitch_diff = (rot.pitch - current_aim.pitch).abs();
            let angular = (yaw_diff + pitch_diff) as f64;
            if angular < best_angular {
                best_angular = angular;
                best_point = Some(closest);
            }
        }
    }

    match best_point {
        Some(p) => calculate_rotation(eye, p),
        None => current_aim,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f64, b: f64) -> bool {
        (a - b).abs() < 1e-9
    }

    #[test]
    fn normalize_wraps_into_range() {
        assert_eq!(normalize_angle(190.0), -170.0);
        assert_eq!(normalize_angle(-190.0), 170.0);
        assert_eq!(normalize_angle(0.0), 0.0);
    }

    #[test]
    fn to_radians_matches_java_rounding() {
        // The whole reason this helper exists: x/180*PI, not x*(PI/180).
        let deg = -179.9997f64;
        assert_eq!(to_radians(deg), deg / 180.0 * std::f64::consts::PI);
        assert_ne!(to_radians(deg), deg.to_radians());
    }

    #[test]
    fn centrel_ray_hits_box() {
        let bx = Hitbox::new(-0.3, 64.0, -0.3, 0.3, 65.8, 0.3);
        let eye = Vec3::new(0.0, 65.62, 0.0);
        assert!(ray_intersects_aabb(eye, Vec3::new(0.0, 0.0, 1.0), &bx));
    }

    #[test]
    fn box_angle_range_is_ordered() {
        let bx = Hitbox::new(1.7, 64.0, -0.3, 2.3, 65.8, 0.3);
        let eye = Vec3::new(0.0, 65.62, 0.0);
        let r = get_box_angle_range(eye, &bx);
        assert!(!r.is_degenerate());
        assert!(r.yaw_min <= r.yaw_max);
    }

    #[test]
    fn step_scale_is_monotonic_in_speed() {
        let prev = step_scale(10.0, 5.0, 1, 1.0);
        let next = step_scale(10.0, 5.0, 10, 1.0);
        assert!(next >= prev);
        assert!(approx(step_scale(0.0, 0.0, 15, 1.0) as f64, 1.0));
    }
}
