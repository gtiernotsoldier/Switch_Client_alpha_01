// text.rs — the text rasteriser: GDI draws into a scratch DIB, this copies the
// result into the premultiplied BGRA canvas.
//
// Why GDI at all: the HUD needs a real face (Segoe UI for everything but the
// numbers, Consolas for the mono ones), and a hand-baked bitmap font would have
// to be embedded in the binary and would fall apart on any string the game hands
// us. GDI is already linked for CreateDIBSection, and it is not a third party
// dependency.
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
// The mockup asks for Inter with a `system-ui … Microsoft YaHei` fallback and
// JetBrains Mono with `ui-monospace` (Consolas on Windows). Neither webfont is
// installed on the build machine, so this uses the stylesheet's own fallbacks:
// **Segoe UI** for the UI text and **Consolas** for `ui-monospace`.
//
// A CSS `font-size` is the em size, and a negative LOGFONT height is also the
// character (em) height, so the two numbers are the same — only the integer
// rounding differs:
//
//   element                      CSS size   face                lfHeight
//   module name (`.al .t`)        12.5 px   Segoe UI 600           -13
//   DOPPEL (`.word`)              13.5 px   Segoe UI 700           -14
//   the D mark (`.mark`)          15   px   Segoe UI 800           -15
//   pill label (`.s-name`)         9.5 px   Segoe UI 700           -10
//   values / count (`#cnt`)        11  px   Consolas 400 / 600     -11
//   slogan (`.slogan`)             9   px   Consolas 400            -9
//
// `em_px` hands the *unrounded* CSS size back to `hud.rs`, which is what the
// stylesheet's em-based letter spacing is computed from (`0.4em` of 13.5 px is
// 5.4 px, and rounding that to 5 would visibly shorten the wordmark).

use std::ffi::c_void;

use crate::draw::Canvas;
use crate::win::*;

/// Wide enough for a full 24-byte name or value field at any sane face size;
/// taller than any cell we ask for, so the coverage scan never clips a glyph.
const SCRATCH_W: i32 = 512;
const SCRATCH_H: i32 = 64;

const UI_FACE: &str = "Segoe UI";
const MONO_FACE: &str = "Consolas";

const UI_HEIGHT: i32 = -13;
const BOLD_HEIGHT: i32 = -14;
const MARK_HEIGHT: i32 = -15;
const TINY_HEIGHT: i32 = -10;
const MONO_HEIGHT: i32 = -11;
const SLOGAN_HEIGHT: i32 = -9;

/// The CSS sizes the faces above were created from, kept unrounded so that
/// `letter-spacing: .4em` can be turned into pixels exactly.
const UI_EM: f32 = 12.5;
const BOLD_EM: f32 = 13.5;
const MARK_EM: f32 = 15.0;
const TINY_EM: f32 = 9.5;
const MONO_EM: f32 = 11.0;
const SLOGAN_EM: f32 = 9.0;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FontId {
    /// Module names — `.al .t`, 12.5 px semibold.
    Ui,
    /// The DOPPEL wordmark — `.word`, 13.5 px bold.
    UiBold,
    /// The mirrored "D" — `.mark`, 15 px extrabold.
    Mark,
    /// The status pill's label — `.s-name`, 9.5 px bold.
    UiTiny,
    /// Module values — Consolas 11 px.
    Mono,
    /// The status pill's count — `#cnt`, Consolas 11 px semibold.
    MonoBold,
    /// The mono slogan — `.slogan`, 9 px.
    Slogan,
}

struct Font {
    handle: Hfont,
    /// Stock fallback objects must never be deleted.
    owned: bool,
    height: i32,
    /// The CSS font-size this face was asked for, in pixels.
    em: f32,
}

/// The scratch DC, its DIB and the cached faces.
pub struct TextEngine {
    dc: Hdc,
    dib: *mut c_void,
    prev: *mut c_void,
    bits: *mut u8,
    ui: Font,
    bold: Font,
    mark: Font,
    tiny: Font,
    mono: Font,
    mono_bold: Font,
    slogan: Font,
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

            let ui = load_font(dc, UI_FACE, UI_HEIGHT, FW_SEMIBOLD, UI_PITCH_FAMILY, UI_EM);
            let bold = load_font(dc, UI_FACE, BOLD_HEIGHT, FW_BOLD, UI_PITCH_FAMILY, BOLD_EM);
            let mark = load_font(dc, UI_FACE, MARK_HEIGHT, FW_EXTRABOLD, UI_PITCH_FAMILY, MARK_EM);
            let tiny = load_font(dc, UI_FACE, TINY_HEIGHT, FW_BOLD, UI_PITCH_FAMILY, TINY_EM);
            let mono = load_font(dc, MONO_FACE, MONO_HEIGHT, FW_NORMAL, MONO_PITCH_FAMILY, MONO_EM);
            let mono_bold =
                load_font(dc, MONO_FACE, MONO_HEIGHT, FW_SEMIBOLD, MONO_PITCH_FAMILY, MONO_EM);
            let slogan =
                load_font(dc, MONO_FACE, SLOGAN_HEIGHT, FW_NORMAL, MONO_PITCH_FAMILY, SLOGAN_EM);

            Ok(TextEngine {
                dc,
                dib,
                prev,
                bits: bits as *mut u8,
                ui,
                bold,
                mark,
                tiny,
                mono,
                mono_bold,
                slogan,
            })
        }
    }

    fn font(&self, id: FontId) -> &Font {
        match id {
            FontId::Ui => &self.ui,
            FontId::UiBold => &self.bold,
            FontId::Mark => &self.mark,
            FontId::UiTiny => &self.tiny,
            FontId::Mono => &self.mono,
            FontId::MonoBold => &self.mono_bold,
            FontId::Slogan => &self.slogan,
        }
    }

    /// Cell height of a face: what the layout budgets per row.
    pub fn height(&self, id: FontId) -> i32 {
        self.font(id).height
    }

    /// The CSS font-size behind a face, for `letter-spacing: <em>` maths.
    pub fn em_px(&self, id: FontId) -> f32 {
        self.font(id).em
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
    /// (`letter-spacing`; the trailing track is not counted, which is what the
    /// stylesheet's `margin-right: -.4em` compensates for).
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
        self.blit(canvas, id, text, x, y, colour, false)
    }

    /// Draw `text` mirrored about its own box, the way `transform: scaleX(-1)`
    /// flips the mockup's accent "D". The run keeps its position and advance; the
    /// coverage is read back to front.
    pub fn draw_mirrored(
        &mut self,
        canvas: &mut Canvas,
        id: FontId,
        text: &str,
        x: i32,
        y: i32,
        colour: u32,
    ) -> i32 {
        self.blit(canvas, id, text, x, y, colour, true)
    }

    fn blit(
        &mut self,
        canvas: &mut Canvas,
        id: FontId,
        text: &str,
        x: i32,
        y: i32,
        colour: u32,
        mirror: bool,
    ) -> i32 {
        let (width, height) = match self.rasterise(id, text) {
            Some(size) => size,
            None => return 0,
        };

        // SAFETY: the scratch holds SCRATCH_W x SCRATCH_H pixels and every column
        // read below is inside `width` (<= SCRATCH_W) and every row inside
        // `height` (<= SCRATCH_H). The font handle stays live for the engine.
        unsafe {
            for row in 0..height {
                let row_base = (row * SCRATCH_W * 4) as isize;
                // The advance box is the run width less the two overhang columns.
                let advance = width - 2;
                for col in 0..width {
                    // A mirrored run reads the coverage back to front inside its
                    // own advance box, so the glyph flips in place.
                    let src = if mirror { advance - 1 - col } else { col };
                    if src < 0 || src >= width {
                        continue;
                    }
                    let off = row_base + (src * 4) as isize;
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

    /// Draw `text` scaled about the centre of its own box — the status pill's
    /// count "pop" (`@keyframes pop { 45% { transform: scale(1.45) } }`). The
    /// coverage is resampled bilinearly, because the face is 11 px and a
    /// nearest-neighbour scale of a 1.45x jump is visibly stepped on a digit.
    /// Returns the unscaled advance, so the caller's layout does not move.
    pub fn draw_scaled(
        &mut self,
        canvas: &mut Canvas,
        id: FontId,
        text: &str,
        x: i32,
        y: i32,
        scale: f32,
        colour: u32,
    ) -> i32 {
        if scale <= 0.0 {
            return 0;
        }
        if (scale - 1.0).abs() < 0.01 {
            return self.draw(canvas, id, text, x, y, colour);
        }
        let advance = self.measure(id, text);
        let (width, height) = match self.rasterise(id, text) {
            Some(size) => size,
            None => return 0,
        };

        let out_w = ((width as f32) * scale).ceil().max(1.0) as i32;
        let out_h = ((height as f32) * scale).ceil().max(1.0) as i32;
        // Scale about the run's centre: the box keeps its place in the pill.
        let cx = x as f32 + width as f32 / 2.0;
        let cy = y as f32 + height as f32 / 2.0;
        let x0 = (cx - out_w as f32 / 2.0).round() as i32;
        let y0 = (cy - out_h as f32 / 2.0).round() as i32;

        for dy in 0..out_h {
            for dx in 0..out_w {
                let sx = (dx as f32 + 0.5) / scale - 0.5;
                let sy = (dy as f32 + 0.5) / scale - 0.5;
                let coverage = self.sample(sx, sy, width, height);
                if coverage > 0.0 {
                    canvas.blend_pixel(x0 + dx, y0 + dy, colour, coverage);
                }
            }
        }

        advance
    }

    /// Bilinear coverage of the scratch at a fractional position, 0 outside the
    /// run. Only the first `width` columns of each of the `height` rows were
    /// cleared by `rasterise`, which is exactly the sampled area.
    fn sample(&self, x: f32, y: f32, width: i32, height: i32) -> f32 {
        if x < -1.0 || y < -1.0 || x > width as f32 || y > height as f32 {
            return 0.0;
        }
        let x0 = x.floor();
        let y0 = y.floor();
        let fx = x - x0;
        let fy = y - y0;
        let (x0, y0) = (x0 as i32, y0 as i32);

        // SAFETY: guarded by the bounds check above plus the per-tap check below.
        let tap = |px: i32, py: i32| -> f32 {
            if px < 0 || py < 0 || px >= width || py >= height {
                return 0.0;
            }
            let off = (py * SCRATCH_W + px) as isize * 4;
            unsafe {
                let b = *self.bits.offset(off) as f32;
                let g = *self.bits.offset(off + 1) as f32;
                let r = *self.bits.offset(off + 2) as f32;
                b.max(g).max(r) / 255.0
            }
        };

        let top = tap(x0, y0) * (1.0 - fx) + tap(x0 + 1, y0) * fx;
        let bottom = tap(x0, y0 + 1) * (1.0 - fx) + tap(x0 + 1, y0 + 1) * fx;
        top * (1.0 - fy) + bottom * fy
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

    /// Clear the scratch and ask GDI for one run, white on transparent. Returns
    /// the run's box, or None when there is nothing to draw.
    ///
    /// Only the columns this run can touch are cleared: a glyph is ~15 px wide
    /// inside a 512 px scratch row, and the HUD now draws tracked text (one call
    /// per character), so clearing the whole row would be ~34x the work.
    fn rasterise(&self, id: FontId, text: &str) -> Option<(i32, i32)> {
        if text.is_empty() {
            return None;
        }
        let height = self.height(id).min(SCRATCH_H);
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
            for font in [
                &self.ui,
                &self.bold,
                &self.mark,
                &self.tiny,
                &self.mono,
                &self.mono_bold,
                &self.slogan,
            ] {
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
        let measured = if GetTextMetricsW(dc, &mut metrics) != 0 {
            metrics.height
        } else {
            -height
        };
        SelectObject(dc, previous);

        Font { handle, owned, height: measured.max(1), em }
    }
}
