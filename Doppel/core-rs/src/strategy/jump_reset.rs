//! JumpReset — knockback-triggered jump timing, split across two call sites.
//!
//! The JVM keeps only judgements and platform I/O: the internal conditions
//! (sprinting / on ground / not in fluid / knockback came from the front),
//! the unified `gate`, the `sendSprint`-free `queueJump` write, and the final
//! "still sprinting at jump time" check. Everything stateful lives here:
//! the cooldown counter (two modes), the pending delayed jump and its
//! deadline, and the probability roll — on JumpReset's own RNG stream.
//!
//! Two entry points share one [`State`], mirroring the two JVM threads:
//!  * [`knock`] runs on the velocity (Netty) thread when an S12/S27 arrives;
//!  * [`pump`] runs once per client tick to advance the Ticks cooldown and
//!    fire a delayed jump at its deadline.
//!
//! The roll closure is called **only** in `knock`, after the gates and only
//! when `chance < 100`. `pump` never draws — a port that rolls on a tick would
//! advance the stream with frame timing and diverge on the first replay.

/// What the core remembers between a knockback and the jump it may cause.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct State {
    /// Ticks mode: ticks since the last jump (capped at `ticks_until`).
    /// ReceivedHits mode: fresh knockbacks since the last jump.
    pub cooldown_counter: i32,
    /// A jump is armed and waiting for its delay deadline.
    pub pending: bool,
    /// Nanosecond deadline at which the armed jump fires.
    pub deadline_ns: i64,
}

impl Default for State {
    fn default() -> State {
        State {
            cooldown_counter: 0,
            pending: false,
            deadline_ns: 0,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// What the module should do. The numeric code is the wire value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    /// Do nothing (gates closed, chance rejected, cooldown not met, or the
    /// jump was armed for a later tick).
    None,
    /// Execute the jump now.
    Jump,
}

impl Step {
    pub fn code(self) -> u8 {
        match self {
            Step::None => 0,
            Step::Jump => 1,
        }
    }

    pub fn from_code(c: u8) -> Option<Step> {
        match c {
            0 => Some(Step::None),
            1 => Some(Step::Jump),
            _ => None,
        }
    }
}

/// 0 = Ticks (cooldown advances per tick), 1 = ReceivedHits (per knockback).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CooldownMode {
    Ticks,
    ReceivedHits,
}

impl CooldownMode {
    pub fn from_code(c: i32) -> CooldownMode {
        if c == 1 {
            CooldownMode::ReceivedHits
        } else {
            CooldownMode::Ticks
        }
    }
}

/// The judgements and knobs the velocity thread marshals into one request.
#[derive(Clone, Copy, Debug)]
pub struct KnockInput {
    /// All internal conditions + the unified gate, combined into one bool.
    pub gates_open: bool,
    pub now_ns: i64,
    /// Fire probability, 0..100. 100 disables the roll entirely.
    pub chance: i32,
    pub cooldown_mode: CooldownMode,
    /// Ticks mode: wait this many ticks between jumps.
    pub ticks_until: i32,
    /// ReceivedHits mode: jump after this many knockbacks.
    pub hits_until: i32,
    /// Delay the jump by this many ms (0 = immediate).
    pub delay_ms: i32,
}

/// A knockback arrived. Decide whether to jump now, arm a delayed jump, or
/// do nothing. Returns the (step, new state); the caller turns a `Jump` into
/// `queueJump` after its own "still sprinting" judgement.
pub fn knock(
    state: State,
    input: KnockInput,
    roll: &mut impl FnMut() -> i32,
) -> (Step, State) {
    let mut s = state;

    // Gates closed: no roll, no cooldown change. This preserves the ordering
    // of the original onKnockback, which returns before touching any state.
    if !input.gates_open {
        return (Step::None, s);
    }

    // The only draw. Kotlin's `Random.nextInt(100)` is [0, 100); a roll >=
    // chance rejects when chance < 100. chance == 100 never draws.
    if input.chance < 100 && roll() >= input.chance {
        return (Step::None, s);
    }

    match input.cooldown_mode {
        CooldownMode::Ticks => {
            if s.cooldown_counter < input.ticks_until {
                return (Step::None, s);
            }
        }
        CooldownMode::ReceivedHits => {
            s.cooldown_counter += 1;
            if s.cooldown_counter < input.hits_until {
                return (Step::None, s);
            }
        }
    }

    // Firing: reset the cooldown (the original zeroes it here and again on
    // executeJump), then either schedule or emit immediately.
    s.cooldown_counter = 0;

    if input.delay_ms > 0 {
        s.pending = true;
        s.deadline_ns = input.now_ns + input.delay_ms as i64 * 1_000_000;
        (Step::None, s)
    } else {
        (Step::Jump, s)
    }
}

/// Per-tick inputs. No roll, no gates — this half only advances the clock.
#[derive(Clone, Copy, Debug)]
pub struct PumpInput {
    pub now_ns: i64,
    pub cooldown_mode: CooldownMode,
    pub ticks_until: i32,
}

/// One client tick: advance the Ticks cooldown, then fire a delayed jump that
/// reached its deadline. The two are independent in the original tick listener
/// (two separate `if`s), so both happen even on the tick that fires.
pub fn pump(state: State, input: PumpInput) -> (Step, State) {
    let mut s = state;

    if input.cooldown_mode == CooldownMode::Ticks && s.cooldown_counter < input.ticks_until {
        s.cooldown_counter += 1;
    }

    if s.pending {
        if input.now_ns >= s.deadline_ns {
            s.pending = false;
            return (Step::Jump, s);
        }
    }

    (Step::None, s)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn knock_input() -> KnockInput {
        KnockInput {
            gates_open: true,
            now_ns: 1_000_000_000,
            chance: 100,
            cooldown_mode: CooldownMode::Ticks,
            ticks_until: 4,
            hits_until: 2,
            delay_ms: 0,
        }
    }

    fn ticks_mode(ticks_until: i32) -> (KnockInput, PumpInput) {
        let k = KnockInput {
            cooldown_mode: CooldownMode::Ticks,
            ticks_until,
            ..knock_input()
        };
        let p = PumpInput {
            now_ns: 1_050_000_000,
            cooldown_mode: CooldownMode::Ticks,
            ticks_until,
        };
        (k, p)
    }

    #[test]
    fn closed_gates_never_roll_or_change_state() {
        let mut draws = 0;
        let (step, next) = tick_knock(
            State::new(),
            KnockInput {
                gates_open: false,
                chance: 40,
                ..knock_input()
            },
            &mut || {
                draws += 1;
                0
            },
        );
        assert_eq!(step, Step::None);
        assert_eq!(next, State::new());
        assert_eq!(draws, 0);
    }

    #[test]
    fn chance_roll_rejects_without_firing() {
        let mut draws = 0;
        let (step, _) = tick_knock(
            State::new(),
            KnockInput {
                chance: 40,
                ..knock_input()
            },
            &mut || {
                draws += 1;
                70
            },
        );
        assert_eq!(step, Step::None);
        assert_eq!(draws, 1, "exactly one draw at the fire point");
    }

    #[test]
    fn chance_100_never_draws_even_when_the_knock_fires() {
        let mut draws = 0;
        // ReceivedHits with hits_until=1 reaches the fire point immediately.
        let k = KnockInput {
            cooldown_mode: CooldownMode::ReceivedHits,
            hits_until: 1,
            ..knock_input()
        };
        let (step, _) = tick_knock(State::new(), k, &mut || {
            draws += 1;
            0
        });
        assert_eq!(step, Step::Jump);
        assert_eq!(draws, 0, "chance 100 never touches the stream");
    }

    #[test]
    fn ticks_cooldown_blocks_until_enough_ticks_elapsed() {
        let (k, mut p) = ticks_mode(4);
        // Fresh state: counter 0 < 4 -> blocked.
        let mut s = State::new();
        let (first, next) = tick_knock(s, k, &mut || 0);
        assert_eq!(first, Step::None);
        s = next;

        // Four pump ticks bring the counter to 4.
        for i in 0..4 {
            p.now_ns = 1_000_000_000 + (i as i64 + 1) * 50_000_000;
            let (_, next) = tick_pump(s, p);
            s = next;
        }
        assert_eq!(s.cooldown_counter, 4);

        // Now the knock fires.
        let (fired, next) = tick_knock(s, k, &mut || 0);
        assert_eq!(fired, Step::Jump);
        assert_eq!(next.cooldown_counter, 0);
    }

    #[test]
    fn received_hits_fires_every_nth_knockback() {
        let k = KnockInput {
            cooldown_mode: CooldownMode::ReceivedHits,
            hits_until: 3,
            ..knock_input()
        };
        let mut s = State::new();
        let (a, next) = tick_knock(s, k, &mut || 0);
        assert_eq!(a, Step::None);
        assert_eq!(next.cooldown_counter, 1);
        s = next;
        let (b, next) = tick_knock(s, k, &mut || 0);
        assert_eq!(b, Step::None);
        assert_eq!(next.cooldown_counter, 2);
        s = next;
        let (c, next) = tick_knock(s, k, &mut || 0);
        assert_eq!(c, Step::Jump);
        assert_eq!(next.cooldown_counter, 0);
    }

    #[test]
    fn pump_never_advances_the_ticks_cooldown_in_received_hits_mode() {
        let p = PumpInput {
            now_ns: 1_000_000_000,
            cooldown_mode: CooldownMode::ReceivedHits,
            ticks_until: 4,
        };
        let s = State {
            cooldown_counter: 2,
            ..State::new()
        };
        let (_, next) = tick_pump(s, p);
        assert_eq!(next.cooldown_counter, 2);
    }

    #[test]
    fn a_delayed_jump_arms_on_knock_and_fires_at_the_deadline_on_pump() {
        let k = KnockInput {
            cooldown_mode: CooldownMode::ReceivedHits,
            hits_until: 1,
            delay_ms: 100,
            now_ns: 1_000_000_000,
            ..knock_input()
        };
        let (armed, s) = tick_knock(State::new(), k, &mut || 0);
        assert_eq!(armed, Step::None);
        assert!(s.pending);
        assert_eq!(s.deadline_ns, 1_000_000_000 + 100 * 1_000_000);

        let p = |now| PumpInput {
            now_ns: now,
            cooldown_mode: CooldownMode::Ticks,
            ticks_until: 4,
        };
        let (before, s2) = tick_pump(s, p(1_050_000_000));
        assert_eq!(before, Step::None);
        assert!(s2.pending);

        let (fired, s3) = tick_pump(s2, p(1_100_000_000));
        assert_eq!(fired, Step::Jump);
        assert!(!s3.pending);
    }

    #[test]
    fn pump_fires_and_advances_cooldown_on_the_same_tick() {
        let s = State {
            cooldown_counter: 1,
            pending: true,
            deadline_ns: 1_000_000_000,
        };
        let p = PumpInput {
            now_ns: 1_000_000_000,
            cooldown_mode: CooldownMode::Ticks,
            ticks_until: 4,
        };
        let (step, next) = tick_pump(s, p);
        assert_eq!(step, Step::Jump);
        assert_eq!(next.cooldown_counter, 2, "cooldown still advances on the firing tick");
        assert!(!next.pending);
    }

    #[test]
    fn step_codes_round_trip() {
        assert_eq!(Step::from_code(Step::None.code()), Some(Step::None));
        assert_eq!(Step::from_code(Step::Jump.code()), Some(Step::Jump));
        assert_eq!(Step::from_code(7), None);
    }

    // Thin wrappers so the tests read as one-liners.
    fn tick_knock(s: State, i: KnockInput, roll: &mut impl FnMut() -> i32) -> (Step, State) {
        knock(s, i, roll)
    }
    fn tick_pump(s: State, i: PumpInput) -> (Step, State) {
        pump(s, i)
    }
}
