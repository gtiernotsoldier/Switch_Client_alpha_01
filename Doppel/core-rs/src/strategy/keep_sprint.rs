//! KeepSprint — carry the sprint flag across a landed swing, with the whole
//! state machine in the core.
//!
//! The JVM keeps only judgements and platform I/O: the attack composite
//! (physical button OR a clicker's synthetic attack), the crosshair target
//! being alive, the forward-key arbitration, the tap-owns-W override, and
//! the sprint-claim / C0B writes. Everything stateful lives here: the
//! sprint-sample latch (a 20 Hz sampler can land either side of the attack
//! that clears the flag), the two-dispatch hold window, the wall-clock
//! backstop for a stalled dispatcher, and the per-tick "already handled"
//! dedup — 1.8 polls the attack key every tick, so a held button is a new
//! attack every 50 ms and each one must re-assert.
//!
//! This module has **no randomness**: no RNG stream exists for it, and the
//! answer is a pure function of the inputs and the state. It is also the
//! first **frame-driven** op — the attack flag is refreshed per render
//! frame, and the wall-clock backstop must be checked on a thread (render)
//! that survives a stalled tick dispatcher. The adapter short-circuits
//! idle frames locally (`!attacking && !sprinting-sample && no claim`) so
//! the frame-rate round trips only happen while something can change; the
//! skip guard is exactly the original's early returns re-ordered, not a
//! behaviour change.
//!
//! The scalar answer is packed the same way SuperKnockback's is, because a
//! single frame can carry two platform writes: a hold step in the low
//! nibble and an assert step in the high nibble. The hold nibble carries a
//! *status*, not just a transition — `DEAD` lets the adapter self-heal a
//! claim the core no longer knows about (another module's Reset frame, or a
//! core restart mid-hold), which is the leak class the AutoBlock lag window
//! fix belonged to.

/// Wall-clock backstop for the hold, in nanoseconds.
///
/// The "tick" counter only moves when dispatches happen. If the dispatcher
/// stalls (pause, world change) the two-dispatch window would never end,
/// and a sprint claim is applied straight to the player, not through the
/// keybind layer a GUI suppresses — so it would sit there forcing sprint.
/// Three dispatches of real time is longer than the window needs and short
/// enough that a stall cannot hold the flag.
pub const HOLD_MAX_NANOS: i64 = 150_000_000;

/// How long a "sprinting" sample stays credible, in nanoseconds.
///
/// `EventBridge.localSprinting` is a 20 Hz sample whose phase against the
/// attack is arbitrary — it can land after vanilla cleared the flag. A
/// single read would then say "not sprinting" about a player who
/// demonstrably was; latching for a bit over two dispatch intervals makes
/// the answer independent of where the sample fell.
pub const SPRINT_MEMORY_NANOS: i64 = 120_000_000;

/// What the core remembers between the swing and the hold it causes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct State {
    /// Dispatch tick at which the current hold stops being applied; -1 when
    /// no hold is live.
    pub hold_until_tick: i32,
    /// Wall-clock deadline of the hold; 0 when no hold is live.
    pub hold_deadline_ns: i64,
    /// Last dispatch tick an attack was handled on — one assertion per tick,
    /// not one per render frame.
    pub last_handled_tick: i32,
    /// Last time the sprint sample reported "sprinting"; 0 when never.
    pub last_sprint_seen_ns: i64,
}

impl Default for State {
    fn default() -> State {
        State {
            hold_until_tick: -1,
            hold_deadline_ns: 0,
            last_handled_tick: -1,
            last_sprint_seen_ns: 0,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// The hold half of the answer. Carries a status rather than only a
/// transition so the adapter can detect a hold the core no longer owns.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HoldStatus {
    /// No hold is live — the adapter releases any claim it still holds.
    Dead,
    /// A hold is live; keep claiming.
    Live,
    /// The hold ended on this frame — release now.
    Release,
}

impl HoldStatus {
    pub fn code(self) -> u8 {
        match self {
            HoldStatus::Dead => 0,
            HoldStatus::Live => 1,
            HoldStatus::Release => 2,
        }
    }

    pub fn from_code(c: u8) -> Option<HoldStatus> {
        Some(match c {
            0 => HoldStatus::Dead,
            1 => HoldStatus::Live,
            2 => HoldStatus::Release,
            _ => return None,
        })
    }
}

/// The assert half of the answer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AssertStep {
    /// No platform write.
    None,
    /// Claim sprint (ADDITIVE) and send START_SPRINTING.
    Assert,
}

impl AssertStep {
    pub fn code(self) -> u8 {
        match self {
            AssertStep::None => 0,
            AssertStep::Assert => 1,
        }
    }

    pub fn from_code(c: u8) -> Option<AssertStep> {
        Some(match c {
            0 => AssertStep::None,
            1 => AssertStep::Assert,
            _ => return None,
        })
    }
}

/// A frame can end a hold at the top and assert a new one later — pack both
/// into the single scalar the wire carries.
pub fn pack(hold: HoldStatus, assert: AssertStep) -> i32 {
    (hold.code() as i32) | ((assert.code() as i32) << 4)
}

pub fn unpack_hold(code: i32) -> u8 {
    (code & 0xF) as u8
}

pub fn unpack_assert(code: i32) -> u8 {
    ((code >> 4) & 0xF) as u8
}

/// The judgements the JVM marshals into one request per frame.
#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub now_ns: i64,
    /// `EventBridge.getCurrentTick()` — the 20 Hz dispatch counter.
    pub tick: i32,
    /// The current sprint sample.
    pub local_sprinting: bool,
    /// Physical left mouse OR an AutoClicker synthetic attack.
    pub attacking: bool,
    /// Crosshair target present and alive — the proxy for "the swing
    /// landed"; a swing at air must not assert anything.
    pub target_alive: bool,
    /// `EventBridge.isKeyForwardActive`.
    pub forward: bool,
    /// A tap module currently owns W (it resets sprint on purpose).
    pub forward_override: bool,
}

/// One render frame. Mirrors the original `onRenderFrame` order: latch,
/// release check, assert evaluation — the release runs before the attack
/// gates so an expired hold ends even on a frame that asserts nothing, and
/// an assert in the same frame re-arms the hold after the release.
pub fn frame(state: State, input: Input) -> (i32, State) {
    let mut s = state;

    // ---- Sprint-sample latch ----
    // Only a "sprinting" sample refreshes the stamp, exactly like the
    // original `if (EventBridge.localSprinting) lastSprintSeenNanos = now`.
    if input.local_sprinting {
        s.last_sprint_seen_ns = input.now_ns;
    }

    // ---- Hold upkeep ----
    // Three ways a hold outlives its reason: the dispatch window (two
    // dispatches), the wall clock (dispatcher stalled), or the player
    // letting go of forward — the last one also keeps the flag from
    // rendering sprint particles for a player who has stopped moving.
    let mut hold = HoldStatus::Dead;
    if s.hold_until_tick >= 0 {
        if input.tick > s.hold_until_tick
            || input.now_ns > s.hold_deadline_ns
            || !input.forward
        {
            s.hold_until_tick = -1;
            s.hold_deadline_ns = 0;
            hold = HoldStatus::Release;
        } else {
            hold = HoldStatus::Live;
        }
    }

    // ---- Assert evaluation ----
    let mut assert = AssertStep::None;
    if input.attacking && input.tick != s.last_handled_tick && input.target_alive {
        // Preserve a sprint, never create one: the sample said sprinting, or
        // it said so within the last ~2 dispatches. Without this gate a
        // player standing still and clicking would be put into sprint.
        let sprint_was_on = input.local_sprinting
            || (s.last_sprint_seen_ns != 0
                && input.now_ns - s.last_sprint_seen_ns <= SPRINT_MEMORY_NANOS);
        // Sprint is fed by forward input; with W released the flag is on its
        // way out anyway, and a tap module owning W is resetting sprint on
        // purpose — re-asserting would undo the reset it just paid for.
        if sprint_was_on && input.forward && !input.forward_override {
            s.last_handled_tick = input.tick;
            s.hold_until_tick = input.tick + 1;
            s.hold_deadline_ns = input.now_ns + HOLD_MAX_NANOS;
            assert = AssertStep::Assert;
            // The hold is (re)armed — Live beats the Release this frame may
            // have produced, exactly as the original re-armed after
            // releaseHold().
            hold = HoldStatus::Live;
        }
    }

    (pack(hold, assert), s)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> Input {
        Input {
            now_ns: 1_000_000_000,
            tick: 10,
            local_sprinting: true,
            attacking: true,
            target_alive: true,
            forward: true,
            forward_override: false,
        }
    }

    fn frame(s: State, i: Input) -> (i32, State) {
        super::frame(s, i)
    }

    #[test]
    fn a_landed_swing_while_sprinting_asserts_and_arms_the_hold() {
        let (code, s) = frame(State::new(), input());
        assert_eq!(unpack_hold(code), HoldStatus::Live.code());
        assert_eq!(unpack_assert(code), AssertStep::Assert.code());
        assert_eq!(s.hold_until_tick, 11);
        assert_eq!(s.hold_deadline_ns, 1_000_000_000 + HOLD_MAX_NANOS);
        assert_eq!(s.last_handled_tick, 10);
    }

    #[test]
    fn a_swing_at_air_or_a_dead_target_asserts_nothing() {
        for target_alive in [false] {
            let (code, _) = frame(
                State::new(),
                Input { target_alive, ..input() },
            );
            assert_eq!(code, 0, "no target alive means no assert");
        }
        // No target at all is the same judgement on the JVM side.
        let (code, s) = frame(State::new(), Input { target_alive: false, ..input() });
        assert_eq!(code, 0);
        assert_eq!(s.last_handled_tick, -1, "the tick was not consumed");
    }

    #[test]
    fn one_assert_per_tick_not_per_frame() {
        let (_, mut s) = frame(State::new(), input());
        // Same tick, still attacking: the dedup must hold even though the
        // hold is live.
        for _ in 0..5 {
            let (code, next) = frame(s, input());
            assert_eq!(unpack_assert(code), AssertStep::None.code());
            assert_eq!(unpack_hold(code), HoldStatus::Live.code());
            s = next;
        }
        // The next dispatch tick re-asserts: 1.8 polls the attack key every
        // tick, so a held button really is a new attack.
        let (code, s2) = frame(s, Input { tick: 11, ..input() });
        assert_eq!(unpack_assert(code), AssertStep::Assert.code());
        assert_eq!(s2.last_handled_tick, 11);
    }

    #[test]
    fn the_hold_survives_one_dispatch_and_ends_on_the_second() {
        let (_, s) = frame(State::new(), input());
        let (_, s1) = frame(s, Input { tick: 11, attacking: false, ..input() });
        assert_eq!(s1.hold_until_tick, 11, "tick 11 == holdUntil: still live");
        let (code, s2) = frame(s1, Input { tick: 12, attacking: false, ..input() });
        assert_eq!(unpack_hold(code), HoldStatus::Release.code());
        assert_eq!(s2.hold_until_tick, -1);
        assert_eq!(s2.hold_deadline_ns, 0);
    }

    #[test]
    fn the_wall_clock_backstop_ends_a_stalled_hold() {
        let (_, s) = frame(State::new(), input());
        // Ticks stop advancing; only the clock moves.
        let (code, s2) = frame(
            s,
            Input {
                now_ns: 1_000_000_000 + HOLD_MAX_NANOS + 1,
                tick: 10,
                attacking: false,
                ..input()
            },
        );
        assert_eq!(unpack_hold(code), HoldStatus::Release.code());
        assert_eq!(s2.hold_until_tick, -1);
    }

    #[test]
    fn releasing_forward_ends_the_hold_immediately() {
        let (_, s) = frame(State::new(), input());
        let (code, _) = frame(s, Input { forward: false, ..input() });
        assert_eq!(unpack_hold(code), HoldStatus::Release.code());
    }

    #[test]
    fn a_release_and_an_assert_can_share_one_frame() {
        // Hold armed on tick 10 (holdUntil 11); two dispatches later the
        // hold expires on the same frame a fresh attack lands.
        let (_, s) = frame(State::new(), input());
        let (code, s2) = frame(
            s,
            Input {
                tick: 12,
                now_ns: 1_000_000_000 + 30_000_000,
                ..input()
            },
        );
        assert_eq!(unpack_hold(code), HoldStatus::Live.code(), "re-armed, not released");
        assert_eq!(unpack_assert(code), AssertStep::Assert.code());
        assert_eq!(s2.hold_until_tick, 13, "the assert re-armed the hold");
    }

    #[test]
    fn a_stale_sprinting_sample_still_counts_via_the_latch() {
        // The sample was true on an earlier frame, then flipped false before
        // the attack frame — the latch must carry the sprint across.
        let (_, s) = frame(
            State::new(),
            Input {
                attacking: false,
                local_sprinting: true,
                ..input()
            },
        );
        assert_eq!(s.last_sprint_seen_ns, 1_000_000_000);
        let (code, _) = frame(
            s,
            Input {
                now_ns: 1_000_000_000 + 60_000_000,
                local_sprinting: false,
                ..input()
            },
        );
        assert_eq!(unpack_assert(code), AssertStep::Assert.code());
    }

    #[test]
    fn the_latch_expires_after_the_memory_window() {
        let (_, s) = frame(
            State::new(),
            Input {
                attacking: false,
                local_sprinting: true,
                ..input()
            },
        );
        let (code, s2) = frame(
            s,
            Input {
                now_ns: 1_000_000_000 + SPRINT_MEMORY_NANOS + 1,
                local_sprinting: false,
                ..input()
            },
        );
        assert_eq!(unpack_assert(code), AssertStep::None.code());
        assert_eq!(s2.last_handled_tick, -1, "the tick was not consumed");
    }

    #[test]
    fn a_false_sample_never_refreshes_the_latch() {
        let (_, s) = frame(
            State::new(),
            Input {
                attacking: false,
                local_sprinting: true,
                ..input()
            },
        );
        let (_, s2) = frame(
            s,
            Input {
                now_ns: 1_000_000_000 + 10_000_000,
                attacking: false,
                local_sprinting: false,
                ..input()
            },
        );
        assert_eq!(s2.last_sprint_seen_ns, 1_000_000_000, "unchanged");
    }

    #[test]
    fn a_player_who_never_sprinted_gets_no_assert() {
        let (code, _) = frame(
            State::new(),
            Input { local_sprinting: false, ..input() },
        );
        assert_eq!(code, 0);
    }

    #[test]
    fn a_tap_owning_w_blocks_the_assert() {
        let (code, _) = frame(
            State::new(),
            Input { forward_override: true, ..input() },
        );
        assert_eq!(unpack_assert(code), AssertStep::None.code());
        assert_eq!(unpack_hold(code), HoldStatus::Dead.code());
    }

    #[test]
    fn a_fresh_state_reports_dead_lets_the_adapter_self_heal() {
        // After a Reset (or a core restart) the hold is gone while the
        // adapter may still hold the claim — DEAD is the status that tells
        // it to let go.
        let (code, s) = frame(State::new(), Input { attacking: false, local_sprinting: false, ..input() });
        assert_eq!(unpack_hold(code), HoldStatus::Dead.code());
        assert_eq!(s, State::new());
    }

    #[test]
    fn codes_and_packing_round_trip() {
        for h in [HoldStatus::Dead, HoldStatus::Live, HoldStatus::Release] {
            assert_eq!(HoldStatus::from_code(h.code()), Some(h));
        }
        for a in [AssertStep::None, AssertStep::Assert] {
            assert_eq!(AssertStep::from_code(a.code()), Some(a));
        }
        assert_eq!(HoldStatus::from_code(3), None);
        assert_eq!(AssertStep::from_code(2), None);
        assert_eq!(unpack_hold(pack(HoldStatus::Release, AssertStep::Assert)), 2);
        assert_eq!(unpack_assert(pack(HoldStatus::Release, AssertStep::Assert)), 1);
    }
}
