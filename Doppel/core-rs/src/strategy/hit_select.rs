//! Port of `HitSelect` — the click selector.
//!
//! This is the only module whose decision is split across two threads: the
//! attack gate is consulted on the render thread at every click, while the
//! target tracking runs on the 20 Hz tick. Both halves used to share a bag of
//! `@Volatile` fields in the JVM object. Across a process boundary that bag has
//! to live here, and the two calls become two ops that mutate one `State`:
//!
//!   * `tick` — feeds the tick's view of the target and refreshes the windows.
//!     It also decides whether CounterHit fires this tick.
//!   * `gate` — reads those windows and answers one click.
//!
//! The gate order is preserved exactly, including the two rolls. `chance` and
//! `missedSwing` draw from the same stream and the second draw only happens on
//! the branch that reaches it — a port that rolled both up front would spend a
//! draw on clicks that never had a chance and diverge on the next click.

/// Everything the gate needs to remember between ticks.
pub struct State {
    /// Id of the target the windows belong to; -1 when there is none.
    pub tracked_target_id: i32,
    /// When this target was first seen (nanoseconds), -1 before the first tick.
    pub target_first_seen_ns: i64,
    /// When a hit on this target was last confirmed, -1 when there was none.
    pub last_confirmed_hit_ns: i64,
    /// Previous tick's target hurtTime, for the server-confirmed hit edge.
    pub prev_hurt_time: i32,
    /// Refreshed on each tick: airborne and falling means a crit is possible.
    pub can_crit_now: bool,
    /// Last time CounterHit fired, -1 before the first one.
    ///
    /// Not 0: same reasoning as the two windows above — the caller's clock may
    /// read 0 (or a small boot-relative value; JVM `nanoTime` may even be
    /// negative), and a 0 sentinel would swallow the first eligible CounterHit
    /// inside the first cooldown window after the clock origin.
    pub last_counter_ns: i64,
}

impl State {
    pub fn new() -> State {
        State {
            tracked_target_id: -1,
            // Not 0: the caller's clock is allowed to read 0 in a replay, and a
            // 0 sentinel would then look like a window that opened at the epoch.
            target_first_seen_ns: -1,
            last_confirmed_hit_ns: -1,
            prev_hurt_time: 0,
            can_crit_now: false,
            last_counter_ns: -1,
        }
    }
}

impl Default for State {
    fn default() -> Self {
        State::new()
    }
}

/// One 20 Hz tick. `can_crit` is computed on the JVM side (it is two field
/// reads, not a decision); everything that remembers anything is here.
#[derive(Clone, Copy, Debug)]
pub struct TickInput {
    pub now_ns: i64,
    pub can_crit: bool,
    pub player_hurt_time: i32,
    pub left_mouse_down: bool,
    pub has_target: bool,
    pub target_id: i32,
    pub target_hurt_time: i32,
    pub counter_hit: bool,
    pub counter_cd_ms: i32,
}

/// Advances the windows. Returns whether CounterHit should fire this tick —
/// the one-tick pulse itself is a platform concern and stays in the adapter.
pub fn tick(state: &mut State, input: TickInput) -> bool {
    state.can_crit_now = input.can_crit;

    if !input.has_target {
        state.tracked_target_id = -1;
        state.prev_hurt_time = 0;
        // The reference returns here: CounterHit is not considered without a
        // target. The windows are left alone; a new target overwrites them.
        return false;
    }

    if input.target_id != state.tracked_target_id {
        // New target: restart both windows and do not inherit the previous
        // target's hurt state.
        state.tracked_target_id = input.target_id;
        state.target_first_seen_ns = input.now_ns;
        state.last_confirmed_hit_ns = -1;
        state.prev_hurt_time = input.target_hurt_time;
    }

    // Server-confirmed hit: the target's hurtTime rose since the previous tick.
    if input.target_hurt_time > state.prev_hurt_time {
        state.last_confirmed_hit_ns = input.now_ns;
    }
    state.prev_hurt_time = input.target_hurt_time;

    // CounterHit: eat a hit while not clicking → answer it once.
    if input.counter_hit
        && !input.left_mouse_down
        && input.player_hurt_time > 0
        && (state.last_counter_ns < 0
            || input.now_ns - state.last_counter_ns >= input.counter_cd_ms as i64 * 1_000_000)
    {
        state.last_counter_ns = input.now_ns;
        true
    } else {
        false
    }
}

/// One click, on the render thread.
#[derive(Clone, Copy, Debug)]
pub struct GateInput {
    pub now_ns: i64,
    pub gui_open: bool,
    pub looking_at_block: bool,
    pub chance: i32,
    pub criticals: bool,
    pub missed_swing: i32,
    pub has_forward_target: bool,
    pub forward_hurt_resistant_time: i32,
    pub wait_first_ms: i32,
    pub retime_at: i32,
    pub pause_ms: i32,
}

/// Returns true when the click should be delivered, false when it is swallowed.
///
/// `roll()` supplies a value in `0..=99` and is called at most twice, in gate
/// order. It is a closure rather than a `Pcg32` so the parity fixture can hand
/// in a scripted sequence — the two sides do not share a generator.
pub fn gate(state: &State, input: GateInput, roll: &mut impl FnMut() -> i32) -> bool {
    // 1. Never swallow clicks while a screen is open or the crosshair is on a
    //    block: block interaction (mining, placing) is not an attack.
    if input.gui_open || input.looking_at_block {
        return true;
    }

    // 6. Chance: skip the selector entirely for this click.
    if input.chance < 100 && roll() >= input.chance {
        return true;
    }

    // 2. Criticals only.
    if input.criticals && !state.can_crit_now {
        return false;
    }

    if !input.has_forward_target {
        // Nothing on the forward ray: raven-bs scores these as missed swings
        // and can cancel them by a separate rate.
        return !(input.missed_swing > 0 && roll() < input.missed_swing);
    }

    // 5. Wait for the first hit on a newly acquired target.
    if input.wait_first_ms > 0
        && state.target_first_seen_ns >= 0
        && input.now_ns - state.target_first_seen_ns < input.wait_first_ms as i64 * 1_000_000
    {
        return false;
    }

    // 3. Still invulnerable: the hit would be discarded by the server.
    if input.forward_hurt_resistant_time > input.retime_at {
        return false;
    }

    // 4. Burst window after our own confirmed hit.
    if input.pause_ms > 0
        && state.last_confirmed_hit_ns >= 0
        && input.now_ns - state.last_confirmed_hit_ns < input.pause_ms as i64 * 1_000_000
    {
        return false;
    }

    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rng::Pcg32;

    const MS: i64 = 1_000_000;

    fn state() -> State {
        State::new()
    }

    fn tick_input(now_ns: i64, target_hurt_time: i32) -> TickInput {
        TickInput {
            now_ns,
            can_crit: true,
            player_hurt_time: 0,
            left_mouse_down: false,
            has_target: true,
            target_id: 7,
            target_hurt_time,
            counter_hit: false,
            counter_cd_ms: 300,
        }
    }

    fn gate_input(now_ns: i64) -> GateInput {
        GateInput {
            now_ns,
            gui_open: false,
            looking_at_block: false,
            chance: 100,
            criticals: false,
            missed_swing: 0,
            has_forward_target: true,
            forward_hurt_resistant_time: 0,
            wait_first_ms: 0,
            retime_at: 3,
            pause_ms: 0,
        }
    }

    /// A roll that records how many draws the gate actually made.
    fn recording(value: i32) -> (Box<dyn FnMut() -> i32>, std::rc::Rc<std::cell::Cell<usize>>) {
        let calls = std::rc::Rc::new(std::cell::Cell::new(0));
        let c = calls.clone();
        (Box::new(move || { c.set(c.get() + 1); value }), calls)
    }

    #[test]
    fn gui_and_blocks_always_pass_without_rolling() {
        let s = state();
        let (mut roll, calls) = recording(0);
        assert!(gate(&s, GateInput { gui_open: true, ..gate_input(0) }, &mut roll));
        assert!(gate(&s, GateInput { looking_at_block: true, ..gate_input(0) }, &mut roll));
        assert_eq!(calls.get(), 0, "these gates must not spend a draw");
    }

    #[test]
    fn chance_gate_passes_through_and_spends_one_draw() {
        let s = state();
        let (mut roll, calls) = recording(99);
        // chance 50, draw 99 >= 50 → the selector is skipped, so a click that
        // would otherwise be held must pass.
        let input = GateInput {
            chance: 50,
            criticals: true,
            ..gate_input(0)
        };
        assert!(gate(&s, input, &mut roll), "can_crit is false, so only chance can pass it");
        assert_eq!(calls.get(), 1);
    }

    #[test]
    fn criticals_hold_without_a_crit_state() {
        let s = state(); // can_crit_now = false
        let (mut roll, _) = recording(0);
        assert!(!gate(&s, GateInput { criticals: true, ..gate_input(0) }, &mut roll));
    }

    #[test]
    fn missed_swing_uses_the_second_draw_only_on_that_branch() {
        let s = state();
        let (mut roll, calls) = recording(0);
        let input = GateInput {
            has_forward_target: false,
            missed_swing: 100,
            ..gate_input(0)
        };
        assert!(!gate(&s, input, &mut roll), "a 100% missed-swing rate swallows the whiff");
        assert_eq!(calls.get(), 1, "chance is 100, so only the missed-swing draw happens");

        // With a target in front, the missed-swing draw is never made.
        let (mut roll2, calls2) = recording(0);
        assert!(gate(&s, gate_input(0), &mut roll2));
        assert_eq!(calls2.get(), 0);
    }

    #[test]
    fn new_target_restarts_the_wait_first_window() {
        let mut s = state();
        tick(&mut s, tick_input(1_000 * MS, 0));
        let (mut roll, _) = recording(0);
        let input = GateInput {
            now_ns: 1_100 * MS,
            wait_first_ms: 500,
            ..gate_input(1_100 * MS)
        };
        assert!(!gate(&s, input, &mut roll), "100 ms into a 500 ms wait-first window");
        assert!(gate(
            &s,
            GateInput { now_ns: 1_600 * MS, ..input },
            &mut roll
        ));
    }

    #[test]
    fn a_confirmed_hit_opens_the_burst_window() {
        let mut s = state();
        tick(&mut s, tick_input(1_000 * MS, 0));
        // hurtTime rises → a hit is confirmed at 1_200 ms.
        tick(&mut s, tick_input(1_200 * MS, 5));
        let (mut roll, _) = recording(0);
        let input = GateInput {
            now_ns: 1_300 * MS,
            pause_ms: 500,
            ..gate_input(1_300 * MS)
        };
        assert!(!gate(&s, input, &mut roll));
        assert!(gate(&s, GateInput { now_ns: 1_800 * MS, ..input }, &mut roll));
    }

    #[test]
    fn iframe_gate_holds_while_the_target_is_invulnerable() {
        let s = state();
        let (mut roll, _) = recording(0);
        let input = GateInput {
            forward_hurt_resistant_time: 8,
            retime_at: 3,
            ..gate_input(0)
        };
        assert!(!gate(&s, input, &mut roll));
    }

    #[test]
    fn counter_hit_needs_a_target_and_respects_its_cooldown() {
        let mut s = state();
        let base = TickInput {
            counter_hit: true,
            player_hurt_time: 1,
            ..tick_input(1_000 * MS, 0)
        };
        assert!(tick(&mut s, base), "first eligible hit fires");
        assert!(!tick(&mut s, TickInput { now_ns: 1_100 * MS, ..base }), "inside 300 ms cooldown");
        assert!(tick(&mut s, TickInput { now_ns: 1_400 * MS, ..base }), "cooldown elapsed");

        // Without a target the reference returns before considering CounterHit.
        assert!(!tick(&mut s, TickInput { has_target: false, ..base }));
        // A physically held mouse suppresses it too.
        assert!(!tick(
            &mut s,
            TickInput { now_ns: 2_000 * MS, left_mouse_down: true, ..base }
        ));
    }

    #[test]
    fn counter_fires_on_a_zero_clock_without_a_prior_window() {
        // The -1 sentinel: with a 0 sentinel the first eligible CounterHit was
        // swallowed for `counter_cd_ms` after the clock origin.
        let mut s = state();
        let base = TickInput {
            counter_hit: true,
            player_hurt_time: 1,
            ..tick_input(0, 0)
        };
        assert!(tick(&mut s, base), "no prior counter, so the cooldown must not block it");
    }

    #[test]
    fn the_two_ops_replay_over_one_shared_state() {
        // A sanity check that a port cannot get away with giving each op its
        // own state: the gate must see the window the tick opened.
        let mut s = state();
        let mut rng = Pcg32::new(11);
        let mut roll = || rng.random_int(0, 99);
        tick(&mut s, tick_input(1_000 * MS, 0));
        let wait = GateInput {
            now_ns: 1_050 * MS,
            wait_first_ms: 500,
            ..gate_input(1_050 * MS)
        };
        assert!(!gate(&s, wait, &mut roll));
        tick(&mut s, tick_input(1_100 * MS, 5));
        let burst = GateInput {
            now_ns: 1_150 * MS,
            wait_first_ms: 500,
            pause_ms: 500,
            ..gate_input(1_150 * MS)
        };
        assert!(!gate(&s, burst, &mut roll));
    }
}
