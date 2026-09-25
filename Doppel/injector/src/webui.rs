// webui.rs — the injector's local web panel.
//
// Why a web UI instead of the console flow: the injector used to scan for
// Minecraft once, pick whatever it found, and inject. If the game was still
// booting you got "no running Minecraft window found" and had to re-run it by
// hand. Now the injector opens a page that lists every javaw/java process on
// the machine, refreshes itself while you wait for the game to come up, and
// injects exactly the PID you click.
//
// Zero third-party deps, same rule as the rest of the injector: a hand-rolled
// HTTP/1.1 server on std::net. It binds 127.0.0.1 only, and every endpoint
// requires a per-run token that is printed to the console and embedded in the
// URL we hand to the browser — so no other local process, and no other web
// page, can drive an injection.
//
// Threading: the accept loop and each connection run on their own threads; the
// injection itself runs on a worker so the page keeps polling while it runs.
// The panel state is a single Mutex<State> — the console timeline is updated
// in parallel by the same Reporter.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::flow::{self, FlowError, Reporter, STEPS};
use crate::ui;

/// The panel page, compiled into the exe (no external files at runtime).
const PAGE: &str = include_str!("injector.html");

// ── shared state ────────────────────────────────────────────────────────────

#[derive(Clone, Copy, PartialEq, Eq)]
enum StepState {
    Pending,
    Active,
    Ok,
    Failed,
}

impl StepState {
    fn as_str(self) -> &'static str {
        match self {
            StepState::Pending => "pending",
            StepState::Active => "active",
            StepState::Ok => "ok",
            StepState::Failed => "failed",
        }
    }
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

struct State {
    /// idle | injecting | done | failed
    phase: &'static str,
    headline: String,
    pid: u32,
    version: String,
    platform: String,
    panel_url: String,
    panel_token: String,
    steps: Vec<StepInfo>,
    /// (kind, text) — kind is info | warn | error | ok
    events: Vec<(&'static str, String)>,
}

impl Default for State {
    fn default() -> Self {
        State {
            phase: "idle",
            headline: "Pick the Minecraft process to inject".to_string(),
            pid: 0,
            version: String::new(),
            platform: String::new(),
            panel_url: String::new(),
            panel_token: String::new(),
            steps: (0..STEPS.len()).map(|_| StepInfo::default()).collect(),
            events: Vec::new(),
        }
    }
}

static STATE: OnceLock<Mutex<State>> = OnceLock::new();
static TOKEN: OnceLock<String> = OnceLock::new();
static BUSY: AtomicBool = AtomicBool::new(false);
static EXIT: AtomicBool = AtomicBool::new(false);

fn state() -> MutexGuard<'static, State> {
    STATE
        .get_or_init(|| Mutex::new(State::default()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

fn token() -> &'static str {
    TOKEN.get().map(|s| s.as_str()).unwrap_or("")
}

fn push_event(kind: &'static str, text: String) {
    let mut st = state();
    st.events.push((kind, text));
    // Keep the page light; the newest lines are the interesting ones.
    let len = st.events.len();
    if len > 240 {
        st.events.drain(0..len - 240);
    }
}

// ── the reporter that drives the panel ──────────────────────────────────────

struct WebReporter {
    started: Mutex<Vec<Option<Instant>>>,
}

impl WebReporter {
    fn new() -> Self {
        WebReporter { started: Mutex::new((0..STEPS.len()).map(|_| None).collect()) }
    }
}

impl Reporter for WebReporter {
    fn step_begin(&self, idx: usize) {
        {
            let mut st = state();
            if let Some(s) = st.steps.get_mut(idx) {
                s.state = StepState::Active;
            }
            st.phase = "injecting";
            st.headline = STEPS.get(idx).copied().unwrap_or("").to_string();
        }
        if let Ok(mut g) = self.started.lock() {
            if let Some(slot) = g.get_mut(idx) {
                *slot = Some(Instant::now());
            }
        }
        // Keep the terminal timeline in step with the page.
        ui::step_begin(idx, STEPS.get(idx).copied().unwrap_or(""));
    }

    fn step_note(&self, note: &str) {
        {
            let mut st = state();
            let active = st.steps.iter().position(|s| s.state == StepState::Active);
            if let Some(i) = active {
                st.steps[i].note = note.to_string();
            }
        }
        ui::step_note(note);
    }

    fn step_ok(&self, idx: usize, secs: f32) {
        {
            let mut st = state();
            if let Some(s) = st.steps.get_mut(idx) {
                s.state = StepState::Ok;
                s.secs = secs;
            }
        }
        let started = self
            .started
            .lock()
            .ok()
            .and_then(|g| g.get(idx).copied().flatten())
            .unwrap_or_else(Instant::now);
        ui::step_ok(idx, STEPS.get(idx).copied().unwrap_or(""), started);
    }

    fn step_fail(&self, idx: usize, reason: &str) {
        {
            let mut st = state();
            if let Some(s) = st.steps.get_mut(idx) {
                s.state = StepState::Failed;
                s.note = reason.to_string();
            }
            st.phase = "failed";
            st.headline = reason.to_string();
        }
        push_event("error", reason.to_string());
        ui::step_fail(idx, STEPS.get(idx).copied().unwrap_or(""), reason);
    }

    fn detail(&self, msg: &str) {
        ui::detail(msg);
    }

    fn warn(&self, msg: &str) {
        push_event("warn", msg.to_string());
        ui::warn(msg);
    }

    fn hint(&self, msg: &str) {
        push_event("info", msg.to_string());
        ui::hint(msg);
    }
}

// ── lifecycle ───────────────────────────────────────────────────────────────

/// A running panel.
pub struct Panel {
    pub port: u16,
    pub token: String,
}

impl Panel {
    /// The URL to open, token included.
    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}/?t={}", self.port, self.token)
    }
}

/// Bind the panel and start serving. `port` 0 (or a busy port) picks a free
/// one. When `open_browser` is set the default browser is launched at the URL.
pub fn start(port: u16, open_browser: bool) -> std::io::Result<Panel> {
    let new_token = make_token();
    let _ = TOKEN.set(new_token.clone());
    let _ = STATE.set(Mutex::new(State::default()));

    let listener = match TcpListener::bind(("127.0.0.1", port)) {
        Ok(l) => l,
        Err(_) if port != 0 => TcpListener::bind(("127.0.0.1", 0))?,
        Err(e) => return Err(e),
    };
    let actual = listener.local_addr()?.port();

    let panel = Panel { port: actual, token: new_token };
    let url = panel.url();
    std::thread::spawn(move || accept_loop(listener));
    if open_browser {
        open_url(&url);
    }

    Ok(panel)
}

/// Block until the page asks us to quit (Close button) or the caller stops us.
pub fn wait_for_exit() {
    while !EXIT.load(Ordering::Relaxed) {
        std::thread::sleep(Duration::from_millis(150));
    }
}

pub fn request_exit() {
    EXIT.store(true, Ordering::Relaxed);
}

fn accept_loop(listener: TcpListener) {
    for conn in listener.incoming() {
        if EXIT.load(Ordering::Relaxed) {
            break;
        }
        match conn {
            Ok(stream) => {
                std::thread::spawn(move || {
                    let _ = handle(stream);
                });
            }
            Err(_) => continue,
        }
    }
}

fn open_url(url: &str) {
    // cmd /C start "" <url> — the empty first argument is the window title,
    // otherwise `start` would treat the URL as the title and open nothing.
    let _ = std::process::Command::new("cmd")
        .args(["/C", "start", "", url])
        .spawn();
}

// ── HTTP ────────────────────────────────────────────────────────────────────

struct Request {
    method: String,
    path: String,
    query: String,
    header_token: String,
    body: String,
}

fn handle(mut stream: TcpStream) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));

    let req = match read_request(&mut stream) {
        Some(r) => r,
        None => {
            let _ = respond(&mut stream, 400, "application/json", "{\"error\":\"bad request\"}");
            return;
        }
    };

    // ── auth: per-run token, from ?t= or the X-Doppel-Token header ──
    let q_token = req
        .query
        .split('&')
        .find_map(|kv| kv.strip_prefix("t="))
        .unwrap_or("");
    let authed = (!req.header_token.is_empty() && req.header_token == token())
        || (!q_token.is_empty() && q_token == token());

    if !authed {
        if req.path == "/" {
            let body = "<!doctype html><meta charset=\"utf-8\"><title>Doppel injector</title>\
<body style=\"background:#05070B;color:#D5DEEA;font:14px system-ui;padding:48px\">\
<h2 style=\"color:#F87171\">Doppel injector</h2>\
<p>Open the URL printed in the injector console — it carries a one-time token.</p>\
<p style=\"color:#5A6B7F\">http://127.0.0.1:PORT/?t=&lt;token&gt;</p></body>";
            let _ = respond(&mut stream, 403, "text/html; charset=utf-8", body);
        } else {
            let _ = respond(&mut stream, 403, "application/json", "{\"error\":\"forbidden\"}");
        }
        return;
    }

    match (req.method.as_str(), req.path.as_str()) {
        ("GET", "/") | ("GET", "/index.html") => {
            let _ = respond(&mut stream, 200, "text/html; charset=utf-8", PAGE);
        }
        ("GET", "/favicon.ico") => {
            let _ = respond(&mut stream, 204, "text/plain", "");
        }
        ("GET", "/api/state") => {
            let body = state_json();
            let _ = respond(&mut stream, 200, "application/json", &body);
        }
        ("GET", "/api/processes") => {
            let body = processes_json();
            let _ = respond(&mut stream, 200, "application/json", &body);
        }
        ("POST", "/api/inject") => {
            let pid = extract_pid(&req.body);
            if pid == 0 {
                let _ = respond(
                    &mut stream,
                    400,
                    "application/json",
                    "{\"error\":\"missing pid\"}",
                );
                return;
            }
            if BUSY.swap(true, Ordering::SeqCst) {
                let _ = respond(
                    &mut stream,
                    409,
                    "application/json",
                    "{\"error\":\"an injection is already running\"}",
                );
                return;
            }
            {
                let mut st = state();
                st.phase = "injecting";
                st.pid = pid;
                st.panel_url.clear();
                st.panel_token.clear();
                st.version.clear();
                st.platform.clear();
                st.headline = format!("Injecting into PID {}", pid);
                for s in st.steps.iter_mut() {
                    *s = StepInfo::default();
                }
                st.events.clear();
            }
            push_event("info", format!("target PID {}", pid));
            std::thread::spawn(move || run_injection(pid));
            let _ = respond(&mut stream, 200, "application/json", "{\"ok\":true}");
        }
        ("POST", "/api/quit") => {
            let _ = respond(&mut stream, 200, "application/json", "{\"ok\":true}");
            request_exit();
        }
        _ => {
            let _ = respond(&mut stream, 404, "application/json", "{\"error\":\"not found\"}");
        }
    }
}

fn read_request(stream: &mut TcpStream) -> Option<Request> {
    let mut buf: Vec<u8> = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    let mut header_end = None;

    // Read until the end of the header block.
    while header_end.is_none() {
        let n = stream.read(&mut chunk).ok()?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
        header_end = find_headers_end(&buf);
        if buf.len() > 64 * 1024 {
            return None;
        }
    }
    let header_end = header_end?;
    let head = String::from_utf8_lossy(&buf[..header_end]).to_string();

    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or("");
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("/").to_string();

    let mut header_token = String::new();
    let mut content_length = 0usize;
    for line in lines {
        let Some((k, v)) = line.split_once(':') else { continue };
        let key = k.trim().to_ascii_lowercase();
        let val = v.trim();
        if key == "x-doppel-token" {
            header_token = val.to_string();
        } else if key == "content-length" {
            content_length = val.parse().unwrap_or(0);
        }
    }

    // Body (only for POST; we never send more than a few dozen bytes).
    let body_start = header_end + 4;
    let mut body = String::new();
    if content_length > 0 && content_length <= 64 * 1024 {
        while buf.len() < body_start + content_length {
            let n = stream.read(&mut chunk).ok()?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
        }
        let end = (body_start + content_length).min(buf.len());
        body = String::from_utf8_lossy(&buf[body_start..end]).to_string();
    }

    // Split path/query without holding a borrow of `target` across the move.
    let (path, query) = match target.find('?') {
        Some(i) => (target[..i].to_string(), target[i + 1..].to_string()),
        None => (target, String::new()),
    };

    Some(Request { method, path, query, header_token, body })
}

fn find_headers_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

fn respond(stream: &mut TcpStream, code: u16, ctype: &str, body: &str) -> std::io::Result<()> {
    let reason = match code {
        200 => "OK",
        204 => "No Content",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        409 => "Conflict",
        _ => "OK",
    };
    let head = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\n\
Cache-Control: no-store\r\nConnection: close\r\n\r\n",
        code,
        reason,
        ctype,
        body.as_bytes().len()
    );
    stream.write_all(head.as_bytes())?;
    stream.write_all(body.as_bytes())?;
    stream.flush()
}

fn extract_pid(json: &str) -> u32 {
    // Tiny hand-rolled reader — {"pid":1234}. No JSON dep in this crate.
    let Some(i) = json.find("\"pid\"") else { return 0 };
    let rest = &json[i + 5..];
    let digits: String = rest
        .trim_start()
        .trim_start_matches(':')
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse().unwrap_or(0)
}

// ── injection worker ────────────────────────────────────────────────────────

fn run_injection(pid: u32) {
    let rep = WebReporter::new();
    let result = flow::run(Some(pid), &rep);

    match result {
        Ok(outcome) => {
            {
                let mut st = state();
                st.phase = "done";
                st.pid = outcome.pid;
                st.version = outcome.version.clone();
                st.platform = outcome.platform.clone();
                st.panel_url = outcome.panel_url.clone().unwrap_or_default();
                st.panel_token = outcome.panel_token.clone();
                st.headline = "Injected — the agent is running inside the game".to_string();
            }
            push_event(
                "ok",
                format!("agent loaded into PID {} ({})", outcome.pid, outcome.version),
            );
        }
        Err(e) => {
            {
                let mut st = state();
                st.phase = "failed";
                st.headline = e.reason().to_string();
            }
            if matches!(e, FlowError::NoProcess) {
                push_event("error", "that process is gone — refresh the list".to_string());
            }
        }
    }
    BUSY.store(false, Ordering::SeqCst);
}

// ── JSON (hand-rolled; zero deps) ───────────────────────────────────────────

fn esc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn state_json() -> String {
    let st = state();
    let mut s = String::with_capacity(1024);
    s.push('{');
    s.push_str(&format!("\"phase\":\"{}\",", st.phase));
    s.push_str(&format!("\"headline\":\"{}\",", esc(&st.headline)));
    s.push_str(&format!("\"pid\":{},", st.pid));
    s.push_str(&format!("\"version\":\"{}\",", esc(&st.version)));
    s.push_str(&format!("\"platform\":\"{}\",", esc(&st.platform)));
    s.push_str(&format!("\"panel\":\"{}\",", esc(&st.panel_url)));
    s.push_str(&format!("\"panelToken\":\"{}\",", esc(&st.panel_token)));
    s.push_str(&format!("\"busy\":{},", BUSY.load(Ordering::SeqCst)));

    s.push_str("\"steps\":[");
    for (i, step) in st.steps.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        s.push_str(&format!(
            "{{\"label\":\"{}\",\"state\":\"{}\",\"note\":\"{}\",\"secs\":{:.2}}}",
            esc(STEPS.get(i).copied().unwrap_or("")),
            step.state.as_str(),
            esc(&step.note),
            step.secs
        ));
    }
    s.push_str("],");

    s.push_str("\"events\":[");
    for (i, (kind, text)) in st.events.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        s.push_str(&format!("{{\"kind\":\"{}\",\"text\":\"{}\"}}", kind, esc(text)));
    }
    s.push_str("]}");
    s
}

fn processes_json() -> String {
    let list = flow::candidates();
    let mut s = String::with_capacity(256 + list.len() * 256);
    s.push_str("{\"processes\":[");
    for (i, p) in list.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        s.push_str(&format!(
            "{{\"pid\":{},\"exe\":\"{}\",\"title\":\"{}\",\"path\":\"{}\",\
\"cmdline\":\"{}\",\"hint\":\"{}\",\"minecraft\":{},\"injected\":{}}}",
            p.pid,
            esc(&p.exe),
            esc(&p.window_title),
            esc(&p.path),
            esc(&shorten(&p.cmdline, 220)),
            esc(&p.cmdline_hint()),
            p.is_minecraft,
            p.injected
        ));
    }
    s.push_str("]}");
    s
}

fn shorten(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    let head: String = s.chars().take(max).collect();
    format!("{}…", head)
}

// ── token ───────────────────────────────────────────────────────────────────

fn splitmix64(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut z = x;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

/// 128 bits of hex, seeded from the clock, the pid and a stack address. This
/// only has to be unguessable to another process on the same machine for the
/// few minutes the injector is open — not a cryptographic secret.
fn make_token() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    let mut seed = nanos ^ ((std::process::id() as u64) << 32);
    let stack = &seed as *const u64 as u64;
    seed ^= stack.rotate_left(17);
    let mut out = String::with_capacity(32);
    for _ in 0..2 {
        seed = splitmix64(seed);
        out.push_str(&format!("{:016x}", seed));
    }
    out
}
