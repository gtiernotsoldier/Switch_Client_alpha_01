//! Port of `io.doppel.core.strategy.velocity.*`.
//!
//! The branch order is the behaviour. Each numbered step below has a comment
//! naming the Kotlin line it came from, because reordering any two of them
//! changes what the module does on a hit that fails two conditions at once —
//! and that is invisible until the replay diff shows up.
//!
//! State that used to live in the adapter (`reduceLastPassed`, the delay queue,
//! `tickCounter`) lives here now. That is PROTECTION.md 9.5.4: session state
//! stays on the core side of the boundary and never travels.

use crate::condition;
use crate::model::*;
use crate::rng::Pcg32;
use crate::wire::Reason;

/// `VelocityStrategy.State`. One per module instance, owned by the core session.
pub struct State {
    /// LEGIT: did the previous hit pass the gate? The chain's second state.
    pub reduce_last_passed: bool,

    /// DELAY: packets held back, as (motion, release tick).
    pub delay_queue: Vec<DelayEntry>,

    /// Tick counter, advanced by every snapshot / pump.
    pub tick_counter: i32,

    /// SPRINT: did the previous hit brace?
    pub brace_last_passed: bool,

    /// SPRINT: the armed window, as (source_x, source_z). None = idle.
    ///
    /// Source only; the length is `brace_ticks_left`. Unlike the JVM version
    /// there is no identity check on adoption — this state is touched by one
    /// thread, so a fresh arm can simply overwrite the remainder.
    pub brace_window: Option<(f64, f64)>,

    /// SPRINT: ticks left in the current window.
    pub brace_ticks_left: i32,
}

#[derive(Debug, Clone, Copy)]
pub struct DelayEntry {
    pub motion: Vec3,
    pub release_tick: i32,
}

impl State {
    pub fn new() -> State {
        State {
            reduce_last_passed: false,
            delay_queue: Vec::new(),
            tick_counter: 0,
            brace_last_passed: false,
            brace_window: None,
            brace_ticks_left: 0,
        }
    }

    pub fn reset(&mut self) {
        self.reduce_last_passed = false;
        self.delay_queue.clear();
        self.tick_counter = 0;
        self.brace_last_passed = false;
        self.brace_window = None;
        self.brace_ticks_left = 0;
    }
}

impl Default for State {
    fn default() -> Self {
        State::new()
    }
}

pub fn execute(config: &VelocityConfig, state: &mut State, ctx: &VelocityContext, rng: &mut Pcg32) -> Decision {
    match config.mode {
        VelocityMode::Legit => legit(config, state, ctx, rng),
        VelocityMode::Click => click(config, ctx, rng),
        VelocityMode::Sprint => {
            // SPRINT never touches the packet: it arms a key-press window and
            // returns Pass. The window itself is tick-level and resolved by the
            // stub from the last armed plan (9.5.2 "frame-level plan").
            let armed = arm(config, state, ctx, rng);
            let reason = if armed {
                Reason::SprintArmed
            } else {
                Reason::PassThrough
            };
            Decision::pass(ctx.original_motion, reason as u8)
        }
    }
}

/// `LegitVelocityStrategy.processLegit`, steps 1–5.
pub fn legit(config: &VelocityConfig, state: &mut State, ctx: &VelocityContext, rng: &mut Pcg32) -> Decision {
    let original = ctx.original_motion;

    // 1. Unified condition check.
    if condition::check(&config.trigger, &ctx.player, ctx.target.as_ref(), rng).is_some() {
        return Decision::pass(original, Reason::ConditionFailed as u8);
    }

    // 2. Hit-frame filter: reduce on an attack's knockback, leave explosions alone.
    if config.only_on_hit_frame && !ctx.is_knockback_hit {
        return Decision::pass(original, Reason::HitFrameFilter as u8);
    }

    // 3. The gate: one chain, two states, no cadence.
    let chance = if state.reduce_last_passed {
        config.reduce_given_reduced
    } else {
        config.reduce_given_skipped
    };
    let passed = (rng.next_int(100) as i32) < chance;
    state.reduce_last_passed = passed;
    if !passed {
        return Decision::pass(original, Reason::GateSkip as u8);
    }

    // 4. Sample and scale.
    let h = rng.random_float(config.horizontal_min, config.horizontal_max);
    let v = rng.random_float(config.vertical_min, config.vertical_max);

    // 5. `VectorOperations.scale(original, h, v, h)` — Double * Float.
    Decision::modify(Vec3::new(
        original.x * h as f64,
        original.y * v as f64,
        original.z * h as f64,
    ))
}

/// `ClickVelocityStrategy.processClick`, steps 1–6.
pub fn click(config: &VelocityConfig, ctx: &VelocityContext, rng: &mut Pcg32) -> Decision {
    let original = ctx.original_motion;

    if condition::check(&config.trigger, &ctx.player, ctx.target.as_ref(), rng).is_some() {
        return Decision::pass(original, Reason::ConditionFailed as u8);
    }

    let target = match ctx.target.as_ref() {
        Some(t) => t,
        None => return Decision::pass(original, Reason::NoTarget as u8),
    };

    if ctx.player.hurt_time != config.hurt_time_to_click {
        return Decision::pass(original, Reason::HurtTimeMismatch as u8);
    }

    if config.when_facing_enemy_only && !ctx.player.is_looking_at_target() {
        return Decision::pass(original, Reason::Facing as u8);
    }

    let distance = ctx.player.position.distance_to(&target.position);
    if distance > config.click_range as f64 {
        return Decision::pass(original, Reason::OutOfRange as u8);
    }

    let clicks = rng.random_int(config.click_burst_min, config.click_burst_max);
    Decision::click_burst(target.id, clicks)
}

/// SPRINT: condition check, gate roll, arm the window. Returns whether it armed.
fn arm(config: &VelocityConfig, state: &mut State, ctx: &VelocityContext, rng: &mut Pcg32) -> bool {
    if condition::check(&config.trigger, &ctx.player, ctx.target.as_ref(), rng).is_some() {
        return false;
    }

    // Direction first, dice second: a hit with no horizontal component is not a
    // brace decision, so it must not consume a roll.
    if brace_source(&ctx.original_motion).is_none() {
        return false;
    }

    let p = if state.brace_last_passed {
        config.sprint_gate_braced
    } else {
        config.sprint_gate_idle
    };
    let passed = (rng.next_int(100) as i32) < p;
    state.brace_last_passed = passed;
    if passed {
        if let Some(source) = brace_source(&ctx.original_motion) {
            state.brace_window = Some(source);
            state.brace_ticks_left = config.sprint_hold_ticks;
        }
    }
    passed
}

/// `SprintBrace.keysFor` — resolve the armed direction against the player's
/// current facing, in `(forward, back, left, right)` order.
///
/// The yaw comes from the snapshot, not from the hit: the mapping depends on
/// where the player is facing *now*, and that changes during the window.
pub fn brace_keys(yaw_deg: f32, sector: f64, state: &mut State) -> (bool, bool, bool, bool) {
    let window = match state.brace_window {
        Some(w) => w,
        None => return (false, false, false, false),
    };
    if state.brace_ticks_left <= 0 {
        state.brace_window = None;
        return (false, false, false, false);
    }
    state.brace_ticks_left -= 1;

    // Bit-exact primitives, not the builtins: the Kotlin twin
    // (`SprintBrace.keysFor`) evaluates `PortableMath.toRadians` (divide, then
    // multiply) and fdlibm sin/cos, and both builtins disagree with those by
    // an ulp on a meaningful share of inputs. At the sector boundary one ulp
    // flips a key, which is the exact bug class this crate's trig/geom exist
    // to remove.
    let yaw = crate::geom::to_radians(yaw_deg as f64);
    let fwd_x = -crate::trig::sin(yaw);
    let fwd_z = crate::trig::cos(yaw);
    // Right of forward in the (x, z) plane: (x, z) -> (-z, x).
    let rgt_x = -fwd_z;
    let rgt_z = fwd_x;
    let f_dot = fwd_x * window.0 + fwd_z * window.1;
    let r_dot = rgt_x * window.0 + rgt_z * window.1;
    (
        f_dot > sector,
        f_dot < -sector,
        r_dot < -sector,
        r_dot > sector,
    )
}

/// `SprintBrace.sourceFrom` — unit vector back at the attacker, or None when the
/// knockback is (near) purely vertical.
pub fn brace_source(motion: &Vec3) -> Option<(f64, f64)> {
    let h = (motion.x * motion.x + motion.z * motion.z).sqrt();
    if h < 1e-4 {
        return None;
    }
    Some((-motion.x / h, -motion.z / h))
}

// ── KnockbackDelay ────────────────────────────────────────────────────────────
//
// A separate entry point, not a VelocityMode: delay changes *when* the knockback
// lands, never *how much*, so it composes with whichever mode is running.

/// Returns true when the packet was swallowed and the stub must cancel it.
pub fn delay_enqueue(config: &VelocityConfig, state: &mut State, ctx: &VelocityContext, rng: &mut Pcg32) -> bool {
    if config.require_weapon && ctx.player.weapon == WeaponType::Other {
        return false;
    }
    if condition::check(&config.trigger, &ctx.player, ctx.target.as_ref(), rng).is_some() {
        return false;
    }
    if config.delay_chance < 100 && (rng.next_int(100) as i32) >= config.delay_chance {
        return false;
    }

    let max = if config.max_delay_ticks < 1 {
        1
    } else {
        config.max_delay_ticks
    };
    let delay = rng.random_int(1, max);

    state.delay_queue.push(DelayEntry {
        motion: ctx.original_motion,
        release_tick: state.tick_counter + delay,
    });
    true
}

/// Release everything whose tick has arrived, unchanged.
///
/// Does not touch `tick_counter`: the caller decides which clock the queue runs on, and having
/// this overwrite it made the delay depend on whether a knockback happened to arrive that tick.
pub fn delay_pump(state: &mut State, current_tick: i32) -> Vec<Vec3> {
    let mut released = Vec::new();
    let mut i = 0;
    while i < state.delay_queue.len() {
        if current_tick >= state.delay_queue[i].release_tick {
            let e = state.delay_queue.remove(i);
            released.push(e.motion);
        } else {
            i += 1;
        }
    }
    released
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(motion: Vec3, hurt_time: i32) -> VelocityContext {
        VelocityContext {
            original_motion: motion,
            player: PlayerState {
                position: Vec3::new(0.0, 64.0, 0.0),
                motion: Vec3::new(0.0, 0.0, 0.0),
                yaw: 0.0,
                pitch: 0.0,
                health: 20.0,
                hurt_time,
                hurt_resistant_time: hurt_time,
                weapon: WeaponType::Sword,
                flags: F_ON_GROUND | F_MOVING | F_LOOKING_AT_TARGET,
                ticks: 0,
            },
            target: None,
            is_knockback_hit: true,
            packet_id: 1,
        }
    }

    #[test]
    fn gate_always_on_reduces_and_stays_in_range() {
        let mut cfg = VelocityConfig::default();
        cfg.reduce_given_reduced = 100;
        cfg.reduce_given_skipped = 100;
        let mut st = State::new();
        let mut rng = Pcg32::new(11);
        let c = ctx(Vec3::new(1.0, 0.4, -1.0), 0);
        let d = legit(&cfg, &mut st, &c, &mut rng);
        assert_eq!(d.action, Action::Modify);
        assert!(d.motion.x >= 0.4 && d.motion.x <= 0.6);
        assert!(d.motion.y >= 0.16 && d.motion.y <= 0.24);
        assert!((d.motion.z + d.motion.x).abs() < 1e-9, "z uses the same h as x");
    }

    #[test]
    fn gate_always_off_passes() {
        let mut cfg = VelocityConfig::default();
        cfg.reduce_given_reduced = 0;
        cfg.reduce_given_skipped = 0;
        let mut st = State::new();
        let mut rng = Pcg32::new(11);
        let c = ctx(Vec3::new(1.0, 0.4, -1.0), 0);
        let d = legit(&cfg, &mut st, &c, &mut rng);
        assert_eq!(d.action, Action::Pass);
        assert_eq!(d.reason, Reason::GateSkip as u8);
        assert!(!st.reduce_last_passed);
    }

    #[test]
    fn gate_state_is_carried_between_hits() {
        let mut cfg = VelocityConfig::default();
        cfg.reduce_given_reduced = 100;
        cfg.reduce_given_skipped = 0;
        let mut st = State::new();
        let mut rng = Pcg32::new(5);
        let c = ctx(Vec3::new(1.0, 0.4, 1.0), 0);
        // First hit: skipped state -> skip.
        assert_eq!(legit(&cfg, &mut st, &c, &mut rng).action, Action::Pass);
        assert!(!st.reduce_last_passed);
        // Second hit: skipped state -> still skip. State must not have flipped.
        assert_eq!(legit(&cfg, &mut st, &c, &mut rng).action, Action::Pass);
    }

    #[test]
    fn hit_frame_filter_passes_explosions() {
        let mut cfg = VelocityConfig::default();
        cfg.only_on_hit_frame = true;
        cfg.reduce_given_reduced = 100;
        cfg.reduce_given_skipped = 100;
        let mut st = State::new();
        let mut rng = Pcg32::new(1);
        let mut c = ctx(Vec3::new(1.0, 0.4, 1.0), 0);
        c.is_knockback_hit = false;
        let d = legit(&cfg, &mut st, &c, &mut rng);
        assert_eq!(d.action, Action::Pass);
        assert_eq!(d.reason, Reason::HitFrameFilter as u8);
    }

    #[test]
    fn click_mode_requires_the_exact_hurt_time() {
        let mut cfg = VelocityConfig::default();
        cfg.mode = VelocityMode::Click;
        cfg.hurt_time_to_click = 8;
        cfg.when_facing_enemy_only = false;
        cfg.click_burst_min = 3;
        cfg.click_burst_max = 3;
        let mut rng = Pcg32::new(2);
        let target = TargetState {
            id: 42,
            position: Vec3::new(0.0, 64.0, 2.0),
            motion: Vec3::new(0.0, 0.0, 0.0),
            hitbox: crate::geom::Hitbox::around(Vec3::new(0.0, 64.0, 2.0), 0.3, 1.8),
            distance: 2.0,
            health: 20.0,
            hurt_time: 0,
            is_moving_backward: false,
            is_moving_towards_player: false,
        };
        let mut c = ctx(Vec3::new(0.0, 0.0, 0.0), 7);
        c.target = Some(target);
        assert_eq!(click(&cfg, &c, &mut rng).action, Action::Pass);

        c.player.hurt_time = 8;
        let d = click(&cfg, &c, &mut rng);
        assert_eq!(d.action, Action::ClickBurst);
        assert_eq!(d.target_id, 42);
        assert_eq!(d.clicks, 3);
    }

    #[test]
    fn delay_is_uniform_within_configured_bound() {
        let mut cfg = VelocityConfig::default();
        cfg.max_delay_ticks = 4;
        cfg.delay_chance = 100;
        let mut rng = Pcg32::new(3);
        let c = ctx(Vec3::new(0.3, 0.4, 0.3), 0);
        let mut st = State::new();
        assert!(delay_enqueue(&cfg, &mut st, &c, &mut rng));
        let e = st.delay_queue[0];
        assert!((1..=4).contains(&e.release_tick));
    }

    #[test]
    fn delayed_motion_is_released_unchanged() {
        let mut cfg = VelocityConfig::default();
        cfg.max_delay_ticks = 2;
        let mut rng = Pcg32::new(4);
        let c = ctx(Vec3::new(0.7, 0.4, -0.7), 0);
        let mut st = State::new();
        assert!(delay_enqueue(&cfg, &mut st, &c, &mut rng));
        assert!(delay_pump(&mut st, 0).is_empty());
        let released = delay_pump(&mut st, 2);
        assert_eq!(released.len(), 1);
        assert_eq!(released[0].x, 0.7);
        assert_eq!(released[0].z, -0.7);
        assert!(st.delay_queue.is_empty());
    }

    #[test]
    fn vertical_knockback_has_no_brace_source() {
        assert!(brace_source(&Vec3::new(0.0, 0.4, 0.0)).is_none());
        assert!(brace_source(&Vec3::new(0.5, 0.4, 0.0)).is_some());
    }
}
