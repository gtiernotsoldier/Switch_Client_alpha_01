//! Strategies. One module per family; each is a pure function of
//! (config, state, input, rng) with no I/O and no platform types.

pub mod aim;
pub mod backtrack;
pub mod click;
pub mod combat;
pub mod eagle;
pub mod knockback_displace;
pub mod movement;
pub mod reach;
pub mod tap;
pub mod velocity;
