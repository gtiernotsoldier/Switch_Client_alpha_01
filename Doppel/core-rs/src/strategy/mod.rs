//! Strategies. One module per family; each is a pure function of
//! (config, state, input, rng) with no I/O and no platform types.

pub mod aim;
pub mod combat;
pub mod click;
pub mod eagle;
pub mod movement;
pub mod reach;
pub mod tap;
pub mod velocity;
