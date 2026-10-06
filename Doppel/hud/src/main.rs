// main.rs — the out-of-process overlay window (plan phase D0).
//
// Why a second process at all: drawing the HUD from inside the JVM means
// touching the game's render thread and its GL state, which is exactly the
// coupling the plan moves away from. A plain Win32 process can put a card on top
// of the game without ever being part of it.
//
// Why a layered window: the card has to look like it belongs to the game, which
// means per-pixel alpha and a soft rim. An ordinary window is composited as an
// opaque rectangle, so this uses WS_EX_LAYERED together with
// UpdateLayeredWindow, the one Win32 path that takes a premultiplied ARGB bitmap
// and keeps it translucent.
//
// Why nothing here may activate: the game owns the foreground. Any call that
// moves focus — SetForegroundWindow, SetFocus, SetActiveWindow, ShowWindow with
// SW_SHOW — makes the game lose it, and a game in exclusive fullscreen then stops
// rendering altogether (see README: windowed / borderless fullscreen is a hard
// prerequisite). So the window is created with WS_EX_NOACTIVATE, shown with
// SW_SHOWNOACTIVATE, and never asks for focus or for foreground rights.
//
// Zero third-party dependencies, same rule as the injector: kernel32/user32/gdi32
// through raw extern "system" FFI, with the struct layouts spelled out by hand.
// The declarations below are transcribed from the documented prototypes in
// winuser.h / wingdi.h rather than guessed; the two that would fail first if a
// character were wrong are UpdateLayeredWindow and CreateDIBSection, and both are
// written out in full with every parameter named so a mismatch is visible at a
// glance rather than hidden behind a type alias.

mod draw;

use std::ffi::c_void;

use draw::{argb, Canvas};

// ── Win32 types ─────────────────────────────────────────────────────────────
//
// The layout structs below carry `#[allow(dead_code)]`: every field exists
// because Win32 dictates the byte offset, and the padding and reserved ones are
// only ever zeroed, so the never-read-field lint would otherwise flag perfectly
// correct FFI. The allowance is per struct on purpose — a crate-wide allow would
// also hide genuinely dead code.

type Hwnd = *mut c_void;
type Hinstance = *mut c_void;
type Hdc = *mut c_void;
type Hbrush = *mut c_void;
type Hicon = *mut c_void;
type Hcursor = *mut c_void;
type Wparam = usize;
type Lparam = isize;
type Lresult = isize;

#[repr(C)]
#[allow(dead_code)]
struct Point {
    x: i32,
    y: i32,
}

#[repr(C)]
#[allow(dead_code)]
struct Size {
    cx: i32,
    cy: i32,
}

#[repr(C)]
#[allow(dead_code)]
struct Msg {
    hwnd: Hwnd,
    message: u32,
    w_param: Wparam,
    l_param: Lparam,
    time: u32,
    pt: Point,
}

#[repr(C)]
#[allow(dead_code)]
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
#[allow(dead_code)]
struct BitmapInfoHeader {
    size: u32,
    width: i32,
    height: i32,
    planes: u16,
    bit_count: u16,
    compression: u32,
    size_image: u32,
    x_pels_per_meter: i32,
    y_pels_per_meter: i32,
    clr_used: u32,
    clr_important: u32,
}

/// BITMAPINFO is a header followed by a colour table. We always ask for BI_RGB
/// at 32bpp, where the table is ignored, but the struct still has to have room
/// for at least one entry or the API would read past our stack frame.
#[repr(C)]
#[allow(dead_code)]
struct BitmapInfo {
    header: BitmapInfoHeader,
    colours: [u32; 1],
}

/// BLENDFUNCTION, four bytes in this exact order (BlendOp, BlendFlags,
/// SourceConstantAlpha, AlphaFormat) — it is passed by pointer, so a reordered
/// field would silently change what the compositor does.
#[repr(C)]
#[allow(dead_code)]
struct BlendFunction {
    blend_op: u8,
    blend_flags: u8,
    source_constant_alpha: u8,
    alpha_format: u8,
}

// ── Win32 FFI ───────────────────────────────────────────────────────────────

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
    fn GetMessageW(msg: *mut Msg, hwnd: Hwnd, min: u32, max: u32) -> i32;
    fn TranslateMessage(msg: *const Msg) -> i32;
    fn DispatchMessageW(msg: *const Msg) -> Lresult;
    fn PostQuitMessage(code: i32);
    fn UpdateLayeredWindow(
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

#[link(name = "gdi32")]
extern "system" {
    fn CreateCompatibleDC(hdc: Hdc) -> Hdc;
    fn CreateDIBSection(
        hdc: Hdc,
        info: *const BitmapInfo,
        usage: u32,
        bits: *mut *mut c_void,
        section: *mut c_void,
        offset: u32,
    ) -> *mut c_void;
    fn SelectObject(hdc: Hdc, obj: *mut c_void) -> *mut c_void;
    fn DeleteObject(obj: *mut c_void) -> i32;
    fn DeleteDC(hdc: Hdc) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn GetModuleHandleW(name: *const u16) -> Hinstance;
}

// ── Win32 constants ─────────────────────────────────────────────────────────

const WS_POPUP: u32 = 0x8000_0000;
const WS_EX_TRANSPARENT: u32 = 0x0000_0020;
const WS_EX_TOOLWINDOW: u32 = 0x0000_0080;
const WS_EX_LAYERED: u32 = 0x0008_0000;
const WS_EX_NOACTIVATE: u32 = 0x0800_0000;

const WM_DESTROY: u32 = 0x0002;
const WM_CLOSE: u32 = 0x0010;
const WM_NCHITTEST: u32 = 0x0084;

/// "The click belongs to whatever is underneath." HTTRANSPARENT is the only
/// negative HT* value, hence the odd-looking literal.
const HTTRANSPARENT: Lresult = -1;

/// SW_SHOWNOACTIVATE. Deliberately not SW_SHOW: see the header note on focus.
const SW_SHOWNOACTIVATE: i32 = 4;

const DIB_RGB_COLORS: u32 = 0;
const BI_RGB: u32 = 0;

const AC_SRC_OVER: u8 = 0x00;
const AC_SRC_ALPHA: u8 = 0x01;
const ULW_ALPHA: u32 = 0x02;

/// Neutral class name and an empty caption: nothing that greps for the product's
/// name may find this window (plan §7).
const CLASS_NAME: &str = "WndClsA1";

// ── card geometry ───────────────────────────────────────────────────────────
//
// All values are physical pixels; the window is exactly the size of the card, so
// the buffer, the DIB and the window rectangle always agree.

const CARD_RADIUS: f32 = 8.0;
const ACCENT_INSET: u32 = 12;
const ACCENT_TOP: u32 = 12;
const ACCENT_H: u32 = 4;

/// #0D111C at 80%: the panel body. The alpha is baked into the DIB, and
/// BlendFunction then supplies the per-pixel alpha on top of it.
const CARD_ARGB: u32 = argb(0x0D, 0x11, 0x1C, 0xCC);
/// The accent strip. Without one, a dark card over a dark game is invisible.
const ACCENT_ARGB: u32 = argb(0x3F, 0xD8, 0xE0, 0xFF);

const DEF_X: i32 = 40;
const DEF_Y: i32 = 40;
const DEF_W: u32 = 360;
const DEF_H: u32 = 200;

const USAGE: &str = "\
usage: doppel-hud [options]

  --x <i32>   screen x of the card's top-left corner (default 40)
  --y <i32>   screen y of the card's top-left corner (default 40)
  --w <u32>   card width in physical pixels (default 360)
  --h <u32>   card height in physical pixels (default 200)
  --hide      print one line, create no window, exit 0 (automation check)
  --help      print this text and exit 0

The card is click-through and never takes focus, so stop it from Task Manager,
from `taskkill /IM doppel-hud.exe` (without /F, which posts WM_CLOSE), or with
Ctrl+C in this console. Run Minecraft in windowed or borderless-fullscreen mode:
under exclusive fullscreen the desktop compositor is bypassed and a layered
window is never drawn.";

// ── command line ────────────────────────────────────────────────────────────
//
// Hand-rolled on purpose: three numeric flags and two switches do not justify a
// dependency, and the injector already parses its own arguments.

struct Args {
    x: i32,
    y: i32,
    w: u32,
    h: u32,
    /// Headless check: prove the binary starts and links without opening a window.
    hide: bool,
    help: bool,
}

impl Default for Args {
    fn default() -> Args {
        Args { x: DEF_X, y: DEF_Y, w: DEF_W, h: DEF_H, hide: false, help: false }
    }
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut args = Args::default();
    let mut i = 0;
    while i < argv.len() {
        let flag = argv[i].as_str();
        match flag {
            "--x" => {
                args.x = parse_num(flag, &next_value(argv, &mut i, flag)?)?;
            }
            "--y" => {
                args.y = parse_num(flag, &next_value(argv, &mut i, flag)?)?;
            }
            "--w" => {
                args.w = parse_num(flag, &next_value(argv, &mut i, flag)?)?;
            }
            "--h" => {
                args.h = parse_num(flag, &next_value(argv, &mut i, flag)?)?;
            }
            "--hide" => {
                args.hide = true;
                i += 1;
            }
            "--help" | "-h" => {
                args.help = true;
                i += 1;
            }
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    Ok(args)
}

/// Consume the value that follows a flag and step the cursor past both. A
/// missing value is an error rather than a silent default: `--w --hide` should
/// not quietly produce a 0-wide card.
fn next_value(argv: &[String], i: &mut usize, flag: &str) -> Result<String, String> {
    let value = argv.get(*i + 1).ok_or_else(|| format!("{flag} needs a value"))?;
    *i += 2;
    Ok(value.clone())
}

/// Parse one flag value. The message names the flag because a bare
/// "invalid digit found in string" tells the user nothing about which argument
/// was wrong.
fn parse_num<T: std::str::FromStr>(flag: &str, raw: &str) -> Result<T, String> {
    raw.parse::<T>().map_err(|_| format!("{flag}: not a valid number: {raw}"))
}

// ── painting ────────────────────────────────────────────────────────────────

/// Compose one frame of the card. The rasteriser lives in `draw` and knows
/// nothing about Win32, which is what keeps this part testable on any host and
/// leaves room for the D1 bitmap font to write into the same buffer.
fn paint(canvas: &mut Canvas, w: u32, h: u32) {
    canvas.fill_rounded_rect(0, 0, w, h, CARD_RADIUS, CARD_ARGB);

    // Inset so the strip never touches the rounded rim, and skipped entirely on
    // a card too small to hold it (a 0-wide fill would just divide the work).
    let bar_w = w.saturating_sub(ACCENT_INSET * 2);
    if bar_w > 0 && h > ACCENT_TOP + ACCENT_H {
        canvas.fill_rect(ACCENT_INSET as i32, ACCENT_TOP as i32, bar_w, ACCENT_H, ACCENT_ARGB);
    }
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

// ── window procedure ────────────────────────────────────────────────────────

/// `unsafe extern "system"` because user32 calls it: the signature has to match
/// the ABI the class was registered with, and every argument arrives as a raw
/// Win32 value. It runs on the thread that owns the window, which is the thread
/// blocked in the message loop below.
unsafe extern "system" fn wnd_proc(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult {
    match msg {
        // WS_EX_TRANSPARENT already keeps the mouse away; answering like this as
        // well means a click that does arrive is routed to the window below
        // instead of being swallowed if the style is ever dropped by the driver.
        WM_NCHITTEST => HTTRANSPARENT,
        // Both paths end the same way: the message loop's GetMessageW returns 0
        // on WM_QUIT and run() falls out to process exit.
        WM_CLOSE | WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}

// ── entry point ─────────────────────────────────────────────────────────────

/// Create the overlay and pump messages until it closes.
fn run(args: &Args) -> Result<(), String> {
    // One `unsafe` block, and the smallest one this job allows: Win32 is all
    // there is to call here, so the alternative would be scattering `unsafe`
    // through the function.
    //
    // SAFETY: every call below is a Win32 entry point taking plain handles and
    // pointers to the stack values just built. The window, DIB and DC never leave
    // this function; the only memory reached through a raw pointer is the DIB
    // section's own storage, which Win32 sized for us and which we fill exactly
    // once, contiguously, before handing it back to the compositor. The function
    // runs on the thread that created the window and no other thread touches
    // these handles.
    unsafe {
        let instance = GetModuleHandleW(std::ptr::null());

        let class_name = wide(CLASS_NAME);
        // zeroed() first and then the fields we care about, matching the
        // injector's window setup: the members left at zero (styles, icons,
        // cursor, background brush, menu) are all "use the default" values.
        let mut wc: WndClassExW = std::mem::zeroed();
        wc.cb_size = std::mem::size_of::<WndClassExW>() as u32;
        wc.lpfn_wnd_proc = Some(wnd_proc);
        wc.h_instance = instance;
        wc.lpsz_class_name = class_name.as_ptr();
        if RegisterClassExW(&wc) == 0 {
            return Err("RegisterClassExW failed".to_string());
        }

        let title = wide("");
        let hwnd = CreateWindowExW(
            WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT,
            class_name.as_ptr(),
            title.as_ptr(),
            WS_POPUP,
            args.x,
            args.y,
            args.w as i32,
            args.h as i32,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null_mut(),
        );
        if hwnd.is_null() {
            return Err("CreateWindowExW failed".to_string());
        }

        // A never-showing, never-focusing window is also never painted, so the
        // card is rendered into a DIB and pushed through UpdateLayeredWindow;
        // that is the whole reason WS_EX_LAYERED is here.
        //
        // CreateCompatibleDC(NULL) gives a DC compatible with the screen, which
        // is all the DIB needs — no window DC is acquired, so there is nothing to
        // release if the next call fails.
        let mem_dc = CreateCompatibleDC(std::ptr::null_mut());
        if mem_dc.is_null() {
            return Err("CreateCompatibleDC failed".to_string());
        }

        let mut info: BitmapInfo = std::mem::zeroed();
        info.header.size = std::mem::size_of::<BitmapInfoHeader>() as u32;
        info.header.width = args.w as i32;
        // Negative height asks for a top-down DIB: row 0 is the top scanline,
        // which is the order the canvas writes in, so no flip is needed.
        info.header.height = -(args.h as i32);
        info.header.planes = 1;
        info.header.bit_count = 32;
        info.header.compression = BI_RGB;

        let mut bits: *mut c_void = std::ptr::null_mut();
        let dib = CreateDIBSection(
            mem_dc,
            &info,
            DIB_RGB_COLORS,
            &mut bits,
            std::ptr::null_mut(),
            0,
        );
        if dib.is_null() || bits.is_null() {
            DeleteDC(mem_dc);
            return Err("CreateDIBSection failed".to_string());
        }
        let previous = SelectObject(mem_dc, dib);

        // The canvas is the same size as the DIB, so this covers every pixel —
        // nothing depends on how CreateDIBSection initialises its storage.
        let mut canvas = Canvas::new(args.w, args.h);
        paint(&mut canvas, args.w, args.h);
        let pixels = canvas.pixels();
        std::ptr::copy_nonoverlapping(
            pixels.as_ptr() as *const u8,
            bits as *mut u8,
            pixels.len() * std::mem::size_of::<u32>(),
        );

        let size = Size { cx: args.w as i32, cy: args.h as i32 };
        let src_pos = Point { x: 0, y: 0 };
        let blend = BlendFunction {
            blend_op: AC_SRC_OVER,
            blend_flags: 0,
            // The alpha is already in the pixels; a constant alpha below 255
            // would dim the whole card a second time.
            source_constant_alpha: 255,
            alpha_format: AC_SRC_ALPHA,
        };
        // A null destination position keeps the coordinates CreateWindowExW was
        // given — moving the window here would be a second chance to get the
        // placement wrong.
        let updated = UpdateLayeredWindow(
            hwnd,
            std::ptr::null_mut(),
            std::ptr::null(),
            &size,
            mem_dc,
            &src_pos,
            0,
            &blend,
            ULW_ALPHA,
        );
        if updated == 0 {
            SelectObject(mem_dc, previous);
            DeleteObject(dib);
            DeleteDC(mem_dc);
            return Err("UpdateLayeredWindow failed".to_string());
        }

        // Shown only now, so the first thing the desktop sees is the finished
        // card rather than an empty window, and shown without activation.
        ShowWindow(hwnd, SW_SHOWNOACTIVATE);

        let mut msg: Msg = std::mem::zeroed();
        // GetMessageW returns 0 on WM_QUIT and -1 on error; either way the loop
        // ends and the process is on its way out.
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        // Released in reverse: a DIB must not be deleted while it is still
        // selected into the DC.
        SelectObject(mem_dc, previous);
        DeleteObject(dib);
        DeleteDC(mem_dc);
        Ok(())
    }
}

fn main() {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match parse_args(&argv) {
        Ok(args) => args,
        Err(reason) => {
            eprintln!("error: {reason}");
            eprintln!("{}", USAGE);
            std::process::exit(2);
        }
    };

    if args.help {
        println!("{}", USAGE);
        return;
    }

    if args.hide {
        // Automation hook: proves the executable starts, parses its arguments and
        // resolves its imports without ever touching the desktop.
        println!(
            "{} {} — ok, no window created (card would be {}x{} at {}, {})",
            env!("CARGO_PKG_NAME"),
            env!("CARGO_PKG_VERSION"),
            args.w,
            args.h,
            args.x,
            args.y
        );
        return;
    }

    if args.w == 0 || args.h == 0 {
        eprintln!("error: --w and --h must both be non-zero");
        std::process::exit(2);
    }

    if let Err(reason) = run(&args) {
        eprintln!("error: {reason}");
        std::process::exit(1);
    }
}
