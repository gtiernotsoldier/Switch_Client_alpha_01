//! Port of `io.doppel.core.condition.ConditionChecker`.
//!
//! Ported whole rather than in the subset this vertical slice happens to need:
//! the module is pure math over plain structs, and a condition engine that is
//! 80% faithful is worse than useless for the replay gate — every unported
//! branch becomes a tick that silently diverges with no visible cause.
//!
//! Two deliberate choices to keep bit-exact parity with the Kotlin:
//!
//!  - `yaw -> direction` and `atan2` run in f64 exactly as the Kotlin does
//!    (`Math.toRadians(yaw.toDouble())`), and only the final comparison is done
//!    in f32. Doing the trig in f32 would differ in the last ulp and flip
//!    borderline "am I looking at them" answers — a one-tick difference in a
//!    module, which is exactly what a run-length detector notices.
//!  - the angle is converted with `* (180.0 / PI)`, not `f32::to_degrees`,
//!    because the two round differently.

use crate::model::{PlayerState, TargetState, TriggerOptions};
use crate::rng::Pcg32;

const RAD_TO_DEG: f64 = 180.0 / std::f64::consts::PI;

/// Result of a failed check, for the replay log. `None` means "passed".
pub type Blocked = Option<&'static str>;

pub fn check(
    options: &TriggerOptions,
    player: &PlayerState,
    target: Option<&TargetState>,
    rng: &mut Pcg32,
) -> Blocked {
    if options.only_ground && !player.on_ground() {
        return Some("onlyGround");
    }
    if options.only_air && player.on_ground() {
        return Some("onlyAir");
    }
    if options.disabled_in_air && !player.on_ground() {
        return Some("disabledInAir");
    }

    if options.only_move && !player.is_moving() {
        return Some("onlyMove");
    }
    if options.only_move_forward && !player.is_moving_forward() {
        return Some("onlyMoveForward");
    }
    if options.only_move_backward && !is_moving_backward(player) {
        return Some("onlyMoveBackward");
    }
    if options.only_strafe && !is_strafing(player) {
        return Some("onlyStrafe");
    }

    // Target-based checks are skipped when there is no target — "no target" is
    // not the same as "target failed the condition", and the original code is
    // explicit about that.
    if let Some(t) = target {
        if options.only_when_target_goes_back && !t.is_moving_backward {
            return Some("onlyWhenTargetGoesBack");
        }
        if options.only_when_target_approaches && !t.is_moving_towards_player {
            return Some("onlyWhenTargetApproaches");
        }
        if t.distance < options.min_distance {
            return Some("minDistance");
        }
        if t.distance > options.max_distance {
            return Some("maxDistance");
        }
    }

    if options.on_look || options.only_current_view {
        match target {
            None => {
                if options.on_look {
                    return Some("onLook");
                }
            }
            Some(t) => {
                if !is_looking_at(player, t, options.look_angle_threshold) {
                    return Some(if options.on_look { "onLook" } else { "onlyCurrentView" });
                }
            }
        }
    }

    if options.only_on_click && !player.is_attack_key_down() {
        return Some("onlyOnClick");
    }
    if options.disable_on_mine && player.is_mining() {
        return Some("disableOnMine");
    }

    if options.chance < 100 {
        let roll = rng.next_int(100) as i32;
        if roll >= options.chance {
            return Some("chance");
        }
    }

    // delayTicks / delayMs are applied by the module, not here.
    None
}

pub fn is_looking_at(player: &PlayerState, target: &TargetState, threshold_deg: f32) -> bool {
    let dx = target.position.x - player.position.x;
    let dz = target.position.z - player.position.z;
    if dx == 0.0 && dz == 0.0 {
        return true;
    }

    // MC convention: yaw 0 faces +Z, yaw 90 faces -X.
    let yaw_to_target = ((-dx).atan2(dz) * RAD_TO_DEG) as f32;

    let player_yaw = normalize_angle(player.yaw);
    let angle_deg = normalize_angle(player_yaw - yaw_to_target).abs();

    angle_deg <= threshold_deg
}

pub fn is_moving_backward(player: &PlayerState) -> bool {
    let speed = (player.motion.x * player.motion.x + player.motion.z * player.motion.z).sqrt();
    if speed < 0.01 {
        return false;
    }
    let facing = yaw_to_direction(player.yaw);
    let dot = player.motion.x * facing.0 + player.motion.z * facing.2;
    dot < -0.01
}

pub fn is_strafing(player: &PlayerState) -> bool {
    let speed = (player.motion.x * player.motion.x + player.motion.z * player.motion.z).sqrt();
    if speed < 0.01 {
        return false;
    }
    let facing = yaw_to_direction(player.yaw);

    let forward = player.motion.x * facing.0 + player.motion.z * facing.2;
    let lateral = player.motion.x * facing.2 - player.motion.z * facing.0;

    lateral.abs() > forward.abs() && lateral.abs() > 0.01
}

pub fn normalize_angle(angle: f32) -> f32 {
    let mut a = angle % 360.0;
    if a > 180.0 {
        a -= 360.0;
    }
    if a < -180.0 {
        a += 360.0;
    }
    a
}

/// (x, y, z) in f64 — matches `RotationCalculator.yawToDirection`, which builds
/// a `Vec3` of doubles.
pub fn yaw_to_direction(yaw: f32) -> (f64, f64, f64) {
    let rad = (yaw as f64).to_radians();
    (-rad.sin(), 0.0, rad.cos())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::*;

    fn player(yaw: f32) -> PlayerState {
        PlayerState {
            position: Vec3::new(0.0, 64.0, 0.0),
            motion: Vec3::new(0.0, 0.0, 0.0),
            yaw,
            pitch: 0.0,
            health: 20.0,
            hurt_time: 0,
            hurt_resistant_time: 0,
            weapon: WeaponType::Sword,
            flags: F_ON_GROUND | F_MOVING | F_ATTACK_KEY_DOWN,
            ticks: 0,
        }
    }

    fn target(x: f64, z: f64, distance: f32) -> TargetState {
        TargetState {
            id: 7,
            position: Vec3::new(x, 64.0, z),
            distance,
            health: 20.0,
            hurt_time: 0,
            is_moving_backward: false,
            is_moving_towards_player: false,
        }
    }

    #[test]
    fn normalize_wraps_into_pm180() {
        assert_eq!(normalize_angle(0.0), 0.0);
        assert_eq!(normalize_angle(190.0), -170.0);
        assert_eq!(normalize_angle(-190.0), 170.0);
        assert_eq!(normalize_angle(540.0), 180.0);
    }

    #[test]
    fn yaw_zero_faces_positive_z() {
        let (x, _, z) = yaw_to_direction(0.0);
        assert!(x.abs() < 1e-9);
        assert!((z - 1.0).abs() < 1e-9);
    }

    #[test]
    fn looking_straight_at_target_passes() {
        // yaw 0 faces +Z, target at +Z.
        assert!(is_looking_at(&player(0.0), &target(0.0, 5.0, 5.0), 30.0));
    }

    #[test]
    fn looking_away_fails() {
        assert!(!is_looking_at(&player(0.0), &target(0.0, -5.0, 5.0), 30.0));
    }

    #[test]
    fn standing_on_target_counts_as_looking() {
        assert!(is_looking_at(&player(37.0), &target(0.0, 0.0, 0.0), 30.0));
    }

    #[test]
    fn ground_gate_blocks_in_air() {
        let mut p = player(0.0);
        p.flags &= !F_ON_GROUND;
        let mut o = TriggerOptions::default();
        o.only_ground = true;
        let mut rng = Pcg32::new(1);
        assert_eq!(check(&o, &p, None, &mut rng), Some("onlyGround"));
    }

    #[test]
    fn distance_gate_blocks_out_of_range() {
        let mut o = TriggerOptions::default();
        o.max_distance = 3.0;
        let t = target(0.0, 5.0, 5.0);
        let mut rng = Pcg32::new(1);
        assert_eq!(check(&o, &player(0.0), Some(&t), &mut rng), Some("maxDistance"));
    }

    #[test]
    fn chance_zero_always_blocks_and_consumes_one_roll() {
        let mut o = TriggerOptions::default();
        o.chance = 0;
        let mut rng = Pcg32::new(1);
        assert_eq!(check(&o, &player(0.0), None, &mut rng), Some("chance"));
        // One roll consumed: the next value must match a fresh generator's first.
        let mut fresh = Pcg32::new(1);
        fresh.next_int(100);
        assert_eq!(rng.next_int(100), fresh.next_int(100));
    }
}
