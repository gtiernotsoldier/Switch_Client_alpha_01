// main.rs — the out-of-process overlay window (plan phase D0, now D1/D2/D3/D4).
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
// What the frame loop does now: read the published state block (D2), advance the
// entrance/flash animations (D3/D4), rasterise the card into the canvas, push it
// through UpdateLayeredWindow. Frames are only pushed when something actually
// changed — a still card is not re-composited 60 times a second.
//
// Zero third-party dependencies, same rule as the injector: kernel32/user32/gdi32
// through raw extern "system" FFI, declared in win.rs. The pixel work lives in
// draw.rs (pure), the text in text.rs (GDI), the card in hud.rs (pure geometry).

mod draw;
mod hud;
mod preview;
mod state;
mod text;
mod win;

use std::ffi::c_void;
use std::path::Path;
use std::time::{Duration, Instant};

use draw::{argb, Canvas};
use hud::{Anim, Config, Position};
use state::Entry;
use text::TextEngine;
use win::*;

/// Neutral class name and an empty caption: nothing that greps for the product's
/// name may find this window (plan §7).
const CLASS_NAME: &str = "WndClsA1";

// ── window geometry ─────────────────────────────────────────────────────────
//
// The window is the *stage*: the card is drawn inside it at MARGIN from the top
// corner it occupies and everything else stays fully transparent. It is
// click-through and never visible as a surface, so a stage larger than the card
// costs nothing but the DIB it needs.

/// Stacked layout: pill on top, list under it. Wide enough for the longest name
/// and value field the contract allows, tall enough for the pill plus eight rows.
const DEF_W: u32 = 520;
const DEF_H: u32 = 400;
/// Split layouts put the two halves in opposite top corners of the screen, so the
/// stage has to span it.
const MIN_SPLIT_W: u32 = 640;
const DEF_X: i32 = 0;
const DEF_Y: i32 = 0;

/// Backdrop the `--dump` BMP is composited over: the card is translucent, so a
/// dump has to sit on something. A mid-tone desk (rather than the near-black a
/// game usually is) keeps the panel body, its rim and its drop shadow all
/// distinguishable from the background, which is what makes the dump reviewable.
const BACKDROP: u32 = argb(0x4A, 0x50, 0x61, 0xFF);

// ── process behaviour ───────────────────────────────────────────────────────

/// Target frame interval. Animations are ~350 ms fades, so 60 Hz is smooth and
/// the loop is idle-cheap (a state read and a hash) when nothing is moving.
const FRAME_MS: u64 = 16;
/// "The game is gone": nothing has written the state file for this long.
const STALE_SECS: f32 = 3.0;
/// Silence tolerated BEFORE the first successful read. The injector starts this process while the
/// game is still loading, and Forge + OptiFine + world load routinely take longer than three
/// seconds — with the short rule the window would die before the JVM ever published anything.
const FIRST_WRITE_GRACE_SECS: f32 = 30.0;
/// How long the card takes to fade out once that happens.
const FADE_SECS: f32 = 0.35;

const USAGE: &str = "\
usage: doppel-hud [options]

  --x <i32>            screen x of the stage's top-left corner (default 0)
  --y <i32>            screen y of the stage's top-left corner (default 0)
  --w <u32>            stage width in physical pixels (default 520 stacked,
                       the screen width when --position is left/right)
  --h <u32>            stage height in physical pixels (default 400)
  --position <where>   stacked (default) = pill on top, list under it;
                       left = list in the left corner, pill in the right;
                       right = list in the right corner, pill in the left
  --dump <path.bmp>    render one representative frame to a 32-bit BMP, print an
                       ASCII preview, create no window, exit 0
  --hide               print one line, create no window, exit 0 (automation check)
  --help               print this text and exit 0

The card reads %TEMP%\\doppel-hud.state, published by the game 3600 bytes at a
time (magic 0x444F5050, version 1). It opens the file read-only: there is no back
channel and nothing this process does can reach the game.

It never takes focus (no SetForegroundWindow / SetFocus / SW_SHOW) and every
click passes through it, so Minecraft keeps the foreground and the mouse. Run the
game in windowed or borderless-fullscreen mode: under exclusive fullscreen the
desktop compositor is bypassed and a layered window is never drawn.

The process exits by itself when the game does — three seconds without the state
file being written, then a short fade. Stop it early from Task Manager, from
`taskkill /IM doppel-hud.exe` (without /F, which posts WM_CLOSE), or with Ctrl+C
in this console.";

// ── command line ────────────────────────────────────────────────────────────
//
// Hand-rolled on purpose: a handful of flags does not justify a dependency, and
// the injector already parses its own arguments.

struct Args {
    x: i32,
    y: i32,
    /// None = "pick by layout" (see DEF_W and the split case in run()).
    w: Option<u32>,
    h: Option<u32>,
    position: Position,
    /// Headless render to a BMP, no window.
    dump: Option<String>,
    /// Headless check: prove the binary starts and links without opening a window.
    hide: bool,
    help: bool,
}

impl Default for Args {
    fn default() -> Args {
        Args {
            x: DEF_X,
            y: DEF_Y,
            w: None,
            h: None,
            position: Position::Stacked,
            dump: None,
            hide: false,
            help: false,
        }
    }
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut args = Args::default();
    let mut i = 0;
    while i < argv.len() {
        let flag = argv[i].as_str();
        match flag {
            "--x" => args.x = parse_num(flag, &next_value(argv, &mut i, flag)?)?,
            "--y" => args.y = parse_num(flag, &next_value(argv, &mut i, flag)?)?,
            "--w" => args.w = Some(parse_num(flag, &next_value(argv, &mut i, flag)?)?),
            "--h" => args.h = Some(parse_num(flag, &next_value(argv, &mut i, flag)?)?),
            "--position" => {
                let raw = next_value(argv, &mut i, flag)?;
                args.position = parse_position(&raw)?;
            }
            "--dump" => {
                args.dump = Some(next_value(argv, &mut i, flag)?);
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

/// "Left" means the list is on the left, matching the old card's `HUD.position`.
fn parse_position(raw: &str) -> Result<Position, String> {
    match raw.to_ascii_lowercase().as_str() {
        "stacked" | "stack" | "default" => Ok(Position::Stacked),
        "left" => Ok(Position::ListLeft),
        "right" => Ok(Position::ListRight),
        other => Err(format!("--position: expected stacked, left or right, got {other}")),
    }
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

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

// ── window procedure ────────────────────────────────────────────────────────

/// `unsafe extern "system"` because user32 calls it: the signature has to match
/// the ABI the class was registered with, and every argument arrives as a raw
/// Win32 value. It runs on the thread that owns the window, which is the thread
/// that runs the frame loop below.
unsafe extern "system" fn wnd_proc(hwnd: Hwnd, msg: u32, wp: Wparam, lp: Lparam) -> Lresult {
    match msg {
        // WS_EX_TRANSPARENT already keeps the mouse away; answering like this as
        // well means a click that does arrive is routed to the window below
        // instead of being swallowed if the style is ever dropped by the driver.
        WM_NCHITTEST => HTTRANSPARENT,
        // Both paths end the same way: the frame loop sees WM_QUIT and falls out
        // to the cleanup at the end of run().
        WM_CLOSE | WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}

// ── frame content ───────────────────────────────────────────────────────────

/// A representative module table for `--dump`: the mix the real HUD shows —
/// valued and bare modules, two accent values, one module mid-flash.
fn demo_entries() -> Vec<Entry> {
    const DEMO: &[(&str, &str, bool)] = &[
        ("AutoClicker", "12.3 cps", true),
        ("Velocity", "82%", true),
        ("Keystrokes", "", false),
        ("Reach", "3.1-3.3", false),
        ("HitSelect", "", false),
        ("BlockHit", "", false),
        ("WTap", "", false),
        ("Sprint", "", false),
    ];

    DEMO.iter()
        .map(|(name, value, highlight)| Entry {
            id: state::fnv1a(name),
            enabled: true,
            flash: false,
            highlight: *highlight,
            name: (*name).to_string(),
            value: (*value).to_string(),
        })
        .collect()
}

/// Ids the dump seeds with a partly decayed flash (255 = just fired).
fn demo_flashes() -> Vec<(u32, u8)> {
    vec![(state::fnv1a("Velocity"), 150)]
}

/// Cheap change detector: everything the card depends on except time. When it
/// matches the previous frame and nothing is animating, the compositor already
/// holds the right pixels and the frame is skipped.
fn signature(entries: &[Entry], fade: f32) -> u64 {
    fn mix(hash: &mut u64, byte: u8) {
        *hash ^= byte as u64;
        *hash = hash.wrapping_mul(0x0000_0100_0000_01B3);
    }

    let mut hash: u64 = 0xCBF2_9CE4_8422_2325;
    mix(&mut hash, entries.len() as u8);
    for e in entries {
        for b in e.id.to_le_bytes() {
            mix(&mut hash, b);
        }
        mix(&mut hash, e.flash as u8);
        mix(&mut hash, e.highlight as u8);
        for b in e.name.as_bytes() {
            mix(&mut hash, *b);
        }
        mix(&mut hash, 0);
        for b in e.value.as_bytes() {
            mix(&mut hash, *b);
        }
        mix(&mut hash, 0xFF);
    }
    // The fade is quantised into the signature so the ramp repaints, without
    // floating-point noise making every frame look different.
    mix(&mut hash, (fade.clamp(0.0, 1.0) * 64.0) as u8);
    hash
}

// ── entry points ────────────────────────────────────────────────────────────

/// Create the overlay and run the frame loop until the game goes away or the
/// window is closed.
fn run(args: &Args) -> Result<(), String> {
    let state_path = state::default_path();
    let mut text = TextEngine::new()?;

    let height = args.h.unwrap_or(DEF_H);
    let width = args.w.unwrap_or_else(|| match args.position {
        Position::Stacked => DEF_W,
        // The split layouts put the list and the pill in opposite *screen*
        // corners, so the stage has to span the screen.
        Position::ListLeft | Position::ListRight => {
            let screen = unsafe { GetSystemMetrics(SM_CXSCREEN) };
            (screen.max(MIN_SPLIT_W as i32)) as u32
        }
    });
    if width == 0 || height == 0 {
        return Err("--w and --h must both be non-zero".to_string());
    }

    let mut config = Config { position: args.position, margin: hud::MARGIN, alpha: 1.0 };

    // One `unsafe` block, and the smallest one this job allows: Win32 is all
    // there is to call here, so the alternative would be scattering `unsafe`
    // through the function.
    //
    // SAFETY: every call below is a Win32 entry point taking plain handles and
    // pointers to the stack values just built. The window, DIB and DC never leave
    // this function; the only memory reached through a raw pointer is the DIB
    // section's own storage, which Win32 sized for us and which we fill
    // contiguously before handing it back to the compositor. The function runs on
    // the thread that created the window and no other thread touches these
    // handles. TextEngine owns its own scratch DC/DIB and is dropped after the
    // window's DC is deleted, so the two never share an object.
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
            width as i32,
            height as i32,
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
        info.header.width = width as i32;
        // Negative height asks for a top-down DIB: row 0 is the top scanline,
        // which is the order the canvas writes in, so no flip is needed.
        info.header.height = -(height as i32);
        info.header.planes = 1;
        info.header.bit_count = 32;
        info.header.compression = BI_RGB;

        let mut bits: *mut c_void = std::ptr::null_mut();
        let dib = CreateDIBSection(mem_dc, &info, DIB_RGB_COLORS, &mut bits, std::ptr::null_mut(), 0);
        if dib.is_null() || bits.is_null() {
            DeleteDC(mem_dc);
            return Err("CreateDIBSection failed".to_string());
        }
        let previous = SelectObject(mem_dc, dib);

        let size = Size { cx: width as i32, cy: height as i32 };
        let src_pos = Point { x: 0, y: 0 };
        let blend = BlendFunction {
            blend_op: AC_SRC_OVER,
            blend_flags: 0,
            // The alpha is already in the pixels; a constant alpha below 255
            // would dim the whole card a second time.
            source_constant_alpha: 255,
            alpha_format: AC_SRC_ALPHA,
        };

        // ── live state ──
        let mut canvas = Canvas::new(width, height);
        let mut anim = Anim::new();
        let mut entries: Vec<Entry> = Vec::new();
        // Last write time of the state file — the game's own liveness signal.
        let mut last_write: Option<std::time::SystemTime> = None;
        let mut fade = 1.0f32;
        let mut last_signature = 0u64;
        let mut animating = true;
        let mut shown = false;
        let mut result: Result<(), String> = Ok(());

        let started = Instant::now();
        let mut previous_frame = started;
        let mut msg: Msg = std::mem::zeroed();

        loop {
            // Drain the queue. GetMessageW would block and the card has to keep
            // animating while the game is running, so this loop peeks and paces
            // itself instead.
            let mut quit = false;
            while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
                if msg.message == WM_QUIT {
                    quit = true;
                    break;
                }
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            if quit {
                break;
            }

            let now = Instant::now();
            let dt = now.saturating_duration_since(previous_frame).as_secs_f32();
            previous_frame = now;

            // D2: read the published block. A missing, short or foreign file
            // leaves the last good frame on screen — never garbage.
            if let Some(snapshot) = state::read(&state_path) {
                entries = snapshot.entries;
                if let Some(modified) = snapshot.modified {
                    last_write = Some(modified);
                }
            }

            // The game is gone when nothing has written the file for three seconds; fading out and
            // exiting beats leaving a ghost card on the desktop. Before the FIRST write the overlay
            // is still waiting for a game that may be loading, so it gets a much longer grace
            // instead (see FIRST_WRITE_GRACE_SECS).
            let stale = match last_write {
                Some(written) => written.elapsed().map(|d| d.as_secs_f32() > STALE_SECS).unwrap_or(false),
                None => started.elapsed().as_secs_f32() > FIRST_WRITE_GRACE_SECS,
            };
            if stale {
                fade -= dt / FADE_SECS;
                if fade <= 0.0 {
                    break;
                }
            }

            anim.update(&entries);
            let current = signature(&entries, fade);
            if current != last_signature || animating {
                config.alpha = fade;
                // The card is redrawn from scratch: last frame's pixels must not
                // survive underneath the new ones.
                canvas.clear();
                animating = hud::paint(&mut canvas, &mut text, &entries, &mut anim, now, &config);
                last_signature = current;

                let pixels = canvas.pixels();
                std::ptr::copy_nonoverlapping(
                    pixels.as_ptr() as *const u8,
                    bits as *mut u8,
                    pixels.len() * std::mem::size_of::<u32>(),
                );
                // A null destination position keeps the coordinates
                // CreateWindowExW was given — moving the window here would be a
                // second chance to get the placement wrong.
                if UpdateLayeredWindow(
                    hwnd,
                    std::ptr::null_mut(),
                    std::ptr::null(),
                    &size,
                    mem_dc,
                    &src_pos,
                    0,
                    &blend,
                    ULW_ALPHA,
                ) == 0
                {
                    result = Err("UpdateLayeredWindow failed".to_string());
                    break;
                }

                // Shown only once a finished card is in the window, so the first
                // thing the desktop sees is the card rather than an empty window,
                // and shown without activation.
                if !shown {
                    ShowWindow(hwnd, SW_SHOWNOACTIVATE);
                    shown = true;
                }
            }

            let frame = now.elapsed();
            if frame < Duration::from_millis(FRAME_MS) {
                std::thread::sleep(Duration::from_millis(FRAME_MS) - frame);
            }
        }

        // Released in reverse: a DIB must not be deleted while it is still
        // selected into the DC.
        SelectObject(mem_dc, previous);
        DeleteObject(dib);
        DeleteDC(mem_dc);
        result
    }
}

/// `--dump`: render one representative frame to a BMP and print the ASCII
/// preview. No window class is registered and no window is created — the text
/// engine only needs a memory DC, so this works on a session with no desktop.
/// `--position` is honoured so both layouts can be reviewed the same way.
fn run_dump(path: &str, position: Position) -> Result<(), String> {
    let mut text = TextEngine::new()?;
    // The split layouts put the two halves in opposite screen corners; the dump
    // uses a deterministic screen width rather than whatever this session has.
    let (w, h) = (if position == Position::Stacked { DEF_W } else { 900 }, DEF_H);
    let mut canvas = Canvas::new(w, h);

    let entries = demo_entries();
    let mut anim = Anim::new();
    let now = Instant::now();
    anim.seed(&entries, now, &demo_flashes());

    let config = Config { position, margin: hud::MARGIN, alpha: 1.0 };
    canvas.clear();
    hud::paint(&mut canvas, &mut text, &entries, &mut anim, now, &config);

    preview::write_bmp(Path::new(path), &canvas, BACKDROP)
        .map_err(|e| format!("cannot write {path}: {e}"))?;
    println!(
        "wrote {path} ({w}x{h}, 32-bit BMP, {:?} layout, composited over #{:06X})",
        position,
        BACKDROP & 0x00FF_FFFF
    );
    // The numbers behind the picture: a dump that looks wrong is much easier to
    // fix when the face metrics are on screen next to it.
    let (pill_w, pill_h) = hud::pill_size(&text);
    let row_h = hud::row_height(&text);
    println!(
        "faces: ui {}px cell, bold {}px cell, mono {}px cell | pill {}x{} | \
         {} rows of {}px (+2) from y={} | widest value {}px",
        text.height(text::FontId::Ui),
        text.height(text::FontId::UiBold),
        text.height(text::FontId::Mono),
        pill_w,
        pill_h,
        entries.len(),
        row_h,
        hud::MARGIN + pill_h + 10,
        entries
            .iter()
            .map(|e| text.measure(text::FontId::Mono, &e.value))
            .max()
            .unwrap_or(0),
    );
    // The measured card: pill and backing sheet, the row band and each row's
    // name-end -> value distance. Printed from the same layout the painter used,
    // so a dump that looks wrong can be checked against real numbers.
    print!("{}", hud::describe(&text, &entries, position, w));
    print!("{}", preview::ascii_preview(&canvas, BACKDROP, 106, 44));
    Ok(())
}

fn main() {
    // Must happen before any window is created: on a scaled display this is what keeps one canvas
    // pixel equal to one screen pixel instead of letting Windows stretch the card.
    win::enable_dpi_awareness();

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

    if let Some(path) = &args.dump {
        if let Err(reason) = run_dump(path, args.position) {
            eprintln!("error: {reason}");
            std::process::exit(1);
        }
        return;
    }

    if args.hide {
        // Automation hook: proves the executable starts, parses its arguments and
        // resolves its imports without ever touching the desktop.
        println!(
            "{} {} — ok, no window created (stage would be {}x{} at {}, {}, layout {:?})",
            env!("CARGO_PKG_NAME"),
            env!("CARGO_PKG_VERSION"),
            args.w.map(|v| v as i64).unwrap_or(-1),
            args.h.unwrap_or(DEF_H),
            args.x,
            args.y,
            args.position
        );
        return;
    }

    if let Err(reason) = run(&args) {
        eprintln!("error: {reason}");
        std::process::exit(1);
    }
}
