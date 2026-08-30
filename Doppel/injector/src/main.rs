// main.rs — Doppel Rust-led injector entry point
//
// Sandwich Architecture — Layer 1 (injector). The console experience follows
// the approved v4 fluid design: gradient banner, braille spinner + flowing
// segmented bar per step, quiet by default, --verbose for details.
//
// Responsibilities (migrated from the old C++ main.cpp):
//   1. Linking with Minecraft — find the javaw process + window
//   2. Preparing runtime     — detect version/platform, extract agent.jar
//   3. Injecting             — payload.dll performs the in-process JNI attach
//   4. Handoff               — wait for the WebUI panel broadcast, show summary
//
// The C++ payload.dll stays C++ (it must run inside javaw.exe and talk JNI /
// the Windows Attach pipe); everything around it is Rust.

mod inject;
mod process;
mod resource;
mod ui;
mod version;

use std::time::Instant;

const STEPS: [&str; 4] = [
    "Linking with Minecraft",
    "Preparing runtime",
    "Injecting",
    "Handoff",
];

fn main() {
    let verbose = std::env::args().any(|a| a == "--verbose" || a == "-v");
    ui::init(verbose);
    ui::banner(env!("CARGO_PKG_VERSION"));

    // ── Step 1 · Linking with Minecraft ──
    let t0 = Instant::now();
    ui::step_begin(0, STEPS[0]);
    let mc = process::find_minecraft_process();
    if !mc.valid {
        ui::step_fail(0, STEPS[0], "no running Minecraft window found");
        ui::hint("launch Minecraft first, then re-run the injector");
        ui::shutdown();
        pause_exit();
        std::process::exit(1);
    }
    ui::detail(&format!("PID {} · window \"{}\"", mc.pid, mc.window_title));
    ui::step_ok(0, STEPS[0], t0);

    // ── Step 2 · Preparing runtime ──
    let t1 = Instant::now();
    ui::step_begin(1, STEPS[1]);
    let info = version::parse_minecraft_version(&mc.path, &mc.window_title);
    if !info.valid {
        ui::step_fail(1, STEPS[1], "could not identify the Minecraft version");
        ui::hint("unsupported launcher layout — see %TEMP%\\doppel-payload.log");
        ui::shutdown();
        pause_exit();
        std::process::exit(1);
    }
    ui::detail(&format!("version {} · platform {}", info.version, info.platform));
    ui::step_note(&format!("mc {}", info.version));
    let agent_path = resource::get_embedded_agent_path();
    if agent_path.is_empty() {
        ui::step_fail(1, STEPS[1], "embedded agent.jar missing from this exe");
        ui::hint("rebuild via CI — the fat jar step must run before cargo build");
        ui::shutdown();
        pause_exit();
        std::process::exit(1);
    }
    ui::detail(&format!("agent ready · {}", agent_path));
    ui::step_ok(1, STEPS[1], t1);

    // ── Step 3 · Injecting ──
    let t2 = Instant::now();
    ui::step_begin(2, STEPS[2]);
    ui::step_note(&format!("{} · {}", info.platform, info.version));
    let ok = inject::inject_java_agent(&mc, &agent_path, &info.platform, &info.version);
    if !ok {
        ui::step_fail(2, STEPS[2], "the JVM rejected the payload");
        ui::hint("check %TEMP%\\doppel-payload.log and doppel-agent.log");
        ui::shutdown();
        pause_exit();
        std::process::exit(1);
    }
    ui::step_ok(2, STEPS[2], t2);

    // ── Step 4 · Handoff ──
    let t3 = Instant::now();
    ui::step_begin(3, STEPS[3]);
    ui::step_note("waiting for panel");
    let (panel_url, token) = wait_panel_broadcast(6);
    if panel_url.is_some() {
        ui::detail("panel broadcast captured");
    } else {
        ui::warn("panel not broadcast yet — it keeps starting in the background");
        ui::hint("the address will appear in %TEMP%\\doppel-payload.log");
    }
    ui::step_ok(3, STEPS[3], t3);

    ui::summary(panel_url.as_deref(), &token);
    ui::detail("done — the agent keeps running inside the game process");
    ui::shutdown();
    pause_exit();
}

/// Poll %TEMP%\doppel-payload.log for the WebUI broadcast line:
///   "[WebUI] Panel: <url...>  Token: <token>"
/// Returns (first url, token). Empty strings when not broadcast within budget.
fn wait_panel_broadcast(max_secs: u64) -> (Option<String>, String) {
    let log_path = format!(
        "{}doppel-payload.log",
        resource::temp_dir()
    );
    let deadline = Instant::now() + std::time::Duration::from_secs(max_secs);
    while Instant::now() < deadline {
        if let Ok(log) = std::fs::read_to_string(&log_path) {
            if let Some(line) = log.lines().rev().find(|l| l.contains("[WebUI] Panel:")) {
                let rest = match line.find("[WebUI] Panel:") {
                    Some(i) => line[i + "[WebUI] Panel:".len()..].trim(),
                    None => "",
                };
                let (urls_part, token) = match rest.find("Token:") {
                    Some(i) => (rest[..i].trim(), rest[i + "Token:".len()..].trim()),
                    None => (rest, ""),
                };
                let url = urls_part
                    .split_whitespace()
                    .find(|s| s.starts_with("http"))
                    .unwrap_or("")
                    .to_string();
                let url_opt = if url.is_empty() { None } else { Some(url) };
                return (url_opt, token.to_string());
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(400));
    }
    (None, String::new())
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
