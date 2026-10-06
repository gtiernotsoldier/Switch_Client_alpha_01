// hud.rs — the visible card: the brand pill on top of the module list.
//
// Geometry, paddings, colours and animation timings are transcribed from the old
// in-JVM card (`drawHudCard` + `drawGlass` + `entranceEase` in
// adapter/common/ui/…CardRenderer.kt, kept as `.dsh-tmp/card-old.kt`), which this
// out-of-process window replaces. The palette is `Theme.kt` verbatim, including
// `spineColor` / `lerpArgb`, so the accent spine still fades ACCENT -> ACCENT2
// down the list.
//
// Three things necessarily differ, because this side is not inside the game:
//   * the glass is not a capture of the framebuffer behind the card (there is no
//     framebuffer here) — it is the flat panel the old code fell back to;
//   * the rows sit on their own backing sheet (`PANEL_*`), so the list reads as a
//     sheet rather than as text floating on the game;
//   * each row's value follows its own name (`ROW_VALUE_GAP`) instead of sharing
//     one right-aligned column, so the value column no longer drifts away from
//     short names.
//
// Everything else — pill shape, 7/5 padding, 2px letter tracking, the 2px bars
// with a 4px gap, row height = font height + 5, 2px row gap, the 350 ms
// staggered slide-in, the ~300 ms flash decay — is the old geometry.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use crate::draw::{argb, Canvas};
use crate::state::Entry;
use crate::text::{FontId, TextEngine};

// ── palette (Theme.kt) ──────────────────────────────────────────────────────

/// Spectral cyan #22D3EE — the primary accent.
pub const ACCENT: u32 = argb(0x22, 0xD3, 0xEE, 0xFF);
/// Aurora violet #A78BFA — where the spine gradient ends.
pub const ACCENT2: u32 = argb(0xA7, 0x8B, 0xFA, 0xFF);
/// Primary text: white.
pub const TEXT: u32 = argb(0xFF, 0xFF, 0xFF, 0xFF);
/// Secondary text: 60% white.
pub const TEXT_DIM: u32 = argb(0xFF, 0xFF, 0xFF, 0x99);
/// "Module is working" flash red #FF5A5A.
pub const FLASH_RED: u32 = argb(0xFF, 0x5A, 0x5A, 0xFF);
/// Text colour at the peak of a work flash #FF6B6B.
pub const FLASH_RED_TEXT: u32 = argb(0xFF, 0x6B, 0x6B, 0xFF);
/// Deepest card shadow.
pub const SHADOW_DEEP: u32 = argb(0x00, 0x00, 0x00, 0x55);
/// Subtle top highlight line on an Aurora card.
pub const TOP_HIGHLIGHT: u32 = argb(0xFF, 0xFF, 0xFF, 0x14);
/// The pill's hairline divider in front of the version chip.
pub const DIVIDER: u32 = argb(0xFF, 0xFF, 0xFF, 0x24);
/// The pill body: #0D111C at 65% alpha — translucent enough to read as glass
/// over the game without the wordmark losing its contrast.
pub const PILL_BG: u32 = argb(0x0D, 0x11, 0x1C, 0xA6);
/// The module list's backing sheet: the pill's own body colour at ~38% alpha,
/// which is the same hue as the pill but far lighter, so it reads as a sheet the
/// rows sit on rather than as a second card.
pub const PANEL_BG: u32 = argb(0x0D, 0x11, 0x1C, 0x60);
/// Work-flash row tint base (the old `0x66FF5A5A`), used at k * 0.16 alpha.
const FLASH_TINT: u32 = argb(0xFF, 0x5A, 0x5A, 0x66);

pub const WORDMARK: &str = "DOPPEL";
pub const MARK: &str = "D";
pub const VERSION_CHIP: &str = "alpha";

// ── geometry ────────────────────────────────────────────────────────────────

/// Inset of the card from the window edge. The window is placed so that the pill
/// lands at screen (24, 24) with the default origin.
pub const MARGIN: i32 = 24;

const PILL_PAD_X: i32 = 7;
const PILL_PAD_Y: i32 = 5;
const PILL_TRACK: f32 = 2.0;
const PILL_INNER_GAP: i32 = 7;
const PILL_DIVIDER_W: i32 = 1;

const ROW_BAR_W: i32 = 2;
const ROW_BAR_GAP: i32 = 4;
/// Gap between a row's name and *its own* value: the value is no longer aligned
/// on a shared column, so this is the whole distance between the two.
const ROW_VALUE_GAP: i32 = 10;
/// Row height = font cell height + this (the old `fontHeight + 5`).
const ROW_EXTRA: i32 = 5;
/// Gap between rows (the old `rowStep = rowH + 2`).
const ROW_GAP: i32 = 2;
/// Stacked layout only: gap between the pill and the list under it.
const LIST_TOP_GAP: i32 = 10;

// ── the list's backing sheet ────────────────────────────────────────────────

/// Corner radius of the backing sheet: much squarer than the pill, so the two
/// shapes cannot be mistaken for one another.
const PANEL_RADIUS: f32 = 8.0;
/// Padding from the panel's rim to the spine bars on the side the bars face (the
/// screen edge), so the bars stay clear of the highlight without floating.
const PANEL_PAD_BARS: i32 = 6;
/// Padding from the widest row's last pixel (name or value) to the opposite rim.
const PANEL_PAD_CONTENT: i32 = 8;
/// Padding above the first row and below the last one.
const PANEL_PAD_Y: i32 = 8;
/// The sheet's drop shadow, the same (spread, alpha) shape the pill uses but
/// shallower: a backing sheet sits *on* the game, it does not float above it.
const PANEL_SHADOW: [(i32, f32); 3] = [(3, 0.10), (2, 0.16), (1, 0.24)];

// ── animation timings (old card constants) ──────────────────────────────────

const ENTRANCE_MS: f32 = 350.0;
const ENTRANCE_SLIDE: f32 = 10.0;
const STAGGER_MS: f32 = 45.0;
/// Flash strength lost per frame: 255 / 15 ~= 17 frames ~= 280 ms at 60 Hz.
const FLASH_DECAY: u8 = 15;

/// Where the list lives; the pill takes the opposite corner, and "Left" means
/// the list is on the left, matching `HUD.position` in the old card. The default
/// is the single-column card: pill on top, list directly under it.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Position {
    Stacked,
    ListLeft,
    ListRight,
}

/// Per-frame inputs that are not part of the shared state.
pub struct Config {
    pub position: Position,
    pub margin: i32,
    /// Global fade multiplier, 1.0 normally and ramped to 0 when the game dies.
    pub alpha: f32,
}

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
/// bottom (Theme.spineColor).
pub fn spine_color(index: usize, count: usize) -> u32 {
    let t = if count <= 1 { 0.0 } else { index as f32 / (count - 1) as f32 };
    lerp_argb(ACCENT, ACCENT2, t)
}

// ── animation state ─────────────────────────────────────────────────────────

/// The two things this side animates that the writer does not send: when a row
/// first appeared, and how hot a module's flash still is. Both are keyed the way
/// the old card keyed them — name for the entrance, module id for the flash.
pub struct Anim {
    appear: HashMap<String, Instant>,
    flash: HashMap<u32, u8>,
}

impl Anim {
    pub fn new() -> Anim {
        Anim { appear: HashMap::new(), flash: HashMap::new() }
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

    /// 0 -> 1 ease-out-cubic entrance progress for the row, staggered by its
    /// index in the list. A row scheduled for a later slot returns 0 until its
    /// turn arrives, which is what the old card did (`if (ease <= 0) continue`).
    pub fn ease(&mut self, name: &str, index: usize, now: Instant) -> f32 {
        let ts = *self.appear.entry(name.to_string()).or_insert_with(|| {
            now + Duration::from_millis((index as f32 * STAGGER_MS) as u64)
        });
        let elapsed_ms = now.saturating_duration_since(ts).as_secs_f32() * 1000.0;
        let t = (elapsed_ms / ENTRANCE_MS).clamp(0.0, 1.0);
        1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)
    }

    /// Current flash strength for a module, 0..=255.
    pub fn strength(&self, id: u32) -> u8 {
        *self.flash.get(&id).unwrap_or(&0)
    }

    /// Dump/test hook: pretend every row entered a second ago and seed the given
    /// flash strengths, so a still frame is representative of a live card.
    pub fn seed(&mut self, entries: &[Entry], now: Instant, flashes: &[(u32, u8)]) {
        let past = now.checked_sub(Duration::from_secs(1)).unwrap_or(now);
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

// ── layout + painting ───────────────────────────────────────────────────────

/// Width of one row as drawn: `[bar][gap][name]`, plus `[gap][value]` when the
/// row carries a value. The list's content width — and with it the backing
/// sheet — is the widest of these, so a value-less row no longer reserves room
/// for a value it does not have.
pub fn row_width(name_w: i32, value_w: i32) -> i32 {
    ROW_BAR_W
        + ROW_BAR_GAP
        + name_w
        + if value_w > 0 { ROW_VALUE_GAP + value_w } else { 0 }
}

/// Left edge of one row's value in a left-anchored list: `ROW_VALUE_GAP` after
/// that row's own name, never a shared column. A mirrored (right-anchored) list
/// flips it, so the value's right edge sits the same gap before the name.
pub fn value_x(anchor_x: i32, name_w: i32, value_w: i32, bars_left: bool) -> i32 {
    if bars_left {
        anchor_x + ROW_BAR_W + ROW_BAR_GAP + name_w + ROW_VALUE_GAP
    } else {
        anchor_x - ROW_BAR_W - ROW_BAR_GAP - name_w - ROW_VALUE_GAP - value_w
    }
}

/// The backing sheet's rectangle for a list of `count` rows: `content_w` pixels
/// of row content starting at the anchor the bars are flush to, padded as
/// documented on the `PANEL_*` constants. Pure geometry, so the padding, the
/// mirroring and the band height are testable without a text engine.
pub fn panel_rect(
    anchor_x: i32,
    content_w: i32,
    count: usize,
    row_step: i32,
    list_y: i32,
    bars_left: bool,
) -> (i32, i32, u32, u32) {
    let (x, w) = if bars_left {
        (anchor_x - PANEL_PAD_BARS, content_w + PANEL_PAD_BARS + PANEL_PAD_CONTENT)
    } else {
        (anchor_x - content_w - PANEL_PAD_CONTENT, content_w + PANEL_PAD_CONTENT + PANEL_PAD_BARS)
    };
    // (count - 1) row steps plus one row, plus the padding on both sides.
    let h = count as i32 * row_step - ROW_GAP + PANEL_PAD_Y * 2;
    (x, list_y - PANEL_PAD_Y, w.max(0) as u32, h.max(0) as u32)
}

/// One measured row, positioned at rest. `paint` adds the entrance slide to the
/// x values; nothing else about a row depends on time.
struct RowLayout {
    /// Index into the entry slice this layout was measured from.
    index: usize,
    name_w: i32,
    value_w: i32,
    /// Left edge of the spine bar, and of the row band the flash tint covers.
    bar_x: i32,
    row_left: i32,
    name_x: i32,
    value_x: i32,
}

/// A still card: the pill, the backing sheet and one entry per row. Everything
/// `paint` needs except the current time.
struct Layout {
    pill_x: i32,
    pill_y: i32,
    pill_w: i32,
    pill_h: i32,
    /// None when no entry has a name: an empty list draws no sheet at all.
    panel: Option<(i32, i32, u32, u32)>,
    content_w: i32,
    list_y: i32,
    row_h: i32,
    row_step: i32,
    bars_left: bool,
    rows: Vec<RowLayout>,
}

/// Pill size for the current face metrics. Shared by the painter and the dump's
/// diagnostic line so the two cannot drift apart.
pub fn pill_size(text: &TextEngine) -> (i32, i32) {
    let w = PILL_PAD_X * 2
        + text.measure(FontId::UiBold, MARK)
        + 4
        + text.measure_tracked(FontId::UiBold, WORDMARK, PILL_TRACK)
        + PILL_INNER_GAP
        + PILL_DIVIDER_W
        + PILL_INNER_GAP
        + text.measure(FontId::Mono, VERSION_CHIP);
    let h = text.height(FontId::UiBold) + 4 + PILL_PAD_Y * 2;
    (w, h)
}

/// Row height for the current face metrics: the old `fontHeight + 5`.
pub fn row_height(text: &TextEngine) -> i32 {
    text.height(FontId::Ui) + ROW_EXTRA
}

/// Measure the whole card for the current text metrics: row widths, where each
/// row's pieces sit, the content width and the backing sheet's rectangle. Shared
/// by `paint` and `describe`, so the picture and the printed numbers cannot
/// disagree.
fn measure_layout(text: &TextEngine, entries: &[Entry], cfg: &Config, canvas_w: u32) -> Layout {
    let row_h = row_height(text);
    let row_step = row_h + ROW_GAP;

    // Rows: everything with a name, measured once. Sorted width-descending — the
    // old card's default staircase order.
    let mut measured: Vec<(usize, i32, i32)> = Vec::new();
    for (index, entry) in entries.iter().enumerate() {
        if entry.name.is_empty() {
            continue;
        }
        let name_w = text.measure(FontId::Ui, &entry.name);
        let value_w = if entry.value.is_empty() { 0 } else { text.measure(FontId::Mono, &entry.value) };
        measured.push((index, name_w, value_w));
    }
    measured.sort_by(|a, b| b.1.cmp(&a.1));

    // The list is as wide as its widest row, value included. Each row keeps its
    // own value next to its own name, so the right edge is ragged by design and
    // only the widest row reaches `content_w`.
    let content_w = measured
        .iter()
        .map(|(_, name_w, value_w)| row_width(*name_w, *value_w))
        .max()
        .unwrap_or(0);

    let (pill_w, pill_h) = pill_size(text);

    let margin = cfg.margin;
    let bars_left = matches!(cfg.position, Position::Stacked | Position::ListLeft);
    let anchor_x = if bars_left { margin } else { canvas_w as i32 - margin };

    // Pill in one top corner, list in the other; stacked puts the list directly
    // under the pill, which is the default card.
    let (pill_x, list_y) = match cfg.position {
        Position::Stacked => (margin, margin + pill_h + LIST_TOP_GAP),
        Position::ListLeft => (canvas_w as i32 - margin - pill_w, margin),
        Position::ListRight => (margin, margin),
    };

    let rows: Vec<RowLayout> = measured
        .iter()
        .map(|&(index, name_w, value_w)| RowLayout {
            index,
            name_w,
            value_w,
            bar_x: if bars_left { anchor_x } else { anchor_x - ROW_BAR_W },
            row_left: if bars_left { anchor_x } else { anchor_x - content_w },
            name_x: if bars_left {
                anchor_x + ROW_BAR_W + ROW_BAR_GAP
            } else {
                anchor_x - ROW_BAR_W - ROW_BAR_GAP - name_w
            },
            value_x: value_x(anchor_x, name_w, value_w, bars_left),
        })
        .collect();

    let panel = if rows.is_empty() {
        None
    } else {
        Some(panel_rect(anchor_x, content_w, rows.len(), row_step, list_y, bars_left))
    };

    Layout {
        pill_x,
        pill_y: margin,
        pill_w,
        pill_h,
        panel,
        content_w,
        list_y,
        row_h,
        row_step,
        bars_left,
        rows,
    }
}

/// Compose one frame of the card. Returns true while something is still moving
/// (an entrance or a flash), which the caller uses to decide whether the next
/// frame needs to be pushed to the compositor at all.
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

    let layout = measure_layout(text, entries, cfg, canvas.width());
    let name_cell = text.height(FontId::Ui);
    let value_cell = text.height(FontId::Mono);
    let count = layout.rows.len();

    // The backing sheet goes down first: the flash tint, the bars, the names and
    // the values are all meant to sit on top of it.
    if let Some((x, y, w, h)) = layout.panel {
        draw_panel(canvas, x, y, w, h, global);
    }
    draw_pill(
        canvas,
        text,
        layout.pill_x,
        layout.pill_y,
        layout.pill_w,
        layout.pill_h,
        global,
    );

    // ── module list ──
    let mut y = layout.list_y;
    let mut animating = false;
    for (i, row) in layout.rows.iter().enumerate() {
        let entry = &entries[row.index];
        let ease = anim.ease(&entry.name, i, now);
        if ease <= 0.0 {
            // Not this row's turn yet: the slot is reserved, nothing is drawn.
            y += layout.row_step;
            animating = true;
            continue;
        }
        let alpha_mul = 0.25 + 0.75 * ease;
        let slide = ((1.0 - ease) * ENTRANCE_SLIDE).round() as i32;

        // Bars stay flush to the screen edge and the row slides in from it: left
        // list [bar][gap][name][10px][value], right list mirrored, with the value
        // on the outside of its own name.
        let shift = if layout.bars_left { slide } else { -slide };
        let bar_x = row.bar_x + shift;
        let name_x = row.name_x + shift;
        let value_x = row.value_x + shift;
        let row_left = row.row_left;

        let strength = anim.strength(entry.id) as f32 / 255.0;

        if strength > 0.0 {
            // Work flash: soft red halo + rounded row tint (successor of isRed).
            canvas.fill_rounded_rect(
                row_left - 3,
                y - 2,
                (layout.content_w + 6) as u32,
                (layout.row_h + 4) as u32,
                5.0,
                with_alpha(FLASH_RED, strength * 0.05 * global),
            );
            canvas.fill_rounded_rect(
                row_left - 2,
                y - 1,
                (layout.content_w + 4) as u32,
                (layout.row_h + 2) as u32,
                3.0,
                with_alpha(FLASH_TINT, strength * 0.16 * global),
            );
        }

        let text_y = y + (layout.row_h - name_cell) / 2;
        let base = with_alpha(TEXT, 0.92 * alpha_mul * global);
        let name_colour = if strength > 0.0 {
            lerp_argb(base, with_alpha(FLASH_RED_TEXT, global), strength)
        } else {
            base
        };
        text.draw(canvas, FontId::Ui, &entry.name, name_x, text_y, name_colour);

        if row.value_w > 0 {
            let colour = if entry.highlight { ACCENT } else { TEXT_DIM };
            text.draw(
                canvas,
                FontId::Mono,
                &entry.value,
                value_x,
                y + (layout.row_h - value_cell) / 2,
                with_alpha(colour, alpha_mul * global),
            );
        }

        let base_bar = with_alpha(spine_color(i, count), alpha_mul * global);
        let bar_colour = if strength > 0.0 {
            lerp_argb(base_bar, with_alpha(FLASH_RED, global), strength)
        } else {
            base_bar
        };
        canvas.fill_rounded_rect(
            bar_x,
            y + 1,
            ROW_BAR_W as u32,
            (layout.row_h - 4).max(1) as u32,
            1.0,
            bar_colour,
        );

        if ease < 1.0 || strength > 0.0 {
            animating = true;
        }
        y += layout.row_step;
    }

    animating
}

/// The module list's backing sheet: one rounded rect behind the rows in the
/// pill's own body colour at a much lower alpha, with the same soft drop shadow
/// and the same 1 px `TOP_HIGHLIGHT` line inside its top edge. It deliberately
/// gets no accent rim, so it stays a sheet the rows sit on instead of a second
/// card.
fn draw_panel(canvas: &mut Canvas, x: i32, y: i32, w: u32, h: u32, global: f32) {
    if w == 0 || h == 0 {
        return;
    }

    // Drop shadow: stacked SHADOW_DEEP rects, each a little wider and one pixel
    // lower, exactly the way the pill's shadow falls off.
    for (spread, alpha) in PANEL_SHADOW {
        canvas.fill_rounded_rect(
            x - spread,
            y + spread,
            w + (spread * 2) as u32,
            h,
            PANEL_RADIUS + spread as f32,
            with_alpha(SHADOW_DEEP, alpha * global),
        );
    }

    canvas.fill_rounded_rect(x, y, w, h, PANEL_RADIUS, with_alpha(PANEL_BG, global));

    // 1 px top highlight, inset by the corner radius so it spans the straight
    // part of the top edge only, as the pill's highlight does.
    let inset = PANEL_RADIUS as i32;
    canvas.fill_rect(
        x + inset,
        y + 1,
        (w as i32 - inset * 2).max(1) as u32,
        1,
        with_alpha(TOP_HIGHLIGHT, global),
    );
}

/// The brand pill: soft shadow, #0D111C at 65%, a 1px top highlight inside the
/// rim, the accent "D" mark, the tracked wordmark, a hairline divider and the
/// mono version chip.
fn draw_pill(
    canvas: &mut Canvas,
    text: &mut TextEngine,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    global: f32,
) {
    if w <= 0 || h <= 0 {
        return;
    }
    let radius = h as f32 / 2.0;

    // Drop shadow: four stacked SHADOW_DEEP pills, each a little wider and one
    // pixel lower, so the edge falls off instead of ending in a hard band.
    for (spread, alpha) in [(4, 0.10f32), (3, 0.14), (2, 0.20), (1, 0.28)] {
        canvas.fill_rounded_rect(
            x - spread,
            y + spread,
            (w + spread * 2) as u32,
            h as u32,
            radius + spread as f32,
            with_alpha(SHADOW_DEEP, alpha * global),
        );
    }

    canvas.fill_rounded_rect(x, y, w as u32, h as u32, radius, with_alpha(PILL_BG, global));

    // The old drawGlass rim (accent at 18%), plus the Aurora top highlight.
    canvas.stroke_rounded_rect(x, y, w as u32, h as u32, radius, 1.0, with_alpha(ACCENT, 0.18 * global));
    let inset = (radius * 0.6) as i32;
    canvas.fill_rect(
        x + inset,
        y + 1,
        (w - inset * 2).max(1) as u32,
        1,
        with_alpha(TOP_HIGHLIGHT, global),
    );

    let text_y = y + PILL_PAD_Y + 1;
    let mut cx = x + PILL_PAD_X;

    text.draw(canvas, FontId::UiBold, MARK, cx, text_y, with_alpha(ACCENT, global));
    cx += mark_w(text);

    // Old colouring: the two P's of DOPPEL carry the accent.
    text.draw_tracked(
        canvas,
        FontId::UiBold,
        WORDMARK,
        cx,
        text_y,
        PILL_TRACK,
        |i, _| with_alpha(if i == 2 || i == 3 { ACCENT } else { TEXT }, global),
    );
    cx += text.measure_tracked(FontId::UiBold, WORDMARK, PILL_TRACK);

    cx += PILL_INNER_GAP;
    let divider_h = (text.height(FontId::UiBold) - 4).max(1);
    canvas.fill_rect(
        cx,
        y + (h - divider_h) / 2,
        PILL_DIVIDER_W as u32,
        divider_h as u32,
        with_alpha(DIVIDER, global),
    );
    cx += PILL_DIVIDER_W + PILL_INNER_GAP;

    text.draw(
        canvas,
        FontId::Mono,
        VERSION_CHIP,
        cx,
        y + (h - text.height(FontId::Mono)) / 2,
        with_alpha(ACCENT, global),
    );
}

fn mark_w(text: &TextEngine) -> i32 {
    text.measure(FontId::UiBold, MARK) + 4
}

/// The geometry behind the picture, for `--dump`: the pill and sheet rectangles,
/// the row band and, per row, the distance from the name's end to its value.
/// Measured through the same `measure_layout` the painter uses, so a review dump
/// and the printed numbers cannot drift apart.
pub fn describe(text: &TextEngine, entries: &[Entry], position: Position, canvas_w: u32) -> String {
    let cfg = Config { position, margin: MARGIN, alpha: 1.0 };
    let layout = measure_layout(text, entries, &cfg, canvas_w);
    let body = |colour: u32| colour & 0x00FF_FFFF;

    let mut out = format!(
        "card: pill {}x{} at ({}, {}), body #{:06X} alpha 0x{:02X} | radius {}\n",
        layout.pill_w,
        layout.pill_h,
        layout.pill_x,
        layout.pill_y,
        body(PILL_BG),
        PILL_BG >> 24,
        layout.pill_h / 2,
    );
    match layout.panel {
        Some((x, y, w, h)) => out.push_str(&format!(
            "      panel {}x{} at ({}, {}), body #{:06X} alpha 0x{:02X} | radius {} | \
             pad bars {} / content {} / top {} / bottom {}\n",
            w,
            h,
            x,
            y,
            body(PANEL_BG),
            PANEL_BG >> 24,
            PANEL_RADIUS as i32,
            PANEL_PAD_BARS,
            PANEL_PAD_CONTENT,
            PANEL_PAD_Y,
            PANEL_PAD_Y,
        )),
        None => out.push_str("      panel none (no named rows)\n"),
    }

    let count = layout.rows.len();
    if count > 0 {
        let last_y = layout.list_y + (count as i32 - 1) * layout.row_step;
        out.push_str(&format!(
            "rows: {} x {}px, step {}px, first y={}, last y={}..{}, content {}px wide\n",
            count,
            layout.row_h,
            layout.row_step,
            layout.list_y,
            last_y,
            last_y + layout.row_h,
            layout.content_w,
        ));
    }

    for (slot, row) in layout.rows.iter().enumerate() {
        let entry = &entries[row.index];
        let name_end = row.name_x + row.name_w;
        if row.value_w > 0 {
            // The gap on the side the value actually sits: a mirrored list puts
            // the value before its name instead of after it.
            let gap = if layout.bars_left {
                row.value_x - name_end
            } else {
                row.name_x - (row.value_x + row.value_w)
            };
            out.push_str(&format!(
                "  row {} {}: nameEnd x={} -> value x={} (gap {}px), \"{}\" {}px wide\n",
                slot, entry.name, name_end, row.value_x, gap, entry.value, row.value_w,
            ));
        } else {
            out.push_str(&format!(
                "  row {} {}: nameEnd x={}, no value\n",
                slot, entry.name, name_end,
            ));
        }
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The whole point of change 2: the value starts at the row's own name end
    /// plus the gap, so a short name gets a short gap.
    #[test]
    fn a_value_starts_the_gap_after_its_own_name() {
        let anchor = MARGIN;
        let name_w = 52;
        let name_x = anchor + ROW_BAR_W + ROW_BAR_GAP;
        let vx = value_x(anchor, name_w, 31, true);

        assert_eq!(name_x, 30);
        assert_eq!(name_x + name_w, 82, "name ends here");
        assert_eq!(vx, 92);
        assert_eq!(vx - (name_x + name_w), ROW_VALUE_GAP);
        // Two names of different lengths therefore get different value x, which
        // is the ragged edge that was asked for.
        assert_eq!(value_x(anchor, 20, 31, true) - (name_x + 20), ROW_VALUE_GAP);
        assert_ne!(value_x(anchor, 20, 31, true), vx);
    }

    /// A mirrored (right-anchored) list keeps the same gap, on the other side.
    #[test]
    fn a_mirrored_row_keeps_the_same_gap_before_its_name() {
        let anchor = 900 - MARGIN;
        let (name_w, value_w) = (52, 31);
        let vx = value_x(anchor, name_w, value_w, false);
        let name_x = anchor - ROW_BAR_W - ROW_BAR_GAP - name_w;

        assert_eq!(name_x, 818);
        assert_eq!(vx + value_w, 808, "value ends here");
        assert_eq!(name_x - (vx + value_w), ROW_VALUE_GAP);
    }

    #[test]
    fn a_row_is_only_as_wide_as_its_own_content() {
        assert_eq!(row_width(52, 31), ROW_BAR_W + ROW_BAR_GAP + 52 + ROW_VALUE_GAP + 31);
        assert_eq!(row_width(52, 0), ROW_BAR_W + ROW_BAR_GAP + 52);
        // A value-less row must not reserve the value gap.
        assert_eq!(row_width(52, 0), 58);
    }

    #[test]
    fn the_panel_pads_the_row_band_as_documented() {
        // The dump's default card: 8 rows, 25px cell, 2px gap, first row at 68.
        let count = 8;
        let row_step = 27;
        let list_y = 68;
        let content_w = 200;
        let (x, y, w, h) = panel_rect(MARGIN, content_w, count, row_step, list_y, true);

        // 6px left of the bars, 8px past the content, 8px above and below.
        assert_eq!((x, y), (MARGIN - PANEL_PAD_BARS, list_y - PANEL_PAD_Y));
        assert_eq!((w, h), (214, 230));
        assert_eq!(x + w as i32, MARGIN + content_w + PANEL_PAD_CONTENT);

        let first_top = list_y;
        let last_bottom = list_y + (count as i32 - 1) * row_step + 25;
        assert_eq!(last_bottom, 282);
        assert_eq!(first_top - y, PANEL_PAD_Y, "pad above the first row");
        assert_eq!((y + h as i32) - last_bottom, PANEL_PAD_Y, "pad below the last row");
    }

    #[test]
    fn a_right_anchored_panel_mirrors_the_padding() {
        let anchor = 900 - MARGIN;
        let content_w = 200;
        let (x, y, w, h) = panel_rect(anchor, content_w, 3, 27, 68, false);

        assert_eq!(x, anchor - content_w - PANEL_PAD_CONTENT);
        assert_eq!(x + w as i32, anchor + PANEL_PAD_BARS, "the bars' side keeps 6px");
        assert_eq!(w as i32, content_w + PANEL_PAD_CONTENT + PANEL_PAD_BARS);
        assert_eq!((y, h as i32), (60, 3 * 27 - ROW_GAP + PANEL_PAD_Y * 2));
    }

    /// Change 1 and change 3, as numbers: the sheet is the pill's hue at a much
    /// lower alpha, and the pill itself is now ~65% rather than 80%.
    #[test]
    fn the_sheet_is_lighter_than_the_pill_and_the_pill_is_more_transparent() {
        let pill_a = PILL_BG >> 24;
        let panel_a = PANEL_BG >> 24;

        assert_eq!(pill_a, 0xA6, "the pill wants ~65% alpha");
        assert!((0x59..=0x66).contains(&panel_a), "sheet alpha {panel_a:#04X} outside 0x59..=0x66");
        assert!(panel_a < pill_a, "the sheet must read lighter than the pill");
        // Same body colour, only the alpha differs.
        assert_eq!(PILL_BG & 0x00FF_FFFF, PANEL_BG & 0x00FF_FFFF);
        assert_eq!(PANEL_BG & 0x00FF_FFFF, 0x0D_111C);
        // The sheet's highlight is the pill's, and both are a 1px 0x14 white.
        assert_eq!(TOP_HIGHLIGHT, argb(0xFF, 0xFF, 0xFF, 0x14));
    }
}
