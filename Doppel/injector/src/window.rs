// window.rs — the injector's native launcher window.
//
// Form follows OpenCommunity's launcher: a real window that walks through
// Chooser -> Injecting -> result, instead of a page in a browser. The browser
// panel worked, but it meant a local HTTP server, a per-run token, a tab the
// user had to keep open, and no UI at all when no browser was around. A
// launcher is what this tool actually is.
//
// Zero third-party dependencies, same rule as the rest of the injector: raw
// Win32 (user32/gdi32) through extern "system" FFI, painted with GDI. The only
// child control is the process list box; the header, buttons and step timeline
// are drawn in WM_PAINT and hit-tested by hand so the window can follow the
// Doppel palette without an owner-draw control for every widget.
//
// Threading: the message loop owns the window on the main thread. The process
// scan runs on a background thread (it opens handles in every java process and
// takes ~100 ms — far too long to run on the UI thread every tick), and the
// injection runs on its own worker. Both publish into the shared state below.

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::flow::{self, FlowError, Reporter, STEPS};
use crate::process::ProcessInfo;
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
#[derive(Clone, Copy, Default)]
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
}

#[link(name = "gdi32")]
extern "system" {
    fn CreateSolidBrush(color: u32) -> Hbrush;
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
const WS_BORDER: u32 = 0x0080_0000;
const WS_TABSTOP: u32 = 0x0001_0000;

const LBS_NOTIFY: u32 = 0x0001;
const LBS_NOINTEGRALHEIGHT: u32 = 0x0100;

const WM_CREATE: u32 = 0x0001;
const WM_DESTROY: u32 = 0x0002;
const WM_PAINT: u32 = 0x000F;
const WM_CLOSE: u32 = 0x0010;
const WM_ERASEBKGND: u32 = 0x0014;
const WM_SETFONT: u32 = 0x0030;
const WM_COMMAND: u32 = 0x0111;
const WM_TIMER: u32 = 0x0113;
const WM_MOUSEMOVE: u32 = 0x0200;
const WM_LBUTTONDOWN: u32 = 0x0201;
const WM_SETCURSOR: u32 = 0x0020;
const WM_SYSCOMMAND: u32 = 0x0112;
const SC_MINIMIZE: u32 = 0xF020;
const WM_CTLCOLORLISTBOX: u32 = 0x0134;

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
const MB_ICONERROR: u32 = 0x0000_0010;
const LBN_SELCHANGE: u32 = 1;

// Doppel palette as COLORREF (0x00BBGGRR)
const C_BG: u32 = 0x000B_0705; // #05070B
const C_PANEL: u32 = 0x001C_120D; // #0D121C
const C_HOVER: u32 = 0x0028_1C_10;
const C_TEXT: u32 = 0x00EA_DE_D5; // #D5DEEA
const C_DIM: u32 = 0x007F_6B_5A; // #5A6B7F
const C_CYAN: u32 = 0x00EE_D3_22; // #22D3EE
const C_GREEN: u32 = 0x0099_D3_34; // #34D399
const C_RED: u32 = 0x0071_71_F8; // #F87171
const C_AMBER: u32 = 0x0024_BF_FB; // #FBBF24
const C_LINE: u32 = 0x0020_1A_14;

// ── layout (logical client area, fixed size — the window is not resizable) ───

const CLIENT_W: i32 = 780;
const CLIENT_H: i32 = 560;
const PAD: i32 = 26;
const HEADER_H: i32 = 72;

const LIST_X: i32 = PAD;
const LIST_Y: i32 = 112;
const LIST_W: i32 = CLIENT_W - PAD * 2;
const LIST_H: i32 = 286;

const BTN_W: i32 = 132;
const BTN_H: i32 = 36;
const BTN_Y: i32 = LIST_Y + LIST_H + 22;

const REFRESH_RECT: Rect = Rect { left: PAD, top: BTN_Y, right: PAD + BTN_W, bottom: BTN_Y + BTN_H };
const INJECT_RECT: Rect = Rect { left: CLIENT_W - PAD - BTN_W, top: BTN_Y, right: CLIENT_W - PAD, bottom: BTN_Y + BTN_H };
const HINT_Y: i32 = BTN_Y + BTN_H + 18;

const CLOSE_RECT: Rect = Rect { left: CLIENT_W - PAD - 110, top: CLIENT_H - PAD - 24, right: CLIENT_W - PAD, bottom: CLIENT_H - PAD + 6 };
const BACK_RECT: Rect = Rect { left: PAD, top: CLIENT_H - PAD - 24, right: PAD + 110, bottom: CLIENT_H - PAD + 6 };
const OPEN_RECT: Rect = Rect { left: PAD + 122, top: BACK_RECT.top, right: PAD + 122 + 138, bottom: BACK_RECT.bottom };
const HIDE_RECT: Rect = Rect { left: PAD + 272, top: BACK_RECT.top, right: PAD + 272 + 138, bottom: BACK_RECT.bottom };

const STEP_Y0: i32 = 128;
const STEP_DY: i32 = 38;

// ── shared state ────────────────────────────────────────────────────────────

#[derive(Clone, Copy, PartialEq, Eq)]
enum App {
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
            app: App::Chooser,
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
}

static SHARED: OnceLock<Shared> = OnceLock::new();
static BUSY: AtomicBool = AtomicBool::new(false);
static CLASS_REGISTERED: AtomicBool = AtomicBool::new(false);
static LIST_VISIBLE: AtomicBool = AtomicBool::new(true);
static HWND_MAIN: OnceLock<usize> = OnceLock::new();
static HWND_LIST: OnceLock<usize> = OnceLock::new();

fn shared() -> &'static Shared {
    SHARED.get_or_init(|| Shared {
        state: Mutex::new(UiState::default()),
        procs: Mutex::new(Vec::new()),
        shown_sig: Mutex::new(String::new()),
        selected: Mutex::new(0),
        hover: Mutex::new(Point::default()),
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

unsafe fn text_in(hdc: Hdc, font: Hfont, color: u32, s: &str, rect: Rect, format: u32) {
    if s.is_empty() {
        return;
    }
    let null_pen = GetStockObject(NULL_PEN);
    let old_pen = SelectObject(hdc, null_pen);
    let old_font = SelectObject(hdc, font);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, color);
    let mut r = rect;
    let w = wide(s);
    DrawTextW(hdc, w.as_ptr(), -1, &mut r, format);
    SelectObject(hdc, old_font);
    SelectObject(hdc, old_pen);
}

unsafe fn text_at(hdc: Hdc, font: Hfont, color: u32, s: &str, x: i32, y: i32) {
    text_in(hdc, font, color, s, Rect { left: x, top: y, right: CLIENT_W, bottom: y + 34 }, DT_LEFT | DT_SINGLELINE);
}

unsafe fn fill(hdc: Hdc, color: u32, r: Rect) {
    let b = CreateSolidBrush(color);
    FillRect(hdc, &r, b);
    DeleteObject(b);
}

/// Rounded button: an outer rounded rect in the border colour with an inner one
/// in the fill colour on top, so it reads as a 1 px outline.
unsafe fn draw_button(hdc: Hdc, font: Hfont, r: Rect, label: &str, accent: bool, enabled: bool, hovered: bool) {
    let (border, body, fg) = if !enabled {
        (C_LINE, C_PANEL, C_DIM)
    } else if accent {
        (C_CYAN, if hovered { C_HOVER } else { C_PANEL }, C_CYAN)
    } else {
        (C_LINE, if hovered { C_HOVER } else { C_PANEL }, C_TEXT)
    };
    let null_pen = GetStockObject(NULL_PEN);
    let old_pen = SelectObject(hdc, null_pen);

    let outer = CreateSolidBrush(border);
    let old_brush = SelectObject(hdc, outer);
    RoundRect(hdc, r.left, r.top, r.right, r.bottom, 10, 10);

    let inner_brush = CreateSolidBrush(body);
    SelectObject(hdc, inner_brush);
    RoundRect(hdc, r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, 9, 9);

    SelectObject(hdc, old_brush);
    SelectObject(hdc, old_pen);
    DeleteObject(outer);
    DeleteObject(inner_brush);

    text_in(hdc, font, fg, label, r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

unsafe fn draw_dot(hdc: Hdc, cx: i32, cy: i32, color: u32) {
    let null_pen = GetStockObject(NULL_PEN);
    let old_pen = SelectObject(hdc, null_pen);
    let brush = CreateSolidBrush(color);
    let old_brush = SelectObject(hdc, brush);
    RoundRect(hdc, cx - 6, cy - 6, cx + 6, cy + 6, 12, 12);
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
    let p = *LIST_BRUSH.get_or_init(|| unsafe { CreateSolidBrush(C_PANEL) as usize });
    p as Hbrush
}

// ── fonts ───────────────────────────────────────────────────────────────────

struct Fonts {
    title: Hfont,
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
            title: CreateFontW(-30, 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            body: CreateFontW(-16, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            small: CreateFontW(-13, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            bold: CreateFontW(-17, 0, 0, 0, 600, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()),
            mono: CreateFontW(-13, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, mono.as_ptr()),
        };
        Box::into_raw(Box::new(f)) as usize
    });
    unsafe { &*(ptr as *const Fonts) }
}

// ── painting ────────────────────────────────────────────────────────────────

unsafe fn paint(hdc: Hdc) {
    let f = fonts();
    fill(hdc, C_BG, Rect { left: 0, top: 0, right: CLIENT_W, bottom: CLIENT_H });

    // ── header ──
    text_at(hdc, f.title, C_CYAN, "DOPPEL", PAD, 18);
    text_at(hdc, f.small, C_DIM, "injector", PAD + 170, 34);
    let tag = format!("v{}  ·  native launcher", env!("CARGO_PKG_VERSION"));
    text_in(
        hdc,
        f.small,
        C_DIM,
        &tag,
        Rect { left: CLIENT_W - PAD - 260, top: 32, right: CLIENT_W - PAD, bottom: 54 },
        DT_LEFT | DT_SINGLELINE,
    );
    fill(hdc, C_LINE, Rect { left: PAD, top: HEADER_H - 8, right: CLIENT_W - PAD, bottom: HEADER_H - 7 });

    let hover = *lock(&shared().hover);
    let state = lock(&shared().state);

    match state.app {
        App::Chooser => {
            text_at(hdc, f.bold, C_TEXT, "Java processes", PAD, 82);
            let count = lock(&shared().procs).len();
            let sub = if count == 0 {
                "scanning…".to_string()
            } else {
                format!("{} found · refreshes by itself", count)
            };
            text_at(hdc, f.small, C_DIM, &sub, PAD + 158, 86);

            // frame around the child list box
            fill(hdc, C_LINE, Rect { left: LIST_X - 1, top: LIST_Y - 1, right: LIST_X + LIST_W + 1, bottom: LIST_Y + LIST_H + 1 });

            let busy = BUSY.load(Ordering::SeqCst);
            let sel = *lock(&shared().selected);
            draw_button(hdc, f.body, REFRESH_RECT, "Refresh", false, !busy, point_in(hover, REFRESH_RECT));
            draw_button(
                hdc,
                f.body,
                INJECT_RECT,
                if busy { "Working…" } else { "Inject" },
                true,
                sel != 0 && !busy,
                point_in(hover, INJECT_RECT),
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
                    C_AMBER,
                    &format!("Selected PID {}. Launching the game fresh is the only way to reload the agent.", sel),
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
                Rect { left: PAD, top: HINT_Y + 30, right: CLIENT_W - PAD, bottom: CLIENT_H - PAD },
                DT_LEFT | DT_WORDBREAK,
            );
        }
        App::Running | App::Done | App::Failed => {
            let (headline, head_color) = match state.app {
                App::Running => ("Injecting", C_CYAN),
                App::Done => ("Injected — the agent is running inside the game", C_GREEN),
                _ => ("Injection failed", C_RED),
            };
            text_at(hdc, f.bold, head_color, headline, PAD, 84);
            if state.pid != 0 {
                text_in(
                    hdc,
                    f.mono,
                    C_DIM,
                    &format!("PID {}", state.pid),
                    Rect { left: CLIENT_W - PAD - 140, top: 86, right: CLIENT_W - PAD, bottom: 108 },
                    DT_LEFT | DT_SINGLELINE,
                );
            }

            for (i, step) in state.steps.iter().enumerate() {
                let y = STEP_Y0 + i as i32 * STEP_DY;
                let dot = match step.state {
                    StepState::Pending => C_LINE,
                    StepState::Active => C_CYAN,
                    StepState::Ok => C_GREEN,
                    StepState::Failed => C_RED,
                };
                draw_dot(hdc, PAD + 8, y + 8, dot);
                let label_color = if step.state == StepState::Pending { C_DIM } else { C_TEXT };
                text_in(
                    hdc,
                    f.body,
                    label_color,
                    STEPS.get(i).copied().unwrap_or(""),
                    Rect { left: PAD + 30, top: y - 2, right: PAD + 320, bottom: y + 20 },
                    DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                );
                if !step.note.is_empty() {
                    text_in(
                        hdc,
                        f.small,
                        C_DIM,
                        &step.note,
                        Rect { left: PAD + 330, top: y - 1, right: CLIENT_W - PAD - 64, bottom: y + 20 },
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
                        Rect { left: CLIENT_W - PAD - 58, top: y - 1, right: CLIENT_W - PAD, bottom: y + 20 },
                        DT_LEFT | DT_SINGLELINE | DT_VCENTER,
                    );
                }
            }

            // ── result box ──
            let box_top = STEP_Y0 + STEPS.len() as i32 * STEP_DY + 14;
            fill(hdc, C_PANEL, Rect { left: PAD, top: box_top, right: CLIENT_W - PAD, bottom: box_top + 104 });

            if state.app == App::Done && !state.panel_url.is_empty() {
                text_at(hdc, f.small, C_DIM, "PANEL", PAD + 14, box_top + 14);
                text_in(
                    hdc,
                    f.mono,
                    C_CYAN,
                    state.panel_url.as_str(),
                    Rect { left: PAD + 96, top: box_top + 12, right: CLIENT_W - PAD - 14, bottom: box_top + 34 },
                    DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS,
                );
                text_at(hdc, f.small, C_DIM, "TOKEN", PAD + 14, box_top + 44);
                text_in(
                    hdc,
                    f.mono,
                    C_AMBER,
                    if state.panel_token.is_empty() { "(see doppel-payload.log)" } else { state.panel_token.as_str() },
                    Rect { left: PAD + 96, top: box_top + 42, right: CLIENT_W - PAD - 14, bottom: box_top + 64 },
                    DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS,
                );
                text_at(
                    hdc,
                    f.small,
                    C_DIM,
                    "open that address in a browser to reach the in-game panel",
                    PAD + 14,
                    box_top + 74,
                );
            } else {
                let detail = if !state.detail.is_empty() {
                    state.detail.clone()
                } else {
                    state.events.last().map(|(_, t)| t.clone()).unwrap_or_default()
                };
                let color = if state.app == App::Failed { C_RED } else { C_TEXT };
                text_in(
                    hdc,
                    f.body,
                    color,
                    &detail,
                    Rect { left: PAD + 14, top: box_top + 12, right: CLIENT_W - PAD - 14, bottom: box_top + 92 },
                    DT_LEFT | DT_WORDBREAK,
                );
            }

            let can_close = state.app != App::Running;
            let has_panel = !state.panel_url.is_empty();
            draw_button(hdc, f.body, BACK_RECT, "Back", false, can_close, point_in(hover, BACK_RECT));
            draw_button(hdc, f.body, OPEN_RECT, "Open WebUI", true, can_close && has_panel, point_in(hover, OPEN_RECT));
            draw_button(hdc, f.body, HIDE_RECT, "Hide", false, can_close, point_in(hover, HIDE_RECT));
            draw_button(hdc, f.body, CLOSE_RECT, "Close", false, can_close, point_in(hover, CLOSE_RECT));
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
    // otherwise sit on top of the step timeline.
    let app = lock(&shared().state).app;
    let want_visible = app == App::Chooser;
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
    for (i, p) in procs.iter().enumerate() {
        let title = if !p.window_title.is_empty() {
            p.window_title.clone()
        } else if !p.cmdline_hint().is_empty() {
            p.cmdline_hint()
        } else {
            p.exe.clone()
        };
        let mut flags = String::new();
        if p.injected {
            flags.push_str("   [ALREADY LOADED]");
        } else if p.is_minecraft {
            flags.push_str("   [minecraft]");
        }
        let line = format!("  {:>6}   {:<11} {}", p.pid, p.exe, title) + &flags;
        let w = wide(&line);
        SendMessageW(list, LB_ADDSTRING, 0, w.as_ptr() as Lparam);
        SendMessageW(list, LB_SETITEMDATA, i, p.pid as Lparam);
        if p.pid == selected && restore.is_none() {
            restore = Some(i);
        }
    }
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

// ── window procedure ────────────────────────────────────────────────────────

unsafe extern "system" fn wnd_proc(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult {
    match msg {
        WM_CREATE => {
            let instance = GetModuleHandleW(std::ptr::null());
            let class = wide("LISTBOX");
            let list = CreateWindowExW(
                0,
                class.as_ptr(),
                std::ptr::null(),
                WS_CHILD | WS_VISIBLE | WS_BORDER | WS_VSCROLL | WS_TABSTOP | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT,
                LIST_X,
                LIST_Y,
                LIST_W,
                LIST_H,
                hwnd,
                std::ptr::null_mut(),
                instance,
                std::ptr::null_mut(),
            );
            let _ = HWND_LIST.set(list as usize);
            SendMessageW(list, WM_SETFONT, fonts().body as Wparam, 1);
            SendMessageW(list, LB_SETITEMHEIGHT, 0, 22 as Lparam);
            SetTimer(hwnd, 1, 250, std::ptr::null());
            0
        }
        WM_TIMER => {
            sync_list();
            repaint();
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
        WM_CTLCOLORLISTBOX => {
            let hdc = wp as Hdc;
            SetTextColor(hdc, C_TEXT);
            SetBkColor(hdc, C_PANEL);
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
            *lock(&shared().hover) = Point { x, y };
            repaint();
            0
        }
        WM_SETCURSOR => {
            let cursor = LoadCursorW(std::ptr::null_mut(), IDC_ARROW as *const u16);
            SetCursor(cursor);
            1
        }
        WM_LBUTTONDOWN => {
            let p = Point {
                x: (lp & 0xFFFF) as u16 as i16 as i32,
                y: ((lp >> 16) & 0xFFFF) as u16 as i16 as i32,
            };
            let busy = BUSY.load(Ordering::SeqCst);
            let app = lock(&shared().state).app;
            match app {
                App::Chooser => {
                    if point_in(p, REFRESH_RECT) && !busy {
                        let scanned = flow::candidates();
                        let s = shared();
                        *lock(&s.procs) = scanned;
                        *lock(&s.shown_sig) = String::new();
                    } else if point_in(p, INJECT_RECT) && !busy {
                        let pid = *lock(&shared().selected);
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
                    if point_in(p, CLOSE_RECT) {
                        PostQuitMessage(0);
                    } else if point_in(p, OPEN_RECT) {
                        let url = lock(&shared().state).panel_url.clone();
                        open_url(&url);
                    } else if point_in(p, HIDE_RECT) {
                        hide_launcher();
                    } else if point_in(p, BACK_RECT) {
                        let s = shared();
                        let mut st = lock(&s.state);
                        st.app = App::Chooser;
                        st.detail.clear();
                        st.panel_url.clear();
                        st.panel_token.clear();
                        st.pid = 0;
                        drop(st);
                        *lock(&s.shown_sig) = String::new();
                        repaint();
                    }
                }
            }
            0
        }
        WM_CLOSE | WM_DESTROY => {
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
