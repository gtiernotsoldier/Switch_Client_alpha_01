//! Port of `EagleLogic` — Raven's SafeWalk sneak branch.
//!
//! The machine is `(state, input) -> (decision, state)`: no I/O, no clock, and
//! randomness only through the `roll` closure, which is called **only** when a
//! fresh edge press arms the hold delay. Keeping that call conditional is what
//! keeps the module's RNG stream from advancing while it is idle — the Kotlin
//! version had the same rule, and a 20 tick replay depends on it.

/// The two things this machine remembers between ticks.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct State {
    /// Whether the sneak currently held was pressed by this module.
    pub auto_sneak_active: bool,
    /// Ticks left to keep sneaking after leaving the edge.
    pub unsneak_delay_ticks: i32,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Input {
    /// `on_ground && is_edge_of_block()` — the block under the feet is air.
    pub on_edge: bool,
    /// The module's pitch gate (`OnlyWhenLookingDown`).
    pub settings_met: bool,
    /// The player is physically holding the sneak key (a manual sneak we must not fight).
    pub sneak_key_held: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Decision {
    /// Write the sneak key down this tick.
    pub press: bool,
    /// Write the sneak key up this tick.
    pub release: bool,
}

/// One tick of the sneak state machine.
///
/// Mirrors the order of `EagleLogic.tick`: the settings gate wins over
/// everything, then the edge, then the countdown.
pub fn tick(
    state: State,
    input: Input,
    roll: &mut impl FnMut() -> i32,
) -> (Decision, State) {
    // Gate off → let go immediately, but never release a key the player holds.
    if !input.settings_met {
        return (
            Decision {
                press: false,
                release: state.auto_sneak_active && !input.sneak_key_held,
            },
            State::default(),
        );
    }

    if input.on_edge {
        // Already holding our own sneak — nothing to write.
        if state.auto_sneak_active {
            return (Decision::default(), state);
        }
        // The player is sneaking on their own: hands off, never take ownership.
        if input.sneak_key_held {
            return (Decision::default(), State::default());
        }
        return (
            Decision {
                press: true,
                release: false,
            },
            State {
                auto_sneak_active: true,
                // The roll happens here and nowhere else.
                unsneak_delay_ticks: roll().max(0),
            },
        );
    }

    // Off the edge and not holding anything → nothing to do.
    if !state.auto_sneak_active {
        return (Decision::default(), State::default());
    }
    if state.unsneak_delay_ticks > 0 {
        return (
            Decision::default(),
            State {
                auto_sneak_active: true,
                unsneak_delay_ticks: state.unsneak_delay_ticks - 1,
            },
        );
    }
    // Raven: `if (!sneakState && isBindDown(keyBindSneak)) return;` — keep holding
    // while the player holds the key, and retry next tick with the delay spent.
    if input.sneak_key_held {
        return (
            Decision::default(),
            State {
                auto_sneak_active: true,
                unsneak_delay_ticks: 0,
            },
        );
    }
    (
        Decision {
            press: false,
            release: true,
        },
        State::default(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(on_edge: bool, settings_met: bool, sneak_key_held: bool) -> Input {
        Input {
            on_edge,
            settings_met,
            sneak_key_held,
        }
    }

    fn state(auto_sneak_active: bool, unsneak_delay_ticks: i32) -> State {
        State {
            auto_sneak_active,
            unsneak_delay_ticks,
        }
    }

    struct Counter(i32);

    impl Counter {
        fn roll(&mut self) -> i32 {
            self.0 += 1;
            self.0
        }
    }

    #[test]
    fn a_fresh_edge_presses_once_and_arms_the_delay() {
        let mut n = 0;
        let (d, s) = tick(State::default(), input(true, true, false), &mut || {
            n += 1;
            3
        });
        assert!(d.press);
        assert!(!d.release);
        assert!(s.auto_sneak_active);
        assert_eq!(s.unsneak_delay_ticks, 3);
        assert_eq!(n, 1, "the delay is rolled exactly once, when the press happens");
    }

    #[test]
    fn holding_the_edge_does_not_press_again_or_reroll() {
        let mut n = 0;
        let (d, s) = tick(state(true, 3), input(true, true, false), &mut || {
            n += 1;
            1
        });
        assert!(!d.press);
        assert!(!d.release);
        assert!(s.auto_sneak_active);
        assert_eq!(s.unsneak_delay_ticks, 3);
        assert_eq!(n, 0);
    }

    #[test]
    fn leaving_the_edge_counts_down_then_releases() {
        let (d, s) = tick(state(true, 2), input(false, true, false), &mut || 0);
        assert!(!d.release);
        assert_eq!(s.unsneak_delay_ticks, 1);

        let (d, s) = tick(state(true, 1), input(false, true, false), &mut || 0);
        assert!(!d.release);
        assert_eq!(s.unsneak_delay_ticks, 0);

        let (d, s) = tick(state(true, 0), input(false, true, false), &mut || 0);
        assert!(d.release);
        assert!(!s.auto_sneak_active);
    }

    #[test]
    fn a_zero_delay_releases_on_the_first_tick_off_the_edge() {
        let (d, s) = tick(state(true, 0), input(false, true, false), &mut || 0);
        assert!(d.release);
        assert!(!s.auto_sneak_active);
        assert_eq!(s.unsneak_delay_ticks, 0);
    }

    #[test]
    fn an_idle_module_does_nothing_and_never_rolls() {
        let mut n = 0;
        let (d, s) = tick(State::default(), input(false, true, false), &mut || {
            n += 1;
            1
        });
        assert!(!d.press);
        assert!(!d.release);
        assert!(!s.auto_sneak_active);
        assert_eq!(n, 0);
    }

    #[test]
    fn a_players_own_sneak_is_never_claimed() {
        let (d, s) = tick(State::default(), input(true, true, true), &mut || 5);
        assert!(!d.press);
        assert!(!s.auto_sneak_active);
        assert!(!d.release);

        let (d, _) = tick(State::default(), input(false, true, true), &mut || 0);
        assert!(!d.release);
    }

    #[test]
    fn a_held_key_blocks_the_release_until_the_player_lets_go() {
        let (d, s) = tick(state(true, 0), input(false, true, true), &mut || 0);
        assert!(!d.release);
        assert!(s.auto_sneak_active);
        assert_eq!(s.unsneak_delay_ticks, 0);

        let (d, _) = tick(state(true, 0), input(false, true, false), &mut || 0);
        assert!(d.release);
    }

    #[test]
    fn failing_the_settings_gate_releases_at_once_and_forgets_the_delay() {
        let (d, s) = tick(state(true, 4), input(true, false, false), &mut || 9);
        assert!(d.release);
        assert!(!s.auto_sneak_active);
        assert_eq!(s.unsneak_delay_ticks, 0);
    }

    #[test]
    fn failing_the_settings_gate_still_spares_the_players_key() {
        let (d, s) = tick(state(true, 0), input(true, false, true), &mut || 0);
        assert!(!d.release);
        assert!(!s.auto_sneak_active, "we let go of ownership but not of their key");
    }

    #[test]
    fn a_negative_roll_is_clamped_to_zero() {
        let (_, s) = tick(State::default(), input(true, true, false), &mut || -3);
        assert_eq!(s.unsneak_delay_ticks, 0);
    }

    #[test]
    fn the_roll_is_consumed_in_the_callers_order() {
        // Two edges in a row (leaving and coming back) must draw two values in order.
        let mut c = Counter(0);
        let (_, s1) = tick(State::default(), input(true, true, false), &mut || c.roll());
        assert_eq!(s1.unsneak_delay_ticks, 1);
        let (_, s2) = tick(State::default(), input(true, true, false), &mut || c.roll());
        assert_eq!(s2.unsneak_delay_ticks, 2);
    }
}
