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
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::flow::{self, FlowError, Reporter, STEPS};
use crate::process::ProcessInfo;
use crate::theme::{self, metrics};
use crate::ui;

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
    fn AdjustWindowRect(rect: *mut Rect, style: u32, menu: i32) -> i32;
    fn GetSystemMetrics(index: i32) -> i32;
    fn SystemParametersInfoW(action: u32, param: u32, data: *mut c_void, win_ini: u32) -> i32;
    fn GetDC(hwnd: Hwnd) -> Hdc;
    fn ReleaseDC(hwnd: Hwnd, hdc: Hdc) -> i32;
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

// ── layout (logical client area, fixed size — the window is not resizable) ───

const CLIENT_W: i32 = metrics::WINDOW_W;
const CLIENT_H: i32 = metrics::WINDOW_H;
const PAD: i32 = metrics::PAD_WINDOW;
const HEADER_H: i32 = 76;

const LIST_X: i32 = PAD;
const LIST_Y: i32 = 116;
const LIST_W: i32 = CLIENT_W - PAD * 2;
const LIST_H: i32 = 280;

// The stock list box is inset so the card's rounded corners stay visible.
const LISTBOX_X: i32 = LIST_X + metrics::ITEM_INNER_Y;
const LISTBOX_Y: i32 = LIST_Y + metrics::ITEM_INNER_Y;
const LISTBOX_W: i32 = LIST_W - metrics::ITEM_INNER_Y * 2;
const LISTBOX_H: i32 = LIST_H - metrics::ITEM_INNER_Y * 2;

const BTN_W: i32 = metrics::BTN_LARGE_W;
const BTN_H: i32 = metrics::BTN_LARGE_H;
const BTN_Y: i32 = LIST_Y + LIST_H + 22;

const REFRESH_RECT: Rect = Rect { left: PAD, top: BTN_Y, right: PAD + BTN_W, bottom: BTN_Y + BTN_H };
const INJECT_RECT: Rect = Rect { left: CLIENT_W - PAD - BTN_W, top: BTN_Y, right: CLIENT_W - PAD, bottom: BTN_Y + BTN_H };
const HINT_Y: i32 = BTN_Y + BTN_H + 14;
const FOOTER_Y: i32 = HINT_Y + 26;

const STEPS_CARD: Rect = Rect { left: PAD, top: 126, right: CLIENT_W - PAD, bottom: 272 };
const RESULT_CARD: Rect = Rect { left: PAD, top: 286, right: CLIENT_W - PAD, bottom: 452 };

const ROW_BTN_Y: i32 = CLIENT_H - PAD - BTN_H;
const BACK_RECT: Rect = Rect { left: PAD, top: ROW_BTN_Y, right: PAD + BTN_W, bottom: ROW_BTN_Y + BTN_H };
const OPEN_RECT: Rect = Rect { left: PAD + BTN_W + 12, top: ROW_BTN_Y, right: PAD + BTN_W * 2 + 12, bottom: ROW_BTN_Y + BTN_H };
const HIDE_RECT: Rect = Rect { left: PAD + BTN_W * 2 + 24, top: ROW_BTN_Y, right: PAD + BTN_W * 3 + 24, bottom: ROW_BTN_Y + BTN_H };
const CLOSE_RECT: Rect = Rect { left: CLIENT_W - PAD - BTN_W, top: ROW_BTN_Y, right: CLIENT_W - PAD, bottom: ROW_BTN_Y + BTN_H };

const STEP_Y0: i32 = 142;
const STEP_DY: i32 = 34;

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
    tag: &'static str,
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

fn run_injection(pid: u32) {
    crate::log::append(&format!("=== injection start · target PID {} ===", pid));
    let rep = WindowReporter::new();
    let result = flow::run(Some(pid), &rep);

    let s = shared();
    match result {
        Ok(outcome) => {
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

            // Same finish as OpenCommunity's launcher: the game is running and
            // the in-game panel is the thing the user actually wants, so open it
            // and get the launcher out of the way.
            if !outcome.panel_url.as_deref().unwrap_or("").is_empty() {
                open_url(outcome.panel_url.as_deref().unwrap_or(""));
            }
            hide_launcher();
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

    // ── header ──
    text_in(
        hdc,
        f.title,
        C_STRONG,
        "DOPPEL",
        Rect { left: PAD, top: 10, right: PAD + 200, bottom: 62 },
        DT_LEFT | DT_SINGLELINE | DT_VCENTER,
    );
    text_in(
        hdc,
        f.small,
        C_LINK,
        "injector",
        Rect { left: PAD + 172, top: 28, right: PAD + 280, bottom: 56 },
        DT_LEFT | DT_SINGLELINE | DT_VCENTER,
    );
    let tag = format!("v{}  ·  native launcher", env!("CARGO_PKG_VERSION"));
    text_in(
        hdc,
        f.small,
        C_DIM,
        &tag,
        Rect { left: CLIENT_W - PAD - 300, top: 28, right: CLIENT_W - PAD, bottom: 56 },
        DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
    );
    fill(hdc, C_SEPARATOR, Rect { left: PAD, top: HEADER_H - 8, right: CLIENT_W - PAD, bottom: HEADER_H - 7 });

    let hover = *lock(&shared().hover);
    let pressed = *lock(&PRESSED);
    let state = lock(&shared().state);

    match state.app {
        App::Intro => {}
        App::Chooser => {
            text_in(
                hdc,
                f.bold,
                C_STRONG,
                "Java processes",
                Rect { left: PAD, top: 86, right: PAD + 300, bottom: 112 },
                DT_LEFT | DT_SINGLELINE | DT_VCENTER,
            );
            let count = lock(&shared().procs).len();
            let sub = if count == 0 {
                "scanning…".to_string()
            } else {
                format!("{count} found · refreshes by itself")
            };
            text_in(
                hdc,
                f.small,
                C_SECOND,
                &sub,
                Rect { left: CLIENT_W - PAD - 320, top: 88, right: CLIENT_W - PAD, bottom: 110 },
                DT_RIGHT | DT_SINGLELINE | DT_VCENTER,
            );

            // the card the child list box sits in
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
                text_in(
                    hdc,
                    f.small,
                    C_WARNING,
                    &format!("Selected PID {sel}. Launching the game fresh is the only way to reload the agent."),
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
                Rect { left: PAD, top: 88, right: CLIENT_W - PAD - 160, bottom: 114 },
                DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
            );
            if state.pid != 0 {
                text_in(
                    hdc,
                    f.mono,
                    C_DIM,
                    &format!("PID {}", state.pid),
                    Rect { left: CLIENT_W - PAD - 150, top: 90, right: CLIENT_W - PAD, bottom: 112 },
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
                text_in(
                    hdc,
                    f.small,
                    C_DIM,
                    "open that address in a browser to reach the in-game panel",
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
                "Open WebUI",
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

    let procs = lock(&shared().procs);
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
        let mut flags = String::new();
        let tag = if p.injected {
            flags.push_str("   [ALREADY LOADED]");
            "ALREADY LOADED"
        } else if p.is_minecraft {
            flags.push_str("   [minecraft]");
            "minecraft"
        } else {
            ""
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
        // Auto-pick the first Minecraft process so the common case is one click.
        if let Some(i) = procs.iter().position(|p| p.is_minecraft && !p.injected) {
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
                    repaint();
                }
            }
            0
        }
        WM_MOUSEMOVE => {
            let x = (lp & 0xFFFF) as u16 as i16 as i32;
            let y = ((lp >> 16) & 0xFFFF) as u16 as i16 as i32;
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
            let busy = BUSY.load(Ordering::SeqCst);
            let app = lock(&shared().state).app;
            match app {
                App::Intro => {
                    // Click through the splash: jump to the content fade.
                    skip_intro();
                    repaint();
                }
                App::Chooser => {
                    if point_in(p, REFRESH_RECT) && !busy {
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
                            let url = lock(&shared().state).panel_url.clone();
                            open_url(&url);
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
        // TODO(B3): the client is the reference's fixed 900x550 and the frame is
        // not DPI-scaled (GetDpiForWindow); Windows bitmap-stretches the window
        // on a scaled display. Unchanged from before the restyle.
        let mut frame = Rect { left: 0, top: 0, right: CLIENT_W, bottom: CLIENT_H };
        AdjustWindowRect(&mut frame, style, 0);
        let w = frame.right - frame.left;
        let h = frame.bottom - frame.top;

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

        // Dark native title bar, so the OS chrome does not clash with #0B0E13.
        let dark: i32 = 1;
        let dark_ptr = &dark as *const i32 as *const c_void;
        if DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark_ptr, 4) != 0 {
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, dark_ptr, 4);
        }

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
