//! Module payload codecs: what a request or a decision looks like after the
//! 32-byte header.
//!
//! Two shapes, deliberately.
//!
//! **Structured** — aim and click. Each has a dozen config fields and a result
//! that is more than one number. A positional layout with named length
//! constants is the only form that stays reviewable, and the byte table in
//! `IPC-PROTOCOL.md` mirrors it line for line.
//!
//! **Op + argument array** — movement, tap, combat, reach. These are bags of
//! small functions (`strafe_correct`, `seven_tap_side`, `range_gate_compute`,
//! `intersect_box`) plus two multi-field state machines. A bespoke frame layout
//! for each would be a dozen decoders that all look identical and none of which
//! can be checked against a spec without counting bytes. A tagged op with an
//! f64 argument vector costs eight bytes of headroom and makes the op table the
//! only thing that has to be kept in sync.
//!
//! On f64 arguments: every scalar that crosses as an argument is widened to f64
//! and narrowed back on the far side. That is lossless in both directions —
//! f32 ⊂ f64 exactly, and every i32/bool this protocol carries is far below
//! 2^53 — so the round trip cannot perturb a value. It also means one argument
//! reader serves all four modules.
//!
//! All op tables are append-only. A stub and a core are separate binaries that
//! can be upgraded independently; renumbering an op would make an old stub
//! silently compute a different function instead of failing.

use crate::geom::Hitbox;
use crate::model::*;
use crate::model::VelocityContext;
use crate::session::decode_snapshot;
use crate::strategy::aim::{self, AimConfig, AimMode, AimResult};
use crate::strategy::click::{
    self, ClickConfig, ClickMode, ClickOperatingMode, ClickResult, CooldownClickConfig, CooldownClickMode,
    CritMode,
};
use crate::strategy::movement;
use crate::wire::{Action as WireAction, Frame, Kind, Module, Reader, Reason, Status, Writer};

/// Length of the shared snapshot block every module receives.
pub const SNAP: usize = crate::session::SLOT_SNAPSHOT;

/// Bytes an aim request appends to the snapshot.
pub const AIM_TAIL: usize = 12;
/// Bytes an aim config occupies.
pub const AIM_CONFIG_LEN: usize = 77;
/// Bytes an aim decision occupies: the 40-byte base plus fractions and hitbox.
pub const AIM_DECISION_LEN: usize = 97;

/// Bytes a click request appends to the snapshot.
pub const CLICK_TAIL: usize = 8;
/// Largest click config (the probabilistic variant).
pub const CLICK_CONFIG_MAX: usize = 38;

/// The argument vector starts here in a generic request.
pub const OP_AT: usize = SNAP;
/// Maximum arguments that fit in one frame.
pub const MAX_ARGS: usize = (crate::wire::MAX_PAYLOAD - SNAP - 2) / 8;

// ── shared helpers ─────────────────────────────────────────────────────────

fn require(len: usize, need: usize) -> Result<(), Status> {
    if len < need {
        Err(Status::BadPayload)
    } else {
        Ok(())
    }
}

/// Encodes the 19 trigger flags plus the six distance/chance fields.
///
/// Shared by every module that gates on a condition, so that "what a trigger
/// looks like on the wire" has exactly one definition. Velocity's decoder was
/// written before this file existed and decodes the same 26 bytes inline; the
/// layout test at the bottom of `session.rs` keeps the two honest.
pub fn write_trigger(w: &mut Writer, t: &TriggerOptions) {
    let mut f: u16 = 0;
    if t.only_ground {
        f |= 1 << 0;
    }
    if t.only_air {
        f |= 1 << 1;
    }
    if t.only_move {
        f |= 1 << 2;
    }
    if t.only_move_forward {
        f |= 1 << 3;
    }
    if t.only_move_backward {
        f |= 1 << 4;
    }
    if t.only_strafe {
        f |= 1 << 5;
    }
    if t.only_when_target_goes_back {
        f |= 1 << 6;
    }
    if t.only_when_target_approaches {
        f |= 1 << 7;
    }
    if t.on_look {
        f |= 1 << 8;
    }
    if t.only_current_view {
        f |= 1 << 9;
    }
    if t.disable_on_mine {
        f |= 1 << 10;
    }
    if t.only_on_click {
        f |= 1 << 11;
    }
    if t.disabled_in_air {
        f |= 1 << 12;
    }
    w.u16(f);
    w.f32(t.min_distance);
    w.f32(t.max_distance);
    w.i32(t.chance);
    w.i32(t.delay_ticks);
    w.i32(t.delay_ms);
    w.f32(t.look_angle_threshold);
}

pub fn read_trigger(r: &mut Reader) -> Result<TriggerOptions, Status> {
    let f = r.u16()?;
    let mut t = TriggerOptions::default();
    t.only_ground = f & (1 << 0) != 0;
    t.only_air = f & (1 << 1) != 0;
    t.only_move = f & (1 << 2) != 0;
    t.only_move_forward = f & (1 << 3) != 0;
    t.only_move_backward = f & (1 << 4) != 0;
    t.only_strafe = f & (1 << 5) != 0;
    t.only_when_target_goes_back = f & (1 << 6) != 0;
    t.only_when_target_approaches = f & (1 << 7) != 0;
    t.on_look = f & (1 << 8) != 0;
    t.only_current_view = f & (1 << 9) != 0;
    t.disable_on_mine = f & (1 << 10) != 0;
    t.only_on_click = f & (1 << 11) != 0;
    t.disabled_in_air = f & (1 << 12) != 0;
    t.min_distance = r.f32()?;
    t.max_distance = r.f32()?;
    t.chance = r.i32()?;
    t.delay_ticks = r.i32()?;
    t.delay_ms = r.i32()?;
    t.look_angle_threshold = r.f32()?;
    Ok(t)
}

// ── trigger gate packed into a request argument ────────────────────────────

/// Rebuilds a [`TriggerOptions`] from the packed form a generic request carries.
///
/// The bit order is `write_trigger`'s, deliberately: a config frame and a per-tick
/// gate must describe the same trigger, and two layouts would eventually disagree
/// about which bit is `only_strafe`.
pub fn trigger_from_packed(
    flags: f64,
    min_distance: f64,
    max_distance: f64,
    look_angle_threshold: f64,
    chance: f64,
) -> TriggerOptions {
    let f = flags as u16;
    let mut t = TriggerOptions::default();
    t.only_ground = f & (1 << 0) != 0;
    t.only_air = f & (1 << 1) != 0;
    t.only_move = f & (1 << 2) != 0;
    t.only_move_forward = f & (1 << 3) != 0;
    t.only_move_backward = f & (1 << 4) != 0;
    t.only_strafe = f & (1 << 5) != 0;
    t.only_when_target_goes_back = f & (1 << 6) != 0;
    t.only_when_target_approaches = f & (1 << 7) != 0;
    t.on_look = f & (1 << 8) != 0;
    t.only_current_view = f & (1 << 9) != 0;
    t.disable_on_mine = f & (1 << 10) != 0;
    t.only_on_click = f & (1 << 11) != 0;
    t.disabled_in_air = f & (1 << 12) != 0;
    t.min_distance = min_distance as f32;
    t.max_distance = max_distance as f32;
    t.chance = chance as i32;
    t.look_angle_threshold = look_angle_threshold as f32;
    t
}

/// `encode_bool` plus two counters.
///
/// The trigger's hit counter and freshly-sampled threshold have to come back or the
/// module cannot count toward its next fire — and the threshold is sampled inside the
/// core, so the JVM cannot reconstruct it.
pub fn encode_tristate(
    seq: u32,
    tick: u32,
    module: Module,
    action: WireAction,
    flag: bool,
    a: i32,
    b: i32,
) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, module);
    let mut buf = [0u8; 40];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(action as u8);
        w.u8(Reason::None as u8);
        w.u16(if flag { 1 } else { 0 });
        w.f64(0.0);
        w.f64(0.0);
        w.f64(0.0);
        w.i32(a);
        w.i32(b);
        w.u32(tick);
    }
    f.payload[..40].copy_from_slice(&buf);
    f.set_len(40);
    f
}

// ── generic request: op + f64 arguments ────────────────────────────────────

pub struct GenericRequest {
    pub ctx: VelocityContext,
    pub op: u8,
    pub args: [f64; 16],
    pub nargs: usize,
}

impl GenericRequest {
    pub fn arg(&self, i: usize) -> Result<f64, Status> {
        if i < self.nargs {
            Ok(self.args[i])
        } else {
            Err(Status::BadPayload)
        }
    }

    pub fn i32(&self, i: usize) -> Result<i32, Status> {
        Ok(self.arg(i)? as i32)
    }

    pub fn bool(&self, i: usize) -> Result<bool, Status> {
        Ok(self.arg(i)? != 0.0)
    }

    pub fn f32(&self, i: usize) -> Result<f32, Status> {
        Ok(self.arg(i)? as f32)
    }

    pub fn vec3(&self, i: usize) -> Result<Vec3, Status> {
        Ok(Vec3::new(self.arg(i)?, self.arg(i + 1)?, self.arg(i + 2)?))
    }
}

pub fn decode_generic(buf: &[u8], len: usize) -> Result<GenericRequest, Status> {
    let ctx = decode_snapshot(buf, len)?;
    require(len, SNAP + 2)?;
    let op = buf[OP_AT];
    let n = buf[OP_AT + 1] as usize;
    if n > MAX_ARGS {
        return Err(Status::BadPayload);
    }
    require(len, SNAP + 2 + n * 8)?;
    let mut r = Reader::new(&buf[OP_AT + 2..len]);
    let mut args = [0.0f64; 16];
    for i in 0..n {
        args[i] = r.f64()?;
    }
    Ok(GenericRequest {
        ctx,
        op,
        args,
        nargs: n,
    })
}

// ── aim ────────────────────────────────────────────────────────────────────

pub struct AimRequest {
    pub ctx: VelocityContext,
    pub mouse_dx: f32,
    pub mouse_dy: f32,
    pub sensitivity: f32,
}

impl AimRequest {
    /// Borrows the shared halves as the strategy's own input type.
    pub fn input(&self) -> aim::AimInput {
        aim::AimInput {
            player: self.ctx.player,
            target: self.ctx.target,
            mouse_delta_x: self.mouse_dx,
            mouse_delta_y: self.mouse_dy,
        }
    }
}

pub fn decode_aim_request(buf: &[u8], len: usize) -> Result<AimRequest, Status> {
    let ctx = decode_snapshot(buf, len)?;
    require(len, SNAP + AIM_TAIL)?;
    let mut r = Reader::new(&buf[SNAP..len]);
    Ok(AimRequest {
        ctx,
        mouse_dx: r.f32()?,
        mouse_dy: r.f32()?,
        sensitivity: r.f32()?,
    })
}

pub fn encode_aim_config(c: &AimConfig) -> [u8; AIM_CONFIG_LEN] {
    let mut buf = [0u8; AIM_CONFIG_LEN];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(match c.mode {
            AimMode::Legit => 0,
            AimMode::Normal => 1,
            AimMode::SelfAdaptive => 2,
            AimMode::Linear => 3,
        });
        w.f32(c.range_min);
        w.f32(c.range_max);
        w.f32(c.fov);
        w.f32(c.min_fov);
        w.f32(c.aim_speed_y);
        w.f32(c.aim_speed_p);
        w.i32(c.speed);
        w.f32(c.smoothness);
        w.f32(c.noise_intensity);
        w.f32(c.offset);
        w.f32(c.multipoint_x);
        w.f32(c.multipoint_y);
        let mut f: u16 = 0;
        if c.linear {
            f |= 1;
        }
        if c.lock_on_crosshair {
            f |= 1 << 1;
        }
        w.u16(f);
        write_trigger(&mut w, &c.trigger_options);
        debug_assert_eq!(w.pos(), AIM_CONFIG_LEN);
    }
    buf
}

pub fn decode_aim_config(buf: &[u8], len: usize) -> Result<AimConfig, Status> {
    require(len, AIM_CONFIG_LEN)?;
    let mut r = Reader::new(&buf[..len]);
    let mode = match r.u8()? {
        0 => AimMode::Legit,
        1 => AimMode::Normal,
        2 => AimMode::SelfAdaptive,
        3 => AimMode::Linear,
        _ => return Err(Status::BadPayload),
    };
    let range_min = r.f32()?;
    let range_max = r.f32()?;
    let fov = r.f32()?;
    let min_fov = r.f32()?;
    let aim_speed_y = r.f32()?;
    let aim_speed_p = r.f32()?;
    let speed = r.i32()?;
    let smoothness = r.f32()?;
    let noise_intensity = r.f32()?;
    let offset = r.f32()?;
    let multipoint_x = r.f32()?;
    let multipoint_y = r.f32()?;
    let flags = r.u16()?;
    let trigger_options = read_trigger(&mut r)?;
    Ok(AimConfig {
        mode,
        range_min,
        range_max,
        fov,
        min_fov,
        aim_speed_y,
        aim_speed_p,
        speed,
        smoothness,
        noise_intensity,
        offset,
        multipoint_x,
        multipoint_y,
        linear: flags & 1 != 0,
        lock_on_crosshair: flags & 2 != 0,
        trigger_options,
    })
}

/// Aim's answer. The world point rides in the base `motion` triple; the two
/// fractions and the optional box follow it.
pub fn encode_aim_decision(r: &AimResult, seq: u32, tick: u32) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, Module::Aim);
    let mut buf = [0u8; AIM_DECISION_LEN];
    {
        let mut w = Writer::new(&mut buf);
        match r {
            AimResult::Skip => {
                w.u8(WireAction::NoOp as u8);
                w.u8(Reason::NoTarget as u8);
                w.u16(0);
                w.f64(0.0);
                w.f64(0.0);
                w.f64(0.0);
                w.i32(0);
                w.i32(0);
                w.u32(tick);
                w.f32(0.0);
                w.f32(0.0);
                w.u8(0);
                for _ in 0..6 {
                    w.f64(0.0);
                }
            }
            AimResult::ApplyRotation {
                world_point,
                hitbox,
                fraction_y,
                fraction_p,
            } => {
                w.u8(WireAction::ApplyRotation as u8);
                w.u8(Reason::None as u8);
                w.u16(if hitbox.is_some() { 1 } else { 0 });
                w.f64(world_point.x);
                w.f64(world_point.y);
                w.f64(world_point.z);
                w.i32(0);
                w.i32(0);
                w.u32(tick);
                w.f32(*fraction_y);
                w.f32(*fraction_p);
                w.u8(if hitbox.is_some() { 1 } else { 0 });
                let b = hitbox.unwrap_or(Hitbox::new(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
                w.f64(b.min_x);
                w.f64(b.min_y);
                w.f64(b.min_z);
                w.f64(b.max_x);
                w.f64(b.max_y);
                w.f64(b.max_z);
            }
        }
        debug_assert_eq!(w.pos(), AIM_DECISION_LEN);
    }
    f.payload[..AIM_DECISION_LEN].copy_from_slice(&buf);
    f.set_len(AIM_DECISION_LEN);
    f
}

/// Decoded aim answer, for the stub and for the parity tests.
pub struct AimDecision {
    pub apply: bool,
    pub world_point: Vec3,
    pub hitbox: Option<Hitbox>,
    pub fraction_y: f32,
    pub fraction_p: f32,
}

pub fn decode_aim_decision(f: &Frame) -> Result<AimDecision, Status> {
    require(f.header.payload_len as usize, AIM_DECISION_LEN)?;
    let mut r = Reader::new(&f.payload[..f.header.payload_len as usize]);
    let action = r.u8()?;
    r.u8()?;
    let flags = r.u16()?;
    let x = r.f64()?;
    let y = r.f64()?;
    let z = r.f64()?;
    r.i32()?;
    r.i32()?;
    r.u32()?;
    let fraction_y = r.f32()?;
    let fraction_p = r.f32()?;
    let has_box = r.u8()? != 0;
    let mut box_ = Hitbox::new(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    box_.min_x = r.f64()?;
    box_.min_y = r.f64()?;
    box_.min_z = r.f64()?;
    box_.max_x = r.f64()?;
    box_.max_y = r.f64()?;
    box_.max_z = r.f64()?;
    Ok(AimDecision {
        apply: action == WireAction::ApplyRotation as u8 && flags & 1 != 0,
        world_point: Vec3::new(x, y, z),
        hitbox: if has_box { Some(box_) } else { None },
        fraction_y,
        fraction_p,
    })
}

// ── click ──────────────────────────────────────────────────────────────────

pub struct ClickRequest {
    pub ctx: VelocityContext,
    pub attack_cooldown: f32,
    pub is_falling: bool,
}

impl ClickRequest {
    pub fn input(&self) -> click::ClickInput {
        click::ClickInput {
            player: self.ctx.player,
            target: self.ctx.target,
            attack_cooldown: self.attack_cooldown,
            is_falling: self.is_falling,
        }
    }
}

pub fn decode_click_request(buf: &[u8], len: usize) -> Result<ClickRequest, Status> {
    let ctx = decode_snapshot(buf, len)?;
    require(len, SNAP + CLICK_TAIL)?;
    let mut r = Reader::new(&buf[SNAP..len]);
    Ok(ClickRequest {
        ctx,
        attack_cooldown: r.f32()?,
        is_falling: r.u8()? != 0,
    })
}

/// Both click configs share their first byte: 0 = probabilistic (1.8),
/// 1 = cooldown (1.9+). Everything after depends on it.
pub fn encode_click_config_p(c: &ClickConfig) -> Vec<u8> {
    let mut buf = vec![0u8; 1 + 4 + 4 + 3 + 26];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(0);
        w.i32(c.min_cps);
        w.i32(c.max_cps);
        w.u8(match c.click_mode {
            ClickMode::Single => 0,
            ClickMode::Double => 1,
        });
        w.u8(match c.mode {
            ClickOperatingMode::Normal => 0,
            ClickOperatingMode::Legit => 1,
        });
        w.u8(if c.disable_on_block { 1 } else { 0 });
        write_trigger(&mut w, &c.trigger_options);
    }
    buf
}

pub fn encode_click_config_c(c: &CooldownClickConfig) -> Vec<u8> {
    let mut buf = vec![0u8; 1 + 4 + 4 + 26];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(1);
        w.f32(c.cooldown_threshold);
        w.u8(match c.crit_mode {
            CritMode::Off => 0,
            CritMode::On => 1,
            CritMode::Smart => 2,
        });
        w.u8(if c.crit_stop_sprint { 1 } else { 0 });
        w.u8(match c.cooldown_mode {
            CooldownClickMode::Normal => 0,
            CooldownClickMode::Legit => 1,
        });
        w.u8(if c.disable_on_block { 1 } else { 0 });
        write_trigger(&mut w, &c.trigger_options);
    }
    buf
}

pub enum ClickConfigKind {
    Probabilistic(ClickConfig),
    Cooldown(CooldownClickConfig),
}

pub fn decode_click_config(buf: &[u8], len: usize) -> Result<ClickConfigKind, Status> {
    require(len, 1)?;
    let mut r = Reader::new(&buf[..len]);
    match r.u8()? {
        0 => {
            let min_cps = r.i32()?;
            let max_cps = r.i32()?;
            let click_mode = match r.u8()? {
                0 => ClickMode::Single,
                1 => ClickMode::Double,
                _ => return Err(Status::BadPayload),
            };
            let mode = match r.u8()? {
                0 => ClickOperatingMode::Normal,
                1 => ClickOperatingMode::Legit,
                _ => return Err(Status::BadPayload),
            };
            let disable_on_block = r.u8()? != 0;
            let trigger_options = read_trigger(&mut r)?;
            Ok(ClickConfigKind::Probabilistic(ClickConfig {
                min_cps,
                max_cps,
                click_mode,
                mode,
                disable_on_block,
                trigger_options,
            }))
        }
        1 => {
            let cooldown_threshold = r.f32()?;
            let crit_mode = match r.u8()? {
                0 => CritMode::Off,
                1 => CritMode::On,
                2 => CritMode::Smart,
                _ => return Err(Status::BadPayload),
            };
            let crit_stop_sprint = r.u8()? != 0;
            let cooldown_mode = match r.u8()? {
                0 => CooldownClickMode::Normal,
                1 => CooldownClickMode::Legit,
                _ => return Err(Status::BadPayload),
            };
            let disable_on_block = r.u8()? != 0;
            let trigger_options = read_trigger(&mut r)?;
            Ok(ClickConfigKind::Cooldown(CooldownClickConfig {
                cooldown_threshold,
                crit_mode,
                crit_stop_sprint,
                cooldown_mode,
                disable_on_block,
                trigger_options,
            }))
        }
        _ => Err(Status::BadPayload),
    }
}

pub fn encode_click_decision(r: &ClickResult, seq: u32, tick: u32) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, Module::Click);
    let mut buf = [0u8; 40];
    {
        let mut w = Writer::new(&mut buf);
        let (action, flags) = match r {
            ClickResult::Click => (WireAction::Click, 0u16),
            ClickResult::StopSprint => (WireAction::StopSprint, 0),
            ClickResult::RestoreSprint { was_sprinting } => {
                (WireAction::RestoreSprint, if *was_sprinting { 1 } else { 0 })
            }
            ClickResult::Skip => (WireAction::NoOp, 0),
        };
        w.u8(action as u8);
        w.u8(Reason::None as u8);
        w.u16(flags);
        w.f64(0.0);
        w.f64(0.0);
        w.f64(0.0);
        w.i32(0);
        w.i32(0);
        w.u32(tick);
    }
    f.payload[..40].copy_from_slice(&buf);
    f.set_len(40);
    f
}

pub fn decode_click_decision(f: &Frame) -> Result<ClickResult, Status> {
    require(f.header.payload_len as usize, 4)?;
    let mut r = Reader::new(&f.payload[..f.header.payload_len as usize]);
    let action = r.u8()?;
    r.u8()?;
    let flags = r.u16()?;
    Ok(match action {
        x if x == WireAction::Click as u8 => ClickResult::Click,
        x if x == WireAction::StopSprint as u8 => ClickResult::StopSprint,
        x if x == WireAction::RestoreSprint as u8 => ClickResult::RestoreSprint {
            was_sprinting: flags & 1 != 0,
        },
        _ => ClickResult::Skip,
    })
}

// ── generic answers ────────────────────────────────────────────────────────

/// A one-number answer. `motion.x` carries it; the other two stay zero so a
/// reader that expects a vector does not pick up garbage.
pub fn encode_scalar(seq: u32, tick: u32, module: Module, action: WireAction, v: f64) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, module);
    let mut buf = [0u8; 40];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(action as u8);
        w.u8(Reason::None as u8);
        w.u16(0);
        w.f64(v);
        w.f64(0.0);
        w.f64(0.0);
        w.i32(0);
        w.i32(0);
        w.u32(tick);
    }
    f.payload[..40].copy_from_slice(&buf);
    f.set_len(40);
    f
}

/// A variable-length list of released motions: `count(u16)` then `count * (x, y, z)`.
///
/// The knockback delay can release several packets on the same tick, so this cannot reuse the
/// fixed decision layout. `count` is capped at what one frame holds; the rest stay queued and come
/// out on the next pump rather than being dropped.
pub fn encode_motions(seq: u32, tick: u32, motions: &[Vec3]) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, Module::Velocity);
    let max = (crate::wire::MAX_PAYLOAD - 4) / 24;
    let n = motions.len().min(max);
    let mut buf = [0u8; crate::wire::MAX_PAYLOAD];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(WireAction::Modify as u8);
        w.u8(Reason::Released as u8);
        w.u16(n as u16);
        for m in motions.iter().take(n) {
            w.f64(m.x);
            w.f64(m.y);
            w.f64(m.z);
        }
    }
    let len = 4 + n * 24;
    f.payload[..len].copy_from_slice(&buf[..len]);
    f.set_len(len);
    f
}

pub fn encode_bool(seq: u32, tick: u32, module: Module, action: WireAction, v: bool) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, module);
    let mut buf = [0u8; 40];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(action as u8);
        w.u8(Reason::None as u8);
        w.u16(if v { 1 } else { 0 });
        w.f64(0.0);
        w.f64(0.0);
        w.f64(0.0);
        w.i32(if v { 1 } else { 0 });
        w.i32(0);
        w.u32(tick);
    }
    f.payload[..40].copy_from_slice(&buf);
    f.set_len(40);
    f
}

/// Movement's answer: four suppression bits plus the score before and after.
pub fn encode_suppress(
    seq: u32,
    tick: u32,
    module: Module,
    corr: &movement::StrafeCorrection,
) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, module);
    let mut buf = [0u8; 40];
    {
        let mut w = Writer::new(&mut buf);
        let mut flags: u16 = 0;
        if corr.suppress_forward {
            flags |= 1;
        }
        if corr.suppress_back {
            flags |= 2;
        }
        if corr.suppress_left {
            flags |= 4;
        }
        if corr.suppress_right {
            flags |= 8;
        }
        w.u8(WireAction::Suppress as u8);
        w.u8(Reason::None as u8);
        w.u16(flags);
        w.f64(corr.before);
        w.f64(corr.after);
        w.f64(0.0);
        w.i32(0);
        w.i32(0);
        w.u32(tick);
    }
    f.payload[..40].copy_from_slice(&buf);
    f.set_len(40);
    f
}

/// The four suppression bits of a decision, in forward/back/left/right order.
pub fn decode_suppress(f: &Frame) -> Result<(bool, bool, bool, bool), Status> {
    require(f.header.payload_len as usize, 4)?;
    let mut r = Reader::new(&f.payload[..f.header.payload_len as usize]);
    r.u8()?;
    r.u8()?;
    let flags = r.u16()?;
    Ok((
        flags & 1 != 0,
        flags & 2 != 0,
        flags & 4 != 0,
        flags & 8 != 0,
    ))
}

// ── op tables ──────────────────────────────────────────────────────────────
//
// Each constant below is the first byte of a generic request. The comment on
// the right is the argument list, in order, as f64.

pub mod op {
    /// velocity: `brace_keys(yaw_deg, sector)` → 4 bits (forward, back, left, right).
    /// The armed window and its remaining ticks are core state; this consumes one.
    pub const VEL_BRACE: u8 = 0;
    /// velocity: `delay_enqueue(tick, chance, max_delay, require_weapon, flags,
    ///            min_d, max_d, look_angle, gate_chance)` → bool (swallowed).
    ///
    /// The delay parameters ride on the request instead of a config frame because
    /// KnockbackDelay and Velocity are separate modules that would otherwise
    /// overwrite each other's single velocity config slot.
    pub const VEL_DELAY_ENQUEUE: u8 = 1;
    /// velocity: `delay_pump(tick)` → released motions, packed.
    pub const VEL_DELAY_PUMP: u8 = 2;

    /// movement: `socd_resolve(forward, back, left, right)` → 4 bits.
    pub const MOV_SOCD: u8 = 0;
    /// movement: `strafe_correct(forward, back, left, right, distance,
    ///            bearing_rad, yaw_deg, reach)` → 4 bits + before/after.
    pub const MOV_STRAFE: u8 = 1;
    /// movement: `still_sliding(motion_x, motion_z, yaw, released_forward,
    ///            min_speed)` → bool.
    pub const MOV_SLIDING: u8 = 2;

    /// tap: `chase_side(yaw, lead_ticks)` → side (0 left, 1 right).
    /// Position and target motion come from the snapshot.
    pub const TAP_CHASE: u8 = 0;
    /// tap: `seven_tap_side(last_side, repeat_chance_percent)` → side.
    pub const TAP_SEVEN: u8 = 1;
    /// tap: `wrap180(angle)` → scalar.
    pub const TAP_WRAP: u8 = 2;
    /// tap: `state_machine_tick(now_ns)` → event (0 none, 1 end, 2 start).
    pub const TAP_TICK: u8 = 3;
    /// tap: `begin_post_delay(now_ns, delay_ms)` → none.
    pub const TAP_POST_DELAY: u8 = 4;
    /// tap: `begin_tap(now_ns, duration_ms)` → none.
    pub const TAP_BEGIN: u8 = 5;
    /// tap: `random_side()` → side. Draws from `rng_tap`, so a module that mixes
    /// this with seven_tap_side keeps one stream.
    pub const TAP_RANDOM: u8 = 6;

    /// combat: `trigger_evaluate(mode, hurt_resistant, max_hurt, counter,
    ///          threshold, per_min, per_max, chance, hurt_gate)` → fire.
    pub const CBT_TRIGGER: u8 = 0;
    /// combat: `block_arbiter(locked, threat, sustain, reflex, w_lock,
    ///          w_threat, w_sustain, w_reflex, threshold, max_duty)` → verdict.
    /// The running duty cycle lives in the core.
    pub const CBT_ARBITER: u8 = 1;
    /// combat: `predictor(max_hurt, rtt, lead, predict, window, chance,
    ///          alpha, seed_interval, current_tick, opponent_just_hit)` → verdict.
    pub const CBT_PREDICT: u8 = 2;
    /// combat: `range_gate(mode, min_distance, max_distance, hysteresis,
    ///          has_distance, distance)` → bool.
    pub const CBT_RANGE: u8 = 3;
    /// combat: `decide(flags, min_d, max_d, look_angle, gate_chance, mode,
    ///          hurt_gate, max_hurt, target_hurt, counter, threshold,
    ///          per_min, per_max, trigger_chance)` → fire + new counter/threshold.
    ///
    /// The gate and the trigger in one round trip, because they share a random
    /// stream. Split across two calls the gate's `chance` roll and the trigger's
    /// two draws would interleave differently on each side, and a 300-tick replay
    /// would diverge on the first fire.
    pub const CBT_DECIDE: u8 = 4;

    /// reach: `intersect_box(min_x, min_y, min_z, max_x, max_y, max_z,
    ///          ox, oy, oz, dx, dy, dz)` → bool.
    pub const REACH_INTERSECT: u8 = 0;

    /// player/eagle: `tick(on_edge, settings_met, sneak_key_held, min_delay,
    ///               max_delay)` → packed flags.
    ///
    /// The hold delay and the "we own the sneak" bit live in the core; only the
    /// three predicates and the roll bounds ride on the request, because both
    /// are things only the game side can see (edge query, pitch gate, real key).
    pub const PLAYER_EAGLE_TICK: u8 = 0;

    /// player/backtrack: `tick(now_ns, modern, pulse, smart, min_delay_ms,
    ///                     max_delay_ms, distance, legacy_distance,
    ///                     must_release)` → bool (hold this tick).
    ///
    /// `now_ns` is the caller's monotonic clock. Only differences are used, so
    /// the two processes never have to agree on an absolute time base.
    pub const PLAYER_BACKTRACK_TICK: u8 = 1;

    /// player/knockback_displace: `attack(now_ns, direction, use_last_strafe,
    ///                              cooldown_ms, angle, not_taking_knockback,
    ///                              velocity_this_tick, key_left, key_right)`
    ///                              → yaw offset in degrees (0 = no flick).
    pub const PLAYER_KBD_ATTACK: u8 = 2;

    /// player/reach: `sample(now_ns, conditions_ok, gui_open, chance, hit_per,
    ///                 delay_ms, hit_through_blocks, looking_at_block,
    ///                 reach_min, reach_max)` → distance, or 0 for "leave it alone".
    pub const PLAYER_REACH_SAMPLE: u8 = 3;
}

/// Maps a side to the wire's 0/1.
pub fn side_to_f64(s: crate::strategy::tap::Side) -> f64 {
    match s {
        crate::strategy::tap::Side::Left => 0.0,
        crate::strategy::tap::Side::Right => 1.0,
    }
}

pub fn f64_to_side(v: f64) -> crate::strategy::tap::Side {
    if v == 0.0 {
        crate::strategy::tap::Side::Left
    } else {
        crate::strategy::tap::Side::Right
    }
}

/// Combat's answer. `Block` carries the score in `motion.x` and the effective
/// hurt time in `clicks`; the reason table is reused rather than extended
/// because the stub only logs it.
pub fn encode_verdict(
    seq: u32,
    tick: u32,
    block: bool,
    score: f64,
    effective_hurt: i32,
    locked: bool,
    threat: bool,
) -> Frame {
    let mut f = Frame::new(Kind::Decision, seq, tick, Module::Combat);
    let mut buf = [0u8; 40];
    {
        let mut w = Writer::new(&mut buf);
        w.u8(WireAction::Block as u8);
        w.u8(Reason::None as u8);
        // bit0 block, bit1 locked (layer A), bit2 threat (layer B). The adaptive mode needs all
        // three: it weighs each signal separately, so "block" alone would collapse the two.
        let flags = (if block { 1 } else { 0 })
            | (if locked { 2 } else { 0 })
            | (if threat { 4 } else { 0 });
        w.u16(flags);
        w.f64(score);
        w.f64(0.0);
        w.f64(0.0);
        w.i32(effective_hurt);
        w.i32(0);
        w.u32(tick);
    }
    f.payload[..40].copy_from_slice(&buf);
    f.set_len(40);
    f
}

pub fn decode_verdict(f: &Frame) -> Result<(bool, f64, i32), Status> {
    require(f.header.payload_len as usize, 4)?;
    let mut r = Reader::new(&f.payload[..f.header.payload_len as usize]);
    r.u8()?;
    r.u8()?;
    let flags = r.u16()?;
    let score = r.f64()?;
    r.skip(16)?;
    let eff = r.i32()?;
    Ok((flags & 1 != 0, score, eff))
}

/// The op tables as the dispatcher sees them, so a test can walk them and
/// assert that every advertised op has an arm.
pub fn known_ops(module: Module) -> &'static [u8] {
    match module {
        Module::Movement => &[op::MOV_SOCD, op::MOV_STRAFE, op::MOV_SLIDING],
        Module::Tap => &[
            op::TAP_RANDOM,
            op::TAP_CHASE,
            op::TAP_SEVEN,
            op::TAP_WRAP,
            op::TAP_TICK,
            op::TAP_POST_DELAY,
            op::TAP_BEGIN,
        ],
        Module::Combat => &[
            op::CBT_TRIGGER,
            op::CBT_ARBITER,
            op::CBT_PREDICT,
            op::CBT_RANGE,
            op::CBT_DECIDE,
        ],
        Module::Reach => &[op::REACH_INTERSECT],
        Module::Player => &[
            op::PLAYER_EAGLE_TICK,
            op::PLAYER_BACKTRACK_TICK,
            op::PLAYER_KBD_ATTACK,
            op::PLAYER_REACH_SAMPLE,
        ],
        Module::Velocity => &[op::VEL_BRACE, op::VEL_DELAY_ENQUEUE, op::VEL_DELAY_PUMP],
        _ => &[],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `trigger_from_packed` must read the same bits `write_trigger` writes.
    ///
    /// Two layouts for one concept is the failure this guards: if a bit moves here and not
    /// there, the gate silently answers a different question — a module fires with
    /// `onlyGround` set while the player is airborne, and nothing in the log says why.
    #[test]
    fn packed_trigger_matches_the_config_layout() {
        let mut t = TriggerOptions::default();
        t.only_ground = true;
        t.only_strafe = true;
        t.only_when_target_approaches = true;
        t.on_look = true;
        t.disabled_in_air = true;
        t.min_distance = 1.5;
        t.max_distance = 4.25;
        t.chance = 37;
        t.look_angle_threshold = 22.5;

        let mut buf = [0u8; 64];
        let mut w = Writer::new(&mut buf);
        write_trigger(&mut w, &t);
        let mut r = Reader::new(&buf[..26]);
        let from_config = read_trigger(&mut r).expect("config layout");

        let from_request = trigger_from_packed(
            from_config_flags(&from_config),
            t.min_distance as f64,
            t.max_distance as f64,
            t.look_angle_threshold as f64,
            t.chance as f64,
        );

        assert_eq!(from_request.only_ground, t.only_ground);
        assert_eq!(from_request.only_air, t.only_air);
        assert_eq!(from_request.only_move, t.only_move);
        assert_eq!(from_request.only_move_forward, t.only_move_forward);
        assert_eq!(from_request.only_move_backward, t.only_move_backward);
        assert_eq!(from_request.only_strafe, t.only_strafe);
        assert_eq!(
            from_request.only_when_target_goes_back,
            t.only_when_target_goes_back
        );
        assert_eq!(
            from_request.only_when_target_approaches,
            t.only_when_target_approaches
        );
        assert_eq!(from_request.on_look, t.on_look);
        assert_eq!(from_request.only_current_view, t.only_current_view);
        assert_eq!(from_request.disable_on_mine, t.disable_on_mine);
        assert_eq!(from_request.only_on_click, t.only_on_click);
        assert_eq!(from_request.disabled_in_air, t.disabled_in_air);
        assert_eq!(from_request.min_distance, t.min_distance);
        assert_eq!(from_request.max_distance, t.max_distance);
        assert_eq!(from_request.chance, t.chance);
        assert_eq!(from_request.look_angle_threshold, t.look_angle_threshold);
    }

    /// The bit field as the config frame carries it, so the test compares layouts rather than
    /// re-deriving the mask a third time.
    fn from_config_flags(t: &TriggerOptions) -> f64 {
        let mut f: u16 = 0;
        if t.only_ground {
            f |= 1 << 0;
        }
        if t.only_air {
            f |= 1 << 1;
        }
        if t.only_move {
            f |= 1 << 2;
        }
        if t.only_move_forward {
            f |= 1 << 3;
        }
        if t.only_move_backward {
            f |= 1 << 4;
        }
        if t.only_strafe {
            f |= 1 << 5;
        }
        if t.only_when_target_goes_back {
            f |= 1 << 6;
        }
        if t.only_when_target_approaches {
            f |= 1 << 7;
        }
        if t.on_look {
            f |= 1 << 8;
        }
        if t.only_current_view {
            f |= 1 << 9;
        }
        if t.disable_on_mine {
            f |= 1 << 10;
        }
        if t.only_on_click {
            f |= 1 << 11;
        }
        if t.disabled_in_air {
            f |= 1 << 12;
        }
        f as f64
    }
}
