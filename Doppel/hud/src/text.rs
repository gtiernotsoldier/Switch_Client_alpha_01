// text.rs — the text rasteriser: GDI draws into a scratch DIB, this copies the
// result into the premultiplied BGRA canvas.
//
// Why GDI at all: the HUD needs a real face, and a hand-baked bitmap font would
// have to be embedded in the binary and would fall apart on any string the game
// hands us. GDI is already linked for CreateDIBSection, and it is not a third
// party dependency.
//
// Why this shape: the compositor wants premultiplied BGRA, GDI will not produce
// it. So text is drawn white-on-transparent-black into a 32bpp top-down scratch
// DIB, where a pixel's value *is* its coverage; the blitter then reads
// coverage = max(r,g,b)/255 and blends the requested colour with that coverage.
// Subpixel (ClearType) antialiasing is deliberately not requested — its tinted
// edge pixels cannot be premultiplied without colour fringing.
//
// The scratch is per-process, the fonts are created once and cached, and nothing
// here is freed per frame.
//
// ── faces ───────────────────────────────────────────────────────────────────
//
// The ported HUD is OpenCommunity's `HUD.cpp`, which loads its own copy of the
// **Play** typeface through ImGui (regular and bold) and draws all of it at
// `fontSize = 16` (HUD.cpp:469). Play is not installed on this machine and may
// not be on the target's, so the closest installed pair stands in:
//
//   role                        source                 this port
//   module names, Default mode  Play Bold 16 px        Segoe UI Semibold, lfHeight -16
//   everything else             Play Regular 16 px     Segoe UI, lfHeight -16
//
// The 16 px size is the source's own number and is kept exactly — one em is one
// em, so every layout constant the port takes from the source (`lineHeight`,
// `itemHeight`, the paddings) keeps its value. `--scale` multiplies it, which is
// the one thing the source has no equivalent of (it has no DPI handling at all).
//
// Placement: `ImDrawList::AddText(font, size, pos, ...)` puts `pos` at the top of
// a `size`-tall line box, so that is what `draw`'s `y` means here too. GDI's cell
// is taller than that box (ascent + descent + internal leading), so a run is drawn
// `internal_leading` rows above `y`; the glyph ink then lands where ImGui would
// have put it, and a row's background box (which the source sizes from the same
// 16 px) keeps the text inside it. The substitute face's metrics are not Play's,
// so this is a stand-in, not a byte-for-byte match — see the README.

use std::ffi::c_void;

use crate::draw::Canvas;
use crate::win::*;

/// The source's font size, HUD.cpp:469 (`fontSize = 16.0f`), the size every face
/// here is created at. `hud.rs` reads it as the mode's `lineHeight`.
pub const FONT_PX: f32 = 16.0;

/// Room for a 24-byte name at 4x scale in one scratch row, and for the tallest
/// scaled cell: the HUD draws one run at a time and clears only the columns it
/// can touch, so the scratch is sized for the worst case and then left alone.
const SCRATCH_W: i32 = 2048;
const SCRATCH_H: i32 = 96;

/// The standing-in family. Both faces are the same family at two weights, which is
/// the closest a stock Windows install gets to Play's regular/bold pair.
const UI_FACE: &str = "Segoe UI";

/// The largest scale `--scale` accepts; the scratch above is sized for it.
pub const MAX_SCALE: f32 = 4.0;
/// The smallest scale `--scale` accepts.
pub const MIN_SCALE: f32 = 0.5;

/// Which face a run is drawn in. The source picks between its bold and regular
/// Play per element; everything that is not a module name in Default mode uses the
/// regular weight.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FontId {
    /// Module names in Default mode — the source's bold Play (`nameFont`).
    Name,
    /// Tags, the watermark and every non-Default name — the source's regular Play.
    Body,
}

struct Font {
    handle: Hfont,
    /// Stock fallback objects must never be deleted.
    owned: bool,
    /// GDI's cell height (`tmHeight`), for the dump's face line.
    cell: i32,
    /// The source's font size this face was created from, times `--scale`.
    em: f32,
    /// `tmInternalLeading`: how far below the cell's top the em box starts.
    leading: i32,
}

/// The scratch DC, its DIB and the cached faces.
pub struct TextEngine {
    dc: Hdc,
    dib: *mut c_void,
    prev: *mut c_void,
    bits: *mut u8,
    name: Font,
    body: Font,
}

impl TextEngine {
    /// Create the scratch and both faces at `16 * scale` pixels. A scale outside
    /// `MIN_SCALE..=MAX_SCALE` is clamped rather than refused: the command line
    /// rejects it first, and this keeps a bad call from producing a 0-pixel font.
    pub fn new(scale: f32) -> Result<TextEngine, String> {
        let scale = if scale.is_finite() { scale.clamp(MIN_SCALE, MAX_SCALE) } else { 1.0 };
        let em = FONT_PX * scale;
        let height = -(em.round() as i32).max(1);

        // SAFETY: create a memory DC and a top-down 32bpp DIB in it, exactly as
        // the blit target is built in main.rs; the returned `bits` pointer is
        // owned by the DIB and stays valid until the DIB is deleted, which only
        // happens in Drop.
        unsafe {
            let dc = CreateCompatibleDC(std::ptr::null_mut());
            if dc.is_null() {
                return Err("CreateCompatibleDC failed (text scratch)".to_string());
            }

            let mut info: BitmapInfo = std::mem::zeroed();
            info.header.size = std::mem::size_of::<BitmapInfoHeader>() as u32;
            info.header.width = SCRATCH_W;
            // Negative height = top-down, so scratch row 0 is canvas row 0.
            info.header.height = -SCRATCH_H;
            info.header.planes = 1;
            info.header.bit_count = 32;
            info.header.compression = BI_RGB;

            let mut bits: *mut c_void = std::ptr::null_mut();
            let dib = CreateDIBSection(
                dc,
                &info,
                DIB_RGB_COLORS,
                &mut bits,
                std::ptr::null_mut(),
                0,
            );
            if dib.is_null() || bits.is_null() {
                DeleteDC(dc);
                return Err("CreateDIBSection failed (text scratch)".to_string());
            }
            let prev = SelectObject(dc, dib);

            // White ink on a scratch that is cleared before every glyph run: the
            // stored value is then coverage x white, which is all the blitter
            // needs. Transparent background so the glyph is not boxed.
            SetBkMode(dc, TRANSPARENT);
            SetTextColor(dc, 0x00FF_FFFF);

            let name = load_font(dc, UI_FACE, height, FW_SEMIBOLD, UI_PITCH_FAMILY, em);
            let body = load_font(dc, UI_FACE, height, FW_NORMAL, UI_PITCH_FAMILY, em);

            Ok(TextEngine { dc, dib, prev, bits: bits as *mut u8, name, body })
        }
    }

    fn font(&self, id: FontId) -> &Font {
        match id {
            FontId::Name => &self.name,
            FontId::Body => &self.body,
        }
    }

    /// The source's font size for this face, times `--scale`. This is the number
    /// the HUD's layout uses: the source's `lineHeight` is exactly the font size
    /// (HUD.cpp:472), not the taller GDI cell.
    pub fn em_px(&self, id: FontId) -> f32 {
        self.font(id).em
    }

    /// GDI's cell height for this face, printed by `--dump` next to the em size so
    /// the two are never confused when a face metric moves.
    pub fn cell_height(&self, id: FontId) -> i32 {
        self.font(id).cell
    }

    /// Pixel width of `text` in the given face.
    pub fn measure(&self, id: FontId, text: &str) -> i32 {
        if text.is_empty() {
            return 0;
        }
        let buf: Vec<u16> = text.encode_utf16().collect();
        // SAFETY: the font handle is live for the life of the engine and the
        // buffer outlives the call.
        unsafe {
            SelectObject(self.dc, self.font(id).handle);
            let mut size = Size { cx: 0, cy: 0 };
            if GetTextExtentPoint32W(self.dc, buf.as_ptr(), buf.len() as i32, &mut size) == 0 {
                return 0;
            }
            size.cx.max(0)
        }
    }

    /// Draw `text` with the top of its line box at (x, y). Returns the advance
    /// width. `y` is the source's own `textY`: ImGui's `AddText` places the top of
    /// a 16 px line box there, so this does the same (see the header note on
    /// internal leading).
    pub fn draw(
        &mut self,
        canvas: &mut Canvas,
        id: FontId,
        text: &str,
        x: i32,
        y: i32,
        colour: u32,
    ) -> i32 {
        let (width, height) = match self.rasterise(id, text) {
            Some(size) => size,
            None => return 0,
        };
        let top = y - self.font(id).leading;

        // SAFETY: the scratch holds SCRATCH_W x SCRATCH_H pixels and every column
        // read below is inside `width` (<= SCRATCH_W) and every row inside
        // `height` (<= SCRATCH_H). The font handle stays live for the engine.
        unsafe {
            for row in 0..height {
                let row_base = (row * SCRATCH_W * 4) as isize;
                // Every column the run touched, the two overhang ones included: a
                // glyph may reach past its advance, and clipping that off would
                // shave the last stem. `blend_pixel` clamps to the canvas.
                for col in 0..width {
                    let off = row_base + (col * 4) as isize;
                    let b = *self.bits.offset(off) as u32;
                    let g = *self.bits.offset(off + 1) as u32;
                    let r = *self.bits.offset(off + 2) as u32;
                    let coverage = b.max(g).max(r);
                    if coverage > 0 {
                        canvas.blend_pixel(x + col, top + row, colour, coverage as f32 / 255.0);
                    }
                }
            }
        }

        self.measure(id, text)
    }

    /// Clear the scratch and ask GDI for one run, white on transparent. Returns
    /// the run's box, or None when there is nothing to draw.
    ///
    /// Only the columns this run can touch are cleared: a glyph is ~15 px wide
    /// inside a 2048 px scratch row, and clearing the whole row would be ~130x the
    /// work for the one short run a row draws.
    fn rasterise(&self, id: FontId, text: &str) -> Option<(i32, i32)> {
        if text.is_empty() {
            return None;
        }
        let height = self.font(id).cell.min(SCRATCH_H);
        // Two extra columns: a glyph may overhang its advance slightly (italic
        // bearings, hinting), and clipping that off would shave the last stem.
        let width = (self.measure(id, text) + 2).min(SCRATCH_W);
        if height <= 0 || width <= 0 {
            return None;
        }

        // SAFETY: the scratch DIB storage is SCRATCH_W x SCRATCH_H x 4 bytes; the
        // per-row clear below stays inside it and every handle is live.
        unsafe {
            for row in 0..height {
                std::ptr::write_bytes(
                    self.bits.offset((row * SCRATCH_W * 4) as isize),
                    0,
                    (width * 4) as usize,
                );
            }

            SelectObject(self.dc, self.font(id).handle);
            SetTextColor(self.dc, 0x00FF_FFFF);
            let buf: Vec<u16> = text.encode_utf16().collect();
            if TextOutW(self.dc, 0, 0, buf.as_ptr(), buf.len() as i32) == 0 {
                return None;
            }
        }

        Some((width, height))
    }
}

impl Drop for TextEngine {
    fn drop(&mut self) {
        // SAFETY: unwinding the objects in the reverse order of selection — a
        // font or a DIB must not be deleted while it is still selected into the
        // DC, and the stock font is never deleted at all.
        unsafe {
            SelectObject(self.dc, GetStockObject(DEFAULT_GUI_FONT));
            SelectObject(self.dc, self.prev);
            for font in [&self.name, &self.body] {
                if font.owned && !font.handle.is_null() {
                    DeleteObject(font.handle);
                }
            }
            DeleteObject(self.dib);
            DeleteDC(self.dc);
        }
    }
}

/// Create a face and read back its cell height and internal leading. A face that
/// cannot be created falls back to the stock GUI font: a missing font must not
/// cost the HUD all of its text.
fn load_font(dc: Hdc, face: &str, height: i32, weight: i32, family: u32, em: f32) -> Font {
    let face_w: Vec<u16> = face.encode_utf16().chain(std::iter::once(0)).collect();
    // SAFETY: the DC is live; the face string is NUL-terminated and outlives the
    // call. Every other argument is a documented constant.
    unsafe {
        let mut handle = CreateFontW(
            height,
            0,
            0,
            0,
            weight,
            0,
            0,
            0,
            DEFAULT_CHARSET,
            OUT_TT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            ANTIALIASED_QUALITY,
            family,
            face_w.as_ptr(),
        );
        let mut owned = true;
        if handle.is_null() {
            owned = false;
            handle = GetStockObject(DEFAULT_GUI_FONT);
        }

        let previous = SelectObject(dc, handle);
        let mut metrics: TextMetricsW = std::mem::zeroed();
        let (cell, leading) = if GetTextMetricsW(dc, &mut metrics) != 0 {
            (metrics.height, metrics.internal_leading)
        } else {
            (-height, 0)
        };
        SelectObject(dc, previous);

        Font { handle, owned, cell: cell.max(1), em, leading: leading.max(0) }
    }
}
