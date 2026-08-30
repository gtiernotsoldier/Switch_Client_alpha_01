// ui.rs — Doppel injector fluid terminal UI (v4 design language)
//
// Visual identity (matches the approved v4 demo):
//   bg          #05070B   (terminal default; we never paint background)
//   primary     #22D3EE   cyan  — spinner, bar head, values
//   accent      #A78BFA   violet— brand, tagline, bar tail
//   success     #34D399   green — ✓ step completion, summary dot
//   failure     #F87171   red   — ✗ step failure, errors
//   warning     #FBBF24   amber — hints, non-fatal warnings
//   skeleton    #5A6B7F   dim   — bar skeleton, detail lines, frames
//   text        #D5DEEA   soft white — labels, body
//
// Zero third-party deps: VT100 is enabled through raw kernel32 FFI
// (ENABLE_VIRTUAL_TERMINAL_PROCESSING) with graceful plain-text fallback,
// and the console is switched to UTF-8 so the braille spinner / bar cells
// render correctly on Windows.
//
// Threading model:
//   - a single worker thread repaints the "live line" (spinner + flowing bar)
//     every 80 ms while a step is running;
//   - detail/warn/error lines lock the shared IO mutex, clear the live line,
//     print themselves above it, and let the next tick repaint the line —
//     so output never garbles and the animation never stutters.

use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

// ── palette (Doppel v4) ──
pub const CYAN: [u8; 3] = [34, 211, 238];
pub const VIOLET: [u8; 3] = [167, 139, 250];
pub const GREEN: [u8; 3] = [52, 211, 153];
pub const RED: [u8; 3] = [248, 113, 113];
pub const AMBER: [u8; 3] = [251, 191, 36];
pub const DIM: [u8; 3] = [90, 107, 127];
pub const TEXT: [u8; 3] = [213, 222, 234];

const SPINNER: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];
const BAR_CELLS: usize = 28;
const TICK_MS: u64 = 80;

fn fg(c: [u8; 3]) -> String {
    format!("\x1b[38;2;{};{};{}m", c[0], c[1], c[2])
}

fn lerp(a: [u8; 3], b: [u8; 3], t: f32) -> [u8; 3] {
    [
        (a[0] as f32 + (b[0] as f32 - a[0] as f32) * t) as u8,
        (a[1] as f32 + (b[1] as f32 - a[1] as f32) * t) as u8,
        (a[2] as f32 + (b[2] as f32 - a[2] as f32) * t) as u8,
    ]
}

// ── global state ──

static ANSI: AtomicBool = AtomicBool::new(false);
static VERBOSE: AtomicBool = AtomicBool::new(false);
static IO: Mutex<()> = Mutex::new(());
static STATE: Mutex<State> = Mutex::new(State::Idle);
static WORKER: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);

enum State {
    Idle,
    Live {
        label: String,
        note: String,
        frame: usize,
        phase: usize,
    },
    Stopped,
}

fn io_lock() -> MutexGuard<'static, ()> {
    IO.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn ansi() -> bool {
    ANSI.load(Ordering::Relaxed)
}

pub fn init(verbose: bool) {
    VERBOSE.store(verbose, Ordering::Relaxed);
    enable_vt();
}

// ── Windows VT + UTF-8 console setup (raw FFI, zero deps) ──

#[cfg(windows)]
pub fn enable_vt() -> bool {
    #[link(name = "kernel32")]
    extern "system" {
        fn GetStdHandle(n_std_handle: i32) -> *mut core::ffi::c_void;
        fn GetConsoleMode(h_console: *mut core::ffi::c_void, lp_mode: *mut u32) -> i32;
        fn SetConsoleMode(h_console: *mut core::ffi::c_void, dw_mode: u32) -> i32;
        fn SetConsoleOutputCP(w_code_page_id: u32) -> i32;
        fn SetConsoleCP(w_code_page_id: u32) -> i32;
    }
    const STD_OUTPUT_HANDLE: i32 = -11;
    const ENABLE_VIRTUAL_TERMINAL_PROCESSING: u32 = 0x0004;
    const CODEPAGE_UTF8: u32 = 65001;
    unsafe {
        let _ = SetConsoleOutputCP(CODEPAGE_UTF8);
        let _ = SetConsoleCP(CODEPAGE_UTF8);
        let h = GetStdHandle(STD_OUTPUT_HANDLE);
        if h.is_null() {
            ANSI.store(false, Ordering::Relaxed);
            return false;
        }
        let mut mode: u32 = 0;
        if GetConsoleMode(h, &mut mode) == 0 {
            // Not a console (piped output) — plain mode.
            ANSI.store(false, Ordering::Relaxed);
            return false;
        }
        let ok = SetConsoleMode(h, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0;
        ANSI.store(ok, Ordering::Relaxed);
        ok
    }
}

#[cfg(not(windows))]
pub fn enable_vt() -> bool {
    // Unix terminals speak ANSI natively; keep it quiet when piped.
    let tty = false; // injector ships on Windows; keep CI logs plain anyway
    ANSI.store(tty, Ordering::Relaxed);
    tty
}

// ── banner ──

const FIGLET: [&str; 6] = [
    "██████╗  ██████╗ ██████╗ ██████╗ ███████╗██╗     ",
    "██╔══██╗██╔═══██╗██╔══██╗██╔══██╗██╔════╝██║     ",
    "██║  ██║██║   ██║██████╔╝██║  ██║█████╗  ██║     ",
    "██║  ██║██║   ██║██╔═══╝ ██║  ██║██╔══╝  ██║     ",
    "██████╔╝╚██████╔╝██║     ██████╔╝███████╗███████╗",
    "╚═════╝  ╚═════╝ ╚═╝     ╚═════╝ ╚══════╝╚══════╝",
];

pub fn banner(version: &str) {
    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    if ansi() {
        let _ = writeln!(w);
        for line in FIGLET {
            let mut s = String::from("  ");
            for (x, ch) in line.chars().enumerate() {
                if ch == ' ' {
                    s.push(' ');
                } else {
                    let t = x as f32 / 48.0;
                    s.push_str(&fg(lerp(CYAN, VIOLET, t)));
                    s.push(ch);
                }
            }
            s.push_str("\x1b[0m");
            let _ = writeln!(w, "{}", s);
        }
        let _ = writeln!(w, "\n  \x1b[3m{}statistically you.\x1b[0m", fg(VIOLET));
        let _ = writeln!(
            w,
            "  {}v{} · ghost client injector · --verbose for details\x1b[0m\n",
            fg(DIM),
            version
        );
    } else {
        let _ = writeln!(w, "Doppel Injector v{} (Rust)", version);
        let _ = writeln!(w, "statistically you.\n");
    }
    start_worker();
}

// ── spinner worker ──

fn start_worker() {
    let mut guard = WORKER.lock().unwrap_or_else(|e| e.into_inner());
    if guard.is_some() {
        return;
    }
    *guard = Some(std::thread::spawn(|| loop {
        std::thread::sleep(Duration::from_millis(TICK_MS));
        let snapshot: Option<(String, String, String, usize)> = {
            let mut st = STATE.lock().unwrap_or_else(|e| e.into_inner());
            match &mut *st {
                State::Live { frame, phase, .. } => {
                    *frame = frame.wrapping_add(1);
                    *phase = (*phase + 1) % 90;
                    Some(live_snapshot(&*st))
                }
                State::Stopped => None,
                State::Idle => Some((String::new(), String::new(), String::new(), 0)),
            }
        };
        match snapshot {
            Some((label, note, spinner, phase)) => {
                if !label.is_empty() {
                    draw_live(&label, &note, &spinner, phase);
                }
            }
            None => break,
        }
    }));
}

// snapshot to avoid holding STATE while painting
fn live_snapshot(st: &State) -> (String, String, String, usize) {
    match st {
        State::Live { label, note, frame, phase } => {
            (label.clone(), note.clone(), SPINNER[*frame % SPINNER.len()].to_string(), *phase)
        }
        _ => (String::new(), String::new(), String::new(), 0),
    }
}

fn draw_live(label: &str, note: &str, spinner: &str, phase: usize) {
    if !ansi() {
        return;
    }
    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    let mut bar = String::new();
    for i in 0..BAR_CELLS {
        let d = (i + phase) % 9;
        let ch = if d < 3 {
            '▓'
        } else if d < 6 {
            '▒'
        } else {
            '░'
        };
        let t = i as f32 / (BAR_CELLS - 1) as f32;
        bar.push_str(&fg(lerp(CYAN, VIOLET, t)));
        bar.push(ch);
    }
    let _ = write!(w, "\r\x1b[2K  {}{}{}\x1b[0m  {}{}{}\x1b[0m", fg(CYAN), spinner, fg(TEXT), fg(TEXT), label, fg(DIM));
    if !note.is_empty() {
        let _ = write!(w, " {}· {}{}", fg(DIM), note, "\x1b[0m");
    }
    let _ = write!(w, "  {}{}{}\x1b[0m", "", bar, "");
    let _ = w.flush();
}

fn clear_live_line() {
    if ansi() {
        let out = std::io::stdout();
        let mut w = out.lock();
        let _ = write!(w, "\r\x1b[2K");
    }
}

// ── step API ──

pub fn step_begin(idx: usize, label: &str) {
    let _ = idx;
    {
        let mut st = STATE.lock().unwrap_or_else(|e| e.into_inner());
        *st = State::Live {
            label: label.to_string(),
            note: String::new(),
            frame: 0,
            phase: 0,
        };
    }
    if !ansi() {
        let _g = io_lock();
        let _ = writeln!(std::io::stdout().lock(), "[*] {}...", label);
    }
}

pub fn step_note(note: &str) {
    let mut st = STATE.lock().unwrap_or_else(|e| e.into_inner());
    if let State::Live { note: n, .. } = &mut *st {
        *n = note.to_string();
    }
}

pub fn step_ok(idx: usize, label: &str, started: Instant) {
    let _ = idx;
    let elapsed = started.elapsed();
    {
        let mut st = STATE.lock().unwrap_or_else(|e| e.into_inner());
        *st = State::Idle;
    }
    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    if ansi() {
        clear_live_line();
        let _ = writeln!(
            w,
            "  {}✓{}\x1b[0m {}{}{}\x1b[0m {}· {:.1}s{}\x1b[0m",
            fg(GREEN),
            "",
            fg(TEXT),
            label,
            "",
            fg(DIM),
            elapsed.as_secs_f32().max(0.05),
            ""
        );
    } else {
        let _ = writeln!(w, "[+] {} done ({:.1}s)", label, elapsed.as_secs_f32().max(0.05));
    }
    let _ = w.flush();
}

pub fn step_fail(idx: usize, label: &str, reason: &str) {
    let _ = idx;
    {
        let mut st = STATE.lock().unwrap_or_else(|e| e.into_inner());
        *st = State::Idle;
    }
    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    if ansi() {
        clear_live_line();
        let _ = writeln!(w, "  {}✗{}\x1b[0m {}{}{}\x1b[0m {}· failed{}", fg(RED), "", fg(TEXT), label, "", fg(DIM), "\x1b[0m");
        let _ = writeln!(w, "    {}{}{}\x1b[0m", fg(RED), reason, "");
    } else {
        let _ = writeln!(w, "[x] {} failed: {}", label, reason);
    }
    let _ = w.flush();
}

// ── inline message API ──

fn print_line(msg: &str, color: [u8; 3], verbose_only: bool) {
    if verbose_only && !VERBOSE.load(Ordering::Relaxed) {
        return;
    }
    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    if ansi() {
        clear_live_line();
        let _ = writeln!(w, "  {}· {}{}\x1b[0m", fg(color), msg, "");
    } else if verbose_only {
        let _ = writeln!(w, "[i] {}", msg);
    } else {
        let _ = writeln!(w, "[!] {}", msg);
    }
    let _ = w.flush();
}

/// Verbose-only detail line (dim). Silent unless --verbose.
pub fn detail(msg: &str) {
    print_line(msg, DIM, true);
}

/// Always-visible warning (amber).
pub fn warn(msg: &str) {
    print_line(msg, AMBER, false);
}

/// Always-visible error (red).
pub fn error(msg: &str) {
    print_line(msg, RED, false);
}

/// Always-visible actionable hint (amber, indented).
pub fn hint(msg: &str) {
    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    if ansi() {
        let _ = writeln!(w, "    {}→ {}{}\x1b[0m", fg(AMBER), msg, "");
    } else {
        let _ = writeln!(w, "[!] → {}", msg);
    }
    let _ = w.flush();
}

// ── summary box ──

pub fn summary(panel: Option<&str>, token: &str) {
    let url = panel.unwrap_or("http://127.0.0.1:4173").to_string();
    let token = if token.is_empty() {
        "printed in doppel-agent.log".to_string()
    } else {
        token.to_string()
    };

    // (key, key-color, value, value-color)
    let rows: Vec<(&str, [u8; 3], &str, [u8; 3])> = vec![
        ("STATUS", GREEN, "agent injected — handoff complete", TEXT),
        ("WEBUI", DIM, url.as_str(), CYAN),
        ("TOKEN", DIM, token.as_str(), AMBER),
        ("HUD", DIM, "toggle modules from the panel, in game", TEXT),
    ];
    let tag = "statistically you.";
    let plain: Vec<usize> = rows.iter().map(|(k, _, v, _)| k.len() + 4 + v.len()).collect();
    let inner = plain.iter().copied().max().unwrap_or(44).max(tag.len() + 4);

    let _g = io_lock();
    let out = std::io::stdout();
    let mut w = out.lock();
    if ansi() {
        let _ = writeln!(w);
        let _ = writeln!(w, "  {}╭{}╮\x1b[0m", fg(DIM), "─".repeat(inner));
        for ((k, kc, v, vc), w_i) in rows.iter().zip(plain.iter()) {
            let pad = inner - w_i;
            let _ = writeln!(
                w,
                "  {}│\x1b[0m  {}{}{}  {}{}{}{}│\x1b[0m",
                fg(DIM),
                fg(*kc),
                k,
                fg(DIM),
                fg(*vc),
                v,
                "\x1b[0m",
                " ".repeat(pad)
            );
        }
        let pad = inner - tag.len();
        let _ = writeln!(
            w,
            "  {}│\x1b[0m{}\x1b[3m{}{}{}\x1b[0m{}{}│\x1b[0m",
            fg(DIM),
            " ".repeat(pad / 2),
            fg(VIOLET),
            tag,
            "\x1b[0m",
            " ".repeat(pad - pad / 2),
            ""
        );
        let _ = writeln!(w, "  {}╰{}╯\x1b[0m\n", fg(DIM), "─".repeat(inner));
    } else {
        let _ = writeln!(w, "\n+{}+", "-".repeat(inner));
        for ((k, _, v, _), w_i) in rows.iter().zip(plain.iter()) {
            let pad = inner - w_i;
            let _ = writeln!(w, "|  {}    {}{}|", k, v, " ".repeat(pad));
        }
        let _ = writeln!(w, "+{}+\n", "-".repeat(inner));
    }
    let _ = w.flush();
}

// ── shutdown ──

pub fn shutdown() {
    {
        let mut st = STATE.lock().unwrap_or_else(|e| e.into_inner());
        if matches!(*st, State::Live { .. }) {
            *st = State::Idle;
        }
        *st = State::Stopped;
    }
    let handle = {
        let mut guard = WORKER.lock().unwrap_or_else(|e| e.into_inner());
        guard.take()
    };
    if let Some(h) = handle {
        let _ = h.join();
    }
}
