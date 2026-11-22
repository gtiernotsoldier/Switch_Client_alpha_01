//! Izanagi — Mark & Recall: walk your own reported path back to a pinned frame.
//!
//! The trail is the client's own "what the server has seen" ring: every tick the
//! adapter reports a position the server can actually see (it is the position the
//! next movement packet declares), and the core keeps the last [CAPACITY] of them.
//! `mark_edge` pins the newest frame as the recall anchor; `recall_edge` starts a
//! **replay**: the server is walked back along the recorded path, one stored frame
//! per tick, in reverse order. Every step is physics-legal by construction — it is
//! a step the server already accepted once — so the replay needs no speed
//! computation at all (the clamped-speed machinery is the later migration; this
//! slice walks at exactly 1x).
//!
//! Storage is wide on purpose, but **usable depth is decided by the environment**,
//! never by the ring: `max_depth` is the fuse the adapter derives from the current
//! anti-cheat profile (Grim tolerates ~10-20 ticks), and an anchor older than it
//! is refused at recall. The anchor also carries a fuse of its own — when the ring
//! evicts the pinned tick, the pin is gone and MARKED falls back to IDLE.
//!
//! What the JVM keeps: the judgements (death, GUI, whether the server can see us —
//! false while Kamui holds the connection, because those frames are never sent),
//! the corridor check (collision + danger sources; a platform without a checker
//! fails closed), the key edges, and the platform write (set the player position
//! to the returned step). Everything stateful lives here: the ring, the pin, the
//! walk list, the stall counter and the cooldown.
//!
//! Arbitration with Kamui is core-internal by construction: the session dispatch
//! arm passes `kamui_busy = self.kamui.state == kamui::PHASE` — no JVM-side
//! cross-module reference exists, and a recall against a live phase is refused
//! here (both machines rewriting reported positions at once is the one outcome
//! this family must never produce).
//!
//! There is **no randomness** — no RNG stream. The machine is a pure function of
//! the inputs and the state.

use std::collections::VecDeque;

/// Ring capacity in ticks — ~6 s at 20 Hz. Wide by design; see the module comment
/// on usable depth.
pub const CAPACITY: usize = 120;

/// Machine states, as carried by the answer's flags on the wire.
pub const STATE_IDLE: u8 = 0;
pub const STATE_MARKED: u8 = 1;
pub const STATE_REPLAY: u8 = 2;
pub const STATE_COOLDOWN: u8 = 3;

/// Answer flag bits (the Decision's u16 flags field, low byte).
pub const FLAG_ADVANCE: u16 = 1;
pub const FLAG_STARTED: u16 = 2;
pub const FLAG_ABORTED: u16 = 4;
pub const FLAG_DONE: u16 = 8;

/// One server-seen position.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Frame {
    pub tick: i32,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub on_ground: bool,
}

/// What the core remembers between ticks.
#[derive(Clone, Debug)]
pub struct State {
    /// Server-seen frames, oldest first. Only fed while `feed` is true.
    pub frames: VecDeque<Frame>,
    /// The pinned frame's tick, or None. Dies when the ring evicts it.
    pub anchor_tick: Option<i32>,
    /// IDLE / MARKED / REPLAY / COOLDOWN. REPLAY is also "walk is non-empty".
    pub state: u8,
    /// The ticks to walk back to, in order (newest-first, anchor last). Empty
    /// unless REPLAY.
    pub walk: VecDeque<i32>,
    /// Consecutive ticks the corridor check rejected; reset on every advance.
    pub stall: i32,
    /// Ticks spent in the current cooldown.
    pub cooldown: i32,
    /// Completed recalls, for logs and the HUD.
    pub recalls: i32,
}

impl Default for State {
    fn default() -> State {
        State {
            frames: VecDeque::with_capacity(CAPACITY + 1),
            anchor_tick: None,
            state: STATE_IDLE,
            walk: VecDeque::new(),
            stall: 0,
            cooldown: 0,
            recalls: 0,
        }
    }
}

impl State {
    pub fn new() -> State {
        State::default()
    }
}

/// The judgements, edges and knobs the JVM marshals into one request per tick.
#[derive(Clone, Copy, Debug)]
pub struct Input {
    /// The adapter's "the server can see this position" judgement — false while
    /// Kamui holds the connection (those frames are never sent) and while no
    /// answer came back. Unfed ticks leave gaps in the trail; the walk goes by
    /// stored ticks, so gaps only shorten the usable path.
    pub feed: bool,
    pub tick_clock: i32,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub on_ground: bool,
    /// The mark key edge. Overwrites the pin (single anchor slot, overwrite制).
    pub mark_edge: bool,
    /// The recall key edge. Refused while a phase is live (kamui_busy), while
    /// the anchor is dead, while its age exceeds `max_depth`, and while a
    /// replay is already running.
    pub recall_edge: bool,
    /// The corridor judgement for the segment just applied — the adapter's
    /// collision + danger check. A platform without a checker fails closed and
    /// never marshals a recall at all; a false here stalls the walk.
    pub corridor_ok: bool,
    pub gui_open: bool,
    pub dead: bool,
    /// A Kamui phase is live — the one thing a recall must never race with.
    pub kamui_busy: bool,
    /// Usable depth fuse: an anchor older than this is refused at recall.
    pub max_depth: i32,
    /// How many stalled ticks an aborting replay tolerates before it stops.
    pub stall_cap: i32,
    pub cooldown_ticks: i32,
}

/// One tick's answer. `x/y/z` are the position to report this tick — valid only
/// when `flags` carries ADVANCE.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Answer {
    pub state: u8,
    pub flags: u16,
    /// Frames left in the walk after this tick's step (HUD countdown).
    pub remaining: i32,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

/// Record one server-seen frame. Oldest frames fall out past [CAPACITY]; the
/// pinned anchor dies when its tick is evicted (the fuse).
fn push_frame(s: &mut State, frame: Frame) {
    if s.frames.len() >= CAPACITY {
        s.frames.pop_front();
    }
    s.frames.push_back(frame);
    if let (Some(front), Some(pinned)) = (s.frames.front(), s.anchor_tick) {
        if front.tick > pinned {
            s.anchor_tick = None;
            // A MARKED machine whose anchor just burned falls back to IDLE — the
            // HUD must not advertise a recall that no longer has a target.
            if s.state == STATE_MARKED {
                s.state = STATE_IDLE;
            }
        }
    }
}

fn abort_replay(s: &mut State) {
    s.walk.clear();
    s.anchor_tick = None;
    s.stall = 0;
    s.state = STATE_COOLDOWN;
}

/// One client tick. Returns the answer and the new state.
///
/// The branch order is part of the behaviour: the trail is fed BEFORE the
/// machine reads the ring (a mark on the same tick pins the frame that tick
/// produced), and the safety overrides on a live replay run before the corridor
/// check (a dead player must not keep walking back).
pub fn tick(state: State, input: Input) -> (Answer, State) {
    let mut s = state;

    // ---- The trail: feed only what the server can actually see ----
    if input.feed {
        push_frame(
            &mut s,
            Frame {
                tick: input.tick_clock,
                x: input.x,
                y: input.y,
                z: input.z,
                on_ground: input.on_ground,
            },
        );
    }

    // ---- A mark pins the newest frame (IDLE or MARKED: single slot, overwrite) ----
    if input.mark_edge && s.state != STATE_REPLAY && s.state != STATE_COOLDOWN && !input.kamui_busy
    {
        if let Some(newest) = s.frames.back() {
            s.anchor_tick = Some(newest.tick);
            s.state = STATE_MARKED;
        }
    }

    // ---- A recall starts the replay ----
    let mut flags: u16 = 0;
    if input.recall_edge
        && s.state == STATE_MARKED
        && !input.kamui_busy
        && !input.gui_open
        && !input.dead
    {
        let refused = match (s.frames.back(), s.anchor_tick) {
            (Some(newest), Some(pinned)) => {
                let age = newest.tick - pinned;
                let idx = s.frames.iter().rposition(|f| f.tick == pinned);
                match idx {
                    // The anchor IS the newest frame: nothing to walk back.
                    Some(i) if i == s.frames.len() - 1 => true,
                    // The depth fuse: the environment decides how far back is
                    // usable, the ring is only storage.
                    Some(_) if age > input.max_depth => true,
                    Some(i) => {
                        // The walk: every stored frame from the anchor to just
                        // before the newest one, NEWEST-FIRST, anchor last. Each
                        // step is a frame the server already accepted once.
                        let ticks: Vec<i32> = s.frames.range(i..).map(|f| f.tick).collect();
                        s.walk = ticks[..ticks.len() - 1].iter().rev().copied().collect();
                        s.stall = 0;
                        s.recalls += 1;
                        s.state = STATE_REPLAY;
                        false
                    }
                    None => true, // evicted despite the fuse — fail closed
                }
            }
            _ => true,
        };
        if !refused {
            flags |= FLAG_STARTED;
            // The recall tick starts the replay but does not advance: the press
            // tick's own position is the one the player is standing in, and the
            // first walked step belongs to the next tick's packet.
            return (
                Answer {
                    state: STATE_REPLAY,
                    flags,
                    remaining: s.walk.len() as i32,
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                },
                s,
            );
        }
    }

    // ---- The replay: one stored frame per tick, corridor-checked ----
    if s.state == STATE_REPLAY {
        // Safety overrides first, in the order they are allowed to fire.
        if input.dead || input.gui_open {
            // Stop where the walk is: the body stays mid-corridor — no snap, no
            // synthetic jump. A dead player owes no cooldown but keeps one anyway:
            // simpler, and the respawn resets the family through the Reset frame.
            abort_replay(&mut s);
            flags |= FLAG_ABORTED;
        } else if !input.corridor_ok {
            s.stall += 1;
            if s.stall >= input.stall_cap {
                abort_replay(&mut s);
                flags |= FLAG_ABORTED;
            }
            // else: no advance this tick — wait for the corridor to clear.
        } else {
            s.stall = 0;
            match s.walk.pop_front() {
                Some(t) => match s.frames.iter().rev().find(|f| f.tick == t) {
                    Some(f) => {
                        flags |= FLAG_ADVANCE;
                        let (x, y, z) = (f.x, f.y, f.z);
                        if s.walk.is_empty() {
                            // The anchor frame was the last step: the body is back.
                            s.state = STATE_COOLDOWN;
                            s.anchor_tick = None;
                            s.cooldown = 0;
                            flags |= FLAG_DONE;
                        }
                        let ans = Answer {
                            state: s.state,
                            flags,
                            remaining: s.walk.len() as i32,
                            x,
                            y,
                            z,
                        };
                        return (ans, s);
                    }
                    // The walk target vanished from the ring — the fuse that
                    // must never be needed, kept because a missing frame means
                    // a path the server can no longer reconstruct.
                    None => {
                        abort_replay(&mut s);
                        flags |= FLAG_ABORTED;
                    }
                },
                None => {
                    // REPLAY with an empty walk cannot happen (recall enters with
                    // a non-empty walk and done empties it) — treat as abort.
                    abort_replay(&mut s);
                    flags |= FLAG_ABORTED;
                }
            }
        }
    }

    // ---- Cooldown ----
    if s.state == STATE_COOLDOWN {
        s.cooldown += 1;
        if s.cooldown >= input.cooldown_ticks {
            s.state = STATE_IDLE;
            s.cooldown = 0;
        }
    }

    let state_for_answer = if s.state == STATE_REPLAY { STATE_REPLAY } else { s.state };
    (
        Answer {
            state: state_for_answer,
            flags,
            remaining: s.walk.len() as i32,
            x: 0.0,
            y: 0.0,
            z: 0.0,
        },
        s,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> Input {
        Input {
            feed: true,
            tick_clock: 0,
            x: 0.0,
            y: 64.0,
            z: 0.0,
            on_ground: true,
            mark_edge: false,
            recall_edge: false,
            corridor_ok: true,
            gui_open: false,
            dead: false,
            kamui_busy: false,
            max_depth: 20,
            stall_cap: 5,
            cooldown_ticks: 300,
        }
    }

    /// Feed `n` ticks of diagonal movement starting at tick `from`.
    fn walk_in(s: State, from: i32, n: i32, step: f64) -> State {
        let mut s = s;
        for i in 0..n {
            let (_, next) = tick(
                s,
                Input {
                    tick_clock: from + i,
                    x: (i as f64) * step,
                    z: (i as f64) * step,
                    ..input()
                },
            );
            s = next;
        }
        s
    }

    #[test]
    fn mark_pins_the_newest_frame_and_the_anchor_fuse_burns() {
        let s = walk_in(State::new(), 0, 10, 0.1);
        // Unfed mark tick: the pin lands on the newest EXISTING frame (tick 9) —
        // the tick the mark itself happens on was never reported.
        let (_, s) = tick(s, Input { tick_clock: 10, feed: false, mark_edge: true, ..input() });
        assert_eq!(s.anchor_tick, Some(9));

        // 130 more fed ticks: the pinned tick (9) falls out of the ring and the
        // pin burns — MARKED falls back to IDLE.
        let s = walk_in(s, 11, 130, 0.1);
        assert_eq!(s.anchor_tick, None);
        assert_eq!(s.state, STATE_IDLE);
    }

    #[test]
    fn a_mark_overwrites_the_pin() {
        let s = walk_in(State::new(), 0, 10, 0.1);
        let (_, s) = tick(s, Input { tick_clock: 10, feed: false, mark_edge: true, ..input() });
        let s = walk_in(s, 11, 5, 0.1);
        let (_, s) = tick(s, Input { tick_clock: 16, feed: false, mark_edge: true, ..input() });
        assert_eq!(s.anchor_tick, Some(15), "single anchor slot, overwrite");
        assert_eq!(s.state, STATE_MARKED);
    }

    #[test]
    fn recall_walks_back_one_stored_frame_per_tick_and_ends_in_cooldown() {
        let s = walk_in(State::new(), 0, 12, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 12, feed: false, mark_edge: true, ..input() });
        assert_eq!(s.anchor_tick, Some(11));
        let s = walk_in(s, 12, 4, 1.0); // ticks 12..15, now at x=15
        let (a, s) = tick(s, Input { tick_clock: 16, feed: false, recall_edge: true, ..input() });
        assert_eq!(a.state, STATE_REPLAY);
        assert_ne!(a.flags & FLAG_STARTED, 0, "the recall tick starts, not advances");
        assert_eq!(a.flags & FLAG_ADVANCE, 0);
        assert_eq!(s.walk, {
            let mut v = VecDeque::new();
            for t in (11..15).rev() {
                v.push_back(t);
            }
            v
        }, "newest-first, anchor last");

        // Four advance ticks, newest-first: 14, 13, 12, 11. The helper resets x per
        // call, so the expectations read the stored frames literally.
        let mut s2 = s;
        let mut positions = Vec::new();
        for t in 16..20 {
            let (a, next) = tick(s2, Input { tick_clock: t, x: 15.0, z: 15.0, feed: false, ..input() });
            assert_ne!(a.flags & FLAG_ADVANCE, 0, "tick {} advances", t);
            positions.push((a.x, a.z));
            s2 = next;
        }
        assert_eq!(positions[0], (2.0, 2.0), "tick 14's stored frame");
        assert_eq!(positions[1], (1.0, 1.0), "tick 13");
        assert_eq!(positions[2], (0.0, 0.0), "tick 12");
        assert_eq!(positions[3], (11.0, 11.0), "the anchor frame (tick 11) is the last step");
        assert!(s2.walk.is_empty(), "the anchor step consumed the walk");
        assert_eq!(s2.state, STATE_COOLDOWN);
    }

    #[test]
    fn a_refused_recall_keeps_the_mark() {
        let s = walk_in(State::new(), 0, 10, 0.1);
        let (_, s) = tick(s, Input { tick_clock: 10, feed: false, mark_edge: true, ..input() });
        // Out of depth: the anchor is 9 ticks old but max_depth is 5.
        let s = walk_in(s, 10, 9, 0.1); // ticks 10..18, newest = 18, age = 9
        let (a, s) = tick(s, Input { tick_clock: 19, feed: false, recall_edge: true, max_depth: 5, ..input() });
        assert_eq!(a.flags & FLAG_STARTED, 0, "the depth fuse refuses");
        assert_eq!(s.state, STATE_MARKED, "the mark survives a refusal");
        assert!(s.walk.is_empty());
    }

    #[test]
    fn a_recall_against_a_live_kamui_phase_is_refused() {
        let s = walk_in(State::new(), 0, 10, 0.1);
        let (_, s) = tick(s, Input { tick_clock: 10, feed: false, mark_edge: true, ..input() });
        let (a, s) = tick(s, Input { tick_clock: 11, feed: false, recall_edge: true, kamui_busy: true, ..input() });
        assert_eq!(a.flags & FLAG_STARTED, 0, "the phase owns the reported position");
        assert_eq!(s.state, STATE_MARKED);
        // And a mark during a phase is refused too — both machines never race.
        let s = walk_in(s, 12, 2, 0.1);
        let (_, s) = tick(s, Input { tick_clock: 14, feed: false, mark_edge: true, kamui_busy: true, ..input() });
        assert_eq!(s.state, STATE_MARKED, "the old pin survives");
        assert_eq!(s.anchor_tick, Some(9));
    }

    #[test]
    fn corridor_rejections_stall_then_abort() {
        let s = walk_in(State::new(), 0, 12, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 12, feed: false, mark_edge: true, ..input() });
        let s = walk_in(s, 12, 4, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 16, feed: false, recall_edge: true, ..input() });
        // Five blocked ticks: stall_cap = 5 → abort on the fifth.
        let mut s2 = s;
        for t in 16..20 {
            let (a, next) = tick(s2, Input { tick_clock: t, feed: false, corridor_ok: false, ..input() });
            assert_eq!(a.flags & FLAG_ADVANCE, 0, "a blocked tick does not advance");
            s2 = next;
        }
        let (a, s3) = tick(s2, Input { tick_clock: 20, feed: false, corridor_ok: false, ..input() });
        assert_ne!(a.flags & FLAG_ABORTED, 0, "stall_cap reached — the walk stops");
        assert_eq!(s3.state, STATE_COOLDOWN);
        assert!(s3.walk.is_empty());
    }

    #[test]
    fn gui_or_death_mid_replay_stops_where_the_walk_is() {
        let s = walk_in(State::new(), 0, 12, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 12, feed: false, mark_edge: true, ..input() });
        let s = walk_in(s, 12, 4, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 16, feed: false, recall_edge: true, ..input() });
        let (a, s) = tick(s, Input { tick_clock: 17, feed: false, gui_open: true, ..input() });
        assert_ne!(a.flags & FLAG_ABORTED, 0);
        assert_eq!(s.state, STATE_COOLDOWN);
        assert!(s.walk.is_empty());
    }

    #[test]
    fn the_cooldown_releases_back_to_idle() {
        let s = walk_in(State::new(), 0, 12, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 12, feed: false, mark_edge: true, ..input() });
        // Movement after the mark: without it the anchor IS the newest frame and
        // the recall is refused (nothing to walk back).
        let s = walk_in(s, 12, 3, 1.0);
        let (_, s) = tick(s, Input { tick_clock: 15, feed: false, recall_edge: true, cooldown_ticks: 10, ..input() });
        assert_eq!(s.state, STATE_REPLAY, "the recall started");
        let mut s = s;
        for t in 15..40 {
            let (_, next) = tick(s, Input { tick_clock: t, feed: false, cooldown_ticks: 10, ..input() });
            s = next;
        }
        assert_eq!(s.state, STATE_IDLE, "a short cooldown ends and re-arms");
    }

    #[test]
    fn unfed_ticks_leave_gaps_and_the_walk_still_lands_on_stored_ticks() {
        // Feed every other tick: the trail carries only even ticks.
        let mut s = State::new();
        for i in 0..12 {
            let (_, next) = tick(
                s,
                Input {
                    tick_clock: i,
                    feed: i % 2 == 0,
                    x: (i as f64) * 0.5,
                    z: (i as f64) * 0.5,
                    ..input()
                },
            );
            s = next;
        }
        // Unfed mark: the pin lands on the newest stored (even) tick.
        let (_, s) = tick(s, Input { tick_clock: 12, feed: false, mark_edge: true, ..input() });
        let s = walk_in(s, 12, 2, 0.5); // even tick 12 is fed again
        let (_, s) = tick(s, Input { tick_clock: 14, feed: false, recall_edge: true, ..input() });
        // The walk targets only stored (even) ticks.
        for t in s.walk.iter() {
            assert_eq!(t % 2, 0, "walk targets must be stored ticks");
        }
        assert!(!s.walk.is_empty());
    }
}
