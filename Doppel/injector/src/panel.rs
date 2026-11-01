// panel.rs — the in-window module panel's data layer ("window GUI" choice).
//
// When the user picks the in-window GUI on the chooser, the launcher stops
// being a one-shot wizard and turns into a small native control panel: it
// talks to the SAME HTTP API the browser panel uses (adapter/common WebUIServer),
// just rendered with GDI instead of HTML.
//
//   GET  /api/modules              → [{name, category, enabled, keybind, keyName, …}]
//   POST /api/modules/{name}/toggle → {"name":…, "enabled":…}
//
// Everything here follows the injector's zero-third-party-deps rule: the HTTP
// client is a hand-rolled HTTP/1.1 conversation over `std::net::TcpStream`
// (the panel is plain http on loopback/LAN, no TLS), and the module JSON is
// read with a tiny shape-specific scanner instead of a serde stack.
//
// Threading: the poller lives in window.rs (it needs the shared UI state);
// this module only offers blocking request helpers the workers call, plus the
// published snapshot the UI thread reads.

use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::Duration;

// ── published state (read by the UI thread, written by the workers) ─────────

/// One row of the panel: exactly the fields the native screen renders.
#[derive(Clone)]
pub struct PanelModule {
    pub name: String,
    pub category: String,
    pub enabled: bool,
    pub key_name: String,
}

/// Latest poll snapshot + connection health, shared between threads.
pub struct PanelState {
    pub modules: Mutex<Vec<PanelModule>>,
    /// When the last successful poll finished (for the "synced 2 s ago" chip).
    pub last_ok: Mutex<Option<std::time::Instant>>,
    /// Last error text; empty = healthy.
    pub last_err: Mutex<String>,
}

static PANEL: OnceLock<PanelState> = OnceLock::new();

pub fn state() -> &'static PanelState {
    PANEL.get_or_init(|| PanelState {
        modules: Mutex::new(Vec::new()),
        last_ok: Mutex::new(None),
        last_err: Mutex::new(String::new()),
    })
}

pub fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// Set the module list in one shot (workers call this after a poll/toggle).
pub fn publish(list: Vec<PanelModule>) {
    *lock(&state().modules) = list;
}

// ── minimal HTTP/1.1 client ─────────────────────────────────────────────────

/// One request against the panel base URL ("http://127.0.0.1:4173").
/// Returns (status, body). Fails with a human-readable reason.
pub fn request(base: &str, method: &str, path: &str, token: &str, body: Option<&str>) -> Result<(u16, String), String> {
    let hostport = base
        .strip_prefix("http://")
        .unwrap_or(base)
        .split('/')
        .next()
        .unwrap_or("")
        .to_string();
    if hostport.is_empty() {
        return Err("no panel address".to_string());
    }
    let (host, port) = match hostport.rsplit_once(':') {
        Some((h, p)) => (h.trim_matches(|c| c == '[' || c == ']').to_string(), p.parse().unwrap_or(80u16)),
        None => (hostport.clone(), 80),
    };

    let addr = (host.as_str(), port)
        .to_socket_addrs()
        .map_err(|e| format!("resolve: {e}"))?
        .next()
        .ok_or_else(|| "no address".to_string())?;

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_millis(700))
        .map_err(|e| format!("connect {hostport}: {e}"))?;
    let _ = stream.set_read_timeout(Some(Duration::from_millis(2500)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(1500)));

    let body = body.unwrap_or("");
    let req = format!(
        "{method} {path} HTTP/1.1\r\nHost: {hostport}\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    use std::io::{Read, Write};
    stream.write_all(req.as_bytes()).map_err(|e| format!("send: {e}"))?;

    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).map_err(|e| format!("read: {e}"))?;
    let text = String::from_utf8_lossy(&raw).to_string();
    let (head, resp_body) = match text.split_once("\r\n\r\n") {
        Some((h, b)) => (h, b.to_string()),
        None => (text.as_str(), String::new()),
    };
    let status: u16 = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(0);
    Ok((status, resp_body))
}

/// GET /api/modules → parsed list.
pub fn fetch_modules(base: &str, token: &str) -> Result<Vec<PanelModule>, String> {
    let (code, body) = request(base, "GET", "/api/modules", token, None)?;
    if code != 200 {
        return Err(if code == 401 {
            "panel says 401 — token missing (see doppel-payload.log)".to_string()
        } else {
            format!("panel answered {code}")
        });
    }
    Ok(parse_modules(&body))
}

/// POST /api/modules/{name}/toggle → the module's new enabled state.
pub fn post_toggle(base: &str, token: &str, name: &str) -> Result<bool, String> {
    let path = format!("/api/modules/{name}/toggle");
    let (code, body) = request(base, "POST", &path, token, Some("{}"))?;
    if code != 200 {
        return Err(format!("toggle answered {code}"));
    }
    Ok(scan_bool(&body, "enabled").unwrap_or(false))
}

// ── tiny JSON reading (shape-specific, escape-safe) ─────────────────────────

/// Split a JSON array's top-level `{...}` objects, honouring strings/escapes.
fn split_objects(json: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut depth: i32 = 0;
    let mut in_str = false;
    let mut esc = false;
    let mut start: Option<usize> = None;
    for (i, c) in json.char_indices() {
        if esc {
            esc = false;
            continue;
        }
        match c {
            '\\' if in_str => esc = true,
            '"' => in_str = !in_str,
            '{' if !in_str => {
                if depth == 0 {
                    start = Some(i);
                }
                depth += 1;
            }
            '}' if !in_str => {
                depth -= 1;
                if depth == 0 {
                    if let Some(s) = start {
                        out.push(&json[s..=i]);
                    }
                    start = None;
                }
            }
            _ => {}
        }
    }
    out
}

/// Value of `"key":"…"` inside one object, with basic escape handling.
fn scan_string(obj: &str, key: &str) -> Option<String> {
    let pat = format!("\"{key}\":");
    let i = obj.find(&pat)? + pat.len();
    let rest = obj[i..].trim_start().strip_prefix('"')?;
    let mut out = String::new();
    let mut it = rest.chars();
    while let Some(c) = it.next() {
        match c {
            '"' => break,
            '\\' => match it.next() {
                Some('n') => out.push('\n'),
                Some('t') => out.push('\t'),
                Some('r') => out.push('\r'),
                Some('u') => {
                    let hex: String = it.by_ref().take(4).collect();
                    if let Ok(v) = u16::from_str_radix(&hex, 16) {
                        out.push(char::from_u32(v as u32).unwrap_or('\u{fffd}'));
                    }
                }
                Some(x) => out.push(x),
                None => break,
            },
            _ => out.push(c),
        }
    }
    Some(out)
}

/// Value of `"key":true|false` inside one object.
fn scan_bool(obj: &str, key: &str) -> Option<bool> {
    let pat = format!("\"{key}\":");
    let i = obj.find(&pat)? + pat.len();
    Some(obj[i..].trim_start().starts_with("true"))
}

/// The server shape is fixed (moduleJson in WebUIServer.kt): read the four
/// fields the native panel renders and ignore the rest (options, hud…).
pub fn parse_modules(json: &str) -> Vec<PanelModule> {
    split_objects(json)
        .into_iter()
        .map(|obj| PanelModule {
            name: scan_string(obj, "name").unwrap_or_default(),
            category: scan_string(obj, "category").unwrap_or_default(),
            enabled: scan_bool(obj, "enabled").unwrap_or(false),
            key_name: scan_string(obj, "keyName").unwrap_or_default(),
        })
        .filter(|m| !m.name.is_empty())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"[{"name":"AimAssist","category":"COMBAT","enabled":true,"keybind":-1,"keyName":"NONE","hudHidden":false,"hudShown":true,"redIndicator":false,"hudEnabled":false,"hudValue":null,"options":[]},{"name":"Sprint","category":"MOVEMENT","enabled":false,"keybind":82,"keyName":"R","hudHidden":false,"hudShown":true,"redIndicator":false,"hudEnabled":false,"hudValue":null,"options":[]}]"#;

    #[test]
    fn parses_the_server_shape() {
        let list = parse_modules(SAMPLE);
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "AimAssist");
        assert_eq!(list[0].category, "COMBAT");
        assert!(list[0].enabled);
        assert_eq!(list[0].key_name, "NONE");
        assert_eq!(list[1].name, "Sprint");
        assert!(!list[1].enabled);
        assert_eq!(list[1].key_name, "R");
    }

    #[test]
    fn survives_escapes_and_braces_in_strings() {
        let tricky = r#"[{"name":"Mod\"X","category":"VISUAL","enabled":false,"keybind":-1,"keyName":"{NONE}","options":[]}]"#;
        let list = parse_modules(tricky);
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].name, "Mod\"X");
        assert_eq!(list[0].key_name, "{NONE}");
    }

    #[test]
    fn empty_and_garbage_input_yield_empty() {
        assert!(parse_modules("").is_empty());
        assert!(parse_modules("not json at all").is_empty());
        assert!(parse_modules("[]").is_empty());
    }
}
