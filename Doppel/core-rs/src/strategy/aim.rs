//! Port of `io.doppel.core.strategy.aim` — LEGIT/NORMAL/LINEAR and SELF_ADAPTIVE.
//!
//! The two modes differ in exactly two places: the aim point (SELF_ADAPTIVE
//! leads the target by 0.1 s of its velocity first) and the speed model
//! (SELF_ADAPTIVE uses the per-axis fractions, LEGIT the shared angular step).
//! Everything else — range gate, condition engine, FOV cone, drift, lock gate —
//! is identical, and it is written once here for that reason. In the Kotlin the
//! duplication is ~80 lines; keeping it duplicated in the port would mean two
//! places to drift apart.

use crate::condition;
use crate::geom::{
    calculate_difference, calculate_rotation, multipoint_aim_point, step_scale, Hitbox, Vec2,
};
use crate::model::{PlayerState, TargetState, TriggerOptions, Vec3};
use crate::rng::Pcg32;

const EYE_HEIGHT: f64 = 1.62;
const LOCK_ANGLE: f32 = 8.0;
const ON_TARGET_FACTOR: f32 = 0.85;
const CLICK_SPEED_BOOST: f32 = 1.5;
const FULL_FOV: f32 = 360.0;
const T_LEAD: f64 = 0.1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AimMode {
    Legit,
    Normal,
    SelfAdaptive,
    Linear,
}

#[derive(Debug, Clone, Copy)]
pub struct AimConfig {
    pub mode: AimMode,
    pub range_min: f32,
    pub range_max: f32,
    pub fov: f32,
    pub min_fov: f32,
    pub aim_speed_y: f32,
    pub aim_speed_p: f32,
    pub speed: i32,
    pub smoothness: f32,
    pub noise_intensity: f32,
    pub offset: f32,
    pub multipoint_x: f32,
    pub multipoint_y: f32,
    pub linear: bool,
    pub lock_on_crosshair: bool,
    pub trigger_options: TriggerOptions,
}

impl Default for AimConfig {
    fn default() -> Self {
        AimConfig {
            mode: AimMode::Legit,
            range_min: 3.0,
            range_max: 6.0,
            fov: 360.0,
            min_fov: 8.0,
            aim_speed_y: 0.25,
            aim_speed_p: 0.1,
            speed: 10,
            smoothness: 0.85,
            noise_intensity: 0.05,
            offset: 0.5,
            multipoint_x: 0.5,
            multipoint_y: 0.5,
            linear: false,
            lock_on_crosshair: false,
            trigger_options: TriggerOptions::default(),
        }
    }
}

/// Per-session aim state. Only the drift fields and the last target id survive
/// between ticks; everything else is recomputed from the snapshot.
#[derive(Debug, Clone, Copy)]
pub struct AimState {
    pub last_target_id: i32,
    pub drift_yaw: f32,
    pub drift_pitch: f32,
    pub drift_goal_yaw: f32,
    pub drift_goal_pitch: f32,
    pub drift_ticks_left: i32,
}

impl Default for AimState {
    fn default() -> Self {
        AimState {
            last_target_id: -1,
            drift_yaw: 0.0,
            drift_pitch: 0.0,
            drift_goal_yaw: 0.0,
            drift_goal_pitch: 0.0,
            drift_ticks_left: 0,
        }
    }
}

impl AimState {
    pub fn reset(&mut self) {
        *self = AimState::default();
    }
}

#[derive(Debug, Clone, Copy)]
pub struct AimInput {
    pub player: PlayerState,
    pub target: Option<TargetState>,
    pub mouse_delta_x: f32,
    pub mouse_delta_y: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AimResult {
    ApplyRotation {
        world_point: Vec3,
        hitbox: Option<Hitbox>,
        fraction_y: f32,
        fraction_p: f32,
    },
    Skip,
}

impl AimResult {
    pub fn is_skip(&self) -> bool {
        matches!(self, AimResult::Skip)
    }
}

/// Natural drift: the aim point wanders within ±offset degrees instead of
/// locking dead-on. Picks a new goal every 20–59 ticks and eases 10% toward it.
pub fn update_natural_drift(state: &mut AimState, offset: f32, rng: &mut Pcg32) -> (f32, f32) {
    if offset <= 0.0 {
        state.drift_yaw = 0.0;
        state.drift_pitch = 0.0;
        return (0.0, 0.0);
    }
    if state.drift_ticks_left <= 0 {
        state.drift_goal_yaw = rng.next_float() * 2.0 - 1.0;
        state.drift_goal_pitch = rng.next_float() * 2.0 - 1.0;
        state.drift_ticks_left = 20 + rng.next_int(40) as i32;
    }
    state.drift_ticks_left -= 1;
    state.drift_yaw += (state.drift_goal_yaw * offset - state.drift_yaw) * 0.1;
    state.drift_pitch += (state.drift_goal_pitch * offset * 0.6 - state.drift_pitch) * 0.1;
    (state.drift_yaw, state.drift_pitch)
}

/// Shared front half: everything up to (and including) the world point.
/// Returns `None` for "skip this tick".
fn aim_point_and_rotation(
    config: &AimConfig,
    player: &PlayerState,
    target: &TargetState,
    predicted_box: &Hitbox,
    rng: &mut Pcg32,
) -> Option<(Vec3, Vec2)> {
    let eye = Vec3::new(player.position.x, player.position.y + EYE_HEIGHT, player.position.z);
    let aim = Vec2::new(player.yaw, player.pitch);

    // Range is measured centre-to-centre, not eye-to-box: the Kotlin compares
    // `player.position.distanceTo(target.position)`, and a 0.2-block difference
    // at the range boundary flips the whole module on or off.
    let distance_3d = player.position.distance_to(&target.position);
    if distance_3d < config.range_min as f64 || distance_3d > config.range_max as f64 {
        return None;
    }

    if condition::check(&config.trigger_options, player, Some(target), rng).is_some() {
        return None;
    }

    let aim_point = multipoint_aim_point(eye, predicted_box, config.multipoint_x, config.multipoint_y);
    let target_rot = calculate_rotation(eye, aim_point);

    if config.fov < FULL_FOV {
        let diff = calculate_difference(aim, target_rot);
        if diff.yaw.abs() > config.fov / 2.0 || diff.pitch.abs() > config.fov / 2.0 {
            return None;
        }
    }

    Some((aim_point, target_rot))
}

/// Applies the drift offset in world space around the aim point.
fn with_drift(aim_point: Vec3, eye: Vec3, state: &mut AimState, config: &AimConfig, rng: &mut Pcg32) -> Vec3 {
    let (drift_yaw, drift_pitch) = update_natural_drift(state, config.offset, rng);
    let eye_to_target = Vec3::new(
        aim_point.x - eye.x,
        aim_point.y - eye.y,
        aim_point.z - eye.z,
    );
    let dist = eye_to_target.length();
    let denom = if dist > 0.001 { dist } else { 1.0 };
    let lat_x = -eye_to_target.z / denom;
    let lat_z = eye_to_target.x / denom;
    Vec3::new(
        aim_point.x + lat_x * drift_yaw as f64 * 0.02 * dist,
        aim_point.y + drift_pitch as f64 * 0.02 * dist,
        aim_point.z + lat_z * drift_yaw as f64 * 0.02 * dist,
    )
}

/// LEGIT / NORMAL / LINEAR.
pub fn legit_tick(
    config: &AimConfig,
    state: &mut AimState,
    player: &PlayerState,
    target: Option<&TargetState>,
    rng: &mut Pcg32,
) -> AimResult {
    let target = match target {
        Some(t) => t,
        None => {
            state.reset();
            return AimResult::Skip;
        }
    };

    let eye = Vec3::new(player.position.x, player.position.y + EYE_HEIGHT, player.position.z);
    let aim = Vec2::new(player.yaw, player.pitch);

    let (aim_point, target_rot) = match aim_point_and_rotation(
        config, player, target, &target.hitbox, rng,
    ) {
        Some(v) => v,
        None => return AimResult::Skip,
    };

    let final_world = with_drift(aim_point, eye, state, config, rng);

    if config.lock_on_crosshair {
        let diff = calculate_difference(aim, target_rot);
        if diff.yaw.abs() > LOCK_ANGLE || diff.pitch.abs() > LOCK_ANGLE {
            return AimResult::Skip;
        }
    }

    let click_boost = if player.is_attack_key_down() { CLICK_SPEED_BOOST } else { 1.0 };
    let rot_diff = calculate_difference(aim, target_rot);
    let on_target = if rot_diff.yaw.abs() < 5.0 && rot_diff.pitch.abs() < 3.0 {
        ON_TARGET_FACTOR
    } else {
        1.0
    };
    let raw = step_scale(rot_diff.yaw, rot_diff.pitch, config.speed, config.smoothness)
        * click_boost
        * on_target;
    let scale = if raw < 0.0 {
        0.0
    } else if raw > 1.0 {
        1.0
    } else {
        raw
    };

    AimResult::ApplyRotation {
        world_point: final_world,
        hitbox: Some(target.hitbox),
        fraction_y: scale,
        fraction_p: scale,
    }
}

/// SELF_ADAPTIVE: predictive lead on the hitbox, per-axis fractions.
pub fn self_adaptive_tick(
    config: &AimConfig,
    state: &mut AimState,
    player: &PlayerState,
    target: Option<&TargetState>,
    rng: &mut Pcg32,
) -> AimResult {
    let target = match target {
        Some(t) => t,
        None => {
            state.reset();
            return AimResult::Skip;
        }
    };

    let eye = Vec3::new(player.position.x, player.position.y + EYE_HEIGHT, player.position.z);
    let aim = Vec2::new(player.yaw, player.pitch);

    let hb = &target.hitbox;
    let predicted = Hitbox::new(
        hb.min_x + target.motion.x * T_LEAD,
        hb.min_y + target.motion.y * T_LEAD,
        hb.min_z + target.motion.z * T_LEAD,
        hb.max_x + target.motion.x * T_LEAD,
        hb.max_y + target.motion.y * T_LEAD,
        hb.max_z + target.motion.z * T_LEAD,
    );

    let (aim_point, target_rot) = match aim_point_and_rotation(
        config, player, target, &predicted, rng,
    ) {
        Some(v) => v,
        None => return AimResult::Skip,
    };

    // Target-switch bookkeeping happens after the gates in the Kotlin too, so a
    // skipped tick does not update last_target_id.
    if target.id != state.last_target_id {
        state.last_target_id = target.id;
    }

    let final_world = with_drift(aim_point, eye, state, config, rng);
    let rot_diff = calculate_difference(aim, target_rot);

    if config.lock_on_crosshair {
        if rot_diff.yaw.abs() > LOCK_ANGLE || rot_diff.pitch.abs() > LOCK_ANGLE {
            return AimResult::Skip;
        }
    }

    let click_boost = if player.is_attack_key_down() { CLICK_SPEED_BOOST } else { 1.0 };
    let on_target = if rot_diff.yaw.abs() < 5.0 && rot_diff.pitch.abs() < 3.0 {
        ON_TARGET_FACTOR
    } else {
        1.0
    };

    AimResult::ApplyRotation {
        world_point: final_world,
        hitbox: Some(predicted),
        fraction_y: config.aim_speed_y * click_boost * on_target,
        fraction_p: config.aim_speed_p * click_boost * on_target,
    }
}

/// Dispatch on the configured mode, matching `AimStrategyFactory`.
pub fn execute(
    config: &AimConfig,
    state: &mut AimState,
    input: &AimInput,
    rng: &mut Pcg32,
) -> AimResult {
    match config.mode {
        AimMode::SelfAdaptive => {
            self_adaptive_tick(config, state, &input.player, input.target.as_ref(), rng)
        }
        // LEGIT, NORMAL and LINEAR all run the shared step model; `linear` only
        // changes how the main render thread consumes the fractions.
        _ => legit_tick(config, state, &input.player, input.target.as_ref(), rng),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{F_MOVING, F_ON_GROUND};

    fn player(yaw: f32) -> PlayerState {
        PlayerState {
            position: Vec3::new(0.0, 64.0, 0.0),
            motion: Vec3::new(0.0, 0.0, 0.0),
            yaw,
            pitch: 0.0,
            health: 20.0,
            hurt_time: 0,
            hurt_resistant_time: 0,
            weapon: crate::model::WeaponType::Sword,
            flags: F_ON_GROUND | F_MOVING,
            ticks: 0,
        }
    }

    fn target_at(x: f64) -> TargetState {
        let pos = Vec3::new(x, 64.0, 0.0);
        TargetState {
            id: 3,
            position: pos,
            motion: Vec3::new(0.05, 0.0, 0.0),
            hitbox: Hitbox::around(pos, 0.3, 1.8),
            distance: x as f32,
            health: 20.0,
            hurt_time: 0,
            is_moving_backward: false,
            is_moving_towards_player: false,
        }
    }

    #[test]
    fn no_target_skips_and_resets() {
        let mut st = AimState { drift_yaw: 4.0, ..Default::default() };
        let mut rng = Pcg32::new(1);
        let r = legit_tick(&AimConfig::default(), &mut st, &player(0.0), None, &mut rng);
        assert!(r.is_skip());
        assert_eq!(st.drift_yaw, 0.0);
    }

    #[test]
    fn out_of_range_skips() {
        let mut st = AimState::default();
        let mut rng = Pcg32::new(1);
        let mut cfg = AimConfig::default();
        cfg.range_min = 3.0;
        cfg.range_max = 4.5;
        let t = target_at(1.0);
        let r = legit_tick(&cfg, &mut st, &player(0.0), Some(&t), &mut rng);
        assert!(r.is_skip(), "distance 1.0 is below range_min");
    }

    #[test]
    fn in_range_applies_rotation_within_unit_fraction() {
        let mut st = AimState::default();
        let mut rng = Pcg32::new(7);
        let t = target_at(5.0);
        let r = legit_tick(&AimConfig::default(), &mut st, &player(0.0), Some(&t), &mut rng);
        match r {
            AimResult::ApplyRotation { fraction_y, fraction_p, .. } => {
                assert!((0.0..=1.0).contains(&fraction_y));
                assert!((0.0..=1.0).contains(&fraction_p));
            }
            _ => panic!("expected ApplyRotation"),
        }
    }

    #[test]
    fn self_adaptive_leads_the_target() {
        let mut st = AimState::default();
        let mut rng = Pcg32::new(3);
        let t = target_at(5.0);
        let mut cfg = AimConfig::default();
        cfg.mode = AimMode::SelfAdaptive;
        let r = self_adaptive_tick(&cfg, &mut st, &player(0.0), Some(&t), &mut rng);
        match r {
            AimResult::ApplyRotation { hitbox: Some(hb), .. } => {
                // motion.x = 0.05, T_LEAD = 0.1 → box shifted by 0.005
                let expected = t.hitbox.min_x + 0.05 * T_LEAD;
                assert!((hb.min_x - expected).abs() < 1e-12);
            }
            _ => panic!("expected ApplyRotation with predicted hitbox"),
        }
    }

    #[test]
    fn drift_stays_within_offset() {
        let mut st = AimState::default();
        let mut rng = Pcg32::new(11);
        for _ in 0..2000 {
            let (y, p) = update_natural_drift(&mut st, 0.5, &mut rng);
            assert!(y.abs() <= 0.5 + 1e-6, "drift yaw {} exceeded offset", y);
            assert!(p.abs() <= 0.3 + 1e-6, "drift pitch {} exceeded 0.6*offset", p);
        }
    }

    #[test]
    fn zero_offset_pins_drift() {
        let mut st = AimState { drift_yaw: 3.0, drift_pitch: 2.0, ..Default::default() };
        let mut rng = Pcg32::new(5);
        let (y, p) = update_natural_drift(&mut st, 0.0, &mut rng);
        assert_eq!((y, p), (0.0, 0.0));
    }
}
