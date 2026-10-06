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
    pub fn PeekMessageW(msg: *mut Msg, hwnd: Hwnd, min: u32, max: u32, remove: u32) -> i32;
    pub fn TranslateMessage(msg: *const Msg) -> i32;
    pub fn DispatchMessageW(msg: *const Msg) -> Lresult;
    pub fn PostQuitMessage(code: i32);
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
pub const WS_EX_TRANSPARENT: u32 = 0x0000_0020;
pub const WS_EX_TOOLWINDOW: u32 = 0x0000_0080;
pub const WS_EX_LAYERED: u32 = 0x0008_0000;
pub const WS_EX_NOACTIVATE: u32 = 0x0800_0000;

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
pub const FW_NORMAL: i32 = 400;
pub const FW_SEMIBOLD: i32 = 600;
pub const FW_BOLD: i32 = 700;
pub const FW_EXTRABOLD: i32 = 800;
pub const TRANSPARENT: i32 = 1;
/// DEFAULT_GUI_FONT — the fallback when a named face cannot be created.
pub const DEFAULT_GUI_FONT: i32 = 17;
/// FIXED_PITCH | FF_MODERN, for the Consolas chip and value column.
pub const MONO_PITCH_FAMILY: u32 = 0x31;
/// DEFAULT_PITCH | FF_DONTCARE.
pub const UI_PITCH_FAMILY: u32 = 0x00;
