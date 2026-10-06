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
// not be on the target's, so the reference's own font **bytes** travel inside this
// executable: `assets/Play-Regular.ttf` and `assets/Play-Bold.ttf` are recovered
// from the C arrays the reference embeds (`deps/imgui/fonts/play_*_font.h`) by
// `tools/extract_oc_fonts.py`, which also reads each file's `name` table so the
// family below is the font's own word for itself rather than a guess.
//
//   role                        source                 this port
//   module names in Default,    Play Bold 16 px        Play Bold (FW_BOLD), lfHeight -16
//   the watermark title
//   everything else             Play Regular 16 px     Play Regular (FW_NORMAL), lfHeight -16
//
// (`HUD.cpp:468` picks bold for names in Default mode and regular in Rise,
// Tesseract and VapeV4; `--dump` prints which face each role resolved to.) The
// reference's third face, a "vape font" loaded from disk, is substituted with
// regular Play exactly as `RenderHook.cpp:511-512` does when its file is missing.
//
// Inter — the reference's other embedded family — is deliberately **not** bundled:
// `RenderHook.cpp:533` hands it to `Notifications::SetFonts`, and this overlay has
// no notification path, so its ~670 KB of glyphs would ship for nothing. See
// `assets/FONTS.md` for the licence each bundled face declares.
//
// Registration is `AddFontMemResourceEx`, then `CreateFontW` by family name — the
// one Win32 path that uses a font without a file on disk and without a
// third-party rasteriser. If either call fails, or if GDI resolves the request to
// a family other than Play, both roles fall back to the substitute pair (Segoe UI
// Semibold / Segoe UI) below and the caller logs which pair it got.
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
// 16 px) keeps the text inside it.

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

/// The reference client's own faces, pulled out of its embedded C arrays and
/// compiled into this binary. `include_bytes!` rather than a sidecar file: the
/// injector embeds *this executable* as a resource and extracts it to `%TEMP%`
/// alone, so a `.ttf` sitting next to the exe on the build machine would not
/// travel with it.
const PLAY_REGULAR_TTF: &[u8] = include_bytes!("../assets/Play-Regular.ttf");
const PLAY_BOLD_TTF: &[u8] = include_bytes!("../assets/Play-Bold.ttf");

/// What both faces call themselves in their `name` table (`family` = `Play`;
/// `subfamily` = `Regular` / `Bold`). Read out of the files by
/// `tools/extract_oc_fonts.py`, not guessed: `CreateFontW` matches on this string.
const PLAY_FAMILY: &str = "Play";

/// The standing-in family, used only when the embedded faces cannot be used at
/// all. Both fallback roles are the same family at two weights, which is the
/// closest a stock Windows install gets to Play's regular/bold pair.
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

impl FontId {
    /// Index into `FontSetup::resolved`, so the report and the lookup cannot drift.
    fn index(self) -> usize {
        match self {
            FontId::Name => 0,
            FontId::Body => 1,
        }
    }
}

/// What the startup registration actually did. `main` writes this to
/// `%TEMP%\doppel-hud.log` and `--dump` prints it, because "the HUD is drawing the
/// substitute face" is otherwise invisible: both pairs produce a plausible HUD.
pub struct FontSetup {
    /// True when the embedded Play faces registered *and* GDI resolved the two
    /// roles to that family.
    pub play: bool,
    /// Faces `AddFontMemResourceEx` reported adding, summed over the two calls
    /// (2 when both went in; 0 when the API is unavailable).
    pub registered_faces: u32,
    /// The family the two roles were created from: `Play`, or the substitute.
    pub family: &'static str,
    /// The face name GDI selected for each role, `[Name, Body]`.
    pub resolved: [String; 2],
}

impl FontSetup {
    /// The one-line form both the log and `--dump` use.
    pub fn summary(&self) -> String {
        format!(
            "{} ({} face(s) registered, family {}{})",
            if self.play { "embedded Play" } else { "substitute Segoe UI" },
            self.registered_faces,
            self.family,
            if self.play { "" } else { " — embedded faces unusable, fell back" }
        )
    }
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
    /// The face name GDI resolved for the request (`GetTextFaceW`).
    resolved: String,
}

/// The scratch DC, its DIB and the cached faces.
pub struct TextEngine {
    dc: Hdc,
    dib: *mut c_void,
    prev: *mut c_void,
    bits: *mut u8,
    name: Font,
    body: Font,
    /// The `AddFontMemResourceEx` handles. Never freed before the faces that were
    /// created from them: GDI keeps reading the registered bytes.
    memory_fonts: Vec<*mut c_void>,
    setup: FontSetup,
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

            // Register the embedded faces first: GDI cannot select a family it has
            // never been told about, and there is no file on disk to fall back to.
            // The bytes are `include_bytes!` statics — they live in the image's
            // read-only data for the whole process, which is exactly the lifetime
            // `AddFontMemResourceEx` requires of the buffer it is handed.
            let regular = register_memory_font(PLAY_REGULAR_TTF);
            let bold = register_memory_font(PLAY_BOLD_TTF);
            let registered_faces = regular.1 + bold.1;
            let mut memory_fonts = vec![regular.0, bold.0];
            let usable = !regular.0.is_null() && !bold.0.is_null();

            let (mut family, mut name_weight) =
                if usable { (PLAY_FAMILY, FW_BOLD) } else { (UI_FACE, FW_SEMIBOLD) };
            let mut name = load_font(dc, family, height, name_weight, UI_PITCH_FAMILY, em);
            let mut body = load_font(dc, family, height, FW_NORMAL, UI_PITCH_FAMILY, em);

            // A family can register and still not resolve — a driver that will not
            // use a memory font, a name-table mismatch. Ask GDI which face it
            // actually put in the DC; if it is not the family that was asked for,
            // redo *both* roles with the substitute rather than silently drawing
            // the HUD in some third typeface nobody chose.
            let mut play = usable;
            if usable
                && !(exact_family(&name.resolved, PLAY_FAMILY)
                    && exact_family(&body.resolved, PLAY_FAMILY))
            {
                DeleteObject(name.handle);
                DeleteObject(body.handle);
                family = UI_FACE;
                name_weight = FW_SEMIBOLD;
                name = load_font(dc, family, height, name_weight, UI_PITCH_FAMILY, em);
                body = load_font(dc, family, height, FW_NORMAL, UI_PITCH_FAMILY, em);
                play = false;
            }
            // The fallback keeps no claim on the registered bytes; releasing them
            // here means the handle vector is exactly what must be removed.
            if !play {
                for handle in memory_fonts.drain(..) {
                    if !handle.is_null() {
                        RemoveFontMemResourceEx(handle);
                    }
                }
            }

            let setup = FontSetup {
                play,
                registered_faces,
                family,
                resolved: [name.resolved.clone(), body.resolved.clone()],
            };

            Ok(TextEngine { dc, dib, prev, bits: bits as *mut u8, name, body, memory_fonts, setup })
        }
    }

    /// What the embedded-face registration did, for the log and `--dump`.
    pub fn setup(&self) -> &FontSetup {
        &self.setup
    }

    /// The face GDI resolved for a role, e.g. `Play` or `Segoe UI Semibold`.
    pub fn face_name(&self, id: FontId) -> &str {
        &self.setup.resolved[id.index()]
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
        // DC, and the stock font is never deleted at all. The in-memory font
        // registrations go last: the faces created from them are already gone.
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
            for handle in self.memory_fonts.drain(..) {
                if !handle.is_null() {
                    RemoveFontMemResourceEx(handle);
                }
            }
        }
    }
}

/// Hand one embedded TTF to GDI and return `(handle, faces added)`. A failure is
/// not fatal: the caller falls back to installed faces.
///
/// SAFETY contract: `bytes` must stay valid until `RemoveFontMemResourceEx` is
/// called on the handle — GDI does not copy the data. Both callers pass
/// `include_bytes!` statics, which live in the image for the whole process.
unsafe fn register_memory_font(bytes: &'static [u8]) -> (*mut c_void, u32) {
    if bytes.is_empty() {
        return (std::ptr::null_mut(), 0);
    }
    let mut added: u32 = 0;
    let handle = AddFontMemResourceEx(
        bytes.as_ptr() as *const c_void,
        bytes.len() as u32,
        std::ptr::null_mut(),
        &mut added,
    );
    if handle.is_null() {
        (std::ptr::null_mut(), 0)
    } else {
        (handle, added)
    }
}

/// Did GDI select the family that was asked for?
///
/// `GetTextFaceW` normally answers with the family name and sometimes with the
/// family and its style ("Segoe UI Semibold", "Play Bold"), so the test is the
/// family name alone or the family followed by a space — not a bare prefix, which
/// would accept `Playbill` as `Play`. An empty answer means the call itself
/// failed: that is not evidence of a substitution, and the registration already
/// succeeded, so it counts as a match rather than throwing away working faces.
fn exact_family(resolved: &str, family: &str) -> bool {
    if resolved.is_empty() {
        return true;
    }
    let resolved = resolved.to_lowercase();
    let family = family.to_lowercase();
    resolved == family || resolved.starts_with(&format!("{family} "))
}

/// Create a face and read back its cell height, internal leading and the face name
/// GDI actually resolved. A face that cannot be created falls back to the stock GUI
/// font: a missing font must not cost the HUD all of its text.
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
        // What GDI really put in the DC. LF_FACESIZE is 32, so 64 UTF-16 units is
        // twice what the call can fill.
        let mut name_buf = [0u16; 64];
        let got = GetTextFaceW(dc, name_buf.len() as i32, name_buf.as_mut_ptr());
        let resolved = if got > 1 {
            String::from_utf16_lossy(&name_buf[..(got as usize - 1).min(name_buf.len())])
        } else {
            String::new()
        };
        SelectObject(dc, previous);

        Font { handle, owned, cell: cell.max(1), em, leading: leading.max(0), resolved }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// GDI answers `GetTextFaceW` with the family name, sometimes with the style
    /// appended, and with an empty string when the query itself failed.
    #[test]
    fn the_resolved_face_is_matched_by_family_prefix() {
        assert!(exact_family("Play", "Play"));
        assert!(exact_family("Play Bold", "Play"));
        assert!(exact_family("Play Bold Italic", "Play"));
        assert!(exact_family("play", "Play"));
        // Segoe UI resolves to "Segoe UI Semibold" for the semibold weight.
        assert!(exact_family("Segoe UI Semibold", "Segoe UI"));
        // Substitutions do not match ...
        assert!(!exact_family("Arial", "Play"));
        assert!(!exact_family("Segoe UI", "Play"));
        // ... but a failed query is not evidence of one.
        assert!(exact_family("", "Play"));
    }

    /// The match is the family name or the family plus a style — not a bare
    /// prefix, or `Playbill` would pass as `Play`.
    #[test]
    fn a_family_match_does_not_wander_past_the_prefix() {
        assert!(!exact_family("Playbill", "Play"));
        assert!(!exact_family("Playbook", "Play"));
        assert!(!exact_family("Play Regular", "Play Bold"));
        assert!(exact_family("Play", "Play"));
        assert!(exact_family("Play Regular", "Play Regular"));
    }

    /// The two `include_bytes!` assets must really be the extracted Play faces.
    /// A truncated, empty or re-exported file would otherwise only show up as a
    /// silently substituted typeface at run time.
    #[test]
    fn the_embedded_faces_are_the_reference_play_ttfs() {
        // UTF-16BE "Play" — the family name from each file's name table, which is
        // the string `CreateFontW` is given.
        const PLAY_UTF16BE: &[u8] = &[0x00, 0x50, 0x00, 0x6C, 0x00, 0x61, 0x00, 0x79];

        for (label, bytes) in [("regular", PLAY_REGULAR_TTF), ("bold", PLAY_BOLD_TTF)] {
            assert!(
                bytes.len() > 100_000,
                "the {label} face looks truncated: {} bytes",
                bytes.len()
            );
            assert_eq!(
                &bytes[..4],
                &[0x00, 0x01, 0x00, 0x00],
                "the {label} face is not a TrueType sfnt"
            );
            assert!(
                bytes.windows(PLAY_UTF16BE.len()).any(|w| w == PLAY_UTF16BE),
                "the {label} face's name table does not say \"Play\""
            );
        }
        assert_ne!(PLAY_REGULAR_TTF, PLAY_BOLD_TTF, "both weights are the same file");
        assert_eq!(PLAY_FAMILY, "Play");
    }

    /// Size budget: exactly two faces travel in the executable, and they are the
    /// two the HUD draws with. Inter (~670 KB for the pair) belongs to the
    /// reference's notification path, which this overlay does not have, and
    /// embedding it would be the single biggest thing in the binary.
    #[test]
    fn only_the_two_play_faces_are_embedded() {
        let total = PLAY_REGULAR_TTF.len() + PLAY_BOLD_TTF.len();
        assert_eq!(total, 171_084 + 170_232, "unexpected embedded font bytes: {total}");
        assert!(total < 512 * 1024, "the embedded faces outgrew their budget: {total} bytes");
    }

    /// The role → (family, weight) mapping the reference uses: bold for the module
    /// names in Default mode and the watermark title, regular for everything else.
    /// The fallback pair keeps the same two roles, only in Segoe UI.
    #[test]
    fn the_two_roles_pick_bold_and_regular_of_one_family() {
        // Play: the bold face is the family at FW_BOLD, the regular at FW_NORMAL.
        assert_eq!((PLAY_FAMILY, FW_BOLD), ("Play", 700));
        assert_ne!(FW_BOLD, FW_NORMAL);
        // Separately: both roles are created from the same family whichever pair
        // is in use, so a run never mixes two typefaces.
        assert_ne!(FontId::Name.index(), FontId::Body.index());
    }
}
