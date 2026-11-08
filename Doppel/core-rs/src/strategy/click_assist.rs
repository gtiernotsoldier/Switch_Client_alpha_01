//! Port of `ClickAssist` — extra clicks bolted onto a physical one.
//!
//! The 500 ms click history is core state now. It has to be: "above 5 CPS" is a
//! gate on *this* click, and the rate is measured over the module's own edge
//! history, which a stateless call could not see.
//!
//! The gates read in a fixed order, and several of them return without spending
//! the chance roll. That order is preserved exactly — a port that rolls first
//! uses up RNG draws on clicks that never had a chance, and the 5 CPS estimate
//! then disagrees with the in-process version on the very next click.

use std::collections::VecDeque;

use crate::rng::Pcg32;

/// How many extra clicks a boosted press produces.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Mode {
    /// One extra click — Raven's behaviour.
    Normal = 0,
    /// Two extra clicks.
    Double = 1,
    /// Zero to two extra clicks, drawn.
    Random = 2,
}

impl Mode {
    pub fn from_u8(v: u8) -> Mode {
        match v {
            1 => Mode::Double,
            2 => Mode::Random,
            _ => Mode::Normal,
        }
    }
}

/// One bit per predicate, so the whole request fits in a handful of slots.
pub mod flags {
    pub const RIGHT_BUTTON: u32 = 1 << 0;
    pub const GUI_OPEN: u32 = 1 << 1;
    pub const USING_ITEM: u32 = 1 << 2;
    pub const BLOCKING: u32 = 1 << 3;
    pub const IS_WEAPON: u32 = 1 << 4;
    pub const HAS_TARGET: u32 = 1 << 5;
    pub const LOOKING_AT_BLOCK: u32 = 1 << 6;
    pub const LEFT_ENABLED: u32 = 1 << 7;
    pub const RIGHT_ENABLED: u32 = 1 << 8;
    pub const WEAPONS_ONLY: u32 = 1 << 9;
    pub const ONLY_AIMING_ENTITY: u32 = 1 << 10;
    pub const ONLY_BLOCKS: u32 = 1 << 11;
    pub const ABOVE_5_CPS: u32 = 1 << 12;
}

/// Physical click edges inside the last 500 ms.
pub struct State {
    /// Oldest first. Bounded by the timeout, not by a fixed size: a player who
    /// clicks 20 CPS for half a second has 10 entries, and the sweep below keeps
    /// it that way.
    pub recent: VecDeque<i64>,
}

impl State {
    pub fn new() -> State {
        State {
            recent: VecDeque::with_capacity(16),
        }
    }
}

impl Default for State {
    fn default() -> Self {
        State::new()
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub now_ns: i64,
    pub flags: u32,
    /// Percent; 100 means always.
    pub probability: i32,
    pub mode: Mode,
}

const WINDOW_NS: i64 = 500_000_000;

/// One physical press. Returns the number of extra clicks (0, 1 or 2).
pub fn evaluate(state: &mut State, input: Input, rng: &mut Pcg32) -> i32 {
    // The edge is recorded first, before any gate: the 5 CPS estimate is about
    // what the player is doing, not about whether this press got boosted.
    state.recent.push_back(input.now_ns);
    while let Some(&first) = state.recent.front() {
        if first < input.now_ns - WINDOW_NS {
            state.recent.pop_front();
        } else {
            break;
        }
    }

    let f = input.flags;

    // A screen is open, or the player is consuming/blocking an item: never assist.
    if f & flags::GUI_OPEN != 0 || f & flags::USING_ITEM != 0 || f & flags::BLOCKING != 0 {
        return 0;
    }

    if f & flags::RIGHT_BUTTON != 0 {
        if f & flags::RIGHT_ENABLED == 0 {
            return 0;
        }
        if f & flags::ONLY_BLOCKS != 0 && f & flags::LOOKING_AT_BLOCK == 0 {
            return 0;
        }
    } else {
        if f & flags::LEFT_ENABLED == 0 {
            return 0;
        }
        if f & flags::WEAPONS_ONLY != 0 && f & flags::IS_WEAPON == 0 {
            return 0;
        }
        if f & flags::ONLY_AIMING_ENTITY != 0 && f & flags::HAS_TARGET == 0 {
            return 0;
        }
    }

    if f & flags::ABOVE_5_CPS != 0 && estimate_cps(&state.recent, input.now_ns) < 5.0 {
        return 0;
    }

    if input.probability < 100 && rng.random_int(0, 99) >= input.probability {
        return 0;
    }

    match input.mode {
        Mode::Double => 2,
        Mode::Random => rng.random_int(0, 2),
        Mode::Normal => 1,
    }
}

/// Manual CPS from the recorded edges.
fn estimate_cps(recent: &VecDeque<i64>, now_ns: i64) -> f32 {
    if recent.len() < 2 {
        return 0.0;
    }
    let window_ns = now_ns - recent[0];
    if window_ns <= 0 {
        return 0.0;
    }
    (recent.len() - 1) as f32 / (window_ns as f32 / 1_000_000_000.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: i64 = 1_000_000;

    fn plain(now_ns: i64) -> Input {
        Input {
            now_ns,
            flags: flags::LEFT_ENABLED,
            probability: 100,
            mode: Mode::Normal,
        }
    }

    fn rng() -> Pcg32 {
        Pcg32::new(3)
    }

    #[test]
    fn a_plain_left_press_adds_one_click() {
        let mut s = State::new();
        let mut r = rng();
        assert_eq!(evaluate(&mut s, plain(0), &mut r), 1);
    }

    #[test]
    fn double_adds_two_and_random_stays_in_range() {
        let mut s = State::new();
        let mut r = rng();
        assert_eq!(evaluate(&mut s, Input { mode: Mode::Double, ..plain(0) }, &mut r), 2);

        let mut s = State::new();
        for i in 0..32 {
            let v = evaluate(
                &mut s,
                Input { now_ns: i * 1000 * MS, mode: Mode::Random, ..plain(0) },
                &mut r,
            );
            assert!((0..=2).contains(&v), "random produced {}", v);
        }
    }

    #[test]
    fn an_open_screen_disables_everything() {
        let mut s = State::new();
        let mut r = rng();
        let gui = Input { flags: flags::LEFT_ENABLED | flags::GUI_OPEN, ..plain(0) };
        assert_eq!(evaluate(&mut s, gui, &mut r), 0);
    }

    #[test]
    fn eating_or_blocking_disables_everything() {
        let mut s = State::new();
        let mut r = rng();
        let eating = Input { flags: flags::LEFT_ENABLED | flags::USING_ITEM, ..plain(0) };
        assert_eq!(evaluate(&mut s, eating, &mut r), 0);
        let blocking = Input { flags: flags::LEFT_ENABLED | flags::BLOCKING, ..plain(1000) };
        assert_eq!(evaluate(&mut s, blocking, &mut r), 0);
    }

    #[test]
    fn weapons_only_rejects_a_non_weapon() {
        let mut s = State::new();
        let mut r = rng();
        let bare = Input { flags: flags::LEFT_ENABLED | flags::WEAPONS_ONLY, ..plain(0) };
        assert_eq!(evaluate(&mut s, bare, &mut r), 0);

        let sword = Input {
            flags: flags::LEFT_ENABLED | flags::WEAPONS_ONLY | flags::IS_WEAPON,
            ..plain(1000)
        };
        assert_eq!(evaluate(&mut s, sword, &mut r), 1);
    }

    #[test]
    fn right_click_respects_its_own_toggles() {
        let mut s = State::new();
        let mut r = rng();
        let disabled = Input { flags: flags::RIGHT_BUTTON, ..plain(0) };
        assert_eq!(evaluate(&mut s, disabled, &mut r), 0, "right click is off by default");

        let on_block = Input {
            flags: flags::RIGHT_BUTTON | flags::RIGHT_ENABLED | flags::ONLY_BLOCKS | flags::LOOKING_AT_BLOCK,
            ..plain(1000)
        };
        assert_eq!(evaluate(&mut s, on_block, &mut r), 1);

        let on_air = Input {
            flags: flags::RIGHT_BUTTON | flags::RIGHT_ENABLED | flags::ONLY_BLOCKS,
            ..plain(2000)
        };
        assert_eq!(evaluate(&mut s, on_air, &mut r), 0);
    }

    #[test]
    fn the_cps_gate_reads_its_own_edge_history() {
        let mut s = State::new();
        let mut r = rng();

        // Two clicks 400 ms apart: 2.5 CPS, below the gate.
        let slow = Input { flags: flags::LEFT_ENABLED | flags::ABOVE_5_CPS, ..plain(0) };
        assert_eq!(evaluate(&mut s, slow, &mut r), 0, "first click has no rate yet");
        assert_eq!(
            evaluate(&mut s, Input { now_ns: 400 * MS, ..slow }, &mut r),
            0,
            "2.5 CPS is below five"
        );

        // A burst: six clicks in 200 ms is far above five.
        let mut s2 = State::new();
        let burst = Input { flags: flags::LEFT_ENABLED | flags::ABOVE_5_CPS, ..plain(0) };
        evaluate(&mut s2, burst, &mut r);
        for i in 1..6 {
            let v = evaluate(&mut s2, Input { now_ns: i * 40 * MS, ..burst }, &mut r);
            if i == 5 {
                assert_eq!(v, 1, "the fifth click in 160 ms is well above five CPS");
            }
        }
    }

    #[test]
    fn the_probability_roll_can_reject() {
        let mut s = State::new();
        let mut r = rng();
        let never = Input { probability: 0, ..plain(0) };
        assert_eq!(evaluate(&mut s, never, &mut r), 0);

        let mut s2 = State::new();
        let always = Input { probability: 100, ..plain(0) };
        assert_eq!(evaluate(&mut s2, always, &mut r), 1);
    }

    #[test]
    fn the_edge_history_is_trimmed_to_half_a_second() {
        let mut s = State::new();
        let mut r = rng();
        for i in 0..10 {
            evaluate(&mut s, plain(i * 100 * MS), &mut r);
        }
        assert!(s.recent.len() <= 6, "history kept {} entries", s.recent.len());
    }
}
