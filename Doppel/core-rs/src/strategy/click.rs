//! Port of `io.doppel.core.strategy.click`.
//!
//! Two strategies with different clocks: the 1.8 one runs a rhythm of
//! scheduled intervals, the 1.9 one waits on an attack cooldown and optionally
//! runs a crit state machine. Both are pure functions of (config, state, input, rng).
//!
//! The 1.8 rhythm is the part worth reading carefully. A per-tick Bernoulli
//! roll ("click with probability cps/20") produces memoryless geometric
//! intervals — flat, no burst structure, no long tail — which is exactly the
//! statistical signature autoclicker detectors key on. The rhythm scheduler
//! instead draws an INTERVAL when a click fires and counts it down:
//!
//! - tempo: uniform in [min_cps, max_cps], redrawn per click, distance-adjusted
//!   in LEGIT — the same helpers the Bernoulli model used, just at click
//!   frequency instead of tick frequency;
//! - jitter: an AR(1) multiplicative factor around 1.0 — intervals carry
//!   lag-1 autocorrelation, so short intervals cluster (bursts) and long ones
//!   cluster (lulls), like a human finger;
//! - pause: 5% of intervals are 2.0-4.0x — the blink / aim-recheck;
//! - re-engagement floor: when the gates break (player mining, condition
//!   fail) the rhythm dies and the next engagement waits a fresh full
//!   interval, at least 4 ticks (200 ms) — a reaction floor, not an instant
//!   restart.
//!
//! The crit FSM is the other part worth porting carefully. Its phases emit
//! actions on *consecutive* ticks (`StopSprint` this tick, `Click` the next),
//! so a phase transition that is off by one tick produces a click that does
//! land but never crits — silent, and invisible to any test that only counts
//! clicks.

use crate::condition;
use crate::model::{PlayerState, TargetState, TriggerOptions, WeaponType};
use crate::rng::Pcg32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClickMode {
    Single,
    Double,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClickOperatingMode {
    Normal,
    Legit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WeaponFilter {
    Any,
    Sword,
    Axe,
    SwordAndAxe,
}

impl WeaponFilter {
    pub fn matches(&self, w: WeaponType) -> bool {
        match self {
            WeaponFilter::Any => true,
            WeaponFilter::Sword => w == WeaponType::Sword,
            WeaponFilter::Axe => w == WeaponType::Axe,
            WeaponFilter::SwordAndAxe => w == WeaponType::Sword || w == WeaponType::Axe,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CritMode {
    Off,
    On,
    Smart,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OnItemUse {
    Wait,
    Stop,
    Ignore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CooldownClickMode {
    Normal,
    Legit,
}

#[derive(Debug, Clone, Copy)]
pub struct ClickConfig {
    pub min_cps: i32,
    pub max_cps: i32,
    pub click_mode: ClickMode,
    pub mode: ClickOperatingMode,
    pub disable_on_block: bool,
    pub trigger_options: TriggerOptions,
    // ── 1.8 rhythm shaping (tunable) ────────────────────────────────────
    // Short names on purpose — these are the knobs a human reads. Defaults
    // match the old consts bit-for-bit, so golden fixtures are unchanged.
    /// AR(1) persistence of the interval jitter (lag-1 autocorrelation).
    /// 0 = no bursts, 1 = frozen.
    pub jitter: f32,
    /// Jitter target drawn in [jitter_min, jitter_min + jitter_var).
    /// For flat intervals: jitter=0, jitter_min=1.0, jitter_var=0.0.
    pub jitter_min: f32,
    pub jitter_var: f32,
    /// Chance of a rare long (pause / re-acquisition) interval.
    pub pause: f32,
    pub pause_low: f32,
    pub pause_var: f32,
    /// Reaction floor in ticks after the gates break. 0 removes the floor.
    pub react: i32,
}

impl Default for ClickConfig {
    fn default() -> Self {
        ClickConfig {
            min_cps: 8,
            max_cps: 10,
            click_mode: ClickMode::Single,
            mode: ClickOperatingMode::Normal,
            disable_on_block: true,
            trigger_options: TriggerOptions::default(),
            jitter: JITTER_PERSIST,
            jitter_min: JITTER_LO,
            jitter_var: JITTER_SPAN,
            pause: PAUSE_CHANCE,
            pause_low: PAUSE_MIN,
            pause_var: PAUSE_SPAN,
            react: RE_ENGAGE_MIN_TICKS,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct CooldownClickConfig {
    pub cooldown_threshold: f32,
    pub crit_mode: CritMode,
    pub crit_stop_sprint: bool,
    pub cooldown_mode: CooldownClickMode,
    pub disable_on_block: bool,
    pub trigger_options: TriggerOptions,
}

impl Default for CooldownClickConfig {
    fn default() -> Self {
        CooldownClickConfig {
            cooldown_threshold: 1.0,
            crit_mode: CritMode::Off,
            crit_stop_sprint: true,
            cooldown_mode: CooldownClickMode::Normal,
            disable_on_block: true,
            trigger_options: TriggerOptions::default(),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct ClickInput {
    pub player: PlayerState,
    pub target: Option<TargetState>,
    pub attack_cooldown: f32,
    pub is_falling: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClickResult {
    Click,
    StopSprint,
    RestoreSprint { was_sprinting: bool },
    Skip,
}

#[derive(Debug, Clone, Copy)]
pub struct ClickState {
    pub last_target_id: i32,
    pub pending_second_click: bool,
    /// Rhythm scheduler: ticks left before the next click is due. Meaningful
    /// only while `rhythm_live` is true.
    pub next_click_in: i32,
    /// The persistent AR(1) component of the interval jitter, centred on 1.0.
    pub jitter: f32,
    /// False while the rhythm is idle (fresh state, or the gates broke): the
    /// next gate-passing tick re-arms with a fresh interval as the reaction
    /// gap instead of resuming a half-counted countdown.
    pub rhythm_live: bool,
}

impl Default for ClickState {
    fn default() -> Self {
        ClickState {
            last_target_id: -1,
            pending_second_click: false,
            next_click_in: 0,
            jitter: 1.0,
            rhythm_live: false,
        }
    }
}

impl ClickState {
    pub fn reset(&mut self) {
        *self = ClickState::default();
    }
}

// ── 1.8 probabilistic ──────────────────────────────────────────────────────

/// Re-engagement floor in ticks: after the gates break, the first click of
/// the new rhythm waits at least this long (200 ms at 20 tps) — a human
/// reaction floor, not a robot's instant restart.
const RE_ENGAGE_MIN_TICKS: i32 = 4;
/// Interval jitter persistence (lag-1 autocorrelation of the multiplicative
/// factor). Higher = longer bursts/lulls.
const JITTER_PERSIST: f32 = 0.65;
/// The jitter target is drawn uniformly in [JITTER_LO, JITTER_LO + JITTER_SPAN),
/// centred on 1.0.
const JITTER_LO: f32 = 0.8;
const JITTER_SPAN: f32 = 0.4;
/// Rare re-acquisition pause: with this probability an interval is drawn
/// 2.0-4.0x long — the blink / aim-recheck.
const PAUSE_CHANCE: f32 = 0.05;
const PAUSE_MIN: f32 = 2.0;
const PAUSE_SPAN: f32 = 2.0;

fn sample_cps_in_range(lo: i32, hi: i32, rng: &mut Pcg32) -> i32 {
    if lo == hi {
        return lo;
    }
    // `(u * (hi - lo + 1)).toInt()` truncates in Kotlin; `as i32` truncates here.
    let raw = (rng.next_float() * (hi - lo + 1) as f32) as i32;
    lo + raw.max(0).min(hi - lo)
}

fn adjust_cps_by_distance(base: i32, lo: i32, hi: i32, distance: f32, rng: &mut Pcg32) -> i32 {
    if distance > 2.5 {
        // f32 throughout: the Kotlin is `nextUniform(2f, 5f)` in Float, and a
        // f64 intermediate changes which integer the truncation lands on.
        let reduction = (2.0f32 + rng.next_float() * 3.0f32) as i32;
        (base - reduction).max(lo).min(hi)
    } else if distance < 1.5 {
        let boost = (3.0f32 + rng.next_float() * 2.0f32) as i32;
        (base + boost).max(lo).min(hi)
    } else {
        base.max(lo).min(hi)
    }
}

pub fn probabilistic_tick(
    config: &ClickConfig,
    state: &mut ClickState,
    input: &ClickInput,
    rng: &mut Pcg32,
) -> ClickResult {
    let player = &input.player;

    // 1. Block-hit prevention. A player busy mining is not mid-click-train,
    //    so the rhythm dies here too, not just the click.
    if config.disable_on_block && player.is_mining() {
        state.rhythm_live = false;
        return ClickResult::Skip;
    }

    // 2. Pending second click (DOUBLE mode): the pair completes regardless of
    //    where the rhythm scheduler is.
    if state.pending_second_click {
        state.pending_second_click = false;
        return ClickResult::Click;
    }

    // 3. Condition fail — the player lifted off. The rhythm dies; the next
    //    engagement restarts from the reaction floor.
    if condition::check(&config.trigger_options, player, input.target.as_ref(), rng).is_some() {
        state.rhythm_live = false;
        return ClickResult::Skip;
    }

    if let Some(t) = input.target.as_ref() {
        if t.id != state.last_target_id {
            state.last_target_id = t.id;
        }
    }

    // 5. The rhythm scheduler. An idle rhythm re-arms with a fresh interval
    //    (the reaction gap, floored); a live one counts down and fires on zero.
    if !state.rhythm_live {
        let interval = draw_interval(config, state, input, rng).max(config.react);
        state.next_click_in = interval - 1;
        state.rhythm_live = true;
        return ClickResult::Skip;
    }
    if state.next_click_in > 0 {
        state.next_click_in -= 1;
        return ClickResult::Skip;
    }

    // Due this tick: fire, then schedule the next interval from *next* tick.
    let interval = draw_interval(config, state, input, rng);
    state.next_click_in = interval - 1;
    if config.click_mode == ClickMode::Double {
        state.pending_second_click = true;
    }
    ClickResult::Click
}

/// Draw the next inter-click interval in ticks: a tempo (CPS) sample, the
/// AR(1) multiplicative jitter, a rare pause. Draw order is part of the
/// cross-language contract — the Kotlin twin consumes in exactly this
/// sequence, on exactly these fire/re-arm points and nowhere else.
fn draw_interval(
    config: &ClickConfig,
    state: &mut ClickState,
    input: &ClickInput,
    rng: &mut Pcg32,
) -> i32 {
    let lo = config.min_cps.min(config.max_cps);
    let hi = config.max_cps.max(config.min_cps);
    let mut tempo = sample_cps_in_range(lo, hi, rng);
    if config.mode == ClickOperatingMode::Legit {
        if let Some(t) = input.target.as_ref() {
            tempo = adjust_cps_by_distance(tempo, lo, hi, t.distance, rng);
        }
    }
    let tempo = tempo.max(1) as f32;

    // AR(1) jitter around 1.0: bursts of short intervals, lulls of long ones.
    // Draw order is unchanged — config only supplies the coefficients.
    let target = config.jitter_min + config.jitter_var * rng.next_float();
    state.jitter = config.jitter * state.jitter + (1.0f32 - config.jitter) * target;

    let mut interval = (20.0f32 / tempo) * state.jitter;
    if rng.next_float() < config.pause {
        interval *= config.pause_low + config.pause_var * rng.next_float();
    }
    ((interval + 0.5f32) as i32).max(1)
}

// ── 1.9 cooldown ───────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CritPhase {
    Idle,
    StopSprint,
    WaitFall,
    WaitCooldown,
    Attack,
    Restore,
    SmartStopSprint,
    SmartRestore,
}

#[derive(Debug, Clone, Copy)]
pub struct CritState {
    pub base: ClickState,
    pub crit_phase: CritPhase,
    pub cooldown_ready: bool,
    pub legit_delay_ticks: i32,
    pub was_sprinting_before_crit: bool,
    pub wait_fall_ticks: i32,
}

impl Default for CritState {
    fn default() -> Self {
        CritState {
            base: ClickState::default(),
            crit_phase: CritPhase::Idle,
            cooldown_ready: false,
            legit_delay_ticks: 0,
            was_sprinting_before_crit: false,
            wait_fall_ticks: 0,
        }
    }
}

impl CritState {
    pub fn reset(&mut self) {
        *self = CritState::default();
    }
}

fn sample_legit_delay(rng: &mut Pcg32) -> i32 {
    // `nextUniform(1f, 4f).toInt().coerceIn(1, 3)` — note the truncation means
    // 1..4 exclusive at the top, so the clamp only bites on the low side.
    let raw = (1.0f32 + rng.next_float() * 3.0f32) as i32;
    raw.max(1).min(3)
}

fn reset_crit_state(state: &mut CritState) {
    state.crit_phase = CritPhase::Idle;
    state.was_sprinting_before_crit = false;
    state.cooldown_ready = false;
    state.legit_delay_ticks = 0;
    state.wait_fall_ticks = 0;
}

pub fn cooldown_tick(
    config: &CooldownClickConfig,
    state: &mut CritState,
    input: &ClickInput,
    rng: &mut Pcg32,
) -> ClickResult {
    let player = &input.player;

    if config.disable_on_block && player.is_mining() {
        return ClickResult::Skip;
    }

    if config.crit_mode != CritMode::Off && state.crit_phase != CritPhase::Idle {
        return drive_crit_phase(config, state, input, rng);
    }

    if condition::check(&config.trigger_options, player, input.target.as_ref(), rng).is_some() {
        return ClickResult::Skip;
    }

    if input.attack_cooldown < config.cooldown_threshold {
        if !state.cooldown_ready {
            state.cooldown_ready = true;
        }
        return ClickResult::Skip;
    }

    if !state.cooldown_ready {
        state.cooldown_ready = true;
    }

    if config.cooldown_mode == CooldownClickMode::Legit {
        if state.legit_delay_ticks == 0 {
            state.legit_delay_ticks = sample_legit_delay(rng);
        }
        state.legit_delay_ticks -= 1;
        if state.legit_delay_ticks > 0 {
            return ClickResult::Skip;
        }
    }

    state.cooldown_ready = false;

    match config.crit_mode {
        CritMode::Off => ClickResult::Click,
        CritMode::On => {
            if config.crit_stop_sprint && player.is_sprinting() {
                state.was_sprinting_before_crit = true;
                state.crit_phase = CritPhase::StopSprint;
                ClickResult::StopSprint
            } else {
                state.crit_phase = CritPhase::WaitFall;
                ClickResult::Skip
            }
        }
        CritMode::Smart => {
            if input.is_falling && !player.is_sprinting() {
                return ClickResult::Click;
            }
            if config.crit_stop_sprint && player.is_sprinting() {
                state.was_sprinting_before_crit = true;
                state.crit_phase = CritPhase::SmartStopSprint;
                return ClickResult::StopSprint;
            }
            ClickResult::Click
        }
    }
}

fn drive_crit_phase(
    config: &CooldownClickConfig,
    state: &mut CritState,
    input: &ClickInput,
    rng: &mut Pcg32,
) -> ClickResult {
    match state.crit_phase {
        CritPhase::StopSprint => {
            state.crit_phase = CritPhase::WaitFall;
            ClickResult::Skip
        }
        CritPhase::WaitFall => {
            if input.is_falling {
                state.crit_phase = CritPhase::WaitCooldown;
                state.cooldown_ready = false;
                state.wait_fall_ticks = 0;
            } else {
                state.wait_fall_ticks += 1;
                if state.wait_fall_ticks >= 10 {
                    // Give up on the crit rather than hovering forever; the
                    // ATTACK phase still restores sprint on the way out.
                    state.wait_fall_ticks = 0;
                    state.crit_phase = CritPhase::Attack;
                    return ClickResult::Click;
                }
            }
            ClickResult::Skip
        }
        CritPhase::WaitCooldown => {
            if input.attack_cooldown < config.cooldown_threshold {
                if !state.cooldown_ready {
                    state.cooldown_ready = true;
                }
                return ClickResult::Skip;
            }
            if !state.cooldown_ready {
                state.cooldown_ready = true;
            }
            if config.cooldown_mode == CooldownClickMode::Legit {
                if state.legit_delay_ticks == 0 {
                    state.legit_delay_ticks = sample_legit_delay(rng);
                }
                state.legit_delay_ticks -= 1;
                if state.legit_delay_ticks > 0 {
                    return ClickResult::Skip;
                }
            }
            state.cooldown_ready = false;
            state.crit_phase = CritPhase::Attack;
            ClickResult::Click
        }
        CritPhase::Attack => {
            state.crit_phase = CritPhase::Restore;
            if config.crit_stop_sprint && state.was_sprinting_before_crit {
                return ClickResult::RestoreSprint { was_sprinting: true };
            }
            reset_crit_state(state);
            ClickResult::Skip
        }
        CritPhase::Restore => {
            reset_crit_state(state);
            ClickResult::Skip
        }
        CritPhase::SmartStopSprint => {
            state.crit_phase = CritPhase::SmartRestore;
            ClickResult::Click
        }
        CritPhase::SmartRestore => {
            if config.crit_stop_sprint && state.was_sprinting_before_crit {
                reset_crit_state(state);
                return ClickResult::RestoreSprint { was_sprinting: true };
            }
            reset_crit_state(state);
            ClickResult::Skip
        }
        CritPhase::Idle => ClickResult::Skip,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Vec3, F_MINING, F_ON_GROUND, F_SPRINTING};

    fn player(flags: u16) -> PlayerState {
        PlayerState {
            position: Vec3::new(0.0, 64.0, 0.0),
            motion: Vec3::new(0.0, 0.0, 0.0),
            yaw: 0.0,
            pitch: 0.0,
            health: 20.0,
            hurt_time: 0,
            hurt_resistant_time: 0,
            weapon: WeaponType::Sword,
            flags,
            ticks: 0,
        }
    }

    fn input(flags: u16, cooldown: f32, falling: bool) -> ClickInput {
        ClickInput {
            player: player(flags),
            target: None,
            attack_cooldown: cooldown,
            is_falling: falling,
        }
    }

    #[test]
    fn mining_blocks_the_click() {
        let mut st = ClickState::default();
        let mut rng = Pcg32::new(1);
        let cfg = ClickConfig::default();
        let r = probabilistic_tick(&cfg, &mut st, &input(F_MINING, 1.0, false), &mut rng);
        assert_eq!(r, ClickResult::Skip);
    }

    #[test]
    fn fresh_rhythm_waits_out_the_re_engage_floor() {
        // A brand-new state (module just enabled, or just reset) does not
        // click on the first gate-passing tick: the re-engagement floor is a
        // reaction gap, minimum four ticks.
        let mut st = ClickState::default();
        let mut rng = Pcg32::new(21);
        let cfg = ClickConfig::default();
        for t in 0..(RE_ENGAGE_MIN_TICKS - 1) {
            assert_eq!(
                probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng),
                ClickResult::Skip,
                "tick {} clicked inside the reaction floor",
                t
            );
        }
        assert!(st.rhythm_live, "the rhythm should be armed by now");
    }

    #[test]
    fn gate_break_kills_the_rhythm_and_re_arms_at_the_floor() {
        // onlyGround in the trigger options gives a deterministic gate: in
        // the air the condition fails, on the ground it passes.
        let mut cfg = ClickConfig::default();
        cfg.trigger_options.only_ground = true;
        let mut st = ClickState::default();
        let mut rng = Pcg32::new(5);

        // Warm up on the ground until at least one click has landed, with the
        // rhythm live and a countdown in flight.
        let mut clicked = false;
        for _ in 0..200 {
            if probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng)
                == ClickResult::Click
            {
                clicked = true;
            }
        }
        assert!(clicked, "the warm-up should click");

        // One tick in the air: no click, and the rhythm must be dead.
        assert_eq!(
            probabilistic_tick(&cfg, &mut st, &input(0, 1.0, false), &mut rng),
            ClickResult::Skip
        );
        assert!(!st.rhythm_live, "a failed condition must kill the rhythm");

        // Back on the ground: no click for at least the floor, then a click
        // lands well inside a second.
        for t in 0..(RE_ENGAGE_MIN_TICKS - 1) {
            assert_eq!(
                probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng),
                ClickResult::Skip,
                "tick {} after re-engagement clicked inside the floor",
                t
            );
        }
        let mut clicked_after = 0;
        for _ in 0..200 {
            if probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng)
                == ClickResult::Click
            {
                clicked_after += 1;
            }
        }
        assert!(clicked_after > 0, "the rhythm must resume clicking");
    }

    #[test]
    fn sustained_cps_respects_the_configured_band() {
        // Defaults are 8..10 CPS. With the 5% pauses the long-run rate sits
        // slightly below the pure band; anything outside 5..11 over 20 s is a
        // broken scheduler, not a statistic.
        let mut st = ClickState::default();
        let mut rng = Pcg32::new(777);
        let cfg = ClickConfig::default();
        let mut clicks = 0;
        for _ in 0..20_000 {
            if probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng)
                == ClickResult::Click
            {
                clicks += 1;
            }
        }
        let cps = clicks as f64 / 1000.0;
        assert!(
            (5.0..=11.0).contains(&cps),
            "sustained cps {} outside the human band",
            cps
        );
    }

    #[test]
    fn absurd_config_cannot_exceed_the_tick_ceiling() {
        // A configured 25 CPS is physically impossible at 20 tps; the
        // interval model clamps to one click per tick at most — the old
        // Bernoulli model did the same via p >= 1, so this pins the ceiling.
        let mut st = ClickState::default();
        let mut rng = Pcg32::new(88);
        let cfg = ClickConfig { min_cps: 25, max_cps: 25, ..Default::default() };
        let mut clicks = 0;
        for _ in 0..2000 {
            if probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng)
                == ClickResult::Click
            {
                clicks += 1;
            }
        }
        assert!(clicks <= 2000, "clicked more than once per tick");
        // And it must not collapse either: a 25 CPS request still clicks hard.
        assert!(clicks > 1500, "only {} clicks at requested 25 cps", clicks);
    }

    #[test]
    fn double_mode_queues_the_second_click() {
        let mut st = ClickState { pending_second_click: true, ..Default::default() };
        let mut rng = Pcg32::new(1);
        let cfg = ClickConfig { click_mode: ClickMode::Double, ..Default::default() };
        assert_eq!(
            probabilistic_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng),
            ClickResult::Click
        );
        assert!(!st.pending_second_click);
    }

    #[test]
    fn cooldown_gate_holds_until_ready() {
        let mut st = CritState::default();
        let mut rng = Pcg32::new(1);
        let cfg = CooldownClickConfig::default();
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(F_ON_GROUND, 0.4, false), &mut rng),
            ClickResult::Skip
        );
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(F_ON_GROUND, 1.0, false), &mut rng),
            ClickResult::Click
        );
    }

    #[test]
    fn crit_on_stops_sprint_then_clicks_then_restores() {
        let mut st = CritState::default();
        let mut rng = Pcg32::new(2);
        let cfg = CooldownClickConfig { crit_mode: CritMode::On, ..Default::default() };

        // Sprinting + ready → stop sprint
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(F_SPRINTING, 1.0, false), &mut rng),
            ClickResult::StopSprint
        );
        // Next tick: STOP_SPRINT → WAIT_FALL, still skipping
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(0, 1.0, false), &mut rng),
            ClickResult::Skip
        );
        assert_eq!(st.crit_phase, CritPhase::WaitFall);
        // Falling: the WAIT_FALL branch only advances the phase, it does not
        // click on the same tick. One more skip.
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(0, 1.0, true), &mut rng),
            ClickResult::Skip
        );
        assert_eq!(st.crit_phase, CritPhase::WaitCooldown);
        // Now the cooldown branch clicks
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(0, 1.0, true), &mut rng),
            ClickResult::Click
        );
        assert_eq!(st.crit_phase, CritPhase::Attack);
        // Next tick → restore sprint
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(0, 1.0, true), &mut rng),
            ClickResult::RestoreSprint { was_sprinting: true }
        );
        // Then idle
        assert_eq!(
            cooldown_tick(&cfg, &mut st, &input(0, 1.0, true), &mut rng),
            ClickResult::Skip
        );
        assert_eq!(st.crit_phase, CritPhase::Idle);
    }

    #[test]
    fn wait_fall_gives_up_after_ten_ticks() {
        let mut st = CritState { crit_phase: CritPhase::WaitFall, ..Default::default() };
        let mut rng = Pcg32::new(3);
        let cfg = CooldownClickConfig { crit_mode: CritMode::On, ..Default::default() };
        let mut clicks = 0;
        for _ in 0..12 {
            if cooldown_tick(&cfg, &mut st, &input(0, 1.0, false), &mut rng) == ClickResult::Click {
                clicks += 1;
            }
        }
        assert_eq!(clicks, 1, "exactly one fallback click");
    }

    #[test]
    fn weapon_filter_matches() {
        assert!(WeaponFilter::Any.matches(WeaponType::Other));
        assert!(!WeaponFilter::Sword.matches(WeaponType::Axe));
        assert!(WeaponFilter::SwordAndAxe.matches(WeaponType::Axe));
    }
}
