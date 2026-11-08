//! Strategies. One module per family; each is a pure function of
//! (config, state, input, rng) with no I/O and no platform types.

pub mod aim;
pub mod backtrack;
pub mod click;
pub mod click_assist;
pub mod combat;
pub mod eagle;
pub mod eagle_place;
pub mod hit_select;
pub mod jump_reset;
pub mod knockback_displace;
pub mod movement;
pub mod reach;
pub mod reach_sample;
pub mod sprint_reset;
pub mod super_knockback;
pub mod tap;
pub mod velocity;
