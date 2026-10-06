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
// entrance animation, the exits and the accent clock (D3/D4), rasterise the HUD
// into the canvas, push it through UpdateLayeredWindow. Frames are pushed when
// something actually changed: the signature covers the state and the fade, and
// `hud::paint` reports whether a row is sliding in or out or whether the mode's
// accent is a function of the clock. In Rise, VapeV4, Tesseract and Modern — and
// in Default with the wave on — the accent moves every frame, so those frames
// really do differ from the last; a waveless Default HUD is genuinely static and
// those frames are skipped.
//
// Where a run ends is the injector's pid when there is one (see `verdict`): the
// state file's mtime cannot tell a world load from a quit — 1.8 blocks its render
// thread while it generates terrain, so nothing is published for seconds at a
// time — and the game's own process can. A pid that no longer exists ends the run;
// a pid that does keeps the last frame on screen, stale file and all. With no pid
// the mtime rule stands, three seconds and a fade.
//
// Where the HUD can be seen is the same pid, asked a different question (A1). The
// overlay is on screen only while a window of the followed process is the
// foreground window; when the user alt-tabs to a browser the window is hidden
// (`SW_HIDE`) instead of floating over it, and it is shown again the moment the
// game is in front. The process deliberately keeps running while hidden: exiting
// would mean no HUD when the game comes back, and "hidden" here means "no window
// on screen", not "not running". Without `--follow-pid` there is no way to tell
// which window is the game, so that run keeps the old always-shown behaviour —
// said in the usage text, in the startup log and in `follow::should_show`.
//
// Zero third-party dependencies, same rule as the injector: kernel32/user32/gdi32
// through raw extern "system" FFI, declared in win.rs. The pixel work lives in
// draw.rs (pure), the text in text.rs (GDI), the HUD in hud.rs (pure geometry).
//
// The HUD itself is a port of OpenCommunity's in-game HUD (its `HUD.cpp`), all
// four of its modes plus this port's own Modern, selectable with `--mode`;
// `hud.rs` cites the source line of every constant it takes and the README says
// what could not be reproduced.

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
/// "The game is gone", for the run that has nothing better to go on: nothing has
/// written the state file for this long. Only the no-pid rule reads it (`verdict`)
/// — with `--follow-pid` the game process itself answers, so a world load that
/// blocks the render thread past this is not mistaken for an exit.
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

/// How often the followed pid is asked whether it is still running.
///
/// Slow on purpose: the answer only ever changes when the game exits, and a 60 Hz
/// loop has no business opening (and closing) a process handle every frame. Until
/// the first poll the process is assumed alive, which is the safe direction — a
/// wrong "gone" kills the HUD, a wrong "alive" costs one second of a ghost window.
const LIVENESS_POLL_MS: u64 = 1_000;

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
                       GetWindowThreadProcessId + GetClientRect + ClientToScreen)
                       about once a second and move/resize the overlay when it
                       changes. The **client** area, not the whole window: a HUD
                       anchored to GetWindowRect covers the game's title bar.
                       `--rect` is only read once at launch, so without this a
                       fullscreen switch, a window resize or a move to another
                       monitor leaves the overlay on the old rect. A
                       missing/minimised window changes nothing (the last good
                       rect is kept). The pid is also the liveness rule: while
                       that process exists the overlay stays up, whatever the
                       state file's mtime says — a world load publishes no frame
                       for seconds, and that is not an exit — and the run ends,
                       without a fade, when the pid is gone. It also decides WHERE
                       the HUD may be seen: while a window of that process is the
                       foreground window the overlay is shown, and when any other
                       application is in front the window is hidden (SW_HIDE) so
                       nothing floats over it — re-checked about once a second,
                       never per frame, and the process keeps running while hidden
                       so the HUD is back the moment the game is. A minimised game
                       counts as not in front. Without --follow-pid there is no way
                       to tell which window is the game, so the overlay is always
                       shown, the behaviour it had before this gate existed. NOTE:
                       under EXCLUSIVE fullscreen no external overlay can ever be
                       visible — the display belongs to the game; windowed or
                       borderless fullscreen is required.
  --x <i32>            adjust the chosen rect's x (default: the rect's own)
  --y <i32>            adjust the chosen rect's y
  --w <u32>            adjust the chosen rect's width
  --h <u32>            adjust the chosen rect's height
  --position <where>   right = the ported HUD's own placement: the watermark 10 px
                       in from the left edge and the module list flushed against
                       the right one (HUD.cpp:537, 611); left = the same two
                       elements on the opposite pair of edges. `stacked` is
                       accepted as a synonym of `right`. Default: the state
                       header's `positionLeft` bit (contract v3 flags bit 7, the
                       WebUI's Position option) when the block carries a header,
                       else right. Naming this flag overrides the header.
  --mode <name>        default | rise | vape | tesseract | modern. The four modes
                       of the ported HUD (ArrayList.h:14) plus this port's own
                       Modern, which the contract's version-3 mode field widened
                       to three bits for; each brings its own geometry, colours
                       and accent animation. `vapev4` is accepted for `vape`.
                       Default: the state header's mode, or `default` with no
                       header (see below).
  --scale <f>          multiply every metric and the font size, for a readable HUD
                       on a high-DPI screen (0.5 .. 4.0). Default: 1.0, which is
                       the source's own physical-pixel layout, or the state
                       header's `scale_x1000 / 1000` when the writer set one.
  --opacity <0..100>   the opacity of the HUD's *background* fills — the row
                       cards, the watermark panel and the work-pulse wash — as a
                       percentage. The text, the accent bars and the shadow are
                       never dimmed by it, so a translucent HUD stays readable.
                       Default: 100, or the state header's `opacity` when it is
                       not 0.
  --line-spacing <10..200>
                       the row gap as a percentage of the mode's own. Separate
                       from --scale on purpose: the scale resizes the text too, so
                       it cannot loosen a list that is merely too tight. 100 = the
                       reference's geometry, or the state header's `line_spacing`
                       when the writer set it.
  --ordered            draw the rows in the order the state file published them
                       instead of sorting them by measured width. The width sort
                       is the reference's wedge (HUD.cpp:340-342) and it discards
                       the user's own module order; contract v3's
                       `orderPublished` flag is the same switch for the game's
                       own config, and this flag beats it.
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
                       file's own rows and header (nickname, FPS, mode, scale,
                       opacity, row order and position included) when one is
                       readable, and the built-in demo table when there is none, so
                       it works with no game running.
  --hide               print one line, create no window, exit 0 (automation check)
  --help               print this text and exit 0

The overlay reads %TEMP%\\doppel-hud.state, published by the game 4096 bytes at a
time (magic 0x444F5050, version 3: a 64-byte header carrying the mode, the wave /
spaced / watermark switches, the published-order flag, the left/right position bit,
the two wave colours, the player nickname, the frame counter, the HUD scale and the
background opacity, then 64 entries of 56 bytes, whose `flash` byte is a 0..255
work-pulse strength). The version-2 and the original 3600-byte version-1 blocks are
still read; v2 is decoded with its narrower two-bit mode field and leaves the
scale, the opacity, the published-order flag and the position bit unset, and v1 has
none of those header fields at all, so every one of them falls back to the flag
defaults above. An explicit flag always wins over the header. The file is opened
read-only: there is no back channel and nothing this process does can reach the
game.

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

With `--follow-pid` the overlay is also only on screen while the game is the
window in front: alt-tab to a browser and the window is hidden (SW_HIDE) rather
than floating over it, and it comes back with the game. Hidden means no window on
screen, not \"not running\" — the process and its frame loop keep going, because
exiting would mean no HUD when the game returns. Without a pid there is nothing to
identify the game window by, so the overlay stays visible over everything, which
is also why the injector always passes one.

The process exits by itself when the game does. With `--follow-pid` the game
process itself is the signal — while that pid exists the overlay stays up even
though the state file has gone quiet (a world load blocks the game's render
thread for seconds), and the run ends when the pid is gone. Without a pid there
is nothing better to go on, so the old rule stands: three seconds without the
state file being written, then a short fade. Stop it early from Task Manager,
from `taskkill /IM doppel-hud.exe` (without /F, which posts WM_CLOSE), or with
Ctrl+C in this console.";

// ── command line ────────────────────────────────────────────────────────────
//
// Hand-rolled on purpose: a handful of flags does not justify a dependency, and
// the injector already parses its own arguments.

struct Args {
    /// `--rect x y w h`: the rect the window covers. `None` = the primary monitor.
    rect: Option<Rect>,
    /// `--follow-pid <pid>`: re-read the game window's rect every second and re-place
    /// the overlay when it changes. `None` keeps the overlay on the rect it was
    /// created with, which is what the injector's one-shot `--rect` gives it. It is
    /// also the overlay's liveness signal: while that process exists the run stays
    /// alive whatever the state file says (see `verdict`).
    follow_pid: Option<u32>,
    /// Per-field adjustments applied on top of whichever rect was chosen. `None`
    /// keeps the rect's own value, which is what makes the monitor the default
    /// rather than a silent 520x400 shrink.
    x: Option<i32>,
    y: Option<i32>,
    w: Option<u32>,
    h: Option<u32>,
    /// `--position`: which pair of edges the HUD hangs off. Contract v3's
    /// `positionLeft` bit (flags bit 7) carries the WebUI's own Position option, so
    /// this is the fallback until `--position` is actually named (see
    /// `position_set`).
    position: Position,
    /// `--mode`: which of the ported HUD's modes is drawn, plus this port's own
    /// Modern. The state header carries the game's own combo, so this is the
    /// fallback until `--mode` is actually named (see `mode_set`).
    mode: Mode,
    /// `--scale`: the multiplier on every metric and on the font size. The state
    /// header carries its own `scale_x1000` from contract v3, so this is the
    /// fallback until `--scale` is actually named (see `scale_set`).
    scale: f32,
    /// `--opacity`, in 0..1. The flag takes a percentage, the same unit the
    /// header's `opacity` word uses; this field is the resolved 0..1 the painter
    /// wants. Only the *background* fills are scaled by it (hud::Config).
    opacity: f32,
    /// `--line-spacing`, as a multiple of the mode's own row gap (0.1..2.0). The
    /// flag takes a percentage, the same unit the header's `line_spacing` word uses.
    line_spacing: f32,
    /// `--ordered`: draw the rows in published order instead of width-sorted.
    /// The header's `orderPublished` bit says the same thing for a run with no
    /// flag; this is how a `--dump` can review either order.
    ordered: bool,
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
    // Which of the fields above the caller actually named. Contract v3 puts the
    // same switches, colours, scale and opacity in the state header, and the rule
    // between the two sources is "the header is the default, an explicit flag
    // overrides it" — so whether a flag was given has to be remembered rather
    // than inferred from its value (`--spaced` off and "no --spaced" are
    // different states, and so is "scale 1.0" and "no --scale").
    mode_set: bool,
    scale_set: bool,
    opacity_set: bool,
    line_spacing_set: bool,
    ordered_set: bool,
    watermark_set: bool,
    wave_set: bool,
    primary_set: bool,
    secondary_set: bool,
    spaced_set: bool,
    position_set: bool,
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
            opacity: 1.0,
            line_spacing: 1.0,
            ordered: false,
            watermark: true,
            wave: true,
            wave_speed: hud::DEFAULT_WAVE_SPEED,
            primary: hud::DEFAULT_PRIMARY,
            secondary: hud::DEFAULT_SECONDARY,
            spaced: false,
            mode_set: false,
            scale_set: false,
            opacity_set: false,
            line_spacing_set: false,
            ordered_set: false,
            watermark_set: false,
            wave_set: false,
            primary_set: false,
            secondary_set: false,
            spaced_set: false,
            position_set: false,
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
                args.position_set = true;
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
                args.scale_set = true;
            }
            "--opacity" => {
                // A percentage on the command line, the unit the state header's
                // own `opacity` word is written in, so the two are comparable
                // without the reader doing arithmetic in their head.
                let raw = next_value(argv, &mut i, flag)?;
                let percent: f32 = parse_num(flag, &raw)?;
                if !percent.is_finite() || !(0.0..=100.0).contains(&percent) {
                    return Err(format!("--opacity: expected 0 .. 100 (percent), got {raw}"));
                }
                args.opacity = percent / 100.0;
                args.opacity_set = true;
            }
            "--line-spacing" => {
                // Percentage again, for the same reason: the header's `line_spacing`
                // word is a percent, and this flag is the manual override of it.
                let raw = next_value(argv, &mut i, flag)?;
                let percent: f32 = parse_num(flag, &raw)?;
                if !percent.is_finite() || !(10.0..=200.0).contains(&percent) {
                    return Err(format!("--line-spacing: expected 10 .. 200 (percent), got {raw}"));
                }
                args.line_spacing = percent / 100.0;
                args.line_spacing_set = true;
            }
            "--ordered" => {
                args.ordered = true;
                args.ordered_set = true;
                i += 1;
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
/// the state header and the live fade layered on top. Both the frame loop and
/// `--dump` build their config here, so a dump is exactly what the window would
/// draw.
///
/// Precedence, field by field: an explicit command-line flag wins over the state
/// header, which wins over the overlay's own default. The header is the game's
/// own config — the mode combo, `m_Wave` / `m_SpacedModules` / `m_Watermark`, the
/// two wave colours, and contract v3's HUD scale, background opacity, published
/// order and left/right position — and it also carries the watermark's nickname and
/// frame counter, which no flag supplies. A v1 block (or no file at all) has no
/// header, so every one of those keeps the value the caller asked for; that is what
/// keeps the overlay runnable and reviewable with no game attached.
fn config_from(args: &Args, alpha: f32, header: Option<&state::Header>) -> Config {
    Config {
        // `positionLeft` is a switch, not a word with an "unset", so a header always
        // has an opinion: with no flag the bit decides (clear = the reference's
        // right-hand placement, which is also the default), and `--position` beats
        // it in both directions.
        position: if args.position_set {
            args.position
        } else {
            header.map_or(args.position, position_of)
        },
        alpha,
        mode: if args.mode_set { args.mode } else { header.map_or(args.mode, |h| mode_of(h.mode)) },
        // `scale_x1000` and `opacity` are 0 on the wire when the writer has no
        // opinion, which the reader turns into `None` — so "the header did not
        // say" and "the header said the default" cannot be confused.
        scale: if args.scale_set {
            args.scale
        } else {
            header.and_then(|h| h.scale).unwrap_or(args.scale)
        },
        opacity: if args.opacity_set {
            args.opacity
        } else {
            header.and_then(|h| h.opacity).unwrap_or(args.opacity)
        },
        line_spacing: if args.line_spacing_set {
            args.line_spacing
        } else {
            header.and_then(|h| h.line_spacing).unwrap_or(args.line_spacing)
        },
        ordered: if args.ordered_set {
            args.ordered
        } else {
            header.map_or(args.ordered, |h| h.order_published)
        },
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

/// The header's mode bits → the mode the painter knows. The two vocabularies
/// exist because `state` describes the published file and `hud` describes the
/// ported HUD; this is the one place that has to know both.
fn mode_of(bits: state::StateMode) -> Mode {
    match bits {
        state::StateMode::Default => Mode::Default,
        state::StateMode::Rise => Mode::Rise,
        state::StateMode::Vape => Mode::Vape,
        state::StateMode::Tesseract => Mode::Tesseract,
        state::StateMode::Modern => Mode::Modern,
    }
}

/// The header's `positionLeft` bit → which pair of edges the HUD hangs off. Bit 7
/// is a switch with no "unset", so unlike `scale`/`opacity` there is nothing to
/// fall through to: clear means the reference's own right-hand placement. A v2
/// block can only be clear, because the bit is still reserved there (`state`'s
/// `V2_DEFINED_MASK`).
fn position_of(header: &state::Header) -> Position {
    if header.position_left {
        Position::Left
    } else {
        Position::Right
    }
}

/// Which source decided the drawn position, for the startup line: `--position`
/// when it was named, the header's `positionLeft` bit when the block carried a
/// header, and the overlay's own default (`right`) otherwise. `describe_state`
/// prints what the header asked for and the line prints what is drawn; this is the
/// word that says which of them won.
fn position_source(args: &Args, header: Option<&state::Header>) -> &'static str {
    if args.position_set {
        "--position"
    } else if header.is_some() {
        "header"
    } else {
        "default"
    }
}

/// What the startup line says about the first read: the block's version, its
/// entry count and — for a header-carrying block — the config the header asked
/// for. "The overlay is drawing the wrong mode" and "the overlay never read my
/// header" are otherwise indistinguishable from a screenshot, and the same goes
/// for a scale, an opacity, a row order or a side that was silently ignored.
fn describe_state(snapshot: &state::Snapshot) -> String {
    match &snapshot.header {
        Some(h) => format!(
            "v{} block, {} entries; header mode {} wave {} spaced {} watermark {} order {} \
             position {} scale {} opacity {} line spacing {}, nick {:?}, {} fps",
            h.version,
            snapshot.entries.len(),
            h.mode.label(),
            h.wave,
            h.spaced,
            h.watermark,
            h.order_published,
            if h.position_left { "left" } else { "right" },
            match h.scale {
                Some(scale) => format!("{scale:.2}"),
                None => "unset".to_string(),
            },
            match h.opacity {
                Some(opacity) => format!("{:.0}%", opacity * 100.0),
                None => "unset".to_string(),
            },
            match h.line_spacing {
                Some(spacing) => format!("{:.0}%", spacing * 100.0),
                None => "unset".to_string(),
            },
            h.nickname,
            h.fps
        ),
        None => format!(
            "v{} block, {} entries (no header)",
            state::V1_VERSION,
            snapshot.entries.len()
        ),
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
/// `flash` byte, now a 0..255 work-pulse strength: 255 is a module working right
/// now).
fn demo_entries() -> Vec<Entry> {
    const DEMO: &[(&str, &str, u8)] = &[
        ("AutoClicker", "12.3 cps", 255),
        ("Velocity", "82%", 0),
        ("Keystrokes", "", 0),
        ("Reach", "", 0),
        ("HitSelect", "", 0),
        ("BlockHit", "", 0),
        ("WTap", "", 0),
        ("Sprint", "", 0),
        ("Safewalk", "", 0),
        ("Timer", "", 0),
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
/// holds the right pixels and the frame is skipped. The header is part of it: a
/// nickname, frame counter, mode, colour, scale, opacity, row order or side that
/// changes in the state file has to repaint even though every entry is identical.
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
        // The strength itself, not just "working": a pulse that decays from 255
        // to 40 between two publishes is a different frame.
        mix(&mut hash, e.flash);
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
            mix(
                &mut hash,
                h.wave as u8
                    | (h.spaced as u8) << 1
                    | (h.watermark as u8) << 2
                    | (h.order_published as u8) << 3
                    | (h.position_left as u8) << 4,
            );
            mix(&mut hash, h.mode as u8);
            for b in h.fps.to_le_bytes() {
                mix(&mut hash, b);
            }
            // The two v3 words are part of the frame: a header that turns the
            // background down has to repaint without any entry changing.
            let scale = h.scale.unwrap_or(1.0);
            for b in ((scale.clamp(0.0, 10.0) * 100.0) as u16).to_le_bytes() {
                mix(&mut hash, b);
            }
            let opacity = h.opacity.unwrap_or(1.0);
            mix(&mut hash, (opacity.clamp(0.0, 1.0) * 255.0) as u8);
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
        // a header-carrying block replaces a v1 one the watermark gains its
        // segments and the new knobs fall back to the flags.
        None => mix(&mut hash, 0),
    }
    // The fade is quantised into the signature so the ramp repaints, without
    // floating-point noise making every frame look different.
    mix(&mut hash, (fade.clamp(0.0, 1.0) * 64.0) as u8);
    hash
}

// ── game liveness ───────────────────────────────────────────────────────────

/// What the liveness rule decides for one frame.
///
/// Reached only through `verdict` below, which is pure and takes plain values: the
/// rule — with a pid the process is the authority and the state file's mtime is
/// ignored, without one the mtime is all there is — is then checked without a
/// game, a process or a state file. A world load cannot be staged in a test; this
/// is what stands in for one.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Verdict {
    /// The game is there as far as anything can tell: keep presenting the last
    /// frame and let the next publish repaint it.
    Alive,
    /// The followed pid no longer exists. The pid is the authority whenever there
    /// is one, so this ends the run whatever the state file says.
    Gone,
    /// No pid to ask, and nothing has written the state file since `STALE_SECS`:
    /// the old rule — fade over `FADE_SECS`, then exit.
    Stale,
}

/// The rule, from the three plain facts the frame loop has: whether the injector
/// gave a pid, whether that pid is still alive, and whether the state file has
/// gone stale (`STALE_SECS` against its own mtime).
///
/// The pid wins outright, and that is the whole fix. Minecraft 1.8 blocks its
/// render thread for several seconds while it generates terrain, so no frame is
/// published and the file goes stale during a *world load*; reading that as "the
/// game is gone" is what killed the overlay mid-load. A process that still exists
/// is a game still running, so `stale` is deliberately not consulted when there is
/// a pid: the last frame stays on screen, at full alpha, until the game publishes
/// again.
fn verdict(has_pid: bool, pid_alive: bool, stale: bool) -> Verdict {
    if has_pid {
        // `false` here really is "gone": `win::process_alive` reports a process it
        // cannot open at all (access denied) as alive, so a permissions quirk
        // never reaches this branch.
        if pid_alive { Verdict::Alive } else { Verdict::Gone }
    } else if stale {
        Verdict::Stale
    } else {
        Verdict::Alive
    }
}

/// Why a pid run ended, in the one shape the exit line logs. Split out from the
/// loop so the wording a user reads back — "the HUD exited while the game was
/// loading" has to be distinguishable from "the game exited" — is pinned by a test.
fn gone_reason(pid: u32) -> String {
    format!("game process {pid} exited")
}

/// Which liveness rule this run is under, for the startup line. The exit line says
/// *that* a run ended; without this the log cannot say which rule ended it, and
/// "state stale" means nothing until you know whether a pid was ever consulted.
fn liveness_rule(pid: Option<u32>) -> String {
    match pid {
        Some(pid) => format!(
            "pid {pid} (the game process, polled every {LIVENESS_POLL_MS} ms; a stale state \
             file does not end the run)"
        ),
        None => format!(
            "state file mtime (no --follow-pid: stale after {STALE_SECS:.0}s, then a \
             {FADE_SECS:.2}s fade)"
        ),
    }
}

/// Which visibility rule this run is under, for the startup line.
///
/// The exit line says *that* the overlay stopped drawing, and "the HUD was not on
/// screen" cannot be read back from the log without knowing whether the overlay
/// ever meant to hide itself. It mirrors `liveness_rule`: the two are decided by
/// the same `--follow-pid`, and a run with none is the one that behaves as it did
/// before the gate existed.
fn visibility_rule(pid: Option<u32>) -> String {
    match pid {
        Some(pid) => format!(
            "pid-gated (shown only while a window of pid {pid} is the foreground window and not \
             minimised, re-checked every {POLL_MS} ms; hidden — not exited — otherwise)",
            POLL_MS = follow::POLL_MS
        ),
        None => "always shown (no --follow-pid: without a pid there is no way to tell which \
                 window is the game)"
            .to_string(),
    }
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
    // The header the first frame will be built from, named once because both the
    // effective config and the position source below read it.
    let first_header = first_read.as_ref().ok().and_then(|snap| snap.header.as_ref());
    // The config as the first frame will actually draw it: `--mode` and the other
    // flags are the request, the header may fill in everything they did not name.
    // Logging the request alone would say "mode default" for a run that is about
    // to draw Tesseract because the game's combo says so.
    let effective = config_from(args, 1.0, first_header);
    // The startup line also names the liveness rule this run is under: the exit line
    // says *that* it stopped, and "state stale" cannot be read back without knowing
    // whether a pid was ever consulted. The visibility rule is named for the mirror
    // reason: "I did not see the HUD" is otherwise indistinguishable from "the HUD
    // was hidden because the game was not in front", and the two need opposite
    // diagnoses.
    log.line(&format!(
        "start: rect {}x{} at ({}, {}) [{}], position {:?} ({}), mode {} (scale {:.2}), \
         opacity {:.0}%, rows {}, watermark {}, wave {}, state {} readable: {}, liveness {}, \
         visibility {}",
        rect.w,
        rect.h,
        rect.x,
        rect.y,
        source.label(),
        effective.position,
        position_source(args, first_header),
        effective.mode.label(),
        effective.scale,
        effective.opacity * 100.0,
        if effective.ordered { "published" } else { "width-sorted" },
        effective.watermark,
        effective.wave,
        state_path.display(),
        match &first_read {
            Ok(snapshot) => describe_state(snapshot),
            Err(refusal) => format!("no ({})", refusal.describe()),
        },
        liveness_rule(args.follow_pid),
        visibility_rule(args.follow_pid)
    ));

    // The text engine takes the *effective* scale, not `--scale`: contract v3's
    // header can set the scale on its own, and a HUD whose fonts were not scaled
    // with its boxes would have the glyphs spill out of every card.
    let mut text = match TextEngine::new(effective.scale) {
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
        // The state header behind those entries: the mode, the switches, the two wave
        // colours and the watermark's nickname/FPS. Kept beside `entries` because
        // it is re-applied to the config every frame, not just at start.
        let mut header: Option<state::Header> =
            first_read.as_ref().ok().and_then(|snap| snap.header.clone());
        // Last write time of the state file. Only the no-pid run reads it as a
        // liveness signal (`verdict`): with `--follow-pid` the game's own process is
        // the authority, and a file that stops being written is a paused game.
        let mut last_write: Option<std::time::SystemTime> = None;
        let mut fade = 1.0f32;
        let mut last_signature = 0u64;
        let mut animating = true;
        let mut shown = false;
        // A1: whether the visibility gate currently believes a window is on screen.
        // Seeded `true` — "the overlay means to be visible" — because that is the
        // state every run starts in: the topmost path has already shown the window
        // with `SWP_SHOWWINDOW`, the `--no-topmost` path shows it on its first frame
        // (the `shown` flag below), and a run with no pid never hides itself at all.
        // Only the gate clears it, and only with a `SW_HIDE` to match.
        let mut visible = true;
        let mut result: Result<(), String> = Ok(());
        // When the topmost check runs next, and how many times it has actually had
        // to re-raise. The count is logged (throttled) rather than carried into the
        // exit line, which keeps its existing shape.
        let mut next_topmost_check = started + Duration::from_millis(TOPMOST_CHECK_MS);
        let mut reasserts: u64 = 0;
        // The injector's pid, read once. It is both the follow target for the rect
        // (A2) and, now, the authority on whether the game is still running.
        let follow_pid = args.follow_pid;
        // Whether the followed process was alive at the last poll. Starts `true`
        // because the first poll is one interval out and assuming a live game is the
        // safe direction; only an explicit "no such process" ever clears it, so a
        // process we are not allowed to open (access denied) can never end the run.
        let mut pid_alive = true;
        let mut next_liveness_check = started + Duration::from_millis(LIVENESS_POLL_MS);
        // A2: when the followed game window's rect is read next. The first poll is one
        // interval out, not immediate: `--rect` already carries the injector's own
        // fresh reading, so polling at t=0 would only repeat the same answer.
        let mut next_follow_check = started + Duration::from_millis(follow::POLL_MS);
        let mut follows: u64 = 0;
        // A1: when the visibility gate re-decides, and how many times it has changed
        // its mind. The first check is immediate, unlike the follow poll's: `--rect`
        // is a fresh reading of where the game is, but nothing the injector passed
        // says who is in front *now*, and the first second of a run over a browser is
        // exactly the frame the user complained about. Both Win32 reads are cheap
        // enough that a gate at t=0 costs nothing.
        let mut next_visibility_check = started;
        let mut visibility_changes: u64 = 0;

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
            if let Some(pid) = follow_pid {
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

            // A1: only be on screen while the game is the window in front. The overlay
            // used to be an always-on-top layered window over *every* application, so
            // alt-tabbing to a browser left the HUD glued on top of it — useless, and
            // the opposite of stealthy. `--follow-pid` is what makes the game
            // identifiable, so this gate only exists for a run that has one; with no
            // pid the overlay cannot tell which window is the game and stays shown
            // (`follow::should_show`, which is where the rule and its reasons live).
            //
            // The same slow cadence as the follow poll, never every frame: the answer
            // only changes when the user switches windows, and a `ShowWindow` per frame
            // on a heuristic that flickers would be worse than the bug.
            //
            // Hidden means *no window on screen*, not "not running": the process, the
            // frame loop, the liveness poll and the follow poll all keep going, because
            // exiting would mean no HUD when the game comes back.
            if let Some(pid) = follow_pid {
                if now >= next_visibility_check {
                    next_visibility_check = now + Duration::from_millis(follow::POLL_MS);
                    let fg = follow::foreground_state(pid);
                    let want = follow::should_show(true, fg.game, fg.minimised);
                    if want != visible {
                        // `SW_SHOWNOACTIVATE` / `SW_HIDE`, never `SW_SHOW`: taking the
                        // foreground from the game is the one thing this process must
                        // never do (an exclusive-fullscreen game stops rendering).
                        ShowWindow(hwnd, if want { SW_SHOWNOACTIVATE } else { SW_HIDE });
                        visible = want;
                        if want {
                            // Shown again: the pixels the compositor kept are from
                            // before the hide, and the state may have moved on since,
                            // so the reveal is always followed by one repaint.
                            animating = true;
                            // This call *was* the show, so the first-frame path below
                            // has nothing left to do.
                            shown = true;
                        }
                        visibility_changes += 1;
                        // Throttled like the topmost re-assert: someone alt-tabbing
                        // all evening would otherwise fill the capped log with these.
                        if visibility_changes <= 5 || visibility_changes % 20 == 0 {
                            log.line(&format!(
                                "visibility (#{visibility_changes}): {} — pid {pid}, \
                                 game in front {}, minimised {}",
                                if want {
                                    "shown (game in front)"
                                } else {
                                    "hidden (another window is in front)"
                                },
                                fg.game,
                                fg.minimised
                            ));
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

            // Who says the game is gone. With `--follow-pid` it is the process itself,
            // polled slowly (LIVENESS_POLL_MS) rather than every frame: the answer only
            // changes when the game exits, and this loop has no business opening a
            // process handle 60 times a second.
            if let Some(pid) = follow_pid {
                if now >= next_liveness_check {
                    next_liveness_check = now + Duration::from_millis(LIVENESS_POLL_MS);
                    pid_alive = win::process_alive(pid);
                }
            }

            // The old signal, now consulted only by the run that has nothing better.
            // Before the FIRST write the overlay is still waiting for a game that may be
            // loading, so it gets a much longer grace instead (see
            // FIRST_WRITE_GRACE_SECS); after one, `STALE_SECS` of silence is the rule.
            let stale = match last_write {
                Some(written) => written.elapsed().map(|d| d.as_secs_f32() > STALE_SECS).unwrap_or(false),
                None => started.elapsed().as_secs_f32() > FIRST_WRITE_GRACE_SECS,
            };

            match (verdict(follow_pid.is_some(), pid_alive, stale), follow_pid) {
                // The process the injector pointed at is gone. No fade and no grace: a
                // process that has exited is not a process that is slow, and its window
                // went with it.
                (Verdict::Gone, Some(pid)) => {
                    exit_reason = gone_reason(pid);
                    break;
                }
                // The no-pid rule, unchanged: fade out, then exit. Fading beats leaving
                // a ghost HUD on the desktop.
                (Verdict::Stale, _) => {
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
                // The game is there — a stale file with a live pid included: keep the
                // last frame on screen, at full alpha, until the game publishes again.
                // (`Gone` without a pid cannot happen: `verdict` answers it only for a
                // run that has one.)
                _ => {}
            }

            // The slide state, the frame delta and the accent clock all advance
            // here (the delta even on a frame that is not repainted, so an idle
            // stretch cannot turn into a catch-up jump), and `hud::paint` reports
            // whether the frame it composed is still moving.
            anim.tick(now);
            let current = signature(&entries, fade, header.as_ref());
            // A1: while the window is hidden there is nothing to composite — and the
            // grid of pixels to draw into is the expensive part of a frame, so a run
            // that spends the evening behind a browser spends it idle. The reveal
            // (the gate above) sets `animating`, so the first visible frame is always
            // repainted from scratch and no stale pixels can survive the hide.
            if visible && (current != last_signature || animating) {
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
                // the window still hidden — and for the no-pid run, which has no
                // gate to show it. It is always the non-activating form —
                // `SW_SHOW` would take the foreground from the game.
                if !shown {
                    ShowWindow(hwnd, SW_SHOWNOACTIVATE);
                    shown = true;
                    visible = true;
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
/// every mode can be reviewed the same way.
///
/// `runtime` is the rect the *live* window would cover (the monitor, or `--rect`),
/// which is printed but not drawn on: the review canvas stays the fixed 520x400
/// every earlier dump was compared at, and the printed coordinates are therefore
/// canvas-local. The runtime line is what says where those pixels would land on
/// this machine's screen.
fn run_dump(path: &str, args: &Args, runtime: Rect, source: RectSource) -> Result<(), String> {
    let (w, h) = (DEF_W, DEF_H);
    let mut canvas = Canvas::new(w, h);

    // A published block wins over the demo table: `--dump` is how a state file is
    // reviewed, so a block's nickname, FPS and header config have to reach the
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

    // The config first, because it carries the *effective* scale: the header can
    // set the scale through `scale_x1000`, and the faces have to be created at
    // that size or the dump would not be the frame the overlay would draw.
    let config = config_from(args, 1.0, header.as_ref());
    let mut text = TextEngine::new(config.scale)?;

    let mut anim = Anim::new();
    let now = Instant::now();
    // Every row settled and the accent clock at a known 2000 ms, so the frame's
    // colours are the accent functions at a fixed timestamp rather than whatever
    // the wall clock happened to say. The exits are left empty: a dump has no
    // previous frame to have lost a row from.
    anim.seed(&entries, now);

    canvas.clear();
    hud::paint(&mut canvas, &mut text, &entries, &mut anim, now, &config);

    preview::write_bmp(Path::new(path), &canvas, BACKDROP)
        .map_err(|e| format!("cannot write {path}: {e}"))?;
    let metrics = hud::Metrics::with_line_spacing(config.mode, config.scale, config.line_spacing);
    println!(
        "wrote {path} ({w}x{h}, 32-bit BMP, mode {}, {:?} layout, scale {:.2}, background opacity \
         {:.0}%, rows {}, composited over #{:06X}; {})",
        config.mode.label(),
        config.position,
        config.scale,
        config.opacity * 100.0,
        if config.ordered { "in published order" } else { "width-sorted" },
        BACKDROP & 0x00FF_FFFF,
        source_note
    );
    // The rect the live window covers, which is *not* the review canvas: this is
    // the number that decides whether the HUD lands against the screen's edges.
    // Both elements' own origins are given in screen coordinates, so the anchoring
    // can be checked without running the windowed path. The position is the
    // *effective* one (`config.position`: `--position` or the header's bit), so the
    // printed anchors are the ones the dump above actually drew with.
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
        hud::watermark_x(runtime, config.position, &metrics, run_w),
        hud::watermark_y(runtime, &metrics),
        match config.position {
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
        hud::watermark_x(runtime, config.position, &metrics, run_w),
        hud::watermark_y(runtime, &metrics),
        match config.position {
            Position::Right => "right",
            Position::Left => "left",
        },
        hud::list_flush_x(runtime, config.position, &metrics),
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
    // A3: the dump is how the overlay is reviewed with no game running, so the glyph-coverage
    // answer belongs here too. `--dump` draws the state file's own rows and nickname, so it can
    // catch a character the embedded face cannot draw even when nothing is injected.
    {
        const SAMPLE: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 %/.-:()[]";
        let total = SAMPLE.chars().count();
        let missing = text.missing_glyphs(text::FontId::Body, SAMPLE);
        println!(
            "glyphs: body face draws {}/{} sample characters{}",
            total - missing.len(),
            total,
            if missing.is_empty() {
                " — no tofu source in this face".to_string()
            } else {
                format!(" — MISSING {missing:?}: these would draw as boxes")
            }
        );
        if !config.nickname.is_empty() {
            let missing = text.missing_glyphs(text::FontId::Name, &config.nickname);
            println!(
                "glyphs: nickname {:?} — {} character(s) the name face cannot draw",
                config.nickname,
                missing.len()
            );
        }
    }
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

    /// A header that says something different in every field the contract
    /// carries, so the merge below has something to override.
    fn header() -> state::Header {
        state::Header {
            version: state::VERSION,
            wave: false,
            spaced: true,
            watermark: false,
            mode: state::StateMode::Tesseract,
            order_published: true,
            position_left: true,
            primary: [0.25, 0.5, 0.75],
            secondary: [0.1, 0.2, 0.3],
            fps: 144,
            scale: Some(1.25),
            opacity: Some(0.5),
            line_spacing: Some(1.5),
            nickname: "gtier".to_string(),
        }
    }

    /// The header's mode field reaches the painter's own five modes.
    #[test]
    fn the_headers_mode_bits_map_onto_the_ported_modes() {
        for (bits, want) in [
            (state::StateMode::Default, Mode::Default),
            (state::StateMode::Rise, Mode::Rise),
            (state::StateMode::Vape, Mode::Vape),
            (state::StateMode::Tesseract, Mode::Tesseract),
            (state::StateMode::Modern, Mode::Modern),
        ] {
            assert_eq!(mode_of(bits), want);
            assert_eq!(mode_of(bits).label(), bits.label());
        }
    }

    /// Contract v3's precedence rule, field by field: the header is the default,
    /// an explicit flag overrides it, and a field the header does not carry keeps
    /// the overlay's own default.
    #[test]
    fn the_state_header_is_the_default_and_a_flag_overrides_it() {
        let header = header();

        // Nothing on the command line: the header decides everything it carries.
        let bare = parse("").unwrap();
        assert!(!bare.mode_set && !bare.wave_set && !bare.watermark_set);
        assert!(!bare.spaced_set && !bare.primary_set && !bare.secondary_set);
        assert!(!bare.scale_set && !bare.opacity_set && !bare.ordered_set);
        assert!(!bare.position_set, "nothing named --position either");
        let cfg = config_from(&bare, 1.0, Some(&header));
        assert_eq!(cfg.mode, Mode::Tesseract);
        assert!(!cfg.wave, "the header's m_Wave");
        assert!(cfg.spaced, "the header's m_SpacedModules");
        assert!(!cfg.watermark, "the header's m_Watermark");
        assert!(cfg.ordered, "the header's orderPublished");
        assert_eq!(cfg.position, Position::Left, "the header's positionLeft (flags bit 7)");
        assert_eq!(cfg.scale, 1.25, "the header's scale_x1000");
        assert_eq!(cfg.opacity, 0.5, "the header's opacity");
        assert_eq!(cfg.primary, header.primary);
        assert_eq!(cfg.secondary, header.secondary);
        assert_eq!(cfg.nickname, "gtier");
        assert_eq!(cfg.fps, 144);
        // The fields no header carries keep the caller's own values.
        assert_eq!(cfg.wave_speed, hud::DEFAULT_WAVE_SPEED);
        assert_eq!(cfg.alpha, 1.0);

        // A header that says the opposite of the CLI's own defaults, with no
        // flags at all: the header still wins.
        let flipped = state::Header {
            wave: true,
            spaced: false,
            watermark: true,
            mode: state::StateMode::Vape,
            order_published: false,
            position_left: false,
            scale: Some(2.0),
            opacity: Some(0.25),
            ..header.clone()
        };
        let cfg = config_from(&bare, 1.0, Some(&flipped));
        assert_eq!(cfg.mode, Mode::Vape);
        assert!(cfg.wave && cfg.watermark);
        assert!(!cfg.spaced);
        assert!(!cfg.ordered);
        assert_eq!(cfg.position, Position::Right, "the header's bit clear means right");
        assert_eq!(cfg.scale, 2.0);
        assert_eq!(cfg.opacity, 0.25);

        // Every flag named: every flag wins.
        let explicit = parse(
            "--mode rise --position right --no-wave --no-watermark --spaced --primary 1,2,3 \
             --secondary 4,5,6 --scale 1.5 --opacity 40 --ordered",
        )
        .unwrap();
        assert!(explicit.mode_set && explicit.wave_set && explicit.watermark_set);
        assert!(explicit.spaced_set && explicit.primary_set && explicit.secondary_set);
        assert!(explicit.scale_set && explicit.opacity_set && explicit.ordered_set);
        assert!(explicit.position_set);
        let cfg = config_from(&explicit, 0.5, Some(&flipped));
        assert_eq!(cfg.mode, Mode::Rise);
        assert!(!cfg.wave, "--no-wave beats a header with the wave on");
        assert!(!cfg.watermark, "--no-watermark beats a header with it on");
        assert!(cfg.spaced, "--spaced beats a header with it off");
        assert_eq!(cfg.position, Position::Right, "--position beats the header's bit");
        assert_eq!(cfg.scale, 1.5, "--scale beats scale_x1000");
        assert_eq!(cfg.opacity, 0.4, "--opacity beats the header's percent");
        assert!(cfg.ordered, "--ordered beats a header without the bit");
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
        assert!(cfg.wave && cfg.watermark && !cfg.spaced && !cfg.ordered);
        assert_eq!(cfg.position, Position::Right, "the overlay's own default");
        assert_eq!(cfg.scale, 1.0);
        assert_eq!(cfg.opacity, 1.0);
        assert_eq!(cfg.primary, hud::DEFAULT_PRIMARY);
        assert_eq!(cfg.secondary, hud::DEFAULT_SECONDARY);
        assert_eq!(cfg.nickname, "");
        assert_eq!(cfg.fps, 0);
        // ...and an explicit flag still wins over that.
        let cfg = config_from(&parse("--mode tesseract --no-wave").unwrap(), 1.0, None);
        assert_eq!(cfg.mode, Mode::Tesseract);
        assert!(!cfg.wave);
    }

    /// The two v3 words mean "unset" at 0, and the merge has to tell that apart
    /// from a writer that really asked for the overlay's default: a header with
    /// no opinion must not pin a scale or an opacity the user set on the CLI.
    #[test]
    fn an_unset_header_scale_and_opacity_leave_the_flag_alone() {
        let no_opinion = state::Header {
            scale: None,
            opacity: None,
            order_published: false,
            mode: state::StateMode::Modern,
            ..header()
        };
        let cfg = config_from(&parse("--scale 2 --opacity 25").unwrap(), 1.0, Some(&no_opinion));
        assert_eq!(cfg.scale, 2.0);
        assert_eq!(cfg.opacity, 0.25);
        assert_eq!(cfg.mode, Mode::Modern, "the header's mode still lands");

        // And with no flags either, the overlay's own defaults stand.
        let cfg = config_from(&parse("").unwrap(), 1.0, Some(&no_opinion));
        assert_eq!(cfg.scale, 1.0);
        assert_eq!(cfg.opacity, 1.0);
        assert!(!cfg.ordered);
    }

    /// `--opacity` is a percentage on the command line, and anything outside
    /// 0..100 is refused rather than silently clamped: an opacity that is not
    /// what was asked for is exactly the kind of wrong the user cannot see.
    #[test]
    fn the_opacity_flag_is_a_percentage() {
        assert_eq!(parse("--opacity 0").unwrap().opacity, 0.0);
        assert_eq!(parse("--opacity 35").unwrap().opacity, 0.35);
        assert_eq!(parse("--opacity 100").unwrap().opacity, 1.0);
        assert!(parse("--opacity 101").is_err());
        assert!(parse("--opacity -1").is_err());
        assert!(parse("--opacity half").is_err());
        assert!(parse("--opacity").is_err(), "the flag needs its value");

        // `--scale` still validates its own range, and now it is remembered as
        // "named" so the header cannot override it.
        assert!(parse("--scale 0.1").is_err());
        assert!(parse("--scale 5").is_err());
        assert!(parse("--scale 2").unwrap().scale_set);
        assert!(!parse("").unwrap().scale_set);
    }

    /// `--line-spacing` is a percentage, refused outside the contract's 10..200, and
    /// it merges like the other v3 words: flag, then header, then the reference.
    #[test]
    fn the_line_spacing_flag_merges_like_the_other_v3_words() {
        assert_eq!(parse("--line-spacing 50").unwrap().line_spacing, 0.5);
        assert_eq!(parse("--line-spacing 100").unwrap().line_spacing, 1.0);
        assert_eq!(parse("--line-spacing 200").unwrap().line_spacing, 2.0);
        assert!(parse("--line-spacing 9").is_err());
        assert!(parse("--line-spacing 201").is_err());
        assert!(parse("--line-spacing tight").is_err());
        assert!(parse("--line-spacing").is_err(), "the flag needs its value");

        let from_header = state::Header { line_spacing: Some(1.5), ..header() };
        assert_eq!(
            config_from(&parse("").unwrap(), 1.0, Some(&from_header)).line_spacing,
            1.5,
            "the header's line spacing is the default"
        );
        assert_eq!(
            config_from(&parse("--line-spacing 80").unwrap(), 1.0, Some(&from_header)).line_spacing,
            0.8,
            "the flag beats the header"
        );
        let no_opinion = state::Header { line_spacing: None, ..header() };
        assert_eq!(
            config_from(&parse("").unwrap(), 1.0, Some(&no_opinion)).line_spacing,
            1.0,
            "an unset header leaves the reference geometry alone"
        );
    }

    /// `positionLeft` (contract v3 flags bit 7, the WebUI's Position option) merges
    /// like every other header field, with one difference: it is a switch with no
    /// "unset", so a header-carrying block always decides and only an explicit
    /// `--position` overrides it. This is the bug the bit closes: the WebUI could
    /// change Position and the overlay never moved.
    #[test]
    fn the_position_bit_merges_with_the_flag_winning() {
        // No flag, bit 7 set: the header's left placement is drawn.
        let left = state::Header { position_left: true, ..header() };
        assert_eq!(
            config_from(&parse("").unwrap(), 1.0, Some(&left)).position,
            Position::Left,
            "the header's positionLeft is the default"
        );
        // No flag, bit clear: the reference's right-hand placement. Clear is an
        // opinion too, not an "unset" — there is no third state on the wire.
        let right = state::Header { position_left: false, ..header() };
        assert_eq!(
            config_from(&parse("").unwrap(), 1.0, Some(&right)).position,
            Position::Right,
            "a clear bit is the reference placement"
        );

        // The flag beats the header in both directions.
        assert_eq!(
            config_from(&parse("--position right").unwrap(), 1.0, Some(&left)).position,
            Position::Right,
            "--position beats a header asking for left"
        );
        assert_eq!(
            config_from(&parse("--position left").unwrap(), 1.0, Some(&right)).position,
            Position::Left,
            "--position beats a header asking for right"
        );

        // No header at all — a v1 block, or no file — is the overlay's own default,
        // and `stacked` is still the synonym of `right` it always was. `--position`
        // unset is what lets the header decide in the first place.
        assert_eq!(config_from(&parse("").unwrap(), 1.0, None).position, Position::Right);
        assert_eq!(parse("--position stacked").unwrap().position, Position::Right);
        assert!(parse("--position left").unwrap().position_set);
        assert!(!parse("").unwrap().position_set);

        // Which of the three decided, for the startup line: `describe_state` prints
        // what the header asked for and the line prints what is drawn, so the source
        // word has to agree with the merge above.
        let snapshot = state::Snapshot { entries: Vec::new(), modified: None, header: Some(left.clone()) };
        assert!(describe_state(&snapshot).contains("position left"), "{}", describe_state(&snapshot));
        let snapshot = state::Snapshot { entries: Vec::new(), modified: None, header: Some(right) };
        assert!(describe_state(&snapshot).contains("position right"), "{}", describe_state(&snapshot));
        assert_eq!(position_source(&parse("").unwrap(), Some(&left)), "header");
        assert_eq!(position_source(&parse("--position right").unwrap(), Some(&left)), "--position");
        assert_eq!(position_source(&parse("").unwrap(), None), "default");
    }

    /// The repaint rule has to see the header: a state file that gains a nickname,
    /// an FPS, a mode, a colour, a scale, an opacity, the published-order flag or the
    /// left/right position bit is a different frame even when every module row is
    /// byte-for-byte the same.
    #[test]
    fn a_header_change_reaches_the_frame_signature() {
        let entries = demo_entries();
        let v3 = header();
        assert_ne!(
            signature(&entries, 1.0, None),
            signature(&entries, 1.0, Some(&v3)),
            "a v1 block becoming a header-carrying one repaints"
        );
        assert_eq!(
            signature(&entries, 1.0, Some(&v3)),
            signature(&entries, 1.0, Some(&header())),
            "the same header hashes the same"
        );

        for changed in [
            state::Header { nickname: "someone-else".to_string(), ..v3.clone() },
            state::Header { fps: 60, ..v3.clone() },
            state::Header { mode: state::StateMode::Rise, ..v3.clone() },
            state::Header { wave: true, ..v3.clone() },
            state::Header { spaced: false, ..v3.clone() },
            state::Header { watermark: true, ..v3.clone() },
            state::Header { order_published: false, ..v3.clone() },
            state::Header { position_left: false, ..v3.clone() },
            state::Header { scale: Some(2.0), ..v3.clone() },
            state::Header { opacity: Some(0.75), ..v3.clone() },
            state::Header { primary: [1.0, 1.0, 1.0], ..v3.clone() },
            state::Header { secondary: [0.0, 0.0, 0.0], ..v3.clone() },
        ] {
            assert_ne!(
                signature(&entries, 1.0, Some(&v3)),
                signature(&entries, 1.0, Some(&changed)),
                "a header change must repaint: {changed:?}"
            );
        }

        // The work pulse is a level, not a flag: two strengths are two frames.
        let mut working = demo_entries();
        working[1].flash = 255;
        let mut fading = demo_entries();
        fading[1].flash = 40;
        assert_ne!(
            signature(&working, 1.0, Some(&v3)),
            signature(&fading, 1.0, Some(&v3)),
            "a decaying pulse repaints"
        );
    }

    /// The rule this change is about, and the one a game is needed to see in the
    /// wild: while the injector's pid exists the overlay stays up even though the
    /// state file has gone quiet. 1.8 blocks its render thread for seconds while it
    /// generates terrain, so that is a world load — the case that used to fade out
    /// and exit.
    #[test]
    fn a_live_pid_keeps_the_overlay_up_through_a_stale_state_file() {
        assert_eq!(verdict(true, true, true), Verdict::Alive, "a world load is not an exit");
        assert_eq!(verdict(true, true, false), Verdict::Alive, "a fresh file is alive too");
        // A pid that is really gone ends the run whatever the state file says.
        assert_eq!(verdict(true, false, true), Verdict::Gone);
        assert_eq!(verdict(true, false, false), Verdict::Gone);
    }

    /// Without a pid nothing changed: three seconds of silence, then the fade and
    /// the exit. `pid_alive` is not even read in this run, so no value of it can
    /// turn a stale file into an exit or a fresh one into a hang.
    #[test]
    fn without_a_pid_the_stale_rule_is_unchanged() {
        assert_eq!(verdict(false, true, false), Verdict::Alive);
        assert_eq!(verdict(false, false, false), Verdict::Alive);
        assert_eq!(verdict(false, true, true), Verdict::Stale);
        assert_eq!(verdict(false, false, true), Verdict::Stale);
    }

    /// The exit line is the only record of *why* a run stopped, so the dead-pid
    /// wording is pinned: "the HUD exited" and "the game exited" have to be
    /// distinguishable from the log alone.
    #[test]
    fn a_dead_pid_is_reported_by_id() {
        assert_eq!(gone_reason(1234), "game process 1234 exited");
        assert_eq!(gone_reason(4), "game process 4 exited");
    }

    /// The startup line has to name the rule in force, or the exit line cannot be
    /// read back — "state stale" says nothing until you know whether a pid was ever
    /// consulted.
    #[test]
    fn the_startup_line_names_the_liveness_rule_in_force() {
        let with_pid = liveness_rule(Some(4242));
        assert!(with_pid.contains("4242"), "{with_pid}");
        assert!(with_pid.contains("stale state file does not end the run"), "{with_pid}");

        let without = liveness_rule(None);
        assert!(without.contains("mtime"), "{without}");
        assert!(without.contains("no --follow-pid"), "{without}");
        assert!(without.contains("3s"), "{without}");
        assert!(!without.contains("pid "), "no pid is named here: {without}");
    }

    /// The startup line has to name the visibility rule for the same reason it names
    /// the liveness one: "I never saw the HUD" has to be readable as "the overlay was
    /// deliberately hidden because the game was not in front" rather than as a bug,
    /// and a no-pid run is the one that is allowed to float over everything.
    #[test]
    fn the_startup_line_names_the_visibility_rule_in_force() {
        let gated = visibility_rule(Some(4242));
        assert!(gated.contains("pid-gated"), "{gated}");
        assert!(gated.contains("4242"), "{gated}");
        assert!(gated.contains("foreground"), "{gated}");
        assert!(gated.contains("not exited"), "hidden is not an exit: {gated}");

        let always = visibility_rule(None);
        assert!(always.contains("always shown"), "{always}");
        assert!(always.contains("no --follow-pid"), "{always}");
        assert!(
            !always.chars().any(|c| c.is_ascii_digit()),
            "no pid number is named by the no-pid rule: {always}"
        );
    }

    /// The poll is deliberately far slower than the frame interval: one process
    /// handle per frame is not an acceptable cost in a 60 Hz loop, and it is still
    /// quick enough that a run does not outlive the game by more than a moment.
    #[test]
    fn the_liveness_poll_is_not_a_per_frame_call() {
        assert!(LIVENESS_POLL_MS >= 10 * FRAME_MS, "{LIVENESS_POLL_MS} ms");
        assert!(LIVENESS_POLL_MS <= 5_000, "a run should not outlive the game by much");
    }
}
