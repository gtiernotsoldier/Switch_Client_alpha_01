// text.rs — the text rasteriser: GDI draws into a scratch DIB, this copies the
// result into the premultiplied BGRA canvas.
//
// Why GDI at all: the card needs a real face (Segoe UI for names, Consolas for
// the mono chip and the value column), and a hand-baked bitmap font would have
// to be embedded in the binary and would fall apart on any string the game
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

use std::ffi::c_void;

use crate::draw::Canvas;
use crate::win::*;

/// Wide enough for a full 24-byte name or value field at any sane face size;
/// taller than any cell we ask for, so the coverage scan never clips a glyph.
const SCRATCH_W: i32 = 512;
const SCRATCH_H: i32 = 64;

const UI_FACE: &str = "Segoe UI";
const MONO_FACE: &str = "Consolas";
/// Negative height = character height in pixels (not cell height), which is the
/// value a designer reasons about. 15 px for the UI face, 14 px for the mono one
/// keeps the value column a touch smaller than the names, as the old card did.
const UI_HEIGHT: i32 = -15;
const MONO_HEIGHT: i32 = -14;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FontId {
    /// Row names.
    Ui,
    /// The wordmark and the "D" mark — same face, semibold.
    UiBold,
    /// The version chip and the right-aligned value column.
    Mono,
}

struct Font {
    handle: Hfont,
    /// Stock fallback objects must never be deleted.
    owned: bool,
    height: i32,
}

/// The scratch DC, its DIB and the three cached fonts.
pub struct TextEngine {
    dc: Hdc,
    dib: *mut c_void,
    prev: *mut c_void,
    bits: *mut u8,
    ui: Font,
    bold: Font,
    mono: Font,
}

impl TextEngine {
    pub fn new() -> Result<TextEngine, String> {
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

            let ui = load_font(dc, UI_FACE, UI_HEIGHT, FW_NORMAL, UI_PITCH_FAMILY);
            let bold = load_font(dc, UI_FACE, UI_HEIGHT, FW_SEMIBOLD, UI_PITCH_FAMILY);
            let mono = load_font(dc, MONO_FACE, MONO_HEIGHT, FW_NORMAL, MONO_PITCH_FAMILY);

            Ok(TextEngine {
                dc,
                dib,
                prev,
                bits: bits as *mut u8,
                ui,
                bold,
                mono,
            })
        }
    }

    fn font(&self, id: FontId) -> &Font {
        match id {
            FontId::Ui => &self.ui,
            FontId::UiBold => &self.bold,
            FontId::Mono => &self.mono,
        }
    }

    /// Cell height of a face: what the layout budgets per row.
    pub fn height(&self, id: FontId) -> i32 {
        self.font(id).height
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

    /// Width of `text` drawn with `track` extra pixels after every character
    /// (the old `measureTracked`: the trailing track is not counted).
    pub fn measure_tracked(&self, id: FontId, text: &str, track: f32) -> i32 {
        let mut w = 0.0f32;
        let mut n = 0usize;
        for ch in text.chars() {
            let mut one = [0u8; 4];
            w += self.measure(id, ch.encode_utf8(&mut one)) as f32 + track;
            n += 1;
        }
        if n == 0 {
            0
        } else {
            (w - track).round().max(0.0) as i32
        }
    }

    /// Draw `text` with its cell's top-left at (x, y). Returns the advance width.
    pub fn draw(
        &mut self,
        canvas: &mut Canvas,
        id: FontId,
        text: &str,
        x: i32,
        y: i32,
        colour: u32,
    ) -> i32 {
        if text.is_empty() {
            return 0;
        }
        let height = self.height(id).min(SCRATCH_H);
        // Two extra columns: a glyph may overhang its advance slightly (italic
        // bearings, hinting), and clipping that off would shave the last stem.
        let width = (self.measure(id, text) + 2).min(SCRATCH_W);
        if height <= 0 || width <= 0 {
            return 0;
        }

        // SAFETY: the scratch DIB storage is SCRATCH_W x SCRATCH_H x 4 bytes and
        // the loop below stays inside it; every handle is live.
        unsafe {
            // Only the rows this run can touch are cleared — the whole scratch
            // would be 128 KiB of memset per text run.
            std::ptr::write_bytes(self.bits, 0, (SCRATCH_W * height * 4) as usize);

            SelectObject(self.dc, self.font(id).handle);
            SetTextColor(self.dc, 0x00FF_FFFF);
            let buf: Vec<u16> = text.encode_utf16().collect();
            if TextOutW(self.dc, 0, 0, buf.as_ptr(), buf.len() as i32) == 0 {
                return 0;
            }

            for row in 0..height {
                let row_base = (row * SCRATCH_W * 4) as isize;
                for col in 0..width {
                    let off = row_base + (col * 4) as isize;
                    let b = *self.bits.offset(off) as u32;
                    let g = *self.bits.offset(off + 1) as u32;
                    let r = *self.bits.offset(off + 2) as u32;
                    let coverage = b.max(g).max(r);
                    if coverage > 0 {
                        canvas.blend_pixel(x + col, y + row, colour, coverage as f32 / 255.0);
                    }
                }
            }
        }

        self.measure(id, text)
    }

    /// Draw `text` one character at a time with `track` extra pixels between
    /// characters, asking `colour` for each one's ARGB. Returns the advance.
    pub fn draw_tracked<F>(
        &mut self,
        canvas: &mut Canvas,
        id: FontId,
        text: &str,
        x: i32,
        y: i32,
        track: f32,
        mut colour: F,
    ) -> i32
    where
        F: FnMut(usize, char) -> u32,
    {
        let mut cx = x as f32;
        let mut count = 0usize;
        for (i, ch) in text.chars().enumerate() {
            let mut one = [0u8; 4];
            let s = ch.encode_utf8(&mut one);
            let w = self.draw(canvas, id, s, cx.round() as i32, y, colour(i, ch));
            cx += w as f32 + track;
            count = i + 1;
        }
        if count == 0 {
            0
        } else {
            (cx - track).round() as i32 - x
        }
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
            for font in [&self.ui, &self.bold, &self.mono] {
                if font.owned && !font.handle.is_null() {
                    DeleteObject(font.handle);
                }
            }
            DeleteObject(self.dib);
            DeleteDC(self.dc);
        }
    }
}

/// Create a face and read back its cell height. A face that cannot be created
/// falls back to the stock GUI font: a missing font must not cost the HUD all of
/// its text.
fn load_font(dc: Hdc, face: &str, height: i32, weight: i32, family: u32) -> Font {
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
        let measured = if GetTextMetricsW(dc, &mut metrics) != 0 {
            metrics.height
        } else {
            -height
        };
        SelectObject(dc, previous);

        Font { handle, owned, height: measured.max(1) }
    }
}
