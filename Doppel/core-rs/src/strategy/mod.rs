//! Strategies. One module per family; each is a pure function of
//! (config, state, input, rng) with no I/O and no platform types.

pub mod aim;
pub mod backtrack;
pub mod click;
pub mod click_assist;
pub mod combat;
pub mod eagle;
pub mod hit_select;
pub mod knockback_displace;
pub mod movement;
pub mod reach;
pub mod reach_sample;
pub mod tap;
pub mod velocity;
