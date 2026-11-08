//! Eagle AutoPlace — the aim-first placement timing machine.
//!
//! Only judgements and platform I/O stay in the JVM: `shouldAutoPlace` and the
//! pitch-tolerance comparison are pure predicates the module evaluates, and
//! `syntheticUse` / `setPlayerRotation` are platform side effects. Everything
//! that has to be remembered between ticks (an aim in flight, the ticks spent
//! waiting, the placement cooldown) and the randomised cooldown draw live here,
//! on their own RNG stream, so a replay of the same input sequence is stable.
//!
//! The machine is `(state, input) -> (step, state)`: no I/O, no clock. The roll
//! closure is called **only** on a CLICK — the placement that actually armed a
//! cooldown. A port that draws while cooling down or aiming advances the stream
//! on idle ticks and diverges from the Kotlin oracle on the first replay.

/// What the JVM keeps about the placement in flight.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct State {
    /// An aim is in flight, waiting for the view to reach the bridging angle.
    pub aiming: bool,
    /// Ticks spent waiting for that aim to arrive.
    pub aim_ticks: i32,
    /// Ticks left before the next placement is allowed.
    pub cooldown_ticks: i32,
}

/// What the module should do this tick. The numeric code is the wire value.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Step {
    /// Gates closed, or cooling down: do nothing, keep the right button up.
    #[default]
    None,
    /// Ask for the bridging angle; keep waiting for the view to arrive.
    Aim,
    /// The view is at the angle: emit one right-click and arm a fresh cooldown.
    Click,
    /// The aim never arrived (or BridgeAssist owns the view): give up, retry soon.
    Abort,
}

impl Step {
    pub fn code(self) -> u8 {
        match self {
            Step::None => 0,
            Step::Aim => 1,
            Step::Click => 2,
            Step::Abort => 3,
        }
    }

    pub fn from_code(c: u8) -> Option<Step> {
        match c {
            0 => Some(Step::None),
            1 => Some(Step::Aim),
            2 => Some(Step::Click),
            3 => Some(Step::Abort),
            _ => None,
        }
    }
}

/// The judgements the module made this tick, plus the tuning knobs.
#[derive(Clone, Copy, Debug, Default)]
pub struct Input {
    /// `shouldAutoPlace(enabled, autoPlace, onEdge, sneaking)`.
    pub gates_open: bool,
    /// `abs(currentPitch - targetPitch) <= tolerance` — a geometry comparison
    /// the JVM keeps (Geometry is an explicit whitelist, see PROTECTION 9.4).
    pub pitch_in_tolerance: bool,
    /// BridgeAssist already owns the view, so AutoPlace must not fight for it.
    pub bridge_assist_enabled: bool,
    /// Give up if the aim has not arrived in this many ticks.
    pub max_wait_ticks: i32,
    /// Cooldown roll bounds, inclusive on both ends.
    pub cooldown_min: i32,
    pub cooldown_max: i32,
}

/// One tick of the placement machine.
pub fn tick(state: State, input: Input, roll: &mut impl FnMut() -> i32) -> (Step, State) {
    // A closed gate cancels a pending aim: never click after the reason to
    // click is gone. Forget the cooldown too — the next opening starts fresh.
    if !input.gates_open {
        return (Step::None, State::default());
    }

    // Cooling down from the last placement, and not mid-aim: spend one tick.
    // No roll here — the delay was drawn on the CLICK that armed it.
    if !state.aiming && state.cooldown_ticks > 0 {
        let mut next = state;
        next.cooldown_ticks -= 1;
        return (Step::None, next);
    }

    // `placeDecision`: CLICK wins when the view is already at the angle (a
    // BridgeAssist-held angle counts — the two modules cooperate), otherwise
    // an aim that timed out or that BridgeAssist would fight is abandoned.
    let step = if input.pitch_in_tolerance {
        Step::Click
    } else if state.aim_ticks >= input.max_wait_ticks || input.bridge_assist_enabled {
        Step::Abort
    } else {
        Step::Aim
    };

    let next = match step {
        Step::Aim => State {
            aiming: true,
            aim_ticks: state.aim_ticks + 1,
            cooldown_ticks: 0,
        },
        Step::Click => State {
            aiming: false,
            aim_ticks: 0,
            // The only draw. Kotlin's `Random.nextInt(min, max + 1)` is the
            // inclusive `[min, max]`, which is what `random_int(lo, hi)` gives.
            cooldown_ticks: roll().max(0),
        },
        Step::Abort => State {
            aiming: false,
            aim_ticks: 0,
            // Retry soon, through the same aim-first path: never a click
            // without the aim. One tick keeps a bridging rhythm.
            cooldown_ticks: 1,
        },
        Step::None => State::default(),
    };

    (step, next)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(gates: bool, pitch: bool, bridge: bool) -> Input {
        Input {
            gates_open: gates,
            pitch_in_tolerance: pitch,
            bridge_assist_enabled: bridge,
            max_wait_ticks: 3,
            cooldown_min: 1,
            cooldown_max: 3,
        }
    }

    fn state(aiming: bool, aim_ticks: i32, cooldown: i32) -> State {
        State {
            aiming,
            aim_ticks,
            cooldown_ticks: cooldown,
        }
    }

    #[test]
    fn a_closed_gate_cancels_a_pending_aim_and_forgets_the_cooldown() {
        let mut n = 0;
        let (s, next) = tick(
            state(true, 2, 5),
            input(false, false, false),
            &mut || {
                n += 1;
                9
            },
        );
        assert_eq!(s, Step::None);
        assert_eq!(next, State::default());
        assert_eq!(n, 0);
    }

    #[test]
    fn a_view_already_at_the_angle_clicks_and_draws_exactly_once() {
        let mut n = 0;
        let (s, next) = tick(State::default(), input(true, true, false), &mut || {
            n += 1;
            3
        });
        assert_eq!(s, Step::Click);
        assert_eq!(next.cooldown_ticks, 3);
        assert!(!next.aiming);
        assert_eq!(n, 1, "the cooldown is rolled once, on the click");
    }

    #[test]
    fn bridge_assist_holding_the_angle_still_cooperates() {
        let (s, _) = tick(State::default(), input(true, true, true), &mut || 1);
        assert_eq!(s, Step::Click, "pitch check wins over the stand-down gate");
    }

    #[test]
    fn an_aim_counts_one_tick_at_a_time() {
        let (s1, n1) = tick(State::default(), input(true, false, false), &mut || 0);
        assert_eq!(s1, Step::Aim);
        assert_eq!(n1.aim_ticks, 1);
        assert!(n1.aiming);

        let (s2, n2) = tick(n1, input(true, false, false), &mut || 0);
        assert_eq!(s2, Step::Aim);
        assert_eq!(n2.aim_ticks, 2);
    }

    #[test]
    fn an_aim_that_never_arrives_is_abandoned_without_clicking() {
        let (s, next) = tick(state(true, 3, 0), input(true, false, false), &mut || 0);
        assert_eq!(s, Step::Abort);
        assert_eq!(next.cooldown_ticks, 1);
        assert!(!next.aiming);
        assert_eq!(next.aim_ticks, 0);
    }

    #[test]
    fn bridge_assist_owns_the_view_so_the_aim_stands_down() {
        let (s, _) = tick(State::default(), input(true, false, true), &mut || 0);
        assert_eq!(s, Step::Abort);
    }

    #[test]
    fn cooling_down_spends_one_tick_without_drawing() {
        let mut n = 0;
        let (s, next) = tick(state(false, 0, 2), input(true, false, false), &mut || {
            n += 1;
            7
        });
        assert_eq!(s, Step::None);
        assert_eq!(next.cooldown_ticks, 1);
        assert_eq!(n, 0, "an idle cooldown tick must not advance the stream");
    }

    #[test]
    fn a_negative_roll_is_clamped() {
        let (_, next) = tick(State::default(), input(true, true, false), &mut || -5);
        assert_eq!(next.cooldown_ticks, 0);
    }

    #[test]
    fn step_codes_round_trip() {
        for s in [Step::None, Step::Aim, Step::Click, Step::Abort] {
            assert_eq!(Step::from_code(s.code()), Some(s));
        }
        assert_eq!(Step::from_code(9), None);
    }

    #[test]
    fn a_full_cycle_keeps_the_roll_order_stable() {
        // click → cooldown → aim → click: two clicks must consume two rolls.
        let mut draws = vec![3, 2].into_iter();
        let mut st = State::default();
        let mut steps = Vec::new();
        let seq = [
            input(true, true, false),  // click
            input(true, false, false), // cooldown 2
            input(true, false, false), // cooldown 1
            input(true, false, false), // cooldown 0
            input(true, false, false), // aim
            input(true, true, false),  // click
        ];
        for i in seq {
            let (s, next) = tick(st, i, &mut || draws.next().unwrap());
            st = next;
            steps.push(s);
        }
        assert_eq!(
            steps,
            vec![
                Step::Click,
                Step::None,
                Step::None,
                Step::None,
                Step::Aim,
                Step::Click
            ]
        );
        assert_eq!(st.cooldown_ticks, 2);
        assert_eq!(draws.next(), None, "exactly two draws for two clicks");
    }
}
