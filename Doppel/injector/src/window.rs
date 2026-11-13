// window.rs — the injector's native launcher window.
//
// Form follows OpenCommunity's launcher: a real window that walks through
// Splash -> Chooser -> Injecting -> result, instead of a page in a browser. The
// browser panel worked, but it meant a local HTTP server, a per-run token, a tab
// the user had to keep open, and no UI at all when no browser was around. A
// launcher is what this tool actually is.
//
// Look: the OpenCommunity dark palette lives in theme.rs and is applied
// everywhere here — flat #0B0E13 window background, a faint topographic contour
// layer (cached into a bitmap at WM_CREATE and blitted, or AlphaBlend-ed while
// the intro fades), glass cards (panel @ 0.72, border @ 0.40, 1 px inner
// highlight), rounded 15 px buttons with hover/pressed states, LINK-blue for the
// one primary action and SUCCESS/WARNING/DANGER for state.
//
// Zero third-party dependencies, same rule as the rest of the injector: raw
// Win32 (user32/gdi32) through extern "system" FFI, painted with GDI. The only
// child control is the process list box (owner-drawn so its rows can carry the
// palette); the header, cards, buttons and step timeline are drawn in WM_PAINT
// and hit-tested by hand.
//
// Threading: the message loop owns the window on the main thread. The process
// scan runs on a background thread (it opens handles in every java process and
// takes ~100 ms — far too long to run on the UI thread every tick), and the
// injection runs on its own worker. Both publish into the shared state below.
// The intro animation is driven by a 16 ms WM_TIMER that kills itself when it
// is done, so it can never block the message loop or steal focus.

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::flow::{self, FlowError, Reporter, STEPS};
use crate::icons;
use crate::panel;
use crate::process::ProcessInfo;
use crate::theme::{self, metrics};
use crate::ui;
use crate::version;

// ── Win32 types ─────────────────────────────────────────────────────────────

type Hwnd = *mut c_void;
type Hinstance = *mut c_void;
type Hbrush = *mut c_void;
type Hfont = *mut c_void;
type Hdc = *mut c_void;
type Hicon = *mut c_void;
type Hcursor = *mut c_void;
type Wparam = usize;
type Lparam = isize;
type Lresult = isize;

#[repr(C)]
#[derive(Clone, Copy, Default, PartialEq, Eq)]
struct Rect {
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Point {
    x: i32,
    y: i32,
}

#[repr(C)]
struct Msg {
    hwnd: Hwnd,
    message: u32,
    w_param: Wparam,
    l_param: Lparam,
    time: u32,
    pt: Point,
}

#[repr(C)]
struct WndClassExW {
    cb_size: u32,
    style: u32,
    lpfn_wnd_proc: Option<unsafe extern "system" fn(Hwnd, u32, Wparam, Lparam) -> Lresult>,
    cb_cls_extra: i32,
    cb_wnd_extra: i32,
    h_instance: Hinstance,
    h_icon: Hicon,
    h_cursor: Hcursor,
    hbr_background: Hbrush,
    lpsz_menu_name: *const u16,
    lpsz_class_name: *const u16,
    h_icon_sm: Hicon,
}

#[repr(C)]
struct PaintStruct {
    hdc: Hdc,
    f_erase: i32,
    rc_paint: Rect,
    f_restore: i32,
    f_inc_update: i32,
    rgb_reserved: [u8; 32],
}

/// The owner-draw notification the list box posts for every row.
#[repr(C)]
struct DrawItemStruct {
    ctl_type: u32,
    ctl_id: u32,
    item_id: u32,
    item_action: u32,
    item_state: u32,
    hwnd_item: Hwnd,
    hdc: Hdc,
    rc_item: Rect,
    item_data: usize,
}

/// Sent once to the parent of an owner-drawn fixed list box; the row height.
#[repr(C)]
struct MeasureItemStruct {
    ctl_type: u32,
    ctl_id: u32,
    item_id: u32,
    item_width: u32,
    item_height: u32,
    item_data: usize,
}

/// BLENDFUNCTION, passed by value to AlphaBlend (msimg32).
#[repr(C)]
#[derive(Clone, Copy)]
struct BlendFunction {
    blend_op: u8,
    blend_flags: u8,
    source_constant_alpha: u8,
    alpha_format: u8,
}

#[link(name = "user32")]
extern "system" {
    fn RegisterClassExW(wc: *const WndClassExW) -> u16;
    fn CreateWindowExW(
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
    fn DefWindowProcW(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult;
    fn ShowWindow(hwnd: Hwnd, cmd: i32) -> i32;
    fn UpdateWindow(hwnd: Hwnd) -> i32;
    fn GetMessageW(msg: *mut Msg, hwnd: Hwnd, min: u32, max: u32) -> i32;
    fn TranslateMessage(msg: *const Msg) -> i32;
    fn DispatchMessageW(msg: *const Msg) -> Lresult;
    fn PostQuitMessage(code: i32);
    fn SendMessageW(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult;
    fn SetTimer(hwnd: Hwnd, id: usize, ms: u32, proc_: *const c_void) -> usize;
    fn KillTimer(hwnd: Hwnd, id: usize) -> i32;
    fn InvalidateRect(hwnd: Hwnd, rect: *const Rect, erase: i32) -> i32;
    fn BeginPaint(hwnd: Hwnd, ps: *mut PaintStruct) -> Hdc;
    fn EndPaint(hwnd: Hwnd, ps: *const PaintStruct) -> i32;
    fn FillRect(hdc: Hdc, rect: *const Rect, brush: Hbrush) -> i32;
    fn DrawTextW(hdc: Hdc, text: *const u16, len: i32, rect: *mut Rect, format: u32) -> i32;
    fn LoadCursorW(instance: Hinstance, name: *const u16) -> Hcursor;
    fn SetCursor(cursor: Hcursor) -> Hcursor;
    fn MessageBeep(kind: u32) -> i32;
    fn ScreenToClient(hwnd: Hwnd, pt: *mut Point) -> i32;
    fn GetSystemMetrics(index: i32) -> i32;
    fn SystemParametersInfoW(action: u32, param: u32, data: *mut c_void, win_ini: u32) -> i32;
    fn GetDC(hwnd: Hwnd) -> Hdc;
    fn ReleaseDC(hwnd: Hwnd, hdc: Hdc) -> i32;
    fn SetCapture(hwnd: Hwnd) -> Hwnd;
    fn ReleaseCapture() -> i32;
}

#[link(name = "gdi32")]
extern "system" {
    fn CreateSolidBrush(color: u32) -> Hbrush;
    fn CreatePen(style: i32, width: i32, color: u32) -> *mut c_void;
    fn CreateFontW(
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
    fn SelectObject(hdc: Hdc, obj: *mut c_void) -> *mut c_void;
    fn SetBkMode(hdc: Hdc, mode: i32) -> i32;
    fn SetBkColor(hdc: Hdc, color: u32) -> u32;
    fn SetTextColor(hdc: Hdc, color: u32) -> u32;
    fn CreateCompatibleDC(hdc: Hdc) -> Hdc;
    fn CreateCompatibleBitmap(hdc: Hdc, w: i32, h: i32) -> *mut c_void;
    fn BitBlt(dst: Hdc, x: i32, y: i32, w: i32, h: i32, src: Hdc, sx: i32, sy: i32, rop: u32) -> i32;
    fn DeleteDC(hdc: Hdc) -> i32;
    fn DeleteObject(obj: *mut c_void) -> i32;
    fn RoundRect(hdc: Hdc, l: i32, t: i32, r: i32, b: i32, ew: i32, eh: i32) -> i32;
    fn GetStockObject(index: i32) -> *mut c_void;
    fn MoveToEx(hdc: Hdc, x: i32, y: i32, prev: *mut Point) -> i32;
    fn LineTo(hdc: Hdc, x: i32, y: i32) -> i32;
    fn SaveDC(hdc: Hdc) -> i32;
    fn RestoreDC(hdc: Hdc, saved: i32) -> i32;
    fn IntersectClipRect(hdc: Hdc, left: i32, top: i32, right: i32, bottom: i32) -> i32;
}

// AlphaBlend: the one bit of real alpha the launcher needs — fading the cached
// topographic layer in over the window background. System DLL, no crate.
#[link(name = "msimg32")]
extern "system" {
    fn AlphaBlend(
        hdc_dst: Hdc,
        x_dst: i32,
        y_dst: i32,
        w_dst: i32,
        h_dst: i32,
        hdc_src: Hdc,
        x_src: i32,
        y_src: i32,
        w_src: i32,
        h_src: i32,
        ftn: BlendFunction,
    ) -> i32;
}

// The native title bar otherwise renders in the OS light theme and fights the
// #0B0E13 client area. Failures are ignored (the attribute is Win10 20H1+).
#[link(name = "dwmapi")]
extern "system" {
    fn DwmSetWindowAttribute(hwnd: Hwnd, attr: u32, value: *const c_void, size: u32) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn GetModuleHandleW(name: *const u16) -> Hinstance;
}

// ── win32 constants ─────────────────────────────────────────────────────────

const WS_OVERLAPPED: u32 = 0x0000_0000;
const WS_CAPTION: u32 = 0x00C0_0000;
const WS_SYSMENU: u32 = 0x0008_0000;
const WS_MINIMIZEBOX: u32 = 0x0002_0000;
const WS_CHILD: u32 = 0x4000_0000;
const WS_VISIBLE: u32 = 0x1000_0000;
const WS_VSCROLL: u32 = 0x0020_0000;
const WS_TABSTOP: u32 = 0x0001_0000;

const LBS_NOTIFY: u32 = 0x0001;
const LBS_OWNERDRAWFIXED: u32 = 0x0010;
const LBS_HASSTRINGS: u32 = 0x0040;
const LBS_NOINTEGRALHEIGHT: u32 = 0x0100;

const WM_CREATE: u32 = 0x0001;
const WM_DESTROY: u32 = 0x0002;
const WM_PAINT: u32 = 0x000F;
const WM_CLOSE: u32 = 0x0010;
const WM_ERASEBKGND: u32 = 0x0014;
const WM_SETCURSOR: u32 = 0x0020;
const WM_MEASUREITEM: u32 = 0x002C;
const WM_DRAWITEM: u32 = 0x002B;
const WM_SETFONT: u32 = 0x0030;
const WM_COMMAND: u32 = 0x0111;
const WM_TIMER: u32 = 0x0113;
const WM_MOUSEMOVE: u32 = 0x0200;
const WM_LBUTTONDOWN: u32 = 0x0201;
const WM_LBUTTONUP: u32 = 0x0202;
const WM_KEYDOWN: u32 = 0x0100;

// Virtual-key codes the keybind capture understands.
const VK_TAB: u32 = 0x09;
const VK_RETURN: u32 = 0x0D;
const VK_SHIFT: u32 = 0x10;
const VK_CONTROL: u32 = 0x11;
const VK_MENU: u32 = 0x12;
const VK_ESCAPE: u32 = 0x1B;
const VK_SPACE: u32 = 0x20;
const VK_NUMPAD0: u32 = 0x60;
const VK_F1: u32 = 0x70;
const WM_SYSCOMMAND: u32 = 0x0112;
const WM_CTLCOLORLISTBOX: u32 = 0x0134;
const SC_MINIMIZE: u32 = 0xF020;

const LB_ADDSTRING: u32 = 0x0180;
const LB_RESETCONTENT: u32 = 0x0184;
const LB_SETCURSEL: u32 = 0x0186;
const LB_GETCURSEL: u32 = 0x0188;
const LB_GETITEMDATA: u32 = 0x0199;
const LB_SETITEMDATA: u32 = 0x019A;
const LB_SETITEMHEIGHT: u32 = 0x00B1;

const SW_SHOW: i32 = 5;
const SW_HIDE: i32 = 0;
const SW_SHOWNORMAL: i32 = 1;

const DT_LEFT: u32 = 0x0000;
const DT_CENTER: u32 = 0x0001;
const DT_RIGHT: u32 = 0x0002;
const DT_VCENTER: u32 = 0x0004;
const DT_SINGLELINE: u32 = 0x0020;
const DT_END_ELLIPSIS: u32 = 0x0000_8000;
const DT_WORDBREAK: u32 = 0x0010;

const TRANSPARENT: i32 = 1;
const IDC_ARROW: u32 = 32512;
const SM_CXSCREEN: i32 = 0;
const SM_CYSCREEN: i32 = 1;
const SPI_GETWORKAREA: u32 = 0x0030;
const SRCCOPY: u32 = 0x00CC_0020;
const NULL_PEN: i32 = 8;
const PS_SOLID: i32 = 0;
const MB_ICONERROR: u32 = 0x0000_0010;
const LBN_SELCHANGE: u32 = 1;
const ODS_SELECTED: u32 = 0x0001;
const AC_SRC_OVER: u8 = 0x00;
const DWMWA_USE_IMMERSIVE_DARK_MODE: u32 = 20;
const DWMWA_USE_IMMERSIVE_DARK_MODE_OLD: u32 = 19;
const DWMWA_BORDER_COLOR: u32 = 34;
const DWMWA_CAPTION_COLOR: u32 = 35;
const DWMWA_TEXT_COLOR: u32 = 36;

// Frame removal + the custom caption strip.
const WM_NCCALCSIZE: u32 = 0x0083;
const WM_NCHITTEST: u32 = 0x0084;
const WM_MOUSEWHEEL: u32 = 0x020A;
const HTCLIENT: Lresult = 1;
const HTCAPTION: Lresult = 2;

// ── palette ─────────────────────────────────────────────────────────────────
//
// Every colour is derived from theme.rs; there is no literal RGB in this file.
// The old v4 cyan/violet constants are gone.

const C_BG: u32 = theme::colorref(theme::BACKGROUND);
const C_STRONG: u32 = theme::colorref(theme::STRONG_TEXT);
const C_TEXT: u32 = theme::colorref(theme::MODULE_TEXT);
const C_ALT: u32 = theme::colorref(theme::MODULE_ALT_TEXT);
const C_SECOND: u32 = theme::colorref(theme::SECONDARY_TEXT);
const C_DIM: u32 = theme::colorref(theme::MUTED_TEXT);
const C_LINK: u32 = theme::colorref(theme::LINK);
const C_LINK_HOVER: u32 = theme::colorref(theme::LINK_HOVER);
const C_SUCCESS: u32 = theme::colorref(theme::SUCCESS);
const C_WARNING: u32 = theme::colorref(theme::WARNING);
const C_DANGER: u32 = theme::colorref(theme::DANGER);
const C_CARD: u32 = theme::colorref(theme::CARD_ON_BG);
const C_CARD_BORDER: u32 = theme::colorref(theme::CARD_BORDER);
const C_CARD_INNER: u32 = theme::colorref(theme::CARD_INNER);
const C_BTN_HOVER: u32 = theme::colorref(theme::BTN_HOVER);
const C_BTN_ACTIVE: u32 = theme::colorref(theme::BTN_ACTIVE);
const C_BTN_DISABLED: u32 = theme::colorref(theme::BTN_DISABLED);
const C_SEPARATOR: u32 = theme::colorref(theme::SEPARATOR);
const C_LIST_FILL: u32 = theme::colorref(theme::LIST_FILL);
const C_ROW_SELECTED: u32 = theme::colorref(theme::ROW_SELECTED);
const C_ON_LINK: u32 = theme::colorref(theme::ON_LINK_TEXT);
const C_TOPO: u32 = theme::colorref(theme::TOPO_LINE_ON_BG);
/// A selected pill's tinted body: LINK at 14 % over the background.
const C_PILL_SEL: u32 = theme::colorref(theme::mix(theme::BACKGROUND, theme::LINK, 14, 100));

// ── layout (logical client area, fixed size — the window is not resizable) ───

const CLIENT_W: i32 = metrics::WINDOW_W;
const CLIENT_H: i32 = metrics::WINDOW_H;
const PAD: i32 = metrics::PAD_WINDOW;

// ── custom title bar ──
// The OS frame is removed (WM_NCCALCSIZE collapses the client onto the whole
// window) and this strip is drawn + hit-tested by hand, so the caption can
// never come back in the white system style the old build showed.

const TITLE_H: i32 = 44;
const CAP_BTN_W: i32 = 44;
const CAP_MIN_RECT: Rect = Rect { left: CLIENT_W - CAP_BTN_W * 2, top: 0, right: CLIENT_W - CAP_BTN_W, bottom: TITLE_H };
const CAP_CLOSE_RECT: Rect = Rect { left: CLIENT_W - CAP_BTN_W, top: 0, right: CLIENT_W, bottom: TITLE_H };

// ── chooser layout ──

const PILL_W: i32 = 300;
const PILL_H: i32 = 48;
const PILL_GAP: i32 = 12;
const VERSION_Y: i32 = 96;
const IFACE_Y: i32 = VERSION_Y + PILL_H + 10;

const FORGE_RECT: Rect = Rect { left: PAD, top: VERSION_Y, right: PAD + PILL_W, bottom: VERSION_Y + PILL_H };
const FABRIC_RECT: Rect = Rect { left: PAD + PILL_W + PILL_GAP, top: VERSION_Y, right: PAD + PILL_W * 2 + PILL_GAP, bottom: VERSION_Y + PILL_H };
const INWIN_RECT: Rect = Rect { left: PAD, top: IFACE_Y, right: PAD + PILL_W, bottom: IFACE_Y + PILL_H };
const WEBGUI_RECT: Rect = Rect { left: PAD + PILL_W + PILL_GAP, top: IFACE_Y, right: PAD + PILL_W * 2 + PILL_GAP, bottom: IFACE_Y + PILL_H };

const LIST_X: i32 = PAD;
const LIST_Y: i32 = IFACE_Y + PILL_H + 40;
const LIST_W: i32 = CLIENT_W - PAD * 2;
const LIST_H: i32 = 160;

// The stock list box is inset so the card's rounded corners stay visible.
const LISTBOX_X: i32 = LIST_X + metrics::ITEM_INNER_Y;
const LISTBOX_Y: i32 = LIST_Y + metrics::ITEM_INNER_Y;
const LISTBOX_W: i32 = LIST_W - metrics::ITEM_INNER_Y * 2;
const LISTBOX_H: i32 = LIST_H - metrics::ITEM_INNER_Y * 2;

const BTN_W: i32 = metrics::BTN_LARGE_W;
const BTN_H: i32 = metrics::BTN_LARGE_H;
const BTN_Y: i32 = LIST_Y + LIST_H + 14;

const REFRESH_RECT: Rect = Rect { left: PAD, top: BTN_Y, right: PAD + BTN_W, bottom: BTN_Y + BTN_H };
const INJECT_RECT: Rect = Rect { left: CLIENT_W - PAD - BTN_W, top: BTN_Y, right: CLIENT_W - PAD, bottom: BTN_Y + BTN_H };
const HINT_Y: i32 = BTN_Y + BTN_H + 14;
const FOOTER_Y: i32 = HINT_Y + 26;

// ── panel screen (the in-window GUI) layout — VAPE-style centre window ──────
//
// One nav row under the caption (‹ back · Home pill · sync · refresh/web/hide),
// a text-only category rail with an active underline, one rounded CARD per
// module (icon tile · name · inline summary · kebab · switch), and — when a
// module is picked — a right-hand settings pane that pushes the list narrower
// inside the same window (the reference's centre-window behaviour).

const NAV_Y: i32 = TITLE_H + 8;
const NAV_H: i32 = 30;
const NAV_BACK_RECT: Rect = Rect { left: PAD, top: NAV_Y, right: PAD + 34, bottom: NAV_Y + NAV_H };
const NAV_HOME_RECT: Rect =
    Rect { left: NAV_BACK_RECT.right + 8, top: NAV_Y, right: NAV_BACK_RECT.right + 100, bottom: NAV_Y + NAV_H };
// right-aligned cluster: [dot + status text] [refresh] [web] [Hide]
const NAV_HIDE_RECT: Rect = Rect { left: CLIENT_W - PAD - 58, top: NAV_Y, right: CLIENT_W - PAD, bottom: NAV_Y + NAV_H };
const NAV_WEB_RECT: Rect =
    Rect { left: NAV_HIDE_RECT.left - 40, top: NAV_Y, right: NAV_HIDE_RECT.left - 6, bottom: NAV_Y + NAV_H };
const NAV_REFRESH_RECT: Rect =
    Rect { left: NAV_WEB_RECT.left - 40, top: NAV_Y, right: NAV_WEB_RECT.left - 6, bottom: NAV_Y + NAV_H };
/// Right edge of the "synced Ns ago" status text.
const NAV_STATUS_RIGHT: i32 = NAV_REFRESH_RECT.left - 10;

const PANEL_TOP: i32 = NAV_Y + NAV_H + 12;
const PANEL_BOTTOM: i32 = CLIENT_H - 30;

const RAIL_W: i32 = 108;
const RAIL_RECT: Rect = Rect { left: PAD, top: PANEL_TOP, right: PAD + RAIL_W, bottom: PANEL_BOTTOM };

const SETTINGS_W: i32 = 244;
const SETTINGS_RECT: Rect =
    Rect { left: CLIENT_W - PAD - SETTINGS_W, top: PANEL_TOP, right: CLIENT_W - PAD, bottom: PANEL_BOTTOM };

const LIST_LEFT: i32 = PAD + RAIL_W + 16;
const LIST_RECT_SHUT: Rect =
    Rect { left: LIST_LEFT, top: PANEL_TOP, right: CLIENT_W - PAD, bottom: PANEL_BOTTOM };
const LIST_RECT_OPEN: Rect =
    Rect { left: LIST_LEFT, top: PANEL_TOP, right: SETTINGS_RECT.left - 14, bottom: PANEL_BOTTOM };

// One module card — 52 px tall with a 6 px gap between cards.
const CARD_H: i32 = 52;
const CARD_GAP: i32 = 6;
// One settings-pane row: numeric options carry a slider track, choices a
// full-width dropdown box, everything else a label + control line.
const OPT_ROW_H: i32 = 30;
const OPT_CHOICE_H: i32 = 34;
const OPT_SLIDER_H: i32 = 48;
const OPT_PAD: i32 = 10;
/// Settings-pane content offset: header + keybind row + separator.
const SET_ROWS_OFF: i32 = 88;

/// Rail categories; index == PANEL_CAT value. "!FAV" is the local Favorites
/// view (starred modules of any category); "" is every module.
const RAIL_CATS: [&str; 8] = ["!FAV", "", "COMBAT", "PLAYER", "MOVEMENT", "VISUAL", "UTILITY", "MISC"];
const RAIL_LABELS: [&str; 8] = ["Favorites", "All", "Combat", "Player", "Movement", "Visual", "Utility", "Misc"];

// ── running / done layout ──

const STEPS_CARD: Rect = Rect { left: PAD, top: 100, right: CLIENT_W - PAD, bottom: 246 };
const RESULT_CARD: Rect = Rect { left: PAD, top: 260, right: CLIENT_W - PAD, bottom: 426 };

const ROW_BTN_Y: i32 = CLIENT_H - PAD - BTN_H;
const BACK_RECT: Rect = Rect { left: PAD, top: ROW_BTN_Y, right: PAD + BTN_W, bottom: ROW_BTN_Y + BTN_H };
const OPEN_RECT: Rect = Rect { left: PAD + BTN_W + 12, top: ROW_BTN_Y, right: PAD + BTN_W * 2 + 12, bottom: ROW_BTN_Y + BTN_H };
const HIDE_RECT: Rect = Rect { left: PAD + BTN_W * 2 + 24, top: ROW_BTN_Y, right: PAD + BTN_W * 3 + 24, bottom: ROW_BTN_Y + BTN_H };
const CLOSE_RECT: Rect = Rect { left: CLIENT_W - PAD - BTN_W, top: ROW_BTN_Y, right: CLIENT_W - PAD, bottom: ROW_BTN_Y + BTN_H };

const STEP_Y0: i32 = 116;
const STEP_DY: i32 = 32;

// ── intro / fade timeline (milliseconds) ────────────────────────────────────
//
// A short in-window splash, exactly like the reference's AppState::Intro: the
// wordmark fades up, the topographic layer fades in with it, and the chooser's
// content then fades up over the background while the splash fades out. All of
// it is driven from the 16 ms timer below; nothing here ever blocks.

const INTRO_MS: f32 = 420.0;
const SPLASH_IN_MS: f32 = 200.0;
const SPLASH_OUT_START: f32 = INTRO_MS - 180.0;
const SPLASH_OUT_LEN: f32 = 300.0;
const TOPO_IN_MS: f32 = 260.0;
const CONTENT_FADE_START: f32 = 300.0;
const CONTENT_FADE_LEN: f32 = 340.0;
const ANIM_END_MS: f32 = CONTENT_FADE_START + CONTENT_FADE_LEN + 80.0;

const TIMER_LIST: usize = 1;
const TIMER_ANIM: usize = 2;

static ANIM_START: OnceLock<Instant> = OnceLock::new();
/// Set when the user clicks through the splash: shifts the whole timeline
/// forward so the content fade starts immediately instead of after the wait.
static TIME_OFFSET_MS: AtomicU32 = AtomicU32::new(0);
/// Current content alpha ×1000. GDI has no alpha, so the drawing helpers read
/// this and blend every colour toward the window background.
static FADE_MILLI: AtomicU32 = AtomicU32::new(0);

fn set_fade(a: f32) {
    FADE_MILLI.store((a.clamp(0.0, 1.0) * 1000.0) as u32, Ordering::Relaxed);
}

fn cur_fade() -> f32 {
    FADE_MILLI.load(Ordering::Relaxed) as f32 / 1000.0
}

/// Blend a COLORREF toward the window background by the current fade.
fn fade(color: u32) -> u32 {
    let a = cur_fade();
    if a >= 0.999 {
        color
    } else {
        theme::fade_colorref(color, a)
    }
}

fn smoothstep(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

fn anim_ms() -> f32 {
    let start = *ANIM_START.get_or_init(Instant::now);
    start.elapsed().as_secs_f32() * 1000.0 + TIME_OFFSET_MS.load(Ordering::Relaxed) as f32
}

/// Click-through: jump to the start of the content fade.
fn skip_intro() {
    let t = anim_ms();
    if t < CONTENT_FADE_START {
        TIME_OFFSET_MS.store((CONTENT_FADE_START - t) as u32 + 1, Ordering::Relaxed);
    }
}

fn topo_alpha(t: f32) -> f32 {
    smoothstep(t / TOPO_IN_MS)
}

fn splash_alpha(t: f32) -> f32 {
    smoothstep(t / SPLASH_IN_MS) * (1.0 - smoothstep((t - SPLASH_OUT_START) / SPLASH_OUT_LEN))
}

fn content_alpha(t: f32) -> f32 {
    smoothstep((t - CONTENT_FADE_START) / CONTENT_FADE_LEN)
}

// ── shared state ────────────────────────────────────────────────────────────

#[derive(Clone, Copy, PartialEq, Eq)]
enum App {
    Intro,
    Chooser,
    Running,
    Done,
    Failed,
    /// The in-window GUI: a native module panel talking to the agent's API.
    Panel,
}

/// The pill choice for which Minecraft flavour is about to be injected.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Target {
    Forge,
    Fabric,
}

impl Target {
    fn key(self) -> &'static str {
        match self {
            Target::Forge => "forge",
            Target::Fabric => "fabric",
        }
    }
}

/// Where the modules are controlled after the agent lands.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Gui {
    InWindow,
    Web,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum StepState {
    Pending,
    Active,
    Ok,
    Failed,
}

struct StepInfo {
    state: StepState,
    note: String,
    secs: f32,
}

impl Default for StepInfo {
    fn default() -> Self {
        StepInfo { state: StepState::Pending, note: String::new(), secs: 0.0 }
    }
}

struct UiState {
    app: App,
    pid: u32,
    version: String,
    panel_url: String,
    panel_token: String,
    /// The exact failure reason (or a hint) shown under the headline.
    detail: String,
    steps: Vec<StepInfo>,
    events: Vec<(String, String)>,
    /// Version pill choice (default: 1.8.9 · Forge).
    target: Target,
    /// GUI pill choice (default: the in-window panel).
    gui: Gui,
    /// "1.8.9 · forge" for the picked process, detected in the background.
    detected: String,
}

impl Default for UiState {
    fn default() -> Self {
        UiState {
            app: App::Intro,
            pid: 0,
            version: String::new(),
            panel_url: String::new(),
            panel_token: String::new(),
            detail: String::new(),
            steps: (0..STEPS.len()).map(|_| StepInfo::default()).collect(),
            events: Vec::new(),
            target: Target::Forge,
            gui: Gui::InWindow,
            detected: String::new(),
        }
    }
}

/// One owner-drawn row of the process list: the fields the row paints itself
/// from, kept beside the list box's own strings.
#[derive(Clone)]
struct Row {
    pid: u32,
    exe: String,
    title: String,
    tag: String,
}

/// Rail selection (index into RAIL_CATS; 1 = All) + pixel scroll of the
/// module list (pixels, because the cards scroll as one column).
static PANEL_CAT: AtomicU32 = AtomicU32::new(1);
static PANEL_SCROLL: AtomicI32 = AtomicI32::new(0);

/// The module whose settings pane is open (its name), if any.
static PANEL_SELECTED: Mutex<Option<String>> = Mutex::new(None);
/// Pixel scroll of the settings pane's option list.
static OPT_SCROLL: AtomicI32 = AtomicI32::new(0);
/// Modules starred locally (the Favorites rail view). Client-side only —
/// the agent has no say in these, they die with the process.
static FAVORITES: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// The row kebab menu currently open, with its item rects.
static POPUP: Mutex<Option<Popup>> = Mutex::new(None);
/// The module waiting for the next keypress, if a keybind chip was clicked.
static KB_CAPTURE: Mutex<Option<String>> = Mutex::new(None);
/// Freshly-confirmed binds: (module, keyName, valid-until). One fence entry
/// each stops a poll answer that was already in flight from painting the OLD
/// key back over a bind we just confirmed (same idea as the web panel's).
static KB_FENCE: Mutex<Vec<(String, String, Instant)>> = Mutex::new(Vec::new());
/// Modules whose trigger-condition section is unfolded (name keyed, client
/// side only). Folded is the default — the folded header carries the live
/// selection, so the state is never hidden, exactly like the web panel's
/// `doppel_trig` localStorage set.
static TRIG_OPEN: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// An option-slider drag in progress (mouse is captured).
static DRAG: Mutex<Option<Drag>> = Mutex::new(None);
/// Until when the poller must keep its hands off the snapshot: any UI edit
/// pokes this forward, so a poll answer can never repaint an edit the user is
/// mid-way through (the web panel's `interacting` guard, in Rust form).
static INTERACT_UNTIL: Mutex<Option<Instant>> = Mutex::new(None);

fn poke_interact() {
    *lock(&INTERACT_UNTIL) = Some(Instant::now() + Duration::from_millis(400));
}

fn interacting() -> bool {
    lock(&INTERACT_UNTIL).map(|t| Instant::now() < t).unwrap_or(false)
}

/// One moving slider thumb: which option key(s) it writes, the range it maps
/// onto, the formatting to POST with, and the track it is being dragged on.
#[derive(Clone)]
struct Drag {
    dual: bool,
    /// Dual-slider only: which of the two thumbs the mouse grabbed.
    thumb_min: bool,
    key_min: String,
    key_max: Option<String>,
    lo: f64,
    hi: f64,
    step: f64,
    dec: i32,
    int: bool,
    vmin: f64,
    vmax: f64,
    track: Rect,
    last_post: Instant,
}

/// The ⋮ menu of one module card: quick keybind + favorite, VAPE's kebab.
/// Rects are client-space and baked at open time; any click outside closes it.
#[derive(Clone)]
struct Popup {
    module: String,
    key_label: String,
    fav: bool,
    rect: Rect,
    bind: Rect,
    star: Rect,
}

struct Shared {
    state: Mutex<UiState>,
    /// Latest scan result, published by the scanner thread.
    procs: Mutex<Vec<ProcessInfo>>,
    /// Signature of the list currently in the list box: the scan refreshes every
    /// two seconds and rebuilding on every tick would fight the user's clicks.
    shown_sig: Mutex<String>,
    /// The process the user picked (0 = none).
    selected: Mutex<u32>,
    hover: Mutex<Point>,
    /// Rows currently in the list box, indexed by item id (owner-draw).
    rows: Mutex<Vec<Row>>,
}

static SHARED: OnceLock<Shared> = OnceLock::new();
static BUSY: AtomicBool = AtomicBool::new(false);
static CLASS_REGISTERED: AtomicBool = AtomicBool::new(false);
static LIST_VISIBLE: AtomicBool = AtomicBool::new(true);
static HWND_MAIN: OnceLock<usize> = OnceLock::new();
static HWND_LIST: OnceLock<usize> = OnceLock::new();
static TOPO_DC: OnceLock<usize> = OnceLock::new();
/// The button the mouse went down on, so it can paint its pressed state.
static PRESSED: Mutex<Option<Rect>> = Mutex::new(None);

fn shared() -> &'static Shared {
    SHARED.get_or_init(|| Shared {
        state: Mutex::new(UiState::default()),
        procs: Mutex::new(Vec::new()),
        shown_sig: Mutex::new(String::new()),
        selected: Mutex::new(0),
        hover: Mutex::new(Point::default()),
        rows: Mutex::new(Vec::new()),
    })
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn main_hwnd() -> Hwnd {
    HWND_MAIN.get().map(|p| *p as Hwnd).unwrap_or(std::ptr::null_mut())
}

fn list_hwnd() -> Hwnd {
    HWND_LIST.get().map(|p| *p as Hwnd).unwrap_or(std::ptr::null_mut())
}

fn repaint() {
    unsafe { InvalidateRect(main_hwnd(), std::ptr::null(), 0) };
}

/// Hand a URL to the shell. `start` needs the empty first argument, otherwise
/// it treats the URL as the window title and opens nothing.
fn open_url(url: &str) {
    if url.is_empty() {
        return;
    }
    let _ = std::process::Command::new("cmd")
        .args(["/C", "start", "", url])
        .spawn();
    crate::log::append(&format!("opened {url} in the default browser"));
}

/// Minimise the launcher. The game is in front now; the launcher stays on the
/// taskbar so it can be brought back.
fn hide_launcher() {
    let hwnd = main_hwnd();
    if !hwnd.is_null() {
        unsafe { SendMessageW(hwnd, WM_SYSCOMMAND, SC_MINIMIZE as Wparam, 0) };
    }
}

// ── the reporter that drives the window ─────────────────────────────────────

struct WindowReporter {
    started: Mutex<Vec<Option<Instant>>>,
}

impl WindowReporter {
    fn new() -> Self {
        WindowReporter { started: Mutex::new((0..STEPS.len()).map(|_| None).collect()) }
    }
}

impl Reporter for WindowReporter {
    fn step_begin(&self, idx: usize) {
        let label = STEPS.get(idx).copied().unwrap_or("");
        crate::log::append(&format!("[step {}] begin · {}", idx + 1, label));
        {
            let s = shared();
            let mut st = lock(&s.state);
            if let Some(step) = st.steps.get_mut(idx) {
                step.state = StepState::Active;
            }
            st.app = App::Running;
            st.detail.clear();
        }
        if let Ok(mut g) = self.started.lock() {
            if let Some(slot) = g.get_mut(idx) {
                *slot = Some(Instant::now());
            }
        }
        ui::step_begin(idx, label);
        repaint();
    }

    fn step_note(&self, note: &str) {
        {
            let s = shared();
            let mut st = lock(&s.state);
            if let Some(i) = st.steps.iter().position(|x| x.state == StepState::Active) {
                st.steps[i].note = note.to_string();
            }
        }
        ui::step_note(note);
        repaint();
    }

    fn step_ok(&self, idx: usize, secs: f32) {
        crate::log::append(&format!("[step {}] ok · {:.2}s", idx + 1, secs));
        {
            let s = shared();
            let mut st = lock(&s.state);
            if let Some(step) = st.steps.get_mut(idx) {
                step.state = StepState::Ok;
                step.secs = secs;
            }
        }
        let started = self
            .started
            .lock()
            .ok()
            .and_then(|g| g.get(idx).copied().flatten())
            .unwrap_or_else(Instant::now);
        ui::step_ok(idx, STEPS.get(idx).copied().unwrap_or(""), started);
        repaint();
    }

    fn step_fail(&self, idx: usize, reason: &str) {
        {
            let s = shared();
            let mut st = lock(&s.state);
            if let Some(step) = st.steps.get_mut(idx) {
                step.state = StepState::Failed;
                step.note = reason.to_string();
            }
            st.detail = reason.to_string();
        }
        push_event("error", reason.to_string());
        ui::step_fail(idx, STEPS.get(idx).copied().unwrap_or(""), reason);
        repaint();
    }

    fn detail(&self, msg: &str) {
        ui::detail(msg);
    }

    fn warn(&self, msg: &str) {
        push_event("warn", msg.to_string());
        ui::warn(msg);
        repaint();
    }

    fn hint(&self, msg: &str) {
        push_event("info", msg.to_string());
        ui::hint(msg);
        repaint();
    }
}

fn push_event(kind: &str, text: String) {
    {
        let s = shared();
        let mut st = lock(&s.state);
        st.events.push((kind.to_string(), text.clone()));
        let len = st.events.len();
        if len > 60 {
            st.events.drain(0..len - 60);
        }
    }
    crate::log::append(&format!("[{}] {}", kind, text));
}

// ── background workers ──────────────────────────────────────────────────────

/// Rescan the java process list every two seconds and publish it. Kept off the
/// UI thread: the scan opens handles in every java process on the machine.
fn spawn_scanner() {
    std::thread::spawn(|| loop {
        let list = flow::candidates();
        *lock(&shared().procs) = list;
        std::thread::sleep(Duration::from_millis(2000));
    });
}

// ── in-window GUI (panel) workers ───────────────────────────────────────

/// One immediate poll (nav-row refresh button): the same body as a poller
/// tick, so the list never diverges from what the agent says.
fn spawn_poll_now() {
    let (url, token) = panel_url_token();
    if url.is_empty() {
        return;
    }
    std::thread::spawn(move || {
        let ps = panel::state();
        match panel::fetch_modules(&url, &token) {
            Ok(mut list) => {
                apply_keybind_fence(&mut list);
                if !interacting() {
                    panel::publish(list);
                    *panel::lock(&ps.last_ok) = Some(Instant::now());
                    panel::lock(&ps.last_err).clear();
                }
            }
            Err(e) => *panel::lock(&ps.last_err) = e,
        }
        repaint();
    });
}

/// Poll GET /api/modules while the panel screen is up, so the native list
/// tracks what the game (or the web GUI) does to the modules. One thread for
/// the whole process life; it idles whenever the panel is not on screen.
/// While the user is mid-edit (slider drag, capture, a click) the answer is
/// dropped instead of published — the same "hands off while interacting" rule
/// the web panel applies, so a 1.5 s poll can never repaint an edit.
/// A poll that arrives after the selected module vanished also closes the
/// settings pane, so the pane can never show a module the game has unloaded.
fn spawn_panel_poller() {
    static STARTED: AtomicBool = AtomicBool::new(false);
    if STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| loop {
        let active = lock(&shared().state).app == App::Panel;
        if active {
            let (url, token) = {
                let st = lock(&shared().state);
                (st.panel_url.clone(), st.panel_token.clone())
            };
            if !url.is_empty() {
                let ps = panel::state();
                match panel::fetch_modules(&url, &token) {
                    Ok(mut list) => {
                        apply_keybind_fence(&mut list);
                        if !interacting() {
                            panel::publish(list);
                            // a vanished module closes its settings pane
                            if let Some(sel) = lock(&PANEL_SELECTED).clone() {
                                if !panel::lock(&ps.modules).iter().any(|m| m.name == sel) {
                                    *lock(&PANEL_SELECTED) = None;
                                }
                            }
                            *panel::lock(&ps.last_ok) = Some(Instant::now());
                            panel::lock(&ps.last_err).clear();
                        }
                    }
                    Err(e) => *panel::lock(&ps.last_err) = e,
                }
            }
        }
        std::thread::sleep(Duration::from_millis(if active { 1500 } else { 500 }));
    });
}

/// Overlay freshly-confirmed keybinds onto a poll answer. A GET that was
/// already in flight when the bind POST landed still carries the old keyName;
/// without this fence the chip would flicker back for one poll cycle.
fn apply_keybind_fence(list: &mut Vec<panel::PanelModule>) {
    let mut fence = lock(&KB_FENCE);
    let now = Instant::now();
    fence.retain(|(_, _, until)| *until > now);
    for (name, kn, _) in fence.iter() {
        if let Some(m) = list.iter_mut().find(|m| &m.name == name) {
            m.key_name = kn.clone();
        }
    }
}

/// Flip a module optimistically, then let the agent's answer settle the value
/// (and the next poll keep it honest — the in-game HUD can toggle too).
fn panel_toggle(name: &str) {
    poke_interact();
    let (url, token) = panel_url_token();
    if url.is_empty() {
        return;
    }
    {
        let ps = panel::state();
        let mut mods = panel::lock(&ps.modules);
        if let Some(m) = mods.iter_mut().find(|m| m.name == name) {
            m.enabled = !m.enabled;
        }
    }
    repaint();
    let name = name.to_string();
    std::thread::spawn(move || {
        let ps = panel::state();
        match panel::post_toggle(&url, &token, &name) {
            Ok(now) => {
                let mut mods = panel::lock(&ps.modules);
                if let Some(m) = mods.iter_mut().find(|m| m.name == name) {
                    m.enabled = now;
                }
            }
            Err(e) => *panel::lock(&ps.last_err) = e,
        }
        repaint();
    });
}

/// Cheap string-only flavour guess for the list chips — NO filesystem access,
/// it runs on every list refresh. The real detection still happens in
/// version.rs (flow step 2); this only tints the rows and the auto-pick.
fn platform_hint(p: &ProcessInfo) -> &'static str {
    let hay = format!(
        "{} {} {} {}",
        p.cmdline,
        p.path,
        p.window_title,
        p.cmdline_hint()
    )
    .to_ascii_lowercase();
    if hay.contains("fabric") {
        "fabric"
    } else if hay.contains("forge") || hay.contains("fml") || hay.contains("1.8.9") {
        "forge"
    } else if hay.contains("1.21") {
        "fabric"
    } else {
        ""
    }
}

fn run_injection(pid: u32) {
    crate::log::append(&format!("=== injection start · target PID {} ===", pid));
    let rep = WindowReporter::new();
    let result = flow::run(Some(pid), &rep);

    let s = shared();
    match result {
        Ok(outcome) => {
            let gui = lock(&s.state).gui;
            {
                let mut st = lock(&s.state);
                st.app = App::Done;
                st.pid = outcome.pid;
                st.version = outcome.version.clone();
                st.panel_url = outcome.panel_url.clone().unwrap_or_default();
                st.panel_token = outcome.panel_token.clone();
                st.detail.clear();
            }
            push_event("ok", format!("agent loaded into PID {} ({})", outcome.pid, outcome.version));
            crate::log::append("=== injection OK · agent loaded ===");
            unsafe { MessageBeep(0) };

            if gui == Gui::InWindow && !outcome.panel_url.as_deref().unwrap_or("").is_empty() {
                // The user picked the in-window GUI: the launcher does not get
                // out of the way — it becomes the panel (same API, native UI).
                push_event("info", "switching to the in-window panel".to_string());
                lock(&s.state).app = App::Panel;
                spawn_panel_poller();
            } else {
                // Web GUI — the old handoff: open the browser tab, get out of
                // the way; the agent keeps running inside the game either way.
                if !outcome.panel_url.as_deref().unwrap_or("").is_empty() {
                    open_url(outcome.panel_url.as_deref().unwrap_or(""));
                }
                hide_launcher();
            }
        }
        Err(e) => {
            {
                let mut st = lock(&s.state);
                st.app = App::Failed;
                st.detail = e.reason().to_string();
            }
            crate::log::append(&format!("=== injection FAILED · {} ===", e.reason()));
            if matches!(e, FlowError::NoProcess) {
                push_event("error", "that process is gone — refresh the list".to_string());
            }
            unsafe { MessageBeep(MB_ICONERROR) };
        }
    }
    BUSY.store(false, Ordering::SeqCst);
    repaint();
}

// ── drawing helpers ─────────────────────────────────────────────────────────

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

unsafe fn select_null_pen(hdc: Hdc) -> *mut c_void {
    let null_pen = GetStockObject(NULL_PEN);
    SelectObject(hdc, null_pen)
}

unsafe fn text_in(hdc: Hdc, font: Hfont, color: u32, s: &str, rect: Rect, format: u32) {
    if s.is_empty() {
        return;
    }
    let old_pen = select_null_pen(hdc);
    let old_font = SelectObject(hdc, font);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, fade(color));
    let mut r = rect;
    let w = wide(s);
    DrawTextW(hdc, w.as_ptr(), -1, &mut r, format);
    SelectObject(hdc, old_font);
    SelectObject(hdc, old_pen);
}

/// Raw solid fill — no fade applied (used to paint the cached topo layer).
unsafe fn fill_raw(hdc: Hdc, color: u32, r: Rect) {
    let b = CreateSolidBrush(color);
    FillRect(hdc, &r, b);
    DeleteObject(b);
}

unsafe fn fill(hdc: Hdc, color: u32, r: Rect) {
    fill_raw(hdc, fade(color), r);
}

/// A rounded rect filled with `body` and ringed by `border` (1 px), the way the
/// reference draws every card and button. `radius` is in pixels; `RoundRect`
/// wants the full ellipse size, hence the doubling.
unsafe fn round_rect_ringed(hdc: Hdc, r: Rect, radius: i32, border: u32, body: u32) {
    let rad = (radius * 2).max(0);
    let old_pen = select_null_pen(hdc);
    let b1 = CreateSolidBrush(border);
    let old_brush = SelectObject(hdc, b1);
    RoundRect(hdc, r.left, r.top, r.right, r.bottom, rad, rad);
    let b2 = CreateSolidBrush(body);
    SelectObject(hdc, b2);
    DeleteObject(b1);
    RoundRect(hdc, r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, (rad - 2).max(0), (rad - 2).max(0));
    SelectObject(hdc, old_brush);
    SelectObject(hdc, old_pen);
    DeleteObject(b2);
}

/// DrawGlassCard: an opaque stand-in for the reference's translucent glass.
/// border @ 0.40 ring, glassHighlight @ 0.26 inner ring, panel @ 0.72 body.
unsafe fn draw_card(hdc: Hdc, r: Rect, radius: i32) {
    let rad = radius * 2;
    let old_pen = select_null_pen(hdc);

    let b1 = CreateSolidBrush(fade(C_CARD_BORDER));
    let old_brush = SelectObject(hdc, b1);
    RoundRect(hdc, r.left, r.top, r.right, r.bottom, rad, rad);

    let b2 = CreateSolidBrush(fade(C_CARD_INNER));
    SelectObject(hdc, b2);
    DeleteObject(b1);
    RoundRect(hdc, r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, rad - 2, rad - 2);

    let b3 = CreateSolidBrush(fade(C_CARD));
    SelectObject(hdc, b3);
    DeleteObject(b2);
    RoundRect(hdc, r.left + 2, r.top + 2, r.right - 2, r.bottom - 2, (rad - 4).max(0), (rad - 4).max(0));

    SelectObject(hdc, old_brush);
    SelectObject(hdc, old_pen);
    DeleteObject(b3);
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Btn {
    /// Filled with LINK — there is exactly one per screen.
    Primary,
    Secondary,
}

#[derive(Clone, Copy)]
struct BtnState {
    enabled: bool,
    hovered: bool,
    pressed: bool,
}

/// Rounded button: an outer rounded rect in the border colour with an inner one
/// in the fill colour on top, so it reads as a 1 px outline. The primary button
/// is filled with LINK and labelled in the dark contrast colour, exactly like
/// the reference (Screen.cpp:3154-3157).
unsafe fn draw_button(hdc: Hdc, font: Hfont, r: Rect, label: &str, kind: Btn, st: BtnState) {
    let (border, body, fg) = if !st.enabled {
        (C_CARD_BORDER, C_BTN_DISABLED, C_DIM)
    } else {
        match kind {
            Btn::Primary => {
                let fill = if st.hovered || st.pressed { C_LINK_HOVER } else { C_LINK };
                (C_LINK_HOVER, fill, C_ON_LINK)
            }
            Btn::Secondary => {
                let fill = if st.pressed {
                    C_BTN_ACTIVE
                } else if st.hovered {
                    C_BTN_HOVER
                } else {
                    C_CARD
                };
                let text = if st.pressed { C_STRONG } else { C_TEXT };
                (C_CARD_BORDER, fill, text)
            }
        }
    };
    round_rect_ringed(hdc, r, metrics::BUTTON_ROUNDING, border, body);
    text_in(hdc, font, fg, label, r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

/// A pill: a state-tinted fill with the state colour as its label, matching the
/// reference's status chips.
unsafe fn draw_chip(hdc: Hdc, font: Hfont, r: Rect, label: &str, state: [u8; 3]) {
    let rad = (r.bottom - r.top).max(2);
    let old_pen = select_null_pen(hdc);
    let brush = CreateSolidBrush(fade(theme::colorref(theme::chip_fill(state))));
    let old_brush = SelectObject(hdc, brush);
    RoundRect(hdc, r.left, r.top, r.right, r.bottom, rad, rad);
    SelectObject(hdc, old_brush);
    SelectObject(hdc, old_pen);
    DeleteObject(brush);
    text_in(hdc, font, theme::colorref(state), label, r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

unsafe fn draw_dot(hdc: Hdc, cx: i32, cy: i32, d: i32, color: u32) {
    let old_pen = select_null_pen(hdc);
    let brush = CreateSolidBrush(fade(color));
    let old_brush = SelectObject(hdc, brush);
    RoundRect(hdc, cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2, d, d);
    SelectObject(hdc, old_brush);
    SelectObject(hdc, old_pen);
    DeleteObject(brush);
}

/// A selectable pill card on the chooser: version / interface choices. Selected
/// pills get the LINK ring + tinted body + accent dot, like the reference's
/// selected rows; unselected ones are plain glass cards.
unsafe fn draw_pill(hdc: Hdc, r: Rect, main: &str, chip_label: &str, chip_color: [u8; 3], selected: bool, hovered: bool) {
    let f = fonts();
    if selected {
        round_rect_ringed(hdc, r, metrics::BUTTON_ROUNDING, C_LINK, C_PILL_SEL);
        draw_dot(hdc, r.left + 16, (r.top + r.bottom) / 2, 8, C_LINK);
    } else {
        let body = if hovered { C_BTN_HOVER } else { C_CARD };
        round_rect_ringed(hdc, r, metrics::BUTTON_ROUNDING, C_CARD_BORDER, body);
    }
    let tx = if selected { r.left + 30 } else { r.left + 16 };
    text_in(
        hdc,
        f.med,
        if selected { C_STRONG } else { C_TEXT },
        main,
        Rect { left: tx, top: r.top, right: r.right - 96, bottom: r.bottom },
        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
    );
    if !chip_label.is_empty() {
        let cw = 20 + chip_label.len() as i32 * 8;
        let chip = Rect {
            left: r.right - cw - 12,
            top: (r.top + r.bottom) / 2 - 11,
            right: r.right - 12,
            bottom: (r.top + r.bottom) / 2 + 11,
        };
        draw_chip(hdc, f.small, chip, chip_label, chip_color);
    }
}

/// One 1 px caption-glyph line (min dash / close ×).
unsafe fn caption_line(hdc: Hdc, x0: i32, y0: i32, x1: i32, y1: i32, color: u32) {
    let pen = CreatePen(PS_SOLID, 1, fade(color));
    let old = SelectObject(hdc, pen);
    MoveToEx(hdc, x0, y0, std::ptr::null_mut());
    LineTo(hdc, x1, y1);
    SelectObject(hdc, old);
    DeleteObject(pen);
}

/// The custom caption strip: brand on the left, version tag + min/close glyphs
/// on the right. Drawn last so it sits above every screen (intro included).
unsafe fn draw_title_bar(hdc: Hdc) {
    let f = fonts();
    let hover = *lock(&shared().hover);

    text_in(
        hdc,
        f.title,
        C_STRONG,
        "DOPPEL",
        Rect { left: PAD, top: 0, right: PAD + 140, bottom: TITLE_H },
        DT_LEFT | DT_VCENTER | DT_SINGLELINE,
    );
    text_in(
        hdc,
        f.small,
        C_LINK,
        "injector",
        Rect { left: PAD + 126, top: 0, right: PAD + 240, bottom: TITLE_H },
        DT_LEFT | DT_VCENTER | DT_SINGLELINE,
    );
    let tag = format!("v{}  ·  native launcher", env!("CARGO_PKG_VERSION"));
    text_in(
        hdc,
        f.small,
        C_DIM,
        &tag,
        Rect { left: CAP_MIN_RECT.left - 340, top: 0, right: CAP_MIN_RECT.left - 14, bottom: TITLE_H },
        DT_RIGHT | DT_VCENTER | DT_SINGLELINE,
    );
    fill(hdc, C_SEPARATOR, Rect { left: 0, top: TITLE_H - 1, right: CLIENT_W, bottom: TITLE_H });

    // minimize — a dash; hover fills like a panel button.
    let min_hov = point_in(hover, CAP_MIN_RECT);
    if min_hov {
        fill(hdc, C_BTN_HOVER, CAP_MIN_RECT);
    }
    let mcx = (CAP_MIN_RECT.left + CAP_MIN_RECT.right) / 2;
    let my = TITLE_H / 2 + 2;
    let min_ink = if min_hov { C_STRONG } else { C_TEXT };
    caption_line(hdc, mcx - 6, my, mcx + 6, my, min_ink);

    // close — an ×; hover turns the tile red, like the native caption.
    let close_hov = point_in(hover, CAP_CLOSE_RECT);
    if close_hov {
        fill(hdc, C_DANGER, CAP_CLOSE_RECT);
    }
    let ccx = (CAP_CLOSE_RECT.left + CAP_CLOSE_RECT.right) / 2;
    let ccy = TITLE_H / 2;
    let close_ink = if close_hov { C_STRONG } else { C_TEXT };
    caption_line(hdc, ccx - 5, ccy - 5, ccx + 5, ccy + 5, close_ink);
    caption_line(hdc, ccx + 5, ccy - 5, ccx - 5, ccy + 5, close_ink);
}

fn rail_item_rect(i: usize) -> Rect {
    let y = RAIL_RECT.top + 8 + (i as i32) * 34;
    Rect { left: RAIL_RECT.left, top: y, right: RAIL_RECT.right, bottom: y + 28 }
}

fn point_in(p: Point, r: Rect) -> bool {
    p.x >= r.left && p.x < r.right && p.y >= r.top && p.y < r.bottom
}

/// The list box asks for its background brush on every repaint, so keep one.
static LIST_BRUSH: OnceLock<usize> = OnceLock::new();

fn list_brush() -> Hbrush {
    let p = *LIST_BRUSH.get_or_init(|| unsafe { CreateSolidBrush(C_LIST_FILL) as usize });
    p as Hbrush
}

// ── topographic background ──────────────────────────────────────────────────
//
// Port of Screen::DrawTopographicBackground (Screen.cpp:2446-2532): fbm noise
// over a 120 x 90 grid, 14 contour levels, topographicLine at ~31 % over the
// background. The reference walks marching squares per frame; GDI is far too
// slow for that at 60 fps, so the whole layer is rendered ONCE into a memory
// bitmap at WM_CREATE and then either blitted (fade done) or AlphaBlend-ed with
// a constant source alpha (during the intro).

const GRID_W: i32 = 120;
const GRID_H: i32 = 90;
const LEVELS: i32 = 14;

static GRID: OnceLock<Vec<f32>> = OnceLock::new();

fn hash2(x: i32, y: i32) -> f32 {
    let mut h = (x as u32).wrapping_mul(0x9E37_79B9) ^ (y as u32).wrapping_mul(0x85EB_CA6B);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2545_F491);
    h ^= h >> 13;
    (h & 0xFFFF) as f32 / 65535.0
}

fn value_noise(x: f32, y: f32) -> f32 {
    let xi = x.floor();
    let yi = y.floor();
    let xf = x - xi;
    let yf = y - yi;
    let u = xf * xf * (3.0 - 2.0 * xf);
    let v = yf * yf * (3.0 - 2.0 * yf);
    let (i, j) = (xi as i32, yi as i32);
    let a = hash2(i, j);
    let b = hash2(i + 1, j);
    let c = hash2(i, j + 1);
    let d = hash2(i + 1, j + 1);
    let top = a + (b - a) * u;
    let bottom = c + (d - c) * u;
    top + (bottom - top) * v
}

fn fbm(x: f32, y: f32) -> f32 {
    let mut sum = 0.0;
    let mut amp = 0.5;
    let mut freq = 1.0;
    let mut norm = 0.0;
    for _ in 0..4 {
        sum += value_noise(x * freq, y * freq) * amp;
        norm += amp;
        freq *= 2.0;
        amp *= 0.5;
    }
    (sum / norm).clamp(0.0, 1.0)
}

fn noise_grid() -> &'static Vec<f32> {
    GRID.get_or_init(|| {
        let mut v = Vec::with_capacity(((GRID_W + 1) * (GRID_H + 1)) as usize);
        for gy in 0..=GRID_H {
            for gx in 0..=GRID_W {
                v.push(fbm(gx as f32 / 7.0, gy as f32 / 7.0));
            }
        }
        v
    })
}

unsafe fn contour_segment(hdc: Hdc, a: (f32, f32), b: (f32, f32), cw: f32, ch: f32) {
    MoveToEx(hdc, (a.0 * cw) as i32, (a.1 * ch) as i32, std::ptr::null_mut());
    LineTo(hdc, (b.0 * cw) as i32, (b.1 * ch) as i32);
}

unsafe fn draw_contours(hdc: Hdc) {
    let grid = noise_grid();
    let cw = CLIENT_W as f32 / GRID_W as f32;
    let ch = CLIENT_H as f32 / GRID_H as f32;
    let pen = CreatePen(PS_SOLID, 1, C_TOPO);
    let old_pen = SelectObject(hdc, pen);

    let stride = (GRID_W + 1) as usize;
    for lvl in 1..LEVELS {
        let iso = lvl as f32 / LEVELS as f32;
        for gy in 0..GRID_H {
            for gx in 0..GRID_W {
                let i = (gy as usize) * stride + gx as usize;
                let v00 = grid[i];
                let v10 = grid[i + 1];
                let v01 = grid[i + stride];
                let v11 = grid[i + stride + 1];

                let mut pts = [(0.0f32, 0.0f32); 4];
                let mut n = 0usize;
                if (v00 < iso) != (v10 < iso) {
                    pts[n] = (gx as f32 + (iso - v00) / (v10 - v00), gy as f32);
                    n += 1;
                }
                if (v10 < iso) != (v11 < iso) {
                    pts[n] = (gx as f32 + 1.0, gy as f32 + (iso - v10) / (v11 - v10));
                    n += 1;
                }
                if (v01 < iso) != (v11 < iso) {
                    pts[n] = (gx as f32 + (iso - v01) / (v11 - v01), gy as f32 + 1.0);
                    n += 1;
                }
                if (v00 < iso) != (v01 < iso) {
                    pts[n] = (gx as f32, gy as f32 + (iso - v00) / (v01 - v00));
                    n += 1;
                }
                if n == 2 {
                    contour_segment(hdc, pts[0], pts[1], cw, ch);
                } else if n == 4 {
                    // Saddle: pair the crossings in edge order. The reference
                    // resolves it exactly; at 0.8 px strokes the difference is
                    // not visible.
                    contour_segment(hdc, pts[0], pts[1], cw, ch);
                    contour_segment(hdc, pts[2], pts[3], cw, ch);
                }
            }
        }
    }
    SelectObject(hdc, old_pen);
    DeleteObject(pen);
}

/// Paint the window backdrop: the flat background plus the topographic layer at
/// the current intro alpha. The layer is cached because WM_MOUSEMOVE repaints
/// on every pointer move.
unsafe fn paint_topo(hdc: Hdc, alpha: f32) {
    fill_raw(hdc, C_BG, Rect { left: 0, top: 0, right: CLIENT_W, bottom: CLIENT_H });
    let src = TOPO_DC.get().map(|p| *p as Hdc).unwrap_or(std::ptr::null_mut());
    if src.is_null() {
        return;
    }
    if alpha >= 0.999 {
        BitBlt(hdc, 0, 0, CLIENT_W, CLIENT_H, src, 0, 0, SRCCOPY);
    } else if alpha > 0.0 {
        let ftn = BlendFunction {
            blend_op: AC_SRC_OVER,
            blend_flags: 0,
            source_constant_alpha: (alpha.clamp(0.0, 1.0) * 255.0) as u8,
            alpha_format: 0,
        };
        AlphaBlend(hdc, 0, 0, CLIENT_W, CLIENT_H, src, 0, 0, CLIENT_W, CLIENT_H, ftn);
    }
}

// ── fonts ───────────────────────────────────────────────────────────────────

struct Fonts {
    title: Hfont,
    med: Hfont,
    body: Hfont,
    small: Hfont,
    bold: Hfont,
    mono: Hfont,
}

static FONTS: OnceLock<usize> = OnceLock::new();

fn fonts() -> &'static Fonts {
    let ptr = *FONTS.get_or_init(|| unsafe {
        let face = wide("Segoe UI");
        let mono = wide("Consolas");
        let f = Fonts {
            title: CreateFontW(metrics::FONT_TITLE, 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            med: CreateFontW(metrics::FONT_MED, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            body: CreateFontW(metrics::FONT_BODY, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            small: CreateFontW(metrics::FONT_SMALL, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            bold: CreateFontW(metrics::FONT_BODY, 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            mono: CreateFontW(metrics::FONT_SMALL, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, mono.as_ptr()),
        };
        Box::into_raw(Box::new(f)) as usize
    });
    unsafe { &*(ptr as *const Fonts) }
}

// ── in-window GUI (panel): layout + interaction ─────────────────────────────

/// (url, token) of the agent panel, cloned out of the shared state.
fn panel_url_token() -> (String, String) {
    let st = lock(&shared().state);
    (st.panel_url.clone(), st.panel_token.clone())
}

/// Virtual-key code → the GLFW key code the agent's keybind API speaks
/// (same table the web panel builds from `event.key`: A–Z, 0–9, F1–F12,
/// Shift/Ctrl/Alt, Space/Enter/Tab; Esc = -1 clears the bind).
fn vk_to_glfw(vk: u32) -> Option<i32> {
    match vk {
        0x41..=0x5A => Some(vk as i32),                            // A..Z
        0x30..=0x39 => Some(vk as i32),                            // top-row 0..9
        0x60..=0x69 => Some((vk - 0x60 + b'0' as u32) as i32),     // numpad digits
        0x70..=0x7B => Some(290 + (vk - 0x70) as i32),             // F1..F12
        VK_SHIFT => Some(340),
        VK_CONTROL => Some(341),
        VK_MENU => Some(342),
        VK_SPACE => Some(32),
        VK_RETURN => Some(257),
        VK_TAB => Some(258),
        VK_ESCAPE => Some(-1),
        _ => None,
    }
}

/// Bind (or clear, code -1) a module's keybind: POST, then write the server's
/// keyName into the snapshot and fence it against one stale poll.
fn kb_bind(name: String, code: i32) {
    poke_interact();
    let (url, token) = panel_url_token();
    if url.is_empty() {
        return;
    }
    std::thread::spawn(move || {
        let ps = panel::state();
        match panel::post_keybind(&url, &token, &name, code) {
            Ok(kn) => {
                {
                    let mut mods = panel::lock(&ps.modules);
                    if let Some(m) = mods.iter_mut().find(|m| m.name == name) {
                        m.key_name = kn.clone();
                    }
                }
                lock(&KB_FENCE).push((name.clone(), kn, Instant::now() + Duration::from_secs(4)));
            }
            Err(e) => *panel::lock(&ps.last_err) = e,
        }
        repaint();
    });
}

fn snap_step(v: f64, step: f64) -> f64 {
    if step > 0.0 {
        (v / step).round() * step
    } else {
        v
    }
}

/// Decimal places an option displays with (server-declared, with fallbacks).
fn opt_dec(o: &panel::PanelOption) -> i32 {
    if o.decimals >= 0 {
        o.decimals
    } else if o.type_name == "FLOAT" {
        2
    } else {
        0
    }
}

/// Write one option's value into the published snapshot (optimistic edits).
fn set_option_value(key: &str, v: panel::Json) {
    let ps = panel::state();
    let mut mods = panel::lock(&ps.modules);
    for m in mods.iter_mut() {
        for o in m.options.iter_mut() {
            if o.key == key {
                o.value = v.clone();
            }
        }
    }
}

/// POST one option edit in the background; the next poll confirms it.
fn option_post(key: String, value_json: String) {
    poke_interact();
    let (url, token) = panel_url_token();
    if url.is_empty() {
        return;
    }
    std::thread::spawn(move || {
        if let Err(e) = panel::post_option(&url, &token, &key, &value_json) {
            *panel::lock(&panel::state().last_err) = e;
        }
        repaint();
    });
}

// ── option views: what the drawer renders ──

/// Which control an option row renders as. Min/Max pairs (the server keeps two
/// options, e.g. MinCPS + MaxCPS) collapse into one dual-thumb slider, exactly
/// like the web panel's merged Min~Max track.
enum OptView {
    Single(usize),
    Dual { min_i: usize, max_i: usize, base: String, lo: f64, hi: f64 },
}

/// Does the option apply to the mode the module is currently in? The server
/// declares `modes` per option; a Mode-type option's current value gates it.
fn option_visible(m: &panel::PanelModule, o: &panel::PanelOption) -> bool {
    if o.modes.is_empty() {
        return true;
    }
    for probe in ["Mode", "CooldownMode", "ClickMode", "CombatVersion"] {
        if let Some(mode_opt) = m.options.iter().find(|x| x.name == probe) {
            let v = mode_opt.value_label();
            if !v.is_empty() {
                return o.modes.iter().any(|mm| *mm == v);
            }
        }
    }
    true
}

/// Does the option belong to the unified trigger-condition engine? The
/// contract's TriggerOptions vocabulary (OnlyGround, OnlyMove*, OnlySprint,
/// OnLook, DisableOnMine, DisabledInAir…) plus the per-module filter flags
/// that feed it (OnlySword, OnlyPlayers, OnlyPlane, OnlyTargeting…). Same
/// name rule as the web panel's `isTriggerOption` — the two panels classify
/// in lockstep, and neither can drift from the modules because the rule IS
/// the vocabulary's shape.
fn is_condition_option(name: &str) -> bool {
    name.starts_with("Only")
        || name == "OnLook"
        || name == "DisableOnMine"
        || name == "DisabledInAir"
}

/// The summary the FOLDED trigger section shows in its header: a live COUNT of
/// the selected (on) conditions, "N selected" — numeric on purpose, so the
/// folded row stays one short line no matter how many conditions a module
/// declares. Port of the web panel's `trigSummaryText`; the two panels phrase
/// it identically.
fn condition_summary(m: &panel::PanelModule) -> String {
    let on = m
        .options
        .iter()
        .filter(|o| is_condition_option(&o.name) && o.value_bool())
        .count();
    format!("{on} selected")
}

/// The Min/Max twin of a numeric option, port of the web panel's sliderPairOf.
fn slider_pair(m: &panel::PanelModule, i: usize) -> Option<(usize, usize, String, f64, f64)> {
    let o = &m.options[i];
    if !o.is_numeric() {
        return None;
    }
    let low = o.name.to_ascii_lowercase();
    if low.len() <= 3 {
        return None;
    }
    let (base, is_min, suffix) = if low.ends_with("min") {
        (o.name[..o.name.len() - 3].to_string(), true, true)
    } else if low.ends_with("max") {
        (o.name[..o.name.len() - 3].to_string(), false, true)
    } else if low.starts_with("min") {
        (o.name[3..].to_string(), true, false)
    } else if low.starts_with("max") {
        (o.name[3..].to_string(), false, false)
    } else {
        return None;
    };
    if base.is_empty() {
        return None;
    }
    let other = if is_min { "max" } else { "min" };
    let twin_name = if suffix {
        format!("{}{}", base.to_ascii_lowercase(), other)
    } else {
        format!("{}{}", other, base.to_ascii_lowercase())
    };
    let twin = m.options.iter().position(|x| {
        x.key != o.key
            && x.is_numeric()
            && x.name.to_ascii_lowercase() == twin_name
            && x.unit == o.unit
    })?;
    let (mn, mx) = if is_min { (i, twin) } else { (twin, i) };
    let lo = m.options[mn].min.max(m.options[mx].min);
    let hi = m.options[mn].max.min(m.options[mx].max);
    if hi <= lo {
        return None;
    }
    Some((mn, mx, base, lo, hi))
}

fn option_views(m: &panel::PanelModule) -> Vec<OptView> {
    let visible: Vec<usize> = m
        .options
        .iter()
        .enumerate()
        .filter(|(_, o)| option_visible(m, o))
        .map(|(i, _)| i)
        .collect();
    let mut used = vec![false; m.options.len()];
    let mut out = Vec::new();
    for &i in &visible {
        if used[i] {
            continue;
        }
        if m.options[i].is_numeric() {
            if let Some((min_i, max_i, base, lo, hi)) = slider_pair(m, i) {
                used[min_i] = true;
                used[max_i] = true;
                out.push(OptView::Dual { min_i, max_i, base, lo, hi });
                continue;
            }
        }
        used[i] = true;
        out.push(OptView::Single(i));
    }
    out
}

/// Height of one settings-pane row for an option.
fn set_row_h(o: &panel::PanelOption) -> i32 {
    if o.is_numeric() {
        OPT_SLIDER_H
    } else if matches!(o.type_name.as_str(), "CHOICES" | "ENUM") {
        OPT_CHOICE_H
    } else {
        OPT_ROW_H
    }
}

/// The inline summary a card shows after its name — the first few notable
/// settings, VAPE-style: "17~20 CPS, Extra, Hold to click +2 more".
fn summary_of(m: &panel::PanelModule) -> String {
    let views = option_views(m);
    let mut parts: Vec<String> = Vec::new();
    for v in &views {
        if parts.len() == 3 {
            break;
        }
        match v {
            OptView::Dual { min_i, max_i, .. } => {
                let (mn, mx) = (&m.options[*min_i], &m.options[*max_i]);
                let unit = if mn.unit.is_empty() { String::new() } else { format!(" {}", mn.unit) };
                parts.push(format!(
                    "{}~{}{}",
                    fmt_opt_value(mn, mn.value_num().clamp(mn.min, mn.max)),
                    fmt_opt_value(mx, mx.value_num().clamp(mx.min, mx.max)),
                    unit
                ));
            }
            OptView::Single(i) => {
                let o = &m.options[*i];
                match o.type_name.as_str() {
                    "BOOLEAN" if o.value_bool() => parts.push(o.name.clone()),
                    "CHOICES" | "ENUM" => {
                        let v = o.value_label();
                        if !v.is_empty() {
                            parts.push(v);
                        }
                    }
                    _ if o.is_numeric() => parts.push(fmt_opt_value(o, o.value_num().clamp(o.min, o.max))),
                    _ => {}
                }
            }
        }
    }
    let rest = views.len().saturating_sub(parts.len());
    let mut s = parts.join(", ");
    if rest > 0 {
        if !s.is_empty() {
            s.push_str(", ");
        }
        s.push_str(&format!("+{rest} more"));
    }
    s
}

// ── the layout: one walk that paint and hit-testing share ──

/// One laid-out module card. Paint and the click handler both walk the same
/// list, so a click can never land a pixel away from what was drawn.
struct PanelRow {
    m: panel::PanelModule,
    summary: String,
    card: Rect,
    icon: Rect,
    name_r: Rect,
    summary_r: Rect,
    toggle: Rect,
    kebab: Rect,
    selected: bool,
}

/// The right-hand settings pane of the picked module (None = closed). Built
/// from the snapshot directly, so it stays open while the user browses any
/// rail category — exactly like the reference's centre window.
struct SetPane {
    name: String,
    key_label: String,
    capturing: bool,
    fav: bool,
    pane: Rect,
    back: Rect,
    star: Rect,
    kb_chip: Rect,
    views: Vec<OptView>,
    opts: Vec<panel::PanelOption>,
    /// (hit rect, index into views)
    hits: Vec<(Rect, usize)>,
    view_h: i32,
    total_h: i32,
    max_scroll: i32,
    /// The unified trigger-condition section: header row (None = this module
    /// has no trigger-classified option), whether it is unfolded, and the
    /// summary of the currently selected conditions the folded header shows.
    trig_header: Option<Rect>,
    trig_open: bool,
    trig_summary: String,
}

struct PanelLayout {
    rows: Vec<PanelRow>,
    list: Rect,
    settings: Option<SetPane>,
    view_h: i32,
    total_h: i32,
    max_scroll: i32,
}

fn panel_layout() -> PanelLayout {
    let ps = panel::state();
    let mods = panel::lock(&ps.modules);
    let cat = PANEL_CAT.load(Ordering::Relaxed) as usize;
    let sel = lock(&PANEL_SELECTED).clone();
    let favs = lock(&FAVORITES).clone();

    // The settings pane pushes the list narrower while it is open.
    let settings_open = sel
        .as_ref()
        .map(|name| mods.iter().any(|m| &m.name == name))
        .unwrap_or(false);
    let list = if settings_open { LIST_RECT_OPEN } else { LIST_RECT_SHUT };

    // ── settings pane (independent of the rail filter) ──
    let settings = sel.clone().and_then(|name| {
        mods.iter()
            .find(|m| m.name == name)
            .map(|m| {
                let capturing = lock(&KB_CAPTURE).as_deref() == Some(m.name.as_str());
                let key_label =
                    if capturing { "[press a key…]".to_string() } else { m.key_name.trim().to_string() };
                let views = option_views(m);
                // The unified trigger-condition section: which views belong to it,
                // and whether this module's section is unfolded right now.
                let view_is_trig: Vec<bool> = views
                    .iter()
                    .map(|v| match v {
                        OptView::Single(i) => is_condition_option(&m.options[*i].name),
                        OptView::Dual { .. } => false,
                    })
                    .collect();
                let has_trig = view_is_trig.iter().any(|b| *b);
                let trig_open = has_trig && lock(&TRIG_OPEN).iter().any(|n| n == &m.name);
                let trig_summary = condition_summary(m);
                let mut total = SET_ROWS_OFF + OPT_PAD;
                if has_trig {
                    total += OPT_ROW_H; // the section header row
                }
                for (vi, v) in views.iter().enumerate() {
                    if view_is_trig[vi] && !trig_open {
                        continue; // folded: the header carries the selection, no rows
                    }
                    total += match v {
                        OptView::Single(i) => set_row_h(&m.options[*i]),
                        OptView::Dual { .. } => OPT_SLIDER_H,
                    };
                }
                if views.is_empty() {
                    total += OPT_ROW_H; // the "no tunable options" line
                }
                total += OPT_PAD;
                let view_h = SETTINGS_RECT.bottom - SETTINGS_RECT.top;
                let max_scroll = (total - view_h).max(0);
                let scroll = OPT_SCROLL.load(Ordering::Relaxed).clamp(0, max_scroll);
                if scroll != OPT_SCROLL.load(Ordering::Relaxed) {
                    OPT_SCROLL.store(scroll, Ordering::Relaxed);
                }
                let mut hits = Vec::new();
                let mut y = SETTINGS_RECT.top + SET_ROWS_OFF - scroll;
                let mut trig_header: Option<Rect> = None;
                if has_trig {
                    trig_header = Some(Rect {
                        left: SETTINGS_RECT.left + OPT_PAD,
                        top: y,
                        right: SETTINGS_RECT.right - OPT_PAD,
                        bottom: y + OPT_ROW_H,
                    });
                    y += OPT_ROW_H;
                }
                for (vi, v) in views.iter().enumerate() {
                    if view_is_trig[vi] && !trig_open {
                        continue; // folded: no row, no hit rect, no height
                    }
                    let h = match v {
                        OptView::Single(i) => set_row_h(&m.options[*i]),
                        OptView::Dual { .. } => OPT_SLIDER_H,
                    };
                    hits.push((
                        Rect {
                            left: SETTINGS_RECT.left + OPT_PAD,
                            top: y,
                            right: SETTINGS_RECT.right - OPT_PAD,
                            bottom: y + h,
                        },
                        vi,
                    ));
                    y += h;
                }
                let kb_w = (18 + key_label.len() as i32 * 8).max(48);
                SetPane {
                    name: m.name.clone(),
                    key_label,
                    capturing,
                    fav: favs.iter().any(|f| f == &m.name),
                    pane: SETTINGS_RECT,
                    back: Rect {
                        left: SETTINGS_RECT.left + OPT_PAD,
                        top: SETTINGS_RECT.top + 8,
                        right: SETTINGS_RECT.left + OPT_PAD + 22,
                        bottom: SETTINGS_RECT.top + 32,
                    },
                    star: Rect {
                        left: SETTINGS_RECT.right - OPT_PAD - 20,
                        top: SETTINGS_RECT.top + 10,
                        right: SETTINGS_RECT.right - OPT_PAD,
                        bottom: SETTINGS_RECT.top + 30,
                    },
                    kb_chip: Rect {
                        left: SETTINGS_RECT.right - OPT_PAD - kb_w,
                        top: SETTINGS_RECT.top + 44,
                        right: SETTINGS_RECT.right - OPT_PAD,
                        bottom: SETTINGS_RECT.top + 66,
                    },
                    views,
                    opts: m.options.clone(),
                    hits,
                    view_h,
                    total_h: total,
                    max_scroll,
                    trig_header,
                    trig_open,
                    trig_summary,
                }
            })
    });

    // ── module cards ──
    let rail_key = RAIL_CATS.get(cat).copied().unwrap_or("");
    let shown: Vec<&panel::PanelModule> = match rail_key {
        "!FAV" => mods.iter().filter(|m| favs.iter().any(|f| f == &m.name)).collect(),
        "" => mods.iter().collect(),
        c => mods.iter().filter(|m| m.category == *c).collect(),
    };
    let view_h = list.bottom - list.top;
    let total_h = if shown.is_empty() { 0 } else { shown.len() as i32 * (CARD_H + CARD_GAP) - CARD_GAP };
    let max_scroll = (total_h + 16 - view_h).max(0);
    let scroll = PANEL_SCROLL.load(Ordering::Relaxed).clamp(0, max_scroll);
    if scroll != PANEL_SCROLL.load(Ordering::Relaxed) {
        PANEL_SCROLL.store(scroll, Ordering::Relaxed);
    }

    let mut rows = Vec::with_capacity(shown.len());
    let mut y = list.top + 8 - scroll;
    for m in shown {
        let card = Rect { left: list.left + 8, top: y, right: list.right - 10, bottom: y + CARD_H };
        let icon = Rect { left: card.left + 10, top: y + 10, right: card.left + 42, bottom: y + 42 };
        let kebab = Rect { left: card.right - 30, top: y + 16, right: card.right - 10, bottom: y + 36 };
        let toggle = Rect { left: card.right - 76, top: y + 17, right: card.right - 40, bottom: y + 35 };
        let name_w = (m.name.len() as i32 * 9).max(28);
        let name_r = Rect {
            left: icon.right + 12,
            top: y + 8,
            right: (icon.right + 12 + name_w).min(toggle.left - 8),
            bottom: y + 28,
        };
        let summary = summary_of(m);
        let summary_r = Rect {
            left: name_r.right + 8,
            top: y + 8,
            right: toggle.left - 10,
            bottom: y + 28,
        };
        rows.push(PanelRow {
            m: (*m).clone(),
            summary,
            card,
            icon,
            name_r,
            summary_r,
            toggle,
            kebab,
            selected: sel.as_deref() == Some(m.name.as_str()),
        });
        y += CARD_H + CARD_GAP;
    }
    PanelLayout { rows, list, settings, view_h, total_h, max_scroll }
}

/// Star/unstar a module in the local Favorites list (client-side only).
fn toggle_favorite(name: &str) {
    {
        let mut favs = lock(&FAVORITES);
        if let Some(pos) = favs.iter().position(|f| f == name) {
            favs.remove(pos);
        } else {
            favs.push(name.to_string());
        }
    }
    poke_interact();
    repaint();
}

/// Open a card's ⋮ menu: quick keybind + favorite (VAPE's kebab).
fn open_popup(m: &panel::PanelModule, kebab: Rect) {
    let w = 176;
    let item_h = 30;
    let left = (kebab.right - w).max(PAD);
    let top = (kebab.bottom + 4).min(CLIENT_H - 2 * item_h - 14);
    let rect = Rect { left, top, right: left + w, bottom: top + item_h * 2 + 12 };
    let bind = Rect { left: left + 6, top: top + 6, right: left + w - 6, bottom: top + 6 + item_h };
    let star = Rect { left: left + 6, top: bind.bottom + 4, right: left + w - 6, bottom: bind.bottom + 4 + item_h };
    let fav = lock(&FAVORITES).iter().any(|f| f == &m.name);
    *lock(&POPUP) = Some(Popup {
        module: m.name.clone(),
        key_label: m.key_name.trim().to_string(),
        fav,
        rect,
        bind,
        star,
    });
    poke_interact();
}

// ── panel drawing helpers ──

/// The on/off pill switch a module card uses. The knob scales with the pill,
/// so the same helper draws both sizes.
unsafe fn draw_switch(hdc: Hdc, r: Rect, on: bool) {
    let h = r.bottom - r.top;
    round_rect_ringed(hdc, r, h / 2, C_CARD_BORDER, if on { C_LINK } else { C_BTN_ACTIVE });
    let d = (h - 6).max(8);
    let kx = if on { r.right - d / 2 - 3 } else { r.left + d / 2 + 3 };
    draw_dot(hdc, kx, (r.top + r.bottom) / 2, d, if on { C_STRONG } else { C_DIM });
}

/// The BOOLEAN option tick box: 14 px rounded square — hollow tile when off,
/// accent body with a strong stroke check when on (the mockup's ESP rows;
/// same idiom as the switch's strong-on-link knob). Only option rows use it;
/// module cards keep the pill switch.
unsafe fn draw_check_box(hdc: Hdc, r: Rect, on: bool) {
    if !on {
        round_rect_ringed(hdc, r, 4, C_CARD_BORDER, C_BTN_ACTIVE);
        return;
    }
    round_rect_ringed(hdc, r, 4, C_LINK, C_LINK);
    icons::draw(hdc, "UI:check", "", r.left, r.top, r.right - r.left, theme::STRONG_TEXT);
}

/// A ghost icon button for the nav row: faint rounded tile on hover, glyph
/// centred (the glyph names live in icons.rs's UI: set).
unsafe fn draw_icon_btn(hdc: Hdc, r: Rect, glyph: &str, hovered: bool) {
    if hovered {
        round_rect_ringed(hdc, r, 8, C_CARD_BORDER, C_BTN_HOVER);
    }
    let tint = if hovered { theme::STRONG_TEXT } else { theme::SECONDARY_TEXT };
    let size = 16;
    icons::draw(
        hdc,
        glyph,
        "",
        r.left + (r.right - r.left - size) / 2,
        r.top + (r.bottom - r.top - size) / 2,
        size,
        tint,
    );
}

/// The Home pill of the nav row: back to every module (All), VAPE-style.
unsafe fn draw_home_pill(hdc: Hdc, font: Hfont, r: Rect, active: bool, hovered: bool) {
    let body = if active {
        C_PILL_SEL
    } else if hovered {
        C_BTN_HOVER
    } else {
        C_CARD
    };
    let ring = if active {
        theme::colorref(theme::mix(theme::BACKGROUND, theme::LINK, 45, 100))
    } else {
        C_CARD_BORDER
    };
    round_rect_ringed(hdc, r, 9, ring, body);
    let tint = if active { theme::LINK } else { theme::SECONDARY_TEXT };
    icons::draw(hdc, "UI:home", "", r.left + 10, (r.top + r.bottom) / 2 - 8, 16, tint);
    text_in(
        hdc,
        font,
        if active { C_STRONG } else { C_SECOND },
        "Home",
        Rect { left: r.left + 32, top: r.top, right: r.right - 8, bottom: r.bottom },
        DT_LEFT | DT_SINGLELINE | DT_VCENTER,
    );
}

/// The keybind chip: click → capture the next key. Capturing glows amber,
/// unbound chips go dim — same states the web panel's .kb-chip has.
unsafe fn draw_kb_chip(hdc: Hdc, font: Hfont, r: Rect, label: &str, capturing: bool, hovered: bool) {
    let state: [u8; 3] = if capturing {
        theme::WARNING
    } else if label.is_empty() || label == "NONE" {
        theme::MUTED_TEXT
    } else {
        theme::SECONDARY_TEXT
    };
    let body = theme::mix(theme::BACKGROUND, state, if hovered || capturing { 26 } else { 14 }, 100);
    let ring = theme::mix(theme::BACKGROUND, state, if capturing { 70 } else { 40 }, 100);
    round_rect_ringed(hdc, r, 10, theme::colorref(ring), theme::colorref(body));
    text_in(hdc, font, theme::colorref(state), label, r, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
}

/// The drawer chevron: down when collapsed, up when expanded.
unsafe fn draw_chevron(hdc: Hdc, r: Rect, up: bool, hovered: bool) {
    let cx = (r.left + r.right) / 2;
    let cy = (r.top + r.bottom) / 2;
    let color = if hovered { C_STRONG } else { C_DIM };
    if up {
        caption_line(hdc, cx - 5, cy - 2, cx, cy + 3, color);
        caption_line(hdc, cx, cy + 3, cx + 5, cy - 2, color);
    } else {
        caption_line(hdc, cx - 5, cy + 2, cx, cy - 3, color);
        caption_line(hdc, cx, cy - 3, cx + 5, cy + 2, color);
    }
}

/// One slider track (6 px, radius 3) with a filled range and thumbs.
/// `range` is the filled (lo_v..hi_v) segment; thumbs sit on each value.
unsafe fn draw_slider(hdc: Hdc, track: Rect, lo: f64, hi: f64, lo_v: f64, hi_v: f64, thumbs: &[f64]) {
    round_rect_ringed(hdc, track, 3, C_CARD_BORDER, C_BTN_ACTIVE);
    if hi > lo {
        let span = (track.right - track.left - 12) as f64;
        let x = |v: f64| track.left + 6 + (((v - lo) / (hi - lo)).clamp(0.0, 1.0) * span) as i32;
        let (xa, xb) = (x(lo_v.min(hi_v)), x(hi_v.max(lo_v)));
        if xb - xa > 4 {
            round_rect_ringed(hdc, Rect { left: xa, top: track.top + 2, right: xb, bottom: track.bottom - 2 }, 2, C_LINK, C_LINK);
        }
        for t in thumbs {
            draw_dot(hdc, x(*t), (track.top + track.bottom) / 2, 12, C_STRONG);
        }
    }
}

/// The value text of an option, formatted the way the web panel shows it.
fn fmt_opt_value(o: &panel::PanelOption, v: f64) -> String {
    let dec = opt_dec(o);
    let num = if o.type_name == "INT" {
        format!("{}", v.round() as i64)
    } else {
        format!("{:.*}", dec.max(0) as usize, v)
    };
    if o.unit.is_empty() {
        num
    } else {
        format!("{num} {}", o.unit)
    }
}

// ── option clicks + slider drags ──

/// A click on one settings-pane row (non-drag controls act immediately).
fn option_click(opts: &[panel::PanelOption], i: usize) {
    let o = &opts[i];
    match o.type_name.as_str() {
        "BOOLEAN" => {
            let now = !o.value_bool();
            set_option_value(&o.key, panel::Json::Bool(now));
            option_post(o.key.clone(), now.to_string());
            repaint();
        }
        "CHOICES" | "ENUM" => {
            let label = o.value_label();
            let next = match o.choices.iter().position(|c| *c == label) {
                Some(p) => o.choices[(p + 1) % o.choices.len()].clone(),
                None => o.choices.first().cloned().unwrap_or(label),
            };
            if !next.is_empty() {
                set_option_value(&o.key, panel::Json::Str(next.clone()));
                option_post(o.key.clone(), panel::json_string(&next));
            }
            repaint();
        }
        // Sliders start a drag instead (see drag_start); COLOR/STRING need a
        // text field, which is the web panel's job — they display read-only.
        _ => {}
    }
}

/// Begin dragging a single-value slider: seed the Drag, capture the mouse, and
/// treat this click as the first drag_move (the web panel does the same).
unsafe fn drag_start_single(opts: &[panel::PanelOption], i: usize, hr: Rect, p: Point) {
    let o = &opts[i];
    let track = Rect { left: hr.left + 2, top: hr.top + 28, right: hr.right - 2, bottom: hr.top + 34 };
    let v = o.value_num().clamp(o.min, o.max);
    *lock(&DRAG) = Some(Drag {
        dual: false,
        thumb_min: true,
        key_min: o.key.clone(),
        key_max: None,
        lo: o.min,
        hi: o.max,
        step: o.step,
        dec: opt_dec(o),
        int: o.type_name == "INT",
        vmin: v,
        vmax: 0.0,
        track,
        last_post: Instant::now() - Duration::from_millis(100),
    });
    if !main_hwnd().is_null() {
        SetCapture(main_hwnd());
    }
    drag_move(p);
}

/// Same for a dual Min~Max track: grab whichever thumb is nearer.
unsafe fn drag_start_dual(
    opts: &[panel::PanelOption],
    min_i: usize,
    max_i: usize,
    lo: f64,
    hi: f64,
    hr: Rect,
    p: Point,
) {
    let mn = &opts[min_i];
    let mx = &opts[max_i];
    let track = Rect { left: hr.left + 2, top: hr.top + 28, right: hr.right - 2, bottom: hr.top + 34 };
    let (vmin, vmax) = (mn.value_num().clamp(lo, hi), mx.value_num().clamp(lo, hi));
    let span = (track.right - track.left - 12).max(1) as f64;
    let x_of = |v: f64| track.left + 6 + (((v - lo) / (hi - lo)).clamp(0.0, 1.0) * span) as i32;
    let thumb_min = (p.x - x_of(vmin)).abs() <= (p.x - x_of(vmax)).abs();
    *lock(&DRAG) = Some(Drag {
        dual: true,
        thumb_min,
        key_min: mn.key.clone(),
        key_max: Some(mx.key.clone()),
        lo,
        hi,
        step: mn.step,
        dec: opt_dec(mn).max(opt_dec(mx)),
        int: mn.type_name == "INT" && mx.type_name == "INT",
        vmin,
        vmax,
        track,
        last_post: Instant::now() - Duration::from_millis(100),
    });
    if !main_hwnd().is_null() {
        SetCapture(main_hwnd());
    }
    drag_move(p);
}

/// Map the mouse position onto the dragged thumb, paint it at once, and POST
/// the value throttled to one write per 60 ms (the web panel's drag throttle).
fn drag_move(p: Point) {
    let (dual, thumb_min, key, val, json) = {
        let g = lock(&DRAG);
        let Some(d) = g.as_ref() else { return };
        let inner_l = d.track.left + 6;
        let inner_r = (d.track.right - 6).max(inner_l + 1);
        let frac = ((p.x - inner_l) as f64 / (inner_r - inner_l) as f64).clamp(0.0, 1.0);
        let v = snap_step(d.lo + frac * (d.hi - d.lo), d.step).clamp(d.lo, d.hi);
        let (dual, thumb_min, key, val) = if d.dual {
            if d.thumb_min {
                (true, true, d.key_min.clone(), v.min(d.vmax))
            } else {
                (true, false, d.key_max.clone().unwrap_or_default(), v.max(d.vmin))
            }
        } else {
            (false, true, d.key_min.clone(), v)
        };
        let json = if d.int {
            format!("{}", val.round() as i64)
        } else {
            format!("{:.*}", d.dec.max(0) as usize, val)
        };
        (dual, thumb_min, key, val, json)
    };
    set_option_value(&key, panel::Json::Num(val));
    repaint();
    // write throttled to one POST per 60 ms while the thumb moves; the exact
    // value lands with finish_drag()
    let due = {
        let mut g = lock(&DRAG);
        match g.as_mut() {
            Some(d) if d.last_post.elapsed() >= Duration::from_millis(60) => {
                d.last_post = Instant::now();
                if dual {
                    if thumb_min {
                        d.vmin = val;
                    } else {
                        d.vmax = val;
                    }
                } else {
                    d.vmin = val;
                }
                true
            }
            _ => false,
        }
    };
    if due {
        let (url, token) = panel_url_token();
        std::thread::spawn(move || {
            if let Err(e) = panel::post_option(&url, &token, &key, &json) {
                *panel::lock(&panel::state().last_err) = e;
            }
        });
    }
}

/// Mouse released: land the final exact value(s), release the capture.
fn finish_drag() {
    let Some(d) = lock(&DRAG).take() else { return };
    let mut posts: Vec<(String, String)> = Vec::new();
    let json = |v: f64| -> String {
        if d.int {
            format!("{}", v.round() as i64)
        } else {
            format!("{:.*}", d.dec.max(0) as usize, v)
        }
    };
    posts.push((d.key_min.clone(), json(d.vmin)));
    if d.dual {
        if let Some(km) = &d.key_max {
            posts.push((km.clone(), json(d.vmax)));
        }
    }
    poke_interact();
    std::thread::spawn(move || {
        let (url, token) = panel_url_token();
        for (key, value) in posts {
            if let Err(e) = panel::post_option(&url, &token, &key, &value) {
                *panel::lock(&panel::state().last_err) = e;
            }
        }
        repaint();
    });
    unsafe {
        if !main_hwnd().is_null() {
            ReleaseCapture();
        }
    }
    repaint();
}

// ── painting ────────────────────────────────────────────────────────────────

unsafe fn paint(hdc: Hdc) {
    let t = anim_ms();
    let app = lock(&shared().state).app;

    paint_topo(hdc, topo_alpha(t));

    if app == App::Intro {
        set_fade(splash_alpha(t));
        draw_splash(hdc, t);
    } else {
        set_fade(content_alpha(t));
        draw_main(hdc);
    }
    draw_title_bar(hdc);
    set_fade(1.0);
}

/// The intro: the wordmark fades up over the contour layer, an accent hairline
/// grows under it, then the whole thing fades out while the chooser fades in.
unsafe fn draw_splash(hdc: Hdc, t: f32) {
    let f = fonts();
    let cx = CLIENT_W / 2;
    let cy = CLIENT_H / 2;

    text_in(
        hdc,
        f.title,
        C_STRONG,
        "DOPPEL",
        Rect { left: 0, top: cy - 74, right: CLIENT_W, bottom: cy - 22 },
        DT_CENTER | DT_VCENTER | DT_SINGLELINE,
    );
    text_in(
        hdc,
        f.med,
        C_SECOND,
        "statistically you.",
        Rect { left: 0, top: cy - 16, right: CLIENT_W, bottom: cy + 18 },
        DT_CENTER | DT_VCENTER | DT_SINGLELINE,
    );

    let progress = ((t - 120.0) / (INTRO_MS - 160.0)).clamp(0.0, 1.0);
    let w = (240.0 * progress) as i32;
    if w > 2 {
        fill(hdc, C_LINK, Rect { left: cx - w / 2, top: cy + 38, right: cx + w / 2, bottom: cy + 40 });
    }

    let tag = format!("native launcher  ·  v{}", env!("CARGO_PKG_VERSION"));
    text_in(
        hdc,
        f.small,
        C_DIM,
        &tag,
        Rect { left: 0, top: cy + 54, right: CLIENT_W, bottom: cy + 78 },
        DT_CENTER | DT_SINGLELINE,
    );
}

unsafe fn draw_main(hdc: Hdc) {
    let f = fonts();

    let hover = *lock(&shared().hover);
    let pressed = *lock(&PRESSED);
    let state = lock(&shared().state);

    match state.app {
        App::Intro => {}
        App::Chooser => {
            let count = lock(&shared().procs).len();
            text_in(
                hdc,
                f.bold,
                C_STRONG,
                "Choose a target",
                Rect { left: PAD, top: 58, right: PAD + 400, bottom: 86 },
                DT_LEFT | DT_SINGLELINE | DT_VCENTER,
            );
            let sub = if count == 0 {
                "scanning for java processes…".to_string()
            } else {
                format!("{count} java processes · refreshes by itself")
            };
            text_in(
                hdc,
                f.small,
                C_SECOND,
                &sub,
                Rect { left: CLIENT_W - PAD - 380, top: 58, right: CLIENT_W - PAD, bottom: 86 },
                DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
            );

            // ── target version pills ──
            draw_pill(hdc, FORGE_RECT, "1.8.9", "Forge", theme::LINK, state.target == Target::Forge, point_in(hover, FORGE_RECT));
            draw_pill(hdc, FABRIC_RECT, "1.21.11", "Fabric", theme::SUCCESS, state.target == Target::Fabric, point_in(hover, FABRIC_RECT));
            text_in(
                hdc,
                f.small,
                C_DIM,
                "preferred target — the agent adapts to the process you pick",
                Rect { left: PAD + PILL_W * 2 + PILL_GAP + 28, top: VERSION_Y + 2, right: CLIENT_W - PAD, bottom: VERSION_Y + PILL_H - 2 },
                DT_LEFT | DT_WORDBREAK,
            );

            // ── interface pills: where the modules are controlled after the agent lands ──
            draw_pill(hdc, INWIN_RECT, "In-window GUI", "native", theme::LINK, state.gui == Gui::InWindow, point_in(hover, INWIN_RECT));
            draw_pill(hdc, WEBGUI_RECT, "Web GUI", "browser", theme::SUCCESS, state.gui == Gui::Web, point_in(hover, WEBGUI_RECT));
            text_in(
                hdc,
                f.small,
                C_DIM,
                "where you control the modules after the injection",
                Rect { left: PAD + PILL_W * 2 + PILL_GAP + 28, top: IFACE_Y + 2, right: CLIENT_W - PAD, bottom: IFACE_Y + PILL_H - 2 },
                DT_LEFT | DT_WORDBREAK,
            );

            // ── the card the child list box sits in ──
            text_in(
                hdc,
                f.bold,
                C_STRONG,
                "Java processes",
                Rect { left: PAD, top: LIST_Y - 30, right: PAD + 300, bottom: LIST_Y - 6 },
                DT_LEFT | DT_SINGLELINE | DT_VCENTER,
            );
            draw_card(hdc, Rect { left: LIST_X, top: LIST_Y, right: LIST_X + LIST_W, bottom: LIST_Y + LIST_H }, metrics::CARD_ROUNDING);

            let busy = BUSY.load(Ordering::SeqCst);
            let sel = *lock(&shared().selected);
            let refresh_hover = point_in(hover, REFRESH_RECT);
            draw_button(
                hdc,
                f.body,
                REFRESH_RECT,
                "Refresh",
                Btn::Secondary,
                BtnState { enabled: !busy, hovered: !busy && refresh_hover, pressed: !busy && pressed == Some(REFRESH_RECT) },
            );
            let inject_enabled = sel != 0 && !busy;
            draw_button(
                hdc,
                f.body,
                INJECT_RECT,
                if busy { "Working…" } else { "Inject" },
                Btn::Primary,
                BtnState {
                    enabled: inject_enabled,
                    hovered: inject_enabled && point_in(hover, INJECT_RECT),
                    pressed: inject_enabled && pressed == Some(INJECT_RECT),
                },
            );

            if sel == 0 {
                text_in(
                    hdc,
                    f.small,
                    C_DIM,
                    "Pick the Minecraft process above.",
                    Rect { left: PAD, top: HINT_Y, right: CLIENT_W - PAD, bottom: HINT_Y + 24 },
                    DT_LEFT | DT_SINGLELINE,
                );
            } else {
                let det = if state.detected.is_empty() {
                    String::new()
                } else {
                    format!(" — looks like {}", state.detected)
                };
                let detected_kind = state.detected.split('·').last().unwrap_or("").trim().to_ascii_lowercase();
                let matches = detected_kind.is_empty() || detected_kind == state.target.key();
                let (color, mismatch) = if matches {
                    (C_SUCCESS, "")
                } else {
                    (C_WARNING, " — differs from the pill above; the agent adapts anyway")
                };
                text_in(
                    hdc,
                    f.small,
                    color,
                    &format!("Selected PID {sel}{det}{mismatch} · relaunching the game is the only way to reload the agent."),
                    Rect { left: PAD, top: HINT_Y, right: CLIENT_W - PAD, bottom: HINT_Y + 24 },
                    DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS,
                );
            }
            let footer = "The agent cannot be unloaded from a running JVM: if Doppel is already in that process, restart Minecraft and inject the fresh one.";
            text_in(
                hdc,
                f.small,
                C_DIM,
                footer,
                Rect { left: PAD, top: FOOTER_Y, right: CLIENT_W - PAD, bottom: CLIENT_H - PAD },
                DT_LEFT | DT_WORDBREAK,
            );
        }
        App::Running | App::Done | App::Failed => {
            let (headline, head_color) = match state.app {
                App::Running => ("Injecting", C_STRONG),
                App::Done => ("Injected — the agent is running inside the game", C_SUCCESS),
                _ => ("Injection failed", C_DANGER),
            };
            text_in(
                hdc,
                f.bold,
                head_color,
                headline,
                Rect { left: PAD, top: 58, right: CLIENT_W - PAD - 160, bottom: 86 },
                DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
            );
            if state.pid != 0 {
                text_in(
                    hdc,
                    f.mono,
                    C_DIM,
                    &format!("PID {}", state.pid),
                    Rect { left: CLIENT_W - PAD - 150, top: 60, right: CLIENT_W - PAD, bottom: 84 },
                    DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
                );
            }

            // ── step timeline, on its own card ──
            draw_card(hdc, STEPS_CARD, metrics::CARD_ROUNDING);
            for (i, step) in state.steps.iter().enumerate() {
                let y = STEP_Y0 + i as i32 * STEP_DY;
                let dot = match step.state {
                    StepState::Pending => C_CARD_INNER,
                    StepState::Active => C_LINK,
                    StepState::Ok => C_SUCCESS,
                    StepState::Failed => C_DANGER,
                };
                draw_dot(hdc, PAD + 16, y + 8, 10, dot);
                let label_color = if step.state == StepState::Pending { C_DIM } else { C_TEXT };
                text_in(
                    hdc,
                    f.body,
                    label_color,
                    STEPS.get(i).copied().unwrap_or(""),
                    Rect { left: PAD + 38, top: y - 2, right: PAD + 330, bottom: y + 20 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                );
                if !step.note.is_empty() {
                    text_in(
                        hdc,
                        f.small,
                        C_DIM,
                        &step.note,
                        Rect { left: PAD + 340, top: y - 1, right: CLIENT_W - PAD - 74, bottom: y + 20 },
                        DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                    );
                }
                if step.secs > 0.0 {
                    let secs = format!("{:.1}s", step.secs);
                    text_in(
                        hdc,
                        f.mono,
                        C_DIM,
                        &secs,
                        Rect { left: CLIENT_W - PAD - 68, top: y - 1, right: CLIENT_W - PAD - 20, bottom: y + 20 },
                        DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
                    );
                }
            }

            // ── result card ──
            draw_card(hdc, RESULT_CARD, metrics::CARD_ROUNDING);
            let box_top = RESULT_CARD.top;
            let inner_left = RESULT_CARD.left + metrics::PAD_CARD;

            if state.app == App::Done && !state.panel_url.is_empty() {
                text_in(
                    hdc,
                    f.small,
                    C_DIM,
                    "PANEL",
                    Rect { left: inner_left, top: box_top + 20, right: inner_left + 80, bottom: box_top + 42 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                );
                text_in(
                    hdc,
                    f.mono,
                    C_LINK,
                    state.panel_url.as_str(),
                    Rect { left: inner_left + 88, top: box_top + 18, right: RESULT_CARD.right - metrics::PAD_CARD, bottom: box_top + 44 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                );
                text_in(
                    hdc,
                    f.small,
                    C_DIM,
                    "TOKEN",
                    Rect { left: inner_left, top: box_top + 62, right: inner_left + 80, bottom: box_top + 84 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                );
                text_in(
                    hdc,
                    f.mono,
                    C_WARNING,
                    if state.panel_token.is_empty() { "(see doppel-payload.log)" } else { state.panel_token.as_str() },
                    Rect { left: inner_left + 88, top: box_top + 60, right: RESULT_CARD.right - metrics::PAD_CARD, bottom: box_top + 86 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                );
                let panel_hint_text = if state.gui == Gui::InWindow {
                    "press Open panel to control the modules right here"
                } else {
                    "open that address in a browser to reach the in-game panel"
                };
                text_in(
                    hdc,
                    f.small,
                    C_DIM,
                    panel_hint_text,
                    Rect { left: inner_left, top: box_top + 104, right: RESULT_CARD.right - metrics::PAD_CARD, bottom: box_top + 126 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                );
            } else {
                let detail = if !state.detail.is_empty() {
                    state.detail.clone()
                } else {
                    state.events.last().map(|(_, t)| t.clone()).unwrap_or_default()
                };
                let color = if state.app == App::Failed { C_DANGER } else { C_TEXT };
                text_in(
                    hdc,
                    f.body,
                    color,
                    &detail,
                    Rect { left: inner_left, top: box_top + 22, right: RESULT_CARD.right - metrics::PAD_CARD, bottom: RESULT_CARD.bottom - 22 },
                    DT_LEFT | DT_WORDBREAK,
                );
            }

            let can_close = state.app != App::Running;
            let has_panel = !state.panel_url.is_empty();
            let open_enabled = can_close && has_panel;
            draw_button(
                hdc,
                f.body,
                BACK_RECT,
                "Back",
                Btn::Secondary,
                BtnState { enabled: can_close, hovered: can_close && point_in(hover, BACK_RECT), pressed: can_close && pressed == Some(BACK_RECT) },
            );
            draw_button(
                hdc,
                f.body,
                OPEN_RECT,
                if state.gui == Gui::InWindow { "Open panel" } else { "Open WebUI" },
                Btn::Primary,
                BtnState {
                    enabled: open_enabled,
                    hovered: open_enabled && point_in(hover, OPEN_RECT),
                    pressed: open_enabled && pressed == Some(OPEN_RECT),
                },
            );
            draw_button(
                hdc,
                f.body,
                HIDE_RECT,
                "Hide",
                Btn::Secondary,
                BtnState { enabled: can_close, hovered: can_close && point_in(hover, HIDE_RECT), pressed: can_close && pressed == Some(HIDE_RECT) },
            );
            draw_button(
                hdc,
                f.body,
                CLOSE_RECT,
                "Close",
                Btn::Secondary,
                BtnState { enabled: can_close, hovered: can_close && point_in(hover, CLOSE_RECT), pressed: can_close && pressed == Some(CLOSE_RECT) },
            );
        }
        App::Panel => {
            let ps = panel::state();
            let synced = panel::lock(&ps.last_ok).map(|t| t.elapsed().as_secs()).unwrap_or(u64::MAX);
            let last_err = panel::lock(&ps.last_err).clone();
            let _mods = panel::lock(&ps.modules);
            let layout = panel_layout();

            // ── nav row: ‹ back · Home · sync health · refresh / web / hide ──
            draw_icon_btn(hdc, NAV_BACK_RECT, "UI:back", point_in(hover, NAV_BACK_RECT));
            let home_active = PANEL_CAT.load(Ordering::Relaxed) as usize == 1;
            draw_home_pill(hdc, f.body, NAV_HOME_RECT, home_active, point_in(hover, NAV_HOME_RECT));
            let (st_rgb, st_text) = if last_err.is_empty() && synced < 60 {
                (theme::SUCCESS, format!("synced {synced}s ago"))
            } else if last_err.is_empty() {
                (theme::WARNING, "waiting for first sync…".to_string())
            } else {
                (theme::DANGER, "offline — retrying…".to_string())
            };
            draw_dot(hdc, NAV_STATUS_RIGHT - 226, NAV_Y + NAV_H / 2, 10, theme::colorref(st_rgb));
            text_in(
                hdc,
                f.small,
                theme::colorref(st_rgb),
                &st_text,
                Rect { left: NAV_STATUS_RIGHT - 216, top: NAV_Y, right: NAV_STATUS_RIGHT, bottom: NAV_Y + NAV_H },
                DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
            );
            draw_icon_btn(hdc, NAV_REFRESH_RECT, "UI:refresh", point_in(hover, NAV_REFRESH_RECT));
            draw_icon_btn(hdc, NAV_WEB_RECT, "UI:web", point_in(hover, NAV_WEB_RECT));
            draw_button(
                hdc,
                f.body,
                NAV_HIDE_RECT,
                "Hide",
                Btn::Secondary,
                BtnState { enabled: true, hovered: point_in(hover, NAV_HIDE_RECT), pressed: pressed == Some(NAV_HIDE_RECT) },
            );
            fill(hdc, C_SEPARATOR, Rect { left: PAD, top: PANEL_TOP - 8, right: CLIENT_W - PAD, bottom: PANEL_TOP - 7 });

            // ── category rail: text + active underline (VAPE style) ──
            let cat_sel = PANEL_CAT.load(Ordering::Relaxed) as usize;
            for (i, label) in RAIL_LABELS.iter().enumerate() {
                let rr = rail_item_rect(i);
                let is_sel = i == cat_sel;
                let key = RAIL_CATS[i];
                let tint = if key == "!FAV" {
                    theme::WARNING
                } else if key.is_empty() {
                    theme::LINK
                } else {
                    icons::cat_color(key)
                };
                text_in(
                    hdc,
                    if is_sel { f.bold } else { f.body },
                    if is_sel {
                        C_STRONG
                    } else if point_in(hover, rr) {
                        C_TEXT
                    } else {
                        C_SECOND
                    },
                    label,
                    Rect { left: rr.left + 4, top: rr.top, right: rr.right - 4, bottom: rr.bottom - 3 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                );
                if is_sel {
                    let w = (14 + label.len() as i32 * 8).min(rr.right - rr.left - 8);
                    fill(
                        hdc,
                        theme::colorref(tint),
                        Rect { left: rr.left + 5, top: rr.bottom - 5, right: rr.left + 5 + w, bottom: rr.bottom - 3 },
                    );
                }
            }

            // ── module cards: icon tile · name · summary · kebab · switch ──
            let list = layout.list;
            if layout.rows.is_empty() {
                let msg = if last_err.is_empty() {
                    if RAIL_CATS.get(cat_sel).copied().unwrap_or("") == "!FAV" {
                        "No favorites yet — open a module and tap the ★."
                    } else {
                        "loading modules from the agent…"
                    }
                } else {
                    "no modules — is the game still running?"
                };
                text_in(hdc, f.body, C_DIM, msg, list, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            } else {
                let saved = SaveDC(hdc);
                IntersectClipRect(hdc, list.left + 2, list.top + 2, list.right - 2, list.bottom - 2);
                for row in &layout.rows {
                    if row.card.bottom < list.top || row.card.top >= list.bottom {
                        continue;
                    }
                    let cat_rgb = icons::cat_color(&row.m.category);
                    let body = if row.selected {
                        theme::mix(theme::CARD_ON_BG, cat_rgb, 7, 100)
                    } else if point_in(hover, row.card) {
                        theme::mix(theme::CARD_ON_BG, theme::PANEL_ACTIVE, 50, 100)
                    } else {
                        theme::CARD_ON_BG
                    };
                    let ring = if row.selected {
                        theme::mix(theme::CARD_ON_BG, cat_rgb, 35, 100)
                    } else {
                        theme::CARD_BORDER
                    };
                    round_rect_ringed(hdc, row.card, 12, theme::colorref(ring), theme::colorref(body));
                    // icon tile: solid category tint when enabled, grey otherwise
                    let (tile_fill, tile_ring, ink) = if row.m.enabled {
                        (cat_rgb, theme::mix(cat_rgb, theme::PANEL, 55, 100), theme::BACKGROUND)
                    } else {
                        (
                            theme::BTN_ACTIVE,
                            theme::CARD_BORDER,
                            theme::mix(cat_rgb, theme::PANEL, 55, 100),
                        )
                    };
                    round_rect_ringed(hdc, row.icon, 9, theme::colorref(tile_ring), theme::colorref(tile_fill));
                    icons::draw(hdc, &row.m.name, &row.m.category, row.icon.left + 6, row.icon.top + 6, 20, ink);
                    // name + inline summary (VAPE's one-line row)
                    text_in(
                        hdc,
                        f.bold,
                        if row.m.enabled { C_STRONG } else { C_SECOND },
                        &row.m.name,
                        row.name_r,
                        DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                    );
                    if !row.summary.is_empty() {
                        text_in(
                            hdc,
                            f.small,
                            C_DIM,
                            &row.summary,
                            row.summary_r,
                            DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                        );
                    }
                    // kebab + switch
                    let k = if point_in(hover, row.kebab) { theme::STRONG_TEXT } else { theme::SECONDARY_TEXT };
                    icons::draw(hdc, "UI:kebab", "", row.kebab.left + 5, row.kebab.top + 4, 12, k);
                    draw_switch(hdc, row.toggle, row.m.enabled);
                }
                RestoreDC(hdc, saved);

                // scrollbar, proportional to the real content height
                if layout.max_scroll > 0 {
                    let track = Rect {
                        left: list.right - 7,
                        top: list.top + 12,
                        right: list.right - 5,
                        bottom: list.bottom - 12,
                    };
                    fill(hdc, C_SEPARATOR, track);
                    let track_h = track.bottom - track.top;
                    let th = ((track_h as f32) * (layout.view_h as f32 / layout.total_h.max(1) as f32)).max(24.0) as i32;
                    let scroll = PANEL_SCROLL.load(Ordering::Relaxed).clamp(0, layout.max_scroll);
                    let off = ((track_h - th) as f32 * (scroll as f32 / layout.max_scroll as f32)) as i32;
                    fill(hdc, C_DIM, Rect { left: track.left, top: track.top + off, right: track.right, bottom: track.top + off + th });
                }
            }

            // ── settings pane: ‹ name · ★ · keybind · one row per option ──
            if let Some(sp) = &layout.settings {
                round_rect_ringed(
                    hdc,
                    sp.pane,
                    12,
                    C_CARD_BORDER,
                    theme::colorref(theme::mix(theme::BACKGROUND, theme::PANEL, 50, 100)),
                );
                let hov_back = point_in(hover, sp.back);
                let b_tint = if hov_back { theme::STRONG_TEXT } else { theme::SECONDARY_TEXT };
                icons::draw(hdc, "UI:back", "", sp.back.left + 3, sp.back.top + 3, 16, b_tint);
                text_in(
                    hdc,
                    f.bold,
                    C_STRONG,
                    &sp.name,
                    Rect { left: sp.back.right + 8, top: sp.pane.top + 6, right: sp.star.left - 8, bottom: sp.pane.top + 34 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
                );
                let hov_star = point_in(hover, sp.star);
                let s_tint = if sp.fav {
                    theme::WARNING
                } else if hov_star {
                    theme::SECONDARY_TEXT
                } else {
                    theme::MUTED_TEXT
                };
                icons::draw(hdc, "UI:star", "", sp.star.left + 2, sp.star.top + 2, 16, s_tint);
                text_in(
                    hdc,
                    f.small,
                    C_ALT,
                    "Keybind",
                    Rect { left: sp.pane.left + OPT_PAD, top: sp.pane.top + 44, right: sp.kb_chip.left - 10, bottom: sp.pane.top + 66 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                );
                draw_kb_chip(hdc, f.mono, sp.kb_chip, &sp.key_label, sp.capturing, point_in(hover, sp.kb_chip));
                fill(
                    hdc,
                    C_SEPARATOR,
                    Rect { left: sp.pane.left + OPT_PAD, top: sp.pane.top + 76, right: sp.pane.right - OPT_PAD, bottom: sp.pane.top + 77 },
                );

                let saved = SaveDC(hdc);
                IntersectClipRect(hdc, sp.pane.left + 2, sp.pane.top + 2, sp.pane.right - 2, sp.pane.bottom - 2);
                // The unified trigger-condition section header: fold / unfold. The
                // summary of the selected conditions stays visible folded or not —
                // same contract as the web panel's .opt-group-head.
                if let Some(hr) = &sp.trig_header {
                    let hov = point_in(hover, *hr);
                    if hov {
                        fill(hdc, C_BTN_HOVER, *hr);
                    }
                    text_in(
                        hdc, f.small, C_DIM, "TRIGGER",
                        Rect { left: hr.left, top: hr.top, right: hr.left + 92, bottom: hr.bottom },
                        DT_LEFT | DT_VCENTER | DT_SINGLELINE,
                    );
                    text_in(
                        hdc, f.small, C_LINK, &sp.trig_summary,
                        Rect { left: hr.left + 94, top: hr.top, right: hr.right - 22, bottom: hr.bottom },
                        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                    );
                    draw_chevron(
                        hdc,
                        Rect { left: hr.right - 20, top: hr.top + 6, right: hr.right - 4, bottom: hr.bottom - 6 },
                        sp.trig_open,
                        hov,
                    );
                    fill(
                        hdc,
                        C_SEPARATOR,
                        Rect { left: hr.left, top: hr.bottom, right: hr.right, bottom: hr.bottom + 1 },
                    );
                }
                if sp.views.is_empty() {
                    text_in(
                        hdc,
                        f.small,
                        C_DIM,
                        "No tunable options — edit this one in the Web UI",
                        Rect {
                            left: sp.pane.left + OPT_PAD,
                            top: sp.pane.top + SET_ROWS_OFF,
                            right: sp.pane.right - OPT_PAD,
                            bottom: sp.pane.top + SET_ROWS_OFF + OPT_ROW_H,
                        },
                        DT_LEFT | DT_SINGLELINE,
                    );
                }
                let dragging = lock(&DRAG).clone();
                for (hr, vi) in &sp.hits {
                    if *vi >= sp.views.len() {
                        continue;
                    }
                    match &sp.views[*vi] {
                        OptView::Single(i) => {
                            let o = &sp.opts[*i];
                            match o.type_name.as_str() {
                                "BOOLEAN" => {
                                    text_in(
                                        hdc, f.small, C_ALT, &o.name,
                                        Rect { left: hr.left, top: hr.top, right: hr.right - 24, bottom: hr.bottom },
                                        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                                    );
                                    draw_check_box(
                                        hdc,
                                        Rect { left: hr.right - 14, top: hr.top + 7, right: hr.right, bottom: hr.top + 21 },
                                        o.value_bool(),
                                    );
                                }
                                "FLOAT" | "INT" | "PROBABILITY" => {
                                    text_in(
                                        hdc, f.small, C_ALT, &o.name,
                                        Rect { left: hr.left, top: hr.top, right: hr.right - 120, bottom: hr.top + 20 },
                                        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                                    );
                                    let v = dragging
                                        .as_ref()
                                        .and_then(|dg| if dg.key_min == o.key { Some(dg.vmin) } else { None })
                                        .unwrap_or_else(|| o.value_num().clamp(o.min, o.max));
                                    text_in(
                                        hdc, f.mono, C_LINK, &fmt_opt_value(o, v),
                                        Rect { left: hr.right - 110, top: hr.top + 1, right: hr.right, bottom: hr.top + 19 },
                                        DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
                                    );
                                    let track = Rect { left: hr.left + 2, top: hr.top + 28, right: hr.right - 2, bottom: hr.top + 34 };
                                    draw_slider(hdc, track, o.min, o.max, o.min, v, &[v]);
                                }
                                "CHOICES" | "ENUM" => {
                                    // VAPE's dropdown look: one full-width box.
                                    let label = o.value_label();
                                    let box_r = Rect { left: hr.left, top: hr.top + 2, right: hr.right, bottom: hr.top + 32 };
                                    let hov = point_in(hover, box_r);
                                    round_rect_ringed(hdc, box_r, 8, C_CARD_BORDER, if hov { C_BTN_HOVER } else { C_BTN_ACTIVE });
                                    text_in(
                                        hdc, f.small, if hov { C_STRONG } else { C_TEXT },
                                        &format!("{} · {}", o.name, label),
                                        Rect { left: box_r.left + 10, top: box_r.top, right: box_r.right - 24, bottom: box_r.bottom },
                                        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                                    );
                                    draw_chevron(
                                        hdc,
                                        Rect { left: box_r.right - 20, top: box_r.top, right: box_r.right - 4, bottom: box_r.bottom },
                                        false,
                                        false,
                                    );
                                }
                                "COLOR" => {
                                    text_in(
                                        hdc, f.small, C_ALT, &o.name,
                                        Rect { left: hr.left, top: hr.top, right: hr.right - 120, bottom: hr.bottom },
                                        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                                    );
                                    let v = o.value_num() as i64 & 0xFF_FFFF;
                                    let sw = theme::colorref([(v >> 16) as u8, (v >> 8) as u8, v as u8]);
                                    round_rect_ringed(
                                        hdc,
                                        Rect { left: hr.right - 110, top: hr.top + 7, right: hr.right - 94, bottom: hr.top + 23 },
                                        4, C_CARD_BORDER, sw,
                                    );
                                    text_in(
                                        hdc, f.mono, C_ALT, &o.value_label(),
                                        Rect { left: hr.right - 86, top: hr.top, right: hr.right, bottom: hr.bottom },
                                        DT_LEFT | DT_VCENTER | DT_SINGLELINE,
                                    );
                                }
                                _ => {
                                    text_in(
                                        hdc, f.small, C_ALT, &o.name,
                                        Rect { left: hr.left, top: hr.top, right: hr.right - 150, bottom: hr.bottom },
                                        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                                    );
                                    text_in(
                                        hdc, f.mono, C_DIM, &o.value_label(),
                                        Rect { left: hr.right - 140, top: hr.top, right: hr.right, bottom: hr.bottom },
                                        DT_RIGHT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                                    );
                                }
                            }
                        }
                        OptView::Dual { min_i, max_i, base, lo, hi } => {
                            let mn = &sp.opts[*min_i];
                            let mx = &sp.opts[*max_i];
                            let vm = dragging
                                .as_ref()
                                .and_then(|dg| if dg.key_min == mn.key { Some(dg.vmin) } else { None })
                                .unwrap_or_else(|| mn.value_num().clamp(*lo, *hi));
                            let vx = dragging
                                .as_ref()
                                .and_then(|dg| if dg.key_max.as_deref() == Some(mx.key.as_str()) { Some(dg.vmax) } else { None })
                                .unwrap_or_else(|| mx.value_num().clamp(*lo, *hi));
                            text_in(
                                hdc, f.small, C_ALT, base,
                                Rect { left: hr.left, top: hr.top, right: hr.right - 170, bottom: hr.top + 20 },
                                DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                            );
                            text_in(
                                hdc, f.mono, C_LINK,
                                &format!("{} ~ {}", fmt_opt_value(mn, vm), fmt_opt_value(mx, vx)),
                                Rect { left: hr.right - 160, top: hr.top + 1, right: hr.right, bottom: hr.top + 19 },
                                DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
                            );
                            let track = Rect { left: hr.left + 2, top: hr.top + 28, right: hr.right - 2, bottom: hr.top + 34 };
                            draw_slider(hdc, track, *lo, *hi, vm, vx, &[vm, vx]);
                        }
                    }
                }
                RestoreDC(hdc, saved);

                // settings scrollbar
                if sp.max_scroll > 0 {
                    let track = Rect {
                        left: sp.pane.right - 7,
                        top: sp.pane.top + 12,
                        right: sp.pane.right - 5,
                        bottom: sp.pane.bottom - 12,
                    };
                    fill(hdc, C_SEPARATOR, track);
                    let track_h = track.bottom - track.top;
                    let th = ((track_h as f32) * (sp.view_h as f32 / sp.total_h.max(1) as f32)).max(24.0) as i32;
                    let scroll = OPT_SCROLL.load(Ordering::Relaxed).clamp(0, sp.max_scroll);
                    let off = ((track_h - th) as f32 * (scroll as f32 / sp.max_scroll as f32)) as i32;
                    fill(hdc, C_DIM, Rect { left: track.left, top: track.top + off, right: track.right, bottom: track.top + off + th });
                }
            }

            // ── the open ⋮ menu, on top of everything ──
            let pop = lock(&POPUP).clone();
            if let Some(pop) = &pop {
                round_rect_ringed(hdc, pop.rect, 10, theme::colorref(theme::mix(theme::BACKGROUND, theme::LINK, 35, 100)), C_LIST_FILL);
                let hov1 = point_in(hover, pop.bind);
                if hov1 {
                    round_rect_ringed(hdc, pop.bind, 7, C_CARD_BORDER, C_BTN_HOVER);
                }
                icons::draw(hdc, "UI:key", "", pop.bind.left + 10, pop.bind.top + 8, 14, theme::SECONDARY_TEXT);
                text_in(
                    hdc, f.small, if hov1 { C_STRONG } else { C_TEXT },
                    &format!("Keybind · {}", pop.key_label),
                    Rect { left: pop.bind.left + 32, top: pop.bind.top, right: pop.bind.right - 6, bottom: pop.bind.bottom },
                    DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
                );
                let hov2 = point_in(hover, pop.star);
                if hov2 {
                    round_rect_ringed(hdc, pop.star, 7, C_CARD_BORDER, C_BTN_HOVER);
                }
                let st_tint = if pop.fav { theme::WARNING } else if hov2 { theme::SECONDARY_TEXT } else { theme::MUTED_TEXT };
                icons::draw(hdc, "UI:star", "", pop.star.left + 10, pop.star.top + 8, 14, st_tint);
                text_in(
                    hdc, f.small, if hov2 { C_STRONG } else { C_TEXT }, "Favorite",
                    Rect { left: pop.star.left + 32, top: pop.star.top, right: pop.star.right - 6, bottom: pop.star.bottom },
                    DT_LEFT | DT_VCENTER | DT_SINGLELINE,
                );
            }

            // ── footer: panel address + target summary ──
            text_in(
                hdc,
                f.mono,
                C_DIM,
                &state.panel_url,
                Rect { left: PAD, top: PANEL_BOTTOM + 10, right: CLIENT_W / 2 - 20, bottom: PANEL_BOTTOM + 28 },
                DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS,
            );
            let info = format!(
                "PID {} · {} · panel token {}",
                state.pid,
                if state.version.is_empty() { "—" } else { state.version.as_str() },
                if state.panel_token.is_empty() { "in doppel-payload.log" } else { "loaded" }
            );
            text_in(
                hdc,
                f.small,
                C_DIM,
                &info,
                Rect { left: CLIENT_W / 2, top: PANEL_BOTTOM + 10, right: CLIENT_W - PAD, bottom: PANEL_BOTTOM + 28 },
                DT_RIGHT | DT_SINGLELINE | DT_END_ELLIPSIS,
            );
        }
    }
}

// ── the process list box ────────────────────────────────────────────────────

unsafe fn sync_list() {
    let list = list_hwnd();
    if list.is_null() {
        return;
    }

    // Show the list only in the chooser state — it is a child window and would
    // otherwise sit on top of the step timeline. It also waits for the intro
    // fade, so the rows do not pop in at full brightness over a dark screen.
    let app = lock(&shared().state).app;
    let faded_in = content_alpha(anim_ms()) >= 0.999;
    let want_visible = app == App::Chooser && faded_in;
    if want_visible != LIST_VISIBLE.load(Ordering::SeqCst) {
        LIST_VISIBLE.store(want_visible, Ordering::SeqCst);
        ShowWindow(list, if want_visible { SW_SHOW } else { SW_HIDE });
    }
    if !want_visible {
        return;
    }

    let mut procs = lock(&shared().procs);
    // Preferred flavour first, then live Minecraft processes, then the rest —
    // computed AFTER sorting so the signature is stable across refreshes.
    let want = match lock(&shared().state).target {
        Target::Forge => "forge",
        Target::Fabric => "fabric",
    };
    procs.sort_by_key(|p| (p.injected, !p.is_minecraft, !p.is_minecraft || platform_hint(p) != want));
    let sig: String = procs
        .iter()
        .map(|p| format!("{}:{}:{}:{};", p.pid, p.exe, p.is_minecraft, p.injected))
        .collect();
    {
        let s = shared();
        let mut shown = lock(&s.shown_sig);
        if *shown == sig {
            return;
        }
        *shown = sig;
    }

    let selected = *lock(&shared().selected);
    SendMessageW(list, LB_RESETCONTENT, 0, 0);
    let mut restore: Option<usize> = None;
    let mut rows: Vec<Row> = Vec::with_capacity(procs.len());
    for (i, p) in procs.iter().enumerate() {
        let title = if !p.window_title.is_empty() {
            p.window_title.clone()
        } else if !p.cmdline_hint().is_empty() {
            p.cmdline_hint()
        } else {
            p.exe.clone()
        };
        let hint = platform_hint(p);
        let mut flags = String::new();
        let tag = if p.injected {
            flags.push_str("   [ALREADY LOADED]");
            "ALREADY LOADED".to_string()
        } else if p.is_minecraft && !hint.is_empty() {
            flags.push_str(&format!("   [{hint}]"));
            hint.to_string()
        } else if p.is_minecraft {
            flags.push_str("   [minecraft]");
            "minecraft".to_string()
        } else {
            String::new()
        };
        let line = format!("  {:>6}   {:<11} {}", p.pid, p.exe, title) + &flags;
        let w = wide(&line);
        SendMessageW(list, LB_ADDSTRING, 0, w.as_ptr() as Lparam);
        SendMessageW(list, LB_SETITEMDATA, i, p.pid as Lparam);
        rows.push(Row { pid: p.pid, exe: p.exe.clone(), title, tag });
        if p.pid == selected && restore.is_none() {
            restore = Some(i);
        }
    }
    *lock(&shared().rows) = rows;
    if let Some(i) = restore {
        SendMessageW(list, LB_SETCURSEL, i, 0);
    } else if selected == 0 {
        // Auto-pick the first Minecraft process for the preferred flavour, so
        // the common case is one click; fall back to any injectable one.
        let pick = procs
            .iter()
            .position(|p| p.is_minecraft && !p.injected && platform_hint(p) == want)
            .or_else(|| procs.iter().position(|p| p.is_minecraft && !p.injected));
        if let Some(i) = pick {
            SendMessageW(list, LB_SETCURSEL, i, 0);
            *lock(&shared().selected) = procs[i].pid;
        }
    }
}

/// Owner-draw one process row: muted PID, alt-coloured executable, body-coloured
/// title, and a state chip for [minecraft] / [ALREADY LOADED]. Selected rows get
/// the panel-active fill plus a LINK accent bar, matching the reference's list.
unsafe fn draw_row(d: &DrawItemStruct) {
    let rows = lock(&shared().rows);
    let Some(row) = rows.get(d.item_id as usize) else {
        return;
    };
    let r = d.rc_item;
    if r.bottom <= r.top {
        return;
    }
    let h = r.bottom - r.top;
    let selected = d.item_state & ODS_SELECTED != 0;

    fill(d.hdc, if selected { C_ROW_SELECTED } else { C_LIST_FILL }, r);
    if selected {
        fill(d.hdc, C_LINK, Rect { left: r.left, top: r.top, right: r.left + 3, bottom: r.bottom });
    }

    let f = fonts();
    let full = Rect { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
    text_in(
        d.hdc,
        f.mono,
        C_DIM,
        &format!("{:>6}", row.pid),
        Rect { left: r.left + 14, right: r.left + 100, ..full },
        DT_LEFT | DT_SINGLELINE | DT_VCENTER,
    );
    text_in(
        d.hdc,
        f.small,
        C_ALT,
        &row.exe,
        Rect { left: r.left + 104, right: r.left + 206, ..full },
        DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
    );
    let chip_w = if row.tag.is_empty() { 0 } else { 34 + row.tag.len() as i32 * 8 };
    text_in(
        d.hdc,
        f.body,
        if selected { C_STRONG } else { C_TEXT },
        &row.title,
        Rect { left: r.left + 214, right: r.right - 16 - chip_w, ..full },
        DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
    );
    if !row.tag.is_empty() {
        let (state, label) = if row.tag == "ALREADY LOADED" {
            (theme::WARNING, "ALREADY LOADED")
        } else if row.tag == "forge" {
            (theme::LINK, "forge")
        } else if row.tag == "fabric" {
            (theme::SUCCESS, "fabric")
        } else {
            (theme::SUCCESS, "minecraft")
        };
        let cw = 34 + label.len() as i32 * 8;
        let chip_h = 22;
        let chip = Rect {
            left: r.right - 16 - cw,
            top: r.top + (h - chip_h) / 2,
            right: r.right - 16,
            bottom: r.top + (h - chip_h) / 2 + chip_h,
        };
        draw_chip(d.hdc, f.small, chip, label, state);
    }
}

// ── window procedure ────────────────────────────────────────────────────────

/// One 16 ms animation tick: advance the intro, hand over to the chooser when
/// the splash is done, and stop the timer once everything is fully faded in.
/// Runs inside the normal message loop, so it never blocks and never touches
/// focus.
unsafe fn animate(hwnd: Hwnd) {
    let t = anim_ms();
    let switched = {
        let s = shared();
        let mut st = lock(&s.state);
        if st.app == App::Intro && t >= INTRO_MS {
            st.app = App::Chooser;
            true
        } else {
            false
        }
    };
    if switched {
        *lock(&shared().shown_sig) = String::new();
    }
    // sync_list() also gates the child list box on the fade being complete.
    sync_list();
    repaint();
    if t >= ANIM_END_MS {
        KillTimer(hwnd, TIMER_ANIM);
    }
}

unsafe extern "system" fn wnd_proc(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult {
    match msg {
        WM_CREATE => {
            let instance = GetModuleHandleW(std::ptr::null());
            let class = wide("LISTBOX");
            let list = CreateWindowExW(
                0,
                class.as_ptr(),
                std::ptr::null(),
                WS_CHILD
                    | WS_VISIBLE
                    | WS_VSCROLL
                    | WS_TABSTOP
                    | LBS_NOTIFY
                    | LBS_NOINTEGRALHEIGHT
                    | LBS_OWNERDRAWFIXED
                    | LBS_HASSTRINGS,
                LISTBOX_X,
                LISTBOX_Y,
                LISTBOX_W,
                LISTBOX_H,
                hwnd,
                std::ptr::null_mut(),
                instance,
                std::ptr::null_mut(),
            );
            let _ = HWND_LIST.set(list as usize);
            // TODO(B3): the rows are owner-drawn and carry the palette, but the
            // scrollbar is still the OS-drawn one and rows have no per-row
            // hover — a fully custom list (Screen.cpp:4094-4172) would fix both.
            SendMessageW(list, WM_SETFONT, fonts().body as Wparam, 1);
            SendMessageW(list, LB_SETITEMHEIGHT, 0, metrics::ROW_H as Lparam);

            // Bake the topographic layer once, in screen format, then blit it.
            let screen = GetDC(hwnd);
            if !screen.is_null() {
                let mem = CreateCompatibleDC(screen);
                let bmp = CreateCompatibleBitmap(screen, CLIENT_W, CLIENT_H);
                SelectObject(mem, bmp);
                fill_raw(mem, C_BG, Rect { left: 0, top: 0, right: CLIENT_W, bottom: CLIENT_H });
                draw_contours(mem);
                let _ = TOPO_DC.set(mem as usize);
                ReleaseDC(hwnd, screen);
            }

            SetTimer(hwnd, TIMER_LIST, 250, std::ptr::null());
            SetTimer(hwnd, TIMER_ANIM, 16, std::ptr::null());
            // The list box starts hidden behind the intro.
            sync_list();
            0
        }
        WM_TIMER => {
            if wp == TIMER_ANIM {
                animate(hwnd);
            } else {
                sync_list();
                repaint();
            }
            0
        }
        WM_ERASEBKGND => 1,
        WM_PAINT => {
            let mut ps: PaintStruct = std::mem::zeroed();
            let hdc = BeginPaint(hwnd, &mut ps);
            // Double buffer: the step rows repaint four times a second and
            // drawing straight to the window flickers.
            let mem = CreateCompatibleDC(hdc);
            let bmp = CreateCompatibleBitmap(hdc, CLIENT_W, CLIENT_H);
            let old = SelectObject(mem, bmp);
            paint(mem);
            BitBlt(hdc, 0, 0, CLIENT_W, CLIENT_H, mem, 0, 0, SRCCOPY);
            SelectObject(mem, old);
            DeleteObject(bmp);
            DeleteDC(mem);
            EndPaint(hwnd, &ps);
            0
        }
        WM_MEASUREITEM => {
            let m = &mut *(lp as *mut MeasureItemStruct);
            m.item_height = metrics::ROW_H as u32;
            1
        }
        WM_DRAWITEM => {
            let d = &*(lp as *const DrawItemStruct);
            draw_row(d);
            1
        }
        WM_CTLCOLORLISTBOX => {
            let hdc = wp as Hdc;
            SetTextColor(hdc, C_TEXT);
            SetBkColor(hdc, C_LIST_FILL);
            list_brush() as Lresult
        }
        WM_COMMAND => {
            if ((wp >> 16) as u32) == LBN_SELCHANGE {
                let list = list_hwnd();
                let sel = SendMessageW(list, LB_GETCURSEL, 0, 0);
                if sel >= 0 {
                    let pid = SendMessageW(list, LB_GETITEMDATA, sel as usize, 0) as u32;
                    *lock(&shared().selected) = pid;
                    // What does the picked process look like? The real answer
                    // comes from version.rs, which reads files — keep it off
                    // the UI thread and light the hint up when it lands.
                    let found = lock(&shared().procs)
                        .iter()
                        .find(|p| p.pid == pid)
                        .map(|p| (p.path.clone(), p.window_title.clone()));
                    if let Some((path, title)) = found {
                        std::thread::spawn(move || {
                            let info = version::parse_minecraft_version(&path, &title);
                            let s = shared();
                            lock(&s.state).detected = if info.valid {
                                format!("{} · {}", info.version, info.platform)
                            } else {
                                String::new()
                            };
                            repaint();
                        });
                    }
                    repaint();
                }
            }
            0
        }
        WM_NCCALCSIZE => {
            // Collapse the client onto the whole window: the standard caption —
            // the white box the old build showed — disappears, and the strip
            // drawn at the top of paint() takes over.
            if wp != 0 {
                0
            } else {
                unsafe { DefWindowProcW(hwnd, msg, wp, lp) }
            }
        }
        WM_NCHITTEST => {
            // The strip drags the window; the caption buttons stay clickable;
            // everything below the strip is plain client.
            let mut pt = Point {
                x: (lp & 0xFFFF) as u16 as i16 as i32,
                y: ((lp >> 16) & 0xFFFF) as u16 as i16 as i32,
            };
            unsafe { ScreenToClient(hwnd, &mut pt) };
            if pt.y < TITLE_H && !point_in(pt, CAP_CLOSE_RECT) && !point_in(pt, CAP_MIN_RECT) {
                HTCAPTION
            } else {
                HTCLIENT
            }
        }
        WM_MOUSEWHEEL => {
            if lock(&shared().state).app == App::Panel {
                let delta = ((wp >> 16) as u16) as i16 as i32;
                *lock(&POPUP) = None;
                // route by the last known hover: over the settings pane the
                // wheel scrolls the pane's options, otherwise the card list
                let layout = panel_layout();
                let h = *lock(&shared().hover);
                let over_settings = layout
                    .settings
                    .as_ref()
                    .map(|sp| point_in(h, sp.pane))
                    .unwrap_or(false);
                if over_settings {
                    OPT_SCROLL.fetch_sub(delta / 120 * 44, Ordering::Relaxed);
                } else {
                    PANEL_SCROLL.fetch_sub(delta / 120 * (CARD_H + CARD_GAP), Ordering::Relaxed);
                }
                repaint();
            }
            0
        }
        WM_KEYDOWN => {
            // Keybind capture: the panel is waiting for the next keypress. The
            // mapped keys are the same table the web panel builds from event.key.
            let name = lock(&KB_CAPTURE).clone();
            match name {
                Some(name) => {
                    if let Some(code) = vk_to_glfw((wp & 0xFF) as u32) {
                        *lock(&KB_CAPTURE) = None;
                        kb_bind(name, code);
                    }
                    0 // swallow keys (incl. auto-repeat) while capturing
                }
                None => unsafe { DefWindowProcW(hwnd, msg, wp, lp) },
            }
        }
        WM_MOUSEMOVE => {
            let x = (lp & 0xFFFF) as u16 as i16 as i32;
            let y = ((lp >> 16) & 0xFFFF) as u16 as i16 as i32;
            // A slider drag owns the pointer until the button comes up.
            if lock(&DRAG).is_some() {
                drag_move(Point { x, y });
                return 0;
            }
            let mut hover = lock(&shared().hover);
            if hover.x != x || hover.y != y {
                *hover = Point { x, y };
                drop(hover);
                repaint();
            }
            0
        }
        WM_SETCURSOR => {
            let cursor = LoadCursorW(std::ptr::null_mut(), IDC_ARROW as *const u16);
            SetCursor(cursor);
            1
        }
        WM_LBUTTONUP => {
            if lock(&DRAG).is_some() {
                finish_drag();
            }
            if lock(&PRESSED).take().is_some() {
                repaint();
            }
            0
        }
        WM_LBUTTONDOWN => {
            let p = Point {
                x: (lp & 0xFFFF) as u16 as i16 as i32,
                y: ((lp >> 16) & 0xFFFF) as u16 as i16 as i32,
            };
            // Custom caption buttons — they live above every screen.
            if point_in(p, CAP_CLOSE_RECT) {
                PostQuitMessage(0);
                return 0;
            }
            if point_in(p, CAP_MIN_RECT) {
                hide_launcher();
                return 0;
            }
            let busy = BUSY.load(Ordering::SeqCst);
            let app = lock(&shared().state).app;
            match app {
                App::Intro => {
                    // Click through the splash: jump to the content fade.
                    skip_intro();
                    repaint();
                }
                App::Chooser => {
                    if point_in(p, FORGE_RECT) || point_in(p, FABRIC_RECT) {
                        lock(&shared().state).target = if point_in(p, FORGE_RECT) { Target::Forge } else { Target::Fabric };
                    } else if point_in(p, INWIN_RECT) || point_in(p, WEBGUI_RECT) {
                        lock(&shared().state).gui = if point_in(p, INWIN_RECT) { Gui::InWindow } else { Gui::Web };
                    } else if point_in(p, REFRESH_RECT) && !busy {
                        *lock(&PRESSED) = Some(REFRESH_RECT);
                        let scanned = flow::candidates();
                        let s = shared();
                        *lock(&s.procs) = scanned;
                        *lock(&s.shown_sig) = String::new();
                    } else if point_in(p, INJECT_RECT) && !busy {
                        let pid = *lock(&shared().selected);
                        if pid != 0 {
                            *lock(&PRESSED) = Some(INJECT_RECT);
                        }
                        if pid != 0 && !BUSY.swap(true, Ordering::SeqCst) {
                            {
                                let s = shared();
                                let mut st = lock(&s.state);
                                st.app = App::Running;
                                st.pid = pid;
                                st.detail.clear();
                                st.panel_url.clear();
                                st.panel_token.clear();
                                st.version.clear();
                                for step in st.steps.iter_mut() {
                                    *step = StepInfo::default();
                                }
                                st.events.clear();
                            }
                            push_event("info", format!("target PID {}", pid));
                            std::thread::spawn(move || run_injection(pid));
                        }
                    }
                    repaint();
                }
                App::Running => {}
                App::Panel => {
                    let layout = panel_layout();
                    // Any click that is not the armed chip itself disarms a
                    // pending keybind capture (web panel's cancelCapture).
                    if let Some(cname) = lock(&KB_CAPTURE).clone() {
                        let armed = layout
                            .settings
                            .as_ref()
                            .map(|sp| sp.name == cname && point_in(p, sp.kb_chip))
                            .unwrap_or(false);
                        if !armed {
                            *lock(&KB_CAPTURE) = None;
                        }
                    }
                    // The open ⋮ menu owns this click: its items act, anything
                    // else just closes it.
                    let pop = lock(&POPUP).clone();
                    if let Some(pop) = pop {
                        if point_in(p, pop.bind) {
                            // quick bind: arm capture AND surface the settings
                            // pane, so "[press a key…]" is visible while typing
                            *lock(&PANEL_SELECTED) = Some(pop.module.clone());
                            OPT_SCROLL.store(0, Ordering::Relaxed);
                            *lock(&KB_CAPTURE) = Some(pop.module.clone());
                            poke_interact();
                        } else if point_in(p, pop.star) {
                            toggle_favorite(&pop.module);
                        }
                        *lock(&POPUP) = None;
                    } else if point_in(p, NAV_BACK_RECT) {
                        *lock(&KB_CAPTURE) = None;
                        *lock(&POPUP) = None;
                        lock(&shared().state).app = App::Chooser;
                        *lock(&shared().shown_sig) = String::new();
                    } else if point_in(p, NAV_HOME_RECT) {
                        // Home: every module, list rewound, settings closed.
                        PANEL_CAT.store(1, Ordering::Relaxed);
                        PANEL_SCROLL.store(0, Ordering::Relaxed);
                        *lock(&PANEL_SELECTED) = None;
                        *lock(&POPUP) = None;
                    } else if point_in(p, NAV_REFRESH_RECT) {
                        spawn_poll_now();
                    } else if point_in(p, NAV_WEB_RECT) {
                        let url = lock(&shared().state).panel_url.clone();
                        open_url(&url);
                    } else if point_in(p, NAV_HIDE_RECT) {
                        hide_launcher();
                        return 0;
                    } else {
                        let mut rail_hit = false;
                        for i in 0..RAIL_CATS.len() {
                            if point_in(p, rail_item_rect(i)) {
                                PANEL_CAT.store(i as u32, Ordering::Relaxed);
                                PANEL_SCROLL.store(0, Ordering::Relaxed);
                                *lock(&PANEL_SELECTED) = None;
                                *lock(&POPUP) = None;
                                rail_hit = true;
                                break;
                            }
                        }
                        if !rail_hit {
                            // the settings pane first (its rows live there)
                            let mut set_hit = false;
                            if let Some(sp) = &layout.settings {
                                if point_in(p, sp.pane) {
                                    set_hit = true;
                                    if point_in(p, sp.back) {
                                        *lock(&PANEL_SELECTED) = None;
                                        OPT_SCROLL.store(0, Ordering::Relaxed);
                                    } else if point_in(p, sp.star) {
                                        toggle_favorite(&sp.name);
                                    } else if point_in(p, sp.kb_chip) {
                                        // (re)arm capture: the next keypress binds
                                        *lock(&KB_CAPTURE) = Some(sp.name.clone());
                                        poke_interact();
                                    } else if sp.trig_header.map(|hr| point_in(p, hr)).unwrap_or(false) {
                                        // Fold / unfold the trigger-condition section. The
                                        // folded header carries the live selection, so nothing
                                        // is hidden either way (web panel parity).
                                        let mut open_set = lock(&TRIG_OPEN);
                                        match open_set.iter().position(|n| n == &sp.name) {
                                            Some(pos) => {
                                                open_set.remove(pos);
                                            }
                                            None => open_set.push(sp.name.clone()),
                                        }
                                        drop(open_set);
                                        poke_interact();
                                    } else {
                                        for (hr, vi) in &sp.hits {
                                            if !point_in(p, *hr) {
                                                continue;
                                            }
                                            poke_interact();
                                            match &sp.views[*vi] {
                                                OptView::Single(i) => {
                                                    let o = &sp.opts[*i];
                                                    if o.is_numeric() {
                                                        unsafe { drag_start_single(&sp.opts, *i, *hr, p) };
                                                    } else if matches!(
                                                        o.type_name.as_str(),
                                                        "BOOLEAN" | "CHOICES" | "ENUM"
                                                    ) {
                                                        option_click(&sp.opts, *i);
                                                    }
                                                    // COLOR / STRING: display-only here
                                                }
                                                OptView::Dual { min_i, max_i, lo, hi, .. } => {
                                                    unsafe {
                                                        drag_start_dual(&sp.opts, *min_i, *max_i, *lo, *hi, *hr, p)
                                                    };
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                            if !set_hit {
                                // a module card: switch toggles, kebab opens the
                                // menu, everything else opens the settings pane
                                if let Some(row) = layout.rows.iter().find(|row| point_in(p, row.card)) {
                                    if point_in(p, row.toggle) {
                                        panel_toggle(&row.m.name);
                                    } else if point_in(p, row.kebab) {
                                        open_popup(&row.m, row.kebab);
                                    } else {
                                        let name = row.m.name.clone();
                                        let mut sel = lock(&PANEL_SELECTED);
                                        if sel.as_deref() != Some(name.as_str()) {
                                            OPT_SCROLL.store(0, Ordering::Relaxed);
                                        }
                                        *sel = Some(name);
                                        drop(sel);
                                        *lock(&POPUP) = None;
                                        poke_interact();
                                    }
                                }
                            }
                        }
                    }
                    repaint();
                }
                _ => {
                    let can_close = app == App::Done || app == App::Failed;
                    let has_panel = !lock(&shared().state).panel_url.is_empty();
                    if point_in(p, CLOSE_RECT) {
                        if can_close {
                            *lock(&PRESSED) = Some(CLOSE_RECT);
                            PostQuitMessage(0);
                        }
                    } else if point_in(p, OPEN_RECT) {
                        if can_close && has_panel {
                            *lock(&PRESSED) = Some(OPEN_RECT);
                            let gui = lock(&shared().state).gui;
                            if gui == Gui::InWindow {
                                lock(&shared().state).app = App::Panel;
                                spawn_panel_poller();
                            } else {
                                let url = lock(&shared().state).panel_url.clone();
                                open_url(&url);
                            }
                        }
                    } else if point_in(p, HIDE_RECT) {
                        if can_close {
                            *lock(&PRESSED) = Some(HIDE_RECT);
                            hide_launcher();
                        }
                    } else if point_in(p, BACK_RECT) {
                        if can_close {
                            *lock(&PRESSED) = Some(BACK_RECT);
                            let s = shared();
                            let mut st = lock(&s.state);
                            st.app = App::Chooser;
                            st.detail.clear();
                            st.panel_url.clear();
                            st.panel_token.clear();
                            st.pid = 0;
                            drop(st);
                            *lock(&s.shown_sig) = String::new();
                        }
                    }
                    repaint();
                }
            }
            0
        }
        WM_CLOSE | WM_DESTROY => {
            KillTimer(hwnd, TIMER_LIST);
            KillTimer(hwnd, TIMER_ANIM);
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}

// ── entry point ─────────────────────────────────────────────────────────────

/// Create the launcher window and pump messages until it closes.
pub fn run() -> i32 {
    unsafe {
        let instance = GetModuleHandleW(std::ptr::null());
        if !CLASS_REGISTERED.swap(true, Ordering::SeqCst) {
            let class_name = wide("DoppelLauncherWnd");
            let mut wc: WndClassExW = std::mem::zeroed();
            wc.cb_size = std::mem::size_of::<WndClassExW>() as u32;
            wc.style = 0x0001 /* CS_VREDRAW */ | 0x0002 /* CS_HREDRAW */;
            wc.lpfn_wnd_proc = Some(wnd_proc);
            wc.h_instance = instance;
            wc.h_cursor = LoadCursorW(std::ptr::null_mut(), IDC_ARROW as *const u16);
            wc.lpsz_class_name = class_name.as_ptr();
            if RegisterClassExW(&wc) == 0 {
                ui::error("could not register the launcher window class");
                return 1;
            }
        }

        let style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
        // WM_NCCALCSIZE collapses the client onto the whole window, so the
        // outer size IS the client — no AdjustWindowRect, no white frame.
        // TODO(B3): the client is the reference's fixed 900x550 and the frame is
        // not DPI-scaled (GetDpiForWindow); Windows bitmap-stretches the window
        // on a scaled display. Unchanged from before the restyle.
        let w = CLIENT_W;
        let h = CLIENT_H;

        let mut work: Rect = std::mem::zeroed();
        SystemParametersInfoW(SPI_GETWORKAREA, 0, &mut work as *mut Rect as *mut c_void, 0);
        if work.right == 0 {
            work = Rect {
                left: 0,
                top: 0,
                right: GetSystemMetrics(SM_CXSCREEN),
                bottom: GetSystemMetrics(SM_CYSCREEN),
            };
        }
        let x = work.left + ((work.right - work.left) - w) / 2;
        let y = work.top + ((work.bottom - work.top) - h) / 2;

        let title = wide("Doppel — injector");
        let class_name = wide("DoppelLauncherWnd");
        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            title.as_ptr(),
            style,
            x,
            y,
            w,
            h,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null_mut(),
        );
        if hwnd.is_null() {
            ui::error("could not create the launcher window");
            return 1;
        }
        let _ = HWND_MAIN.set(hwnd as usize);

        // Dark native chrome, so the OS frame cannot clash with #0B0E13. The
        // caption itself is custom-drawn (WM_NCCALCSIZE); these pins only tint
        // the residual 1 px frame / caption text on Win11 and give Win10 the
        // dark mode the old build was missing on some machines.
        let dark: i32 = 1;
        let dark_ptr = &dark as *const i32 as *const c_void;
        if DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark_ptr, 4) != 0 {
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, dark_ptr, 4);
        }
        let bg = theme::colorref(theme::BACKGROUND);
        let ink = theme::colorref(theme::STRONG_TEXT);
        let _ = DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, &bg as *const u32 as *const c_void, 4);
        let _ = DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, &ink as *const u32 as *const c_void, 4);
        let _ = DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, &bg as *const u32 as *const c_void, 4);

        ShowWindow(hwnd, SW_SHOWNORMAL);
        UpdateWindow(hwnd);

        spawn_scanner();

        let mut msg: Msg = std::mem::zeroed();
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        0
    }
}
