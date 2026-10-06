// win.rs — every Win32 declaration the overlay uses, in one place.
//
// D0 kept these next to the window code in main.rs. D1/D2 added a second caller
// (the GDI text rasteriser in text.rs), and two copies of BITMAPINFO /
// CreateDIBSection would be two chances to get the layout wrong, so the
// declarations moved here and both users import the same ones.
//
// Zero third-party dependencies, same rule as the injector: kernel32/user32/gdi32
// through raw extern "system" FFI, with the struct layouts spelled out by hand.
// The declarations below are transcribed from the documented prototypes in
// winuser.h / wingdi.h rather than guessed; the ones that would fail first if a
// character were wrong are UpdateLayeredWindow and CreateDIBSection, and both are
// written out in full with every parameter named so a mismatch is visible at a
// glance rather than hidden behind a type alias.

use std::ffi::c_void;

/// Declare the process DPI aware before any window exists.
///
/// Without it, a scaled display (125 %, 150 % — the default on most laptops) makes Windows
/// virtualise our coordinates and then stretch the result, so a card laid out to whole pixels comes
/// out blurry. Best effort: a system that does not export the call keeps the old behaviour.
pub fn enable_dpi_awareness() {
    #[link(name = "user32")]
    extern "system" {
        fn SetProcessDPIAware() -> i32;
    }
    unsafe {
        let _ = SetProcessDPIAware();
    }
}

// ── Win32 types ─────────────────────────────────────────────────────────────

pub type Hwnd = *mut c_void;
pub type Hinstance = *mut c_void;
pub type Hdc = *mut c_void;
pub type Hbrush = *mut c_void;
pub type Hicon = *mut c_void;
pub type Hcursor = *mut c_void;
pub type Hfont = *mut c_void;
pub type Wparam = usize;
pub type Lparam = isize;
pub type Lresult = isize;

/// `WNDENUMPROC`: returns non-zero to continue the enumeration. `extern "system"`
/// and `unsafe` because the caller passes a raw pointer to its own state through
/// `l_param` — the one place in this crate where a callback crosses the FFI.
pub type EnumWindowsProc = Option<unsafe extern "system" fn(Hwnd, Lparam) -> i32>;

/// The layout structs below carry `#[allow(dead_code)]`: every field exists
/// because Win32 dictates the byte offset, and the padding and reserved ones are
/// only ever zeroed, so the never-read-field lint would otherwise flag perfectly
/// correct FFI. The allowance is per struct on purpose — a crate-wide allow would
/// also hide genuinely dead code.

#[repr(C)]
#[allow(dead_code)]
pub struct Point {
    pub x: i32,
    pub y: i32,
}

/// Win32 `SIZE` and `POINT` are both a pair of LONGs; one type serves both, and
/// `GetTextExtentPoint32W` takes the same layout.
#[repr(C)]
#[allow(dead_code)]
pub struct Size {
    pub cx: i32,
    pub cy: i32,
}

/// Win32 `RECT`: four LONGs, left/top/right/bottom — the order matters and the two
/// corners are *inclusive* in some APIs and exclusive in others, so the rect is
/// only ever converted through one place (`follow::rect_from_corners`).
///
/// Named `RectWin` because `hud::Rect` is the overlay's own origin+size rectangle;
/// two different shapes with the same name would be a silent way to mix them up.
#[repr(C)]
#[allow(dead_code)]
pub struct RectWin {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[repr(C)]
#[allow(dead_code)]
pub struct Msg {
    pub hwnd: Hwnd,
    pub message: u32,
    pub w_param: Wparam,
    pub l_param: Lparam,
    pub time: u32,
    pub pt: Point,
}

#[repr(C)]
#[allow(dead_code)]
pub struct WndClassExW {
    pub cb_size: u32,
    pub style: u32,
    pub lpfn_wnd_proc: Option<unsafe extern "system" fn(Hwnd, u32, Wparam, Lparam) -> Lresult>,
    pub cb_cls_extra: i32,
    pub cb_wnd_extra: i32,
    pub h_instance: Hinstance,
    pub h_icon: Hicon,
    pub h_cursor: Hcursor,
    pub hbr_background: Hbrush,
    pub lpsz_menu_name: *const u16,
    pub lpsz_class_name: *const u16,
    pub h_icon_sm: Hicon,
}

#[repr(C)]
#[allow(dead_code)]
pub struct BitmapInfoHeader {
    pub size: u32,
    pub width: i32,
    pub height: i32,
    pub planes: u16,
    pub bit_count: u16,
    pub compression: u32,
    pub size_image: u32,
    pub x_pels_per_meter: i32,
    pub y_pels_per_meter: i32,
    pub clr_used: u32,
    pub clr_important: u32,
}

/// BITMAPINFO is a header followed by a colour table. We always ask for BI_RGB
/// at 32bpp, where the table is ignored, but the struct still has to have room
/// for at least one entry or the API would read past our stack frame.
#[repr(C)]
#[allow(dead_code)]
pub struct BitmapInfo {
    pub header: BitmapInfoHeader,
    pub colours: [u32; 1],
}

/// BLENDFUNCTION, four bytes in this exact order (BlendOp, BlendFlags,
/// SourceConstantAlpha, AlphaFormat) — it is passed by pointer, so a reordered
/// field would silently change what the compositor does.
#[repr(C)]
#[allow(dead_code)]
pub struct BlendFunction {
    pub blend_op: u8,
    pub blend_flags: u8,
    pub source_constant_alpha: u8,
    pub alpha_format: u8,
}

/// SYSTEMTIME: eight WORDs in this exact order. Only the log uses it, to stamp a
/// line with the machine's *local* time — `std::time` speaks UTC only and no
/// third-party crate is allowed, so one Win32 call is the cheapest correct answer.
#[repr(C)]
#[allow(dead_code)]
pub struct SystemTimeW {
    pub year: u16,
    pub month: u16,
    pub day_of_week: u16,
    pub day: u16,
    pub hour: u16,
    pub minute: u16,
    pub second: u16,
    pub milliseconds: u16,
}

/// TEXTMETRICW: eleven LONGs, then five WCHARs, then five BYTEs. The font
/// rasteriser needs tmHeight (the cell the layout budgets for) and relies on
/// `repr(C)` to place those trailing narrow fields at offsets 44..60.
#[repr(C)]
#[allow(dead_code)]
pub struct TextMetricsW {
    pub height: i32,
    pub ascent: i32,
    pub descent: i32,
    pub internal_leading: i32,
    pub external_leading: i32,
    pub ave_char_width: i32,
    pub max_char_width: i32,
    pub weight: i32,
    pub overhang: i32,
    pub digitized_aspect_x: i32,
    pub digitized_aspect_y: i32,
    pub first_char: u16,
    pub last_char: u16,
    pub default_char: u16,
    pub break_char: u16,
    pub italic: u8,
    pub underlined: u8,
    pub struck_out: u8,
    pub pitch_and_family: u8,
    pub char_set: u8,
}

// ── user32 ──────────────────────────────────────────────────────────────────

#[link(name = "user32")]
extern "system" {
    pub fn RegisterClassExW(wc: *const WndClassExW) -> u16;
    /// Screen metrics. Only the two indices below (`SM_CXSCREEN` / `SM_CYSCREEN`)
    /// are used, to size the overlay's window to the primary monitor.
    pub fn GetSystemMetrics(index: i32) -> i32;
    pub fn CreateWindowExW(
        ex_style: u32,
        class_name: *const u16,
        window_name: *const u16,
        style: u32,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
        parent: Hwnd,
        menu: *mut c_void,
        instance: Hinstance,
        param: *mut c_void,
    ) -> Hwnd;
    pub fn DefWindowProcW(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult;
    pub fn ShowWindow(hwnd: Hwnd, cmd: i32) -> i32;
    /// Move/size a window and/or change its Z-order without activating it. This is
    /// the only call that can *raise* the overlay into the always-on-top band
    /// after creation, and `SWP_NOACTIVATE` is what keeps the game's foreground
    /// rights intact while it does (see README: no-focus is a hard rule).
    pub fn SetWindowPos(
        hwnd: Hwnd,
        insert_after: Hwnd,
        x: i32,
        y: i32,
        cx: i32,
        cy: i32,
        flags: u32,
    ) -> i32;
    /// Reads one 32-bit window word: the extended style (`GWL_EXSTYLE`) is how the
    /// overlay asks "am I still topmost?" without guessing.
    pub fn GetWindowLongW(hwnd: Hwnd, index: i32) -> i32;
    /// Walks the Z-order: `GetWindow(hwnd, GW_HWNDPREV)` is the window directly
    /// above ours, or NULL when we are the topmost window on the desktop.
    pub fn GetWindow(hwnd: Hwnd, cmd: u32) -> Hwnd;
    pub fn PeekMessageW(msg: *mut Msg, hwnd: Hwnd, min: u32, max: u32, remove: u32) -> i32;
    pub fn TranslateMessage(msg: *const Msg) -> i32;
    pub fn DispatchMessageW(msg: *const Msg) -> Lresult;
    pub fn PostQuitMessage(code: i32);
    /// Walk every top-level window. The callback returns non-zero to continue, zero
    /// to stop; it runs on this thread, which is why the DPI awareness set at
    /// startup applies to the `GetWindowRect` calls it makes (`follow.rs`).
    pub fn EnumWindows(callback: EnumWindowsProc, param: Lparam) -> i32;
    /// The process that owns a window — how the follow search tells the game's own
    /// windows from every other application's.
    pub fn GetWindowThreadProcessId(hwnd: Hwnd, pid: *mut u32) -> u32;
    pub fn GetWindowTextW(hwnd: Hwnd, text: *mut u16, max_count: i32) -> i32;
    pub fn IsWindowVisible(hwnd: Hwnd) -> i32;
    /// A minimised window's rect is the classic (-32000, -32000) icon position, so
    /// it is refused rather than used to place the overlay in a desktop corner.
    pub fn IsIconic(hwnd: Hwnd) -> i32;
    /// The window's **client** rect: the drawable area, with (0, 0) at its own
    /// top-left and `right`/`bottom` carrying its size. Unlike `GetWindowRect`
    /// this excludes the title bar, the borders and any menu — which is what an
    /// overlay has to cover, or the HUD sits over the game's own title bar.
    ///
    /// There is deliberately no `GetWindowRect` declaration next to it: the
    /// overlay anchors to what the game *draws into*, and the one caller that ever
    /// needed the whole window (`follow.rs`, before A2's fix) uses this instead.
    /// A future caller that really wants the frame has to add the declaration and
    /// say why.
    pub fn GetClientRect(hwnd: Hwnd, rect: *mut RectWin) -> i32;
    /// Client → screen coordinates. `GetClientRect` is client-local, so this is
    /// the half of the conversion that says *where* the client area is.
    pub fn ClientToScreen(hwnd: Hwnd, point: *mut Point) -> i32;
    pub fn UpdateLayeredWindow(
        hwnd: Hwnd,
        dst_dc: Hdc,
        dst_pos: *const Point,
        size: *const Size,
        src_dc: Hdc,
        src_pos: *const Point,
        key: u32,
        blend: *const BlendFunction,
        flags: u32,
    ) -> i32;
}

// ── gdi32 ───────────────────────────────────────────────────────────────────

#[link(name = "gdi32")]
extern "system" {
    pub fn CreateCompatibleDC(hdc: Hdc) -> Hdc;
    pub fn CreateDIBSection(
        hdc: Hdc,
        info: *const BitmapInfo,
        usage: u32,
        bits: *mut *mut c_void,
        section: *mut c_void,
        offset: u32,
    ) -> *mut c_void;
    pub fn SelectObject(hdc: Hdc, obj: *mut c_void) -> *mut c_void;
    pub fn DeleteObject(obj: *mut c_void) -> i32;
    pub fn DeleteDC(hdc: Hdc) -> i32;
    pub fn CreateFontW(
        height: i32,
        width: i32,
        escapement: i32,
        orientation: i32,
        weight: i32,
        italic: u32,
        underline: u32,
        strike_out: u32,
        char_set: u32,
        out_precision: u32,
        clip_precision: u32,
        quality: u32,
        pitch_and_family: u32,
        face: *const u16,
    ) -> Hfont;
    pub fn GetStockObject(index: i32) -> *mut c_void;
    /// Registers a font that lives in memory rather than in a file, and returns a
    /// handle the caller keeps for as long as the bytes must stay valid. The
    /// overlay uses this so the embedded Play TTFs never have to be written to
    /// disk next to the executable (see text.rs).
    pub fn AddFontMemResourceEx(
        font_data: *const c_void,
        size: u32,
        reserved: *mut c_void,
        fonts_added: *mut u32,
    ) -> *mut c_void;
    /// Releases what `AddFontMemResourceEx` registered. Called once, on drop.
    pub fn RemoveFontMemResourceEx(handle: *mut c_void) -> i32;
    /// The face name GDI actually selected into the DC. This is how the overlay
    /// proves whether `CreateFontW("Play", ...)` picked the registered family or
    /// silently substituted something else.
    pub fn GetTextFaceW(hdc: Hdc, count: i32, face: *mut u16) -> i32;
    /// Maps characters to the glyph indices of the DC's currently selected font. With
    /// `GGI_MARK_NONEXISTING_GLYPHS` a character the face has no glyph for comes back as
    /// `GLYPH_MISSING` (0xFFFF) — the mechanical answer to "is this face really drawing these
    /// characters, or a row of boxes?". Registration success alone proves nothing about coverage.
    pub fn GetGlyphIndicesW(
        hdc: Hdc,
        text: *const u16,
        count: i32,
        indices: *mut u16,
        flags: u32,
    ) -> u32;
    pub fn GetTextMetricsW(hdc: Hdc, metrics: *mut TextMetricsW) -> i32;
    pub fn GetTextExtentPoint32W(hdc: Hdc, text: *const u16, count: i32, size: *mut Size) -> i32;
    pub fn TextOutW(hdc: Hdc, x: i32, y: i32, text: *const u16, count: i32) -> i32;
    pub fn SetTextColor(hdc: Hdc, colour: u32) -> u32;
    pub fn SetBkMode(hdc: Hdc, mode: i32) -> i32;
}

// ── kernel32 ────────────────────────────────────────────────────────────────

#[link(name = "kernel32")]
extern "system" {
    pub fn GetModuleHandleW(name: *const u16) -> Hinstance;
    /// Fills in the system time in the machine's local zone — the overlay's log
    /// timestamp. No return value, so there is nothing to check.
    pub fn GetLocalTime(time: *mut SystemTimeW);
}

// ── constants ───────────────────────────────────────────────────────────────

pub const WS_POPUP: u32 = 0x8000_0000;
/// **The style the overlay cannot be seen without.** A plain layered window sits
/// in the desktop's normal Z-order band, so as soon as the game has focus it
/// covers the HUD completely. `WS_EX_TOPMOST` puts the overlay in the
/// always-on-top band instead; `--no-topmost` leaves it off.
pub const WS_EX_TOPMOST: u32 = 0x0000_0008;
pub const WS_EX_TRANSPARENT: u32 = 0x0000_0020;
pub const WS_EX_TOOLWINDOW: u32 = 0x0000_0080;
pub const WS_EX_LAYERED: u32 = 0x0008_0000;
pub const WS_EX_NOACTIVATE: u32 = 0x0800_0000;

/// `SetWindowPos`'s `hWndInsertAfter`: the pseudo-handle `(HWND)-1` that names the
/// top of the always-on-top band. Not a real window, hence the odd definition.
pub const HWND_TOPMOST: Hwnd = -1isize as Hwnd;

/// `SetWindowPos` flags. `SWP_NOACTIVATE` keeps the game's foreground rights (the
/// one thing this process must never take), `SWP_NOOWNERZORDER` stops an owner
/// reshuffle nobody asked for, and `SWP_SHOWWINDOW` is what makes the raise also
/// the show — a hidden window cannot be seen however topmost it is.
pub const SWP_NOSIZE: u32 = 0x0001;
pub const SWP_NOMOVE: u32 = 0x0002;
/// `SWP_NOZORDER` — move/resize without touching the Z-order. Used when the overlay
/// is deliberately NOT topmost (`--no-topmost`): `HWND_TOPMOST` would silently
/// override that choice on every follow step.
pub const SWP_NOZORDER: u32 = 0x0004;
pub const SWP_NOACTIVATE: u32 = 0x0010;
pub const SWP_SHOWWINDOW: u32 = 0x0040;
pub const SWP_NOOWNERZORDER: u32 = 0x0200;

/// `GetWindowLongW` index for the extended style word, and `GetWindow`'s
/// "the window directly above me in the Z-order" selector.
pub const GWL_EXSTYLE: i32 = -20;
pub const GW_HWNDPREV: u32 = 3;

/// Render the extended-style word as the flag names it holds, so the log line says
/// `WS_EX_LAYERED|WS_EX_TOPMOST|...` instead of a bare hex word nobody can check.
/// Unknown bits are kept as hex: a style we did not ask for is worth seeing.
pub fn describe_ex_style(bits: u32) -> String {
    /// In the order a reader checks them: the ones the overlay needs first.
    const NAMED: &[(u32, &str)] = &[
        (WS_EX_TOPMOST, "WS_EX_TOPMOST"),
        (WS_EX_LAYERED, "WS_EX_LAYERED"),
        (WS_EX_TRANSPARENT, "WS_EX_TRANSPARENT"),
        (WS_EX_TOOLWINDOW, "WS_EX_TOOLWINDOW"),
        (WS_EX_NOACTIVATE, "WS_EX_NOACTIVATE"),
    ];

    let mut names: Vec<String> = Vec::new();
    let mut seen = 0u32;
    for (bit, name) in NAMED {
        if bits & bit != 0 {
            names.push((*name).to_string());
            seen |= bit;
        }
    }
    let unknown = bits & !seen;
    if unknown != 0 {
        names.push(format!("0x{unknown:08X}"));
    }
    if names.is_empty() {
        return "none".to_string();
    }
    names.join("|")
}

/// Does the overlay need its topmost flag re-applied?
///
/// Two cheap signals, passed in as plain values so the rule can be tested without
/// a window:
///
///   * `ex_style` — the window's current extended style. `WS_EX_TOPMOST` missing
///     means something cleared it (a shell, a fullscreen transition, another
///     always-on-top window taking the band over).
///   * `window_above` — `GetWindow(hwnd, GW_HWNDPREV) != NULL`: even with the bit
///     still set, something has since been raised above us.
///
/// Either one is reason enough. Nothing else is: re-applying on a timer that fires
/// regardless would mean a `SetWindowPos` every two seconds for the whole life of
/// the overlay, and this process is a guest on the game's desktop.
pub fn topmost_lost(ex_style: u32, window_above: bool) -> bool {
    ex_style & WS_EX_TOPMOST == 0 || window_above
}

pub const WM_DESTROY: u32 = 0x0002;
pub const WM_CLOSE: u32 = 0x0010;
pub const WM_NCHITTEST: u32 = 0x0084;
pub const WM_QUIT: u32 = 0x0012;

/// `PeekMessageW`'s remove flag — the frame loop drains the queue itself.
pub const PM_REMOVE: u32 = 0x0001;

/// "The click belongs to whatever is underneath." HTTRANSPARENT is the only
/// negative HT* value, hence the odd-looking literal.
pub const HTTRANSPARENT: Lresult = -1;

/// SW_SHOWNOACTIVATE. Deliberately not SW_SHOW: see the header note on focus.
pub const SW_SHOWNOACTIVATE: i32 = 4;

pub const DIB_RGB_COLORS: u32 = 0;
pub const BI_RGB: u32 = 0;

/// `GetGlyphIndicesW` flag: report a character the face cannot draw as [GLYPH_MISSING] instead of
/// substituting a default glyph, and the value it reports.
pub const GGI_MARK_NONEXISTING_GLYPHS: u32 = 0x0001;
pub const GLYPH_MISSING: u16 = 0xFFFF;

pub const AC_SRC_OVER: u8 = 0x00;
pub const AC_SRC_ALPHA: u8 = 0x01;
pub const ULW_ALPHA: u32 = 0x02;

// `GetSystemMetrics` indices. The primary monitor is the one whose origin is
// (0, 0) by definition, so only its *size* has to be asked for; there is no
// `SM_XSCREEN`/`SM_YSCREEN` — only the virtual screen has an origin
// (`SM_XVIRTUALSCREEN`/`SM_YVIRTUALSCREEN`, 76/77), and the overlay deliberately
// covers the primary monitor rather than the whole desktop.
pub const SM_CXSCREEN: i32 = 0;
pub const SM_CYSCREEN: i32 = 1;

// GDI text: ANTIALIASED_QUALITY (grayscale) is the only quality usable here —
// ClearType is subpixel, so it tints edge pixels, and those cannot be
// premultiplied into a layered window without colour fringing.
pub const ANTIALIASED_QUALITY: u32 = 4;
pub const DEFAULT_CHARSET: u32 = 1;
pub const OUT_TT_PRECIS: u32 = 4;
pub const CLIP_DEFAULT_PRECIS: u32 = 0;
/// The two weights the ported HUD's faces are created at: names semibold, the rest
/// regular (`text.rs`).
pub const FW_NORMAL: i32 = 400;
pub const FW_SEMIBOLD: i32 = 600;
/// `FW_BOLD` — the weight the reference loads Play at for its bold role
/// (`RenderHook.cpp:430`; the face itself declares `Subfamily: Bold`).
pub const FW_BOLD: i32 = 700;
pub const TRANSPARENT: i32 = 1;
/// DEFAULT_GUI_FONT — the fallback when a named face cannot be created.
pub const DEFAULT_GUI_FONT: i32 = 17;
/// DEFAULT_PITCH | FF_DONTCARE.
pub const UI_PITCH_FAMILY: u32 = 0x00;

#[cfg(test)]
mod tests {
    use super::*;

    /// The flag word the overlay asks for, and the two ways "the game is above us"
    /// can show up. Both are pure numbers here, so the re-assert rule is checked
    /// without creating a window or a game.
    #[test]
    fn topmost_is_reasserted_only_when_it_was_lost() {
        let wanted = WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT;

        // Intact: the bit is there and nothing sits above us. Leave it alone —
        // this is the case that must not produce a SetWindowPos every 2 s.
        assert!(!topmost_lost(wanted, false));

        // The bit was cleared (a shell or a fullscreen transition did it): raise.
        assert!(topmost_lost(wanted & !WS_EX_TOPMOST, false));

        // Bit still set but something has been raised above us: raise again.
        assert!(topmost_lost(wanted, true));

        // A window with no styles at all is the degenerate case, and it is "lost".
        assert!(topmost_lost(0, false));

        // The check reads exactly one bit: no other style can fake it.
        assert!(!topmost_lost(WS_EX_TOPMOST, false));
        assert!(!topmost_lost(WS_EX_TOPMOST | WS_EX_LAYERED, false));
    }

    /// `WS_EX_TOPMOST` is `0x00000008` and the pseudo-handle is `(HWND)-1`: both
    /// are transcribed by hand, so both are pinned.
    #[test]
    fn the_style_and_the_insert_after_handle_are_the_documented_values() {
        assert_eq!(WS_EX_TOPMOST, 0x0000_0008);
        assert_eq!(HWND_TOPMOST as isize, -1);
        assert_eq!(GWL_EXSTYLE, -20);
        assert_eq!(GW_HWNDPREV, 3);
        assert_eq!(
            SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_SHOWWINDOW,
            0x0010 | 0x0200 | 0x0040
        );
    }

    /// The log line has to be checkable by eye: the flags the overlay sets must
    /// come out as names, and anything it did not ask for must stay visible.
    #[test]
    fn the_ex_style_is_described_by_flag_name() {
        assert_eq!(describe_ex_style(0), "none");
        assert_eq!(describe_ex_style(WS_EX_TOPMOST), "WS_EX_TOPMOST");
        assert_eq!(
            describe_ex_style(
                WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT
            ),
            "WS_EX_TOPMOST|WS_EX_LAYERED|WS_EX_TRANSPARENT|WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE"
        );
        // A bit nobody asked for (WS_EX_APPWINDOW, 0x00040000) is reported, not lost.
        let with_stranger = WS_EX_LAYERED | 0x0004_0000;
        let text = describe_ex_style(with_stranger);
        assert!(text.contains("WS_EX_LAYERED"), "{text}");
        assert!(text.contains("0x00040000"), "{text}");
    }
}
