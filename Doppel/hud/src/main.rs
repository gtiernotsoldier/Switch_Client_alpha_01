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
mod follow;
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
/// How often the overlay checks whether it is still above the game.
///
/// Topmost is not permanent: a fullscreen transition, a shell surface, another
/// always-on-top window or a driver can drop the overlay out of the band, and the
/// user then sees nothing at all. The check itself is two cheap calls
/// (`GetWindowLongW` + `GetWindow`) and the re-raise only happens when one of them
/// says the overlay really has been overtaken (`win::topmost_lost`), so a settled
/// session costs no `SetWindowPos` calls — just a 2-second poll.
const TOPMOST_CHECK_MS: u64 = 2_000;

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
  --follow-pid <pid>   re-read the game window's own rect (EnumWindows +
                       GetWindowThreadProcessId + GetWindowRect) about once a second
                       and move/resize the overlay when it changes. `--rect` is
                       only read once at launch, so without this a fullscreen
                       switch, a window resize or a move to another monitor leaves
                       the overlay on the old rect. A missing/minimised window
                       changes nothing (the last good rect is kept). NOTE: under
                       EXCLUSIVE fullscreen no external overlay can ever be
                       visible — the display belongs to the game; windowed or
                       borderless fullscreen is required.
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
                       accepted for `vape`. Default: the state header's mode,
                       or `default` with no header (see below).
  --scale <f>          multiply every metric and the font size, for a readable HUD
                       on a high-DPI screen (0.5 .. 4.0). Default: 1.0, which is
                       the source's own physical-pixel layout.
  --primary <r,g,b>    the Default mode's wave colour pair (0..255 each).
                       Default: the state header's primary, else 78,86,107
                       (ModuleConfig.h:65).
  --secondary <r,g,b>  the colour the wave blends towards. Default: the state
                       header's secondary, else 120,146,214.
  --wave-speed <f>     cycles per 2500 ms of the Default mode's wave
                       (ModuleConfig.h:64). Default: 1.
  --no-wave            draw the Default mode's accent as a flat primary colour,
                       whatever the header's wave flag says.
  --no-watermark       do not draw the watermark run, whatever the header's
                       watermark flag says (m_Watermark, default on).
  --spaced             split CamelCase module names. The header's spaced flag is
                       the default; this flag turns it on regardless.
  --no-topmost         do NOT put the overlay in the always-on-top band. Without
                       WS_EX_TOPMOST the game covers the HUD the moment it has
                       focus, which is what the overlay exists to avoid; this is
                       the escape hatch, not the normal mode. It is the more
                       conspicuous combination to leave on (see README: stealth),
                       but it is also the only thing that keeps the HUD visible.
  --dump <path.bmp>    render one representative frame to a 32-bit BMP on the
                       fixed 520x400 review canvas, print the measured geometry,
                       an ASCII preview and the runtime rect the live window
                       would cover, create no window, exit 0. It draws the state
                       file's own rows and v2 header (nickname and FPS included)
                       when one is readable, and the built-in demo table when
                       there is none, so it works with no game running.
  --hide               print one line, create no window, exit 0 (automation check)
  --help               print this text and exit 0

The overlay reads %TEMP%\\doppel-hud.state, published by the game 4096 bytes at a
time (magic 0x444F5050, version 2: a 64-byte header carrying the mode, the wave /
spaced / watermark switches, the two wave colours, the player nickname and the
frame counter, then 64 entries of 56 bytes). The original 3600-byte version 1
block is still read; it has none of those header fields, so every one of them
falls back to the flag defaults above. An explicit flag always wins over the
header. The file is opened read-only: there is no back channel and nothing this
process does can reach the game.

It keeps its own breadcrumbs in %TEMP%\\doppel-hud.log: one line on start (local
time, pid, the rect it covers, the mode, the resolved state file and whether it was
readable), one on exit (why it stopped, and how many frames it drew). Logging can
never affect the overlay, and a log over 64 KB is truncated on the next start.

It never takes focus (no SetForegroundWindow / SetFocus / SW_SHOW) and every
click passes through it, so Minecraft keeps the foreground and the mouse. It does
keep itself in the always-on-top band (WS_EX_TOPMOST, re-asserted when something
gets above it) — without that the game covers the HUD as soon as it has focus.
Run the game in windowed or borderless-fullscreen mode: under exclusive
fullscreen the desktop compositor is bypassed and a layered window is never drawn.

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
    /// `--follow-pid <pid>`: re-read the game window's rect every second and re-place
    /// the overlay when it changes. `None` keeps the overlay on the rect it was
    /// created with, which is what the injector's one-shot `--rect` gives it.
    follow_pid: Option<u32>,
    /// Per-field adjustments applied on top of whichever rect was chosen. `None`
    /// keeps the rect's own value, which is what makes the monitor the default
    /// rather than a silent 520x400 shrink.
    x: Option<i32>,
    y: Option<i32>,
    w: Option<u32>,
    h: Option<u32>,
    position: Position,
    /// `--mode`: which of the ported HUD's four modes is drawn. The v2 state
    /// header carries the game's own combo, so this is the fallback until
    /// `--mode` is actually named (see `mode_set`).
    mode: Mode,
    /// `--scale`: the multiplier on every metric and on the font size. Not in the
    /// state contract: the overlay's own DPI story, not the game's config.
    scale: f32,
    /// `m_Watermark`.
    watermark: bool,
    /// `m_Wave`.
    wave: bool,
    /// `m_WaveSpeed`. Not in the state contract either.
    wave_speed: f32,
    /// `m_PrimaryColor` / `m_SecondaryColor`.
    primary: [f32; 3],
    secondary: [f32; 3],
    /// `m_SpacedModules`.
    spaced: bool,
    // Which of the fields above the caller actually named. Contract v2 puts the
    // same switches and colours in the state header, and the rule between the two
    // sources is "the header is the default, an explicit flag overrides it" — so
    // whether a flag was given has to be remembered rather than inferred from its
    // value (`--spaced` off and "no --spaced" are different states).
    mode_set: bool,
    watermark_set: bool,
    wave_set: bool,
    primary_set: bool,
    secondary_set: bool,
    spaced_set: bool,
    /// `--no-topmost`: leave the overlay in the desktop's normal Z-order band.
    topmost: bool,
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
            follow_pid: None,
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
            mode_set: false,
            watermark_set: false,
            wave_set: false,
            primary_set: false,
            secondary_set: false,
            spaced_set: false,
            topmost: true,
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
            "--follow-pid" => {
                let raw = next_value(argv, &mut i, flag)?;
                let pid: u32 = parse_num(flag, &raw)?;
                if pid == 0 {
                    return Err("--follow-pid: 0 is not a process id".to_string());
                }
                args.follow_pid = Some(pid);
            }
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
                args.mode_set = true;
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
            "--primary" => {
                args.primary = parse_rgb(&next_value(argv, &mut i, flag)?)?;
                args.primary_set = true;
            }
            "--secondary" => {
                args.secondary = parse_rgb(&next_value(argv, &mut i, flag)?)?;
                args.secondary_set = true;
            }
            "--wave-speed" => {
                args.wave_speed = parse_num(flag, &next_value(argv, &mut i, flag)?)?;
            }
            "--no-wave" => {
                args.wave = false;
                args.wave_set = true;
                i += 1;
            }
            "--no-watermark" => {
                args.watermark = false;
                args.watermark_set = true;
                i += 1;
            }
            "--spaced" => {
                args.spaced = true;
                args.spaced_set = true;
                i += 1;
            }
            "--no-topmost" => {
                args.topmost = false;
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
/// the v2 state header and the live fade layered on top. Both the frame loop and
/// `--dump` build their config here, so a dump is exactly what the window would
/// draw.
///
/// Precedence, field by field: an explicit command-line flag wins over the state
/// header, which wins over the overlay's own default. The header is the game's
/// own config — the mode combo, `m_Wave` / `m_SpacedModules` / `m_Watermark` and
/// the two wave colours — and contract v2 also carries the watermark's nickname
/// and frame counter, which no flag supplies. A v1 block (or no file at all) has
/// no header, so every one of those keeps the value the caller asked for; that is
/// what keeps the overlay runnable and reviewable with no game attached.
fn config_from(args: &Args, alpha: f32, header: Option<&state::Header>) -> Config {
    Config {
        position: args.position,
        alpha,
        mode: if args.mode_set { args.mode } else { header.map_or(args.mode, |h| mode_of(h.mode)) },
        scale: args.scale,
        watermark: if args.watermark_set {
            args.watermark
        } else {
            header.map_or(args.watermark, |h| h.watermark)
        },
        wave: if args.wave_set { args.wave } else { header.map_or(args.wave, |h| h.wave) },
        wave_speed: args.wave_speed,
        primary: if args.primary_set { args.primary } else { header.map_or(args.primary, |h| h.primary) },
        secondary: if args.secondary_set {
            args.secondary
        } else {
            header.map_or(args.secondary, |h| h.secondary)
        },
        spaced: if args.spaced_set { args.spaced } else { header.map_or(args.spaced, |h| h.spaced) },
        nickname: header.map(|h| h.nickname.clone()).unwrap_or_default(),
        fps: header.map_or(0, |h| h.fps),
    }
}

/// The header's `flags` bits 3..4 → the mode the painter knows. The two
/// vocabularies exist because `state` describes the published file and `hud`
/// describes the ported HUD; this is the one place that has to know both.
fn mode_of(bits: state::StateMode) -> Mode {
    match bits {
        state::StateMode::Default => Mode::Default,
        state::StateMode::Rise => Mode::Rise,
        state::StateMode::Vape => Mode::Vape,
        state::StateMode::Tesseract => Mode::Tesseract,
    }
}

/// What the startup line says about the first read: the block's version, its
/// entry count and — for a v2 block — the config the header asked for. "The
/// overlay is drawing the wrong mode" and "the overlay never read my header" are
/// otherwise indistinguishable from a screenshot.
fn describe_state(snapshot: &state::Snapshot) -> String {
    match &snapshot.header {
        Some(h) => format!(
            "v{} block, {} entries; header mode {} wave {} spaced {} watermark {}, \
             nick {:?}, {} fps",
            h.version,
            snapshot.entries.len(),
            h.mode.label(),
            h.wave,
            h.spaced,
            h.watermark,
            h.nickname,
            h.fps
        ),
        None => format!("v{} block, {} entries (no v2 header)", state::V1_VERSION, snapshot.entries.len()),
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
/// holds the right pixels and the frame is skipped. The v2 header is part of it:
/// a nickname, frame counter, mode or colour that changes in the state file has
/// to repaint even though every entry is identical.
fn signature(entries: &[Entry], fade: f32, header: Option<&state::Header>) -> u64 {
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
    match header {
        Some(h) => {
            mix(&mut hash, 1);
            mix(&mut hash, h.wave as u8 | (h.spaced as u8) << 1 | (h.watermark as u8) << 2);
            mix(&mut hash, h.mode as u8);
            for b in h.fps.to_le_bytes() {
                mix(&mut hash, b);
            }
            for colour in [h.primary, h.secondary] {
                for channel in colour {
                    mix(&mut hash, (channel.clamp(0.0, 1.0) * 255.0) as u8);
                }
            }
            for b in h.nickname.as_bytes() {
                mix(&mut hash, *b);
            }
            mix(&mut hash, 0xFF);
        }
        // "There is no header" is itself a state worth repainting on: the moment
        // a v2 block replaces a v1 one the watermark gains its segments.
        None => mix(&mut hash, 0),
    }
    // The fade is quantised into the signature so the ramp repaints, without
    // floating-point noise making every frame look different.
    mix(&mut hash, (fade.clamp(0.0, 1.0) * 64.0) as u8);
    hash
}

// ── entry points ────────────────────────────────────────────────────────────

/// The overlay's drawing surface: a memory DC with a top-down 32-bit DIB selected into
/// it, plus the size and the DIB's own pixel base.
///
/// Named and owned rather than three loose locals because A2 has to *replace* it: following
/// the game window can change the overlay's size at any moment (a fullscreen switch changes
/// it drastically), and a leaked DC/DIB per switch would be a slow resource leak in a
/// process that is meant to live as long as the game does.
struct Surface {
    dc: Hdc,
    dib: *mut c_void,
    /// What was selected into the DC before the DIB — a stock bitmap on a fresh DC. Must be
    /// restored before the DIB is deleted, and must never be deleted itself.
    previous: *mut c_void,
    /// The DIB's pixel base. The canvas is blitted here every painted frame.
    bits: *mut c_void,
    size: Size,
}

impl Surface {
    /// Create a `w`x`h` surface. `Err` carries the reason for the log.
    ///
    /// Negative height asks for a top-down DIB — row 0 is the top scanline, which is the order
    /// the canvas writes in — and everything left zero is "use the default", matching the
    /// window-class setup next door.
    unsafe fn new(w: i32, h: i32) -> Result<Surface, String> {
        let dc = CreateCompatibleDC(std::ptr::null_mut());
        if dc.is_null() {
            return Err("CreateCompatibleDC failed".to_string());
        }

        let mut info: BitmapInfo = std::mem::zeroed();
        info.header.size = std::mem::size_of::<BitmapInfoHeader>() as u32;
        info.header.width = w;
        info.header.height = -h;
        info.header.planes = 1;
        info.header.bit_count = 32;
        info.header.compression = BI_RGB;

        let mut bits: *mut c_void = std::ptr::null_mut();
        let dib = CreateDIBSection(dc, &info, DIB_RGB_COLORS, &mut bits, std::ptr::null_mut(), 0);
        if dib.is_null() || bits.is_null() {
            DeleteDC(dc);
            return Err("CreateDIBSection failed".to_string());
        }

        let previous = SelectObject(dc, dib);
        Ok(Surface { dc, dib, previous, bits, size: Size { cx: w, cy: h } })
    }

    /// The DIB's pixel stride in bytes — `w * 4`, asserted by the blit below.
    fn byte_len(&self) -> usize {
        self.size.cx as usize * self.size.cy as usize * std::mem::size_of::<u32>()
    }
}

impl Drop for Surface {
    fn drop(&mut self) {
        unsafe {
            // Reverse order: a DIB must not be deleted while it is still selected.
            SelectObject(self.dc, self.previous);
            DeleteObject(self.dib);
            DeleteDC(self.dc);
        }
    }
}

/// Create the overlay and run the frame loop until the game goes away or the
/// window is closed.
///
/// The log is opened first and closed last: it is the only record of a run that
/// draws nothing on screen, and every write through it is best-effort, so it can
/// never be the reason the overlay fails.
fn run(args: &Args) -> Result<(), String> {
    let mut log = log::Log::open();
    let state_path = state::default_path();

    let (mut rect, source) = match resolve_rect(args) {
        Ok(chosen) => chosen,
        Err(reason) => {
            log.line(&format!("start refused: {reason}"));
            return Err(reason);
        }
    };

    // Read once before the loop starts: the answer is part of the startup line,
    // and it is the difference between "the game has not published yet" and "the
    // file is there but not ours" — the checked form, because the refusal itself
    // (short, foreign, or which version) is the useful part of that line.
    let first_read = state::read_checked(&state_path);
    // The config as the first frame will actually draw it: `--mode` and the other
    // flags are the request, the header may fill in everything they did not name.
    // Logging the request alone would say "mode default" for a run that is about
    // to draw Tesseract because the game's combo says so.
    let effective = config_from(args, 1.0, first_read.as_ref().ok().and_then(|snap| snap.header.as_ref()));
    log.line(&format!(
        "start: rect {}x{} at ({}, {}) [{}], position {:?}, mode {} (scale {:.2}), \
         watermark {}, wave {}, state {} readable: {}",
        rect.w,
        rect.h,
        rect.x,
        rect.y,
        source.label(),
        args.position,
        effective.mode.label(),
        args.scale,
        effective.watermark,
        effective.wave,
        state_path.display(),
        match &first_read {
            Ok(snapshot) => describe_state(snapshot),
            Err(refusal) => format!("no ({})", refusal.describe()),
        }
    ));

    let mut text = match TextEngine::new(args.scale) {
        Ok(text) => text,
        Err(reason) => {
            log.line(&format!("exit: error: {reason}, 0 frames drawn"));
            return Err(reason);
        }
    };
    // Which faces the HUD will actually draw with, on every run. The two pairs
    // produce an equally plausible HUD, so "the embedded Play faces did not load"
    // is otherwise only visible by looking very closely at a screenshot.
    {
        let setup = text.setup();
        log.line(&format!(
            "faces: {} — name role {:?}, body role {:?} at {:.0}px em",
            setup.summary(),
            text.face_name(text::FontId::Name),
            text.face_name(text::FontId::Body),
            text.em_px(text::FontId::Body)
        ));
    }

    // A3: "the faces loaded" is not the same claim as "the faces can draw this text". A report of
    // text rendering as squares can only be settled by asking GDI which characters the selected
    // face actually has glyphs for, so the log answers it at startup instead of leaving it to a
    // screenshot. The sample is every character the HUD's own strings use, plus the published
    // nickname — the one string the overlay draws that it does not control, and the realistic way
    // a Latin-only embedded family meets a character it has no glyph for (a CJK player name).
    {
        const SAMPLE: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 %/.-:()[]";
        let total = SAMPLE.chars().count();
        let missing = text.missing_glyphs(text::FontId::Body, SAMPLE);
        log.line(&format!(
            "glyphs: body face draws {}/{} sample characters{}",
            total - missing.len(),
            total,
            if missing.is_empty() {
                " — no tofu source in this face".to_string()
            } else {
                format!(" — MISSING {missing:?}: these would draw as boxes")
            }
        ));

        let nickname = first_read
            .as_ref()
            .ok()
            .and_then(|snapshot| snapshot.header.as_ref())
            .map(|header| header.nickname.clone())
            .unwrap_or_default();
        if !nickname.is_empty() {
            let missing = text.missing_glyphs(text::FontId::Name, &nickname);
            log.line(&format!(
                "glyphs: watermark nickname {nickname:?} — {} character(s) the name face cannot draw",
                missing.len()
            ));
        }
    }

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
        //
        // The rect is taken verbatim, and deliberately NOT clamped to any monitor:
        // the caller (`--rect`, normally the game window's own `GetWindowRect`)
        // decides what the overlay covers. On a multi-monitor desktop, or with a
        // game that is not maximised, the game's rect is not inside the primary
        // monitor at all, and a clamp would put the HUD somewhere the game is not.
        //
        // `WS_EX_TOPMOST` is the style the overlay cannot be seen without: a plain
        // layered window lives in the desktop's normal band, so the focused game
        // covers it completely. The `SetWindowPos` below puts it at the top of the
        // always-on-top band as well as in the style word, and the frame loop
        // re-asserts it whenever it is lost (see `TOPMOST_CHECK_MS`).
        let ex_style = WS_EX_LAYERED
            | WS_EX_TOOLWINDOW
            | WS_EX_NOACTIVATE
            | WS_EX_TRANSPARENT
            | if args.topmost { WS_EX_TOPMOST } else { 0 };
        let title = wide("");
        let hwnd = CreateWindowExW(
            ex_style,
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

        // Raise into the always-on-top band. `SWP_NOACTIVATE` is what keeps the
        // game's foreground rights while this happens — taking focus is the one
        // thing that stops an exclusive-fullscreen game rendering, and `SWP_SHOWWINDOW`
        // makes the raise the show as well. The window is shown here rather than
        // only after the first frame, which is harmless: a layered window has no
        // surface until the first `UpdateLayeredWindow`, so there is nothing to
        // flash — the first pixels the desktop sees are still a finished HUD.
        let raised = if args.topmost {
            SetWindowPos(
                hwnd,
                HWND_TOPMOST,
                rect.x,
                rect.y,
                rect.w,
                rect.h,
                SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_SHOWWINDOW,
            ) != 0
        } else {
            false
        };
        let read_back = GetWindowLongW(hwnd, GWL_EXSTYLE) as u32;
        // The one line that answers "why is the overlay not on top of the game":
        // what was asked for, what Windows actually left in the style word, and
        // whether the window above it is now nothing at all.
        log.line(&format!(
            "topmost: {} — ex-style 0x{read_back:08X} [{}], SetWindowPos {}, {} window above",
            if args.topmost {
                if raised { "applied" } else { "FAILED (SetWindowPos returned 0)" }
            } else {
                "disabled (--no-topmost)"
            },
            describe_ex_style(read_back),
            if raised { "ok" } else { "not ok" },
            if GetWindow(hwnd, GW_HWNDPREV).is_null() { "no" } else { "a" }
        ));

        // A never-showing, never-focusing window is also never painted, so the
        // HUD is rendered into a DIB and pushed through UpdateLayeredWindow;
        // that is the whole reason WS_EX_LAYERED is here. The surface is owned by
        // `Surface`: A2 replaces it when following the game window changes our size,
        // and the Drop impl is the one place that releases it.
        let mut surface = match Surface::new(rect.w, rect.h) {
            Ok(surface) => surface,
            Err(reason) => {
                log.line(&format!("exit: error: {reason}, {frames} frames drawn"));
                return Err(reason);
            }
        };

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
        // The v2 header behind those entries: the mode, the switches, the two wave
        // colours and the watermark's nickname/FPS. Kept beside `entries` because
        // it is re-applied to the config every frame, not just at start.
        let mut header: Option<state::Header> =
            first_read.as_ref().ok().and_then(|snap| snap.header.clone());
        // Last write time of the state file — the game's own liveness signal.
        let mut last_write: Option<std::time::SystemTime> = None;
        let mut fade = 1.0f32;
        let mut last_signature = 0u64;
        let mut animating = true;
        let mut shown = false;
        let mut result: Result<(), String> = Ok(());
        // When the topmost check runs next, and how many times it has actually had
        // to re-raise. The count is logged (throttled) rather than carried into the
        // exit line, which keeps its existing shape.
        let mut next_topmost_check = started + Duration::from_millis(TOPMOST_CHECK_MS);
        let mut reasserts: u64 = 0;
        // A2: when the followed game window's rect is read next. The first poll is one
        // interval out, not immediate: `--rect` already carries the injector's own
        // fresh reading, so polling at t=0 would only repeat the same answer.
        let mut next_follow_check = started + Duration::from_millis(follow::POLL_MS);
        let mut follows: u64 = 0;

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

            // Keep the overlay above the game. Topmost is not a property Windows
            // guarantees: a fullscreen transition, a shell surface or another
            // always-on-top window can drop the overlay out of the band, and the
            // HUD then simply is not on screen. Two cheap reads decide whether the
            // raise is needed at all; a settled session raises nothing.
            if args.topmost && now >= next_topmost_check {
                next_topmost_check = now + Duration::from_millis(TOPMOST_CHECK_MS);
                let ex_style = GetWindowLongW(hwnd, GWL_EXSTYLE) as u32;
                let above = !GetWindow(hwnd, GW_HWNDPREV).is_null();
                if topmost_lost(ex_style, above) {
                    // A pure Z-order change: the rect is fixed at creation, so
                    // there is nothing to move and nothing to resize.
                    let ok = SetWindowPos(
                        hwnd,
                        HWND_TOPMOST,
                        0,
                        0,
                        0,
                        0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER,
                    ) != 0;
                    reasserts += 1;
                    // Throttled: a desktop that keeps something above the overlay
                    // would otherwise write a line every two seconds and grind the
                    // capped log down to nothing but re-asserts.
                    if reasserts <= 5 || reasserts % 50 == 0 {
                        let after = GetWindowLongW(hwnd, GWL_EXSTYLE) as u32;
                        log.line(&format!(
                            "topmost re-asserted (#{reasserts}): ex-style was 0x{ex_style:08X} \
                             [{}]{}; SetWindowPos {}; now 0x{after:08X} [{}]",
                            describe_ex_style(ex_style),
                            if above { ", a window above" } else { "" },
                            if ok { "ok" } else { "FAILED" },
                            describe_ex_style(after)
                        ));
                    }
                }
            }

            // A2: keep the overlay on the game window. The injector's `--rect` was read once at
            // launch, so a fullscreen switch, a resize or a move to another monitor leaves the
            // overlay on the old rect until this puts it back. The rect is taken verbatim (never
            // clamped to a monitor, same rule as `resolve_rect`), and `None` — the game briefly
            // minimised, or between two windows mid-switch — keeps the last good rect instead of
            // teleporting the HUD.
            if let Some(pid) = args.follow_pid {
                if now >= next_follow_check {
                    next_follow_check = now + Duration::from_millis(follow::POLL_MS);
                    if let Some(found) = follow::game_window_rect(pid) {
                        if found != rect {
                            // Resize first, and only move once the new surface exists: a failed
                            // resize keeps the old surface AND the old rect, because a wrongly
                            // sized overlay beats no overlay at all.
                            let mut ready = true;
                            if found.w != rect.w || found.h != rect.h {
                                match Surface::new(found.w, found.h) {
                                    Ok(fresh) => surface = fresh,
                                    Err(reason) => {
                                        ready = false;
                                        log.line(&format!(
                                            "follow: resize to {}x{} failed: {reason}",
                                            found.w, found.h
                                        ));
                                    }
                                }
                            }
                            if ready {
                                // One call moves and resizes. `SWP_NOACTIVATE` keeps the game's
                                // foreground rights; without `--topmost` the Z-order is left
                                // alone (`SWP_NOZORDER`) rather than silently promoted.
                                let (after, extra) = if args.topmost {
                                    (HWND_TOPMOST, 0u32)
                                } else {
                                    (std::ptr::null_mut(), SWP_NOZORDER)
                                };
                                let moved = SetWindowPos(
                                    hwnd,
                                    after,
                                    found.x,
                                    found.y,
                                    found.w,
                                    found.h,
                                    SWP_NOACTIVATE | SWP_NOOWNERZORDER | extra,
                                ) != 0;
                                let from = rect;
                                rect = found;
                                canvas = Canvas::placed(rect.x, rect.y, rect.w as u32, rect.h as u32);
                                // The signature only covers the state and the fade, so the
                                // repaint has to be forced by hand — otherwise a resize to the
                                // same content would leave the old pixels on screen.
                                animating = true;
                                follows += 1;
                                if follows <= 5 || follows % 20 == 0 {
                                    log.line(&format!(
                                        "follow (#{follows}): pid {pid} rect {}x{} at ({}, {}) → \
                                         {}x{} at ({}, {}), SetWindowPos {}",
                                        from.w,
                                        from.h,
                                        from.x,
                                        from.y,
                                        rect.w,
                                        rect.h,
                                        rect.x,
                                        rect.y,
                                        if moved { "ok" } else { "FAILED" }
                                    ));
                                }
                            }
                        }
                    }
                }
            }

            // D2: read the published block. A missing, short or foreign file
            // leaves the last good frame on screen — never garbage.
            if let Some(snapshot) = state::read(&state_path) {
                entries = snapshot.entries;
                header = snapshot.header;
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
            let current = signature(&entries, fade, header.as_ref());
            if current != last_signature || animating {
                // Rebuilt every frame the HUD is drawn: the header's switches are
                // the defaults and the command line overrides them, so a game that
                // changes its mode combo repaints without a restart. The fade is
                // the overlay's own and is applied on top either way.
                let config = config_from(args, fade, header.as_ref());
                // The HUD is redrawn from scratch: last frame's pixels must not
                // survive underneath the new ones.
                canvas.clear();
                animating = hud::paint(&mut canvas, &mut text, &entries, &mut anim, now, &config);
                last_signature = current;

                let pixels = canvas.pixels();
                // The canvas is exactly the surface: `Canvas::placed` was rebuilt from the same
                // rect `Surface::new` was given, and A2 resizes the two together. Asserted rather
                // than assumed — a mismatch here would be a buffer overrun, not a wrong colour.
                debug_assert_eq!(pixels.len() * std::mem::size_of::<u32>(), surface.byte_len());
                std::ptr::copy_nonoverlapping(
                    pixels.as_ptr() as *const u8,
                    surface.bits as *mut u8,
                    pixels.len() * std::mem::size_of::<u32>(),
                );
                // A null destination position keeps the coordinates
                // CreateWindowExW (and any later follow move) left the window at.
                // A failure is fatal, not a frame to skip: without it the window
                // would stay empty while the process kept looping, which is
                // precisely the silent "it started and I see nothing" this log
                // exists to rule out.
                if UpdateLayeredWindow(
                    hwnd,
                    std::ptr::null_mut(),
                    std::ptr::null(),
                    &surface.size,
                    surface.dc,
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

                // The topmost raise above already showed the window (its
                // `SWP_SHOWWINDOW`), and there are no pixels to flash either way:
                // a layered window has no surface until this call. The explicit
                // show stays for the `--no-topmost` path, which reaches here with
                // the window still hidden, and it is always the non-activating
                // form — `SW_SHOW` would take the foreground from the game.
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

        // The surface releases itself (Surface's Drop): the DC and the DIB have to go
        // in reverse order, and A2 may have replaced them several times along the way.
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

    // A published block wins over the demo table: `--dump` is how a state file is
    // reviewed, so a v2 block's nickname, FPS and header config have to reach the
    // render. With no file — or one this reader refuses — the demo mix stands in,
    // so a review dump still works on a machine with no game running.
    let state_path = state::default_path();
    let (entries, header, source_note) = match state::read_checked(&state_path) {
        Ok(snapshot) => {
            let note = format!("{} read from {}", describe_state(&snapshot), state_path.display());
            (snapshot.entries, snapshot.header, note)
        }
        Err(refusal) => (
            demo_entries(),
            None,
            format!("the built-in demo table ({} at {})", refusal.describe(), state_path.display()),
        ),
    };
    let mut anim = Anim::new();
    let now = Instant::now();
    // Every row settled and the accent clock at a known 2000 ms, so the frame's
    // colours are the accent functions at a fixed timestamp rather than whatever
    // the wall clock happened to say.
    anim.seed(&entries, now);

    let config = config_from(args, 1.0, header.as_ref());
    canvas.clear();
    hud::paint(&mut canvas, &mut text, &entries, &mut anim, now, &config);

    preview::write_bmp(Path::new(path), &canvas, BACKDROP)
        .map_err(|e| format!("cannot write {path}: {e}"))?;
    let metrics = hud::Metrics::new(config.mode, config.scale);
    println!(
        "wrote {path} ({w}x{h}, 32-bit BMP, mode {}, {:?} layout, composited over #{:06X}; {})",
        config.mode.label(),
        args.position,
        BACKDROP & 0x00FF_FFFF,
        source_note
    );
    // The rect the live window covers, which is *not* the review canvas: this is
    // the number that decides whether the HUD lands against the screen's edges.
    // Both elements' own origins are given in screen coordinates, so the anchoring
    // can be checked without running the windowed path.
    let name_font = config.mode.name_font();
    let (_, _, run_w) = hud::measure_watermark(&text, name_font, &config.nickname, config.fps);
    println!(
        "runtime window: {}x{} at ({}, {}) [{}] — the live layered window covers that rect, so the \
         watermark run ({}px) starts at ({}, {}) and the first row is flushed to the {} edge, \
         {}px inside it; topmost {}; the live layout is the same as this dump's, only measured \
         against that rect",
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
        if args.topmost { "on" } else { "off (--no-topmost)" },
    );
    // The measured anchoring, in the same screen coordinates the live window
    // would draw in: the watermark's origin, the x every row's box is flushed to
    // (`list_flush_x` — the same edge for every row, whatever its width, which is
    // what makes the wedge), and the y each block's first line sits on. Both
    // blocks hang off the rect's own top line, one per corner.
    println!(
        "runtime anchors: watermark \"{}\" at ({}, {}); module list flushed to the {} edge, so \
         every row's box ends at x = {} and the first row's top is y = {}; both blocks hang off \
         the rect's top line y = {} (watermark + {}, first row + {}); {} listed rows",
        hud::watermark_text(&config.nickname, config.fps),
        hud::watermark_x(runtime, args.position, &metrics, run_w),
        hud::watermark_y(runtime, &metrics),
        match args.position {
            Position::Right => "right",
            Position::Left => "left",
        },
        hud::list_flush_x(runtime, args.position, &metrics),
        hud::row_y(runtime, &metrics, 0),
        runtime.y,
        metrics.watermark_y,
        metrics.base_y,
        entries
            .iter()
            .filter(|e| e.enabled && !e.name.is_empty() && e.name != hud::SELF_MODULE)
            .count(),
    );
    // The numbers behind the picture: a dump that looks wrong is much easier to
    // fix when the face metrics are on screen next to it. The face names are the
    // ones GDI actually resolved, so "the embedded Play faces did not load" shows
    // up here as `Segoe UI Semibold` instead of `Play`.
    println!(
        "faces: {} — name ({}) {}px em / {}px cell, body ({}) {}px em / {}px cell | \
         {} published rows, {} listed | widest value {}px",
        text.setup().summary(),
        text.face_name(text::FontId::Name),
        text.em_px(text::FontId::Name).round(),
        text.cell_height(text::FontId::Name),
        text.face_name(text::FontId::Body),
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
             at scale {:.2}, topmost {})",
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
            if args.topmost { "on" } else { "off" },
        );
        return;
    }

    if let Err(reason) = run(&args) {
        eprintln!("error: {reason}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(line: &str) -> Result<Args, String> {
        let argv: Vec<String> = line.split_whitespace().map(str::to_string).collect();
        parse_args(&argv)
    }

    /// Topmost is on unless `--no-topmost` says otherwise. It is the default
    /// because without it the overlay is invisible over a focused game, and a
    /// flag that has to be remembered for the HUD to work is a flag that will be
    /// forgotten.
    #[test]
    fn topmost_is_on_by_default_and_off_with_the_escape_hatch() {
        assert!(parse("").unwrap().topmost);
        assert!(parse("--mode rise --scale 2").unwrap().topmost);
        assert!(!parse("--no-topmost").unwrap().topmost);
        // Order does not matter, and the flag takes no value.
        assert!(!parse("--no-topmost --mode rise").unwrap().topmost);
        assert!(!parse("--mode rise --no-topmost --scale 2").unwrap().topmost);
    }

    /// The rect is taken verbatim: `--rect` is what the injector hands the game
    /// window's own `GetWindowRect`, and on a multi-monitor desktop (or with the
    /// game not maximised) that rect is routinely *outside* the primary monitor,
    /// negative origins included. Clamping it to the monitor would put the HUD
    /// somewhere the game is not, so nothing here may clamp.
    #[test]
    fn the_rect_is_never_clamped_to_the_monitor() {
        // A game on a monitor to the left of the primary one.
        let (rect, source) = resolve_rect(&parse("--rect -1920 120 1600 900").unwrap()).unwrap();
        assert_eq!((rect.x, rect.y, rect.w, rect.h), (-1920, 120, 1600, 900));
        assert_eq!(source, RectSource::Override);

        // A windowed game in the middle of the screen: narrower and shorter than
        // the monitor, and it must stay that way.
        let (rect, _) = resolve_rect(&parse("--rect 300 200 1280 720").unwrap()).unwrap();
        assert_eq!((rect.x, rect.y, rect.w, rect.h), (300, 200, 1280, 720));

        // A rect taller than the monitor (a game spanning two stacked displays).
        let (rect, _) = resolve_rect(&parse("--rect 0 -1080 1920 2160").unwrap()).unwrap();
        assert_eq!((rect.w, rect.h), (1920, 2160));
    }

    /// The per-field adjustments still work on top of whatever rect was chosen,
    /// which is what lets the injector's rect be nudged without a second flag.
    #[test]
    fn per_field_adjustments_still_apply_to_an_override() {
        let (rect, _) =
            resolve_rect(&parse("--rect 300 200 1280 720 --x -10 --h 700").unwrap()).unwrap();
        assert_eq!((rect.x, rect.y, rect.w, rect.h), (-10, 200, 1280, 700));
    }

    /// A zero-sized rect is refused rather than quietly creating a window nobody
    /// can see — the exact mistake `--rect` exists to prevent.
    #[test]
    fn a_degenerate_rect_is_refused() {
        assert!(parse("--rect 0 0 0 720").is_err());
        assert!(parse("--rect 0 0 1280 0").is_err());
        assert!(resolve_rect(&parse("--rect 0 0 1280 720 --w 0").unwrap()).is_err());
    }

    /// A v2 header that says something different in every field the contract
    /// carries, so the merge below has something to override.
    fn header() -> state::Header {
        state::Header {
            version: state::VERSION,
            wave: false,
            spaced: true,
            watermark: false,
            mode: state::StateMode::Tesseract,
            primary: [0.25, 0.5, 0.75],
            secondary: [0.1, 0.2, 0.3],
            fps: 144,
            nickname: "gtier".to_string(),
        }
    }

    /// The header's `flags` bits 3..4 reach the painter's own four modes.
    #[test]
    fn the_headers_mode_bits_map_onto_the_four_ported_modes() {
        for (bits, want) in [
            (state::StateMode::Default, Mode::Default),
            (state::StateMode::Rise, Mode::Rise),
            (state::StateMode::Vape, Mode::Vape),
            (state::StateMode::Tesseract, Mode::Tesseract),
        ] {
            assert_eq!(mode_of(bits), want);
            assert_eq!(mode_of(bits).label(), bits.label());
        }
    }

    /// Contract v2's precedence rule, field by field: the header is the default,
    /// an explicit flag overrides it, and a field the header does not carry keeps
    /// the overlay's own default.
    #[test]
    fn the_state_header_is_the_default_and_a_flag_overrides_it() {
        let header = header();

        // Nothing on the command line: the header decides everything it carries.
        let bare = parse("").unwrap();
        assert!(!bare.mode_set && !bare.wave_set && !bare.watermark_set);
        assert!(!bare.spaced_set && !bare.primary_set && !bare.secondary_set);
        let cfg = config_from(&bare, 1.0, Some(&header));
        assert_eq!(cfg.mode, Mode::Tesseract);
        assert!(!cfg.wave, "the header's m_Wave");
        assert!(cfg.spaced, "the header's m_SpacedModules");
        assert!(!cfg.watermark, "the header's m_Watermark");
        assert_eq!(cfg.primary, header.primary);
        assert_eq!(cfg.secondary, header.secondary);
        assert_eq!(cfg.nickname, "gtier");
        assert_eq!(cfg.fps, 144);
        // The fields no header carries keep the caller's own values.
        assert_eq!(cfg.scale, 1.0);
        assert_eq!(cfg.wave_speed, hud::DEFAULT_WAVE_SPEED);
        assert_eq!(cfg.position, Position::Right);
        assert_eq!(cfg.alpha, 1.0);

        // A header that says the opposite of the CLI's own defaults, with no
        // flags at all: the header still wins.
        let flipped = state::Header {
            wave: true,
            spaced: false,
            watermark: true,
            mode: state::StateMode::Vape,
            ..header.clone()
        };
        let cfg = config_from(&bare, 1.0, Some(&flipped));
        assert_eq!(cfg.mode, Mode::Vape);
        assert!(cfg.wave && cfg.watermark);
        assert!(!cfg.spaced);

        // Every flag named: every flag wins.
        let explicit = parse(
            "--mode rise --no-wave --no-watermark --spaced --primary 1,2,3 --secondary 4,5,6",
        )
        .unwrap();
        assert!(explicit.mode_set && explicit.wave_set && explicit.watermark_set);
        assert!(explicit.spaced_set && explicit.primary_set && explicit.secondary_set);
        let cfg = config_from(&explicit, 0.5, Some(&flipped));
        assert_eq!(cfg.mode, Mode::Rise);
        assert!(!cfg.wave, "--no-wave beats a header with the wave on");
        assert!(!cfg.watermark, "--no-watermark beats a header with it on");
        assert!(cfg.spaced, "--spaced beats a header with it off");
        assert_eq!(cfg.primary, [1.0 / 255.0, 2.0 / 255.0, 3.0 / 255.0]);
        assert_eq!(cfg.secondary, [4.0 / 255.0, 5.0 / 255.0, 6.0 / 255.0]);
        assert_eq!(cfg.alpha, 0.5);
        // Nickname and FPS have no flag of their own: the header's run is drawn.
        assert_eq!(cfg.nickname, "gtier");
        assert_eq!(cfg.fps, 144);

        // No header at all — a v1 block, or no file — leaves every one of those at
        // the caller's value, and the watermark with no segments to draw.
        let cfg = config_from(&bare, 1.0, None);
        assert_eq!(cfg.mode, Mode::Default);
        assert!(cfg.wave && cfg.watermark && !cfg.spaced);
        assert_eq!(cfg.primary, hud::DEFAULT_PRIMARY);
        assert_eq!(cfg.secondary, hud::DEFAULT_SECONDARY);
        assert_eq!(cfg.nickname, "");
        assert_eq!(cfg.fps, 0);
        // ...and an explicit flag still wins over that.
        let cfg = config_from(&parse("--mode tesseract --no-wave").unwrap(), 1.0, None);
        assert_eq!(cfg.mode, Mode::Tesseract);
        assert!(!cfg.wave);
    }

    /// The repaint rule has to see the header: a state file that gains a nickname,
    /// an FPS, a mode or a colour is a different frame even when every module row
    /// is byte-for-byte the same.
    #[test]
    fn a_header_change_reaches_the_frame_signature() {
        let entries = demo_entries();
        let v2 = header();
        assert_ne!(
            signature(&entries, 1.0, None),
            signature(&entries, 1.0, Some(&v2)),
            "a v1 block becoming a v2 one repaints"
        );
        assert_eq!(
            signature(&entries, 1.0, Some(&v2)),
            signature(&entries, 1.0, Some(&header())),
            "the same header hashes the same"
        );

        for changed in [
            state::Header { nickname: "someone-else".to_string(), ..v2.clone() },
            state::Header { fps: 60, ..v2.clone() },
            state::Header { mode: state::StateMode::Rise, ..v2.clone() },
            state::Header { wave: true, ..v2.clone() },
            state::Header { spaced: false, ..v2.clone() },
            state::Header { watermark: true, ..v2.clone() },
            state::Header { primary: [1.0, 1.0, 1.0], ..v2.clone() },
            state::Header { secondary: [0.0, 0.0, 0.0], ..v2.clone() },
        ] {
            assert_ne!(
                signature(&entries, 1.0, Some(&v2)),
                signature(&entries, 1.0, Some(&changed)),
                "a header change must repaint: {changed:?}"
            );
        }
    }
}
