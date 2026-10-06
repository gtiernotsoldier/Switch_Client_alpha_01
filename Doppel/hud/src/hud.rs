// hud.rs — the external HUD, re-skinned to the designer's mockup.
//
// The mockup is `E:\swl\doppel.html` (its `<style>` block is also on disk as
// `.dsh-tmp/doppel-design.css`). It draws exactly three client elements over the
// game frame, and this file draws the same three — except that the third, the
// status pill, is **off by default** at the user's request (see
// `SHOW_STATUS_PILL`, which is the one switch that brings it back):
//
//   1. **the client watermark** — the two-D mark, `DOPPEL` with the two P's in
//      the accent, the accent→accent2 hairline and the mono slogan, with no
//      panel behind any of it (the mockup has none).
//   2. **the module list** — one chip per module: a 3x11 spine bar, the name and
//      that module's own value, flushed against the screen edge.
//   3. **the status pill** — the pulsing accent dot, the `ENABLED` label, a
//      hairline separator and the enabled-module count. Not drawn by default;
//      its metrics, its painter and their tests are kept as the spec for it.
//
// Every number here is transcribed from that stylesheet. Three of its features
// cannot be expressed on a canvas that is composited straight onto the game, and
// each is approximated the cheapest way that still reads correctly:
//
//   * `backdrop-filter: blur()` — there is no framebuffer to sample behind the
//     overlay, so the glass is the flat `rgba(13,18,28,.55)` the mockup also
//     paints underneath it.
//   * `box-shadow` glows — no blur passes here: two expanding, fading fills of
//     the same shape at low alpha (`glow`), which is what a 9-12 px blur of a
//     3x11 bar looks like at its centre.
//   * `text-shadow: 0 1px 6px rgba(0,0,0,.7)` — one 1 px dark offset copy of the
//     glyph run (`draw_shadowed`), not a real 6 px blur.
//
// The em-based letter spacing the mockup leans on (`0.4em` on the wordmark,
// `0.32em` on the pill label) is done per character in `TextEngine`, because it
// is 5.4 px and 3.04 px: rounding either to a whole pixel is visible.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use crate::draw::{argb, Canvas};
use crate::state::Entry;
use crate::text::{FontId, TextEngine};

// ── palette (`:root` of the mockup) ─────────────────────────────────────────

/// `--acc` #22D3EE — the accent: bars, the dot, the two P's, values on highlight.
pub const ACCENT: u32 = argb(0x22, 0xD3, 0xEE, 0xFF);
/// `--acc2` #A78BFA — where the spine and the hairline fade to.
pub const ACCENT2: u32 = argb(0xA7, 0x8B, 0xFA, 0xFF);
/// `--txt` #EDF2FA — primary text.
pub const TEXT: u32 = argb(0xED, 0xF2, 0xFA, 0xFF);
/// `--mut` #8B96AB — the slogan and the pill label.
pub const MUTED: u32 = argb(0x8B, 0x96, 0xAB, 0xFF);
/// `--glass: rgba(13,18,28,.55)` — the pill body (and the row chip, at 40 % of it).
pub const GLASS: u32 = argb(0x0D, 0x12, 0x1C, 0x8C);
/// `--line: rgba(148,163,184,.16)` — the pill's hairline border.
pub const LINE: u32 = argb(0x94, 0xA3, 0xB8, 0x29);
/// `#FF5A5A` — the flashing bar and the flash halo.
pub const FLASH_RED: u32 = argb(0xFF, 0x5A, 0x5A, 0xFF);
/// `#FF6B6B` — the flashing name.
pub const FLASH_TEXT: u32 = argb(0xFF, 0x6B, 0x6B, 0xFF);
/// `.al li.flash { background: rgba(255,90,90,.055) }`.
const FLASH_TINT: u32 = argb(0xFF, 0x5A, 0x5A, 0x0E);
/// `.s-sep { background: rgba(255,255,255,.14) }`.
const SEPARATOR: u32 = argb(0xFF, 0xFF, 0xFF, 0x24);
/// The HUD-wide `text-shadow: 0 1px 6px rgba(0,0,0,.7)`, drawn as one offset copy.
const TEXT_SHADOW: u32 = argb(0x00, 0x00, 0x00, 0xB3);

/// The row chip: `--glass` at 40 % of its alpha — the user asked for "a little
/// background on the module list", which the mockup only paints on `:hover`.
/// **Set this to `0.0` for a byte-for-byte faithful copy of the mockup**, which
/// gives the rows no background at all.
const ROW_CHIP_ALPHA: f32 = 0.4;

/// Element 3 of the mockup, the bottom-corner status pill. The designer's mockup
/// includes it; after reviewing the render **the user asked for it not to be on
/// screen**, so it is off by default. **Flipping this to `true` restores the
/// element unchanged** — the layout metrics (`pill_size_from`, `pill_size`), the
/// painter (`paint_pill`) and their tests are all still in the tree. It gates two
/// things and nothing else: whether `paint` draws the pill, and whether a visible
/// HUD counts as permanently animating (the ring). The geometry is measured and
/// printed either way, so the pill's numbers never rot.
const SHOW_STATUS_PILL: bool = false;

// ── strings ─────────────────────────────────────────────────────────────────

pub const MARK: &str = "D";
pub const WORDMARK: &str = "DOPPEL";
/// The two P's of `DOPPEL` carry the accent (`.word b`).
const WORDMARK_ACCENT: [usize; 2] = [2, 3];
const SLOGAN: &str = "statistically you.";
const LABEL: &str = "ENABLED";

// ── geometry, transcribed from the stylesheet ───────────────────────────────
//
// The mockup's frame is much larger than this overlay's stage, but every element
// is inset from a corner, so only the insets and the element-local numbers
// matter here.

/// Inset of all three elements from the corner they occupy (`right: 16px`,
/// `bottom: 16px`, `.wm-col { top: 16px }`).
pub const MARGIN: i32 = 16;

/// `.wm-col { top: 16px }`.
const WATERMARK_TOP: i32 = MARGIN;
/// `.mark { width: 21px; height: 15px }`.
const MARK_W: i32 = 21;
const MARK_H: i32 = 15;
/// `.mark i.d { left: 7px }` — where the mirrored accent D sits inside the box.
const MARK_ECHO_X: i32 = 7;
/// `.mark i { opacity: .62 }` — the ghost D.
const MARK_OPACITY: f32 = 0.62;
/// `.mark i.d { opacity: .92 }` — the mirrored accent D.
const MARK_ECHO_OPACITY: f32 = 0.92;
/// `.wm { gap: 11px }`.
const WM_GAP: i32 = 11;
/// `.word { letter-spacing: .4em }`, and the same trailing space is taken back
/// by `margin-right: -.4em` — which is why the tracked measure drops it.
const WORD_TRACK_EM: f32 = 0.4;
/// `.wm-line { margin-top: 8px }`, and its own height.
const RULE_GAP: i32 = 8;
const RULE_H: i32 = 1;
/// `.wm-line` background: `linear-gradient(90deg, acc, acc2 46%, transparent)`.
const RULE_ACCENT2_STOP: f32 = 0.46;
/// `.slogan { margin-top: 6px; letter-spacing: .06em }`.
const SLOGAN_GAP: i32 = 6;
const SLOGAN_TRACK_EM: f32 = 0.06;

/// Gap from the bottom of the watermark block to the top of the list. The mockup
/// pins the list at `top: 74px`; with the mockup's own block height that is 14 px
/// below it, and expressing it as a gap from the block means the two cannot
/// overlap if a face metric moves by a pixel.
const LIST_TOP_GAP: i32 = 14;

/// `.al li { padding: 3px 8px; border-radius: 5px }` and `.al { gap: 3px }`.
const ROW_PAD_Y: i32 = 3;
const ROW_PAD_X: i32 = 8;
const ROW_RADIUS: f32 = 5.0;
const ROW_GAP: i32 = 3;
/// `.al .bar { width: 3px; height: 11px; border-radius: 2px }`.
const BAR_W: i32 = 3;
const BAR_H: i32 = 11;
const BAR_RADIUS: f32 = 2.0;
/// `.al li { gap: 8px }` — between the bar and the name.
const BAR_GAP: i32 = 8;
/// `.al .t { letter-spacing: .04em }`.
const NAME_TRACK_EM: f32 = 0.04;
/// The one number the mockup does not have: the user asked for the module's value
/// "~10 px after the name", in the mono face.
const ROW_VALUE_GAP: i32 = 10;
/// `.al .bar { box-shadow: 0 0 9px rgba(34,211,238,.35) }`, and the flash's
/// `0 0 12px rgba(255,90,90,.85)`.
const BAR_GLOW: f32 = 0.35;
const BAR_GLOW_FLASH: f32 = 0.85;
/// `.al li.flash .t { text-shadow: 0 0 10px rgba(255,90,90,.7) }`.
const FLASH_TEXT_GLOW: f32 = 0.7;

/// `.status { padding: 8px 13px; border-radius: 9px; gap: 9px }`.
const PILL_PAD_X: i32 = 13;
const PILL_PAD_Y: i32 = 8;
const PILL_RADIUS: f32 = 9.0;
const PILL_GAP: i32 = 9;
/// `.dot { width: 7px; height: 7px }`, and `.dot::after { inset: -1px }` — the
/// pulsing ring is the dot expanded by 1 px on every side.
const DOT_D: i32 = 7;
const RING_INSET: i32 = 1;
/// `.s-sep { width: 1px; height: 11px }`.
const SEP_W: i32 = 1;
const SEP_H: i32 = 11;
/// `.s-name { letter-spacing: .32em }`.
const LABEL_TRACK_EM: f32 = 0.32;
/// `.dot { box-shadow: 0 0 8px rgba(34,211,238,.9) }`.
const DOT_GLOW: f32 = 0.9;
/// `@keyframes pulse`, 2.4 s, `ease-out`: scale .6 → 2.4 while opacity .8 → 0,
/// with the last quarter of the cycle holding the end state.
const PULSE_MS: f32 = 2400.0;
const PULSE_DECAY: f32 = 0.75;
const PULSE_MIN: f32 = 0.6;
const PULSE_MAX: f32 = 2.4;
const PULSE_ALPHA: f32 = 0.8;

// ── motion, transcribed from the stylesheet ─────────────────────────────────

/// `--curve: cubic-bezier(.22,1,.36,1)`.
const DESIGN_CURVE: (f32, f32, f32, f32) = (0.22, 1.0, 0.36, 1.0);
/// `@keyframes rise` over `.wm-col { animation: rise .8s var(--curve) .15s }`.
const WM_DELAY_MS: f32 = 150.0;
const WM_MS: f32 = 800.0;
const WM_RISE: f32 = 10.0;
/// `.al li` — opacity/transform in, `transform: translateX(18px)` out. The
/// mockup staggers 60 ms per row from 140 ms; the spec asks for 45 ms.
const ROW_DELAY_MS: f32 = 140.0;
const ROW_STAGGER_MS: f32 = 45.0;
const ROW_MS: f32 = 550.0;
const ROW_SLIDE: f32 = 18.0;
/// `.status { animation: rise .8s var(--curve) .5s }`.
const PILL_DELAY_MS: f32 = 500.0;
const PILL_MS: f32 = 800.0;
const PILL_RISE: f32 = 10.0;
/// `@keyframes pop { 45% { transform: scale(1.45) } }` over `.3s var(--curve)`.
const POP_MS: f32 = 300.0;
const POP_AT: f32 = 0.45;
const POP_SCALE: f32 = 1.45;
/// Flash strength lost per frame: 255 / 15 ~= 17 frames ~= 280 ms at 60 Hz. This
/// is the decay the old card used and the flash still rides on it.
const FLASH_DECAY: u8 = 15;

/// Which pair of corners the whole HUD occupies. The mockup puts all three
/// elements in the right-hand corners, so that is the default; `Left` is the same
/// HUD mirrored (`--position left`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Position {
    Right,
    Left,
}

/// Per-frame inputs that are not part of the shared state.
pub struct Config {
    pub position: Position,
    pub margin: i32,
    /// Global fade multiplier, 1.0 normally and ramped to 0 when the game dies.
    pub alpha: f32,
}

// ── colour maths ────────────────────────────────────────────────────────────

/// Colour with its alpha channel scaled (Theme.withAlpha).
pub fn with_alpha(colour: u32, mul: f32) -> u32 {
    let a = ((colour >> 24) & 0xFF) as f32 * mul.clamp(0.0, 1.0);
    ((a.round().clamp(0.0, 255.0) as u32) << 24) | (colour & 0x00FF_FFFF)
}

/// Linear interpolation of two ARGB colours (Theme.lerpArgb).
pub fn lerp_argb(a: u32, b: u32, t: f32) -> u32 {
    let t = t.clamp(0.0, 1.0);
    let mix = |x: u32, y: u32| -> u32 {
        (x as f32 + (y as f32 - x as f32) * t).round().clamp(0.0, 255.0) as u32
    };
    (mix((a >> 24) & 0xFF, (b >> 24) & 0xFF) << 24)
        | (mix((a >> 16) & 0xFF, (b >> 16) & 0xFF) << 16)
        | (mix((a >> 8) & 0xFF, (b >> 8) & 0xFF) << 8)
        | mix(a & 0xFF, b & 0xFF)
}

/// Row `index` of `count` on the list spine: cyan at the top, violet at the
/// bottom (the mockup's `barColor`). Also the colour under the watermark's rule.
pub fn spine_color(index: usize, count: usize) -> u32 {
    let t = if count <= 1 { 0.0 } else { index as f32 / (count - 1) as f32 };
    lerp_argb(ACCENT, ACCENT2, t)
}

// ── easing ──────────────────────────────────────────────────────────────────

/// One axis of a cubic Bézier from (0,0) to (1,1) with the two control points.
fn bezier_axis(u: f32, p1: f32, p2: f32) -> f32 {
    let v = 1.0 - u;
    3.0 * v * v * u * p1 + 3.0 * v * u * u * p2 + u * u * u
}

/// The CSS timing function `cubic-bezier(x1, y1, x2, y2)` evaluated at `t`.
///
/// Bisection, not Newton: `ease-out` has x1 = 0, where Newton's derivative is
/// zero and the iteration stalls, and 24 halvings of [0,1] resolve the parameter
/// far below a pixel for the handful of calls a frame makes.
pub fn cubic_bezier(x1: f32, y1: f32, x2: f32, y2: f32, t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    if t <= 0.0 {
        return 0.0;
    }
    if t >= 1.0 {
        return 1.0;
    }
    let (mut lo, mut hi) = (0.0f32, 1.0f32);
    let mut u = t;
    for _ in 0..24 {
        let x = bezier_axis(u, x1, x2);
        if (x - t).abs() < 1e-5 {
            break;
        }
        if x < t {
            lo = u;
        } else {
            hi = u;
        }
        u = 0.5 * (lo + hi);
    }
    bezier_axis(u, y1, y2)
}

/// The mockup's own curve, `cubic-bezier(.22,1,.36,1)`.
pub fn design_curve(t: f32) -> f32 {
    cubic_bezier(DESIGN_CURVE.0, DESIGN_CURVE.1, DESIGN_CURVE.2, DESIGN_CURVE.3, t)
}

/// The `@keyframes pulse` ring: `(scale, alpha)` at `t_ms` into the 2.4 s cycle.
pub fn pulse_ring(t_ms: f32) -> (f32, f32) {
    let phase = t_ms.rem_euclid(PULSE_MS) / (PULSE_MS * PULSE_DECAY);
    let e = cubic_bezier(0.0, 0.0, 0.58, 1.0, phase.clamp(0.0, 1.0));
    (PULSE_MIN + (PULSE_MAX - PULSE_MIN) * e, PULSE_ALPHA * (1.0 - e))
}

/// The count's pop, at `p` of its 300 ms: 1 → 1.45 at 45 % → 1.
pub fn pop_scale(p: f32) -> f32 {
    let p = p.clamp(0.0, 1.0);
    let rise = POP_SCALE - 1.0;
    if p < POP_AT {
        1.0 + rise * design_curve(p / POP_AT)
    } else {
        1.0 + rise * (1.0 - design_curve((p - POP_AT) / (1.0 - POP_AT)))
    }
}

// ── pure geometry ───────────────────────────────────────────────────────────

/// Width of the watermark block: the 21 px mark box, the 11 px gap and the
/// tracked wordmark (`.wm { gap: 11px }`).
pub fn watermark_width(mark_w: i32, word_w: i32) -> i32 {
    mark_w + WM_GAP + word_w
}

/// Height of the watermark block: the mark's 15 px box against the wordmark's
/// line box, whichever is taller (`.wm` is `align-items: center`).
pub fn watermark_height(mark_box_h: i32, word_cell_h: i32) -> i32 {
    mark_box_h.max(word_cell_h)
}

/// The hairline's y: `margin-top: 8px` below the block.
pub fn watermark_rule_y(block_top: i32, block_h: i32) -> i32 {
    block_top + block_h + RULE_GAP
}

/// Row height: the name's cell (never smaller than the 11 px bar) plus the row's
/// 3 px vertical padding on both sides.
pub fn row_height_from(name_cell_h: i32) -> i32 {
    name_cell_h.max(BAR_H) + ROW_PAD_Y * 2
}

/// The status pill's size from its pieces: `[dot][gap][label][gap][sep][gap]
/// [count]` with 13 px of padding either side, and the tallest piece plus 8 px
/// above and below.
pub fn pill_size_from(label_w: i32, count_w: i32, label_h: i32, count_h: i32) -> (i32, i32) {
    let inner_h = DOT_D.max(SEP_H).max(label_h).max(count_h);
    let w = PILL_PAD_X * 2 + DOT_D + PILL_GAP * 3 + label_w + SEP_W + count_w;
    (w, inner_h + PILL_PAD_Y * 2)
}

/// Where the three pieces of one row sit. `edge_x` is the screen edge the row is
/// flushed against — the right edge for the mockup's layout, the left one when
/// the whole HUD is mirrored.
#[derive(Debug, PartialEq, Eq)]
pub struct RowGeometry {
    /// Left edge and width of the row's chip (its padding included).
    pub row_x: i32,
    pub row_w: i32,
    /// Left edge of the 3x11 spine bar.
    pub bar_x: i32,
    /// Left edge of the name. The value's left edge is `ROW_VALUE_GAP` past the
    /// name's end, on the side away from the bar.
    pub name_x: i32,
    pub value_x: i32,
}

/// Lay one row out. `bars_left` means the bar is on the screen-edge side, which
/// is the mockup's arrangement: `[bar][8px][name]`, plus `[10px][value]` when the
/// row has one. The mirrored list keeps the same gaps with everything flipped.
pub fn row_geometry(edge_x: i32, name_w: i32, value_w: i32, bars_left: bool) -> RowGeometry {
    let inner = BAR_W + BAR_GAP + name_w + if value_w > 0 { ROW_VALUE_GAP + value_w } else { 0 };
    let row_w = inner + ROW_PAD_X * 2;
    let row_x = if bars_left { edge_x } else { edge_x - row_w };
    let bar_x = if bars_left { row_x + ROW_PAD_X } else { row_x + row_w - ROW_PAD_X - BAR_W };
    let name_x = if bars_left { bar_x + BAR_W + BAR_GAP } else { bar_x - BAR_GAP - name_w };
    let value_x =
        if bars_left { name_x + name_w + ROW_VALUE_GAP } else { name_x - ROW_VALUE_GAP - value_w };
    RowGeometry { row_x, row_w, bar_x, name_x, value_x }
}

// ── measured sizes (the same numbers, through the text engine) ──────────────

/// Width of `DOPPEL` with `.word { letter-spacing: .4em }`.
pub fn wordmark_width(text: &TextEngine) -> i32 {
    text.measure_tracked(FontId::UiBold, WORDMARK, WORD_TRACK_EM * text.em_px(FontId::UiBold))
}

/// The watermark block's size for the current face metrics.
pub fn watermark_size(text: &TextEngine) -> (i32, i32) {
    (
        watermark_width(MARK_W, wordmark_width(text)),
        watermark_height(MARK_H, text.height(FontId::UiBold)),
    )
}

/// Row height for the current face metrics.
pub fn row_height(text: &TextEngine) -> i32 {
    row_height_from(text.height(FontId::Ui))
}

/// The status pill's size for the current face metrics and a given count.
pub fn pill_size(text: &TextEngine, count: usize) -> (i32, i32) {
    let label_w = text.measure_tracked(
        FontId::UiTiny,
        LABEL,
        LABEL_TRACK_EM * text.em_px(FontId::UiTiny),
    );
    let count_w = text.measure(FontId::MonoBold, &count.to_string());
    pill_size_from(label_w, count_w, text.height(FontId::UiTiny), text.height(FontId::MonoBold))
}

// ── animation state ─────────────────────────────────────────────────────────

/// What this side animates on top of the published state: when a row first
/// appeared, how hot a module's flash still is, and the two one-shot entrances
/// that are measured from process start.
pub struct Anim {
    start: Instant,
    appear: HashMap<String, Instant>,
    flash: HashMap<u32, u8>,
    /// The last count the pill showed, and when it changed (the pop).
    count: Option<usize>,
    pop_at: Option<Instant>,
    /// Phase offset for the endless pulse, so `--dump` can freeze it mid-ring.
    pulse_offset_ms: f32,
}

impl Anim {
    pub fn new() -> Anim {
        Anim {
            start: Instant::now(),
            appear: HashMap::new(),
            flash: HashMap::new(),
            count: None,
            pop_at: None,
            pulse_offset_ms: 0.0,
        }
    }

    /// Drop state for modules that are no longer published, then decay the
    /// flash strengths: a set flag means 255, a clear one loses 15 per frame.
    pub fn update(&mut self, entries: &[Entry]) {
        self.appear.retain(|name, _| entries.iter().any(|e| &e.name == name));
        self.flash.retain(|id, _| entries.iter().any(|e| e.id == *id));

        for e in entries {
            match self.flash.get_mut(&e.id) {
                Some(strength) => {
                    if e.flash {
                        *strength = 255;
                    } else {
                        *strength = strength.saturating_sub(FLASH_DECAY);
                    }
                }
                None => {
                    if e.flash {
                        self.flash.insert(e.id, 255);
                    }
                }
            }
        }
    }

    /// 0 -> 1 progress of the watermark's `rise`, eased with the design curve.
    pub fn watermark(&self, now: Instant) -> f32 {
        entrance(self.start, now, WM_DELAY_MS, WM_MS)
    }

    /// 0 -> 1 progress of the status pill's delayed `rise`.
    pub fn pill(&self, now: Instant) -> f32 {
        entrance(self.start, now, PILL_DELAY_MS, PILL_MS)
    }

    /// 0 -> 1 entrance progress for a row, staggered by its index in the list. A
    /// row scheduled for a later slot returns 0 until its turn arrives.
    pub fn ease(&mut self, name: &str, index: usize, now: Instant) -> f32 {
        let delay = ROW_DELAY_MS + index as f32 * ROW_STAGGER_MS;
        let ts = *self.appear.entry(name.to_string()).or_insert_with(|| {
            now + Duration::from_millis(delay as u64)
        });
        entrance(ts, now, 0.0, ROW_MS)
    }

    /// Current flash strength for a module, 0..=255.
    pub fn strength(&self, id: u32) -> u8 {
        *self.flash.get(&id).unwrap_or(&0)
    }

    /// The pulsing ring's `(scale, alpha)` right now. It never stops, so a frame
    /// with the pill on screen is never static.
    pub fn pulse(&self, now: Instant) -> (f32, f32) {
        pulse_ring(now.saturating_duration_since(self.start).as_secs_f32() * 1000.0
            + self.pulse_offset_ms)
    }

    /// The count's pop scale for this frame, plus whether it is still running.
    /// The first count seen is not a change: the pill must not pop on start-up.
    pub fn pop(&mut self, count: usize, now: Instant) -> (f32, bool) {
        if let Some(previous) = self.count {
            if previous != count {
                self.pop_at = Some(now);
            }
        }
        self.count = Some(count);

        match self.pop_at {
            Some(at) => {
                let p = now.saturating_duration_since(at).as_secs_f32() * 1000.0 / POP_MS;
                if p >= 1.0 {
                    self.pop_at = None;
                    (1.0, false)
                } else {
                    (pop_scale(p), true)
                }
            }
            None => (1.0, false),
        }
    }

    /// Dump/test hook: pretend every row entered two seconds ago, put the pulse
    /// mid-ring and seed the given flash strengths, so a still frame is
    /// representative of a live HUD.
    pub fn seed(&mut self, entries: &[Entry], now: Instant, flashes: &[(u32, u8)]) {
        const AGE_MS: f32 = 2000.0;
        // Where in the 2.4 s ring the dump freezes: expanded past the dot, its
        // opacity still a quarter rather than faded out.
        const RING_AT_MS: f32 = 900.0;

        let past = now.checked_sub(Duration::from_millis(AGE_MS as u64)).unwrap_or(now);
        self.start = past;
        // `pulse` adds the elapsed time, so the offset has to take back the age
        // the block has already been running for.
        self.pulse_offset_ms = (RING_AT_MS - AGE_MS).rem_euclid(PULSE_MS);
        for e in entries {
            if !e.name.is_empty() {
                self.appear.insert(e.name.clone(), past);
            }
        }
        for (id, strength) in flashes {
            self.flash.insert(*id, *strength);
        }
    }
}

/// 0 -> 1 progress of an entrance that starts `delay_ms` after `start`, runs for
/// `dur_ms` and is eased with the mockup's curve.
fn entrance(start: Instant, now: Instant, delay_ms: f32, dur_ms: f32) -> f32 {
    let elapsed = now.saturating_duration_since(start).as_secs_f32() * 1000.0 - delay_ms;
    if elapsed <= 0.0 {
        return 0.0;
    }
    design_curve((elapsed / dur_ms).clamp(0.0, 1.0))
}

// ── layout ──────────────────────────────────────────────────────────────────

/// One measured row, positioned at rest. `paint` adds the entrance slide to the
/// x values; nothing else about a row depends on time.
struct RowLayout {
    /// Index into the entry slice this layout was measured from.
    index: usize,
    name_w: i32,
    value_w: i32,
    row_x: i32,
    row_w: i32,
    bar_x: i32,
    name_x: i32,
    value_x: i32,
}

/// The status pill's pieces, all at rest.
struct PillLayout {
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    dot_x: i32,
    dot_y: i32,
    label_x: i32,
    label_y: i32,
    label_w: i32,
    label_track: f32,
    sep_x: i32,
    sep_y: i32,
    count_x: i32,
    count_y: i32,
    count_w: i32,
}

/// A still HUD: the watermark block, the rows and the pill. Everything `paint`
/// needs except the current time.
struct Layout {
    wm_x: i32,
    wm_w: i32,
    wm_h: i32,
    mark_x: i32,
    mark_y: i32,
    word_x: i32,
    word_y: i32,
    word_w: i32,
    word_track: f32,
    rule_y: i32,
    slogan_x: i32,
    slogan_y: i32,
    slogan_w: i32,
    slogan_track: f32,
    list_y: i32,
    row_h: i32,
    row_step: i32,
    bars_left: bool,
    content_w: i32,
    rows: Vec<RowLayout>,
    pill: PillLayout,
}

/// Measure the whole HUD for the current text metrics. Shared by `paint` and
/// `describe`, so the picture and the printed numbers cannot disagree.
fn measure_layout(
    text: &TextEngine,
    entries: &[Entry],
    cfg: &Config,
    canvas_w: u32,
    canvas_h: u32,
) -> Layout {
    let margin = cfg.margin;
    let bars_left = cfg.position == Position::Left;

    // ── watermark ──
    let word_track = WORD_TRACK_EM * text.em_px(FontId::UiBold);
    let word_w = wordmark_width(text);
    let (wm_w, wm_h) = watermark_size(text);
    let wm_x = if bars_left { margin } else { canvas_w as i32 - margin - wm_w };
    let mark_x = wm_x;
    let mark_y = WATERMARK_TOP + (wm_h - text.height(FontId::Mark)) / 2;
    let word_x = wm_x + MARK_W + WM_GAP;
    let word_y = WATERMARK_TOP + (wm_h - text.height(FontId::UiBold)) / 2;
    let rule_y = watermark_rule_y(WATERMARK_TOP, wm_h);
    let slogan_track = SLOGAN_TRACK_EM * text.em_px(FontId::Slogan);
    let slogan_w = text.measure_tracked(FontId::Slogan, SLOGAN, slogan_track);
    let slogan_y = rule_y + RULE_H + SLOGAN_GAP;
    // `.wm-col { align-items: flex-end }`: the slogan hangs off the same edge the
    // whole block does, so it ends where the wordmark ends.
    let slogan_x = wm_x + wm_w - slogan_w;
    let block_h = slogan_y + text.height(FontId::Slogan) - WATERMARK_TOP;
    let list_y = WATERMARK_TOP + block_h + LIST_TOP_GAP;

    // ── rows: everything with a name, sorted width-descending (the mockup's
    // `measure()` staircase). ──
    let name_track = NAME_TRACK_EM * text.em_px(FontId::Ui);
    let mut measured: Vec<(usize, i32, i32)> = Vec::new();
    for (index, entry) in entries.iter().enumerate() {
        if entry.name.is_empty() {
            continue;
        }
        let name_w = text.measure_tracked(FontId::Ui, &entry.name, name_track);
        let value_w = if entry.value.is_empty() { 0 } else { text.measure(FontId::Mono, &entry.value) };
        measured.push((index, name_w, value_w));
    }
    measured.sort_by(|a, b| b.1.cmp(&a.1));

    let edge_x = if bars_left { margin } else { canvas_w as i32 - margin };
    let rows: Vec<RowLayout> = measured
        .iter()
        .map(|&(index, name_w, value_w)| {
            let g = row_geometry(edge_x, name_w, value_w, bars_left);
            RowLayout {
                index,
                name_w,
                value_w,
                row_x: g.row_x,
                row_w: g.row_w,
                bar_x: g.bar_x,
                name_x: g.name_x,
                value_x: g.value_x,
            }
        })
        .collect();
    let content_w = rows.iter().map(|r| r.row_w).max().unwrap_or(0);

    // ── status pill ──
    let label_track = LABEL_TRACK_EM * text.em_px(FontId::UiTiny);
    let label_w = text.measure_tracked(FontId::UiTiny, LABEL, label_track);
    let count_w = text.measure(FontId::MonoBold, &rows.len().to_string());
    let (pill_w, pill_h) = pill_size_from(
        label_w,
        count_w,
        text.height(FontId::UiTiny),
        text.height(FontId::MonoBold),
    );
    let pill_x = if bars_left { margin } else { canvas_w as i32 - margin - pill_w };
    let pill_y = canvas_h as i32 - margin - pill_h;
    let dot_x = pill_x + PILL_PAD_X;
    let label_x = dot_x + DOT_D + PILL_GAP;
    let sep_x = label_x + label_w + PILL_GAP;
    let count_x = sep_x + SEP_W + PILL_GAP;
    let pill = PillLayout {
        x: pill_x,
        y: pill_y,
        w: pill_w,
        h: pill_h,
        dot_x,
        dot_y: pill_y + (pill_h - DOT_D) / 2,
        label_x,
        label_y: pill_y + (pill_h - text.height(FontId::UiTiny)) / 2,
        label_w,
        label_track,
        sep_x,
        sep_y: pill_y + (pill_h - SEP_H) / 2,
        count_x,
        count_y: pill_y + (pill_h - text.height(FontId::MonoBold)) / 2,
        count_w,
    };

    Layout {
        wm_x,
        wm_w,
        wm_h,
        mark_x,
        mark_y,
        word_x,
        word_y,
        word_w,
        word_track,
        rule_y,
        slogan_x,
        slogan_y,
        slogan_w,
        slogan_track,
        list_y,
        row_h: row_height(text),
        row_step: row_height(text) + ROW_GAP,
        bars_left,
        content_w,
        rows,
        pill,
    }
}

// ── painting ────────────────────────────────────────────────────────────────

/// Compose one frame of the HUD. Returns true while something is still moving,
/// which the caller uses to decide whether the next frame needs to be pushed to
/// the compositor. With the status pill on screen its ring pulses forever and a
/// frame therefore always reports true; with the pill hidden (`SHOW_STATUS_PILL`,
/// the default) nothing is endless, so this reports true only while an entrance
/// or a flash is still moving and the caller repaints on a content-signature
/// change alone.
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

    let layout = measure_layout(text, entries, cfg, canvas.width(), canvas.height());
    let count = layout.rows.len();

    let rows_moving = paint_rows(canvas, text, entries, anim, now, &layout, count, global);
    let mark_moving = paint_watermark(canvas, text, anim, now, &layout, global);
    // The pill is the mockup's third element and the user asked for it off, so it
    // is only painted when the switch is on. The call stays here either way, which
    // is what keeps `paint_pill`, its metrics and their tests live code.
    let pill_moving = if SHOW_STATUS_PILL {
        paint_pill(canvas, text, anim, now, &layout, count, global)
    } else {
        false
    };
    // Repaint policy. `global > 0.0` is the "always repaint a visible HUD" term,
    // and the only thing that ever needed it was the pill's ring: it pulses for as
    // long as the pill is on screen, so a visible HUD is never static. With the
    // pill hidden (the default) that term is gone: this reports true only while a
    // row/watermark entrance or a flash is still moving, and the caller then
    // pushes a frame only when the state changed — the fade-out is quantised into
    // the caller's signature, so the ramp still repaints.
    rows_moving || mark_moving || pill_moving || (SHOW_STATUS_PILL && global > 0.0)
}

/// The module list: one chip, one bar and one name (plus its value) per row.
fn paint_rows(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    entries: &[Entry],
    anim: &mut Anim,
    now: Instant,
    layout: &Layout,
    count: usize,
    global: f32,
) -> bool {
    let name_cell = text.height(FontId::Ui);
    let value_cell = text.height(FontId::Mono);
    let name_track = NAME_TRACK_EM * text.em_px(FontId::Ui);
    let mut animating = false;
    let mut y = layout.list_y;

    for (i, row) in layout.rows.iter().enumerate() {
        let entry = &entries[row.index];
        let ease = anim.ease(&entry.name, i, now);
        if ease <= 0.0 {
            // Not this row's turn yet: the slot is reserved, nothing is drawn.
            y += layout.row_step;
            animating = true;
            continue;
        }
        let alpha = ease * global;
        // The row slides in from the edge it is flushed against: outward is +x on
        // the right, -x on the left.
        let slide = ((1.0 - ease) * ROW_SLIDE).round() as i32;
        let shift = if layout.bars_left { -slide } else { slide };
        let row_x = row.row_x + shift;
        let bar_x = row.bar_x + shift;
        let name_x = row.name_x + shift;
        let value_x = row.value_x + shift;
        let strength = anim.strength(entry.id) as f32 / 255.0;

        // The chip the rows sit on: `--glass` at ROW_CHIP_ALPHA, then the flash
        // tint on top of it.
        if ROW_CHIP_ALPHA > 0.0 {
            canvas.fill_rounded_rect(
                row_x,
                y,
                row.row_w as u32,
                layout.row_h as u32,
                ROW_RADIUS,
                with_alpha(GLASS, ROW_CHIP_ALPHA * alpha),
            );
        }
        if strength > 0.0 {
            canvas.fill_rounded_rect(
                row_x,
                y,
                row.row_w as u32,
                layout.row_h as u32,
                ROW_RADIUS,
                with_alpha(FLASH_TINT, strength * alpha),
            );
        }

        // The spine bar, with the mockup's box-shadow glow: cyan at 35 %, red at
        // 85 % while the module is working.
        let bar_y = y + (layout.row_h - BAR_H) / 2;
        let bar_colour = lerp_argb(spine_color(i, count), FLASH_RED, strength);
        let (glow_colour, glow_alpha) = if strength > 0.0 {
            (FLASH_RED, BAR_GLOW + (BAR_GLOW_FLASH - BAR_GLOW) * strength)
        } else {
            (ACCENT, BAR_GLOW)
        };
        glow(canvas, bar_x, bar_y, BAR_W, BAR_H, BAR_RADIUS, glow_colour, glow_alpha, alpha);
        canvas.fill_rounded_rect(
            bar_x,
            bar_y,
            BAR_W as u32,
            BAR_H as u32,
            BAR_RADIUS,
            with_alpha(bar_colour, alpha),
        );

        // The name, reddened and haloed while the module works.
        let text_y = y + (layout.row_h - name_cell) / 2;
        let name_colour = lerp_argb(TEXT, FLASH_TEXT, strength);
        if strength > 0.0 {
            for (ox, oy) in [(-1, 0), (1, 0), (0, -1), (0, 1)] {
                text.draw_tracked(
                    canvas,
                    FontId::Ui,
                    &entry.name,
                    name_x + ox,
                    text_y + oy,
                    name_track,
                    |_, _| with_alpha(FLASH_RED, FLASH_TEXT_GLOW * 0.3 * strength * alpha),
                );
            }
        }
        draw_shadowed_tracked(
            text,
            canvas,
            FontId::Ui,
            &entry.name,
            name_x,
            text_y,
            name_track,
            alpha,
            |_, _| name_colour,
        );

        if row.value_w > 0 {
            let colour = if entry.highlight { ACCENT } else { MUTED };
            draw_shadowed(
                text,
                canvas,
                FontId::Mono,
                &entry.value,
                value_x,
                y + (layout.row_h - value_cell) / 2,
                alpha,
                colour,
            );
        }

        if ease < 1.0 || strength > 0.0 {
            animating = true;
        }
        y += layout.row_step;
    }

    animating
}

/// The client watermark: the mark, the wordmark, the accent rule and the slogan,
/// with no panel behind any of it — only the text shadow the mockup relies on.
fn paint_watermark(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    anim: &Anim,
    now: Instant,
    layout: &Layout,
    global: f32,
) -> bool {
    let ease = anim.watermark(now);
    if ease <= 0.0 {
        return true;
    }
    let alpha = ease * global;
    // `@keyframes rise`: 10 px up while fading in.
    let dy = ((1.0 - ease) * WM_RISE).round() as i32;

    // The hairline first: accent -> accent2 at 46 % -> transparent, 1 px, the
    // full width of the block, 8 px under the wordmark's line.
    draw_rule(canvas, layout.wm_x, layout.rule_y + dy, layout.wm_w, alpha);

    // The two D's of `.mark`: a 62 % ghost at the box origin and the mirrored
    // accent D seven pixels to its right, painted over it.
    draw_shadowed(
        text,
        canvas,
        FontId::Mark,
        MARK,
        layout.mark_x,
        layout.mark_y + dy,
        alpha * MARK_OPACITY,
        TEXT,
    );
    let echo_x = layout.mark_x + MARK_ECHO_X;
    text.draw(
        canvas,
        FontId::Mark,
        MARK,
        echo_x,
        layout.mark_y + dy + 1,
        with_alpha(TEXT_SHADOW, MARK_ECHO_OPACITY * alpha),
    );
    text.draw_mirrored(
        canvas,
        FontId::Mark,
        MARK,
        echo_x,
        layout.mark_y + dy,
        with_alpha(ACCENT, MARK_ECHO_OPACITY * alpha),
    );

    // `DOPPEL` at 13.5 px, 0.4 em of tracking, the two P's in the accent.
    draw_shadowed_tracked(
        text,
        canvas,
        FontId::UiBold,
        WORDMARK,
        layout.word_x,
        layout.word_y + dy,
        layout.word_track,
        alpha,
        |i, _| if WORDMARK_ACCENT.contains(&i) { ACCENT } else { TEXT },
    );

    // `statistically you.` in the mono face, right-aligned to the same edge.
    draw_shadowed_tracked(
        text,
        canvas,
        FontId::Slogan,
        SLOGAN,
        layout.slogan_x,
        layout.slogan_y + dy,
        layout.slogan_track,
        alpha,
        |_, _| MUTED,
    );

    ease < 1.0
}

/// The status pill: glass, hairline, the pulsing dot, `ENABLED`, the separator
/// and the count.
fn paint_pill(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    anim: &mut Anim,
    now: Instant,
    layout: &Layout,
    count: usize,
    global: f32,
) -> bool {
    let ease = anim.pill(now);
    if ease <= 0.0 {
        return true;
    }
    let alpha = ease * global;
    let dy = ((1.0 - ease) * PILL_RISE).round() as i32;
    let pill = &layout.pill;
    let (x, y, w, h) = (pill.x, pill.y + dy, pill.w, pill.h);

    canvas.fill_rounded_rect(x, y, w as u32, h as u32, PILL_RADIUS, with_alpha(GLASS, alpha));
    canvas.stroke_rounded_rect(
        x,
        y,
        w as u32,
        h as u32,
        PILL_RADIUS,
        1.0,
        with_alpha(LINE, alpha),
    );

    // The dot: a 7 px accent circle with its 8 px glow, wrapped by the ring that
    // scales 0.6 -> 2.4 while fading 0.8 -> 0.
    let dot_x = pill.dot_x;
    let dot_y = pill.dot_y + dy;
    let (scale, ring_alpha) = anim.pulse(now);
    let ring_r = (DOT_D + RING_INSET * 2) as f32 / 2.0 * scale;
    let ring_side = (ring_r * 2.0).round().max(1.0) as u32;
    let ring_x = (dot_x as f32 + DOT_D as f32 / 2.0 - ring_r).round() as i32;
    let ring_y = (dot_y as f32 + DOT_D as f32 / 2.0 - ring_r).round() as i32;
    if ring_alpha > 0.0 && ring_r > 0.5 {
        canvas.stroke_rounded_rect(
            ring_x,
            ring_y,
            ring_side,
            ring_side,
            ring_r,
            scale.max(0.6),
            with_alpha(ACCENT, ring_alpha * alpha),
        );
    }

    glow(canvas, dot_x, dot_y, DOT_D, DOT_D, DOT_D as f32 / 2.0, ACCENT, DOT_GLOW, alpha);
    canvas.fill_rounded_rect(
        dot_x,
        dot_y,
        DOT_D as u32,
        DOT_D as u32,
        DOT_D as f32 / 2.0,
        with_alpha(ACCENT, alpha),
    );

    // `ENABLED` at 9.5 px with 0.32 em of tracking, in the muted colour.
    draw_shadowed_tracked(
        text,
        canvas,
        FontId::UiTiny,
        LABEL,
        pill.label_x,
        pill.label_y + dy,
        pill.label_track,
        alpha,
        |_, _| MUTED,
    );

    // The hairline separator.
    canvas.fill_rect(
        pill.sep_x,
        pill.sep_y + dy,
        SEP_W as u32,
        SEP_H as u32,
        with_alpha(SEPARATOR, alpha),
    );

    // The count, with its short pop when it changes.
    let (pop, popping) = anim.pop(count, now);
    draw_shadowed_scaled(
        text,
        canvas,
        FontId::MonoBold,
        &count.to_string(),
        pill.count_x,
        pill.count_y + dy,
        pop,
        alpha,
        ACCENT,
    );

    ease < 1.0 || popping
}

/// A cheap box-shadow: two expanding, fading fills of the same shape. The shape
/// itself is painted afterwards and covers the middle, so only the halo shows.
fn glow(
    canvas: &mut Canvas,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    radius: f32,
    colour: u32,
    peak: f32,
    alpha: f32,
) {
    if alpha <= 0.0 {
        return;
    }
    canvas.fill_rounded_rect(
        x - 2,
        y - 2,
        (w + 4) as u32,
        (h + 4) as u32,
        radius + 2.0,
        with_alpha(colour, peak * 0.16 * alpha),
    );
    canvas.fill_rounded_rect(
        x - 1,
        y - 1,
        (w + 2) as u32,
        (h + 2) as u32,
        radius + 1.0,
        with_alpha(colour, peak * 0.30 * alpha),
    );
}

/// The watermark's 1 px rule: `linear-gradient(90deg, var(--acc), var(--acc2)
/// 46%, rgba(167,139,250,0))`, one column at a time.
fn draw_rule(canvas: &mut Canvas, x: i32, y: i32, w: i32, alpha: f32) {
    if w <= 0 || alpha <= 0.0 {
        return;
    }
    for col in 0..w {
        let t = (col as f32 + 0.5) / w as f32;
        let (colour, cover) = if t <= RULE_ACCENT2_STOP {
            (lerp_argb(ACCENT, ACCENT2, t / RULE_ACCENT2_STOP), 1.0)
        } else {
            (ACCENT2, 1.0 - (t - RULE_ACCENT2_STOP) / (1.0 - RULE_ACCENT2_STOP))
        };
        canvas.blend_pixel(x + col, y, with_alpha(colour, cover * alpha), 1.0);
    }
}

// ── text with the mockup's global text-shadow ───────────────────────────────

/// Draw a run with the HUD-wide `text-shadow: 0 1px 6px rgba(0,0,0,.7)`,
/// approximated by one 1 px dark offset copy underneath it.
fn draw_shadowed(
    text: &mut TextEngine,
    canvas: &mut Canvas,
    id: FontId,
    s: &str,
    x: i32,
    y: i32,
    alpha: f32,
    colour: u32,
) {
    text.draw(canvas, id, s, x, y + 1, with_alpha(TEXT_SHADOW, alpha));
    text.draw(canvas, id, s, x, y, with_alpha(colour, alpha));
}

/// `draw_shadowed` for a tracked run, with a per-character colour.
fn draw_shadowed_tracked<F>(
    text: &mut TextEngine,
    canvas: &mut Canvas,
    id: FontId,
    s: &str,
    x: i32,
    y: i32,
    track: f32,
    alpha: f32,
    colour: F,
) where
    F: Fn(usize, char) -> u32,
{
    text.draw_tracked(canvas, id, s, x, y + 1, track, |_, _| with_alpha(TEXT_SHADOW, alpha));
    text.draw_tracked(canvas, id, s, x, y, track, |i, c| with_alpha(colour(i, c), alpha));
}

/// `draw_shadowed` for the count, which may be mid-pop.
fn draw_shadowed_scaled(
    text: &mut TextEngine,
    canvas: &mut Canvas,
    id: FontId,
    s: &str,
    x: i32,
    y: i32,
    scale: f32,
    alpha: f32,
    colour: u32,
) {
    text.draw_scaled(canvas, id, s, x, y + 1, scale, with_alpha(TEXT_SHADOW, alpha));
    text.draw_scaled(canvas, id, s, x, y, scale, with_alpha(colour, alpha));
}

// ── the numbers behind the picture ──────────────────────────────────────────

/// The geometry behind the picture, for `--dump`: the watermark block, the pill
/// and, per row, the chip, the bar and the name -> value distance. Measured
/// through the same `measure_layout` the painter uses, so a review dump and the
/// printed numbers cannot drift apart.
pub fn describe(
    text: &TextEngine,
    entries: &[Entry],
    position: Position,
    canvas_w: u32,
    canvas_h: u32,
) -> String {
    let cfg = Config { position, margin: MARGIN, alpha: 1.0 };
    let layout = measure_layout(text, entries, &cfg, canvas_w, canvas_h);
    let mut out = String::new();

    out.push_str(&format!(
        "watermark: block {}x{} at ({}, {}), {}\n",
        layout.wm_w,
        layout.wm_h,
        layout.wm_x,
        WATERMARK_TOP,
        if layout.bars_left { "left-anchored" } else { "right-anchored" },
    ));
    out.push_str(&format!(
        "  mark {}x{} at ({}, {}) | ghost \"{MARK}\" #EDF2FA @{:.0}%, mirrored \"{MARK}\" #22D3EE \
         @{:.0}% at x={} (left +{MARK_ECHO_X})\n",
        MARK_W,
        MARK_H,
        layout.mark_x,
        layout.mark_y,
        MARK_OPACITY * 100.0,
        MARK_ECHO_OPACITY * 100.0,
        layout.mark_x + MARK_ECHO_X,
    ));
    out.push_str(&format!(
        "  word \"{WORDMARK}\" {}px wide ({:.0}px per char at {:.1}px) at ({}, {}), P's in #22D3EE\n",
        layout.word_w,
        layout.word_track,
        text.em_px(FontId::UiBold),
        layout.word_x,
        layout.word_y,
    ));
    out.push_str(&format!(
        "  rule 1x{} at ({}, {}): #22D3EE -> #A78BFA @{:.0}% -> transparent\n",
        layout.wm_w, layout.wm_x, layout.rule_y, RULE_ACCENT2_STOP * 100.0,
    ));
    out.push_str(&format!(
        "  slogan \"{SLOGAN}\" {}px wide ({:.2}px per char at {:.1}px) at ({}, {}), #8B96AB\n",
        layout.slogan_w,
        layout.slogan_track,
        text.em_px(FontId::Slogan),
        layout.slogan_x,
        layout.slogan_y,
    ));

    out.push_str(&format!(
        "list: {} rows of {}px (name cell {} + 2x{ROW_PAD_Y}), step {}px (gap {ROW_GAP}), first y={}, \
         widest row {}px, edge x={}\n",
        layout.rows.len(),
        layout.row_h,
        text.height(FontId::Ui),
        layout.row_step,
        layout.list_y,
        layout.content_w,
        MARGIN,
    ));
    for (slot, row) in layout.rows.iter().enumerate() {
        let entry = &entries[row.index];
        out.push_str(&format!(
            "  row {slot} {}: chip {ROW_PAD_X}|{}|{ROW_PAD_X} = {}px at x={} (y {}..{}), bar {BAR_W}x{BAR_H} \
             r{BAR_RADIUS:.0} at x={} (glow {BAR_GLOW}), name {}px at x={} (track {NAME_TRACK_EM}em = {:.1}px)\n",
            entry.name,
            row.row_w - ROW_PAD_X * 2,
            row.row_w,
            row.row_x,
            layout.list_y + slot as i32 * layout.row_step,
            layout.list_y + slot as i32 * layout.row_step + layout.row_h,
            row.bar_x,
            row.name_w,
            row.name_x,
            NAME_TRACK_EM * text.em_px(FontId::Ui),
        ));
        if row.value_w > 0 {
            let gap = if layout.bars_left {
                row.value_x - (row.name_x + row.name_w)
            } else {
                row.name_x - (row.value_x + row.value_w)
            };
            out.push_str(&format!(
                "      value \"{}\" {}px at x={} (gap {gap}px after the name, after = toward x {})\n",
                entry.value,
                row.value_w,
                row.value_x,
                if layout.bars_left { "right" } else { "left" },
            ));
        }
    }
    if layout.rows.is_empty() {
        out.push_str("  (no named rows: nothing to draw)\n");
    }

    // The pill is off by default — the mockup's third element, hidden at the
    // user's request after the review — so this describes the geometry that is
    // drawn. The pill's own numbers are still measured (the helpers and
    // `paint_pill` are kept for whenever it is switched back on) and its rect is
    // still reported, but the line says plainly that nothing is painted there and
    // that no other element is inset by it.
    let pill = &layout.pill;
    if SHOW_STATUS_PILL {
        out.push_str(&format!(
            "pill: {}x{} at ({}, {}), r{PILL_RADIUS:.0}, body #0D121C @{:.0}%, border #94A3B8 @{:.0}%, \
             pad {PILL_PAD_Y}/{PILL_PAD_X}, gap {PILL_GAP}\n",
            pill.w,
            pill.h,
            pill.x,
            pill.y,
            (GLASS >> 24) as f32 / 255.0 * 100.0,
            (LINE >> 24) as f32 / 255.0 * 100.0,
        ));
        out.push_str(&format!(
            "  dot {DOT_D}px at ({}, {}) #22D3EE, glow {DOT_GLOW}, ring {PULSE_MIN}->{PULSE_MAX} over {:.0}ms\n",
            pill.dot_x, pill.dot_y, PULSE_MS,
        ));
        out.push_str(&format!(
            "  label \"{LABEL}\" {}px at ({}, {}) #8B96AB, track {LABEL_TRACK_EM}em = {:.2}px per char\n",
            pill.label_w, pill.label_x, pill.label_y, pill.label_track,
        ));
        out.push_str(&format!(
            "  sep {SEP_W}x{SEP_H} at ({}, {}) rgba(255,255,255,.14) | count {}px at ({}, {}) #22D3EE \
             600, pop x{POP_SCALE} over {POP_MS:.0}ms\n",
            pill.sep_x, pill.sep_y, pill.count_w, pill.count_x, pill.count_y,
        ));
    } else {
        out.push_str(&format!(
            "pill: NOT DRAWN (SHOW_STATUS_PILL = false) — the mockup's third element, off at the \
             user's request; it would be {}x{} at ({}, {}), nothing is painted there and no other \
             element is inset by it\n",
            pill.w, pill.h, pill.x, pill.y,
        ));
    }
    out.push_str(&format!(
        "motion: curve cubic-bezier({:.2},{:.0},{:.2},{:.0}) | watermark {:.0}ms +{WM_DELAY_MS:.0}ms \
         rise {WM_RISE:.0} | rows {ROW_MS:.0}ms +{ROW_DELAY_MS:.0}ms +{ROW_STAGGER_MS:.0}ms/row slide \
         {ROW_SLIDE:.0} | pill {PILL_MS:.0}ms +{PILL_DELAY_MS:.0}ms rise {PILL_RISE:.0} | flash decay \
         {FLASH_DECAY}/frame\n",
        DESIGN_CURVE.0, DESIGN_CURVE.1, DESIGN_CURVE.2, DESIGN_CURVE.3, WM_MS,
    ));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The mockup's row: `[8px pad][3x11 bar][8px gap][name][8px pad]`, flushed to
    /// the screen edge, with the value 10 px past the name.
    #[test]
    fn a_row_is_the_mockups_padding_bar_gap_name() {
        let g = row_geometry(MARGIN, 52, 0, true);
        assert_eq!((BAR_W, BAR_H, BAR_RADIUS), (3, 11, 2.0));
        assert_eq!(g.row_x, MARGIN);
        assert_eq!(g.row_w, ROW_PAD_X + BAR_W + BAR_GAP + 52 + ROW_PAD_X);
        assert_eq!(g.bar_x, MARGIN + ROW_PAD_X);
        assert_eq!(g.name_x, g.bar_x + BAR_W + BAR_GAP, "8px between bar and name");
        assert_eq!(g.value_x, g.name_x + 52 + ROW_VALUE_GAP);
    }

    /// Mirrored, the row is flushed against the other edge and every gap is the
    /// same, with the bar still on the outside.
    #[test]
    fn a_mirrored_row_keeps_the_same_gaps() {
        let edge = 520 - MARGIN;
        let g = row_geometry(edge, 52, 31, false);
        assert_eq!(g.row_x + g.row_w, edge, "the chip ends on the edge");
        assert_eq!(g.bar_x + BAR_W, edge - ROW_PAD_X, "8px of padding outside the bar");
        assert_eq!(g.name_x + 52, g.bar_x - BAR_GAP);
        assert_eq!(g.value_x + 31, g.name_x - ROW_VALUE_GAP);
    }

    /// The watermark is the mark box, the 11 px gap and the tracked wordmark; the
    /// rule is 8 px under it whatever the face metrics do.
    #[test]
    fn the_watermark_is_mark_gap_word_with_the_rule_eight_below() {
        assert_eq!(watermark_width(21, 80), 21 + 11 + 80);
        assert_eq!(watermark_height(15, 19), 19, "the taller of the box and the line");
        assert_eq!(watermark_height(15, 12), 15);
        assert_eq!(watermark_rule_y(16, 19), 16 + 19 + 8);
        assert_eq!((MARK_W, WM_GAP, MARK_ECHO_X), (21, 11, 7));
    }

    /// The pill is its pieces: pad, dot, three 9 px gaps, label, separator, count,
    /// pad — and the tallest piece plus 8 px of padding above and below.
    #[test]
    fn the_pill_measures_its_pieces() {
        assert_eq!(pill_size_from(70, 10, 13, 13), (13 * 2 + 7 + 9 * 3 + 70 + 1 + 10, 13 + 8 * 2));
        assert_eq!(pill_size_from(70, 10, 10, 10), (141, 11 + 16), "the separator sets the floor");
        assert_eq!(row_height_from(17), 17 + 3 * 2);
        assert_eq!(row_height_from(9), BAR_H + 3 * 2, "the bar sets the floor");
    }

    /// The row bar is cyan at the top of the list and violet at the bottom.
    #[test]
    fn the_spine_fades_accent_to_accent2() {
        assert_eq!(spine_color(0, 8), ACCENT);
        assert_eq!(spine_color(7, 8), ACCENT2);
        assert_eq!(spine_color(0, 1), ACCENT, "a single row gets the top colour");
        assert_eq!(spine_color(1, 8), lerp_argb(ACCENT, ACCENT2, 1.0 / 7.0));
    }

    /// The design curve is pinned at both ends and never goes backwards.
    #[test]
    fn the_design_curve_is_monotonic() {
        assert_eq!(design_curve(0.0), 0.0);
        assert_eq!(design_curve(1.0), 1.0);
        let mut previous = 0.0;
        for step in 0..=100 {
            let y = design_curve(step as f32 / 100.0);
            assert!(y >= previous, "curve dipped at {step}");
            previous = y;
        }
        // Ease-out: already well past half way at the half-way point.
        assert!(design_curve(0.5) > 0.5);
    }

    /// The ring starts small and opaque, expands while fading, and holds the
    /// faded end state for the last quarter of its 2.4 s cycle.
    #[test]
    fn the_pulse_ring_expands_while_it_fades() {
        let (scale, alpha) = pulse_ring(0.0);
        assert!((scale - PULSE_MIN).abs() < 1e-4);
        assert!((alpha - PULSE_ALPHA).abs() < 1e-4);

        let (mid, mid_alpha) = pulse_ring(900.0);
        assert!(mid > PULSE_MIN && mid < PULSE_MAX);
        assert!(mid_alpha < PULSE_ALPHA && mid_alpha > 0.0);

        let (end, end_alpha) = pulse_ring(PULSE_MS * PULSE_DECAY);
        assert!((end - PULSE_MAX).abs() < 1e-4);
        assert!(end_alpha.abs() < 1e-4);
        assert_eq!(pulse_ring(PULSE_MS), pulse_ring(0.0), "the cycle repeats");
    }

    /// The count pops to 1.45 at 45 % of its 300 ms and is back to 1 at both ends.
    #[test]
    fn the_count_pops_at_forty_five_percent() {
        assert!((pop_scale(0.0) - 1.0).abs() < 1e-4);
        assert!((pop_scale(POP_AT) - POP_SCALE).abs() < 1e-4);
        assert!((pop_scale(1.0) - 1.0).abs() < 1e-4);
        assert!(pop_scale(0.2) > 1.0 && pop_scale(0.7) > 1.0);
    }

    /// The palette is the mockup's, to the byte: `rgba(13,18,28,.55)`,
    /// `rgba(148,163,184,.16)`, `rgba(255,90,90,.055)`, `rgba(255,255,255,.14)`.
    #[test]
    fn the_translucent_tokens_are_the_mockups_alphas() {
        assert_eq!(GLASS >> 24, (0.55f32 * 255.0).round() as u32);
        assert_eq!(LINE >> 24, (0.16f32 * 255.0).round() as u32);
        assert_eq!(FLASH_TINT >> 24, (0.055f32 * 255.0).round() as u32);
        assert_eq!(SEPARATOR >> 24, (0.14f32 * 255.0).round() as u32);
        assert_eq!(GLASS & 0x00FF_FFFF, 0x0D_121C);
        assert_eq!(LINE & 0x00FF_FFFF, 0x94_A3B8);
        assert_eq!(ACCENT & 0x00FF_FFFF, 0x22_D3EE);
        assert_eq!(ACCENT2 & 0x00FF_FFFF, 0xA7_8BFA);
        assert_eq!(TEXT & 0x00FF_FFFF, 0xED_F2FA);
        assert_eq!(MUTED & 0x00FF_FFFF, 0x8B_96AB);
        assert_eq!(FLASH_RED & 0x00FF_FFFF, 0xFF_5A5A);
        assert_eq!(FLASH_TEXT & 0x00FF_FFFF, 0xFF_6B6B);
    }

    /// The chip is a fraction of the glass token, so setting it to 0 leaves the
    /// mockup's own (background-less) row.
    #[test]
    fn the_row_chip_is_a_fraction_of_the_glass_token() {
        assert_eq!(ROW_CHIP_ALPHA, 0.4);
        let chip = with_alpha(GLASS, ROW_CHIP_ALPHA);
        assert_eq!(chip >> 24, ((GLASS >> 24) as f32 * 0.4).round() as u32);
        assert!(chip >> 24 < GLASS >> 24, "the chip must be lighter than the pill");
        assert_eq!(with_alpha(GLASS, 0.0) >> 24, 0, "0 is a faithful copy of the mockup");
    }

    /// The pill's endless ring used to be the only reason `paint` never went
    /// static: it reported "still moving" on every frame so the compositor always
    /// got a push. With the pill off (the default) a settled frame reports
    /// `false`, so the caller's content signature is what drives the repaint — and
    /// with the pill on it reports `true` forever, exactly as it did before.
    ///
    /// Gated on the constant on purpose, so flipping the pill back on leaves this
    /// test green; the expected value *is* the flag's two meanings.
    #[test]
    fn a_settled_frame_is_static_unless_the_pill_pulses() {
        let mut entries = Vec::new();
        for (id, name, value) in [(1u32, "Reach", "3.1-3.3"), (2, "Velocity", "82%")] {
            entries.push(Entry {
                id,
                enabled: true,
                flash: false,
                highlight: false,
                name: name.to_string(),
                value: value.to_string(),
            });
        }

        let mut text = TextEngine::new().expect("GDI text engine (--dump needs it too)");
        let mut canvas = Canvas::new(520, 400);
        let config = Config { position: Position::Right, margin: MARGIN, alpha: 1.0 };
        let now = Instant::now();
        let mut anim = Anim::new();
        // Every entrance has long finished and no flash is decaying: nothing is
        // moving except the pill's ring.
        anim.seed(&entries, now, &[]);
        canvas.clear();

        assert_eq!(
            paint(&mut canvas, &mut text, &entries, &mut anim, now, &config),
            SHOW_STATUS_PILL,
            "the status pill's ring is the only endless animation",
        );
    }
}
