// main.rs — Doppel Rust-led injector entry point
//
// Sandwich Architecture — Layer 1 (injector).
//
// Two ways in, one pipeline:
//
//   doppel-injector                 → starts the local web panel, opens the
//                                     browser, and waits. You pick the PID.
//   doppel-injector --pid 1234      → headless: inject that PID right away,
//                                     console timeline only (scripts / CI).
//
// The console timeline is not gone — it runs in parallel with the page, so a
// double-clicked exe still shows what is happening, and there is always a
// usable path when no browser is available.
//
// The pipeline itself lives in flow.rs and is UI-agnostic; ui.rs paints the
// terminal, webui.rs serves the page. The C++ payload.dll stays C++ (it must
// run inside javaw.exe and talk JNI / the Windows Attach pipe).

mod flow;
mod inject;
mod process;
mod resource;
mod ui;
mod version;
mod webui;

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

    // ── Headless path: an explicit PID, console only, no server ──
    if let Some(pid) = value(&args, "--pid").and_then(|v| v.parse::<u32>().ok()) {
        run_headless(pid);
        return;
    }

    // ── Panel path: pick the process in the browser ──
    let port = value(&args, "--port")
        .and_then(|v| v.parse::<u16>().ok())
        .unwrap_or(0);
    let open_browser = !flag(&args, "--no-browser");

    let panel = match webui::start(port, open_browser) {
        Ok(p) => p,
        Err(e) => {
            ui::error(&format!("could not start the local panel: {}", e));
            ui::hint("try another port: doppel-injector --port 8765");
            ui::shutdown();
            pause_exit();
            std::process::exit(1);
        }
    };

    println!();
    ui::note(&format!("panel   {}", panel.url()));
    ui::note("token   included in the URL above — the page sends it on every request");
    if !open_browser {
        ui::hint("open that URL yourself (--no-browser was set)");
    }
    ui::hint("pick the Minecraft process in the page · press Enter here to quit");
    println!();

    // Enter in this console closes the injector too, for people who never look
    // at the browser tab.
    std::thread::spawn(|| {
        let mut line = String::new();
        let _ = std::io::stdin().read_line(&mut line);
        webui::request_exit();
    });

    webui::wait_for_exit();
    ui::shutdown();
    ui::note("injector closed — the agent keeps running inside the game");
}

/// Inject one known PID with the console timeline only.
fn run_headless(pid: u32) {
    ui::note(&format!("headless mode · target PID {}", pid));
    match flow::run(Some(pid), &ConsoleReporter) {
        Ok(outcome) => {
            ui::summary(outcome.panel_url.as_deref(), &outcome.panel_token);
            ui::detail("done — the agent keeps running inside the game process");
            ui::shutdown();
            pause_exit();
        }
        Err(e) => {
            let idx = e.step_index();
            ui::step_fail(idx, STEPS.get(idx).copied().unwrap_or(""), e.reason());
            ui::hint(e.hint());
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
--pid <PID>      inject this PID immediately (headless, no web panel)\n  \
--port <PORT>    fixed port for the panel (default: an ephemeral free port)\n  \
--no-browser     serve the panel but do not open a browser\n  \
-v, --verbose    print detail lines\n  \
-h, --help       this text\n\n\
Without --pid the injector starts a local panel on 127.0.0.1, opens it, and\n\
lists every javaw/java process so you can pick which one to inject.\n",
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
