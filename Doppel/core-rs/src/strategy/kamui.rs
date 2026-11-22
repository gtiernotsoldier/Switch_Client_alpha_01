//! Kamui — the phase machine behind the packet vault: hold the connection for
//! a few ticks while the player sidesteps, then let the backlog out.
//!
//! The JVM keeps only judgements and platform I/O: the player being dead, the
//! GUI being open (already folded with the FlushOnGui switch), the trigger
//! key edge surviving its hop from the key thread to the next tick, and the
//! claim / release / flush writes. Everything stateful lives here: the phase
//! machine (IDLE / PHASE / COOLDOWN), the tick counters, the cast counter and
//! the knob set latched at the moment the phase started.
//!
//! Ported branch for branch from the former JVM-side `KamuiEngine` — including
//! the **order** of the safety overrides (death beats GUI beats the cap) and
//! the retune rule (the knob set in force for a cast is fixed when the cast
//! starts; the original engine ignored config changes outside IDLE, so a live
//! cast was never retuned mid-flight — the latch is that rule expressed on the
//! wire, because a MaxHold dragged upward mid-cast must not un-bound a phase
//! that started under the tighter cap). There is **no randomness** — no RNG
//! stream exists for this module — the machine is a pure function of the
//! inputs and the state.
//!
//! The trigger edge deserves its own note. On the JVM the key listener fires
//! between ticks; here the edge rides the next tick's input. A tick that
//! carries `trigger_edge` in IDLE therefore only *starts* the phase (state
//! becomes PHASE with `phase_tick` 0, no flags) — the phase is evaluated on
//! the next tick call, exactly as `trigger()` + `tick()` did across the same
//! boundary. The keypress claim covers the same tick's packets on the JVM
//! side; every phasing tick's CLAIM flag refreshes it afterwards.
//!
//! ```text
//! IDLE --trigger edge--> PHASE --(cap reached / GUI)--> COOLDOWN --> IDLE
//!                          |
//!                          +--(death)--> IDLE (hard reset, no cooldown)
//! ```
//!
//! The answer packs the whole machine view into one scalar (see [`pack`]),
//! because the HUD reads the state and both counters every tick — flags alone
//! would leave the HUD guessing between two round trips.

/// Machine states, as carried by the answer's low nibble.
pub const STATE_IDLE: u8 = 0;
pub const STATE_PHASE: u8 = 1;
pub const STATE_COOLDOWN: u8 = 2;

/// Flag bits, in the answer's second nibble (bits 4-7).
pub const FLAG_CLAIM: u8 = 1;
pub const FLAG_RELEASE: u8 = 2;

/// What the core remembers between ticks.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct State {
    /// 0 IDLE, 1 PHASE, 2 COOLDOWN.
    pub state: u8,
    /// Ticks spent in the current phase. 0 on the tick that started the
    /// phase; 1-based once the first phasing tick has run.
    pub phase_tick: i32,
    /// Ticks spent in the current cooldown.
    pub cooldown_tick: i32,
    /// Completed phase starts, for logs and the HUD.
    pub casts: i32,
    /// The knob set in force for the current cast, latched when the phase
    /// started. The original engine ignored `applyConfig` outside IDLE, so a
    /// cast was never retuned mid-flight — see the module comment.
    pub latched_phase_ticks: i32,
    pub latched_max_hold_ticks: i32,
    pub latched_cooldown_ticks: i32,
}

impl Default for State {
    fn default() -> State {
        State {
            state: STATE_IDLE,
            phase_tick: 0,
            cooldown_tick: 0,
            casts: 0,
            // The latched knobs are dead weight until the first trigger re-latches
            // them from the request, so the defaults can be zero.
            latched_phase_ticks: 0,
            latched_max_hold_ticks: 0,
            latched_cooldown_ticks: 0,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// The judgements and knobs the JVM marshals into one request per tick.
///
/// `trigger_edge` is a judgement with a travel time: the key thread registered
/// it between ticks, and this tick consumes it whichever way the machine
/// decides — an edge that arrives while the machine is busy is refused, never
/// queued (the original returned `false` from `trigger()`).
#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub trigger_edge: bool,
    /// Already folded with the FlushOnGui switch on the JVM side.
    pub gui_open: bool,
    /// Judgement: `player.health <= 0f`.
    pub dead: bool,
    pub phase_ticks: i32,
    pub max_hold_ticks: i32,
    pub cooldown_ticks: i32,
}

/// The packed scalar answer, unpacked — what the Kotlin side reads back out of
/// the one f64 (mirrored field-for-field by `Strategies.KamuiAnswer`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Answer {
    pub state: u8,
    /// CLAIM_HOLD this tick — refresh the vault claim.
    pub claim: bool,
    /// RELEASE_AND_FLUSH this tick — drop the claim and flush the backlog.
    pub release: bool,
    pub phase_tick: i32,
    pub cooldown_tick: i32,
}

/// Pack the machine view into one scalar.
///
///   bits 0-3   state        (0 idle, 1 phase, 2 cooldown)
///   bits 4-7   flags        (bit 0 = claim this tick, bit 1 = release this tick)
///   bits 8-19  phase_tick   (0..4095)
///   bits 20-31 cooldown_tick (0..4095)
///
/// Fields are masked to their widths. The machine's counters stay far below
/// (the options cap Phase at 10, MaxHold at 20, Cooldown at 40), so the mask
/// never bites in practice — it exists so a mistuned config cannot bleed into
/// a neighbouring field and make the adapter read a different machine state
/// than the core owns: that is exactly the desync class the parity suite
/// hunts, arriving through a slider instead of a port. A packed value with
/// bit 31 set is a *negative* `i32`, which is fine: the wire widens it to
/// f64 (exact for every i32) and the stub narrows it back losslessly.
pub fn pack(state: u8, claim: bool, release: bool, phase_tick: i32, cooldown_tick: i32) -> i32 {
    (((state as u32) & 0xF)
        | ((claim as u32) << 4)
        | ((release as u32) << 5)
        | (((phase_tick as u32) & 0xFFF) << 8)
        | (((cooldown_tick as u32) & 0xFFF) << 20)) as i32
}

/// Inverse of [`pack`].
pub fn unpack(code: i32) -> Answer {
    Answer {
        state: (code as u32 & 0xF) as u8,
        claim: code as u32 & ((FLAG_CLAIM as u32) << 4) != 0,
        release: code as u32 & ((FLAG_RELEASE as u32) << 4) != 0,
        phase_tick: ((code as u32 >> 8) & 0xFFF) as i32,
        cooldown_tick: ((code as u32 >> 20) & 0xFFF) as i32,
    }
}

/// One client tick. Returns the packed answer and the new state.
///
/// Branch order mirrors the original `KamuiEngine.tick` exactly. The phase
/// never outlives its cap even if Phase is mistuned above MaxHold, and a dead
/// player owes no cooldown.
pub fn tick(state: State, input: Input) -> (i32, State) {
    let mut s = state;

    match s.state {
        STATE_IDLE => {
            if input.trigger_edge {
                s.state = STATE_PHASE;
                s.phase_tick = 0;
                s.cooldown_tick = 0;
                s.casts += 1;
                // Latch the knobs the whole cast (phase + cooldown) will run
                // under. Retune mid-flight is ignored by construction: the
                // next cast picks up whatever the request carries then.
                s.latched_phase_ticks = input.phase_ticks;
                s.latched_max_hold_ticks = input.max_hold_ticks;
                s.latched_cooldown_ticks = input.cooldown_ticks;
                // The started phase is evaluated on the NEXT tick call,
                // exactly like the original where trigger() happened between
                // ticks. No flags: the JVM claims at the keypress itself, and
                // the first phasing tick's CLAIM flag takes over from there.
                return (pack(s.state, false, false, s.phase_tick, s.cooldown_tick), s);
            }
            // IDLE: nothing this tick, no flags.
            (pack(s.state, false, false, s.phase_tick, s.cooldown_tick), s)
        }

        STATE_PHASE => {
            s.phase_tick += 1;
            // Safety overrides, in the order they are allowed to fire. A phase is never
            // worth a GUI stall (the player cannot play blink while a screen eats their
            // input) and never worth a runaway hold (the hard cap bounds a mistuned
            // Phase option no matter what).
            if input.dead {
                // Hard reset: no cooldown, a dead player owes none.
                s.state = STATE_IDLE;
                s.phase_tick = 0;
                s.cooldown_tick = 0;
                return (pack(s.state, false, true, s.phase_tick, s.cooldown_tick), s);
            }
            if input.gui_open {
                s.state = STATE_COOLDOWN;
                s.cooldown_tick = 0;
                return (pack(s.state, false, true, s.phase_tick, s.cooldown_tick), s);
            }
            if s.phase_tick >= s.latched_phase_ticks.min(s.latched_max_hold_ticks) {
                s.state = STATE_COOLDOWN;
                s.cooldown_tick = 0;
                return (pack(s.state, false, true, s.phase_tick, s.cooldown_tick), s);
            }
            // Refresh the vault claim this tick (packets are queued instead of
            // sent; the platform keeps its own hard cap as the last-resort guard).
            (pack(s.state, true, false, s.phase_tick, s.cooldown_tick), s)
        }

        // COOLDOWN. No flags — the claim was dropped at the release; a cooldown
        // tick never holds anything.
        _ => {
            s.cooldown_tick += 1;
            if s.cooldown_tick >= s.latched_cooldown_ticks {
                s.state = STATE_IDLE;
                s.cooldown_tick = 0;
            }
            (pack(s.state, false, false, s.phase_tick, s.cooldown_tick), s)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> Input {
        Input {
            trigger_edge: false,
            gui_open: false,
            dead: false,
            phase_ticks: 3,
            max_hold_ticks: 10,
            cooldown_ticks: 8,
        }
    }

    fn tick(s: State, i: Input) -> (i32, State) {
        super::tick(s, i)
    }

    fn trigger(s: State) -> (i32, State) {
        super::tick(s, Input {
            trigger_edge: true,
            ..input()
        })
    }

    /// True when the packed answer says "the tick that consumed the edge just
    /// started a phase" — the signature the adapter looks for.
    fn started(packed: i32) -> bool {
        let a = unpack(packed);
        a.state == STATE_PHASE && a.phase_tick == 0 && !a.claim && !a.release
    }

    // ---- The one cycle ------------------------------------------------------

    #[test]
    fn a_trigger_edge_starts_a_phase_from_idle() {
        let (packed, s) = trigger(State::new());
        assert_eq!(s.state, STATE_PHASE);
        assert_eq!(s.phase_tick, 0);
        assert_eq!(s.cooldown_tick, 0);
        assert_eq!(s.casts, 1);
        assert!(started(packed), "the starting tick carries no flags");
        assert_eq!(s.latched_phase_ticks, 3);
        assert_eq!(s.latched_max_hold_ticks, 10);
        assert_eq!(s.latched_cooldown_ticks, 8);
    }

    #[test]
    fn a_second_edge_while_busy_is_refused() {
        let (_, s) = trigger(State::new());
        let (packed, s) = tick(s, input());
        assert_eq!(unpack(packed).claim, true, "phasing tick claims");
        // Another edge mid-phase: refused, not queued, the cast counter unchanged.
        let (packed2, s2) = tick(s, Input { trigger_edge: true, ..input() });
        assert_eq!(s2.state, STATE_PHASE);
        assert_eq!(s2.casts, 1, "a second key edge must not restart a live phase");
        assert_eq!(unpack(packed2).phase_tick, 2);
    }

    #[test]
    fn a_phase_claims_every_tick_and_releases_exactly_once_at_the_cap() {
        let (_, s) = trigger(State::new());

        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.claim, true);
        assert_eq!(a.release, false);
        assert_eq!(a.phase_tick, 1);
        assert_eq!(s.state, STATE_PHASE);

        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.claim, true);
        assert_eq!(a.phase_tick, 2);
        assert_eq!(s.state, STATE_PHASE);

        // Tick 3 is the cap: release, and the phase moves to cooldown.
        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.release, true);
        assert_eq!(a.claim, false);
        assert_eq!(a.cooldown_tick, 0);
        assert_eq!(s.state, STATE_COOLDOWN);
    }

    #[test]
    fn cooldown_returns_to_idle_and_frees_the_trigger() {
        // Latch Phase=1, Cooldown=2: one phasing tick, straight to release.
        let (_, s) = tick(
            State::new(),
            Input {
                trigger_edge: true,
                phase_ticks: 1,
                max_hold_ticks: 10,
                cooldown_ticks: 2,
                ..input()
            },
        );
        assert_eq!(s.state, STATE_PHASE);

        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.release, true, "cap on phase tick 1");
        assert_eq!(s.state, STATE_COOLDOWN);

        // Two cooldown ticks, then IDLE.
        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.state, STATE_COOLDOWN);
        assert_eq!(a.cooldown_tick, 1);
        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.state, STATE_IDLE);
        assert_eq!(a.cooldown_tick, 0, "reset on the exit tick");

        // The trigger is free again; a fresh cast bumps the counter.
        let (_, s) = trigger(s);
        assert_eq!(s.state, STATE_PHASE);
        assert_eq!(s.casts, 2);
    }

    // ---- Guardrails ---------------------------------------------------------

    #[test]
    fn a_gui_opening_mid_phase_ends_it_with_the_flush() {
        let (_, s) = trigger(State::new());
        let (_, s) = tick(s, input());
        let (packed, s) = tick(s, Input { gui_open: true, ..input() });
        let a = unpack(packed);
        assert_eq!(a.release, true);
        assert_eq!(s.state, STATE_COOLDOWN);
    }

    #[test]
    fn death_hard_resets_to_idle_with_no_cooldown() {
        let (_, s) = trigger(State::new());
        let (_, s) = tick(s, input());
        let (packed, s) = tick(s, Input { dead: true, ..input() });
        let a = unpack(packed);
        assert_eq!(a.release, true);
        assert_eq!(s.state, STATE_IDLE, "hard reset skips the cooldown");
        assert_eq!(s.phase_tick, 0);
        assert_eq!(s.cooldown_tick, 0);

        // The next trigger lands immediately: a dead player does not owe a cooldown.
        let (_, s) = trigger(s);
        assert_eq!(s.state, STATE_PHASE);
    }

    #[test]
    fn the_hard_cap_bounds_a_mistuned_phase_option() {
        // Phase says 7, MaxHold says 3: release at tick 3, not 7.
        let (_, s) = trigger(State::new());
        let (_, s) = tick(
            s,
            Input { phase_ticks: 7, max_hold_ticks: 3, cooldown_ticks: 8, ..input() },
        );
        let (_, s) = tick(s, input());
        let (packed, s) = tick(s, input());
        let a = unpack(packed);
        assert_eq!(a.release, true);
        assert_eq!(s.state, STATE_COOLDOWN);
        assert_eq!(s.phase_tick, 3);
    }

    #[test]
    fn a_reset_frame_is_the_abort() {
        // The JVM engine had abort(); here the equivalent is the Reset frame:
        // Module::Player reset replaces the state wholesale, and the module's
        // onDisable owns the release + flush write. The reset must not smuggle
        // a release through the machine — the tick after a reset answers no
        // flags at all.
        let (_, s) = trigger(State::new());
        let (_, s) = tick(s, input());
        assert_eq!(s.state, STATE_PHASE);

        let fresh = State::new();
        assert_eq!(fresh.state, STATE_IDLE);
        let (packed, _) = tick(fresh, input());
        let a = unpack(packed);
        assert_eq!(a.claim, false);
        assert_eq!(a.release, false, "nothing to release from idle after a reset");
    }

    // ---- Config retuning ----------------------------------------------------

    #[test]
    fn config_changes_apply_from_idle_but_never_mid_phase() {
        // This cast latches Phase=5, Cooldown=0.
        let (_, s) = tick(
            State::new(),
            Input {
                trigger_edge: true,
                phase_ticks: 5,
                max_hold_ticks: 10,
                cooldown_ticks: 0,
                ..input()
            },
        );
        // A GUI-side retune (Phase -> 1) arrives on the following requests:
        // still the latched cap — the live cast is not retuned.
        let retuned = Input { phase_ticks: 1, max_hold_ticks: 10, cooldown_ticks: 0, ..input() };
        let mut s = s;
        for expected_pt in 1..=4 {
            let (packed, next) = tick(s, retuned);
            assert_eq!(
                unpack(packed).claim,
                true,
                "still the ORIGINAL cap — mid-flight retune ignored"
            );
            assert_eq!(unpack(packed).phase_tick, expected_pt);
            s = next;
        }
        let (packed, s) = tick(s, retuned);
        assert_eq!(unpack(packed).release, true);
        assert_eq!(s.phase_tick, 5, "released at the latched cap of 5, not the retuned 1");
        assert_eq!(s.state, STATE_COOLDOWN);

        // Cooldown (0) elapses on the next tick; only from a fresh trigger does
        // a cast pick up the knobs its own trigger tick carries.
        let (_, s) = tick(s, input());
        assert_eq!(s.state, STATE_IDLE);
        let (_, s) = tick(
            s,
            Input {
                trigger_edge: true,
                phase_ticks: 2,
                max_hold_ticks: 10,
                cooldown_ticks: 0,
                ..input()
            },
        );
        assert_eq!(s.latched_phase_ticks, 2, "the new cast latched the retuned knob");
        let (packed, s) = tick(s, input());
        assert_eq!(unpack(packed).claim, true);
        assert_eq!(unpack(packed).phase_tick, 1);
        let (packed, s) = tick(s, input());
        assert_eq!(unpack(packed).release, true);
        assert_eq!(s.phase_tick, 2);
    }

    // ---- The packing --------------------------------------------------------

    #[test]
    fn the_packed_answer_round_trips() {
        for state in [STATE_IDLE, STATE_PHASE, STATE_COOLDOWN] {
            for claim in [false, true] {
                for release in [false, true] {
                    for pt in [0, 1, 4095] {
                        for ct in [0, 8, 4095] {
                            let packed = pack(state, claim, release, pt, ct);
                            let a = unpack(packed);
                            assert_eq!(a.state, state);
                            assert_eq!(a.claim, claim);
                            assert_eq!(a.release, release);
                            assert_eq!(a.phase_tick, pt);
                            assert_eq!(a.cooldown_tick, ct);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn the_packed_answer_survives_the_f64_hop_with_bit31_set() {
        // cooldown_tick 4095 sets bit 31: the packed i32 is negative. The wire
        // widens it to f64 and the stub narrows it back — exact for every i32,
        // but the hop is part of the contract, so pin it.
        let packed = pack(STATE_COOLDOWN, false, false, 4095, 4095);
        assert!(packed < 0, "bit 31 set means a negative i32");
        assert_eq!((packed as f64) as i32, packed);
        let a = unpack(packed);
        assert_eq!(a.cooldown_tick, 4095);
        assert_eq!(a.phase_tick, 4095);
    }

    #[test]
    fn fields_never_bleed_into_each_other() {
        // The mask: a counter beyond its field width cannot corrupt the
        // neighbouring fields (the failure this guards is the adapter reading
        // a different machine state than the core owns).
        let packed = pack(STATE_PHASE, true, false, 0xFFFF, 0xFFFF);
        let a = unpack(packed);
        assert_eq!(a.state, STATE_PHASE);
        assert_eq!(a.claim, true);
        assert_eq!(a.release, false);
        assert_eq!(a.phase_tick, 0xFFF);
        assert_eq!(a.cooldown_tick, 0xFFF);
    }
}
