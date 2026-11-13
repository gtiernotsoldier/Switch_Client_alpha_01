//! Port of `io.doppel.core.strategy.combat` and `strategy.autoblock`.

use crate::rng::Pcg32;

// ── shared combat trigger ──────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriggerMode {
    Post,
    Pre,
    Equal,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EvalResult {
    pub fire: bool,
    pub hit_counter: i32,
    pub hit_threshold: i32,
}

/// Fires after landing hits, gated by hurt-window, hit count and probability.
///
/// The roll happens *after* the counter check, so a miss still resets the
/// counter and draws a fresh threshold — which is why the RNG consumption
/// differs between the fire and no-fire paths and must be ported exactly.
#[allow(clippy::too_many_arguments)]
pub fn combat_trigger_evaluate(
    mode: TriggerMode,
    target_hurt_resistant_time: i32,
    max_hurt_time: i32,
    hit_counter: i32,
    hit_threshold: i32,
    hit_per_min: i32,
    hit_per_max: i32,
    chance: i32,
    hurt_gate: bool,
    rng: &mut Pcg32,
) -> EvalResult {
    let hurt_ok = !hurt_gate
        || match mode {
            TriggerMode::Post => target_hurt_resistant_time >= 10,
            TriggerMode::Pre => {
                target_hurt_resistant_time > 0 && target_hurt_resistant_time <= max_hurt_time
            }
            TriggerMode::Equal => target_hurt_resistant_time == max_hurt_time,
        };
    if !hurt_ok {
        return EvalResult {
            fire: false,
            hit_counter,
            hit_threshold,
        };
    }

    let c = hit_counter + 1;
    if c < hit_threshold {
        return EvalResult {
            fire: false,
            hit_counter: c,
            hit_threshold,
        };
    }

    if chance < 100 && (rng.next_int(100) as i32) >= chance {
        let next_t = (rng.random_int(hit_per_min, hit_per_max)).max(1);
        return EvalResult {
            fire: false,
            hit_counter: 0,
            hit_threshold: next_t,
        };
    }

    let next_threshold = (rng.random_int(hit_per_min, hit_per_max)).max(1);
    EvalResult {
        fire: true,
        hit_counter: 0,
        hit_threshold: next_threshold,
    }
}

// ── adaptive block arbiter ─────────────────────────────────────────────────

#[derive(Debug, Clone, Copy)]
pub struct BlockWeights {
    pub lock: f64,
    pub threat: f64,
    pub sustain: f64,
    pub reflex: f64,
}

impl Default for BlockWeights {
    fn default() -> Self {
        BlockWeights {
            lock: 1.0,
            threat: 0.6,
            sustain: 0.5,
            reflex: 0.4,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Verdict {
    pub block: bool,
    pub score: f64,
    pub firing: Vec<&'static str>,
    pub throttled: bool,
}

pub const DEFAULT_THRESHOLD: f64 = 0.5;
pub const DEFAULT_DUTY_WINDOW: usize = 60;

/// Rolling duty-cycle over the last N ticks.
///
/// `duty()` divides by `filled`, not by the window length: before the window is
/// full, the average is over what has actually been observed. Dividing by the
/// window would make a fresh guard look idle and let it exceed its budget.
#[derive(Debug, Clone)]
pub struct BlockArbiterState {
    held: Vec<bool>,
    cursor: usize,
    filled: usize,
}

impl Default for BlockArbiterState {
    fn default() -> Self {
        BlockArbiterState::new(DEFAULT_DUTY_WINDOW)
    }
}

impl BlockArbiterState {
    pub fn new(window: usize) -> Self {
        // window == 0 would panic (`% 0`, indexing an empty vec) inside the
        // per-call `catch_unwind` — every request would come back Degraded
        // instead of a sane duty cycle. Degrade to a one-tick window instead;
        // the in-tree callers always pass the default 60.
        let window = window.max(1);
        BlockArbiterState {
            held: vec![false; window],
            cursor: 0,
            filled: 0,
        }
    }

    pub fn duty(&self) -> f64 {
        if self.filled == 0 {
            return 0.0;
        }
        let on = self.held.iter().filter(|b| **b).count();
        on as f64 / self.filled as f64
    }

    pub fn record(&mut self, blocked: bool) {
        self.held[self.cursor] = blocked;
        self.cursor = (self.cursor + 1) % self.held.len();
        if self.filled < self.held.len() {
            self.filled += 1;
        }
    }

    pub fn reset(&mut self) {
        self.held.fill(false);
        self.cursor = 0;
        self.filled = 0;
    }
}

#[allow(clippy::too_many_arguments)]
pub fn block_arbiter_evaluate(
    locked: bool,
    threat: bool,
    sustain: bool,
    reflex: bool,
    weights: BlockWeights,
    threshold: f64,
    duty: f64,
    max_duty: f64,
) -> Verdict {
    let signals = [
        ("lock", locked, weights.lock),
        ("threat", threat, weights.threat),
        ("sustain", sustain, weights.sustain),
        ("reflex", reflex, weights.reflex),
    ];

    let mut score = 0.0;
    let mut firing = Vec::new();
    for (name, on, w) in signals {
        if on {
            firing.push(name);
            score += w;
        }
    }

    if score < threshold {
        return Verdict {
            block: false,
            score,
            firing,
            throttled: false,
        };
    }

    if duty > max_duty && !locked {
        return Verdict {
            block: false,
            score,
            firing,
            throttled: true,
        };
    }

    Verdict {
        block: true,
        score,
        firing,
        throttled: false,
    }
}

// ── block predictor ────────────────────────────────────────────────────────

pub const DEFAULT_MAX_HURT: i32 = 20;
pub const DEFAULT_RTT_TICKS: i32 = 2;
pub const DEFAULT_LEAD_TICKS: i32 = 2;
pub const DEFAULT_PREDICT_WINDOW: i32 = 3;
pub const DEFAULT_PREDICT_CHANCE: i32 = 50;
pub const DEFAULT_INTERVAL_ALPHA: f64 = 0.35;
pub const DEFAULT_SEED_INTERVAL: f64 = 12.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockReason {
    Locked,
    PredictedAttack,
    Open,
}

#[derive(Debug, Clone, Copy)]
pub struct BlockDecision {
    pub block: bool,
    pub reason: BlockReason,
    pub effective_hurt: i32,
    pub locked: bool,
    pub threat: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct PredictorState {
    pub last_opponent_hit_tick: i32,
    pub interval_ema: f64,
    pub interval_samples: i32,
}

impl Default for PredictorState {
    fn default() -> Self {
        PredictorState {
            last_opponent_hit_tick: -1,
            interval_ema: 0.0,
            interval_samples: 0,
        }
    }
}

impl PredictorState {
    pub fn reset(&mut self) {
        *self = PredictorState::default();
    }
}

#[derive(Debug, Clone, Copy)]
pub struct PredictorConfig {
    pub max_hurt_resistant_time: i32,
    pub rtt_ticks: i32,
    pub lead_ticks: i32,
    pub predict_attacks: bool,
    pub predict_window_ticks: i32,
    pub predict_chance: i32,
    pub interval_alpha: f64,
    pub seed_interval_ticks: f64,
}

impl Default for PredictorConfig {
    fn default() -> Self {
        PredictorConfig {
            max_hurt_resistant_time: DEFAULT_MAX_HURT,
            rtt_ticks: DEFAULT_RTT_TICKS,
            lead_ticks: DEFAULT_LEAD_TICKS,
            predict_attacks: true,
            predict_window_ticks: DEFAULT_PREDICT_WINDOW,
            predict_chance: DEFAULT_PREDICT_CHANCE,
            interval_alpha: DEFAULT_INTERVAL_ALPHA,
            seed_interval_ticks: DEFAULT_SEED_INTERVAL,
        }
    }
}

pub fn lock_threshold(max_hurt_resistant_time: i32) -> i32 {
    let m = if max_hurt_resistant_time > 0 {
        max_hurt_resistant_time
    } else {
        DEFAULT_MAX_HURT
    };
    m / 2
}

pub fn damage_locked(hurt_resistant_time: i32, config: &PredictorConfig) -> bool {
    let effective = (hurt_resistant_time - config.rtt_ticks).max(0);
    effective > lock_threshold(config.max_hurt_resistant_time) + config.lead_ticks
}

fn observe_opponent(current_tick: i32, just_hit: bool, state: &mut PredictorState, config: &PredictorConfig) {
    if !just_hit {
        return;
    }
    let previous = state.last_opponent_hit_tick;
    if previous >= 0 {
        let gap = (current_tick - previous) as f64;
        if gap > 0.0 {
            state.interval_ema = if state.interval_samples == 0 {
                gap
            } else {
                state.interval_ema + config.interval_alpha * (gap - state.interval_ema)
            };
            state.interval_samples += 1;
        }
    } else {
        // First hit ever: we have a timestamp but no period yet. The seed is
        // only a placeholder (it keeps the HUD readable); `attack_due` refuses
        // to predict until a real gap lands. Intentional, not a dead write —
        // the Kotlin oracle documents this verbatim.
        state.interval_ema = config.seed_interval_ticks;
    }
    state.last_opponent_hit_tick = current_tick;
}

fn attack_due(current_tick: i32, state: &PredictorState, config: &PredictorConfig) -> bool {
    let last = state.last_opponent_hit_tick;
    if last < 0 || state.interval_samples <= 0 {
        return false;
    }
    let since = (current_tick - last) as f64;
    let due = state.interval_ema - since;
    // Bounded on both sides: without the lower bound the clock latches, because
    // `due` only grows more negative and stays "due" forever.
    due <= config.predict_window_ticks as f64 && due >= -(config.predict_window_ticks as f64)
}

pub fn block_predictor_evaluate(
    target_hurt_resistant_time: Option<i32>,
    current_tick: i32,
    opponent_just_hit: bool,
    state: &mut PredictorState,
    config: &PredictorConfig,
    rng: &mut Pcg32,
) -> BlockDecision {
    observe_opponent(current_tick, opponent_just_hit, state, config);

    let hurt = match target_hurt_resistant_time {
        Some(h) => h,
        None => {
            return BlockDecision {
                block: false,
                reason: BlockReason::Open,
                effective_hurt: 0,
                locked: false,
                threat: false,
            }
        }
    };

    let effective = (hurt - config.rtt_ticks).max(0);
    let locked = effective > lock_threshold(config.max_hurt_resistant_time) + config.lead_ticks;

    let threat = !locked
        && config.predict_attacks
        && attack_due(current_tick, state, config)
        && (config.predict_chance >= 100 || (rng.next_int(100) as i32) < config.predict_chance);

    let reason = if locked {
        BlockReason::Locked
    } else if threat {
        BlockReason::PredictedAttack
    } else {
        BlockReason::Open
    };

    BlockDecision {
        block: locked || threat,
        reason,
        effective_hurt: effective,
        locked,
        threat,
    }
}

// ── range gate ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RangeMode {
    InRange,
    OnEnter,
}

pub const DEFAULT_HYSTERESIS: f32 = 1.5;

#[derive(Debug, Clone, Copy, Default)]
pub struct RangeGateState {
    pub engaged: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct RangeGateConfig {
    pub mode: RangeMode,
    pub min_distance: f32,
    pub max_distance: f32,
    pub hysteresis: f32,
}

impl Default for RangeGateConfig {
    fn default() -> Self {
        RangeGateConfig {
            mode: RangeMode::InRange,
            min_distance: 0.0,
            max_distance: 3.0,
            hysteresis: DEFAULT_HYSTERESIS,
        }
    }
}

pub fn range_gate_compute(
    state: Option<&mut RangeGateState>,
    config: &RangeGateConfig,
    distance: Option<f32>,
) -> bool {
    match distance {
        None => {
            if let Some(s) = state {
                s.engaged = false;
            }
            false
        }
        Some(d) => {
            let within = d >= config.min_distance && d <= config.max_distance;
            match config.mode {
                RangeMode::InRange => within,
                RangeMode::OnEnter => match state {
                    None => within,
                    Some(s) => {
                        if within {
                            s.engaged = true;
                        } else if d > config.max_distance + config.hysteresis {
                            s.engaged = false;
                        }
                        s.engaged && d >= config.min_distance
                    }
                },
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn post_gate_needs_ten_or_more() {
        let mut rng = Pcg32::new(1);
        let r = combat_trigger_evaluate(TriggerMode::Post, 9, 10, 0, 1, 1, 1, 100, true, &mut rng);
        assert!(!r.fire);
        let r = combat_trigger_evaluate(TriggerMode::Post, 10, 10, 0, 1, 1, 1, 100, true, &mut rng);
        assert!(r.fire);
    }

    #[test]
    fn counter_must_reach_threshold() {
        let mut rng = Pcg32::new(1);
        let r = combat_trigger_evaluate(TriggerMode::Post, 10, 10, 0, 3, 1, 1, 100, true, &mut rng);
        assert!(!r.fire);
        assert_eq!(r.hit_counter, 1);
        let r = combat_trigger_evaluate(TriggerMode::Post, 10, 10, 2, 3, 1, 1, 100, true, &mut rng);
        assert!(r.fire);
        assert_eq!(r.hit_counter, 0);
    }

    #[test]
    fn lock_alone_holds_and_is_not_throttled() {
        let w = BlockWeights::default();
        let v = block_arbiter_evaluate(true, false, false, false, w, DEFAULT_THRESHOLD, 0.9, 0.5);
        assert!(v.block && !v.throttled);

        let v = block_arbiter_evaluate(false, true, false, false, w, DEFAULT_THRESHOLD, 0.9, 0.5);
        assert!(!v.block && v.throttled);
    }

    #[test]
    fn reflex_alone_does_not_reach_threshold() {
        let w = BlockWeights::default();
        let v = block_arbiter_evaluate(false, false, false, true, w, DEFAULT_THRESHOLD, 0.0, 0.5);
        assert!(!v.block);
        assert!((v.score - 0.4).abs() < 1e-12);
    }

    #[test]
    fn duty_uses_observed_ticks_not_window() {
        let mut s = BlockArbiterState::new(60);
        assert_eq!(s.duty(), 0.0);
        s.record(true);
        s.record(false);
        assert!((s.duty() - 0.5).abs() < 1e-12);
    }

    #[test]
    fn damage_lock_uses_high_half() {
        let c = PredictorConfig::default();
        assert!(damage_locked(20, &c));
        assert!(!damage_locked(5, &c));
    }

    #[test]
    fn predictor_no_target_is_open() {
        let mut st = PredictorState::default();
        let mut rng = Pcg32::new(1);
        let d = block_predictor_evaluate(None, 0, false, &mut st, &PredictorConfig::default(), &mut rng);
        assert_eq!(d.reason, BlockReason::Open);
    }

    #[test]
    fn range_gate_hysteresis_holds_after_enter() {
        let mut st = RangeGateState::default();
        let cfg = RangeGateConfig {
            mode: RangeMode::OnEnter,
            min_distance: 0.0,
            max_distance: 3.0,
            hysteresis: 1.5,
        };
        assert!(range_gate_compute(Some(&mut st), &cfg, Some(2.0)));
        // 3.5 is beyond max but inside hysteresis → still engaged
        assert!(range_gate_compute(Some(&mut st), &cfg, Some(3.5)));
        // 5.0 is beyond max + hysteresis → released
        assert!(!range_gate_compute(Some(&mut st), &cfg, Some(5.0)));
    }

    #[test]
    fn range_gate_none_resets_latch() {
        let mut st = RangeGateState { engaged: true };
        let cfg = RangeGateConfig { mode: RangeMode::OnEnter, ..Default::default() };
        assert!(!range_gate_compute(Some(&mut st), &cfg, None));
        assert!(!st.engaged);
    }
}
