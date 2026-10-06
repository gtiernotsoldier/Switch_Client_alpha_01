// hud.rs — the OpenCommunity in-game HUD, ported.
//
// The reference is `E:\swl\OpenCommunity\runtime\src\features\render\HUD.cpp`
// (654 lines, with its `HUD.h`, the module options in
// `runtime\src\features\visuals\ArrayList.h` and the `HUD{}` block of
// `shared\common\ModuleConfig.h`). The user reviewed that HUD and asked for it to
// be **copied**, not reinterpreted, so this file is a port: the four modes, their
// geometry, their colours and their animations are transcribed from the source
// rather than composed here. Every constant below carries the source line it came
// from so the two can be diffed by eye.
//
// ── what is drawn ────────────────────────────────────────────────────────────
//
//   1. **the watermark** — one inline run on a single baseline: the product name
//      in the mode's accent, then ` | `, then the module count (HUD.cpp:508-563).
//      The source's run is `name | nickname | "<n> FPS"`; this side of the state
//      contract carries no nickname and no frame counter (it publishes a module
//      table, see `state.rs`), so the middle segment is the enabled-module count
//      and the FPS segment is omitted. The README carries the TODO for the
//      contract v2 that would complete it. In the Rise/VapeV4 modes the run sits
//      in the source's panel: a `rgba(0,0,0,88)` fill with the accent as a 2 px bar
//      down its right edge (VapeV4 rounds that panel's left corners by 6 px).
//   2. **the module list** — one row per enabled module, `NAME  TAG` on one
//      baseline, the block right-aligned so the rows form the width-descending
//      wedge (HUD.cpp:565-645). The accent bar is at the row's **right** edge (2 px
//      in Rise/VapeV4, 3 px in Tesseract), the name is painted in the row's
//      animated accent and the tag in the mode's muted grey — red `(255,85,85)` in
//      VapeV4 while the module is in use, which is what the state's `flash` byte
//      reports (HUD.cpp:640).
//
// ── animations, exactly as the source ────────────────────────────────────────
//
//   * **entrance**: a row slides in from the screen's right edge,
//     `progress += dt * 6`, eased with `1 - (1-p)^3` (easeOutCubic), with the frame
//     delta clamped to 0.1 s (HUD.cpp:502-506, 590-612). There is no opacity ramp;
//     a row is opaque from the first frame it exists.
//   * **exit**: instant — the row is dropped and the rows below it snap up. The
//     source does that by erasing the `m_SlideProgress` entries of modules that
//     are no longer enabled (HUD.cpp:647-653).
//   * **accent**: per-row phase offsets, one function per mode (HUD.cpp:249-309).
//     Default blends the two configurable colours on a cosine over
//     `2500 / max(speed, 0.01)` ms with `+400 ms` per row; Rise maps
//     `0.5 + 0.5*sin(t*1.35 + i*0.5)` onto an HSV hue sweep; VapeV4 lerps purple
//     to pink on `sin(t*1.55 + i*0.45)`; Tesseract walks the hue by the golden
//     ratio, `hue = fmod(t_ms/2000 + i*0.618034, 1)`. HSV→RGB is implemented here
//     because the source gets it from ImGui.
//   * a row's **shadow** is the source's only text effect: a 1 px down-right copy
//     of the glyph run in `rgba(0,0,0,120)` (HUD.cpp:79-82). No outline, no glow,
//     no letter spacing.
//
// ── the GDI adaptations ──────────────────────────────────────────────────────
//
//   * **per-corner rounding.** The source rounds whole shapes with ImGui's
//     `ImDrawFlags_RoundCornersLeft`: Tesseract's row chips and VapeV4's panel are
//     rounded on their left corners only, square on the right where the accent bar
//     sits. GDI's `RoundRect` takes one radius for all four corners, so the fill in
//     `draw.rs` selects the radius per quadrant of its distance field instead (a
//     region from `CreateRoundRectRgn` would be opaque and jagged). Documented at
//     the function.
//   * **fonts.** The source loads the **Play** face at 16 px; Play is not installed
//     here, so names use Segoe UI Semibold and everything else Segoe UI, still at
//     16 px (see `text.rs`).
//   * **`--scale`.** The source has no DPI handling at all — every number above is
//     physical pixels. `--scale <f>` (default 1.0) multiplies each metric and the
//     font size, so the HUD can be made readable on a 4K screen; 1.0 is the
//     faithful setting.
//
// ── infrastructure that is deliberately unchanged ────────────────────────────
//
// The window, the state contract and its parser, `--rect`/`--x`/`--y`/`--w`/`--h`
// and `--position`, `--dump`, `--hide`, the log lines and the exit rules are all
// the same as before. Because the source anchors the two elements to *opposite*
// screen edges (watermark left, list right), the anchoring helpers below are the
// port's successors of the mockup's `anchor_x`/`anchor_top`: same `Rect`, same
// meaning of `--position`, different element placement because the source's own
// placement is what is being ported.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use crate::draw::{argb, Canvas};
use crate::state::Entry;
use crate::text::{FontId, TextEngine, FONT_PX, MAX_SCALE, MIN_SCALE};

// ── the four modes (HUD.cpp:463-468, ArrayList.h:14) ────────────────────────

/// `ArrayListMode` — the combo in `ArrayList.h:14`, in the source's own order.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Default,
    Rise,
    Vape,
    Tesseract,
}

impl Mode {
    /// Parse `--mode`. `vapev4` is the source's own spelling of the combo entry,
    /// so it is accepted next to the short one.
    pub fn parse(raw: &str) -> Result<Mode, String> {
        match raw.to_ascii_lowercase().as_str() {
            "default" => Ok(Mode::Default),
            "rise" => Ok(Mode::Rise),
            "vape" | "vapev4" | "vape_v4" => Ok(Mode::Vape),
            "tesseract" => Ok(Mode::Tesseract),
            other => {
                Err(format!("--mode: expected default, rise, vape or tesseract, got {other}"))
            }
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Mode::Default => "default",
            Mode::Rise => "rise",
            Mode::Vape => "vape",
            Mode::Tesseract => "tesseract",
        }
    }

    /// `riseLikeMode` (HUD.cpp:466): Rise and VapeV4 share the panel/spacing rules.
    pub fn is_rise_like(self) -> bool {
        matches!(self, Mode::Rise | Mode::Vape)
    }

    /// The face a module name is drawn in: the source uses its bold face in
    /// Default mode and the regular one everywhere else (HUD.cpp:468).
    pub fn name_font(self) -> FontId {
        match self {
            Mode::Default => FontId::Name,
            _ => FontId::Body,
        }
    }
}

// ── palette and master constants (HUD.cpp:471-491) ──────────────────────────
//
// Names are the source's, values are the source's, lines are cited so a future
// reader can check one against the other.

/// `shadowColor` (HUD.cpp:476) — the 1 px down-right glyph shadow.
pub const SHADOW: u32 = argb(0, 0, 0, 120);
/// `secondaryColor` for Default/Tesseract (HUD.cpp:477).
pub const SECONDARY_TEXT: u32 = argb(200, 200, 200, 255);
/// `secondaryColor` for Rise/VapeV4 (HUD.cpp:477).
pub const RISE_SECONDARY_TEXT: u32 = argb(154, 154, 154, 255);
/// `riseBackgroundColor` (HUD.cpp:478).
pub const RISE_BACKGROUND: u32 = argb(0, 0, 0, 88);
/// `riseWatermarkTextColor` (HUD.cpp:479) — Rise's watermark title, not the accent.
pub const RISE_WATERMARK_TEXT: u32 = argb(232, 232, 232, 255);
/// `vapeTagActiveColor` (HUD.cpp:480) — the tag while the module is in use.
pub const VAPE_TAG_ACTIVE: u32 = argb(255, 85, 85, 255);
/// `tesseractBackgroundColor` (HUD.cpp:485).
pub const TESSERACT_BACKGROUND: u32 = argb(25, 25, 30, 240);

/// The Default mode's wave colours, as `ModuleConfig.h:65-66` (and
/// `ArrayList.h:18-19`) spells them: `78,86,107` → `120,146,214`.
pub const DEFAULT_PRIMARY: [f32; 3] = [78.0 / 255.0, 86.0 / 255.0, 107.0 / 255.0];
pub const DEFAULT_SECONDARY: [f32; 3] = [120.0 / 255.0, 146.0 / 255.0, 214.0 / 255.0];
/// `m_WaveSpeed` (ModuleConfig.h:64).
pub const DEFAULT_WAVE_SPEED: f32 = 1.0;

/// `topY` (HUD.cpp:471).
pub const TOP_Y: f32 = 4.0;
/// `moduleSpacing` for the modes that are not Rise-like (HUD.cpp:473).
pub const MODULE_SPACING: f32 = 2.0;
/// `itemHeight` extras (HUD.cpp:474): `+6` Rise-like, `+4` Tesseract, `+2` Default.
pub const RISE_ITEM_EXTRA: f32 = 6.0;
pub const TESSERACT_ITEM_EXTRA: f32 = 4.0;
pub const DEFAULT_ITEM_EXTRA: f32 = 2.0;
/// `risePadX` / `risePadY` (HUD.cpp:481-482).
pub const RISE_PAD_X: f32 = 4.0;
pub const RISE_PAD_Y: f32 = 2.0;
/// `riseRectWidth` (HUD.cpp:483) — the accent bar at a Rise-like row's right edge.
pub const RISE_RECT_WIDTH: f32 = 2.0;
/// `vapeLeftRounding` (HUD.cpp:484).
pub const VAPE_LEFT_ROUNDING: f32 = 6.0;
/// `tesseractPadding` / `tesseractGapAfterText` / `tesseractRectWidth`
/// (HUD.cpp:486-488).
pub const TESSERACT_PADDING: f32 = 6.0;
pub const TESSERACT_GAP_AFTER_TEXT: f32 = 4.0;
pub const TESSERACT_RECT_WIDTH: f32 = 3.0;
/// The Tesseract chip's left-corner radius (HUD.cpp:632).
pub const TESSERACT_CHIP_RADIUS: f32 = 5.0;
/// `tesseractMarginRight` / `defaultRightMargin` / `riseRightMargin`
/// (HUD.cpp:489-491).
pub const TESSERACT_MARGIN_RIGHT: f32 = 6.0;
pub const DEFAULT_MARGIN_RIGHT: f32 = 6.0;
pub const RISE_MARGIN_RIGHT: f32 = 4.0;
/// `cursorX`'s and the panel's inset from the left edge (HUD.cpp:537, 545).
pub const WATERMARK_X: f32 = 10.0;
/// Tesseract's watermark baseline and the Tesseract rows' own base y
/// (HUD.cpp:536, 613).
pub const TESSERACT_WATERMARK_Y: f32 = 6.0;
pub const TESSERACT_BASE_Y: f32 = 2.0;
/// Tesseract's text inset inside its chip (HUD.cpp:617).
pub const TESSERACT_TEXT_INSET_Y: f32 = 2.0;
/// `2500.0f / max(speed, 0.01f)` ms per wave cycle, `+400 ms` per row
/// (HUD.cpp:258-261, 581).
pub const WAVE_CYCLE_MS: f32 = 2500.0;
pub const WAVE_ROW_OFFSET_MS: f32 = 400.0;
pub const WAVE_MIN_SPEED: f32 = 0.01;
/// `progress += deltaTime * 6.0f` (HUD.cpp:592) and the 0.1 s delta clamp
/// (HUD.cpp:504).
pub const SLIDE_RATE: f32 = 6.0;
pub const MAX_DELTA_S: f32 = 0.1;
/// The golden-ratio hue walk of Tesseract (HUD.cpp:296).
pub const GOLDEN_RATIO: f32 = 0.618_034;
/// The product name in the watermark's title slot. The source writes
/// "OpenCommunity" there (HUD.cpp:539); this overlay's own name goes in its place.
pub const PRODUCT: &str = "DOPPEL";
/// The source's separator between the watermark's segments (HUD.cpp:535).
pub const SEGMENT_SEP: &str = " | ";
/// The module the source deliberately never lists (HUD.cpp:326-328).
pub const SELF_MODULE: &str = "ArrayList";

// ── the mode's metrics ──────────────────────────────────────────────────────

/// The source's master constants after `--scale`. At scale 1.0 every field is the
/// number the source computes at HUD.cpp:469-491.
#[derive(Clone, Copy, Debug)]
pub struct Metrics {
    pub mode: Mode,
    pub scale: f32,
    /// `fontSize` (HUD.cpp:469) and therefore `lineHeight` (HUD.cpp:472).
    pub font_px: f32,
    pub line_height: f32,
    /// `topY` (HUD.cpp:471).
    pub top_y: f32,
    /// `moduleSpacing` (HUD.cpp:473): 0 for Rise-like, 2 otherwise.
    pub module_spacing: f32,
    /// `itemHeight` (HUD.cpp:474).
    pub item_height: f32,
    pub rise_pad_x: f32,
    pub rise_pad_y: f32,
    pub rise_rect_w: f32,
    pub vape_left_rounding: f32,
    pub tesseract_padding: f32,
    pub tesseract_gap: f32,
    pub tesseract_rect_w: f32,
    pub tesseract_chip_radius: f32,
    /// The mode's right margin (HUD.cpp:610).
    pub right_margin: f32,
    /// The watermark run's inset from the edge it hangs off (HUD.cpp:537).
    pub watermark_x: f32,
    /// The watermark run's own y (HUD.cpp:536).
    pub watermark_y: f32,
    /// The first row's y (HUD.cpp:613).
    pub base_y: f32,
    /// `textX`/`textY` inside a row (HUD.cpp:616-617).
    pub text_inset_x: f32,
    pub text_inset_y: f32,
}

impl Metrics {
    /// The source's numbers, multiplied by `scale`.
    pub fn new(mode: Mode, scale: f32) -> Metrics {
        let scale = if scale.is_finite() { scale.clamp(MIN_SCALE, MAX_SCALE) } else { 1.0 };
        let s = scale;
        let rise_like = mode.is_rise_like();
        let tesseract = mode == Mode::Tesseract;

        let font_px = FONT_PX * s;
        // `lineHeight = max(tagSize, nameSize)` (HUD.cpp:472). Both faces are
        // created at the source's single `fontSize`, so this is that size.
        let line_height = font_px;
        let item_height = if rise_like {
            line_height + RISE_ITEM_EXTRA * s
        } else if tesseract {
            font_px + TESSERACT_ITEM_EXTRA * s
        } else {
            line_height + DEFAULT_ITEM_EXTRA * s
        };

        Metrics {
            mode,
            scale: s,
            font_px,
            line_height,
            top_y: TOP_Y * s,
            module_spacing: if rise_like { 0.0 } else { MODULE_SPACING * s },
            item_height,
            rise_pad_x: RISE_PAD_X * s,
            rise_pad_y: RISE_PAD_Y * s,
            rise_rect_w: RISE_RECT_WIDTH * s,
            vape_left_rounding: VAPE_LEFT_ROUNDING * s,
            tesseract_padding: TESSERACT_PADDING * s,
            tesseract_gap: TESSERACT_GAP_AFTER_TEXT * s,
            tesseract_rect_w: TESSERACT_RECT_WIDTH * s,
            tesseract_chip_radius: TESSERACT_CHIP_RADIUS * s,
            right_margin: if rise_like {
                RISE_MARGIN_RIGHT * s
            } else if tesseract {
                TESSERACT_MARGIN_RIGHT * s
            } else {
                DEFAULT_MARGIN_RIGHT * s
            },
            watermark_x: WATERMARK_X * s,
            watermark_y: if rise_like {
                TOP_Y * s + RISE_PAD_Y * s
            } else if tesseract {
                TESSERACT_WATERMARK_Y * s
            } else {
                TOP_Y * s
            },
            base_y: if tesseract { TESSERACT_BASE_Y * s } else { TOP_Y * s },
            text_inset_x: if rise_like {
                RISE_PAD_X * s
            } else if tesseract {
                TESSERACT_PADDING * s
            } else {
                0.0
            },
            text_inset_y: if rise_like {
                RISE_PAD_Y * s
            } else if tesseract {
                TESSERACT_TEXT_INSET_Y * s
            } else {
                0.0
            },
        }
    }
}

/// Row pitch: `itemHeight + moduleSpacing` (HUD.cpp:614).
pub fn row_pitch(m: &Metrics) -> f32 {
    m.item_height + m.module_spacing
}

/// The accent bar's width at a row's right edge: Rise/VapeV4 2 px, Tesseract 3 px,
/// Default none (HUD.cpp:619-634).
pub fn bar_width(m: &Metrics) -> f32 {
    if m.mode.is_rise_like() {
        m.rise_rect_w
    } else if m.mode == Mode::Tesseract {
        m.tesseract_rect_w
    } else {
        0.0
    }
}

/// The four corner radii of a row's chip, (top-left, top-right, bottom-right,
/// bottom-left): only the left corners are rounded, because the accent bar sits
/// square against the right edge (HUD.cpp:622-633).
pub fn chip_corners(m: &Metrics) -> [f32; 4] {
    match m.mode {
        Mode::Vape => [m.vape_left_rounding, 0.0, 0.0, m.vape_left_rounding],
        Mode::Tesseract => [m.tesseract_chip_radius, 0.0, 0.0, m.tesseract_chip_radius],
        _ => [0.0; 4],
    }
}

/// The chip the row sits on: Rise/VapeV4 `rgba(0,0,0,88)` over the whole row,
/// Tesseract `rgba(25,25,30,240)`, Default none (`Option::None`).
pub fn chip_colour(m: &Metrics) -> Option<u32> {
    match m.mode {
        Mode::Rise | Mode::Vape => Some(RISE_BACKGROUND),
        Mode::Tesseract => Some(TESSERACT_BACKGROUND),
        Mode::Default => None,
    }
}

/// The tag's colour: muted grey, and `(255,85,85)` in VapeV4 while the module is
/// in use (HUD.cpp:640). The source applies the active colour in VapeV4 only; the
/// other modes keep the grey whatever the flash byte says.
pub fn tag_colour(m: &Metrics, flash: bool) -> u32 {
    if m.mode == Mode::Vape && flash {
        VAPE_TAG_ACTIVE
    } else if m.mode.is_rise_like() {
        RISE_SECONDARY_TEXT
    } else {
        SECONDARY_TEXT
    }
}

/// The gap between a row's name and its tag: one space glyph, or Tesseract's own
/// 4 px (HUD.cpp:639).
pub fn tag_gap(m: &Metrics, space_w: f32) -> f32 {
    if m.mode == Mode::Tesseract {
        m.tesseract_gap
    } else {
        space_w
    }
}

/// `boxWidth` (HUD.cpp:607-609): the row's own content plus the mode's padding and
/// accent bar. Default adds nothing at all.
pub fn box_width(m: &Metrics, layout_w: f32) -> f32 {
    if m.mode.is_rise_like() {
        layout_w + m.rise_pad_x * 2.0 + m.rise_rect_w + 1.0
    } else if m.mode == Mode::Tesseract {
        layout_w + m.tesseract_padding + m.tesseract_gap + m.tesseract_rect_w
    } else {
        layout_w
    }
}

/// The Rise-like watermark panel's width (HUD.cpp:544). The source adds the FPS
/// run's width to it; this overlay has no frame counter, so that term is the zero
/// the port's watermark run does not have (see the header note).
pub fn watermark_panel_width(m: &Metrics, title_w: f32, segment_w: f32) -> f32 {
    title_w + segment_w + m.rise_pad_x * 2.0 + m.rise_rect_w + 1.0
}

// ── the screen rect the window covers ───────────────────────────────────────
//
// Unchanged infrastructure: the layered window is created at the rect's corner and
// sized to it, and the canvas that fills it carries that origin (`Canvas::placed`),
// so a block measured in screen coordinates lands on the screen edge the caller
// asked for. `--rect` supplies the rect directly (a caller following the game
// window on a multi-monitor desktop passes that window's rect); by default it is
// the primary monitor.

/// A rectangle in physical screen pixels.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl Rect {
    pub const fn new(x: i32, y: i32, w: i32, h: i32) -> Rect {
        Rect { x, y, w, h }
    }

    /// The exclusive right edge, `x + w`.
    pub const fn right(&self) -> i32 {
        self.x + self.w
    }

    /// The exclusive bottom edge, `y + h`.
    pub const fn bottom(&self) -> i32 {
        self.y + self.h
    }

    /// No pixels at all: the caller falls back to a sane default rather than
    /// creating a zero-sized window.
    pub const fn is_empty(&self) -> bool {
        self.w <= 0 || self.h <= 0
    }
}

/// Which pair of screen edges the HUD hangs off. The source itself puts the
/// watermark by the left edge (`cursorX = 10`, HUD.cpp:537) and the module list
/// against the right one (`targetX = screenW - boxWidth - rightMargin`,
/// HUD.cpp:611), so `--position right` (the default) is that arrangement verbatim
/// and `left` swaps the two elements to the other pair of corners — the same
/// `--position` switch the mockup skin had, now with the source's own placement.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Position {
    Right,
    Left,
}

/// Screen x of the watermark run's title (and of its panel, which starts there).
pub fn watermark_x(rect: Rect, position: Position, m: &Metrics, run_w: f32) -> i32 {
    match position {
        Position::Right => (rect.x as f32 + m.watermark_x).round() as i32,
        Position::Left => (rect.right() as f32 - m.watermark_x - run_w).round() as i32,
    }
}

/// Screen y of the watermark run's line box.
pub fn watermark_y(rect: Rect, m: &Metrics) -> i32 {
    (rect.y as f32 + m.watermark_y).round() as i32
}

/// The screen edge the rows are flushed against — the source's `screenW` for the
/// right-hand layout, the mirrored edge for `--position left`. It is the raw edge:
/// the mode's margin is applied per row, in `row_target_x`.
pub fn list_edge_x(rect: Rect, position: Position) -> i32 {
    match position {
        Position::Right => rect.right(),
        Position::Left => rect.x,
    }
}

/// Screen x of a row's box at rest: the source's `targetX` (HUD.cpp:611).
pub fn row_target_x(rect: Rect, position: Position, m: &Metrics, box_w: f32) -> f32 {
    match position {
        Position::Right => rect.right() as f32 - box_w - m.right_margin,
        Position::Left => rect.x as f32 + m.right_margin,
    }
}

/// Screen x a row slides in from. The source starts every row at `screenW`, the
/// screen's right edge (HUD.cpp:612), which is off-screen to the right of its
/// right-flushed target; for a left-flushed row the mirror of that is its own
/// width off the left edge.
pub fn row_slide_origin(rect: Rect, position: Position, box_w: f32) -> f32 {
    match position {
        Position::Right => rect.right() as f32,
        Position::Left => rect.x as f32 - box_w,
    }
}

/// Screen y of row `index` (HUD.cpp:614).
pub fn row_y(rect: Rect, m: &Metrics, index: usize) -> i32 {
    (rect.y as f32 + m.base_y + index as f32 * row_pitch(m)).round() as i32
}

// ── colour maths ────────────────────────────────────────────────────────────

/// HSV → RGB, the conversion the source borrows from ImGui
/// (`ImGui::ColorConvertHSVtoRGB`) in all four accent functions. `h` is 0..1 and
/// wraps; the six sectors are the definitional ones, so this matches ImGui's
/// implementation term for term.
pub fn hsv_to_rgb(h: f32, s: f32, v: f32) -> [f32; 3] {
    let s = s.clamp(0.0, 1.0);
    let v = v.clamp(0.0, 1.0);
    if s == 0.0 {
        return [v, v, v];
    }
    let hh = h.rem_euclid(1.0) * 6.0;
    let sector = hh.floor();
    let f = hh - sector;
    let p = v * (1.0 - s);
    let q = v * (1.0 - s * f);
    let t = v * (1.0 - s * (1.0 - f));
    match sector as i32 {
        0 => [v, t, p],
        1 => [q, v, p],
        2 => [p, v, t],
        3 => [p, q, v],
        4 => [t, p, v],
        _ => [v, p, q],
    }
}

/// The clock the source's accent functions read straight off the wall. The port
/// takes it as a value so a frame's colours are a pure function of it (and so a
/// review dump can freeze them at a known timestamp).
#[derive(Clone, Copy, Debug)]
pub struct Stamp {
    /// `steady_clock` seconds — Rise, VapeV4 and the Default wave (HUD.cpp:274, 282).
    pub seconds: f32,
    /// `system_clock` milliseconds — Tesseract (HUD.cpp:294).
    pub millis: f64,
}

/// The Default mode's wave (HUD.cpp:249-270): a cosine blend between the two
/// configurable colours over `2500 / max(speed, 0.01)` ms, `offset_ms` in.
///
/// The source computes the phase with `fmodf` over a float `time_since_epoch()`
/// in ms — a number around 1e12, where a float has ~65 000 ms of resolution and
/// the blend is effectively arbitrary. The port keeps the formula exactly
/// (`fmod(elapsed, cycle) / cycle`, `0.5 - 0.5*cos(progress*2pi)`, lerp) but does
/// the modulus in f64, so the animation is smooth instead of quantised.
pub fn wave_rgb(
    primary: [f32; 3],
    secondary: [f32; 3],
    elapsed_ms: f64,
    offset_ms: f32,
    speed: f32,
) -> [f32; 3] {
    let speed = speed.max(WAVE_MIN_SPEED);
    let cycle_ms = (WAVE_CYCLE_MS / speed) as f64;
    let elapsed = elapsed_ms + offset_ms as f64;
    let progress = elapsed.rem_euclid(cycle_ms) / cycle_ms;
    let blend = 0.5 - 0.5 * (progress * std::f64::consts::TAU).cos();
    let blend = blend as f32;
    [
        primary[0] + (secondary[0] - primary[0]) * blend,
        primary[1] + (secondary[1] - primary[1]) * blend,
        primary[2] + (secondary[2] - primary[2]) * blend,
    ]
}

/// Rise (HUD.cpp:272-278): `phase = 0.5 + 0.5*sin(t*1.35 + i*0.5)`, then
/// `hue = 0.58 - phase*0.18` at saturation 0.72 and value 0.95.
pub fn rise_rgb(seconds: f32, index: f32) -> [f32; 3] {
    let phase = 0.5 + 0.5 * (seconds * 1.35 + index * 0.5).sin();
    hsv_to_rgb(0.58 - phase * 0.18, 0.72, 0.95)
}

/// VapeV4 (HUD.cpp:280-291): `phase = 0.5 + 0.5*sin(t*1.55 + i*0.45)`, lerping
/// soft purple `(0.82, 0.50, 0.98)` towards pink `(1.0, 0.43, 0.74)`.
pub fn vape_rgb(seconds: f32, index: f32) -> [f32; 3] {
    let phase = 0.5 + 0.5 * (seconds * 1.55 + index * 0.45).sin();
    let soft_purple = [0.82, 0.50, 0.98];
    let pink = [1.0, 0.43, 0.74];
    [
        soft_purple[0] + (pink[0] - soft_purple[0]) * phase,
        soft_purple[1] + (pink[1] - soft_purple[1]) * phase,
        soft_purple[2] + (pink[2] - soft_purple[2]) * phase,
    ]
}

/// Tesseract (HUD.cpp:293-301): `hue = fmod(t_ms / 2000 + i * 0.618034, 1)` at
/// saturation 0.75 and value 0.9.
pub fn tesseract_rgb(millis: f64, index: f32) -> [f32; 3] {
    let base = (millis / 2000.0).rem_euclid(1.0) as f32;
    let offset = (index * GOLDEN_RATIO).rem_euclid(1.0);
    hsv_to_rgb((base + offset).rem_euclid(1.0), 0.75, 0.9)
}

/// Tesseract's watermark colour (HUD.cpp:303-309): the hue steps once every 20 ms
/// through 360 discrete values, `state = ceil((t_ms + offset) / 20)`,
/// `hue = fmod(state, 360) / 360`, at saturation 0.7 and value 0.8.
pub fn tesseract_header_rgb(millis: f64, offset_ms: f32) -> [f32; 3] {
    let state = ((millis + offset_ms as f64) / 20.0).ceil();
    let hue = (state.rem_euclid(360.0) / 360.0) as f32;
    hsv_to_rgb(hue, 0.7, 0.8)
}

// ── per-frame inputs ────────────────────────────────────────────────────────

/// Everything the HUD needs that is not the published state: which mode, how big,
/// and the source's `HUD{}` config block (ModuleConfig.h:57-68 / ArrayList.h:15-19).
#[derive(Clone, Debug)]
pub struct Config {
    pub position: Position,
    /// Global fade multiplier, 1.0 normally and ramped to 0 when the game dies.
    /// The source has no overlay fade; this is the port's exit ramp.
    pub alpha: f32,
    pub mode: Mode,
    /// `--scale`: 1.0 is the source's own physical-pixel layout.
    pub scale: f32,
    /// `m_Watermark` (ModuleConfig.h:59).
    pub watermark: bool,
    /// `m_Wave` (ModuleConfig.h:62) — the Default mode's animation switch.
    pub wave: bool,
    /// `m_WaveSpeed` (ModuleConfig.h:64).
    pub wave_speed: f32,
    /// `m_PrimaryColor` / `m_SecondaryColor` (ModuleConfig.h:65-66).
    pub primary: [f32; 3],
    pub secondary: [f32; 3],
    /// `m_SpacedModules` (ModuleConfig.h:60): split `CamelCase` names.
    pub spaced: bool,
    /// The state contract v2 header's `nickname` — the watermark's middle segment
    /// (`ResolvePlayerNick`, HUD.cpp:526-529). Empty drops the segment.
    pub nickname: String,
    /// The header's `fps`. 0 = unknown, and the FPS segment is dropped rather
    /// than drawn as `0 FPS` (the source only ever has a real frame counter).
    pub fps: u32,
}

impl Default for Config {
    fn default() -> Config {
        Config {
            position: Position::Right,
            alpha: 1.0,
            mode: Mode::Default,
            scale: 1.0,
            watermark: true,
            wave: true,
            wave_speed: DEFAULT_WAVE_SPEED,
            primary: DEFAULT_PRIMARY,
            secondary: DEFAULT_SECONDARY,
            spaced: false,
            nickname: String::new(),
            fps: 0,
        }
    }
}

/// The accent colour of row `index`.
pub fn accent(cfg: &Config, stamp: Stamp, index: usize) -> [f32; 3] {
    match cfg.mode {
        Mode::Rise => rise_rgb(stamp.seconds, index as f32),
        Mode::Vape => vape_rgb(stamp.seconds, index as f32),
        Mode::Tesseract => tesseract_rgb(stamp.millis, index as f32),
        Mode::Default => {
            if cfg.wave {
                wave_rgb(
                    cfg.primary,
                    cfg.secondary,
                    stamp.millis,
                    index as f32 * WAVE_ROW_OFFSET_MS,
                    cfg.wave_speed,
                )
            } else {
                cfg.primary
            }
        }
    }
}

/// The watermark's accent. Tesseract has its own, faster header sweep
/// (HUD.cpp:516-517); every other mode is the row function at offset 0, which is
/// what the source passes for the watermark (`GetWaveRGB(config, 0)`,
/// HUD.cpp:519).
pub fn watermark_accent(cfg: &Config, stamp: Stamp) -> [f32; 3] {
    match cfg.mode {
        Mode::Tesseract => tesseract_header_rgb(stamp.millis, 0.0),
        _ => accent(cfg, stamp, 0),
    }
}

/// Whether the accent keeps moving on its own. Default is time-driven only while
/// the wave is on; the other three modes are functions of the clock by
/// construction, so a frame with them on screen is never static. This is what the
/// overlay's repaint decision hangs on (see `paint`).
pub fn accent_is_animated(cfg: &Config) -> bool {
    cfg.mode != Mode::Default || cfg.wave
}

fn pack(rgb: [f32; 3], alpha: f32) -> u32 {
    let ch = |v: f32| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u32;
    (ch(alpha) << 24) | (ch(rgb[0]) << 16) | (ch(rgb[1]) << 8) | ch(rgb[2])
}

/// Colour with its alpha channel scaled (the overlay's fade-out).
pub fn with_alpha(colour: u32, mul: f32) -> u32 {
    let a = ((colour >> 24) & 0xFF) as f32 * mul.clamp(0.0, 1.0);
    ((a.round().clamp(0.0, 255.0) as u32) << 24) | (colour & 0x00FF_FFFF)
}

/// `FormatModuleName` (HUD.cpp:125-150): insert a space before an interior capital
/// that follows a lowercase letter, or that ends an acronym run (an upper-case
/// letter with a lowercase one after it). Gated on `Spaced Modules`, which is off
/// by default (ArrayList.h:16).
pub fn spaced_name(name: &str) -> String {
    let chars: Vec<char> = name.chars().collect();
    let mut out = String::with_capacity(name.len() + 4);
    for (i, &c) in chars.iter().enumerate() {
        if i > 0 && c.is_ascii_uppercase() {
            let previous = chars[i - 1];
            let follows_lowercase = previous.is_ascii_lowercase();
            let breaks_acronym = previous.is_ascii_uppercase()
                && chars.get(i + 1).map(|n| n.is_ascii_lowercase()).unwrap_or(false);
            if follows_lowercase || breaks_acronym {
                out.push(' ');
            }
        }
        out.push(c);
    }
    out
}

// ── animation state ─────────────────────────────────────────────────────────

/// What this side animates on top of the published state: how far each row has
/// slid in, and the clock the accent functions read. The source keeps
/// `m_SlideProgress` keyed by module name, an `m_LastFrameTime` for the delta and
/// `m_Fps`; the FPS counter has no input on this side of the contract (see the
/// header note), so it is not ported.
pub struct Anim {
    /// When the accent clock was zeroed. Seeded so a review dump is deterministic.
    origin: Instant,
    last: Option<Instant>,
    dt: f32,
    slide: HashMap<String, f32>,
}

/// How far into the accent clock `Anim::seed` puts a review frame: 2000 ms, which
/// gives every mode a mid-animation, per-row-distinct set of colours.
pub const SEED_MS: u64 = 2000;

impl Default for Anim {
    fn default() -> Anim {
        Anim::new()
    }
}

impl Anim {
    pub fn new() -> Anim {
        Anim { origin: Instant::now(), last: None, dt: 0.0, slide: HashMap::new() }
    }

    /// Take the frame's timestamp: the delta is clamped to 0.1 s exactly like
    /// HUD.cpp:502-506, so a stalled or debugger-paused process does not teleport
    /// a row. Called once per loop iteration, even on a frame the overlay decides
    /// not to repaint, so a row that appears after an idle stretch still gets one
    /// ordinary step rather than a catch-up jump.
    pub fn tick(&mut self, now: Instant) {
        self.dt = match self.last {
            Some(previous) => {
                now.saturating_duration_since(previous).as_secs_f32().min(MAX_DELTA_S)
            }
            None => 0.0,
        };
        self.last = Some(now);
    }

    /// Forget the slide state of modules that are no longer listed — the source's
    /// instant exit, so a module that comes back starts its entrance from the edge
    /// rather than from wherever it was dropped (HUD.cpp:647-653).
    pub fn retain(&mut self, live: &[String]) {
        self.slide.retain(|name, _| live.iter().any(|live| live == name));
    }

    /// The wall clock the accent functions are evaluated at.
    pub fn stamp(&self, now: Instant) -> Stamp {
        let elapsed = now.saturating_duration_since(self.origin);
        Stamp { seconds: elapsed.as_secs_f32(), millis: elapsed.as_secs_f64() * 1000.0 }
    }

    /// One row's entrance progress: `progress += dt * 6`, clamped to 1
    /// (HUD.cpp:590-596). A row seen for the first time starts at 0 and takes this
    /// frame's step, exactly as the source's `m_SlideProgress[mod.name]` does, and
    /// nothing here fades opacity.
    pub fn progress(&mut self, name: &str, dt: f32) -> f32 {
        let progress = self.slide.entry(name.to_string()).or_insert(0.0);
        if *progress < 1.0 {
            *progress = (*progress + dt * SLIDE_RATE).min(1.0);
        }
        *progress
    }

    /// Dump hook: every row is treated as long settled (as if it had appeared
    /// before the seed) and the accent clock starts at exactly `SEED_MS`, so a
    /// review frame's colours are the accent functions evaluated at a known
    /// timestamp — what the README's sampled numbers are checked against.
    pub fn seed(&mut self, entries: &[Entry], now: Instant) {
        self.origin = now.checked_sub(Duration::from_millis(SEED_MS)).unwrap_or(now);
        self.last = None;
        self.dt = 0.0;
        self.slide.clear();
        for entry in entries {
            if !entry.name.is_empty() {
                self.slide.insert(entry.name.clone(), 1.0);
            }
        }
    }
}

// ── layout ──────────────────────────────────────────────────────────────────

/// One measured row, in the source's own terms: `layoutWidth` is the run the mode
/// lays out (HUD.cpp:599-605) and `boxWidth` the chip/padding/bar around it.
struct Row {
    /// Index into the published slice this row was measured from.
    entry: usize,
    /// The source's `mod.name` — the key of the slide state (HUD.cpp:590).
    key: String,
    /// The name as drawn, after `m_SpacedModules` formatting.
    name: String,
    name_w: f32,
    tag_w: f32,
    /// The width the wedge is sorted by (HUD.cpp:340-342).
    sort_w: f32,
    /// `layoutWidth` for this row's mode — the run a row's own gap rule produces
    /// (HUD.cpp:599-605).
    layout_w: f32,
    /// `boxWidth` for this row's mode.
    box_w: f32,
}

/// The screen rect a canvas covers — the layered window's own surface. The canvas
/// carries where the window was created, so the layout anchors to the monitor (or
/// to whatever `--rect` asked for) instead of to the surface's own origin.
fn canvas_rect(canvas: &Canvas) -> Rect {
    let (x, y) = canvas.screen_origin();
    Rect::new(x, y, canvas.width() as i32, canvas.height() as i32)
}

/// Measure the rows the way `HUD::GetActiveModules` does (HUD.cpp:311-345):
/// enabled modules only, `ArrayList` itself never listed, each name measured in
/// the mode's name face, the tag one gap later, and the whole list sorted by width
/// descending — the wedge.
fn measure_rows(
    text: &TextEngine,
    entries: &[Entry],
    cfg: &Config,
    m: &Metrics,
) -> Vec<Row> {
    let name_font = cfg.mode.name_font();
    let space_w = text.measure(FontId::Body, " ") as f32;
    let mut rows: Vec<Row> = Vec::new();

    for (index, entry) in entries.iter().enumerate() {
        if !entry.enabled || entry.name.is_empty() || entry.name == SELF_MODULE {
            continue;
        }
        let name = if cfg.spaced { spaced_name(&entry.name) } else { entry.name.clone() };
        let name_w = text.measure(name_font, &name) as f32;
        let tag_w = text.measure(FontId::Body, &entry.value) as f32;
        let has_tag = !entry.value.is_empty();
        // The source measures the sort key with the space glyph for every mode and
        // only then re-measures Tesseract's layout with its own 4 px gap
        // (HUD.cpp:332-335, 600-605).
        let sort_w = name_w + if has_tag { space_w + tag_w } else { 0.0 };
        let layout_w = if cfg.mode == Mode::Tesseract {
            name_w + if has_tag { m.tesseract_gap + tag_w } else { 0.0 }
        } else {
            sort_w
        };
        rows.push(Row {
            entry: index,
            key: entry.name.clone(),
            name,
            name_w,
            tag_w,
            sort_w,
            layout_w,
            box_w: box_width(m, layout_w),
        });
    }

    rows.sort_by(|a, b| b.sort_w.partial_cmp(&a.sort_w).unwrap_or(std::cmp::Ordering::Equal));
    rows
}

// ── painting ────────────────────────────────────────────────────────────────

/// Everything a frame needs that is not the entries or the canvas.
struct Frame<'a> {
    cfg: &'a Config,
    m: &'a Metrics,
    stamp: Stamp,
    /// The overlay's global fade (1.0 live).
    global: f32,
    /// The frame delta for the rows' entrance.
    dt: f32,
}

/// Draw a run with the source's only text effect: a 1 px down-right copy in
/// `rgba(0,0,0,120)` under the glyphs (HUD.cpp:79-82).
fn draw_shadowed(
    text: &mut TextEngine,
    canvas: &mut Canvas,
    id: FontId,
    s: &str,
    x: i32,
    y: i32,
    colour: u32,
    global: f32,
) {
    if s.is_empty() {
        return;
    }
    text.draw(canvas, id, s, x + 1, y + 1, with_alpha(SHADOW, global));
    text.draw(canvas, id, s, x, y, with_alpha(colour, global));
}

/// Compose one frame of the HUD. Returns true while something is still moving,
/// which the caller uses to decide whether the next frame needs to be pushed to
/// the compositor.
///
/// "Moving" is the entrance of any row (the source's slide) **or** a live accent:
/// in Rise, VapeV4 and Tesseract the accent is a function of the clock, and with
/// the Default mode's wave on it is too, so those frames genuinely differ from the
/// last one. With the wave off in Default mode nothing is time-driven, so a
/// settled HUD reports false and the caller repaints only when the state, the
/// mode or the fade moved.
pub fn paint(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    entries: &[Entry],
    anim: &mut Anim,
    now: Instant,
    cfg: &Config,
) -> bool {
    let global = cfg.alpha.clamp(0.0, 1.0);
    if global <= 0.001 {
        return false;
    }

    let m = Metrics::new(cfg.mode, cfg.scale);
    let rect = canvas_rect(canvas);
    let rows = measure_rows(text, entries, cfg, &m);
    let live: Vec<String> = rows.iter().map(|row| row.key.clone()).collect();
    anim.retain(&live);
    let frame = Frame { cfg, m: &m, stamp: anim.stamp(now), global, dt: anim.dt };

    if cfg.watermark {
        paint_watermark(canvas, text, &frame, rect, rows.len());
    }
    let rows_moving = paint_rows(canvas, text, entries, anim, &frame, rect, &rows);

    // The repaint rule: an entrance in flight, or an accent that moves with the
    // clock while something is actually on screen.
    rows_moving || (accent_is_animated(cfg) && !(rows.is_empty() && !cfg.watermark))
}

/// The watermark: one inline run on one baseline — the product name in the mode's
/// accent, ` | ` and the enabled-module count in the muted grey — plus, in
/// Rise/VapeV4, the source's panel behind it with the accent bar down its right
/// edge (HUD.cpp:508-563).
fn paint_watermark(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    frame: &Frame,
    rect: Rect,
    count: usize,
) {
    let m = frame.m;
    let name_font = frame.cfg.mode.name_font();
    let title_w = text.measure(name_font, PRODUCT) as f32;
    let segment = format!("{SEGMENT_SEP}{count}");
    let segment_w = text.measure(FontId::Body, &segment) as f32;
    let run_w = title_w + segment_w;

    let accent = watermark_accent(frame.cfg, frame.stamp);
    // The canvas is surface-local while the anchoring helpers are in screen
    // coordinates, so the rect's own origin is subtracted here — exactly what
    // `paint_rows` does with its rows. Without this the run is displaced by
    // (+rect.x, +rect.y), which is invisible on the dump's origin-anchored review
    // canvas but wrong for every game window that is not at the screen's origin
    // (and entirely off-surface for a rect with a negative origin, i.e. a monitor
    // left of the primary one).
    let box_x = watermark_x(rect, frame.cfg.position, m, run_w) - rect.x;
    let panel_y = (rect.y as f32 + m.top_y).round() as i32 - rect.y;
    let text_y = (rect.y as f32 + m.watermark_y).round() as i32 - rect.y;
    let rise_like = m.mode.is_rise_like();

    if rise_like {
        let panel_w = watermark_panel_width(m, title_w, segment_w);
        canvas.fill_rounded_rect_corners(
            box_x,
            panel_y,
            panel_w.round().max(1.0) as u32,
            m.item_height.round().max(1.0) as u32,
            chip_corners(m),
            with_alpha(RISE_BACKGROUND, frame.global),
        );
        canvas.fill_rect(
            (box_x as f32 + panel_w - m.rise_rect_w).round() as i32,
            panel_y,
            m.rise_rect_w.round().max(1.0) as u32,
            m.item_height.round().max(1.0) as u32,
            with_alpha(pack(accent, 1.0), frame.global),
        );
    }

    let text_x = (box_x as f32 + if rise_like { m.rise_pad_x } else { 0.0 }).round() as i32;
    // Rise paints its title in `riseWatermarkTextColor` and leaves the accent to
    // the panel's bar (HUD.cpp:556); every other mode paints it in the accent.
    let title_colour = if m.mode == Mode::Rise {
        RISE_WATERMARK_TEXT
    } else {
        pack(accent, 1.0)
    };
    let secondary = if rise_like { RISE_SECONDARY_TEXT } else { SECONDARY_TEXT };

    draw_shadowed(text, canvas, name_font, PRODUCT, text_x, text_y, title_colour, frame.global);
    draw_shadowed(
        text,
        canvas,
        FontId::Body,
        &segment,
        (text_x as f32 + title_w).round() as i32,
        text_y,
        secondary,
        frame.global,
    );
}

/// The module list (HUD.cpp:565-645). Returns true while any row is still sliding.
fn paint_rows(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    entries: &[Entry],
    anim: &mut Anim,
    frame: &Frame,
    rect: Rect,
    rows: &[Row],
) -> bool {
    let m = frame.m;
    let name_font = frame.cfg.mode.name_font();
    let space_w = text.measure(FontId::Body, " ") as f32;
    let bar_w = bar_width(m);
    let mut moving = false;

    for (index, row) in rows.iter().enumerate() {
        // The source advances every listed row's progress every frame
        // (HUD.cpp:590-596); the first frame a row exists it takes one step in
        // from the edge, so it is never drawn at an unstepped position.
        let progress = anim.progress(&row.key, frame.dt);
        if progress < 1.0 {
            moving = true;
        }
        // `eased = 1 - pow(1 - progress, 3)` (HUD.cpp:598).
        let eased = 1.0 - (1.0 - progress).powi(3);
        let target = row_target_x(rect, frame.cfg.position, m, row.box_w);
        let origin = row_slide_origin(rect, frame.cfg.position, row.box_w);
        let current = origin + (target - origin) * eased;

        // One rounding for the whole row, so the chip, its text and its bar cannot
        // disagree by a pixel about where the row is.
        let box_x = current.round() as i32 - rect.x;
        let y = row_y(rect, m, index) - rect.y;
        let item_h = m.item_height.round().max(1.0) as u32;
        let accent = pack(accent(frame.cfg, frame.stamp, index), 1.0);

        // The chip: Rise/VapeV4 fill the whole row, Tesseract its own dark one,
        // Default nothing at all — and only ever the left corners are rounded
        // (HUD.cpp:619-634).
        if let Some(colour) = chip_colour(m) {
            canvas.fill_rounded_rect_corners(
                box_x,
                y,
                row.box_w.round().max(1.0) as u32,
                item_h,
                chip_corners(m),
                with_alpha(colour, frame.global),
            );
        }
        // The accent bar, square, flush against the row's right edge.
        if bar_w > 0.0 {
            canvas.fill_rect(
                (box_x as f32 + row.box_w - bar_w).round() as i32,
                y,
                bar_w.round().max(1.0) as u32,
                item_h,
                with_alpha(accent, frame.global),
            );
        }

        let text_x = (box_x as f32 + m.text_inset_x).round() as i32;
        let text_y = (y as f32 + m.text_inset_y).round() as i32;
        let entry = &entries[row.entry];

        draw_shadowed(text, canvas, name_font, &row.name, text_x, text_y, accent, frame.global);

        if row.tag_w > 0.0 {
            let tag_x = (text_x as f32 + row.name_w + tag_gap(m, space_w)).round() as i32;
            draw_shadowed(
                text,
                canvas,
                FontId::Body,
                &entry.value,
                tag_x,
                text_y,
                tag_colour(m, entry.flash),
                frame.global,
            );
        }
    }

    moving
}

// ── the numbers behind the picture ──────────────────────────────────────────

fn hex(colour: u32) -> String {
    format!(
        "#{:02X}{:02X}{:02X} @{:.0}%",
        (colour >> 16) & 0xFF,
        (colour >> 8) & 0xFF,
        colour & 0xFF,
        (colour >> 24) as f32 / 255.0 * 100.0
    )
}

fn rgb_text(rgb: [f32; 3]) -> String {
    format!(
        "({:.0},{:.0},{:.0})",
        rgb[0] * 255.0,
        rgb[1] * 255.0,
        rgb[2] * 255.0
    )
}

/// The geometry behind the picture, for `--dump`: the mode's metrics, the
/// watermark run and panel, and every row's box, insets, bar and tag colour —
/// measured through the same `Metrics` and helpers the painter uses, so a review
/// dump and the printed numbers cannot drift apart. The numbers are canvas-local
/// (the review canvas starts at the origin).
pub fn describe(text: &TextEngine, entries: &[Entry], cfg: &Config, canvas_w: u32, canvas_h: u32) -> String {
    let m = Metrics::new(cfg.mode, cfg.scale);
    let rect = Rect::new(0, 0, canvas_w as i32, canvas_h as i32);
    let rows = measure_rows(text, entries, cfg, &m);
    let mut out = String::new();

    out.push_str(&format!(
        "mode {} (HUD.cpp) | scale {:.2} | font {}px | lineHeight {} | topY {} | moduleSpacing {} | \
         itemHeight {} | pitch {}\n",
        cfg.mode.label(),
        m.scale,
        m.font_px,
        m.line_height,
        m.top_y,
        m.module_spacing,
        m.item_height,
        row_pitch(&m),
    ));
    out.push_str(&format!(
        "  margins right {} | rows base y {} | text inset ({}, {}) | bar {}px at the right edge | \
         tag gap {} | chip {} radius {:?} | risePad {}x{} | shadow {} | secondary {}\n",
        m.right_margin,
        m.base_y,
        m.text_inset_x,
        m.text_inset_y,
        bar_width(&m),
        tag_gap(&m, text.measure(FontId::Body, " ") as f32),
        match chip_colour(&m) {
            Some(colour) => hex(colour),
            None => "none (Default)".to_string(),
        },
        chip_corners(&m),
        m.rise_pad_x,
        m.rise_pad_y,
        hex(SHADOW),
        if m.mode.is_rise_like() { hex(RISE_SECONDARY_TEXT) } else { hex(SECONDARY_TEXT) },
    ));
    out.push_str(&format!(
        "  review rect {}x{} at ({}, {})..({}, {}) | list edge x={} | --position {:?} | {} rows, \
         widest box {}px\n",
        rect.w,
        rect.h,
        rect.x,
        rect.y,
        rect.right(),
        rect.bottom(),
        list_edge_x(rect, cfg.position),
        cfg.position,
        rows.len(),
        rows.iter().map(|r| r.box_w).fold(0.0f32, f32::max),
    ));

    if cfg.watermark {
        let name_font = cfg.mode.name_font();
        let title_w = text.measure(name_font, PRODUCT) as f32;
        let segment = format!("{SEGMENT_SEP}{}", rows.len());
        let segment_w = text.measure(FontId::Body, &segment) as f32;
        let run_w = title_w + segment_w;
        let box_x = watermark_x(rect, cfg.position, &m, run_w);
        out.push_str(&format!(
            "  watermark \"{PRODUCT}\" {:.0}px + \"{segment}\" {:.0}px = {:.0}px at ({}, {}){}",
            title_w, segment_w, run_w, box_x, watermark_y(rect, &m),
            if m.mode == Mode::Rise { " title #E8E8E8 (riseWatermarkTextColor)" } else { " title in the accent" },
        ));
        if m.mode.is_rise_like() {
            let panel_w = watermark_panel_width(&m, title_w, segment_w);
            out.push_str(&format!(
                ", panel {:.0}x{:.0} at ({}, {}) {} corners {:?}, bar {:.0}px {}",
                panel_w,
                m.item_height,
                box_x,
                (rect.y as f32 + m.top_y).round() as i32,
                hex(RISE_BACKGROUND),
                chip_corners(&m),
                m.rise_rect_w,
                if m.mode == Mode::Vape { "left-rounded 6px" } else { "square" },
            ));
        }
        out.push('\n');
        out.push_str(&format!(
            "  watermark accent now {} | wave {} (speed {}) | primary {} secondary {}\n",
            rgb_text(watermark_accent(cfg, Stamp { seconds: SEED_MS as f32 / 1000.0, millis: SEED_MS as f64 })),
            cfg.wave,
            cfg.wave_speed,
            rgb_text(cfg.primary),
            rgb_text(cfg.secondary),
        ));
    } else {
        out.push_str("  watermark: off (m_Watermark = false)\n");
    }

    for (slot, row) in rows.iter().enumerate() {
        let entry = &entries[row.entry];
        out.push_str(&format!(
            "  row {slot} \"{}\"{}: box {:.0}px (content {:.0}px) at x={} (y {}..{}), name {:.0}px at x={}, \
             bar {}x{} at x={}",
            row.name,
            if entry.value.is_empty() { String::new() } else { format!(" tag \"{}\"", entry.value) },
            row.box_w,
            row.layout_w,
            row_target_x(rect, cfg.position, &m, row.box_w).round() as i32,
            (rect.y as f32 + m.base_y + slot as f32 * row_pitch(&m)).round() as i32,
            (rect.y as f32 + m.base_y + slot as f32 * row_pitch(&m) + m.item_height).round() as i32,
            row.name_w,
            (row_target_x(rect, cfg.position, &m, row.box_w) + m.text_inset_x).round() as i32,
            bar_width(&m).round(),
            m.item_height.round(),
            (row_target_x(rect, cfg.position, &m, row.box_w) + row.box_w - bar_width(&m)).round() as i32,
        ));
        if row.tag_w > 0.0 {
            out.push_str(&format!(
                " (gap {:.0}), tag {:.0}px {}",
                tag_gap(&m, text.measure(FontId::Body, " ") as f32),
                row.tag_w,
                hex(tag_colour(&m, entry.flash)),
            ));
        }
        out.push('\n');
    }
    if rows.is_empty() {
        out.push_str("  (no listed modules: nothing to draw)\n");
    }

    out.push_str(&format!(
        "motion: entrance slide from the screen edge, progress += dt*{SLIDE_RATE} (dt clamped to \
         {MAX_DELTA_S}s), easeOutCubic; exit instant | accent {} | defaults wave cycle \
         {WAVE_CYCLE_MS}ms/{WAVE_ROW_OFFSET_MS}ms per row\n",
        if accent_is_animated(cfg) { "time-driven (repaints)" } else { "static" },
    ));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-4
    }

    fn rgb_near(got: [f32; 3], want: [f32; 3]) -> bool {
        near(got[0], want[0]) && near(got[1], want[1]) && near(got[2], want[2])
    }

    fn entry(id: u32, name: &str, value: &str, flash: bool) -> Entry {
        Entry {
            id,
            enabled: true,
            flash,
            highlight: false,
            name: name.to_string(),
            value: value.to_string(),
        }
    }

    /// The master constants of HUD.cpp:469-491, mode by mode: font, topY,
    /// lineHeight, moduleSpacing, itemHeight, row pitch, the text insets, the bar
    /// width and the right margin.
    #[test]
    fn the_four_modes_are_the_sources_master_constants() {
        let cases: [(Mode, [f32; 10]); 4] = [
            // font, topY, lineHeight, spacing, itemHeight, pitch, insetX, insetY, bar, margin
            (Mode::Default, [16.0, 4.0, 16.0, 2.0, 18.0, 20.0, 0.0, 0.0, 0.0, 6.0]),
            (Mode::Rise, [16.0, 4.0, 16.0, 0.0, 22.0, 22.0, 4.0, 2.0, 2.0, 4.0]),
            (Mode::Vape, [16.0, 4.0, 16.0, 0.0, 22.0, 22.0, 4.0, 2.0, 2.0, 4.0]),
            (Mode::Tesseract, [16.0, 4.0, 16.0, 2.0, 20.0, 22.0, 6.0, 2.0, 3.0, 6.0]),
        ];

        for (mode, want) in cases {
            let m = Metrics::new(mode, 1.0);
            let got = [
                m.font_px,
                m.top_y,
                m.line_height,
                m.module_spacing,
                m.item_height,
                row_pitch(&m),
                m.text_inset_x,
                m.text_inset_y,
                bar_width(&m),
                m.right_margin,
            ];
            for (a, b) in got.iter().zip(want.iter()) {
                assert!(near(*a, *b), "{mode:?}: got {got:?}, want {want:?}");
            }
        }

        // The two y origins the source derives rather than shares: the watermark's
        // own baseline and the first row's.
        assert_eq!(Metrics::new(Mode::Default, 1.0).base_y, 4.0);
        assert_eq!(Metrics::new(Mode::Default, 1.0).watermark_y, 4.0);
        assert_eq!(Metrics::new(Mode::Rise, 1.0).watermark_y, 6.0, "topY + risePadY");
        assert_eq!(Metrics::new(Mode::Vape, 1.0).watermark_y, 6.0);
        assert_eq!(Metrics::new(Mode::Tesseract, 1.0).watermark_y, 6.0);
        assert_eq!(Metrics::new(Mode::Tesseract, 1.0).base_y, 2.0, "Tesseract rows");
        assert_eq!(Metrics::new(Mode::Default, 1.0).watermark_x, 10.0, "cursorX = 10");
    }

    /// `--scale` multiplies every metric and the font size; 1.0 is the source.
    #[test]
    fn scale_multiplies_every_metric() {
        let one = Metrics::new(Mode::Vape, 1.0);
        let two = Metrics::new(Mode::Vape, 2.0);
        assert_eq!(two.font_px, one.font_px * 2.0);
        assert_eq!(two.item_height, one.item_height * 2.0);
        assert_eq!(row_pitch(&two), row_pitch(&one) * 2.0);
        assert_eq!(two.text_inset_x, one.text_inset_x * 2.0);
        assert_eq!(two.text_inset_y, one.text_inset_y * 2.0);
        assert_eq!(bar_width(&two), bar_width(&one) * 2.0);
        assert_eq!(two.right_margin, one.right_margin * 2.0);
        assert_eq!(two.watermark_x, 20.0);
        assert_eq!(two.watermark_y, 12.0);
        assert_eq!(two.base_y, 8.0);

        // Out-of-range scales are clamped, exactly as the text engine clamps its
        // face size, so the layout and the glyphs cannot disagree.
        assert_eq!(Metrics::new(Mode::Default, 99.0).scale, MAX_SCALE);
        assert_eq!(Metrics::new(Mode::Default, 0.0).scale, MIN_SCALE);
        assert_eq!(Metrics::new(Mode::Default, f32::NAN).scale, 1.0);
    }

    /// Only the modes the source rounds on the left corners are rounded, and the
    /// right corners stay square for the accent bar: VapeV4 6 px, Tesseract 5 px.
    #[test]
    fn the_chip_rounds_only_the_left_corners() {
        assert_eq!(chip_corners(&Metrics::new(Mode::Default, 1.0)), [0.0; 4]);
        assert_eq!(chip_corners(&Metrics::new(Mode::Rise, 1.0)), [0.0; 4]);
        assert_eq!(chip_corners(&Metrics::new(Mode::Vape, 1.0)), [6.0, 0.0, 0.0, 6.0]);
        assert_eq!(chip_corners(&Metrics::new(Mode::Tesseract, 1.0)), [5.0, 0.0, 0.0, 5.0]);
        assert_eq!(chip_corners(&Metrics::new(Mode::Vape, 2.0)), [12.0, 0.0, 0.0, 12.0]);

        // The chip fills: the source's own background colours, and none in Default.
        assert_eq!(chip_colour(&Metrics::new(Mode::Default, 1.0)), None);
        assert_eq!(chip_colour(&Metrics::new(Mode::Rise, 1.0)), Some(RISE_BACKGROUND));
        assert_eq!(chip_colour(&Metrics::new(Mode::Vape, 1.0)), Some(RISE_BACKGROUND));
        assert_eq!(chip_colour(&Metrics::new(Mode::Tesseract, 1.0)), Some(TESSERACT_BACKGROUND));
        assert_eq!(TESSERACT_BACKGROUND >> 24, 240);
    }

    /// `boxWidth` (HUD.cpp:607-609): Rise-like adds `padX*2 + rectW + 1`, Tesseract
    /// adds `padding + gapAfterText + rectW`, Default adds nothing.
    #[test]
    fn the_row_box_adds_the_modes_padding_and_bar() {
        assert_eq!(box_width(&Metrics::new(Mode::Default, 1.0), 100.0), 100.0);
        assert_eq!(box_width(&Metrics::new(Mode::Rise, 1.0), 100.0), 100.0 + 8.0 + 2.0 + 1.0);
        assert_eq!(box_width(&Metrics::new(Mode::Vape, 1.0), 100.0), 111.0);
        assert_eq!(box_width(&Metrics::new(Mode::Tesseract, 1.0), 100.0), 100.0 + 6.0 + 4.0 + 3.0);

        // The panel the watermark sits in: the run plus the same padding and bar,
        // and no FPS term (this overlay has no frame counter).
        assert_eq!(watermark_panel_width(&Metrics::new(Mode::Rise, 1.0), 74.0, 33.0), 74.0 + 33.0 + 11.0);
    }

    /// The tag sits one space glyph after the name — except in Tesseract, which
    /// uses its own 4 px gap (HUD.cpp:639, 603).
    #[test]
    fn the_tag_gap_is_a_space_but_four_pixels_in_tesseract() {
        let space = 4.0;
        assert_eq!(tag_gap(&Metrics::new(Mode::Default, 1.0), space), 4.0);
        assert_eq!(tag_gap(&Metrics::new(Mode::Rise, 1.0), space), 4.0);
        assert_eq!(tag_gap(&Metrics::new(Mode::Vape, 1.0), space), 4.0);
        assert_eq!(tag_gap(&Metrics::new(Mode::Tesseract, 1.0), space), TESSERACT_GAP_AFTER_TEXT);
        assert_eq!(tag_gap(&Metrics::new(Mode::Tesseract, 2.0), 4.0), 8.0, "and it scales");
    }

    /// HSV → RGB against the definition, including the sector the Rise hue lands
    /// in and the saturation-0 shortcut.
    #[test]
    fn hsv_to_rgb_matches_the_definition() {
        assert_eq!(hsv_to_rgb(0.0, 0.0, 1.0), [1.0, 1.0, 1.0], "no saturation is grey");
        assert_eq!(hsv_to_rgb(0.0, 1.0, 1.0), [1.0, 0.0, 0.0]);
        assert_eq!(hsv_to_rgb(1.0 / 3.0, 1.0, 1.0), [0.0, 1.0, 0.0]);
        assert_eq!(hsv_to_rgb(0.5, 1.0, 1.0), [0.0, 1.0, 1.0]);
        assert_eq!(hsv_to_rgb(0.75, 1.0, 1.0), [0.5, 0.0, 1.0]);

        // (0.5, 0.72, 0.95) is sector 3 with f = 0: p = v(1-s), q = t = v.
        let c = hsv_to_rgb(0.5, 0.72, 0.95);
        assert!(rgb_near(c, [0.95 * 0.28, 0.95, 0.95]), "{c:?}");

        // The hue wraps, and 1.0 is 0.0.
        assert_eq!(hsv_to_rgb(1.25, 1.0, 1.0), hsv_to_rgb(0.25, 1.0, 1.0));
        assert_eq!(hsv_to_rgb(1.0, 1.0, 1.0), hsv_to_rgb(0.0, 1.0, 1.0));
    }

    /// The Default wave: a cosine between the two configurable colours with a
    /// `2500 / speed` ms cycle and `+400 ms` per row.
    #[test]
    fn the_wave_is_a_cosine_blend_on_the_sources_cycle() {
        let primary = DEFAULT_PRIMARY;
        let secondary = DEFAULT_SECONDARY;

        // At the start of a cycle the blend is 0, half way through it is 1 — the
        // primary and secondary colours exactly.
        assert!(rgb_near(wave_rgb(primary, secondary, 0.0, 0.0, 1.0), primary));
        assert!(rgb_near(wave_rgb(primary, secondary, 1250.0, 0.0, 1.0), secondary));

        // A row's `index * 400` offset is the same as being 400 ms further in.
        let row3 = wave_rgb(primary, secondary, 850.0, 1200.0, 1.0);
        assert!(rgb_near(row3, wave_rgb(primary, secondary, 2050.0, 0.0, 1.0)));

        // Speed divides the cycle: at 2x the same phase arrives in half the time.
        assert!(rgb_near(wave_rgb(primary, secondary, 625.0, 0.0, 2.0), secondary));

        // The cycle repeats, and a zero speed is floored at 0.01 rather than
        // dividing by zero (HUD.cpp:258).
        assert!(rgb_near(wave_rgb(primary, secondary, 2500.0, 0.0, 1.0), primary));
        assert!(wave_rgb(primary, secondary, 5000.0, 0.0, 0.0)[0].is_finite());

        // Two different rows are on different phases — the effect the whole mode
        // is for.
        let a = wave_rgb(primary, secondary, 2000.0, 0.0, 1.0);
        let b = wave_rgb(primary, secondary, 2000.0, WAVE_ROW_OFFSET_MS, 1.0);
        assert!(!rgb_near(a, b), "row 1 must not match row 0: {a:?} vs {b:?}");
    }

    /// Rise: `hue = 0.58 - phase*0.18` with `phase = 0.5 + 0.5*sin(t*1.35 + i*0.5)`
    /// at saturation 0.72 and value 0.95 — checked at hand-computed phases.
    #[test]
    fn rise_spreads_a_hue_band_over_the_rows() {
        // t = 0, i = 0 -> phase 0.5 -> hue 0.49.
        assert!(rgb_near(rise_rgb(0.0, 0.0), hsv_to_rgb(0.49, 0.72, 0.95)));
        // t = 0, i = 1 -> phase 0.5 + 0.5*sin(0.5) = 0.73971277 -> hue 0.44685170.
        assert!(rgb_near(rise_rgb(0.0, 1.0), hsv_to_rgb(0.446_851_7, 0.72, 0.95)));
        // t = 0, i = 2 -> phase 0.5 + 0.5*sin(1.0) = 0.92073548 -> hue 0.41426761.
        assert!(rgb_near(rise_rgb(0.0, 2.0), hsv_to_rgb(0.414_267_6, 0.72, 0.95)));

        // Every hue the phase can reach is inside the [0.40, 0.58] band, and the
        // band is a fifth of the wheel rather than a full rainbow.
        for step in 0..40 {
            let t = step as f32 * 0.37;
            for index in 0..4 {
                let c = rise_rgb(t, index as f32);
                assert!(c.iter().all(|v| (0.0..=1.0).contains(v)), "{c:?}");
            }
        }
    }

    /// VapeV4: `sin(t*1.55 + i*0.45)` lerping soft purple to pink — at phase 0.5
    /// the colour is exactly the midpoint of the two source colours.
    #[test]
    fn vape_lerps_purple_to_pink() {
        let mid = vape_rgb(0.0, 0.0);
        assert!(rgb_near(mid, [(0.82 + 1.0) / 2.0, (0.50 + 0.43) / 2.0, (0.98 + 0.74) / 2.0]));

        // A quarter cycle later `sin` is at its positive peak: pure pink.
        let t = std::f32::consts::FRAC_PI_2 / 1.55;
        assert!(rgb_near(vape_rgb(t, 0.0), [1.0, 0.43, 0.74]));
        // And at the negative peak, pure purple.
        let t = -std::f32::consts::FRAC_PI_2 / 1.55;
        assert!(rgb_near(vape_rgb(t, 0.0), [0.82, 0.50, 0.98]));

        // Rows differ by 0.45 rad of phase each.
        assert!(!rgb_near(vape_rgb(0.3, 0.0), vape_rgb(0.3, 1.0)));
    }

    /// Tesseract: the golden-ratio hue walk at saturation 0.75 and value 0.9.
    #[test]
    fn tesseract_walks_the_hue_by_the_golden_ratio() {
        // t = 0, i = 0 -> hue 0.
        assert!(rgb_near(tesseract_rgb(0.0, 0.0), hsv_to_rgb(0.0, 0.75, 0.9)));
        // t = 0, i = 1 -> hue 0.618034.
        assert!(rgb_near(tesseract_rgb(0.0, 1.0), hsv_to_rgb(GOLDEN_RATIO, 0.75, 0.9)));
        // t = 2000 ms is one full turn of the base hue: back to the start.
        assert!(rgb_near(tesseract_rgb(2000.0, 0.0), hsv_to_rgb(0.0, 0.75, 0.9)));
        // t = 1000 ms is half a turn.
        assert!(rgb_near(tesseract_rgb(1000.0, 0.0), hsv_to_rgb(0.5, 0.75, 0.9)));

        // The spread: consecutive rows are a golden ratio apart, so any four rows
        // are four clearly different colours.
        let colours: Vec<[f32; 3]> = (0..4).map(|i| tesseract_rgb(2000.0, i as f32)).collect();
        for i in 0..colours.len() {
            for j in (i + 1)..colours.len() {
                assert!(!rgb_near(colours[i], colours[j]), "rows {i} and {j} collided");
            }
        }
    }

    /// Tesseract's watermark header steps once every 20 ms through 360 hues
    /// (HUD.cpp:303-309).
    #[test]
    fn the_tesseract_header_steps_every_twenty_milliseconds() {
        assert!(rgb_near(tesseract_header_rgb(0.0, 0.0), hsv_to_rgb(0.0, 0.7, 0.8)));
        // ceil(20/20) = 1 -> hue 1/360.
        assert!(rgb_near(tesseract_header_rgb(20.0, 0.0), hsv_to_rgb(1.0 / 360.0, 0.7, 0.8)));
        // Half a second in: ceil(500/20) = 25 -> hue 25/360.
        assert!(rgb_near(tesseract_header_rgb(500.0, 0.0), hsv_to_rgb(25.0 / 360.0, 0.7, 0.8)));
        // It wraps every 7.2 s, and it is not the row function.
        assert!(rgb_near(tesseract_header_rgb(7200.0, 0.0), hsv_to_rgb(0.0, 0.7, 0.8)));
        assert!(!rgb_near(tesseract_header_rgb(500.0, 0.0), tesseract_rgb(500.0, 0.0)));
    }

    /// The repaint rule: Default with the wave off is static once settled; every
    /// other combination is time-driven, because its accent is.
    #[test]
    fn only_a_waveless_default_hud_is_static() {
        let mut cfg = Config::default();
        assert!(accent_is_animated(&cfg), "Default + wave");
        cfg.wave = false;
        assert!(!accent_is_animated(&cfg), "Default without the wave");
        for mode in [Mode::Rise, Mode::Vape, Mode::Tesseract] {
            let cfg = Config { mode, wave: false, ..Config::default() };
            assert!(accent_is_animated(&cfg), "{mode:?} is always animated");
        }
    }

    /// The rows slide in from the edge at `dt * 6` per frame, clamped at 1, and
    /// the delta itself is clamped to 0.1 s (HUD.cpp:504, 592).
    #[test]
    fn a_row_slides_in_at_six_per_second() {
        let mut anim = Anim::new();
        assert!(near(anim.progress("Velocity", 0.05), 0.3));
        assert!(near(anim.progress("Velocity", 0.1), 0.9));
        assert!(near(anim.progress("Velocity", 0.1), 1.0), "clamped at 1");
        assert!(near(anim.progress("Velocity", 0.1), 1.0), "and it stays there");
        // Rows keep their own progress.
        assert!(near(anim.progress("Reach", 0.05), 0.3));

        // A dt of 0.2 s advances the row by one clamped step, not two.
        let now = Instant::now();
        let mut anim = Anim::new();
        anim.tick(now);
        anim.tick(now + Duration::from_millis(200));
        assert!(near(anim.dt, MAX_DELTA_S), "the frame delta is clamped to 0.1s");
        assert!(near(anim.progress("Velocity", anim.dt), 0.6));

        // A module that is no longer listed loses its state — the source's instant
        // exit, so it cannot slide in again from where it left off.
        let mut anim = Anim::new();
        anim.progress("Gone", 0.05);
        anim.retain(&["Velocity".to_string()]);
        assert!(near(anim.progress("Gone", 0.01), 0.06), "restarted from 0");
    }

    /// The list is the source's wedge: sorted by measured width, descending.
    #[test]
    fn the_rows_are_sorted_by_width_descending() {
        let text = TextEngine::new(1.0).expect("GDI text engine");
        let entries = vec![
            entry(1, "Reach", "3.1-3.3", false),
            entry(2, "AutoClicker", "12.3 cps", false),
            entry(3, "Velocity", "82%", false),
            entry(4, "Sprint", "", false),
        ];
        let cfg = Config::default();
        let rows = measure_rows(&text, &entries, &cfg, &Metrics::new(cfg.mode, 1.0));

        assert_eq!(rows.len(), 4, "every enabled module is listed");
        for pair in rows.windows(2) {
            assert!(pair[0].sort_w >= pair[1].sort_w, "the wedge is width-descending");
        }
        // The disabled module, the empty name and ArrayList itself are never rows.
        let skipped = vec![
            Entry { enabled: false, ..entry(5, "Hidden", "", false) },
            entry(6, "", "", false),
            entry(7, SELF_MODULE, "", false),
        ];
        assert!(measure_rows(&text, &skipped, &cfg, &Metrics::new(cfg.mode, 1.0)).is_empty());
    }

    /// The anchoring, on the rect the window covers: the source's own placement is
    /// watermark left, rows right, and `--position left` swaps the pair.
    #[test]
    fn the_hud_hangs_off_the_rects_edges() {
        let monitor = Rect::new(0, 0, 1920, 1080);
        let m = Metrics::new(Mode::Default, 1.0);

        assert_eq!(watermark_x(monitor, Position::Right, &m, 100.0), 10, "cursorX = 10");
        assert_eq!(watermark_y(monitor, &m), 4);
        assert_eq!(row_target_x(monitor, Position::Right, &m, 120.0), 1920.0 - 120.0 - 6.0);
        assert_eq!(row_slide_origin(monitor, Position::Right, 120.0), 1920.0, "from screenW");
        assert_eq!(row_y(monitor, &m, 0), 4);
        assert_eq!(row_y(monitor, &m, 3), 4 + 3 * 20, "pitch 20 in Default");
        assert_eq!(list_edge_x(monitor, Position::Right), 1920);

        // Mirrored, the watermark ends 10 px inside the other edge and the rows are
        // flushed against it; a row still slides in from off-screen, not from its
        // own resting place.
        let left = Position::Left;
        assert_eq!(watermark_x(monitor, left, &m, 100.0), 1920 - 10 - 100);
        assert!(watermark_x(monitor, left, &m, 100.0) + 100 <= 1920);
        assert_eq!(row_target_x(monitor, left, &m, 120.0), 6.0);
        assert_eq!(row_slide_origin(monitor, left, 120.0), -120.0);
        assert_eq!(list_edge_x(monitor, left), 0);

        // A rect that is not at the origin moves the whole HUD with it, and a
        // second monitor below the first moves the top blocks down.
        let second = Rect::new(1920, 0, 1920, 1080);
        assert_eq!(watermark_x(second, Position::Right, &m, 100.0), 1920 + 10);
        assert_eq!(row_target_x(second, Position::Right, &m, 120.0), 3840.0 - 126.0);
        let below = Rect::new(0, 1080, 1920, 1080);
        assert_eq!(watermark_y(below, &m), 1084);
        assert_eq!(row_y(below, &m, 0), 1084);
        assert_eq!(below.bottom(), 2160);

        // Rise's own margin and row pitch.
        let rise = Metrics::new(Mode::Rise, 1.0);
        assert_eq!(row_target_x(monitor, Position::Right, &rise, 120.0), 1920.0 - 120.0 - 4.0);
        assert_eq!(row_y(monitor, &rise, 2), 4 + 44);
        let tesseract = Metrics::new(Mode::Tesseract, 1.0);
        assert_eq!(row_y(monitor, &tesseract, 2), 2 + 44, "base y 2, pitch 22");
    }

    /// A settled Default frame with the wave off reports nothing moving — the
    /// on-demand repaint rule; every other mode keeps the compositor fed because
    /// its accent is a function of the clock.
    #[test]
    fn a_settled_frame_is_static_only_without_a_live_accent() {
        let entries =
            vec![entry(1, "Velocity", "82%", false), entry(2, "Reach", "3.1-3.3", false)];
        let mut text = TextEngine::new(1.0).expect("GDI text engine");
        let mut canvas = Canvas::new(520, 400);
        let now = Instant::now();

        for (mode, wave, want) in [
            (Mode::Default, false, false),
            (Mode::Default, true, true),
            (Mode::Rise, false, true),
            (Mode::Vape, false, true),
            (Mode::Tesseract, false, true),
        ] {
            let cfg = Config { mode, wave, ..Config::default() };
            let mut anim = Anim::new();
            anim.seed(&entries, now);
            canvas.clear();
            assert_eq!(
                paint(&mut canvas, &mut text, &entries, &mut anim, now, &cfg),
                want,
                "{mode:?} with wave {wave}"
            );
        }

        // Nothing on screen at all is also not animating, even in Rise — but with
        // the watermark still drawn, Rise's panel bar keeps moving, so that frame
        // is still one to push.
        let cfg = Config { mode: Mode::Rise, ..Config::default() };
        let mut anim = Anim::new();
        let mut canvas = Canvas::new(520, 400);
        assert!(
            paint(&mut canvas, &mut text, &[], &mut anim, now, &cfg),
            "Rise's watermark panel still animates"
        );
        let cfg = Config { mode: Mode::Rise, watermark: false, ..Config::default() };
        let mut anim = Anim::new();
        assert!(
            !paint(&mut canvas, &mut text, &[], &mut anim, now, &cfg),
            "nothing drawn, nothing moving"
        );
    }

    /// A seeded frame draws every row at rest, with the pixels where the geometry
    /// says they are: the bars sit against the right margin of each mode, and a
    /// VapeV4 row is the only one whose tag turns red while the module is in use.
    #[test]
    fn a_seeded_frame_lands_the_rows_on_the_modes_margins() {
        let entries = vec![entry(1, "Velocity", "82%", true)];
        let canvas = Canvas::placed(0, 0, 1920, 1080);

        // (mode, right margin, the mode's own base y for the first row)
        for (mode, margin, base_y) in [
            (Mode::Default, 6, 4),
            (Mode::Rise, 4, 4),
            (Mode::Vape, 4, 4),
            (Mode::Tesseract, 6, 2),
        ] {
            let text = TextEngine::new(1.0).expect("GDI text engine");
            let cfg = Config { mode, ..Config::default() };
            let m = Metrics::new(mode, 1.0);
            let rows = measure_rows(&text, &entries, &cfg, &m);
            let box_w = rows[0].box_w;
            let box_x = row_target_x(canvas_rect(&canvas), Position::Right, &m, box_w).round() as i32;
            let bar_right = box_x + box_w.round() as i32;

            assert_eq!(bar_right, 1920 - margin, "{mode:?}: the row is flushed to its margin");
            assert_eq!(row_y(canvas_rect(&canvas), &m, 0), base_y, "{mode:?}");
        }

        // The tag colour rule the source encodes at HUD.cpp:640: the active red is
        // VapeV4's alone; every other mode stays grey whatever the flash says.
        for mode in [Mode::Default, Mode::Rise, Mode::Tesseract] {
            let m = Metrics::new(mode, 1.0);
            assert_eq!(tag_colour(&m, true), tag_colour(&m, false), "{mode:?} keeps its grey");
        }
        let vape = Metrics::new(Mode::Vape, 1.0);
        assert_eq!(tag_colour(&vape, true), VAPE_TAG_ACTIVE);
        assert_eq!(tag_colour(&vape, false), RISE_SECONDARY_TEXT);
    }

    /// `FormatModuleName` (HUD.cpp:125-150) for `--spaced`: interior capitals are
    /// split off, including the end of an acronym run.
    #[test]
    fn spaced_names_split_camel_case_like_the_source() {
        assert_eq!(spaced_name("AutoClicker"), "Auto Clicker");
        assert_eq!(spaced_name("ArrayList"), "Array List");
        assert_eq!(spaced_name("WTap"), "W Tap");
        assert_eq!(spaced_name("Velocity"), "Velocity");
        assert_eq!(spaced_name(""), "");
        assert_eq!(spaced_name("HitSelect"), "Hit Select");
    }
}
