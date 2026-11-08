//! SuperKnockback — sprint manipulation on a landed hit, with the whole state
//! machine in the core.
//!
//! The JVM keeps only judgements and platform I/O: the range window, the
//! unified gate, the trigger style (a one-tick attack pulse or an exact
//! `hurtTime` match), `player.isSprinting && serverSprintState`, the
//! `isMovingForward` check inside the force-sprint restore, and the C0B /
//! sprint-claim writes. Everything stateful lives here: the phase machine
//! (Idle / Delay / SprintTap), the delay deadline, the coordination offset,
//! the SprintTap three-tick force sequence, the per-target hit-count throttle
//! and the probability roll — on SuperKnockback's own RNG stream.
//!
//! One tick can produce **two** independent actions: the SprintTap force
//! machine advances at the top of the tick, and the trigger evaluation may
//! fire later in the same tick. Both are platform writes, so neither can be
//! dropped; [`pack`] puts both step codes into the single scalar answer.
//!
//! The roll closure is called **only** at the fire point and only when
//! `chance < 100`. Idle / throttled / gated / delayed / coordinating ticks
//! never advance the stream.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Idle,
    Delay,
    SprintTap,
}

/// What the core remembers between the hit and the sprint burst it may cause.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct State {
    pub phase: Phase,
    /// Deadline at which a delayed action starts.
    pub delay_end_ns: i64,
    /// One-tick offset when coordinated with SprintReset.
    pub coord_offset_ticks: i32,
    /// SprintTap force-sprint countdown (2 -> 1 -> 0).
    pub sprint_ticks: i32,
    /// Hits seen for the current target since the last fire.
    pub hit_counter: i32,
    /// The target whose throttle is being counted.
    pub last_target_id: i32,
}

impl Default for State {
    fn default() -> State {
        State {
            phase: Phase::Idle,
            delay_end_ns: 0,
            coord_offset_ticks: 0,
            sprint_ticks: 0,
            hit_counter: 0,
            last_target_id: -1,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// Wire mode codes. The module maps its `choices("Mode", ...)` to these.
pub const MODE_SPRINT_TAP: i32 = 0;
pub const MODE_OLD: i32 = 1;
pub const MODE_SNEAK_PACKET: i32 = 2;

/// What the module should do. The numeric code is the wire value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    /// No platform write.
    None,
    /// SprintTap step 1: break sprint (`ownSprint(false)`).
    SprintTapBreak,
    /// SprintTap step 2: restore sprint if still moving forward.
    SprintTapRestore,
    /// SprintTap step 3: release the OWNED sprint claim.
    SprintTapEnd,
    /// A fire in SprintTap mode: flash the HUD; the core already armed the tap.
    FireSprintTap,
    /// A fire in Old mode: the STOP/START/STOP/START burst + force sprint on.
    FireOld,
    /// A fire in SneakPacket mode: the four interleaved C0B packets.
    FireSneak,
}

impl Step {
    pub fn code(self) -> u8 {
        match self {
            Step::None => 0,
            Step::SprintTapBreak => 1,
            Step::SprintTapRestore => 2,
            Step::SprintTapEnd => 3,
            Step::FireSprintTap => 4,
            Step::FireOld => 5,
            Step::FireSneak => 6,
        }
    }

    pub fn from_code(c: u8) -> Option<Step> {
        Some(match c {
            0 => Step::None,
            1 => Step::SprintTapBreak,
            2 => Step::SprintTapRestore,
            3 => Step::SprintTapEnd,
            4 => Step::FireSprintTap,
            5 => Step::FireOld,
            6 => Step::FireSneak,
            _ => return None,
        })
    }
}

/// The SprintTap machine advances at the top of the tick; a fire may follow
/// later in the same tick. Pack both into the one scalar the wire carries.
pub fn pack(sprint: Step, fire: Step) -> i32 {
    (sprint.code() as i32) | ((fire.code() as i32) << 4)
}

pub fn unpack_sprint(code: i32) -> u8 {
    (code & 0xF) as u8
}

pub fn unpack_fire(code: i32) -> u8 {
    ((code >> 4) & 0xF) as u8
}

/// The judgements and knobs the JVM marshals into one request per tick.
#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub now_ns: i64,
    /// `EventBridge.crosshairTarget` was present this tick.
    pub has_target: bool,
    /// RisingEdge pulse or an exact hurtTime match, evaluated on the JVM.
    pub trigger_ok: bool,
    /// Distance window judgement.
    pub in_range: bool,
    /// Unified gate judgement (`combatDecide`, mode < 0).
    pub gate_ok: bool,
    pub entity_id: i32,
    /// Fire probability, 0..100. 100 disables the roll entirely.
    pub chance: i32,
    /// Fire once every N fresh hits.
    pub hit_count: i32,
    /// Delay the action by this many ms (0 = immediate).
    pub delay_ms: i32,
    pub mode: i32,
    /// `player.isSprinting && EventBridge.serverSprintState`.
    pub sprint_tap_allowed: bool,
    /// `EventBridge.isSprintCoordinationActive()`.
    pub coordination_active: bool,
}

/// One client tick. Returns a packed step code and the new state.
pub fn tick(state: State, input: Input, roll: &mut impl FnMut() -> i32) -> (i32, State) {
    let mut s = state;
    let mut sprint_step = Step::None;

    // ---- SprintTap force-sprint machine (top of the tick) ----
    // It does not short-circuit: the trigger evaluation below still runs on
    // the same tick, exactly as the original onTick did.
    if s.phase == Phase::SprintTap && input.mode == MODE_SPRINT_TAP {
        sprint_step = match s.sprint_ticks {
            2 => {
                s.sprint_ticks -= 1;
                Step::SprintTapBreak
            }
            1 => {
                s.sprint_ticks -= 1;
                Step::SprintTapRestore
            }
            _ => {
                s.phase = Phase::Idle;
                Step::SprintTapEnd
            }
        };
    }

    // ---- Delay timer -> start action, then short-circuit ----
    if s.phase == Phase::Delay {
        let fire_step = if input.now_ns >= s.delay_end_ns {
            s.phase = Phase::Idle;
            start_action(&mut s, input)
        } else {
            Step::None
        };
        return (pack(sprint_step, fire_step), s);
    }

    // ---- Coordination offset: fire the deferred action from the last hit ----
    if s.coord_offset_ticks > 0 {
        s.coord_offset_ticks -= 1;
        let fire_step = if s.coord_offset_ticks == 0 {
            fire_trigger(&mut s, input)
        } else {
            Step::None
        };
        return (pack(sprint_step, fire_step), s);
    }

    // ---- Trigger evaluation ----
    if !input.has_target {
        return (pack(sprint_step, Step::None), s);
    }

    // Per-target throttle: switching targets restarts the cycle.
    if s.last_target_id != input.entity_id {
        s.last_target_id = input.entity_id;
        s.hit_counter = 0;
    }

    if !input.trigger_ok {
        return (pack(sprint_step, Step::None), s);
    }
    // Range / gate failures return without consuming the hit.
    if !input.in_range || !input.gate_ok {
        return (pack(sprint_step, Step::None), s);
    }

    s.hit_counter += 1;
    if s.hit_counter < input.hit_count {
        return (pack(sprint_step, Step::None), s);
    }
    s.hit_counter = 0;

    // The only draw. Kotlin's `Random.nextInt(100)` is [0, 100); a roll >=
    // chance rejects when chance < 100. chance == 100 never draws.
    if input.chance < 100 && roll() >= input.chance {
        return (pack(sprint_step, Step::None), s);
    }

    // SprintReset acts this tick; offset one tick so our C0B burst lands
    // after its reset burst.
    if input.coordination_active {
        s.coord_offset_ticks = 1;
        return (pack(sprint_step, Step::None), s);
    }

    let fire_step = fire_trigger(&mut s, input);
    (pack(sprint_step, fire_step), s)
}

/// Delay or start now. `startAction` in the original used the *current* mode
/// (a user could change it during the delay), so no mode is captured here.
fn fire_trigger(s: &mut State, input: Input) -> Step {
    if input.delay_ms > 0 {
        s.phase = Phase::Delay;
        s.delay_end_ns = input.now_ns + input.delay_ms as i64 * 1_000_000;
        Step::None
    } else {
        start_action(s, input)
    }
}

fn start_action(s: &mut State, input: Input) -> Step {
    match input.mode {
        MODE_OLD => Step::FireOld,
        MODE_SNEAK_PACKET => Step::FireSneak,
        // SprintTap only arms the force sequence when the server mirror says
        // we are sprinting. The HUD still flashes either way, which is why
        // the answer is FireSprintTap and not None.
        _ => {
            if input.sprint_tap_allowed {
                s.sprint_ticks = 2;
                s.phase = Phase::SprintTap;
            }
            Step::FireSprintTap
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> Input {
        Input {
            now_ns: 1_000_000_000,
            has_target: true,
            trigger_ok: true,
            in_range: true,
            gate_ok: true,
            entity_id: 7,
            chance: 100,
            hit_count: 1,
            delay_ms: 0,
            mode: MODE_OLD,
            sprint_tap_allowed: false,
            coordination_active: false,
        }
    }

    fn tick(s: State, i: Input, roll: &mut impl FnMut() -> i32) -> (i32, State) {
        super::tick(s, i, roll)
    }

    #[test]
    fn a_fresh_hit_fires_the_configured_mode_and_never_draws_at_chance_100() {
        let mut draws = 0;
        let (code, _) = tick(State::new(), input(), &mut || {
            draws += 1;
            0
        });
        assert_eq!(unpack_fire(code), Step::FireOld.code());
        assert_eq!(draws, 0, "chance 100 never touches the stream");
    }

    #[test]
    fn a_failed_gate_or_range_never_draws_or_counts_the_hit() {
        let mut draws = 0;
        let (code, s) = tick(
            State::new(),
            Input { gate_ok: false, chance: 40, ..input() },
            &mut || {
                draws += 1;
                0
            },
        );
        assert_eq!(code, 0);
        assert_eq!(s.hit_counter, 0);
        assert_eq!(draws, 0);

        let (code, s) = tick(
            State::new(),
            Input { in_range: false, chance: 40, ..input() },
            &mut || {
                draws += 1;
                0
            },
        );
        assert_eq!(code, 0);
        assert_eq!(s.hit_counter, 0);
        assert_eq!(draws, 0);
    }

    #[test]
    fn the_chance_roll_rejects_only_at_the_fire_point() {
        let mut draws = 0;
        let (code, s) = tick(
            State::new(),
            Input { chance: 40, ..input() },
            &mut || {
                draws += 1;
                70
            },
        );
        assert_eq!(code, 0);
        assert_eq!(s.hit_counter, 0, "the throttle was consumed before the roll");
        assert_eq!(draws, 1, "exactly one draw at the fire point");
    }

    #[test]
    fn hit_count_throttles_and_only_the_firing_hit_draws() {
        let mut draws = 0;
        let mut s = State::new();
        let i = Input { hit_count: 3, chance: 40, ..input() };

        // Two non-firing hits (separated by idle ticks) must not draw.
        for _ in 0..2 {
            let (code, next) = tick(s, i, &mut || {
                draws += 1;
                0
            });
            assert_eq!(code, 0);
            s = next;
            let (_, next) = tick(s, Input { trigger_ok: false, ..i }, &mut || {
                draws += 1;
                0
            });
            s = next;
        }
        assert_eq!(draws, 0);

        let (code, _) = tick(s, i, &mut || {
            draws += 1;
            0
        });
        assert_eq!(unpack_fire(code), Step::FireOld.code());
        assert_eq!(draws, 1, "only the firing hit draws");
    }

    #[test]
    fn switching_targets_restarts_the_throttle() {
        let mut s = State::new();
        let i = Input { hit_count: 2, ..input() };
        let (_, next) = tick(s, i, &mut || 0);
        s = next;
        assert_eq!(s.hit_counter, 1);

        let (code, next) = tick(s, Input { entity_id: 9, ..i }, &mut || 0);
        assert_eq!(code, 0);
        assert_eq!(next.hit_counter, 1, "a new target starts a fresh cycle");
        assert_eq!(next.last_target_id, 9);
    }

    #[test]
    fn a_delayed_fire_arms_a_deadline_and_fires_later() {
        let i = Input { delay_ms: 100, mode: MODE_SNEAK_PACKET, ..input() };
        let (armed, s) = tick(State::new(), i, &mut || 0);
        assert_eq!(armed, 0);
        assert_eq!(s.phase, Phase::Delay);
        assert_eq!(s.delay_end_ns, 1_000_000_000 + 100 * 1_000_000);

        let (waiting, s2) = tick(s, Input { now_ns: 1_050_000_000, ..i }, &mut || 0);
        assert_eq!(waiting, 0, "the delayed tick short-circuits the trigger");
        assert_eq!(s2.phase, Phase::Delay);

        let (fired, s3) = tick(s2, Input { now_ns: 1_100_000_000, ..i }, &mut || 0);
        assert_eq!(unpack_fire(fired), Step::FireSneak.code());
        assert_eq!(s3.phase, Phase::Idle);
    }

    #[test]
    fn coordination_offset_fires_on_the_next_tick() {
        let i = Input { coordination_active: true, ..input() };
        let (deferred, s) = tick(State::new(), i, &mut || 0);
        assert_eq!(deferred, 0);
        assert_eq!(s.coord_offset_ticks, 1);

        let (fired, s2) = tick(
            s,
            Input { trigger_ok: false, coordination_active: false, ..i },
            &mut || 0,
        );
        assert_eq!(unpack_fire(fired), Step::FireOld.code());
        assert_eq!(s2.coord_offset_ticks, 0);
    }

    #[test]
    fn sprint_tap_runs_three_ticks_then_releases_the_claim() {
        let tap = Input { mode: MODE_SPRINT_TAP, sprint_tap_allowed: true, ..input() };
        let (armed, mut s) = tick(State::new(), tap, &mut || 0);
        assert_eq!(unpack_fire(armed), Step::FireSprintTap.code());
        assert_eq!(s.phase, Phase::SprintTap);
        assert_eq!(s.sprint_ticks, 2);

        // No fresh hit while the tap runs, otherwise a new trigger would
        // re-arm the sequence (which is the original behaviour too).
        let idle = Input { trigger_ok: false, ..tap };
        let (break_code, next) = tick(s, idle, &mut || 0);
        assert_eq!(unpack_sprint(break_code), Step::SprintTapBreak.code());
        s = next;
        let (restore_code, next) = tick(s, idle, &mut || 0);
        assert_eq!(unpack_sprint(restore_code), Step::SprintTapRestore.code());
        s = next;
        let (end_code, next) = tick(s, idle, &mut || 0);
        assert_eq!(unpack_sprint(end_code), Step::SprintTapEnd.code());
        assert_eq!(next.phase, Phase::Idle);
    }

    #[test]
    fn a_sprint_tap_step_and_a_fire_share_one_scalar_answer() {
        // The SprintTap machine advances at the top of the tick while a fresh
        // hit fires later in the same tick. Both writes must survive the wire.
        let s = State {
            phase: Phase::SprintTap,
            sprint_ticks: 2,
            ..State::new()
        };
        let tap = Input { mode: MODE_SPRINT_TAP, sprint_tap_allowed: true, ..input() };
        let (code, next) = tick(s, tap, &mut || 0);
        assert_eq!(unpack_sprint(code), Step::SprintTapBreak.code());
        assert_eq!(unpack_fire(code), Step::FireSprintTap.code(), "two writes, one answer");
        // The new fire re-armed the sequence, exactly as the JVM did.
        assert_eq!(next.sprint_ticks, 2);
        assert_eq!(next.phase, Phase::SprintTap);
    }

    #[test]
    fn step_codes_and_packing_round_trip() {
        for s in [
            Step::None,
            Step::SprintTapBreak,
            Step::SprintTapRestore,
            Step::SprintTapEnd,
            Step::FireSprintTap,
            Step::FireOld,
            Step::FireSneak,
        ] {
            assert_eq!(Step::from_code(s.code()), Some(s));
        }
        assert_eq!(Step::from_code(7), None);
        assert_eq!(unpack_sprint(pack(Step::SprintTapEnd, Step::FireSneak)), Step::SprintTapEnd.code());
        assert_eq!(unpack_fire(pack(Step::SprintTapEnd, Step::FireSneak)), Step::FireSneak.code());
    }
}
