// main.rs — Doppel Rust-led injector entry point
//
// Sandwich Architecture — Layer 1 (injector).
//
// Two ways in, one pipeline:
//
//   doppel-injector              → opens the native launcher window: it lists
//                                  the java processes, you pick one and inject.
//   doppel-injector --pid 1234   → headless: inject that PID right away,
//                                  console timeline only (scripts / CI).
//
// The launcher is a real window, not a page: an injector that needs a browser
// tab, a local HTTP port and a per-run token to do its one job was the wrong
// shape. The form follows OpenCommunity's launcher — choose the instance, then
// watch the injection — and the console timeline still runs in parallel, so a
// terminal is all you need when something goes wrong.
//
// The pipeline itself lives in flow.rs and is UI-agnostic; ui.rs paints the
// terminal, window.rs paints the launcher. The C++ payload.dll stays C++ (it
// must run inside javaw.exe and talk JNI / the Windows Attach pipe).

mod flow;
mod inject;
mod log;
mod process;
mod resource;
mod ui;
mod version;
mod window;

use std::time::{Duration, Instant};

use flow::{Reporter, STEPS};

/// The terminal timeline, wrapped as a Reporter so flow.rs can drive it.
struct ConsoleReporter;

impl Reporter for ConsoleReporter {
    fn step_begin(&self, idx: usize) {
        ui::step_begin(idx, STEPS.get(idx).copied().unwrap_or(""));
    }
    fn step_note(&self, note: &str) {
        ui::step_note(note);
    }
    fn step_ok(&self, idx: usize, secs: f32) {
        let started = Instant::now() - Duration::from_secs_f32(secs.max(0.0));
        ui::step_ok(idx, STEPS.get(idx).copied().unwrap_or(""), started);
    }
    fn step_fail(&self, idx: usize, reason: &str) {
        ui::step_fail(idx, STEPS.get(idx).copied().unwrap_or(""), reason);
    }
    fn detail(&self, msg: &str) {
        ui::detail(msg);
    }
    fn warn(&self, msg: &str) {
        ui::warn(msg);
    }
    fn hint(&self, msg: &str) {
        ui::hint(msg);
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();

    if flag(&args, "--help") || flag(&args, "-h") {
        help();
        return;
    }

    let verbose = flag(&args, "--verbose") || flag(&args, "-v");
    ui::init(verbose);
    ui::banner(env!("CARGO_PKG_VERSION"));
    log::append(&format!(
        "=== doppel-injector v{} started{} ===",
        env!("CARGO_PKG_VERSION"),
        if verbose { " (verbose)" } else { "" }
    ));

    // ── Headless path: an explicit PID, console only, no window ──
    if let Some(pid) = value(&args, "--pid").and_then(|v| v.parse::<u32>().ok()) {
        run_headless(pid);
        return;
    }

    // ── Launcher path: pick the process in the window ──
    ui::note(&format!("log     {}\\doppel-injector.log", resource::temp_dir()));
    ui::hint("pick the Minecraft process in the launcher window");

    let code = window::run();

    ui::shutdown();
    log::append("=== launcher closed ===");
    ui::note("injector closed — the agent keeps running inside the game");
    std::process::exit(code);
}

/// Inject one known PID with the console timeline only.
fn run_headless(pid: u32) {
    ui::note(&format!("headless mode · target PID {}", pid));
    match flow::run(Some(pid), &ConsoleReporter) {
        Ok(outcome) => {
            ui::summary(outcome.panel_url.as_deref(), &outcome.panel_token);
            ui::detail("done — the agent keeps running inside the game process");
            log::append("=== headless injection OK ===");
            ui::shutdown();
            pause_exit();
        }
        Err(e) => {
            let idx = e.step_index();
            ui::step_fail(idx, STEPS.get(idx).copied().unwrap_or(""), e.reason());
            ui::hint(e.hint());
            log::append(&format!("=== headless injection FAILED · {} ===", e.reason()));
            ui::shutdown();
            pause_exit();
            std::process::exit(1);
        }
    }
}

fn help() {
    println!(
        "doppel-injector {}\n\n\
USAGE:\n  \
doppel-injector [OPTIONS]\n\n\
OPTIONS:\n  \
--pid <PID>      inject this PID immediately (headless, no launcher window)\n  \
-v, --verbose    print detail lines in the console\n  \
-h, --help       this text\n\n\
Without --pid the injector opens its launcher window, lists every javaw/java\n\
process on the machine and lets you pick which one to inject. Every run is\n\
also written to %TEMP%\\doppel-injector.log.\n",
        env!("CARGO_PKG_VERSION")
    );
}

fn flag(args: &[String], name: &str) -> bool {
    args.iter().any(|a| a == name)
}

fn value(args: &[String], name: &str) -> Option<String> {
    let mut it = args.iter();
    while let Some(a) = it.next() {
        if a == name {
            return it.next().cloned();
        }
        if let Some(rest) = a.strip_prefix(&format!("{}=", name)) {
            return Some(rest.to_string());
        }
    }
    None
}

fn pause_exit() {
    #[cfg(windows)]
    {
        // "Press any key" — read a line so the console stays open.
        let _ = std::io::stdin().read_line(&mut String::new());
    }
    #[cfg(not(windows))]
    {
        // Non-Windows dev builds exit immediately (CI-friendly).
    }
}
