// main.rs — the out-of-process overlay window (plan phase D0, now D1..D7).
//
// Why a second process at all: drawing the HUD from inside the JVM means
// touching the game's render thread and its GL state, which is exactly the
// coupling the plan moves away from. A plain Win32 process can put the HUD on top
// of the game without ever being part of it.
//
// Why a layered window: the HUD has to look like it belongs to the game, which
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
// What the frame loop does: read the published state block (D2), advance the
// entrance animation and the accent clock (D3/D4), rasterise the HUD into the
// canvas, push it through UpdateLayeredWindow. Frames are pushed when something
// actually changed: the signature covers the state and the fade, and `hud::paint`
// reports whether a row is still sliding in or whether the mode's accent is a
// function of the clock. In Rise, VapeV4 and Tesseract — and in Default with the
// wave on — the accent moves every frame, so those frames really do differ from
// the last; a waveless Default HUD is genuinely static and those frames are
// skipped.
//
// Zero third-party dependencies, same rule as the injector: kernel32/user32/gdi32
// through raw extern "system" FFI, declared in win.rs. The pixel work lives in
// draw.rs (pure), the text in text.rs (GDI), the HUD in hud.rs (pure geometry).
//
// The HUD itself is a port of OpenCommunity's in-game HUD (its `HUD.cpp`), all
// four of its modes, selectable with `--mode`; `hud.rs` cites the source line of
// every constant it takes and the README says what could not be reproduced.

mod draw;
mod hud;
mod log;
mod preview;
mod state;
mod text;
mod win;

use std::ffi::c_void;
use std::path::Path;
use std::time::{Duration, Instant};

use draw::{argb, Canvas};
use hud::{Anim, Config, Mode, Position, Rect};
use state::Entry;
use text::TextEngine;
use win::*;

/// Neutral class name and an empty caption: nothing that greps for the product's
/// name may find this window (plan §7).
const CLASS_NAME: &str = "WndClsA1";

// ── window geometry ─────────────────────────────────────────────────────────
//
// The window covers a whole monitor rect — the primary monitor by default, or
// whatever `--rect` names — and the HUD is drawn inside it at the margins the
// ported mode asks for (4 or 6 px, HUD.cpp:489-491). Every other pixel stays
// fully transparent. The window is click-through and never visible as a surface,
// so the transparent area costs nothing but the DIB it needs.
//
// It used to be a 520x400 *stage* at (0, 0), which is exactly why the overlay
// looked as if it were not there: the HUD anchors its blocks to the edges of
// whatever rect it is handed, so a 520-wide canvas put the module list around
// x = 400 — the top-left/middle of a 1920-wide screen — with all the rest of the
// window transparent. The layout was never wrong; the rect it hung off was. The
// stage that remains is `--dump`'s, and it is only a review canvas: the live
// window is the monitor.

/// The size `--dump` renders on. Deliberately *not* the live window's size: every
/// review BMP this project has produced is comparable at 520x400, and the dump
/// prints the runtime rect it would use alongside, so the two cannot be confused.
const DEF_W: u32 = 520;
const DEF_H: u32 = 400;

/// The rect used when `GetSystemMetrics` cannot answer (a session with no display):
/// a sane desktop rather than a zero-sized window.
const FALLBACK_W: i32 = 1920;
const FALLBACK_H: i32 = 1080;

/// Backdrop the `--dump` BMP is composited over: the HUD is translucent, so a
/// dump has to sit on something. It is a dark game-like screen rather than the
/// mid-tone desk the mockup skin used: the ported HUD's Default accent is a
/// dim blue-grey (`78,86,107` at one end of its wave) that a mid-tone backdrop
/// swallows, and the Tesseract/Rise chips are near-black panels that only read
/// against something darker than they are. The text shadow and the accent bars
/// stay visible against it either way.
const BACKDROP: u32 = argb(0x22, 0x26, 0x2E, 0xFF);

// ── process behaviour ───────────────────────────────────────────────────────

/// Target frame interval. A row's entrance is a 1/6 s slide and the accents are
/// continuous, so 60 Hz is smooth and the loop is idle-cheap (a state read and a
/// hash) whenever nothing is moving.
const FRAME_MS: u64 = 16;
/// "The game is gone": nothing has written the state file for this long.
const STALE_SECS: f32 = 3.0;
/// Silence tolerated BEFORE the first successful read. The injector starts this process while the
/// game is still loading, and Forge + OptiFine + world load routinely take longer than three
/// seconds — with the short rule the window would die before the JVM ever published anything.
const FIRST_WRITE_GRACE_SECS: f32 = 30.0;
/// How long the HUD takes to fade out once that happens.
const FADE_SECS: f32 = 0.35;

const USAGE: &str = "\
usage: doppel-hud [options]

  --rect <x> <y> <w> <h>
                       the screen rect the overlay covers. The layered window is
                       created at (x, y) with that size and the HUD is laid out
                       inside it, at the margins the mode asks for (4 px in
                       Rise/VapeV4, 6 px otherwise) — a caller following the game
                       window on a multi-monitor desktop passes that window's rect
                       here. Default: the whole primary monitor, from
                       GetSystemMetrics(SM_CXSCREEN / SM_CYSCREEN) (the primary
                       monitor's origin is (0, 0) by definition), or 1920x1080 if
                       that call gives nothing usable.
  --x <i32>            adjust the chosen rect's x (default: the rect's own)
  --y <i32>            adjust the chosen rect's y
  --w <u32>            adjust the chosen rect's width
  --h <u32>            adjust the chosen rect's height
  --position <where>   right (default) = the ported HUD's own placement: the
                       watermark 10 px in from the left edge and the module list
                       flushed against the right one (HUD.cpp:537, 611); left =
                       the same two elements on the opposite pair of edges.
                       `stacked` is accepted as a synonym of `right`.
  --mode <name>        default | rise | vape | tesseract. The four modes of the
                       ported HUD (ArrayList.h:14); each one brings its own
                       geometry, colours and accent animation. `vapev4` is
                       accepted for `vape`. Default: default.
  --scale <f>          multiply every metric and the font size, for a readable HUD
                       on a high-DPI screen (0.5 .. 4.0). Default: 1.0, which is
                       the source's own physical-pixel layout.
  --primary <r,g,b>    the Default mode's wave colour pair (0..255 each).
                       Default: 78,86,107 (ModuleConfig.h:65).
  --secondary <r,g,b>  the colour the wave blends towards. Default: 120,146,214.
  --wave-speed <f>     cycles per 2500 ms of the Default mode's wave
                       (ModuleConfig.h:64). Default: 1.
  --no-wave            draw the Default mode's accent as a flat primary colour.
  --no-watermark       do not draw the watermark run (m_Watermark, default on).
  --spaced             split CamelCase module names (Spaced Modules, default off).
  --dump <path.bmp>    render one representative frame to a 32-bit BMP on the
                       fixed 520x400 review canvas, print the measured geometry,
                       an ASCII preview and the runtime rect the live window
                       would cover, create no window, exit 0
  --hide               print one line, create no window, exit 0 (automation check)
  --help               print this text and exit 0

The overlay reads %TEMP%\\doppel-hud.state, published by the game 3600 bytes at a
time (magic 0x444F5050, version 1). It opens the file read-only: there is no back
channel and nothing this process does can reach the game.

It keeps its own breadcrumbs in %TEMP%\\doppel-hud.log: one line on start (local
time, pid, the rect it covers, the mode, the resolved state file and whether it was
readable), one on exit (why it stopped, and how many frames it drew). Logging can
never affect the overlay, and a log over 64 KB is truncated on the next start.

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
    /// `--rect x y w h`: the rect the window covers. `None` = the primary monitor.
    rect: Option<Rect>,
    /// Per-field adjustments applied on top of whichever rect was chosen. `None`
    /// keeps the rect's own value, which is what makes the monitor the default
    /// rather than a silent 520x400 shrink.
    x: Option<i32>,
    y: Option<i32>,
    w: Option<u32>,
    h: Option<u32>,
    position: Position,
    /// `--mode`: which of the ported HUD's four modes is drawn.
    mode: Mode,
    /// `--scale`: the multiplier on every metric and on the font size.
    scale: f32,
    /// `m_Watermark`.
    watermark: bool,
    /// `m_Wave`.
    wave: bool,
    /// `m_WaveSpeed`.
    wave_speed: f32,
    /// `m_PrimaryColor` / `m_SecondaryColor`.
    primary: [f32; 3],
    secondary: [f32; 3],
    /// `m_SpacedModules`.
    spaced: bool,
    /// Headless render to a BMP, no window.
    dump: Option<String>,
    /// Headless check: prove the binary starts and links without opening a window.
    hide: bool,
    help: bool,
}

impl Default for Args {
    fn default() -> Args {
        Args {
            rect: None,
            x: None,
            y: None,
            w: None,
            h: None,
            position: Position::Right,
            mode: Mode::Default,
            scale: 1.0,
            watermark: true,
            wave: true,
            wave_speed: hud::DEFAULT_WAVE_SPEED,
            primary: hud::DEFAULT_PRIMARY,
            secondary: hud::DEFAULT_SECONDARY,
            spaced: false,
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
            "--rect" => args.rect = Some(parse_rect(argv, &mut i)?),
            "--x" => args.x = Some(parse_num(flag, &next_value(argv, &mut i, flag)?)?),
            "--y" => args.y = Some(parse_num(flag, &next_value(argv, &mut i, flag)?)?),
            "--w" => args.w = Some(parse_num(flag, &next_value(argv, &mut i, flag)?)?),
            "--h" => args.h = Some(parse_num(flag, &next_value(argv, &mut i, flag)?)?),
            "--position" => {
                let raw = next_value(argv, &mut i, flag)?;
                args.position = parse_position(&raw)?;
            }
            "--mode" => {
                let raw = next_value(argv, &mut i, flag)?;
                args.mode = Mode::parse(&raw)?;
            }
            "--scale" => {
                let raw = next_value(argv, &mut i, flag)?;
                let value: f32 = parse_num(flag, &raw)?;
                if !value.is_finite() || !(text::MIN_SCALE..=text::MAX_SCALE).contains(&value) {
                    return Err(format!(
                        "--scale: expected {} .. {}, got {raw}",
                        text::MIN_SCALE,
                        text::MAX_SCALE
                    ));
                }
                args.scale = value;
            }
            "--primary" => args.primary = parse_rgb(&next_value(argv, &mut i, flag)?)?,
            "--secondary" => args.secondary = parse_rgb(&next_value(argv, &mut i, flag)?)?,
            "--wave-speed" => {
                args.wave_speed = parse_num(flag, &next_value(argv, &mut i, flag)?)?;
            }
            "--no-wave" => {
                args.wave = false;
                i += 1;
            }
            "--no-watermark" => {
                args.watermark = false;
                i += 1;
            }
            "--spaced" => {
                args.spaced = true;
                i += 1;
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

/// The `HUD{}` block of the args (ModuleConfig.h:57-68, ArrayList.h:15-19), with
/// the live fade on top. Both the frame loop and `--dump` build their config here,
/// so a dump is exactly what the window would draw.
fn config_from(args: &Args, alpha: f32) -> Config {
    Config {
        position: args.position,
        alpha,
        mode: args.mode,
        scale: args.scale,
        watermark: args.watermark,
        wave: args.wave,
        wave_speed: args.wave_speed,
        primary: args.primary,
        secondary: args.secondary,
        spaced: args.spaced,
    }
}

/// `--rect x y w h` — four numbers, then the cursor past all five tokens. The
/// width and the height must be positive: a zero-sized rect is the exact mistake
/// this flag exists to avoid, so it is refused rather than quietly drawing into a
/// window nobody can see.
fn parse_rect(argv: &[String], i: &mut usize) -> Result<Rect, String> {
    let raw = argv
        .get(*i + 1..*i + 5)
        .filter(|values| values.len() == 4)
        .ok_or("--rect needs four numbers: x y w h")?;
    let mut values = [0i32; 4];
    for (slot, text) in values.iter_mut().zip(raw) {
        *slot = parse_num("--rect", text)?;
    }
    *i += 5;
    let rect = Rect::new(values[0], values[1], values[2], values[3]);
    if rect.is_empty() {
        return Err(format!(
            "--rect: width and height must be positive, got {}x{}",
            rect.w, rect.h
        ));
    }
    Ok(rect)
}

/// `right` is the ported HUD's own placement and the default. `left` swaps the two
/// elements to the other pair of corners; `stacked` is kept as a synonym of
/// `right`, because that is the corner pair the old stacked card put its brand
/// pill in.
fn parse_position(raw: &str) -> Result<Position, String> {
    match raw.to_ascii_lowercase().as_str() {
        "right" | "stacked" | "stack" | "default" => Ok(Position::Right),
        "left" => Ok(Position::Left),
        other => Err(format!("--position: expected right (default) or left, got {other}")),
    }
}

/// `--primary r,g,b` / `--secondary r,g,b`, the two numbers the source's config
/// carries as floats over 255 (ModuleConfig.h:65-66). Three channels are required:
/// a missing one would leave a colour silently wrong, which is the one thing a
/// colour flag must not do.
fn parse_rgb(raw: &str) -> Result<[f32; 3], String> {
    let parts: Vec<&str> = raw.split(',').collect();
    if parts.len() != 3 {
        return Err(format!("expected three channels as r,g,b (0..255 each), got {raw}"));
    }
    let mut out = [0.0f32; 3];
    for (slot, text) in out.iter_mut().zip(parts) {
        let value: f32 = text
            .trim()
            .parse()
            .map_err(|_| format!("not a valid colour channel: {text}"))?;
        if !value.is_finite() || !(0.0..=255.0).contains(&value) {
            return Err(format!("colour channels are 0..255, got {text}"));
        }
        *slot = value / 255.0;
    }
    Ok(out)
}

/// Consume the value that follows a flag and step the cursor past both. A
/// missing value is an error rather than a silent default: `--w --hide` should
/// not quietly produce a 0-wide stage.
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

// ── which rect the window covers ────────────────────────────────────────────

/// Where the rect came from. Logged and printed by `--dump`: "the overlay drew
/// but the HUD was in the wrong place" and "the overlay never saw a monitor"
/// are very different reports.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum RectSource {
    /// `GetSystemMetrics(SM_CXSCREEN / SM_CYSCREEN)`.
    Monitor,
    /// That call answered nothing usable, so `FALLBACK_W`x`FALLBACK_H` was used.
    Fallback,
    /// `--rect`, adjusted by any of `--x/--y/--w/--h`.
    Override,
}

impl RectSource {
    fn label(self) -> &'static str {
        match self {
            RectSource::Monitor => "primary monitor",
            RectSource::Fallback => "fallback (GetSystemMetrics gave nothing)",
            RectSource::Override => "--rect override",
        }
    }
}

/// The primary monitor's rect. `SM_CXSCREEN`/`SM_CYSCREEN` are its size and its
/// origin is (0, 0) by definition — Win32 has no `SM_XSCREEN`/`SM_YSCREEN`, only
/// the virtual screen has an origin. A machine that answers 0 (no display
/// attached to this session) gets the fallback instead of a 0x0 window.
fn monitor_rect() -> (Rect, bool) {
    let (cx, cy) = unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) };
    if cx > 0 && cy > 0 {
        (Rect::new(0, 0, cx, cy), false)
    } else {
        (Rect::new(0, 0, FALLBACK_W, FALLBACK_H), true)
    }
}

/// The rect the overlay will actually cover: `--rect` when given, the primary
/// monitor otherwise, with `--x/--y/--w/--h` as per-field adjustments on top. All
/// in physical pixels, matching the process-wide DPI awareness set in `main`.
fn resolve_rect(args: &Args) -> Result<(Rect, RectSource), String> {
    let (mut rect, source) = match args.rect {
        Some(rect) => (rect, RectSource::Override),
        None => {
            let (rect, fell_back) = monitor_rect();
            (rect, if fell_back { RectSource::Fallback } else { RectSource::Monitor })
        }
    };

    if let Some(x) = args.x {
        rect.x = x;
    }
    if let Some(y) = args.y {
        rect.y = y;
    }
    if let Some(w) = args.w {
        rect.w = w as i32;
    }
    if let Some(h) = args.h {
        rect.h = h as i32;
    }
    if rect.is_empty() {
        return Err(format!(
            "--x/--y/--w/--h must leave a non-empty rect, got {}x{} at ({}, {})",
            rect.w, rect.h, rect.x, rect.y
        ));
    }
    Ok((rect, source))
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

/// A representative module table for `--dump`: the mix the real HUD shows — a
/// ten-row list, two of the rows carrying a value, one of them in use (the state's
/// `flash` byte, which is what turns a VapeV4 tag red).
fn demo_entries() -> Vec<Entry> {
    const DEMO: &[(&str, &str, bool)] = &[
        ("AutoClicker", "12.3 cps", true),
        ("Velocity", "82%", false),
        ("Keystrokes", "", false),
        ("Reach", "", false),
        ("HitSelect", "", false),
        ("BlockHit", "", false),
        ("WTap", "", false),
        ("Sprint", "", false),
        ("Safewalk", "", false),
        ("Timer", "", false),
    ];

    DEMO.iter()
        .map(|(name, value, flash)| Entry {
            id: state::fnv1a(name),
            enabled: true,
            flash: *flash,
            highlight: false,
            name: (*name).to_string(),
            value: (*value).to_string(),
        })
        .collect()
}

/// Cheap change detector: everything the HUD depends on except time. When it
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
///
/// The log is opened first and closed last: it is the only record of a run that
/// draws nothing on screen, and every write through it is best-effort, so it can
/// never be the reason the overlay fails.
fn run(args: &Args) -> Result<(), String> {
    let mut log = log::Log::open();
    let state_path = state::default_path();

    let (rect, source) = match resolve_rect(args) {
        Ok(chosen) => chosen,
        Err(reason) => {
            log.line(&format!("start refused: {reason}"));
            return Err(reason);
        }
    };

    // Read once before the loop starts: the answer is part of the startup line,
    // and it is the difference between "the game has not published yet" and "the
    // file is there but not ours".
    let first_read = state::read(&state_path);
    log.line(&format!(
        "start: rect {}x{} at ({}, {}) [{}], position {:?}, mode {} (scale {:.2}), \
         watermark {}, wave {}, state {} readable: {}",
        rect.w,
        rect.h,
        rect.x,
        rect.y,
        source.label(),
        args.position,
        args.mode.label(),
        args.scale,
        args.watermark,
        args.wave,
        state_path.display(),
        match &first_read {
            Some(snapshot) => format!("yes ({} entries)", snapshot.entries.len()),
            None => "no (missing, short, foreign or wrong version)".to_string(),
        }
    ));

    let mut text = match TextEngine::new(args.scale) {
        Ok(text) => text,
        Err(reason) => {
            log.line(&format!("exit: error: {reason}, 0 frames drawn"));
            return Err(reason);
        }
    };

    let mut config = config_from(args, 1.0);
    // How many frames reached the compositor. Zero on a run that ended by itself
    // is itself a diagnosis: the state file never showed up in time.
    let mut frames: u64 = 0;
    // When the process started, for the grace period and the exit line.
    let started = Instant::now();
    // Why the loop ends. Every `break` below sets it and the exit line logs it
    // verbatim, so it is deliberately left uninitialised: a new exit path that
    // forgets to say why is then a compile error rather than a vague log line.
    let exit_reason: String;

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
    let outcome: Result<(), String> = unsafe {
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
            log.line(&format!("exit: error: RegisterClassExW failed, {frames} frames drawn"));
            return Err("RegisterClassExW failed".to_string());
        }

        // The window *is* the rect: created at its corner, sized to it, so an
        // element laid out MARGIN inside the canvas is MARGIN inside the screen.
        let title = wide("");
        let hwnd = CreateWindowExW(
            WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT,
            class_name.as_ptr(),
            title.as_ptr(),
            WS_POPUP,
            rect.x,
            rect.y,
            rect.w,
            rect.h,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null_mut(),
        );
        if hwnd.is_null() {
            log.line(&format!("exit: error: CreateWindowExW failed, {frames} frames drawn"));
            return Err("CreateWindowExW failed".to_string());
        }

        // A never-showing, never-focusing window is also never painted, so the
        // HUD is rendered into a DIB and pushed through UpdateLayeredWindow;
        // that is the whole reason WS_EX_LAYERED is here.
        //
        // CreateCompatibleDC(NULL) gives a DC compatible with the screen, which
        // is all the DIB needs — no window DC is acquired, so there is nothing to
        // release if the next call fails.
        let mem_dc = CreateCompatibleDC(std::ptr::null_mut());
        if mem_dc.is_null() {
            log.line(&format!("exit: error: CreateCompatibleDC failed, {frames} frames drawn"));
            return Err("CreateCompatibleDC failed".to_string());
        }

        let mut info: BitmapInfo = std::mem::zeroed();
        info.header.size = std::mem::size_of::<BitmapInfoHeader>() as u32;
        info.header.width = rect.w;
        // Negative height asks for a top-down DIB: row 0 is the top scanline,
        // which is the order the canvas writes in, so no flip is needed.
        info.header.height = -rect.h;
        info.header.planes = 1;
        info.header.bit_count = 32;
        info.header.compression = BI_RGB;

        let mut bits: *mut c_void = std::ptr::null_mut();
        let dib = CreateDIBSection(mem_dc, &info, DIB_RGB_COLORS, &mut bits, std::ptr::null_mut(), 0);
        if dib.is_null() || bits.is_null() {
            DeleteDC(mem_dc);
            log.line(&format!("exit: error: CreateDIBSection failed, {frames} frames drawn"));
            return Err("CreateDIBSection failed".to_string());
        }
        let previous = SelectObject(mem_dc, dib);

        let size = Size { cx: rect.w, cy: rect.h };
        let src_pos = Point { x: 0, y: 0 };
        let blend = BlendFunction {
            blend_op: AC_SRC_OVER,
            blend_flags: 0,
            // The alpha is already in the pixels; a constant alpha below 255
            // would dim the whole HUD a second time.
            source_constant_alpha: 255,
            alpha_format: AC_SRC_ALPHA,
        };

        // ── live state ──
        // The canvas carries the window's screen origin, so the HUD is measured
        // against the monitor rect the window covers and not against the surface's
        // own (0, 0).
        let mut canvas = Canvas::placed(rect.x, rect.y, rect.w as u32, rect.h as u32);
        let mut anim = Anim::new();
        let mut entries: Vec<Entry> = Vec::new();
        // Last write time of the state file — the game's own liveness signal.
        let mut last_write: Option<std::time::SystemTime> = None;
        let mut fade = 1.0f32;
        let mut last_signature = 0u64;
        let mut animating = true;
        let mut shown = false;
        let mut result: Result<(), String> = Ok(());

        let mut previous_frame = started;
        let mut msg: Msg = std::mem::zeroed();

        loop {
            // Drain the queue. GetMessageW would block and the HUD has to keep
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
                exit_reason = "window lost (WM_QUIT / WM_CLOSE)".to_string();
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
            // exiting beats leaving a ghost HUD on the desktop. Before the FIRST write the overlay
            // is still waiting for a game that may be loading, so it gets a much longer grace
            // instead (see FIRST_WRITE_GRACE_SECS).
            let stale = match last_write {
                Some(written) => written.elapsed().map(|d| d.as_secs_f32() > STALE_SECS).unwrap_or(false),
                None => started.elapsed().as_secs_f32() > FIRST_WRITE_GRACE_SECS,
            };
            if stale {
                fade -= dt / FADE_SECS;
                if fade <= 0.0 {
                    // "Never wrote" and "stopped writing" are different reports:
                    // the first means the game never came up, the second that it
                    // went away. Both exit 0 — a game that quits is not an error.
                    exit_reason = match last_write {
                        Some(_) => format!("state stale (no write for {STALE_SECS:.0}s)"),
                        None => format!(
                            "state file never appeared (first-write grace of \
                             {FIRST_WRITE_GRACE_SECS:.0}s timed out)"
                        ),
                    };
                    break;
                }
            }

            // The slide state, the frame delta and the accent clock all advance
            // here (the delta even on a frame that is not repainted, so an idle
            // stretch cannot turn into a catch-up jump), and `hud::paint` reports
            // whether the frame it composed is still moving.
            anim.tick(now);
            let current = signature(&entries, fade);
            if current != last_signature || animating {
                config.alpha = fade;
                // The HUD is redrawn from scratch: last frame's pixels must not
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
                // second chance to get the placement wrong. A failure is fatal,
                // not a frame to skip: without it the window would stay empty
                // while the process kept looping, which is precisely the silent
                // "it started and I see nothing" this log exists to rule out.
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
                    // Read the Win32 error before anything else can overwrite it.
                    let reason =
                        format!("UpdateLayeredWindow failed: {}", std::io::Error::last_os_error());
                    exit_reason = format!("error: {reason}");
                    result = Err(reason);
                    break;
                }
                frames += 1;

                // Shown only once a finished HUD is in the window, so the first
                // thing the desktop sees is the HUD rather than an empty window,
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
    };

    // One line whatever happened: why it stopped and how much it drew. Written
    // here, after the frame loop and before `main` turns an error into exit code
    // 1, so a run that put nothing on screen is diagnosed from the log rather
    // than from a blank desktop.
    log.line(&format!(
        "exit: {exit_reason}, {frames} frames drawn, {:.1}s",
        started.elapsed().as_secs_f32()
    ));
    outcome
}

/// `--dump`: render one representative frame to a BMP and print the ASCII
/// preview. No window class is registered and no window is created — the text
/// engine only needs a memory DC, so this works on a session with no desktop.
/// `--position`, `--mode`, `--scale` and the rest of the config are honoured, so
/// all four modes can be reviewed the same way.
///
/// `runtime` is the rect the *live* window would cover (the monitor, or `--rect`),
/// which is printed but not drawn on: the review canvas stays the fixed 520x400
/// every earlier dump was compared at, and the printed coordinates are therefore
/// canvas-local. The runtime line is what says where those pixels would land on
/// this machine's screen.
fn run_dump(path: &str, args: &Args, runtime: Rect, source: RectSource) -> Result<(), String> {
    let mut text = TextEngine::new(args.scale)?;
    let (w, h) = (DEF_W, DEF_H);
    let mut canvas = Canvas::new(w, h);

    let entries = demo_entries();
    let mut anim = Anim::new();
    let now = Instant::now();
    // Every row settled and the accent clock at a known 2000 ms, so the frame's
    // colours are the accent functions at a fixed timestamp rather than whatever
    // the wall clock happened to say.
    anim.seed(&entries, now);

    let config = config_from(args, 1.0);
    canvas.clear();
    hud::paint(&mut canvas, &mut text, &entries, &mut anim, now, &config);

    preview::write_bmp(Path::new(path), &canvas, BACKDROP)
        .map_err(|e| format!("cannot write {path}: {e}"))?;
    let metrics = hud::Metrics::new(args.mode, args.scale);
    println!(
        "wrote {path} ({w}x{h}, 32-bit BMP, mode {}, {:?} layout, composited over #{:06X})",
        args.mode.label(),
        args.position,
        BACKDROP & 0x00FF_FFFF
    );
    // The rect the live window covers, which is *not* the review canvas: this is
    // the number that decides whether the HUD lands against the screen's edges.
    // Both elements' own origins are given in screen coordinates, so the anchoring
    // can be checked without running the windowed path.
    let name_font = args.mode.name_font();
    let title_w = text.measure(name_font, hud::PRODUCT) as f32;
    let segment = format!("{}{}", hud::SEGMENT_SEP, entries.len());
    let run_w = title_w + text.measure(text::FontId::Body, &segment) as f32;
    println!(
        "runtime window: {}x{} at ({}, {}) [{}] — the live layered window covers that rect, so the \
         watermark run ({}px) starts at ({}, {}) and the first row is flushed to the {} edge, \
         {}px inside it; the live layout is the same as this dump's, only measured against that rect",
        runtime.w,
        runtime.h,
        runtime.x,
        runtime.y,
        source.label(),
        run_w.round() as i32,
        hud::watermark_x(runtime, args.position, &metrics, run_w),
        hud::watermark_y(runtime, &metrics),
        match args.position {
            Position::Right => "right",
            Position::Left => "left",
        },
        metrics.right_margin,
    );
    // The numbers behind the picture: a dump that looks wrong is much easier to
    // fix when the face metrics are on screen next to it.
    println!(
        "faces: name (Segoe UI Semibold) {}px em / {}px cell, body (Segoe UI) {}px em / {}px cell | \
         {} published rows, {} listed | widest value {}px",
        text.em_px(text::FontId::Name).round(),
        text.cell_height(text::FontId::Name),
        text.em_px(text::FontId::Body).round(),
        text.cell_height(text::FontId::Body),
        entries.len(),
        entries.iter().filter(|e| e.enabled).count(),
        entries
            .iter()
            .map(|e| text.measure(text::FontId::Body, &e.value))
            .max()
            .unwrap_or(0),
    );
    // The measured HUD: the mode's metrics, the watermark run and every row's box,
    // insets, bar and tag colour. Printed from the same layout the painter used, so
    // a dump that looks wrong can be checked against real numbers.
    print!("{}", hud::describe(&text, &entries, &config, w, h));
    // ~4x8 canvas pixels per character: the HUD's own pieces are 16..22 px tall,
    // so anything coarser loses the accent bars.
    print!("{}", preview::ascii_preview(&canvas, BACKDROP, (w / 4).min(160) as usize, (h / 8) as usize));
    Ok(())
}

fn main() {
    // Must happen before any window is created: on a scaled display this is what keeps one canvas
    // pixel equal to one screen pixel instead of letting Windows stretch the HUD.
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
        // The dump draws on its fixed review canvas but reports the rect the live
        // window would use, so a render can be checked against the real screen.
        match resolve_rect(&args) {
            Ok((rect, source)) => {
                if let Err(reason) = run_dump(path, &args, rect, source) {
                    eprintln!("error: {reason}");
                    std::process::exit(1);
                }
            }
            Err(reason) => {
                eprintln!("error: {reason}");
                std::process::exit(2);
            }
        }
        return;
    }

    if args.hide {
        // Automation hook: proves the executable starts, parses its arguments and
        // resolves its imports without ever touching the desktop.
        let (rect, source) = match resolve_rect(&args) {
            Ok(chosen) => chosen,
            Err(reason) => {
                eprintln!("error: {reason}");
                std::process::exit(2);
            }
        };
        println!(
            "{} {} — ok, no window created (window rect {}x{} at ({}, {}) [{}], layout {:?}, mode {} \
             at scale {:.2})",
            env!("CARGO_PKG_NAME"),
            env!("CARGO_PKG_VERSION"),
            rect.w,
            rect.h,
            rect.x,
            rect.y,
            source.label(),
            args.position,
            args.mode.label(),
            args.scale,
        );
        return;
    }

    if let Err(reason) = run(&args) {
        eprintln!("error: {reason}");
        std::process::exit(1);
    }
}
