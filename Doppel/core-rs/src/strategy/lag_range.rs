//! LagRange — hold your own packets while closing in, so the server keeps
//! computing positions from where you were a moment ago. The whole hold
//! decision machine lives in the core.
//!
//! The JVM keeps only judgements and platform I/O: the GUI-open check, the
//! target's distance sample, the weapon being a sword, the player snapshot
//! fields (hurt time, sprinting, blocking), marshalling the knobs
//! (range / maxDelay / the three condition toggles), and the single
//! `packetLagActive` write. Everything stateful lives here: the previous
//! distance (the "still closing in?" comparison), the damage edge, the
//! sprint and block edges, and the hold's start timestamp.
//!
//! Every flush condition from the reference port is preserved in the
//! original order: the target leaving range or the GUI opening, damage
//! taken, a sprint starting, a block starting, the weapon leaving the
//! hand, the distance no longer shrinking, and `max_delay_ms` at the
//! latest. There is **no randomness** — no RNG stream exists for this
//! module — so the answer is a pure function of the inputs and the state.
//!
//! The answer is one step code: `HOLD` keeps the packet lag on, `ARM` is
//! the first closing tick (the adapter also flashes the HUD — a hold
//! starting is a transition, like every other module's work), `FLUSH`
//! drops it. Every terminal path in the original wrote one of those two
//! flags, so `NONE` is reserved for wire discipline and never emitted.

/// What the core remembers between ticks.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct State {
    /// Previous tick's distance; -1 when there is nothing to compare
    /// against (no target, out of range, or a fresh enable).
    pub last_dist: f32,
    /// Previous tick's hurt time; a *rise* is a fresh damage event.
    pub last_self_hurt: i32,
    /// Previous tick's sprint flag (only tracked while `sprint_reset` is on).
    pub last_sprint: bool,
    /// Previous tick's blocking flag (only tracked while `block_sword` is on).
    pub last_blocking: bool,
    /// When the current hold started; 0 when not holding.
    pub hold_start_ns: i64,
}

impl Default for State {
    fn default() -> State {
        State {
            last_dist: -1.0,
            last_self_hurt: 0,
            last_sprint: false,
            last_blocking: false,
            hold_start_ns: 0,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// What the module should write. The numeric code is the wire value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    /// No platform write. Never produced by a valid input sequence; kept so
    /// an unknown code has somewhere to degrade to.
    None,
    /// `packetLagActive = true` — extend the hold.
    Hold,
    /// `packetLagActive = true` + HUD flash — the hold just started.
    Arm,
    /// `packetLagActive = false` — let everything go.
    Flush,
}

impl Step {
    pub fn code(self) -> u8 {
        match self {
            Step::None => 0,
            Step::Hold => 1,
            Step::Arm => 2,
            Step::Flush => 3,
        }
    }

    pub fn from_code(c: u8) -> Option<Step> {
        Some(match c {
            0 => Step::None,
            1 => Step::Hold,
            2 => Step::Arm,
            3 => Step::Flush,
            _ => return None,
        })
    }
}

/// The judgements and knobs the JVM marshals into one request per tick.
///
/// `dist` and `range` ride as f64 on the wire but both originate as Kotlin
/// `Float`s, so the machine narrows them back to f32 and compares in f32 —
/// the original compared `Float` against `Float`, and anything else would
/// be a (tiny, but real) behaviour change visible to a bit-exact replay.
#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub now_ns: i64,
    pub gui_open: bool,
    /// `target?.distance != null`.
    pub has_dist: bool,
    pub dist: f64,
    pub range: f64,
    pub max_delay_ms: i32,
    /// Config: flush when a sprint starts.
    pub sprint_reset_on: bool,
    pub is_sprinting: bool,
    /// Config: flush when a block starts.
    pub block_sword_on: bool,
    pub is_blocking: bool,
    /// Config: flush when the held item stops being a sword.
    pub holding_weapon_on: bool,
    /// Judgement: `player.weaponType == WeaponType.SWORD`.
    pub weapon_is_sword: bool,
    pub hurt_time: i32,
}

/// One client tick. Returns a step code and the new state.
///
/// The branch order mirrors the original `onTick` exactly: every early
/// return flushed *and* still updated the edge trackers it had touched, so
/// a port that reorders them changes which edge fires on the tick after a
/// flush.
pub fn tick(state: State, input: Input) -> (i32, State) {
    let mut s = state;
    // f64 -> f32 is exact here: both values were f32 on the JVM.
    let dist = input.dist as f32;
    let range = input.range as f32;

    // ---- Gate: GUI open, no target, or out of range ----
    if input.gui_open || !input.has_dist || dist > range {
        s.hold_start_ns = 0;
        s.last_dist = -1.0;
        return (Step::Flush.code() as i32, s);
    }

    // ---- Damage taken: never held through it ----
    if input.hurt_time > s.last_self_hurt {
        s.last_self_hurt = input.hurt_time;
        s.hold_start_ns = 0;
        s.last_dist = dist;
        return (Step::Flush.code() as i32, s);
    }
    s.last_self_hurt = input.hurt_time;

    // ---- A sprint starting: the server has to see the transition ----
    if input.sprint_reset_on {
        if input.is_sprinting && !s.last_sprint {
            s.last_sprint = true;
            s.hold_start_ns = 0;
            s.last_dist = dist;
            return (Step::Flush.code() as i32, s);
        }
        s.last_sprint = input.is_sprinting;
    }

    // ---- A block starting: same reasoning ----
    if input.block_sword_on {
        if input.is_blocking && !s.last_blocking {
            s.last_blocking = true;
            s.hold_start_ns = 0;
            s.last_dist = dist;
            return (Step::Flush.code() as i32, s);
        }
        s.last_blocking = input.is_blocking;
    }

    // ---- Weapon dropped ----
    if input.holding_weapon_on && !input.weapon_is_sword {
        s.hold_start_ns = 0;
        s.last_dist = dist;
        return (Step::Flush.code() as i32, s);
    }

    // ---- Not closing in any more: the delay buys nothing ----
    if s.last_dist >= 0.0 && dist >= s.last_dist {
        s.hold_start_ns = 0;
        s.last_dist = dist;
        return (Step::Flush.code() as i32, s);
    }
    s.last_dist = dist;

    // ---- Closing in: arm or extend the hold ----
    let step = if s.hold_start_ns == 0 {
        s.hold_start_ns = input.now_ns;
        Step::Arm
    } else {
        Step::Hold
    };

    // Bounded even if nothing above fired. The original armed/extended
    // first and then checked the deadline, so the deadline tick's net write
    // is "lag off" — `Arm` can never flush on its own tick (maxDelay >= 50
    // ms > 0).
    if input.now_ns - s.hold_start_ns >= input.max_delay_ms as i64 * 1_000_000 {
        s.hold_start_ns = 0;
        return (Step::Flush.code() as i32, s);
    }

    (step.code() as i32, s)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> Input {
        Input {
            now_ns: 1_000_000_000,
            gui_open: false,
            has_dist: true,
            dist: 3.5,
            range: 4.0,
            max_delay_ms: 200,
            sprint_reset_on: true,
            is_sprinting: false,
            block_sword_on: true,
            is_blocking: false,
            holding_weapon_on: true,
            weapon_is_sword: true,
            hurt_time: 0,
        }
    }

    fn tick(s: State, i: Input) -> (i32, State) {
        super::tick(s, i)
    }

    #[test]
    fn a_fresh_closing_tick_arms_the_hold() {
        let (code, s) = tick(State::new(), input());
        assert_eq!(code, Step::Arm.code() as i32);
        assert_eq!(s.hold_start_ns, 1_000_000_000);
        assert_eq!(s.last_dist, 3.5);
    }

    #[test]
    fn further_closing_ticks_hold_and_the_deadline_flushes() {
        let (_, mut s) = tick(State::new(), input());
        // Still closing: 3.5 -> 3.0.
        let (code, next) = tick(
            s,
            Input {
                now_ns: 1_000_000_000 + 50_000_000,
                dist: 3.0,
                ..input()
            },
        );
        assert_eq!(code, Step::Hold.code() as i32);
        s = next;
        // maxDelay = 200 ms: at +250 ms the hold must flush even though the
        // distance is still shrinking.
        let (code, s2) = tick(
            s,
            Input {
                now_ns: 1_000_000_000 + 250_000_000,
                dist: 2.0,
                ..input()
            },
        );
        assert_eq!(code, Step::Flush.code() as i32);
        assert_eq!(s2.hold_start_ns, 0);
        // And the hold re-arms on the next closing tick.
        let (code, _) = tick(
            s2,
            Input {
                now_ns: 1_000_000_000 + 300_000_000,
                dist: 1.5,
                ..input()
            },
        );
        assert_eq!(code, Step::Arm.code() as i32);
    }

    #[test]
    fn the_gate_flushes_and_forgets_the_distance() {
        let (_, s) = tick(State::new(), input());
        for (gui, has) in [(true, true), (false, false)] {
            let (code, s2) = tick(
                s,
                Input {
                    gui_open: gui,
                    has_dist: has,
                    ..input()
                },
            );
            assert_eq!(code, Step::Flush.code() as i32);
            assert_eq!(s2.last_dist, -1.0);
            assert_eq!(s2.hold_start_ns, 0);
        }
        // Out of range too.
        let (code, s3) = tick(s, Input { dist: 4.5, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
        assert_eq!(s3.last_dist, -1.0);
    }

    #[test]
    fn damage_is_never_held_through() {
        let (_, s) = tick(State::new(), input());
        let (code, s2) = tick(s, Input { hurt_time: 10, dist: 3.0, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
        assert_eq!(s2.last_self_hurt, 10);
        // The damage tick's distance becomes the comparison base.
        assert_eq!(s2.last_dist, 3.0);
        // Hurt time counting down is not a new hit.
        let (code, s3) = tick(s2, Input { hurt_time: 9, dist: 2.5, ..input() });
        assert_eq!(code, Step::Arm.code() as i32);
        // A new hit rises above the tracked (counted-down) value.
        let (code, _) = tick(s3, Input { hurt_time: 10, dist: 2.0, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
    }

    #[test]
    fn a_sprint_starting_flushes_only_while_the_option_is_on() {
        let (_, s) = tick(State::new(), input());
        let (code, s2) = tick(s, Input { is_sprinting: true, dist: 3.0, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
        assert!(s2.last_sprint);
        // Still sprinting: not an edge any more.
        let (code, s3) = tick(s2, Input { is_sprinting: true, dist: 2.5, ..input() });
        assert_eq!(code, Step::Arm.code() as i32);
        // Sprint stops (falling edge: tracked, not a flush), then starts
        // again — the second rising edge must flush with the option on.
        let (code, s4) = tick(s3, Input { is_sprinting: false, dist: 2.0, ..input() });
        assert_eq!(code, Step::Hold.code() as i32, "a falling edge just extends the hold");
        let (code, _) = tick(s4, Input { is_sprinting: true, dist: 1.5, ..input() });
        let off = Input {
            sprint_reset_on: false,
            is_sprinting: true,
            dist: 1.5,
            ..input()
        };
        let (code_off, _) = tick(State::new(), off);
        assert_eq!(code, Step::Flush.code() as i32, "with the option on the edge flushed");
        assert_eq!(code_off, Step::Arm.code() as i32, "with the option off a sprint is ignored");
    }

    #[test]
    fn a_block_starting_flushes_only_while_the_option_is_on() {
        let (_, s) = tick(State::new(), input());
        let (code, s2) = tick(s, Input { is_blocking: true, dist: 3.0, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
        assert!(s2.last_blocking);
        let off = Input {
            block_sword_on: false,
            is_blocking: true,
            ..input()
        };
        let (code_off, _) = tick(State::new(), off);
        assert_eq!(code_off, Step::Arm.code() as i32);
    }

    #[test]
    fn dropping_the_sword_flushes_only_while_the_option_is_on() {
        let (_, s) = tick(State::new(), input());
        let (code, _) = tick(s, Input { weapon_is_sword: false, dist: 3.0, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
        let off = Input {
            holding_weapon_on: false,
            weapon_is_sword: false,
            ..input()
        };
        let (code_off, _) = tick(State::new(), off);
        assert_eq!(code_off, Step::Arm.code() as i32);
    }

    #[test]
    fn a_target_that_stops_closing_flushes() {
        let (_, s) = tick(State::new(), input());
        // Equal distance: not shrinking.
        let (code, s2) = tick(s, Input { dist: 3.5, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
        assert_eq!(s2.last_dist, 3.5);
        // Growing distance: same.
        let (code, _) = tick(s2, Input { dist: 3.6, ..input() });
        assert_eq!(code, Step::Flush.code() as i32);
    }

    #[test]
    fn the_first_tick_after_enable_has_no_base_and_arms() {
        // lastDist = -1: the closing check is skipped, so even an equal or
        // growing distance arms on the first tick.
        let (code, _) = tick(State::new(), Input { dist: 4.0, ..input() });
        assert_eq!(code, Step::Arm.code() as i32);
    }

    #[test]
    fn codes_round_trip() {
        for s in [Step::None, Step::Hold, Step::Arm, Step::Flush] {
            assert_eq!(Step::from_code(s.code()), Some(s));
        }
        assert_eq!(Step::from_code(4), None);
    }
}
