//! SprintReset — the packet sprint-reset timing machine.
//!
//! Only judgements and platform I/O stay in the JVM: `Strategies.gate`, the
//! distance window, `hurtTime > 0`, the target-identity comparison and the
//! `sendSprintReset` write. Everything that has to be remembered between ticks
//! (the rising-edge bit, the hit-count throttle, the target id, and the delayed
//! send) and the probability draw live here, on their own RNG stream, so a
//! replay of the same input sequence is stable.
//!
//! The machine is `(state, input) -> (step, state)`: no I/O, no clock. The roll
//! closure is called **only** at the fire decision point, and only when
//! `chance < 100` — a port that draws on an idle tick, or before the gates,
//! advances the stream and diverges from the Kotlin oracle on the first replay.

/// What the JVM keeps about the reset in flight.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct State {
    /// The target's hurt bit on the previous tick (rising-edge memory).
    pub prev_hurt: bool,
    /// Target id last seen, or -1 when there is no target.
    pub last_target_id: i32,
    /// Fresh-hit throttle counter.
    pub hit_counter: i32,
    /// A delayed send is armed and waiting for its deadline.
    pub sending: bool,
    /// Nanosecond deadline at which the armed send fires.
    pub send_at_ns: i64,
    /// Mode captured when the send was armed (0 Nostop, 1 Silent).
    pub pending_mode: i32,
}

impl Default for State {
    fn default() -> State {
        State {
            prev_hurt: false,
            last_target_id: -1,
            hit_counter: 0,
            sending: false,
            send_at_ns: 0,
            pending_mode: 0,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// What the module should do this tick. The numeric code is the wire value:
/// `0` = none, `1 + mode` = send with that mode (0 Nostop, 1 Silent).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    None,
    Send(i32),
}

impl Step {
    pub fn code(self) -> u8 {
        match self {
            Step::None => 0,
            Step::Send(mode) => (1 + mode) as u8,
        }
    }

    pub fn from_code(c: u8) -> Option<Step> {
        match c {
            0 => Some(Step::None),
            1 => Some(Step::Send(0)),
            2 => Some(Step::Send(1)),
            _ => None,
        }
    }
}

/// The judgements the module made this tick, plus the tuning knobs.
#[derive(Clone, Copy, Debug)]
pub struct Input {
    /// Caller's monotonic clock. Only differences matter across the boundary.
    pub now_ns: i64,
    /// A crosshair target exists.
    pub has_target: bool,
    /// `rangeMin <= distance <= rangeMax` — a judgement the JVM keeps.
    pub in_range: bool,
    /// `Strategies.gate(...)` result — a judgement the JVM keeps.
    pub gate_ok: bool,
    /// `target.hurtTime > 0` — a judgement the JVM keeps.
    pub is_hurt: bool,
    /// The target's entity id, for the per-target throttle reset.
    pub entity_id: i32,
    /// Fire probability, 0..100. 100 disables the roll entirely.
    pub chance: i32,
    /// Fire once every `hit_count` fresh hits.
    pub hit_count: i32,
    /// Delay the send by this many milliseconds (0 = immediate).
    pub delay_ms: i32,
    /// Mode code: 0 Nostop, 1 Silent.
    pub mode: i32,
}

/// One tick of the reset machine.
pub fn tick(state: State, input: Input, roll: &mut impl FnMut() -> i32) -> (Step, State) {
    let mut s = state;

    // A send armed on an earlier tick takes priority: the original module
    // short-circuits the whole tick while `sending` is true, so no fresh hit
    // is even evaluated until the deadline has passed.
    if s.sending {
        if input.now_ns >= s.send_at_ns {
            s.sending = false;
            return (Step::Send(s.pending_mode), s);
        }
        return (Step::None, s);
    }

    // No target: forget the edge so a new target's first hit is caught. The
    // hit counter is deliberately NOT reset here (only on a target switch).
    if !input.has_target {
        s.prev_hurt = false;
        s.last_target_id = -1;
        return (Step::None, s);
    }

    // Switching targets restarts the hit-count cycle and the edge.
    if s.last_target_id != input.entity_id {
        s.last_target_id = input.entity_id;
        s.prev_hurt = false;
        s.hit_counter = 0;
    }

    // Range / gate failures return early WITHOUT updating the edge — this is
    // the original ordering and the parity fixture pins it down.
    if !input.in_range || !input.gate_ok {
        return (Step::None, s);
    }

    let just_hit = input.is_hurt && !s.prev_hurt;
    s.prev_hurt = input.is_hurt;
    if !just_hit {
        return (Step::None, s);
    }

    s.hit_counter += 1;
    if s.hit_counter < input.hit_count {
        return (Step::None, s);
    }
    s.hit_counter = 0;

    // The only draw. Kotlin's `Random.nextInt(100)` is [0, 100); a roll >=
    // chance rejects when chance < 100. chance == 100 never draws.
    if input.chance < 100 && roll() >= input.chance {
        return (Step::None, s);
    }

    if input.delay_ms > 0 {
        s.sending = true;
        s.send_at_ns = input.now_ns + input.delay_ms as i64 * 1_000_000;
        s.pending_mode = input.mode;
        return (Step::None, s);
    }

    (Step::Send(input.mode), s)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(now_ns: i64) -> Input {
        Input {
            now_ns,
            has_target: true,
            in_range: true,
            gate_ok: true,
            is_hurt: true,
            entity_id: 7,
            chance: 100,
            hit_count: 1,
            delay_ms: 0,
            mode: 0,
        }
    }

    #[test]
    fn no_target_forgets_the_edge_but_keeps_the_hit_counter() {
        let st = State {
            prev_hurt: true,
            last_target_id: 7,
            hit_counter: 2,
            ..State::default()
        };
        let (step, next) = tick(
            st,
            Input {
                has_target: false,
                ..input(0)
            },
            &mut || 0,
        );
        assert_eq!(step, Step::None);
        assert!(!next.prev_hurt);
        assert_eq!(next.last_target_id, -1);
        assert_eq!(next.hit_counter, 2, "hit counter survives a null target");
    }

    #[test]
    fn switching_targets_restarts_the_throttle_and_edge() {
        let st = State {
            prev_hurt: true,
            last_target_id: 7,
            hit_counter: 3,
            ..State::default()
        };
        // A non-hurt tick on a new target: the switch clears the edge and the
        // throttle, and no rising edge is counted this tick.
        let (step, next) = tick(
            st,
            Input {
                entity_id: 9,
                is_hurt: false,
                ..input(0)
            },
            &mut || 0,
        );
        assert_eq!(step, Step::None);
        assert_eq!(next.last_target_id, 9);
        assert!(!next.prev_hurt, "the edge is reset on a target switch");
        assert_eq!(next.hit_counter, 0, "the throttle restarts from zero");
    }

    #[test]
    fn a_range_failure_does_not_consume_the_edge() {
        let (step, next) = tick(
            State::default(),
            Input {
                in_range: false,
                ..input(0)
            },
            &mut || 0,
        );
        assert_eq!(step, Step::None);
        assert!(!next.prev_hurt, "edge untouched when the gate short-circuits");
    }

    #[test]
    fn only_the_rising_edge_counts_as_a_hit() {
        let mut s = State::default();
        let (first, s1) = tick(s, input(0), &mut || 0);
        assert_eq!(first, Step::Send(0));
        s = s1;
        // hurt stays high: no new rising edge.
        let (second, _) = tick(s, input(50_000_000), &mut || 0);
        assert_eq!(second, Step::None);
    }

    #[test]
    fn hit_count_throttles_and_only_the_firing_hit_draws() {
        let mut draws = 0;
        let mut s = State::default();
        let cfg = Input {
            hit_count: 3,
            chance: 40,
            ..input(0)
        };
        // Each rising edge needs a hurt-low tick in between. Two of them are
        // swallowed by the hit-count throttle; neither reaches the roll.
        for _ in 0..2 {
            let (step, next) = tick(
                s,
                Input {
                    is_hurt: false,
                    ..cfg
                },
                &mut || {
                    draws += 1;
                    0
                },
            );
            assert_eq!(step, Step::None);
            s = next;
            let (step, next) = tick(s, cfg, &mut || {
                draws += 1;
                0
            });
            assert_eq!(step, Step::None);
            s = next;
        }
        assert_eq!(s.hit_counter, 2);
        assert_eq!(draws, 0, "a throttled hit never advances the stream");

        // The third rising edge reaches the fire point: one draw, rejected.
        let (step, next) = tick(
            s,
            Input {
                is_hurt: false,
                ..cfg
            },
            &mut || {
                draws += 1;
                0
            },
        );
        assert_eq!(step, Step::None);
        s = next;
        let (step, _) = tick(s, cfg, &mut || {
            draws += 1;
            70 // >= chance 40 -> reject
        });
        assert_eq!(step, Step::None);
        assert_eq!(draws, 1, "exactly one draw, at the fire point");
    }

    #[test]
    fn the_chance_roll_rejects_without_firing() {
        let mut draws = 0;
        let (step, _) = tick(
            State::default(),
            Input {
                chance: 40,
                ..input(0)
            },
            &mut || {
                draws += 1;
                70 // >= 40 -> reject
            },
        );
        assert_eq!(step, Step::None);
        assert_eq!(draws, 1, "exactly one draw at the fire point");
    }

    #[test]
    fn an_idle_tick_never_draws() {
        let mut draws = 0;
        let (step, _) = tick(
            State::default(),
            Input {
                gate_ok: false,
                chance: 40,
                ..input(0)
            },
            &mut || {
                draws += 1;
                0
            },
        );
        assert_eq!(step, Step::None);
        assert_eq!(draws, 0, "a gate failure must not advance the stream");
    }

    #[test]
    fn a_delayed_send_arms_then_fires_at_the_deadline() {
        let cfg = Input {
            delay_ms: 100,
            mode: 1,
            ..input(1_000_000_000)
        };
        let (armed, s) = tick(State::default(), cfg, &mut || 0);
        assert_eq!(armed, Step::None);
        assert!(s.sending);
        assert_eq!(s.send_at_ns, 1_000_000_000 + 100 * 1_000_000);
        assert_eq!(s.pending_mode, 1);

        // Before the deadline: still nothing, and a fresh hit is ignored.
        let (before, s2) = tick(s, Input { now_ns: 1_050_000_000, ..cfg }, &mut || 0);
        assert_eq!(before, Step::None);
        assert!(s2.sending);

        // At the deadline the captured mode fires.
        let (fired, s3) = tick(s2, Input { now_ns: 1_100_000_000, ..cfg }, &mut || 0);
        assert_eq!(fired, Step::Send(1));
        assert!(!s3.sending);
    }

    #[test]
    fn step_codes_round_trip() {
        for s in [Step::None, Step::Send(0), Step::Send(1)] {
            assert_eq!(Step::from_code(s.code()), Some(s));
        }
        assert_eq!(Step::from_code(9), None);
    }
}
