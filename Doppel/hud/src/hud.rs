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
// **Modern** and **Aurora** are the two exceptions. Modern is the port's own
// fifth mode, added by the user's request alongside contract v3's three-bit mode
// field; Aurora is the sixth — the visual reset the user asked for after living
// with the four ports, the WebUI's own glass-card language carried into the
// game. Neither has a source line: their numbers are marked `MODERN_*` /
// `AURORA_*` and explained where they are declared, so nothing here can be
// mistaken for something HUD.cpp does.
//
// ── what is drawn ────────────────────────────────────────────────────────────
//
//   1. **the watermark** — one inline run on a single baseline: the product name
//      in the mode's accent, then ` | ` and each middle segment the state's header
//      carries — the nickname and `"<n> FPS"` — in the mode's secondary colour
//      (HUD.cpp:508-563). That is the source's own `name | nickname | "<n> FPS"`
//      run: the header carries both of the source's segments, and a segment with
//      nothing to say is dropped rather than drawn empty (`watermark_segments`). A
//      v1 block, whose 16-byte header has neither field, draws the bare title. In
//      the Rise/VapeV4 modes the run sits in the source's panel: a `rgba(0,0,0,88)`
//      fill with the accent as a 2 px bar down its right edge (VapeV4 rounds that
//      panel's left corners by 6 px); in Modern it sits in that mode's own rounded
//      card.
//   2. **the module list** — one row per enabled module, `NAME  TAG` on one
//      baseline, the block right-aligned so the rows form the width-descending
//      wedge (HUD.cpp:565-645) — unless the header asks for published order. The
//      accent bar is at the row's **right** edge (2 px in Rise/VapeV4, 3 px in
//      Tesseract and Modern), the name is painted in the row's animated accent and
//      the tag in the mode's muted grey — red `(255,85,85)` in VapeV4 while the
//      module is in use, and the live accent in Modern (HUD.cpp:640). A non-zero
//      `flash` tints the row's **text** towards that same red by `flash/255` — the
//      name and its value, each lerped from the colour it would otherwise use
//      (`flash_tint`). No fill is drawn for it, so the card, the spine bar and the
//      layout are the pixels they would be with no pulse at all.
//
// ── animations, exactly as the source ────────────────────────────────────────
//
//   * **entrance**: a row slides in from the screen's right edge,
//     `progress += dt * 6`, eased with `1 - (1-p)^3` (easeOutCubic), with the frame
//     delta clamped to 0.1 s (HUD.cpp:502-506, 590-612). There is no opacity ramp;
//     a row is opaque from the first frame it exists.
//   * **exit**: contract v3 adds one where the source is instant — a row that
//     disappears is kept for ~220 ms, fading and sliding back towards the edge it
//     entered from, so enabling a module in the WebUI is a transition rather than a
//     jump. The source's own answer is to erase the `m_SlideProgress` entries of
//     modules that are no longer enabled (HUD.cpp:647-653), which is still what
//     happens to the slide state itself.
//   * **work pulse**: contract v3's entry `flash` is a 0..255 strength, and it is
//     carried by the row's **text**: the name and the value are lerped, channel by
//     channel, from the colour that run would otherwise be drawn in towards red by
//     `flash/255` — so at full strength the labels are the mode's red and as the
//     byte decays they walk back to their own colours. Nothing is filled, so the
//     pulse cannot appear as a band behind or over the row. It decays to nothing at
//     0 and the overlay never decays it itself: the writer recomputes it every
//     publish.
//   * **accent**: per-row phase offsets, one function per mode (HUD.cpp:249-309).
//     Default blends the two configurable colours on a cosine over
//     `2500 / max(speed, 0.01)` ms with `+400 ms` per row; Rise maps
//     `0.5 + 0.5*sin(t*1.35 + i*0.5)` onto an HSV hue sweep; VapeV4 lerps purple
//     to pink on `sin(t*1.55 + i*0.45)`; Tesseract walks the hue by the golden
//     ratio, `hue = fmod(t_ms/2000 + i*0.618034, 1)`; Modern sweeps two colours on
//     a 4.2 s cosine. HSV→RGB is implemented here because the source gets it from
//     ImGui.
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

use crate::draw::{argb, lerp_rgb, Canvas};
use crate::state::Entry;
use crate::text::{FontId, TextEngine, FONT_PX, MAX_SCALE, MIN_SCALE};

// ── the five modes (HUD.cpp:463-468, ArrayList.h:14) ────────────────────────

/// `ArrayListMode` — the combo in `ArrayList.h:14`, in the source's own order,
/// plus `Modern`, which the source has never had: contract v3 widened the
/// header's mode field to three bits so the WebUI can offer a fifth mode, and
/// this is it. Everything below marks the Modern numbers apart from the four
/// ported ones, because there is no HUD.cpp line to cite for them.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Default,
    Rise,
    Vape,
    Tesseract,
    Modern,
    Aurora,
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
            "modern" => Ok(Mode::Modern),
            "aurora" => Ok(Mode::Aurora),
            other => {
                Err(format!(
                    "--mode: expected default, rise, vape, tesseract, modern or aurora, got {other}"
                ))
            }
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Mode::Default => "default",
            Mode::Rise => "rise",
            Mode::Vape => "vape",
            Mode::Tesseract => "tesseract",
            Mode::Modern => "modern",
            Mode::Aurora => "aurora",
        }
    }

    /// `riseLikeMode` (HUD.cpp:466): Rise and VapeV4 share the panel/spacing rules.
    /// Modern is deliberately *not* rise-like: it has its own card, its own
    /// spacing and its own rounded corners, and folding it in here is how a
    /// "fifth mode" ends up looking like the second one with different colours.
    pub fn is_rise_like(self) -> bool {
        matches!(self, Mode::Rise | Mode::Vape)
    }

    /// The face a module name is drawn in: the source uses its bold face in
    /// Default mode and the regular one everywhere else (HUD.cpp:468). Aurora
    /// joins Default in the bold face — its own choice, no source line: bold
    /// near-white names against muted values are the mode's type hierarchy.
    pub fn name_font(self) -> FontId {
        match self {
            Mode::Default | Mode::Aurora => FontId::Name,
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

// ── the work pulse (contract v3's entry `flash`) ────────────────────────────
//
// `flash` stopped being a bool in v3 and became a 0..255 strength the writer
// recomputes every publish. The overlay reads it as a level and never decays it:
// it tints the row's *text* towards red by `flash / 255`, which is the row's own
// colour at 0 (nothing at all) and the mode's red at 255. The tint replaced a wash
// that was filled over the whole row (contract note in the header): a working row
// now reads as its name going red rather than as a red block appearing, and it
// costs one lerp per run — no extra pass and no allocation.

/// The red a working row's text is tinted towards: the source's own
/// `vapeTagActiveColor` red `(255,85,85)` (HUD.cpp:480) — the one red in the
/// reference's palette, and already what the source means by "this module is in
/// use". A glyph tint has to be a *text* colour; the wash this replaced was a fill
/// colour (`argb(200,48,48,210)`) blended over the card, which is exactly what the
/// row no longer does.
pub const FLASH_TINT: u32 = VAPE_TAG_ACTIVE;

/// `flash` 0..255 → 0..1. The one place the wire value becomes a strength, so
/// "0 means no tint at all" cannot drift between the painter and a test.
pub fn flash_strength(flash: u8) -> f32 {
    flash as f32 / 255.0
}

/// The colour a run is drawn in while the module works: `base` — whatever colour
/// that run would use with no pulse at all — lerped channel-wise towards
/// `FLASH_TINT` by `flash` (0..1, from `flash_strength`). 0 is the untouched
/// colour, 1 is the mode's red, and a decaying value lands on the straight line
/// between them.
///
/// Pure, allocation-free and a handful of integer ops on the RGB bytes: it is
/// called twice per row per frame. The base's alpha is kept, so the caller's own
/// fade (`with_alpha`) still has the last word.
pub fn flash_tint(base: u32, flash: f32) -> u32 {
    lerp_rgb(base, FLASH_TINT, flash)
}

// ── Modern (this port's own mode, contract v3) ──────────────────────────────
//
// There is no source line for any of these: the user asked for a fifth mode that
// does not look like a recolour of the other four, so the numbers below are the
// design. The card is a translucent near-black panel rounded on **all four**
// corners — deliberately the opposite of Tesseract's left-only chips and VapeV4's
// left-only panel — with the accent as a bar down the row's right edge and the
// value tag in the accent colour.

/// The row card's fill: darker and more opaque than Rise's `rgba(0,0,0,88)`, so
/// it reads as a card of its own against a game frame.
pub const MODERN_BACKGROUND: u32 = argb(14, 16, 22, 205);
/// The card's corner radius, all four corners.
pub const MODERN_CARD_RADIUS: f32 = 6.0;
/// The accent bar at the row's right edge.
pub const MODERN_RECT_WIDTH: f32 = 3.0;
/// The card's inner padding: the name starts this far in, and the card is this
/// much wider than its content on both sides.
pub const MODERN_PAD_X: f32 = 6.0;
pub const MODERN_PAD_Y: f32 = 2.0;
/// `itemHeight`'s extra over the line height: Default's +2 plus the ~4 px the
/// mode was asked for, so a Modern row is visibly taller than the four.
pub const MODERN_ITEM_EXTRA: f32 = 6.0;
/// The gap between two cards, where Default uses 2.
pub const MODERN_MODULE_SPACING: f32 = 4.0;
/// The right margin the list is flushed to, where Default uses 6.
pub const MODERN_MARGIN_RIGHT: f32 = 6.0;
/// One full sweep of the accent, ms. Slow on purpose: a fast accent is what the
/// other four already are.
pub const MODERN_SWEEP_MS: f32 = 4200.0;

// ── Aurora (this port's own mode, contract v3) ──────────────────────────
//
// The visual reset the user asked for after the four ports and Modern: the
// WebUI's own look carried into the game. The card is a glass panel — a
// near-black navy fill inside a 1 px light border, with a 1 px highlight along
// its top edge — rounded on all four corners, and the accent lives in a small
// **gradient spine** floating inside the card's left edge instead of a bar
// glued to its right one. The name is bold near-white, the value muted grey
// unless the entry carries `highlight`, and the accent itself is the header's
// two colours on a slow cosine — the mode's own cyan/violet pair while the
// user has not picked any (see `aurora_stops`).
//
// None of these numbers has a source line; they are the design, tuned on the
// mockup the user reviewed first (`scripts/hud_aurora_mockup.html` reads the
// same constants).

/// The glass card's fill: deep navy, translucent like the WebUI's cards.
pub const AURORA_BACKGROUND: u32 = argb(13, 17, 26, 208);
/// The card's 1 px border: the slate the WebUI draws around its cards.
pub const AURORA_BORDER: u32 = argb(148, 163, 184, 56);
/// The module name, bold. Near-white rather than the accent: the accent lives
/// in the spine, and a name that fights it would read as noise.
pub const AURORA_TEXT: u32 = argb(236, 242, 248, 255);
/// The value tag and the watermark's middle segments.
pub const AURORA_MUTED: u32 = argb(152, 164, 184, 255);
/// The 1 px highlight along a card's top edge — the glass rim.
pub const AURORA_TOP_HIGHLIGHT: u32 = argb(255, 255, 255, 18);
/// The card's corner radius, all four corners.
pub const AURORA_CARD_RADIUS: f32 = 7.0;
/// `itemHeight`'s extra over the line height: taller than Modern's +6, so the
/// glass card has room for the floating spine.
pub const AURORA_ITEM_EXTRA: f32 = 8.0;
/// The gap between two cards.
pub const AURORA_MODULE_SPACING: f32 = 4.0;
/// The right margin the list is flushed to.
pub const AURORA_MARGIN_RIGHT: f32 = 8.0;
/// The card's inner padding: the name starts this far in (the spine's zone
/// plus breathing room), and the value ends this far from the other edge.
pub const AURORA_PAD_LEFT: f32 = 11.0;
pub const AURORA_PAD_RIGHT: f32 = 9.0;
/// The text's vertical inset inside a 24 px card: (24 − 16) / 2.
pub const AURORA_PAD_Y: f32 = 4.0;
/// The spine floats this far in from the card's left edge, and its height is
/// the card minus twice the vertical inset, so it never touches the border.
pub const AURORA_SPINE_INSET_X: f32 = 3.0;
pub const AURORA_SPINE_W: f32 = 3.0;
pub const AURORA_SPINE_INSET_Y: f32 = 4.0;
/// The gap between a row's name and its value.
pub const AURORA_TAG_GAP: f32 = 9.0;
/// The watermark pill's leading zone — pad, gradient dot, gap — before the
/// title (`aurora_wm_lead`), and the pill's right pad.
pub const AURORA_WM_PAD_X: f32 = 8.0;
pub const AURORA_DOT: f32 = 6.0;
pub const AURORA_DOT_GAP: f32 = 7.0;
/// One full sweep of the accent, ms — slower than Modern's 4.2 s: the mode's
/// whole point is that nothing hurries.
pub const AURORA_SWEEP_MS: f32 = 6000.0;
/// Each row's phase offset along the sweep, in cycles.
pub const AURORA_ROW_PHASE: f32 = 0.13;
/// The spine's bottom stop is this far down the same sweep as its top one, so
/// the gradient visibly flows downward instead of sitting still.
pub const AURORA_SPINE_OFFSET_MS: f64 = 450.0;
/// The mode's own pair, used while the header still carries the reference's
/// wave defaults (see `aurora_stops`): the WebUI's cyan and violet.
pub const AURORA_COLD: [f32; 3] = [77.0 / 255.0, 199.0 / 255.0, 255.0 / 255.0];
pub const AURORA_WARM: [f32; 3] = [179.0 / 255.0, 112.0 / 255.0, 255.0 / 255.0];

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
    /// The user's line-spacing multiplier (contract v3's `line_spacing`, 0.1..2.0).
    /// It scales the whole row pitch rather than just the gap, so it also loosens the
    /// modes whose own `moduleSpacing` is 0 — otherwise the knob would do nothing in
    /// half the modes. 1.0 = the source's own geometry.
    pub line_spacing: f32,
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
    /// Modern's own numbers: the card's radius, its accent bar, its inner
    /// padding. Nothing in the source has these.
    pub modern_card_radius: f32,
    pub modern_rect_w: f32,
    pub modern_pad_x: f32,
    pub modern_pad_y: f32,
    /// Aurora's own numbers, none of them sourced: the glass card's radius and
    /// padding, the floating spine, the tag gap, and the watermark's leading
    /// zone (pad, dot, gap).
    pub aurora_card_radius: f32,
    pub aurora_pad_left: f32,
    pub aurora_pad_right: f32,
    pub aurora_spine_w: f32,
    pub aurora_spine_inset_x: f32,
    pub aurora_spine_inset_y: f32,
    pub aurora_tag_gap: f32,
    pub aurora_wm_pad_x: f32,
    pub aurora_dot: f32,
    pub aurora_dot_gap: f32,
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
    /// The source's numbers, multiplied by `scale`, with the reference's own row gap.
    pub fn new(mode: Mode, scale: f32) -> Metrics {
        Metrics::with_line_spacing(mode, scale, 1.0)
    }

    /// The same, with the user's line-spacing multiplier (contract v3's
    /// `line_spacing`). Clamped here so a writer cannot hand the painter a pitch of
    /// zero (rows stacked on top of each other) or one that pushes the list off the
    /// surface.
    pub fn with_line_spacing(mode: Mode, scale: f32, line_spacing: f32) -> Metrics {
        let line_spacing =
            if line_spacing.is_finite() { line_spacing.clamp(0.1, 2.0) } else { 1.0 };
        let scale = if scale.is_finite() { scale.clamp(MIN_SCALE, MAX_SCALE) } else { 1.0 };
        let s = scale;
        let rise_like = mode.is_rise_like();
        let tesseract = mode == Mode::Tesseract;
        let modern = mode == Mode::Modern;
        let aurora = mode == Mode::Aurora;

        let font_px = FONT_PX * s;
        // `lineHeight = max(tagSize, nameSize)` (HUD.cpp:472). Both faces are
        // created at the source's single `fontSize`, so this is that size.
        let line_height = font_px;
        let item_height = if rise_like {
            line_height + RISE_ITEM_EXTRA * s
        } else if tesseract {
            font_px + TESSERACT_ITEM_EXTRA * s
        } else if modern {
            line_height + MODERN_ITEM_EXTRA * s
        } else if aurora {
            line_height + AURORA_ITEM_EXTRA * s
        } else {
            line_height + DEFAULT_ITEM_EXTRA * s
        };

        Metrics {
            mode,
            scale: s,
            font_px,
            line_height,
            top_y: TOP_Y * s,
            module_spacing: if rise_like {
                0.0
            } else if modern {
                MODERN_MODULE_SPACING * s
            } else if aurora {
                AURORA_MODULE_SPACING * s
            } else {
                MODULE_SPACING * s
            },
            line_spacing,
            item_height,
            rise_pad_x: RISE_PAD_X * s,
            rise_pad_y: RISE_PAD_Y * s,
            rise_rect_w: RISE_RECT_WIDTH * s,
            vape_left_rounding: VAPE_LEFT_ROUNDING * s,
            tesseract_padding: TESSERACT_PADDING * s,
            tesseract_gap: TESSERACT_GAP_AFTER_TEXT * s,
            tesseract_rect_w: TESSERACT_RECT_WIDTH * s,
            tesseract_chip_radius: TESSERACT_CHIP_RADIUS * s,
            modern_card_radius: MODERN_CARD_RADIUS * s,
            modern_rect_w: MODERN_RECT_WIDTH * s,
            modern_pad_x: MODERN_PAD_X * s,
            modern_pad_y: MODERN_PAD_Y * s,
            aurora_card_radius: AURORA_CARD_RADIUS * s,
            aurora_pad_left: AURORA_PAD_LEFT * s,
            aurora_pad_right: AURORA_PAD_RIGHT * s,
            aurora_spine_w: AURORA_SPINE_W * s,
            aurora_spine_inset_x: AURORA_SPINE_INSET_X * s,
            aurora_spine_inset_y: AURORA_SPINE_INSET_Y * s,
            aurora_tag_gap: AURORA_TAG_GAP * s,
            aurora_wm_pad_x: AURORA_WM_PAD_X * s,
            aurora_dot: AURORA_DOT * s,
            aurora_dot_gap: AURORA_DOT_GAP * s,
            right_margin: if rise_like {
                RISE_MARGIN_RIGHT * s
            } else if tesseract {
                TESSERACT_MARGIN_RIGHT * s
            } else if modern {
                MODERN_MARGIN_RIGHT * s
            } else if aurora {
                AURORA_MARGIN_RIGHT * s
            } else {
                DEFAULT_MARGIN_RIGHT * s
            },
            watermark_x: WATERMARK_X * s,
            watermark_y: if rise_like {
                TOP_Y * s + RISE_PAD_Y * s
            } else if tesseract {
                TESSERACT_WATERMARK_Y * s
            } else if modern {
                // The run sits inside Modern's card the way Rise's sits inside
                // its panel: the card starts at `topY`, the text one pad lower.
                TOP_Y * s + MODERN_PAD_Y * s
            } else if aurora {
                // The same rule as Modern: the pill starts at `topY`, its text
                // one vertical pad lower, centred in the 24 px card.
                TOP_Y * s + AURORA_PAD_Y * s
            } else {
                TOP_Y * s
            },
            base_y: if tesseract { TESSERACT_BASE_Y * s } else { TOP_Y * s },
            text_inset_x: if rise_like {
                RISE_PAD_X * s
            } else if tesseract {
                TESSERACT_PADDING * s
            } else if modern {
                MODERN_PAD_X * s
            } else if aurora {
                // A row's text clears the floating spine plus breathing room;
                // the watermark title starts further in (see `aurora_wm_lead`).
                AURORA_PAD_LEFT * s
            } else {
                0.0
            },
            text_inset_y: if rise_like {
                RISE_PAD_Y * s
            } else if tesseract {
                TESSERACT_TEXT_INSET_Y * s
            } else if modern {
                MODERN_PAD_Y * s
            } else if aurora {
                AURORA_PAD_Y * s
            } else {
                0.0
            },
        }
    }
}

/// Row pitch: `itemHeight + moduleSpacing` (HUD.cpp:614), times the user's
/// line-spacing multiplier.
///
/// The multiplier applies to the pitch and not to `moduleSpacing` alone: Rise and
/// VapeV4 sit at `moduleSpacing = 0` (their chips touch), so a multiplier on the gap
/// alone would leave the knob dead in half the modes.
pub fn row_pitch(m: &Metrics) -> f32 {
    (m.item_height + m.module_spacing) * m.line_spacing
}

/// The accent bar's width at a row's right edge: Rise/VapeV4 2 px, Tesseract 3 px,
/// Modern 3 px, Default none (HUD.cpp:619-634).
pub fn bar_width(m: &Metrics) -> f32 {
    if m.mode.is_rise_like() {
        m.rise_rect_w
    } else if m.mode == Mode::Tesseract {
        m.tesseract_rect_w
    } else if m.mode == Mode::Modern {
        m.modern_rect_w
    } else {
        0.0
    }
}

/// The four corner radii of a row's chip, (top-left, top-right, bottom-right,
/// bottom-left): only the left corners are rounded, because the accent bar sits
/// square against the right edge (HUD.cpp:622-633). Modern is the one mode that
/// rounds all four — its card is a card, and its accent bar rounds its own right
/// corners to match.
pub fn chip_corners(m: &Metrics) -> [f32; 4] {
    match m.mode {
        Mode::Vape => [m.vape_left_rounding, 0.0, 0.0, m.vape_left_rounding],
        Mode::Tesseract => [m.tesseract_chip_radius, 0.0, 0.0, m.tesseract_chip_radius],
        Mode::Modern => [m.modern_card_radius; 4],
        Mode::Aurora => [m.aurora_card_radius; 4],
        _ => [0.0; 4],
    }
}

/// The chip the row sits on: Rise/VapeV4 `rgba(0,0,0,88)` over the whole row,
/// Tesseract `rgba(25,25,30,240)`, Modern its own dark card, Aurora its glass
/// card, Default none (`Option::None`). Aurora's chip is drawn by
/// `paint_glass_card` — border, fill and top highlight — rather than by this
/// colour alone; the value here is what `describe` reports and what a test
/// can check.
pub fn chip_colour(m: &Metrics) -> Option<u32> {
    match m.mode {
        Mode::Rise | Mode::Vape => Some(RISE_BACKGROUND),
        Mode::Tesseract => Some(TESSERACT_BACKGROUND),
        Mode::Modern => Some(MODERN_BACKGROUND),
        Mode::Aurora => Some(AURORA_BACKGROUND),
        Mode::Default => None,
    }
}

/// The tag's colour: muted grey, `(255,85,85)` in VapeV4 while the module is in
/// use (HUD.cpp:640), and Modern's live accent — the one mode whose tag is not a
/// fixed grey. Aurora does not come through here either: its value is muted
/// until the entry carries `highlight`, and the lift into the accent happens in
/// `paint_row`, where that flag is at hand. The source applies the active colour in VapeV4 only; every other
/// ported mode keeps the grey whatever the flash byte says.
///
/// `flash` is the v3 strength, read here only as "working at all": the *degree*
/// of the pulse is carried by the text tint (`flash_tint`, applied in `paint_row`
/// over this colour), which every mode gets, because a grey tag alone cannot show
/// a 0..255 level. VapeV4's active red is therefore the top of the same ramp the
/// tint climbs — at `flash` 255 the tag here and the tag tinted are one colour.
pub fn tag_colour(m: &Metrics, flash: bool, accent: u32) -> u32 {
    if m.mode == Mode::Modern {
        accent
    } else if m.mode == Mode::Vape && flash {
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
    } else if m.mode == Mode::Aurora {
        // The mode's own 9 px — a space glyph is too tight between a bold name
        // and a value for the glass card to read cleanly.
        m.aurora_tag_gap
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
    } else if m.mode == Mode::Modern {
        // The card's padding on both sides plus its bar; no `+1`, which is a
        // source quirk of the Rise panel rather than a rule.
        layout_w + m.modern_pad_x * 2.0 + m.modern_rect_w
    } else if m.mode == Mode::Aurora {
        // The glass card's padding on both sides; the spine floats inside the
        // left padding rather than adding to the box.
        layout_w + m.aurora_pad_left + m.aurora_pad_right
    } else {
        layout_w
    }
}

/// Modern's watermark card: the run plus its own inner padding and the accent
/// bar, the same shape as the Rise-like panel (`watermark_panel_width`) but with
/// Modern's numbers.
pub fn modern_panel_width(m: &Metrics, run_w: f32) -> f32 {
    run_w + m.modern_pad_x * 2.0 + m.modern_rect_w
}

/// The distance from an Aurora pill's left edge to its title, and the
/// title-to-run anchor the painter and `describe` share: the pad, the gradient
/// dot and the gap between them.
pub fn aurora_wm_lead(m: &Metrics) -> f32 {
    m.aurora_wm_pad_x + m.aurora_dot + m.aurora_dot_gap
}

/// Aurora's watermark pill: the leading zone plus the run plus the right pad.
/// No accent bar — the pill's accent is its dot.
pub fn aurora_panel_width(m: &Metrics, run_w: f32) -> f32 {
    aurora_wm_lead(m) + run_w + m.aurora_pad_right
}

/// The Rise-like watermark panel's width (HUD.cpp:544). The source computes
/// `titleWidth + segmentWidth + fpsWidth + risePadX*2 + riseRectWidth + 1`; the
/// middle width passed here is that `segmentWidth + fpsWidth` — every segment
/// after the title, ` | ` included (see `measure_watermark`).
pub fn watermark_panel_width(m: &Metrics, title_w: f32, middle_w: f32) -> f32 {
    title_w + middle_w + m.rise_pad_x * 2.0 + m.rise_rect_w + 1.0
}

// ── the watermark run (HUD.cpp:526-562) ─────────────────────────────────────

/// The watermark's middle segments, in the source's order, with the empty ones
/// dropped: the nickname (`ResolvePlayerNick`, HUD.cpp:526-529) and `"<n> FPS"`
/// (`:531-532`, dropped when the header's `fps` is 0 — unknown, not zero). The
/// enabled-module count this port used to draw here is gone: the header carries
/// both of the source's own segments, so the run is the source's again.
pub fn watermark_segments(nickname: &str, fps: u32) -> Vec<String> {
    let mut out = Vec::with_capacity(2);
    if !nickname.is_empty() {
        out.push(nickname.to_string());
    }
    if fps != 0 {
        out.push(format!("{fps} FPS"));
    }
    out
}

/// The same segments, each carrying the ` | ` that precedes it — the source's
/// `segment` and `fpsText` runs (HUD.cpp:535, 562), which is what the painter
/// draws and measures one after the other.
pub fn watermark_middle(nickname: &str, fps: u32) -> Vec<String> {
    watermark_segments(nickname, fps)
        .into_iter()
        .map(|segment| format!("{SEGMENT_SEP}{segment}"))
        .collect()
}

/// The whole run as one string — `DOPPEL | <nickname> | <fps> FPS`, minus the
/// segments the state had nothing to say about. For `--dump` and the log.
pub fn watermark_text(nickname: &str, fps: u32) -> String {
    let mut parts = vec![PRODUCT.to_string()];
    parts.extend(watermark_segments(nickname, fps));
    parts.join(SEGMENT_SEP)
}

/// Measure the run the painter draws: the title in the mode's name face, then
/// every middle segment in the body face, ` | ` included. Returns
/// `(title, middle, whole)` — the Rise-like panel needs the first two separately
/// (HUD.cpp:539-544).
pub fn measure_watermark(
    text: &TextEngine,
    name_font: FontId,
    nickname: &str,
    fps: u32,
) -> (f32, f32, f32) {
    let title = text.measure(name_font, PRODUCT) as f32;
    let middle: f32 = watermark_middle(nickname, fps)
        .iter()
        .map(|segment| text.measure(FontId::Body, segment) as f32)
        .sum();
    (title, middle, title + middle)
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

/// Screen y of the Rise-like watermark panel's top edge. The source builds that
/// panel at `boxMin.y = topY` (HUD.cpp:545-546) — the same line the first module
/// row starts on — while the run's *text* is `risePadY` further down inside it.
pub fn watermark_panel_y(rect: Rect, m: &Metrics) -> i32 {
    (rect.y as f32 + m.top_y).round() as i32
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

/// The screen x every row's box is flushed against, the mode's margin included.
/// It is `row_target_x` plus the row's own box width, which is to say it does not
/// depend on the row: that shared edge is what makes the rows a wedge with one
/// straight side (HUD.cpp:611).
pub fn list_flush_x(rect: Rect, position: Position, m: &Metrics) -> i32 {
    match position {
        Position::Right => (rect.right() as f32 - m.right_margin).round() as i32,
        Position::Left => (rect.x as f32 + m.right_margin).round() as i32,
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

/// Modern's accent: a slow cosine sweep between a cold cyan-blue and a warm
/// violet over `MODERN_SWEEP_MS`, with a small per-row phase offset so the column
/// reads as a gradient rather than as one flat block. Pure in `millis`, exactly
/// like the four ported accents, which is what keeps a review dump reproducible.
pub fn modern_rgb(millis: f64, index: f32) -> [f32; 3] {
    let phase = 0.5
        - 0.5 * ((millis / MODERN_SWEEP_MS as f64 + index as f64 * 0.09) * std::f64::consts::TAU).cos();
    let t = phase as f32;
    let cold = [0.30, 0.78, 1.00];
    let warm = [0.70, 0.44, 1.00];
    [
        cold[0] + (warm[0] - cold[0]) * t,
        cold[1] + (warm[1] - cold[1]) * t,
        cold[2] + (warm[2] - cold[2]) * t,
    ]
}

/// The two colours an Aurora frame actually sweeps between. The header's
/// primary/secondary are the user's choice — but they arrive holding the
/// reference's muted wave defaults bit-for-bit when the user has never touched
/// them, and an Aurora built on `78,86,107 → 120,146,214` would be a grey-blue
/// mode with no aurora in it. So the untouched defaults are treated as "no
/// opinion" and the mode's own cold/warm pair is used instead; the moment the
/// user picks their own colours in the WebUI they are honoured exactly.
pub fn aurora_stops(primary: [f32; 3], secondary: [f32; 3]) -> ([f32; 3], [f32; 3]) {
    let same = |a: [f32; 3], b: [f32; 3]| {
        a.iter().zip(b.iter()).all(|(x, y)| (x - y).abs() < 0.5 / 255.0)
    };
    if same(primary, DEFAULT_PRIMARY) && same(secondary, DEFAULT_SECONDARY) {
        (AURORA_COLD, AURORA_WARM)
    } else {
        (primary, secondary)
    }
}

/// Aurora's accent: the two stops on a 6 s cosine, walked down the rows so the
/// column reads as one gradient drifting along itself. Pure in `millis`, like
/// every accent in this file — the review dump stays reproducible.
pub fn aurora_rgb(millis: f64, index: f32, primary: [f32; 3], secondary: [f32; 3]) -> [f32; 3] {
    let (cold, warm) = aurora_stops(primary, secondary);
    let phase = 0.5
        - 0.5 * ((millis / AURORA_SWEEP_MS as f64 + index as f64 * AURORA_ROW_PHASE as f64)
            * std::f64::consts::TAU)
            .cos();
    let t = phase as f32;
    [
        cold[0] + (warm[0] - cold[0]) * t,
        cold[1] + (warm[1] - cold[1]) * t,
        cold[2] + (warm[2] - cold[2]) * t,
    ]
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
    /// The state header's `nickname` — the watermark's middle segment
    /// (`ResolvePlayerNick`, HUD.cpp:526-529). Empty drops the segment.
    pub nickname: String,
    /// The header's `fps`. 0 = unknown, and the FPS segment is dropped rather
    /// than drawn as `0 FPS` (the source only ever has a real frame counter).
    pub fps: u32,
    /// How opaque the *background* fills are, 0..1 (contract v3's `opacity`,
    /// `--opacity <percent>`). The text is deliberately not scaled by it: the
    /// user asked for background opacity, and a HUD whose labels fade with its
    /// cards is unreadable at the low end. 1.0 = as the source draws it.
    pub opacity: f32,
    /// Draw the rows in the order the writer published them instead of sorting
    /// them by measured width — contract v3's `orderPublished`. The width sort is
    /// the reference's wedge (HUD.cpp:340-342) and it destroys the user's own
    /// module order, which is the whole reason the bit exists.
    pub ordered: bool,
    /// The row gap as a multiple of the mode's own (contract v3's `line_spacing`,
    /// `--line-spacing <percent>`). 1.0 = as the source draws it. Kept apart from
    /// `scale` because the scale also resizes the text, so it cannot loosen a list
    /// that is merely too tight.
    pub line_spacing: f32,
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
            opacity: 1.0,
            ordered: false,
            line_spacing: 1.0,
        }
    }
}

/// The accent colour of row `index`.
pub fn accent(cfg: &Config, stamp: Stamp, index: usize) -> [f32; 3] {
    match cfg.mode {
        Mode::Rise => rise_rgb(stamp.seconds, index as f32),
        Mode::Vape => vape_rgb(stamp.seconds, index as f32),
        Mode::Tesseract => tesseract_rgb(stamp.millis, index as f32),
        Mode::Modern => modern_rgb(stamp.millis, index as f32),
        Mode::Aurora => aurora_rgb(stamp.millis, index as f32, cfg.primary, cfg.secondary),
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
/// the wave is on; the other four modes — Modern included, whose sweep is a
/// function of the clock like the rest — are never static. This is what the
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

/// How long a row that has left the published list stays on screen while it
/// fades and slides back towards the edge it entered from. The source drops the
/// row instantly (HUD.cpp:647-653); the transition is this port's, because
/// enabling or disabling a module in the WebUI should not look like a jump. The
/// figure is contract v3's "~220 ms".
pub const EXIT_SECS: f32 = 0.22;

/// One row's measured geometry, kept so a row can still be painted after it has
/// left the published list. Every field is a plain value measured while the row
/// was live, so an exit never touches the state file again: a writer that stops
/// mid-transition cannot make the last frame jump.
#[derive(Clone, Debug, PartialEq)]
struct RowShape {
    /// The name as drawn (`m_SpacedModules` applied) and the value tag.
    name: String,
    value: String,
    name_w: f32,
    tag_w: f32,
    box_w: f32,
    /// The position the row held in the list while it was live — the accent's
    /// per-row phase, and the y an exit is painted on.
    slot: usize,
    /// The work-pulse strength at the moment the row left, so its text keeps the
    /// same red (or lack of it) on its way out instead of snapping to a different
    /// colour mid-exit.
    flash: u8,
    /// Whether the entry asked for its value in the accent (`highlight`). Only
    /// Aurora reads it today; carried for every mode so an exit repaints the
    /// same value colour the row left with.
    highlight: bool,
}

/// A row on its way out.
#[derive(Clone, Debug)]
struct Exit {
    shape: RowShape,
    /// Seconds since it left, 0 .. `EXIT_SECS`.
    elapsed: f32,
    /// The entrance progress it had when it left, so the exit starts from where
    /// the row actually was rather than snapping to its resting place.
    entrance: f32,
}

/// What this side animates on top of the published state: how far each row has
/// slid in, which rows are sliding out, and the clock the accent functions read.
/// The source keeps `m_SlideProgress` keyed by module name, an `m_LastFrameTime`
/// for the delta and `m_Fps`; the FPS counter has no input on this side of the
/// contract (see the header note), so it is not ported.
pub struct Anim {
    /// When the accent clock was zeroed. Seeded so a review dump is deterministic.
    origin: Instant,
    last: Option<Instant>,
    dt: f32,
    slide: HashMap<String, f32>,
    /// The last measured shape of every *listed* row, keyed the way `slide` is.
    /// This is the material an exit is made of: a row cannot be measured after it
    /// is gone, so it is measured while it is still there.
    shapes: HashMap<String, RowShape>,
    /// The rows that have left and are still being painted out.
    exits: HashMap<String, Exit>,
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
        Anim {
            origin: Instant::now(),
            last: None,
            dt: 0.0,
            slide: HashMap::new(),
            shapes: HashMap::new(),
            exits: HashMap::new(),
        }
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

    /// Record this frame's measured rows. Called before `retain`, so by the time
    /// a row is known to be gone its geometry is already here to be handed to an
    /// exit.
    fn remember(&mut self, rows: &[Row], entries: &[Entry]) {
        for (slot, row) in rows.iter().enumerate() {
            let entry = &entries[row.entry];
            self.shapes.insert(
                row.key.clone(),
                RowShape {
                    name: row.name.clone(),
                    value: entry.value.clone(),
                    name_w: row.name_w,
                    tag_w: row.tag_w,
                    box_w: row.box_w,
                    slot,
                    flash: entry.flash,
                    highlight: entry.highlight,
                },
            );
        }
    }

    /// The geometry remembered for a listed row. Always `Some` right after
    /// `remember`, which is the only way the painter asks for it.
    fn shape(&self, key: &str) -> Option<&RowShape> {
        self.shapes.get(key)
    }

    /// Fold this frame's listed rows into the animation state: a row that was
    /// remembered but is no longer listed starts its exit (and loses its slide
    /// state, exactly as the source's instant drop did, so a module that comes
    /// back starts its entrance from the edge); a row that came back while it was
    /// still leaving is live again and its exit is dropped rather than painted
    /// twice.
    pub fn retain(&mut self, live: &[String]) {
        self.exits.retain(|name, _| !live.iter().any(|live| live == name));

        let gone: Vec<String> = self
            .shapes
            .keys()
            .filter(|name| !live.iter().any(|live| live == *name))
            .cloned()
            .collect();
        for key in gone {
            if let Some(shape) = self.shapes.remove(&key) {
                let entrance = self.slide.get(&key).copied().unwrap_or(1.0);
                self.exits.entry(key).or_insert(Exit { shape, elapsed: 0.0, entrance });
            }
        }
        self.slide.retain(|name, _| live.iter().any(|live| live == name));
    }

    /// Step every exit by one frame and forget the ones whose window is over.
    ///
    /// Called *before* the rows are painted, and `has_exits` is read before that:
    /// the frame in which the last exit is dropped is still a frame that has to
    /// be pushed, because it is the frame that erases the row from the surface.
    fn step_exits(&mut self, dt: f32) {
        for exit in self.exits.values_mut() {
            exit.elapsed += dt;
        }
        self.exits.retain(|_, exit| exit.elapsed < EXIT_SECS);
    }

    /// Whether any row is still leaving — the repaint rule's second input.
    fn has_exits(&self) -> bool {
        !self.exits.is_empty()
    }

    /// How far through its exit a row is, 0..1.
    fn exit_progress(exit: &Exit) -> f32 {
        (exit.elapsed / EXIT_SECS).clamp(0.0, 1.0)
    }

    /// The wall clock the accent functions are evaluated at.
    pub fn stamp(&self, now: Instant) -> Stamp {
        let elapsed = now.saturating_duration_since(self.origin);
        Stamp { seconds: elapsed.as_secs_f32(), millis: elapsed.as_secs_f64() * 1000.0 }
    }

    /// One row's entrance progress: `progress += dt * 6`, clamped to 1
    /// (HUD.cpp:590-596). A row seen for the first time starts at 0 and takes this
    /// frame's step, exactly as the source's `m_SlideProgress[mod.name]` does, and
    /// nothing here fades opacity — the entrance is the source's slide, unchanged.
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
    ///
    /// Exits are emptied too: a review dump has no previous frame to have lost a
    /// row from, and letting one survive would make the dump depend on whatever
    /// ran before it.
    pub fn seed(&mut self, entries: &[Entry], now: Instant) {
        self.origin = now.checked_sub(Duration::from_millis(SEED_MS)).unwrap_or(now);
        self.last = None;
        self.dt = 0.0;
        self.slide.clear();
        self.shapes.clear();
        self.exits.clear();
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
        } else if cfg.mode == Mode::Aurora {
            // Aurora measures with its own 9 px gap for the box it will draw;
            // the wedge sort stays on the space glyph like every other mode.
            name_w + if has_tag { m.aurora_tag_gap + tag_w } else { 0.0 }
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

    // The reference's wedge, and the one thing contract v3's `orderPublished`
    // turns off: the sort is what makes the list a wedge, and it also destroys the
    // user's own module order from the WebUI, which is why the bit exists.
    if !cfg.ordered {
        rows.sort_by(|a, b| b.sort_w.partial_cmp(&a.sort_w).unwrap_or(std::cmp::Ordering::Equal));
    }
    rows
}

// ── painting ────────────────────────────────────────────────────────────────

/// Everything a frame needs that is not the entries or the canvas.
struct Frame<'a> {
    cfg: &'a Config,
    m: &'a Metrics,
    stamp: Stamp,
    /// The overlay's global fade (1.0 live), applied to everything.
    global: f32,
    /// The same fade times the configured background opacity: the multiplier for
    /// the *fills* only. The text keeps `global`, which is what "background
    /// opacity" means (see `Config::opacity`).
    background: f32,
    /// The frame delta for the rows' entrance and the exits.
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
/// "Moving" is the entrance of any row (the source's slide), a row leaving (the
/// v3 exit), **or** a live accent: in Rise, VapeV4, Tesseract and Modern the
/// accent is a function of the clock, and with the Default mode's wave on it is
/// too, so those frames genuinely differ from the last one. With the wave off in
/// Default mode nothing is time-driven, so a settled HUD reports false and the
/// caller repaints only when the state, the mode or the fade moved.
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

    let m = Metrics::with_line_spacing(cfg.mode, cfg.scale, cfg.line_spacing);
    let rect = canvas_rect(canvas);
    let rows = measure_rows(text, entries, cfg, &m);
    let live: Vec<String> = rows.iter().map(|row| row.key.clone()).collect();
    // Measure before forgetting: an exit is painted from the geometry the row had
    // on its last live frame, because there is nothing left to measure it from.
    anim.remember(&rows, entries);
    anim.retain(&live);
    let dt = anim.dt;
    let frame = Frame {
        cfg,
        m: &m,
        stamp: anim.stamp(now),
        global,
        background: global * cfg.opacity.clamp(0.0, 1.0),
        dt,
    };

    if cfg.watermark {
        paint_watermark(canvas, text, &frame, rect);
    }
    // Read *before* stepping: the frame that drops the last exit is the frame
    // that erases the row from the surface, so it has to be pushed as well.
    let exits_moving = anim.has_exits();
    anim.step_exits(dt);
    let rows_moving = paint_rows(canvas, text, anim, &frame, rect, &rows);

    // The repaint rule: an entrance or an exit in flight, or an accent that moves
    // with the clock while something is actually on screen.
    rows_moving || exits_moving || (accent_is_animated(cfg) && !(rows.is_empty() && !cfg.watermark))
}

/// The watermark: one inline run on one baseline — the product name in the mode's
/// accent, then ` | ` and each non-empty segment the state's header carries (the
/// nickname and `"<n> FPS"`) in the mode's secondary colour — plus, in
/// Rise/VapeV4, the source's panel behind it with the accent bar down its right
/// edge (HUD.cpp:508-563).
fn paint_watermark(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    frame: &Frame,
    rect: Rect,
) {
    let m = frame.m;
    let name_font = frame.cfg.mode.name_font();
    let (title_w, middle_w, run_w) =
        measure_watermark(text, name_font, &frame.cfg.nickname, frame.cfg.fps);

    let accent = watermark_accent(frame.cfg, frame.stamp);
    // The canvas is surface-local while the anchoring helpers are in screen
    // coordinates, so the rect's own origin is subtracted here — exactly what
    // `paint_rows` does with its rows. Without this the run is displaced by
    // (+rect.x, +rect.y), which is invisible on the dump's origin-anchored review
    // canvas but wrong for every game window that is not at the screen's origin
    // (and entirely off-surface for a rect with a negative origin, i.e. a monitor
    // left of the primary one).
    let box_x = watermark_x(rect, frame.cfg.position, m, run_w) - rect.x;
    let panel_y = watermark_panel_y(rect, m) - rect.y;
    let text_y = watermark_y(rect, m) - rect.y;
    let rise_like = m.mode.is_rise_like();
    let modern = m.mode == Mode::Modern;
    let aurora = m.mode == Mode::Aurora;

    // The panel behind the run: the source's rectangular one for Rise/VapeV4, or
    // Modern's card, rounded on all four corners like its rows. Both are the same
    // shape of thing — a fill plus the accent bar against the run's right edge —
    // so they share the drawing and differ only in the numbers.
    let panel_w = if modern {
        Some(modern_panel_width(m, run_w))
    } else if aurora {
        Some(aurora_panel_width(m, run_w))
    } else if rise_like {
        Some(watermark_panel_width(m, title_w, middle_w))
    } else {
        None
    };
    if let Some(panel_w) = panel_w {
        let item_h = m.item_height.round().max(1.0) as u32;
        if aurora {
            // The glass pill, and its brand mark: one small gradient dot, the
            // only fixed shape in the run. Its two stops are the mode's pair.
            paint_glass_card(
                canvas,
                box_x,
                panel_y,
                panel_w.round().max(1.0) as u32,
                item_h,
                m.aurora_card_radius,
                frame.background,
            );
            let dot = m.aurora_dot.round().max(1.0) as u32;
            let dot_x = (box_x as f32 + m.aurora_wm_pad_x).round() as i32;
            let dot_y = panel_y + ((item_h as f32 - dot as f32) / 2.0).round() as i32;
            let (cold, warm) = aurora_stops(frame.cfg.primary, frame.cfg.secondary);
            canvas.fill_rounded_rect_gradient(
                dot_x,
                dot_y,
                dot,
                dot,
                [dot as f32 / 2.0; 4],
                pack(cold, 1.0),
                pack(warm, 1.0),
                true,
            );
        } else {
            let panel_colour = if modern { MODERN_BACKGROUND } else { RISE_BACKGROUND };
            canvas.fill_rounded_rect_corners(
                box_x,
                panel_y,
                panel_w.round().max(1.0) as u32,
                item_h,
                chip_corners(m),
                with_alpha(panel_colour, frame.background),
            );
            let bar_w = if modern { m.modern_rect_w } else { m.rise_rect_w };
            let bar_x = (box_x as f32 + panel_w - bar_w).round() as i32;
            let bar_px = bar_w.round().max(1.0) as u32;
            if modern {
                // Modern's bar rounds its own right corners to sit inside the card;
                // the ported panel's bar is square, as the source draws it.
                canvas.fill_rounded_rect_corners(
                    bar_x,
                    panel_y,
                    bar_px,
                    item_h,
                    [0.0, m.modern_card_radius, m.modern_card_radius, 0.0],
                    with_alpha(pack(accent, 1.0), frame.global),
                );
            } else {
                canvas.fill_rect(bar_x, panel_y, bar_px, item_h, with_alpha(pack(accent, 1.0), frame.global));
            }
        }
    }

    let text_x = (box_x as f32
        + if rise_like || modern {
            m.text_inset_x
        } else if aurora {
            // The title clears the pad + dot + gap, not the row inset: the
            // leading zone is wider than a row's spine zone.
            aurora_wm_lead(m)
        } else {
            0.0
        })
        .round() as i32;
    // Rise paints its title in `riseWatermarkTextColor` and leaves the accent to
    // the panel's bar (HUD.cpp:556); every other mode — Modern included — paints
    // it in the accent. Aurora paints it near-white: the accent lives in the dot
    // and the rows' spines, and a gradient title would fight them.
    let title_colour = if m.mode == Mode::Rise {
        RISE_WATERMARK_TEXT
    } else if aurora {
        AURORA_TEXT
    } else {
        pack(accent, 1.0)
    };
    let secondary = if rise_like {
        RISE_SECONDARY_TEXT
    } else if aurora {
        AURORA_MUTED
    } else {
        SECONDARY_TEXT
    };

    draw_shadowed(text, canvas, name_font, PRODUCT, text_x, text_y, title_colour, frame.global);
    // The rest of the run: the nickname and the FPS segment, in the source's own
    // order and both in the secondary colour (HUD.cpp:559-562). An empty segment
    // was never assembled, so a HUD with no nickname and no frame counter draws
    // the bare title rather than a run of dangling separators.
    let mut cursor_x = text_x as f32 + title_w;
    for segment in watermark_middle(&frame.cfg.nickname, frame.cfg.fps) {
        draw_shadowed(
            text,
            canvas,
            FontId::Body,
            &segment,
            cursor_x.round() as i32,
            text_y,
            secondary,
            frame.global,
        );
        cursor_x += text.measure(FontId::Body, &segment) as f32;
    }
}

/// The module list (HUD.cpp:565-645). Returns true while any row is still sliding
/// in; the rows on their way out are painted here too and are reported by
/// `paint`'s own exit flag rather than by this one.
fn paint_rows(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    anim: &mut Anim,
    frame: &Frame,
    rect: Rect,
    rows: &[Row],
) -> bool {
    let m = frame.m;
    let mut moving = paint_exits(canvas, text, anim, frame, rect);

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
        let accent = pack(accent(frame.cfg, frame.stamp, index), 1.0);

        // The geometry `remember` just stored for this row: the same struct an
        // exit is painted from, so the two cannot drift apart.
        if let Some(shape) = anim.shape(&row.key) {
            paint_row(canvas, text, frame, shape, box_x, y, 1.0, accent);
        }
    }

    moving
}

/// Paint the rows that have left the published list. Each one fades and slides
/// back towards the edge its mode enters from — the exact reverse of the
/// entrance — and `Anim::step_exits` forgets it once the window is over.
fn paint_exits(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    anim: &Anim,
    frame: &Frame,
    rect: Rect,
) -> bool {
    if anim.exits.is_empty() {
        return false;
    }
    let m = frame.m;
    // In the order the rows were listed, because a `HashMap`'s iteration order
    // would let two leaving rows swap places frame to frame.
    let mut leaving: Vec<&Exit> = anim.exits.values().collect();
    leaving.sort_by_key(|exit| exit.shape.slot);

    for exit in leaving {
        let t = Anim::exit_progress(exit);
        // `eased_in` is where the row was when it left — a row removed halfway
        // through its entrance leaves from halfway in, not from its resting place.
        let eased_in = 1.0 - (1.0 - exit.entrance).powi(3);
        // Ease-in: the row hangs where it was for a moment and then goes, which
        // reads as the reverse of the entrance's ease-out.
        let eased_out = t * t;
        let target = row_target_x(rect, frame.cfg.position, m, exit.shape.box_w);
        let origin = row_slide_origin(rect, frame.cfg.position, exit.shape.box_w);
        let current = origin + (target - origin) * eased_in * (1.0 - eased_out);
        let box_x = current.round() as i32 - rect.x;
        let y = row_y(rect, m, exit.shape.slot) - rect.y;
        let accent = pack(accent(frame.cfg, frame.stamp, exit.shape.slot), 1.0);
        paint_row(canvas, text, frame, &exit.shape, box_x, y, 1.0 - t, accent);
    }
    true
}

/// One Aurora card — row or watermark pill, the same three draws so the two
/// cannot drift: the 1 px slate border, the glass fill inset inside it, and the
/// 1 px highlight along the fill's top edge. `alpha` is the background
/// multiplier the frame carries (the overlay's fade times the configured
/// opacity); the border, fill and highlight share it, and the text is never
/// dimmed by it.
fn paint_glass_card(
    canvas: &mut Canvas,
    x: i32,
    y: i32,
    w: u32,
    h: u32,
    radius: f32,
    alpha: f32,
) {
    canvas.fill_rounded_rect_corners(x, y, w, h, [radius; 4], with_alpha(AURORA_BORDER, alpha));
    let inner_w = w.saturating_sub(2);
    let inner_h = h.saturating_sub(2);
    canvas.fill_rounded_rect_corners(
        x + 1,
        y + 1,
        inner_w,
        inner_h,
        [(radius - 1.0).max(0.0); 4],
        with_alpha(AURORA_BACKGROUND, alpha),
    );
    if inner_w > 0 {
        canvas.fill_rect(x + 1, y + 1, inner_w, 1, with_alpha(AURORA_TOP_HIGHLIGHT, alpha));
    }
}

/// Paint one row: its card, the accent bar and its two text runs, with the work
/// pulse carried by the **text** colour rather than by a fill. A listed row and a
/// row on its way out both come through here, so an exit cannot look different
/// from the frame that preceded it. `fade` is the row's own 0..1 multiplier on top
/// of the overlay's — 1.0 for a listed row, the exit's ramp for one on its way out.
fn paint_row(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    frame: &Frame,
    shape: &RowShape,
    box_x: i32,
    y: i32,
    fade: f32,
    accent: u32,
) {
    let m = frame.m;
    let name_font = frame.cfg.mode.name_font();
    let item_h = m.item_height.round().max(1.0) as u32;
    let box_w = shape.box_w.round().max(1.0) as u32;
    let strength = flash_strength(shape.flash);
    let text_alpha = frame.global * fade;
    // The work pulse, as a text tint: the name lerps from the row's animated
    // accent and the tag from its own colour towards the mode's red, so a working
    // row reads as its labels going red and a decaying `flash` walks them back.
    // Nothing is filled for it — no wash above the card, the accent bar or the
    // glyphs' shadow, and no change to the row's geometry or background.
    // Aurora's name base is the near-white constant instead of the accent: the
    // accent lives in the spine, and a name that fights it would read as noise.
    let name_base = if m.mode == Mode::Aurora { AURORA_TEXT } else { accent };
    let name_colour = flash_tint(name_base, strength);

    // The chip: Rise/VapeV4 fill the whole row, Tesseract its own dark one,
    // Modern its rounded card, Aurora its glass card, Default nothing at all
    // (HUD.cpp:619-634).
    if m.mode == Mode::Aurora {
        paint_glass_card(
            canvas,
            box_x,
            y,
            box_w,
            item_h,
            m.aurora_card_radius,
            frame.background * fade,
        );
        // The spine: the row's gradient accent. Its top stop is the row's live
        // accent and its bottom one `AURORA_SPINE_OFFSET_MS` further down the
        // same sweep, so the gradient visibly flows downward. It floats inside
        // the card's padding and never touches the border.
        let spine_w = m.aurora_spine_w.round().max(1.0) as u32;
        let spine_x = (box_x as f32 + m.aurora_spine_inset_x).round() as i32;
        let spine_y = y + m.aurora_spine_inset_y.round() as i32;
        let spine_h =
            (m.item_height - 2.0 * m.aurora_spine_inset_y).round().max(spine_w as f32) as u32;
        let bottom = pack(
            aurora_rgb(
                frame.stamp.millis + AURORA_SPINE_OFFSET_MS,
                shape.slot as f32,
                frame.cfg.primary,
                frame.cfg.secondary,
            ),
            1.0,
        );
        canvas.fill_rounded_rect_gradient(
            spine_x,
            spine_y,
            spine_w,
            spine_h,
            [spine_w as f32 / 2.0; 4],
            accent,
            bottom,
            false,
        );
    } else {
        if let Some(colour) = chip_colour(m) {
            canvas.fill_rounded_rect_corners(
                box_x,
                y,
                box_w,
                item_h,
                chip_corners(m),
                with_alpha(colour, frame.background * fade),
            );
        }
        // The accent bar, flush against the row's right edge. Modern rounds its
        // right corners to match the card; the ported modes keep the square bar.
        let bar_w = bar_width(m);
        if bar_w > 0.0 {
            let bar_x = (box_x as f32 + shape.box_w - bar_w).round() as i32;
            let bar_px = bar_w.round().max(1.0) as u32;
            if m.mode == Mode::Modern {
                // Modern's bar rounds its own right corners to sit inside the card.
                canvas.fill_rounded_rect_corners(
                    bar_x,
                    y,
                    bar_px,
                    item_h,
                    [0.0, m.modern_card_radius, m.modern_card_radius, 0.0],
                    with_alpha(accent, text_alpha),
                );
            } else {
                // The ported modes' bar is the source's square one.
                canvas.fill_rect(bar_x, y, bar_px, item_h, with_alpha(accent, text_alpha));
            }
        }
    }

    let text_x = (box_x as f32 + m.text_inset_x).round() as i32;
    let text_y = (y as f32 + m.text_inset_y).round() as i32;

    draw_shadowed(text, canvas, name_font, &shape.name, text_x, text_y, name_colour, text_alpha);

    if shape.tag_w > 0.0 {
        let space_w = text.measure(FontId::Body, " ") as f32;
        let tag_x = (text_x as f32 + shape.name_w + tag_gap(m, space_w)).round() as i32;
        // The value is tinted by the same strength as the name, from the colour
        // this mode gives that row's tag — so "the labels go red" is one rule, not
        // two, and the two runs cannot disagree about how far into the pulse the
        // row is. Aurora lifts a highlighted value into the accent before the
        // tint; every other mode takes `tag_colour`'s word.
        let tag_base = if m.mode == Mode::Aurora {
            if shape.highlight {
                accent
            } else {
                AURORA_MUTED
            }
        } else {
            tag_colour(m, shape.flash != 0, accent)
        };
        let tag = flash_tint(tag_base, strength);
        draw_shadowed(text, canvas, FontId::Body, &shape.value, tag_x, text_y, tag, text_alpha);
    }
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
    let m = Metrics::with_line_spacing(cfg.mode, cfg.scale, cfg.line_spacing);
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
        "  background opacity {:.0}% of the fills only (text at 100%) | rows {} | work pulse {} \
         tints the row's text by flash/255 — no fill, the card is unchanged\n",
        cfg.opacity * 100.0,
        if cfg.ordered { "in published order (orderPublished)" } else { "sorted by width (the wedge)" },
        hex(FLASH_TINT),
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
        let (title_w, middle_w, run_w) =
            measure_watermark(text, name_font, &cfg.nickname, cfg.fps);
        let box_x = watermark_x(rect, cfg.position, &m, run_w);
        out.push_str(&format!(
            "  watermark \"{}\" — {PRODUCT} {:.0}px + segments {:.0}px = {:.0}px at ({}, {}){}",
            watermark_text(&cfg.nickname, cfg.fps),
            title_w,
            middle_w,
            run_w,
            box_x,
            watermark_y(rect, &m),
            if m.mode == Mode::Rise {
                " title #E8E8E8 (riseWatermarkTextColor)"
            } else if m.mode == Mode::Aurora {
                " title #ECF2F8 (AURORA_TEXT)"
            } else {
                " title in the accent"
            },
        ));
        if m.mode.is_rise_like() {
            let panel_w = watermark_panel_width(&m, title_w, middle_w);
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
        } else if m.mode == Mode::Modern {
            out.push_str(&format!(
                ", card {:.0}x{:.0} at ({}, {}) {} radius {:.0} on all four corners, pad {}x{}, \
                 bar {:.0}px rounded right",
                modern_panel_width(&m, run_w),
                m.item_height,
                box_x,
                (rect.y as f32 + m.top_y).round() as i32,
                hex(MODERN_BACKGROUND),
                m.modern_card_radius,
                m.modern_pad_x,
                m.modern_pad_y,
                m.modern_rect_w,
            ));
        } else if m.mode == Mode::Aurora {
            out.push_str(&format!(
                ", glass pill {:.0}x{:.0} at ({}, {}) {} radius {:.0} on all four corners, dot \
                 {:.0}px at +{:.0}, lead {:.0}px, no bar",
                aurora_panel_width(&m, run_w),
                m.item_height,
                box_x,
                (rect.y as f32 + m.top_y).round() as i32,
                hex(AURORA_BACKGROUND),
                m.aurora_card_radius,
                m.aurora_dot,
                m.aurora_wm_pad_x,
                aurora_wm_lead(&m),
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
            let accent_now = pack(accent(cfg, Stamp { seconds: SEED_MS as f32 / 1000.0, millis: SEED_MS as f64 }, slot), 1.0);
            // The colour the painter will actually use for the tag: `tag_colour`
            // with the pulse's own tint over it, so the dump's numbers cannot drift
            // from the frame.
            let tag_now = flash_tint(
                // Aurora lifts a highlighted value into the accent before the
                // pulse tint, exactly as `paint_row` does — the dump's numbers
                // cannot drift from the frame.
                if m.mode == Mode::Aurora {
                    if entry.highlight {
                        accent_now
                    } else {
                        AURORA_MUTED
                    }
                } else {
                    tag_colour(&m, entry.flash != 0, accent_now)
                },
                flash_strength(entry.flash),
            );
            out.push_str(&format!(
                " (gap {:.0}), tag {:.0}px {}",
                tag_gap(&m, text.measure(FontId::Body, " ") as f32),
                row.tag_w,
                hex(tag_now),
            ));
            if entry.flash != 0 {
                out.push_str(&format!(", WORK PULSE {}/255", entry.flash));
            }
        }
        out.push('\n');
    }
    if rows.is_empty() {
        out.push_str("  (no listed modules: nothing to draw)\n");
    }

    out.push_str(&format!(
        "motion: entrance slide from the screen edge, progress += dt*{SLIDE_RATE} (dt clamped to \
         {MAX_DELTA_S}s), easeOutCubic; exit slide back to that edge with a {EXIT_SECS}s fade | \
         accent {} | defaults wave cycle {WAVE_CYCLE_MS}ms/{WAVE_ROW_OFFSET_MS}ms per row\n",
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

    /// `flash` stays a bool at the call sites because every test that predates
    /// contract v3 means "working / not working"; the wire value v3 carries is
    /// 255 for the working case, which is the strength that tints the row most.
    fn entry(id: u32, name: &str, value: &str, flash: bool) -> Entry {
        Entry {
            id,
            enabled: true,
            flash: if flash { 255 } else { 0 },
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

    /// The user's line-spacing knob scales the row pitch — the whole pitch, not just
    /// the gap, so it works in the modes whose own `moduleSpacing` is 0 (Rise/VapeV4
    /// chips touch, and a multiplier on the gap alone would leave the knob dead).
    #[test]
    fn line_spacing_scales_the_row_pitch_in_every_mode() {
        for mode in [Mode::Default, Mode::Rise, Mode::Vape, Mode::Tesseract, Mode::Modern, Mode::Aurora] {
            let one = Metrics::with_line_spacing(mode, 1.0, 1.0);
            let one_and_a_half = Metrics::with_line_spacing(mode, 1.0, 1.5);
            let tight = Metrics::with_line_spacing(mode, 1.0, 0.5);

            assert_eq!(row_pitch(&one), one.item_height + one.module_spacing, "{mode:?}");
            assert_eq!(row_pitch(&one_and_a_half), row_pitch(&one) * 1.5, "{mode:?}");
            assert_eq!(row_pitch(&tight), row_pitch(&one) * 0.5, "{mode:?}");
            // The chip itself is untouched: only the distance between rows moves.
            assert_eq!(one_and_a_half.item_height, one.item_height, "{mode:?}");
            assert_eq!(one_and_a_half.font_px, one.font_px, "{mode:?}");
        }
    }

    /// A pitch of zero would stack every row on top of the previous one, and a
    /// writer that asks for 100x must not push the list off the surface.
    #[test]
    fn line_spacing_is_clamped() {
        assert_eq!(Metrics::with_line_spacing(Mode::Default, 1.0, 0.0).line_spacing, 0.1);
        assert_eq!(Metrics::with_line_spacing(Mode::Default, 1.0, 99.0).line_spacing, 2.0);
        assert_eq!(Metrics::with_line_spacing(Mode::Default, 1.0, f32::NAN).line_spacing, 1.0);
        // `new` is `with_line_spacing(.., 1.0)`, so the reference geometry is unchanged.
        assert_eq!(Metrics::new(Mode::Default, 1.0).line_spacing, 1.0);
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
        assert_eq!(
            chip_corners(&Metrics::new(Mode::Aurora, 1.0)),
            [7.0; 4],
            "Aurora rounds all four corners like Modern"
        );

        // The chip fills: the source's own background colours, and none in Default.
        assert_eq!(chip_colour(&Metrics::new(Mode::Default, 1.0)), None);
        assert_eq!(chip_colour(&Metrics::new(Mode::Rise, 1.0)), Some(RISE_BACKGROUND));
        assert_eq!(chip_colour(&Metrics::new(Mode::Vape, 1.0)), Some(RISE_BACKGROUND));
        assert_eq!(chip_colour(&Metrics::new(Mode::Tesseract, 1.0)), Some(TESSERACT_BACKGROUND));
        assert_eq!(chip_colour(&Metrics::new(Mode::Aurora, 1.0)), Some(AURORA_BACKGROUND));
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
        assert_eq!(
            box_width(&Metrics::new(Mode::Aurora, 1.0), 100.0),
            100.0 + 11.0 + 9.0,
            "Aurora's card padding, the spine floating inside it"
        );

        // The panel the watermark sits in: the run plus the same padding and bar,
        // and no FPS term (this overlay has no frame counter).
        assert_eq!(watermark_panel_width(&Metrics::new(Mode::Rise, 1.0), 74.0, 33.0), 74.0 + 33.0 + 11.0);
        assert_eq!(
            aurora_panel_width(&Metrics::new(Mode::Aurora, 1.0), 74.0),
            8.0 + 6.0 + 7.0 + 74.0 + 9.0,
            "pad + dot + gap before the run, then the right pad"
        );
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
        assert_eq!(
            tag_gap(&Metrics::new(Mode::Aurora, 1.0), space),
            AURORA_TAG_GAP,
            "Aurora's own 9 px, not the space glyph"
        );
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
        for mode in [Mode::Rise, Mode::Vape, Mode::Tesseract, Mode::Modern] {
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
        // VapeV4's alone; every other ported mode stays grey whatever the flash
        // says. Modern is the exception — its tag *is* the row's accent.
        let accent = argb(1, 2, 3, 255);
        for mode in [Mode::Default, Mode::Rise, Mode::Tesseract] {
            let m = Metrics::new(mode, 1.0);
            assert_eq!(
                tag_colour(&m, true, accent),
                tag_colour(&m, false, accent),
                "{mode:?} keeps its grey"
            );
        }
        let vape = Metrics::new(Mode::Vape, 1.0);
        assert_eq!(tag_colour(&vape, true, accent), VAPE_TAG_ACTIVE);
        assert_eq!(tag_colour(&vape, false, accent), RISE_SECONDARY_TEXT);
        let modern = Metrics::new(Mode::Modern, 1.0);
        assert_eq!(tag_colour(&modern, false, accent), accent, "Modern's tag is the accent");
        assert_eq!(tag_colour(&modern, true, accent), accent, "and the wash carries the pulse");
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

    /// The watermark run is the source's `name | nickname | "<n> FPS"` again, now
    /// that the state header publishes both segments — with any segment the state
    /// had nothing to say about dropped, separators included.
    #[test]
    fn the_watermark_run_drops_the_segments_it_has_nothing_for() {
        assert_eq!(watermark_text("gtier", 144), "DOPPEL | gtier | 144 FPS");
        assert_eq!(watermark_segments("gtier", 144), vec!["gtier".to_string(), "144 FPS".to_string()]);
        assert_eq!(
            watermark_middle("gtier", 144),
            vec![" | gtier".to_string(), " | 144 FPS".to_string()],
            "each segment carries its own separator, as the source's `segment` and `fpsText` do"
        );

        // A missing segment takes its separator with it: never a dangling " | ".
        assert_eq!(watermark_text("", 144), "DOPPEL | 144 FPS");
        assert_eq!(watermark_text("gtier", 0), "DOPPEL | gtier");
        assert_eq!(watermark_text("", 0), "DOPPEL", "a v1 block draws the bare title");
        assert!(watermark_segments("", 0).is_empty());
        assert!(watermark_middle("", 0).is_empty());
        // fps 0 is "unknown", not a frame counter of zero.
        assert!(!watermark_text("nick", 0).contains("FPS"));

        // The measured run is the title plus every segment, and the Rise-like
        // panel is that run plus the method's padding and bar — the source's
        // `titleWidth + segmentWidth + fpsWidth + padX*2 + rectW + 1`
        // (HUD.cpp:539-544). The FPS term is really measured, not assumed: a run
        // with one segment fewer is narrower.
        let text = TextEngine::new(1.0).expect("GDI text engine");
        let m = Metrics::new(Mode::Rise, 1.0);
        let (title_w, middle_w, run_w) = measure_watermark(&text, m.mode.name_font(), "gtier", 144);
        assert_eq!(run_w, title_w + middle_w);
        assert_eq!(title_w, text.measure(m.mode.name_font(), PRODUCT) as f32);
        assert_eq!(
            watermark_panel_width(&m, title_w, middle_w),
            run_w + m.rise_pad_x * 2.0 + m.rise_rect_w + 1.0
        );
        let (_, no_fps_middle, no_fps_run) = measure_watermark(&text, m.mode.name_font(), "gtier", 0);
        let (_, bare_middle, bare_run) = measure_watermark(&text, m.mode.name_font(), "", 0);
        assert!(no_fps_middle < middle_w && no_fps_run < run_w);
        assert_eq!(bare_run, title_w, "nothing but the title");
        assert_eq!(bare_middle, 0.0);
        // And the run's width is what `watermark_x` mirrors with.
        let rect = Rect::new(0, 0, 1920, 1080);
        assert_eq!(
            watermark_x(rect, Position::Left, &m, run_w),
            1920 - 10 - run_w.round() as i32
        );
    }

    /// The reference's two-corner layout, mode by mode: the watermark left-anchored
    /// at `cursorX = 10` and the module list right-anchored, both on the rect's own
    /// top line (HUD.cpp:536-537, 610-614).
    #[test]
    fn the_two_blocks_hang_off_opposite_top_corners_in_every_mode() {
        let rect = Rect::new(0, 0, 1920, 1080);
        let run_w = 120.0f32;

        // mode, the watermark's own y inset, the first row's y inset
        let cases = [
            (Mode::Default, 4, 4),
            (Mode::Rise, 6, 4),
            (Mode::Vape, 6, 4),
            (Mode::Tesseract, 6, 2),
        ];

        for (mode, watermark_inset, row_inset) in cases {
            let m = Metrics::new(mode, 1.0);

            // Watermark: top-LEFT. Its left edge is `cursorX = 10` in from the
            // rect's left edge, its y is `topY` plus the mode's own pad.
            assert_eq!(watermark_x(rect, Position::Right, &m, run_w), 10, "{mode:?}");
            assert_eq!(watermark_y(rect, &m), watermark_inset, "{mode:?}");

            // List: top-RIGHT. Every row's box ends at the rect's right edge minus
            // the mode's margin — `list_flush_x` is `targetX + boxWidth` for any
            // row width, which is what makes the list a wedge with one straight
            // side rather than a ragged edge.
            assert_eq!(
                list_flush_x(rect, Position::Right, &m),
                1920 - m.right_margin.round() as i32,
                "{mode:?}"
            );
            for box_w in [40.0f32, 137.0, 260.0] {
                assert_eq!(
                    row_target_x(rect, Position::Right, &m, box_w) + box_w,
                    list_flush_x(rect, Position::Right, &m) as f32,
                    "{mode:?} box {box_w}"
                );
            }
            assert_eq!(row_y(rect, &m, 0), row_inset, "{mode:?}");

            // Both blocks are on the rect's top line: the first row starts at
            // `topY` (or Tesseract's 2), and in the Rise-like modes the watermark's
            // panel starts on exactly the row's line. Nothing is stacked below
            // anything else.
            assert_eq!(m.top_y, 4.0, "{mode:?}");
            assert!(row_y(rect, &m, 0) <= 4 + 2, "{mode:?}: the list starts at the top");
            assert!(watermark_y(rect, &m) <= 4 + 2, "{mode:?}: so does the watermark");
            if m.mode.is_rise_like() {
                assert_eq!(
                    watermark_panel_y(rect, &m),
                    row_y(rect, &m, 0),
                    "{mode:?}: panel and first row share the line"
                );
            }

            // The rows march *down* from that line and the watermark does not
            // move with them: the second row is already below the first.
            assert!(row_y(rect, &m, 1) > row_y(rect, &m, 0), "{mode:?}");
        }

        // `--position left` is the mirror of that: watermark top-right, list
        // top-left, same insets, and the y of each block unchanged.
        for (mode, watermark_inset, row_inset) in cases {
            let m = Metrics::new(mode, 1.0);
            assert_eq!(
                watermark_x(rect, Position::Left, &m, run_w),
                1920 - 10 - run_w.round() as i32,
                "{mode:?}"
            );
            assert_eq!(list_flush_x(rect, Position::Left, &m), m.right_margin.round() as i32, "{mode:?}");
            assert_eq!(watermark_y(rect, &m), watermark_inset, "{mode:?}: only x mirrors");
            assert_eq!(row_y(rect, &m, 0), row_inset, "{mode:?}");
        }

        // A rect that is not at the origin moves both blocks with it, and keeps
        // them in opposite corners of *that* rect — this is the game-window case,
        // where the rect comes from `--rect`.
        let window = Rect::new(-1920, 240, 1600, 900);
        for (mode, watermark_inset, row_inset) in cases {
            let m = Metrics::new(mode, 1.0);
            assert_eq!(watermark_x(window, Position::Right, &m, run_w), -1920 + 10, "{mode:?}");
            assert_eq!(watermark_y(window, &m), 240 + watermark_inset, "{mode:?}");
            assert_eq!(
                list_flush_x(window, Position::Right, &m),
                -1920 + 1600 - m.right_margin.round() as i32,
                "{mode:?}"
            );
            assert_eq!(row_y(window, &m, 0), 240 + row_inset, "{mode:?}");
        }
    }

    /// The overlay's canvas carries the window's screen origin while the anchoring
    /// helpers work in screen coordinates. One frame of the same config must land
    /// on the same surface pixels whatever origin the canvas was placed at — the
    /// regression that put the watermark at `rect.x + 10` *canvas-local*, which is
    /// a different place on every window position and no place at all (off the
    /// surface) for a rect with a negative origin.
    #[test]
    fn the_frame_lands_on_the_same_pixels_whatever_origin_the_canvas_has() {
        let entries = vec![entry(1, "Velocity", "82%", false), entry(2, "Reach", "3.1-3.3", false)];
        let cfg = Config { nickname: "gtier".to_string(), fps: 144, ..Config::default() };
        let mut text = TextEngine::new(1.0).expect("GDI text engine");
        let now = Instant::now();
        let mut reference: Option<Vec<u32>> = None;

        for (x, y) in [(0, 0), (320, 180), (1920, 0), (-1920, 240)] {
            let mut canvas = Canvas::placed(x, y, 520, 400);
            let mut anim = Anim::new();
            anim.seed(&entries, now);
            canvas.clear();
            paint(&mut canvas, &mut text, &entries, &mut anim, now, &cfg);
            let pixels = canvas.pixels().to_vec();
            assert!(pixels.iter().any(|pixel| *pixel != 0), "({x}, {y}) drew nothing");
            match &reference {
                None => reference = Some(pixels),
                Some(want) => assert_eq!(&pixels, want, "origin ({x}, {y}) moved the HUD"),
            }
        }
    }

    // ── Modern, this port's own mode ────────────────────────────────────────

    /// Modern's numbers, none of which come from the source: a taller row than
    /// Default, 4 px of spacing, a card rounded on all four corners, a 3 px bar
    /// and a watermark that is a card too. The point of the mode is that none of
    /// this is a recolour of the other four, so the geometry is pinned here.
    #[test]
    fn modern_is_its_own_geometry_not_a_recolour_of_the_four() {
        let m = Metrics::new(Mode::Modern, 1.0);
        assert_eq!(m.font_px, 16.0);
        assert_eq!(m.item_height, 22.0, "Default's +2 plus the ~4 the mode asks for");
        assert_eq!(m.module_spacing, 4.0, "~4 px between cards");
        assert_eq!(row_pitch(&m), 26.0);
        assert_eq!(m.right_margin, 6.0, "~6 px of right margin");
        assert_eq!(m.base_y, 4.0);
        assert_eq!(bar_width(&m), 3.0);
        assert_eq!(chip_corners(&m), [6.0; 4], "rounded on all four corners");
        assert_eq!(chip_colour(&m), Some(MODERN_BACKGROUND));
        assert!(MODERN_BACKGROUND >> 24 < 255, "the card is translucent");
        assert!(!m.mode.is_rise_like(), "Modern shares nothing with Rise/VapeV4");
        assert_eq!(m.mode.name_font() as u8, FontId::Body as u8);
        assert_eq!(box_width(&m, 100.0), 100.0 + 12.0 + 3.0);
        assert_eq!(tag_gap(&m, 4.0), 4.0, "a space glyph, not Tesseract's gap");

        // The card's own numbers scale like every other mode's.
        let two = Metrics::new(Mode::Modern, 2.0);
        assert_eq!(chip_corners(&two), [12.0; 4]);
        assert_eq!(bar_width(&two), 6.0);
        assert_eq!(row_pitch(&two), 52.0);
        assert_eq!(two.scale, 2.0);

        // The watermark is a card as tall as a row, with the run inset inside it
        // the way Rise's panel does it.
        assert_eq!(m.watermark_y, 6.0, "topY + the card's pad");
        assert_eq!(m.text_inset_x, 6.0);
        assert_eq!(m.text_inset_y, 2.0);
        assert_eq!(modern_panel_width(&m, 100.0), 100.0 + 12.0 + 3.0);

        // The mode is reachable from the command line and from the header's
        // three-bit field, and it is animated by construction.
        assert_eq!(Mode::parse("modern").unwrap(), Mode::Modern);
        assert_eq!(Mode::parse("MODERN").unwrap(), Mode::Modern);
        assert_eq!(Mode::Modern.label(), "modern");
        assert!(Mode::parse("bogus").is_err());
        assert!(accent_is_animated(&Config { mode: Mode::Modern, wave: false, ..Config::default() }));
    }

    /// Modern's accent: a slow, pure function of the clock between two colours,
    /// with a per-row offset — never a copy of one of the four ported ones.
    #[test]
    fn modern_sweeps_slowly_between_two_colours() {
        let cold = [0.30, 0.78, 1.00];
        let warm = [0.70, 0.44, 1.00];
        assert!(rgb_near(modern_rgb(0.0, 0.0), cold));
        assert!(rgb_near(modern_rgb((MODERN_SWEEP_MS / 2.0) as f64, 0.0), warm));
        // One full sweep is back where it started, and the phase is the clock's
        // alone: the same millisecond gives the same colour, always.
        assert!(rgb_near(modern_rgb(MODERN_SWEEP_MS as f64, 0.0), cold));
        assert_eq!(modern_rgb(1234.0, 2.0), modern_rgb(1234.0, 2.0));

        // Rows are offset from each other, so the column is a gradient.
        assert!(!rgb_near(modern_rgb(0.0, 0.0), modern_rgb(0.0, 1.0)));
        // Every colour stays inside the unit cube, whatever the clock says.
        for step in 0..40 {
            for index in 0..4 {
                let c = modern_rgb(step as f64 * 137.0, index as f32);
                assert!(c.iter().all(|v| (0.0..=1.0).contains(v)), "{c:?}");
            }
        }

        // The overlay's own row function is that function, and it is genuinely
        // slower than the four ported accents: a fifth of a second in, the colour
        // has barely moved.
        let cfg = Config { mode: Mode::Modern, ..Config::default() };
        let stamp = Stamp { seconds: 1.0, millis: 2000.0 };
        assert_eq!(accent(&cfg, stamp, 0), modern_rgb(2000.0, 0.0));
        let drift = modern_rgb(200.0, 0.0);
        let moved = (0..3).map(|i| (drift[i] - cold[i]).abs()).fold(0.0f32, f32::max);
        assert!(moved < 0.1, "a 200 ms step must not jump the sweep: {drift:?}");
    }

    // ── the exit animation (contract v3) ────────────────────────────────────

    // ── Aurora (this port's own sixth mode) ──────────────────────────────

    /// Aurora's numbers are its own, and they all scale together: a 24 px glass
    /// card at pitch 28, the spine zone as the text inset, the pill's text one
    /// pad below the top line.
    #[test]
    fn aurora_is_its_own_glass_geometry() {
        let m = Metrics::new(Mode::Aurora, 1.0);
        assert_eq!(m.item_height, 16.0 + 8.0, "the tallest card yet");
        assert_eq!(row_pitch(&m), 24.0 + 4.0);
        assert_eq!(m.right_margin, 8.0);
        assert_eq!(m.text_inset_x, 11.0, "the spine zone plus breathing room");
        assert_eq!(m.text_inset_y, 4.0, "centred in the 24 px card");
        assert_eq!(m.watermark_y, 4.0 + 4.0, "the pill's text, one pad in");
        assert_eq!(m.aurora_card_radius, 7.0);
        assert_eq!(m.aurora_tag_gap, 9.0);
        assert_eq!(m.aurora_spine_w, 3.0);

        // And it all scales, like every other mode's numbers.
        let big = Metrics::new(Mode::Aurora, 2.0);
        assert_eq!(big.item_height, 48.0);
        assert_eq!(big.aurora_card_radius, 14.0);
        assert_eq!(big.aurora_tag_gap, 18.0);
    }

    /// Aurora reads the bold face like Default, sweeps whatever the wave switch
    /// says, and parses and prints like the other five.
    #[test]
    fn aurora_names_are_bold_and_always_animated() {
        assert!(matches!(Mode::Aurora.name_font(), FontId::Name), "bold names, like Default");
        let mut cfg = Config::default();
        cfg.mode = Mode::Aurora;
        cfg.wave = false;
        assert!(accent_is_animated(&cfg), "the 6 s sweep runs regardless of the wave switch");
        assert_eq!(Mode::parse("aurora"), Ok(Mode::Aurora));
        assert_eq!(Mode::Aurora.label(), "aurora");
    }

    /// The sweep: the mode's own pair while the header still carries the
    /// reference defaults, the user's pair the moment they pick one, phase 0 on
    /// the cold stop, half a cycle later on the warm one, and each row a step
    /// along the way.
    #[test]
    fn aurora_sweeps_the_stops_over_six_seconds() {
        let mut cfg = Config::default();
        cfg.mode = Mode::Aurora;
        let cold = accent(&cfg, Stamp { seconds: 0.0, millis: 0.0 }, 0);
        let warm = accent(&cfg, Stamp { seconds: 3.0, millis: 3000.0 }, 0);
        for i in 0..3 {
            assert!(
                (cold[i] - AURORA_COLD[i]).abs() < 1.0 / 255.0,
                "phase 0 is the cold stop: {cold:?}"
            );
            assert!(
                (warm[i] - AURORA_WARM[i]).abs() < 1.0 / 255.0,
                "half a cycle later is the warm one: {warm:?}"
            );
        }

        // The per-row phase walks the sweep, so the column reads as a gradient.
        let a = accent(&cfg, Stamp { seconds: 0.0, millis: 0.0 }, 0);
        let b = accent(&cfg, Stamp { seconds: 0.0, millis: 0.0 }, 1);
        assert!(a != b, "row 1 sits elsewhere on the sweep than row 0");

        // A user's own colours are honoured over the built-in pair.
        let mine = [0.1, 0.2, 0.3];
        let theirs = [0.9, 0.8, 0.7];
        let stops = aurora_stops(mine, theirs);
        assert_eq!(stops.0, mine);
        assert_eq!(stops.1, theirs);
    }

    /// The work pulse reaches Aurora like every other mode — it tints the row's
    /// text towards the mode red, from whatever colour the run would have used,
    /// the white name included.
    #[test]
    fn aurora_work_pulse_tints_the_white_name_towards_red() {
        assert_eq!(flash_tint(AURORA_TEXT, 0.0), AURORA_TEXT, "no flash, no tint");
        assert_eq!(
            flash_tint(AURORA_TEXT, 1.0) & 0x00FF_FFFF,
            FLASH_TINT & 0x00FF_FFFF,
            "a full pulse is the mode red, whatever the base was"
        );
    }

    /// The timeline of a row leaving: it is remembered while it is listed, it
    /// starts an exit the frame it disappears, it is painted for ~220 ms and then
    /// it is forgotten — and its slide state goes with it, so a module that comes
    /// back slides in from the edge again.
    #[test]
    fn a_row_that_leaves_is_painted_for_the_exit_window_and_then_forgotten() {
        let text = TextEngine::new(1.0).expect("GDI text engine");
        let entries = vec![entry(1, "Velocity", "82%", false)];
        let cfg = Config::default();
        let m = Metrics::new(cfg.mode, 1.0);
        let rows = measure_rows(&text, &entries, &cfg, &m);

        let mut anim = Anim::new();
        anim.remember(&rows, &entries);
        anim.retain(&["Velocity".to_string()]);
        assert!(!anim.has_exits(), "a row that is still listed has not left");

        // It was halfway through its entrance when it disappeared, which is the
        // position its exit has to start from.
        assert!(near(anim.progress("Velocity", 0.1), 0.6));
        anim.retain(&[]);
        assert!(anim.has_exits(), "the frame it disappears it starts leaving");
        let (elapsed, entrance) = {
            let exit = anim.exits.values().next().expect("one exit");
            (exit.elapsed, exit.entrance)
        };
        assert_eq!(elapsed, 0.0);
        assert!(near(entrance, 0.6), "the exit starts where the row was");

        // 220 ms at 60 Hz. The frame that finds the window over is still a frame
        // that has to be pushed — it is the one that erases the row.
        let dt = 1.0 / 60.0;
        let mut frames = 0;
        while anim.has_exits() {
            anim.step_exits(dt);
            frames += 1;
            assert!(frames <= 20, "an exit must end");
        }
        assert_eq!(frames, (EXIT_SECS / dt).ceil() as usize, "220 ms at 60 Hz");
        assert_eq!(frames, 14);
        assert!(!anim.has_exits(), "and then it is forgotten");

        // The slide state left with it: a module that comes back starts from the
        // edge rather than from wherever it was dropped.
        assert!(near(anim.progress("Velocity", 0.01), 0.06));
        let _ = anim.shape("Velocity");

        // A row that comes back while it is still leaving is live again, not two
        // rows: the exit is dropped the moment the name is listed.
        let mut anim = Anim::new();
        anim.remember(&rows, &entries);
        anim.retain(&[]);
        assert!(anim.has_exits());
        anim.remember(&rows, &entries);
        anim.retain(&["Velocity".to_string()]);
        assert!(!anim.has_exits(), "the returning row cancels its own exit");
    }

    /// `paint` has to keep the frames coming for the whole exit window: a mode
    /// that is otherwise completely static (Default without the wave) would
    /// otherwise leave the row's last pixels on screen for good.
    #[test]
    fn paint_keeps_pushing_frames_while_a_row_is_leaving() {
        let mut text = TextEngine::new(1.0).expect("GDI text engine");
        let mut canvas = Canvas::new(520, 400);
        let t0 = Instant::now();
        let both = vec![entry(1, "Velocity", "82%", false), entry(2, "Reach", "3.1-3.3", false)];
        let one = vec![entry(1, "Velocity", "82%", false)];
        let cfg = Config { mode: Mode::Default, wave: false, ..Config::default() };

        let mut anim = Anim::new();
        anim.seed(&both, t0);
        canvas.clear();
        assert!(!paint(&mut canvas, &mut text, &both, &mut anim, t0, &cfg), "a settled static HUD");
        assert!(anim.exits.is_empty());

        // One module is disabled. Every frame from here is one the compositor has
        // to be given, right up to the frame that erases the row.
        let mut frames = 0;
        loop {
            frames += 1;
            assert!(frames <= 30, "the exit must end");
            let at = t0 + Duration::from_millis(16 * frames);
            anim.tick(at);
            canvas.clear();
            if !paint(&mut canvas, &mut text, &one, &mut anim, at, &cfg) {
                break;
            }
        }
        assert!(!anim.has_exits(), "and the row is forgotten by then");
        assert!(frames >= 14, "the exit held the frames for its whole window: {frames}");

        // A review dump never has an exit in flight: `seed` empties them, so a
        // dump cannot depend on the frame that ran before it.
        let mut anim = Anim::new();
        anim.remember(&measure_rows(&text, &both, &cfg, &Metrics::new(cfg.mode, 1.0)), &both);
        anim.retain(&[]);
        assert!(anim.has_exits());
        anim.seed(&both, t0);
        assert!(!anim.has_exits() && anim.shapes.is_empty());
    }

    // ── the work pulse (contract v3's entry `flash`) ────────────────────────

    /// The tint is a pure lerp from whatever colour the run would otherwise be
    /// drawn in, which is what makes it work in all five modes: the base is the
    /// row's own accent (or its own tag colour), never a constant, and every base
    /// lands on the same red at full strength.
    #[test]
    fn flash_tint_lerps_the_rows_own_colour_towards_the_red() {
        let base = argb(0x30, 0x80, 0xC0, 0xFF);
        assert_eq!(flash_tint(base, 0.0), base, "an idle row keeps its own colour exactly");
        assert_eq!(
            flash_tint(base, 1.0),
            argb(0xFF, 0x55, 0x55, 0xFF),
            "a full pulse is the mode's red, byte for byte"
        );
        assert_eq!(
            flash_tint(base, 1.0) & 0x00FF_FFFF,
            FLASH_TINT & 0x00FF_FFFF,
            "and that red is the constant the painter means"
        );

        // Every mode's normal colour is a different base; the same helper has to
        // reach the same red from each of them, which is the whole reason the base
        // is a parameter and not a table.
        for normal in [DEFAULT_PRIMARY, DEFAULT_SECONDARY, [1.0, 1.0, 1.0], [0.0, 0.0, 0.0]] {
            let hot = flash_tint(pack(normal, 1.0), 1.0);
            assert_eq!(hot & 0x00FF_FFFF, FLASH_TINT & 0x00FF_FFFF, "from {normal:?}");
        }

        // A decaying value walks the channels, it does not jump.
        let mid = flash_tint(base, 0.5);
        assert!(((mid >> 16) & 0xFF) > ((base >> 16) & 0xFF), "red rises");
        assert!(((mid >> 8) & 0xFF) < ((base >> 8) & 0xFF), "green falls");
        assert!(((mid & 0xFF)) < ((base & 0xFF)), "blue falls");
        assert_ne!(mid, base);
        assert_ne!(mid, flash_tint(base, 1.0));

        // The alpha belongs to the caller: `with_alpha` fades the run afterwards,
        // and an out-of-range strength cannot wrap a channel.
        assert_eq!(flash_tint(argb(0x10, 0x20, 0x30, 0x80), 1.0) >> 24, 0x80, "alpha is kept");
        assert_eq!(flash_tint(base, 2.0), flash_tint(base, 1.0), "clamped above");
        assert_eq!(flash_tint(base, -1.0), base, "and below");
    }

    /// The pulse is a strength, not a flag, and it is carried by the **text**: at
    /// 255 the row's glyphs are red where an idle row's are its accent, while every
    /// background pixel of the card behind them is byte-for-byte what it was at 0.
    /// Measured on the pixels, because "the name goes red, no red block appears" is
    /// the requirement and `flash_tint` alone cannot prove the painter uses it.
    #[test]
    fn a_full_flash_tints_the_text_and_leaves_the_card_alone() {
        let mut text = TextEngine::new(1.0).expect("GDI text engine");
        let now = Instant::now();
        let cfg = Config { mode: Mode::Modern, ..Config::default() };
        let rect = Rect::new(0, 0, 520, 400);
        let m = Metrics::new(Mode::Modern, 1.0);
        let entries = |flash: u8| {
            vec![Entry {
                id: 1,
                enabled: true,
                flash,
                highlight: false,
                name: "Velocity".to_string(),
                value: "82%".to_string(),
            }]
        };

        let frame = |text: &mut TextEngine, flash: u8| {
            let entries = entries(flash);
            let mut anim = Anim::new();
            anim.seed(&entries, now);
            let mut canvas = Canvas::placed(0, 0, 520, 400);
            canvas.clear();
            paint(&mut canvas, text, &entries, &mut anim, now, &cfg);
            canvas
        };

        assert_eq!(flash_strength(0), 0.0);
        assert_eq!(flash_strength(255), 1.0);
        assert!(near(flash_strength(128), 128.0 / 255.0));

        let cold = frame(&mut text, 0);
        let mid = frame(&mut text, 128);
        let hot = frame(&mut text, 255);

        // The row's own box, measured the way the painter measures it, so the
        // background pixel below is really the card and not the desktop.
        let live = entries(255);
        let box_w = measure_rows(&text, &live, &cfg, &m)[0].box_w;
        let box_x = row_target_x(rect, cfg.position, &m, box_w).round() as i32;
        let y = row_y(rect, &m, 0);
        let item_h = m.item_height.round() as i32;

        // ── A: the background. The card's inner left padding, at the row's
        // vertical middle: no glyph is drawn there (the text starts 6 px in), and
        // the wash this change removed covered exactly this pixel. It must not move.
        let mut background = 0;
        for px in (box_x + 1)..(box_x + 5) {
            for py in (y + 8)..(y + item_h - 8) {
                let (px, py) = (px as u32, py as u32);
                assert!(cold.pixel(px, py) >> 24 > 0, "the card is there to be tinted");
                assert_eq!(
                    cold.pixel(px, py),
                    hot.pixel(px, py),
                    "background pixel ({px}, {py}) moved with the pulse"
                );
                assert_eq!(cold.pixel(px, py), mid.pixel(px, py), "…at any strength");
                background += 1;
            }
        }
        assert!(background >= 16, "the padding strip was really sampled: {background}");

        // ── B: the text. A full pulse leaves strongly red glyphs where the idle row
        // has none at all, and the mid strength lands between the two.
        let strongly_red = |canvas: &Canvas| -> usize {
            let mut n = 0;
            for py in y..(y + item_h) {
                for px in 0..520u32 {
                    let p = canvas.pixel(px, py as u32);
                    let (r, g, b) = ((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
                    if r > 150 && r > g + 80 && r > b + 80 {
                        n += 1;
                    }
                }
            }
            n
        };
        assert_eq!(strongly_red(&cold), 0, "an idle Modern row has no red text");
        assert!(
            strongly_red(&hot) > 200,
            "a working row's text is red: {} pixels",
            strongly_red(&hot)
        );

        // Red-minus-blue summed over the row's band rises with the strength, which
        // is the level reading as a level rather than as a flag.
        let redness = |canvas: &Canvas| -> i64 {
            let mut sum = 0i64;
            for py in y..(y + item_h) {
                for px in 0..520u32 {
                    let p = canvas.pixel(px, py as u32);
                    sum += ((p >> 16) & 0xFF) as i64 - (p & 0xFF) as i64;
                }
            }
            sum
        };
        let (cold_r, mid_r, hot_r) = (redness(&cold), redness(&mid), redness(&hot));
        assert!(hot_r > cold_r, "flash 255 must tint more than 0: {hot_r} vs {cold_r}");
        assert!(
            mid_r > cold_r && mid_r < hot_r,
            "a decaying pulse lands between: {cold_r} < {mid_r} < {hot_r}"
        );
        assert_eq!(redness(&frame(&mut text, 0)), cold_r, "0 is deterministic — no tint at all");
    }

    // ── the two v3 config knobs ─────────────────────────────────────────────

    /// `orderPublished`: the published order survives instead of being sorted by
    /// width. The sort is the reference's wedge (HUD.cpp:340-342), so it stays
    /// the default and only the flag turns it off.
    #[test]
    fn the_published_order_survives_when_the_header_asks_for_it() {
        let text = TextEngine::new(1.0).expect("GDI text engine");
        let entries = vec![
            entry(1, "Reach", "3.1-3.3", false),
            entry(2, "AutoClicker", "12.3 cps", false),
            entry(3, "Velocity", "82%", false),
        ];
        let names = |cfg: &Config| -> Vec<String> {
            measure_rows(&text, &entries, cfg, &Metrics::new(cfg.mode, 1.0))
                .into_iter()
                .map(|row| row.name)
                .collect()
        };

        let wedge = Config::default();
        let sorted = names(&wedge);
        assert_eq!(sorted[0], "AutoClicker", "the widest row leads the wedge");
        assert_ne!(sorted, vec!["Reach", "AutoClicker", "Velocity"], "…so it is not the file's order");

        let kept = names(&Config { ordered: true, ..Config::default() });
        assert_eq!(kept, vec!["Reach", "AutoClicker", "Velocity"], "the published order, untouched");
    }

    /// The opacity knob scales the *background* fills only. Default has no fills
    /// at all, so it is the control: the same frame whatever the opacity is. A
    /// carded mode dims its card — and the row's text is not dimmed with it.
    #[test]
    fn background_opacity_dims_the_fills_and_leaves_the_text_alone() {
        let mut text = TextEngine::new(1.0).expect("GDI text engine");
        let now = Instant::now();
        let entries = vec![entry(1, "Velocity", "82%", false)];

        let mut frame = |mode: Mode, opacity: f32| {
            let cfg = Config { mode, opacity, ..Config::default() };
            let mut anim = Anim::new();
            anim.seed(&entries, now);
            let mut canvas = Canvas::placed(0, 0, 520, 400);
            canvas.clear();
            paint(&mut canvas, &mut text, &entries, &mut anim, now, &cfg);
            canvas
        };

        // No background fill in Default mode: the knob changes nothing, so the
        // labels are demonstrably not scaled by it.
        assert_eq!(
            frame(Mode::Default, 1.0).pixels(),
            frame(Mode::Default, 0.25).pixels(),
            "the text must not dim with the background"
        );

        // Tesseract's chip is opaque (alpha 240), so dimming it is visible in the
        // alpha channel of every pixel the card covers.
        let alpha_sum = |canvas: &Canvas| -> u64 {
            let m = Metrics::new(Mode::Tesseract, 1.0);
            let y = row_y(Rect::new(0, 0, 520, 400), &m, 0);
            let mut sum = 0u64;
            for py in y..y + m.item_height.round() as i32 {
                for px in 0..520u32 {
                    sum += (canvas.pixel(px, py as u32) >> 24) as u64;
                }
            }
            sum
        };
        let full = frame(Mode::Tesseract, 1.0);
        let dim = frame(Mode::Tesseract, 0.25);
        assert!(alpha_sum(&dim) < alpha_sum(&full), "the card's fill dims");
        // The glyphs are still drawn at full strength — the brightest pixel of the
        // frame is the same 255 in both. (Their edges do differ, because a glyph
        // edge composited over a dimmer card is a dimmer pixel: that is the card
        // showing through, not the text fading.)
        let brightest = |canvas: &Canvas| canvas.pixels().iter().map(|p| p >> 24).max();
        assert_eq!(brightest(&dim), Some(255));
        assert_eq!(brightest(&dim), brightest(&full), "the text is not scaled by the knob");

        // 0 % leaves the fills invisible; the text and the accent bar stand.
        let none = frame(Mode::Tesseract, 0.0);
        assert!(alpha_sum(&none) < alpha_sum(&dim));
        assert!(none.pixels().iter().any(|pixel| *pixel != 0), "text only");
    }
}
