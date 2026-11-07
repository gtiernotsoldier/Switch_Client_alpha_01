//! Port of `io.doppel.core.strategy.click`.
//!
//! Two strategies with different clocks: the 1.8 one decides per tick from a
//! CPS probability, the 1.9 one waits on an attack cooldown and optionally runs
//! a crit state machine. Both are pure functions of (config, state, input, rng).
//!
//! The crit FSM is the part worth porting carefully. Its phases emit actions on
//! *consecutive* ticks (`StopSprint` this tick, `Click` the next), so a phase
//! transition that is off by one tick produces a click that does land but never
//! crits — silent, and invisible to any test that only counts clicks.

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
}

impl Default for ClickState {
    fn default() -> Self {
        ClickState { last_target_id: -1, pending_second_click: false }
    }
}

impl ClickState {
    pub fn reset(&mut self) {
        *self = ClickState::default();
    }
}

// ── 1.8 probabilistic ──────────────────────────────────────────────────────

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

    if config.disable_on_block && player.is_mining() {
        return ClickResult::Skip;
    }

    if state.pending_second_click {
        state.pending_second_click = false;
        return ClickResult::Click;
    }

    if condition::check(&config.trigger_options, player, input.target.as_ref(), rng).is_some() {
        return ClickResult::Skip;
    }

    if let Some(t) = input.target.as_ref() {
        if t.id != state.last_target_id {
            state.last_target_id = t.id;
        }
    }

    let lo = config.min_cps.min(config.max_cps);
    let hi = config.max_cps.max(config.min_cps);
    let effective = match config.mode {
        ClickOperatingMode::Normal => sample_cps_in_range(lo, hi, rng),
        ClickOperatingMode::Legit => {
            let base = sample_cps_in_range(lo, hi, rng);
            match input.target.as_ref() {
                Some(t) => adjust_cps_by_distance(base, lo, hi, t.distance, rng),
                None => base,
            }
        }
    }
    .max(1);

    let click_chance = effective as f64 / 20.0;
    if (rng.next_float() as f64) >= click_chance {
        return ClickResult::Skip;
    }

    if config.click_mode == ClickMode::Double {
        state.pending_second_click = true;
    }
    ClickResult::Click
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
