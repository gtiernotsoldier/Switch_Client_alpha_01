//! Domain model on the core side.
//!
//! These mirror `io.doppel.core.model` field for field. The only structural
//! difference is that booleans travel as bit flags in the snapshot, because a
//! fixed 256-byte frame has no room for one byte per flag — the mapping back to
//! named fields happens here, once, so the strategy code never sees a bitmask.

pub const F_ON_GROUND: u16 = 1 << 0;
pub const F_MOVING: u16 = 1 << 1;
pub const F_MOVING_FORWARD: u16 = 1 << 2;
pub const F_SPRINTING: u16 = 1 << 3;
pub const F_LOOKING_AT_TARGET: u16 = 1 << 4;
pub const F_BLOCKING: u16 = 1 << 5;
pub const F_USING_ITEM: u16 = 1 << 6;
pub const F_SNEAKING: u16 = 1 << 7;
pub const F_ATTACK_KEY_DOWN: u16 = 1 << 8;
pub const F_MINING: u16 = 1 << 9;

/// Weapon class, matching `WeaponType`. Only `OTHER` vs not matters to velocity,
/// but the full enum travels so a later module does not need a protocol bump.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum WeaponType {
    Other = 0,
    Sword = 1,
    Axe = 2,
}

impl WeaponType {
    pub fn from_u8(v: u8) -> WeaponType {
        match v {
            1 => WeaponType::Sword,
            2 => WeaponType::Axe,
            _ => WeaponType::Other,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Vec3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Vec3 {
    pub fn new(x: f64, y: f64, z: f64) -> Vec3 {
        Vec3 { x, y, z }
    }

    pub fn length(&self) -> f64 {
        (self.x * self.x + self.y * self.y + self.z * self.z).sqrt()
    }

    pub fn dot(&self, o: &Vec3) -> f64 {
        self.x * o.x + self.y * o.y + self.z * o.z
    }

    pub fn cross(&self, o: &Vec3) -> Vec3 {
        Vec3::new(
            self.y * o.z - self.z * o.y,
            self.z * o.x - self.x * o.z,
            self.x * o.y - self.y * o.x,
        )
    }

    pub fn normalize(&self) -> Vec3 {
        let len = self.length();
        if len > 0.0 {
            // Multiply by the reciprocal; the Kotlin is `times(1.0 / len)`.
            let inv = 1.0 / len;
            Vec3::new(self.x * inv, self.y * inv, self.z * inv)
        } else {
            Vec3::new(0.0, 0.0, 0.0)
        }
    }

    pub fn distance_to(&self, o: &Vec3) -> f64 {
        let dx = self.x - o.x;
        let dy = self.y - o.y;
        let dz = self.z - o.z;
        (dx * dx + dy * dy + dz * dz).sqrt()
    }
}

#[derive(Debug, Clone, Copy)]
pub struct PlayerState {
    pub position: Vec3,
    pub motion: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub health: f32,
    pub hurt_time: i32,
    pub hurt_resistant_time: i32,
    pub weapon: WeaponType,
    pub flags: u16,
    pub ticks: u32,
}

impl PlayerState {
    fn bit(&self, f: u16) -> bool {
        self.flags & f != 0
    }
    pub fn on_ground(&self) -> bool {
        self.bit(F_ON_GROUND)
    }
    pub fn is_moving(&self) -> bool {
        self.bit(F_MOVING)
    }
    pub fn is_moving_forward(&self) -> bool {
        self.bit(F_MOVING_FORWARD)
    }
    pub fn is_sprinting(&self) -> bool {
        self.bit(F_SPRINTING)
    }
    pub fn is_looking_at_target(&self) -> bool {
        self.bit(F_LOOKING_AT_TARGET)
    }
    pub fn is_blocking(&self) -> bool {
        self.bit(F_BLOCKING)
    }
    pub fn is_using_item(&self) -> bool {
        self.bit(F_USING_ITEM)
    }
    pub fn is_sneaking(&self) -> bool {
        self.bit(F_SNEAKING)
    }
    pub fn is_attack_key_down(&self) -> bool {
        self.bit(F_ATTACK_KEY_DOWN)
    }
    pub fn is_mining(&self) -> bool {
        self.bit(F_MINING)
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TargetState {
    pub id: i32,
    pub position: Vec3,
    /// Per-tick velocity of the target, needed by the predictive lead in
    /// SELF_ADAPTIVE. The velocity modules never read it.
    pub motion: Vec3,
    /// Entity bounding box in world space. Read by every aim mode; the velocity
    /// modules ignore it, which is why it can carry a default until the wire
    /// grows a field for it.
    pub hitbox: crate::geom::Hitbox,
    pub distance: f32,
    pub health: f32,
    pub hurt_time: i32,
    pub is_moving_backward: bool,
    pub is_moving_towards_player: bool,
}

impl TargetState {
    /// Hitbox a player-shaped entity occupies at `position`, before the adapter
    /// replaces it with the exact box. Purely a fallback for paths that have no
    /// box on the wire yet.
    pub fn with_default_hitbox(mut self) -> TargetState {
        self.hitbox = crate::geom::Hitbox::around(self.position, 0.3, 1.8);
        self
    }
}

/// Mirrors `TriggerOptions` — same fields, same defaults.
#[derive(Debug, Clone, Copy)]
pub struct TriggerOptions {
    pub only_ground: bool,
    pub only_air: bool,
    pub only_move: bool,
    pub only_move_forward: bool,
    pub only_move_backward: bool,
    pub only_strafe: bool,
    pub only_when_target_goes_back: bool,
    pub only_when_target_approaches: bool,
    pub on_look: bool,
    pub only_current_view: bool,
    pub disable_on_mine: bool,
    pub only_on_click: bool,
    pub disabled_in_air: bool,
    pub min_distance: f32,
    pub max_distance: f32,
    pub chance: i32,
    pub delay_ticks: i32,
    pub delay_ms: i32,
    pub look_angle_threshold: f32,
}

impl Default for TriggerOptions {
    fn default() -> Self {
        TriggerOptions {
            only_ground: false,
            only_air: false,
            only_move: false,
            only_move_forward: false,
            only_move_backward: false,
            only_strafe: false,
            only_when_target_goes_back: false,
            only_when_target_approaches: false,
            on_look: false,
            only_current_view: false,
            disable_on_mine: false,
            only_on_click: false,
            disabled_in_air: false,
            min_distance: 0f32,
            max_distance: f32::MAX,
            chance: 100,
            delay_ticks: 0,
            delay_ms: 0,
            look_angle_threshold: 30.0f32,
        }
    }
}

/// `VelocityMode` — 0 LEGIT, 1 CLICK, 2 SPRINT.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum VelocityMode {
    Legit = 0,
    Click = 1,
    Sprint = 2,
}

impl VelocityMode {
    pub fn from_u8(v: u8) -> VelocityMode {
        match v {
            1 => VelocityMode::Click,
            2 => VelocityMode::Sprint,
            _ => VelocityMode::Legit,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct VelocityConfig {
    pub mode: VelocityMode,
    pub horizontal_min: f32,
    pub horizontal_max: f32,
    pub vertical_min: f32,
    pub vertical_max: f32,
    pub reduce_given_reduced: i32,
    pub reduce_given_skipped: i32,
    pub only_on_hit_frame: bool,
    pub trigger: TriggerOptions,
    // CLICK mode
    pub click_burst_min: i32,
    pub click_burst_max: i32,
    pub hurt_time_to_click: i32,
    pub when_facing_enemy_only: bool,
    pub max_angle_difference: f32,
    pub click_range: f32,
    // SPRINT mode
    pub sprint_hold_ticks: i32,
    pub sprint_gate_braced: i32,
    pub sprint_gate_idle: i32,
    pub sprint_sector: f32,
    // DELAY (KnockbackDelay)
    pub delay_chance: i32,
    pub max_delay_ticks: i32,
    pub require_weapon: bool,
}

impl Default for VelocityConfig {
    fn default() -> Self {
        VelocityConfig {
            mode: VelocityMode::Legit,
            horizontal_min: 0.4,
            horizontal_max: 0.6,
            vertical_min: 0.4,
            vertical_max: 0.6,
            reduce_given_reduced: 25,
            reduce_given_skipped: 25,
            only_on_hit_frame: false,
            trigger: TriggerOptions::default(),
            click_burst_min: 2,
            click_burst_max: 5,
            hurt_time_to_click: 8,
            when_facing_enemy_only: true,
            max_angle_difference: 90.0,
            click_range: 3.0,
            sprint_hold_ticks: 4,
            sprint_gate_braced: 25,
            sprint_gate_idle: 25,
            sprint_sector: 0.38,
            delay_chance: 100,
            max_delay_ticks: 4,
            require_weapon: false,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct VelocityContext {
    pub original_motion: Vec3,
    pub player: PlayerState,
    pub target: Option<TargetState>,
    pub is_knockback_hit: bool,
    pub packet_id: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Action {
    NoOp = 0,
    Pass = 1,
    Modify = 2,
    ClickBurst = 3,
    Cancel = 4,
}

#[derive(Debug, Clone, Copy)]
pub struct Decision {
    pub action: Action,
    pub reason: u8,
    pub motion: Vec3,
    pub target_id: i32,
    pub clicks: i32,
}

impl Decision {
    pub fn pass(motion: Vec3, reason: u8) -> Decision {
        Decision {
            action: Action::Pass,
            reason,
            motion,
            target_id: 0,
            clicks: 0,
        }
    }

    pub fn noop(reason: u8) -> Decision {
        Decision {
            action: Action::NoOp,
            reason,
            motion: Vec3::default(),
            target_id: 0,
            clicks: 0,
        }
    }

    pub fn modify(motion: Vec3) -> Decision {
        Decision {
            action: Action::Modify,
            reason: crate::wire::Reason::Modified as u8,
            motion,
            target_id: 0,
            clicks: 0,
        }
    }

    pub fn click_burst(target_id: i32, clicks: i32) -> Decision {
        Decision {
            action: Action::ClickBurst,
            reason: 0,
            motion: Vec3::default(),
            target_id,
            clicks,
        }
    }
}

// ── setback models ─────────────────────────────────────────────────────────

/// One movement vector a module produced at a given tick.
#[derive(Debug, Clone, PartialEq)]
pub struct ModuleMovementSample {
    pub module_id: String,
    pub tick: i64,
    pub movement: Vec3,
}

/// Snapshot of an anti-cheat pull-back.
#[derive(Debug, Clone)]
pub struct SetbackEvent {
    pub tick: i64,
    pub position_before: Vec3,
    pub position_after: Vec3,
    pub recent_movement_samples: Vec<ModuleMovementSample>,
}

impl SetbackEvent {
    pub fn displacement(&self) -> Vec3 {
        Vec3::new(
            self.position_before.x - self.position_after.x,
            self.position_before.y - self.position_after.y,
            self.position_before.z - self.position_after.z,
        )
    }

    pub fn displacement_magnitude(&self) -> f64 {
        self.displacement().length()
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum SetbackVerdict {
    ExternalCause { tick: i64, total_setbacks: i32 },
    BlameModule { module_id: String, blame_count: i32, threshold: i32 },
    CircuitBreak { module_id: String, blame_count: i32 },
}
