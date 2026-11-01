// panel.rs — the in-window module panel's data layer ("window GUI" choice).
//
// When the user picks the in-window GUI on the chooser, the launcher stops
// being a one-shot wizard and turns into a small native control panel: it
// talks to the SAME HTTP API the browser panel uses (adapter/common WebUIServer),
// just rendered with GDI instead of HTML.
//
//   GET  /api/modules                 → [{name, category, enabled, keyName, options[]}]
//   POST /api/modules/{name}/toggle   → {"name":…, "enabled":…}
//   POST /api/modules/{name}/keybind  → {"key": glfw-code} → {"keyName":…}
//   POST /api/options                 → {"key":"Module.Option","value":…}
//
// Everything here follows the injector's zero-third-party-deps rule: the HTTP
// client is a hand-rolled HTTP/1.1 conversation over `std::net::TcpStream`
// (the panel is plain http on loopback/LAN, no TLS), and the module JSON is
// read with a small recursive-descent parser below — the module objects now
// carry nested option arrays (and choice values can be objects), which the old
// shape-specific scanner could not walk.
//
// Threading: the poller lives in window.rs (it needs the shared UI state);
// this module only offers blocking request helpers the workers call, plus the
// published snapshot the UI thread reads.

use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::Duration;

// ── published state (read by the UI thread, written by the workers) ─────────

/// One tunable option, exactly the fields the native drawer renders.
#[derive(Clone)]
pub struct PanelOption {
    /// "ModuleName.OptionName" — the /api/options key.
    pub key: String,
    pub name: String,
    /// BOOLEAN / FLOAT / INT / PROBABILITY / CHOICES / ENUM / COLOR / STRING.
    pub type_name: String,
    pub value: Json,
    pub min: f64,
    pub max: f64,
    pub step: f64,
    pub decimals: i32,
    pub unit: String,
    pub choices: Vec<String>,
    /// Mode names this option applies to (empty = always).
    pub modes: Vec<String>,
}

impl PanelOption {
    /// The display label of the value: choice objects unwrap to their name,
    /// booleans render as on/off, everything else as its scalar.
    pub fn value_label(&self) -> String {
        match &self.value {
            Json::Str(s) => s.clone(),
            Json::Obj(pairs) => pairs
                .iter()
                .find(|(k, _)| k == "name")
                .and_then(|(_, v)| v.as_str())
                .unwrap_or("")
                .to_string(),
            Json::Bool(b) => if *b { "on".into() } else { "off".into() },
            Json::Num(n) => {
                if self.type_name == "COLOR" {
                    format!("#{:06X}", (*n as i64).clamp(0, 0xFF_FFFF) as u32)
                } else {
                    format!("{}", n)
                }
            }
            _ => String::new(),
        }
    }

    pub fn value_num(&self) -> f64 {
        match &self.value {
            Json::Num(n) => *n,
            _ => 0.0,
        }
    }

    pub fn value_bool(&self) -> bool {
        matches!(self.value, Json::Bool(true))
    }

    pub fn is_numeric(&self) -> bool {
        matches!(self.type_name.as_str(), "FLOAT" | "INT" | "PROBABILITY")
    }
}

/// One row of the panel: exactly the fields the native screen renders.
#[derive(Clone)]
pub struct PanelModule {
    pub name: String,
    pub category: String,
    pub enabled: bool,
    pub key_name: String,
    pub options: Vec<PanelOption>,
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
    let path = format!("/api/modules/{}/toggle", urlencode(name));
    let (code, body) = request(base, "POST", &path, token, Some("{}"))?;
    if code != 200 {
        return Err(format!("toggle answered {code}"));
    }
    Ok(parse_json(&body)
        .as_ref()
        .and_then(|j| j.get("enabled"))
        .and_then(|v| v.as_bool())
        .unwrap_or(false))
}

/// POST /api/modules/{name}/keybind {"key": code} → the server's keyName.
/// code -1 clears the bind (what Esc does on the web panel).
pub fn post_keybind(base: &str, token: &str, name: &str, code: i32) -> Result<String, String> {
    let path = format!("/api/modules/{}/keybind", urlencode(name));
    let body = format!("{{\"key\":{code}}}");
    let (code_status, resp) = request(base, "POST", &path, token, Some(&body))?;
    if code_status != 200 {
        return Err(format!("keybind answered {code_status}"));
    }
    Ok(parse_json(&resp)
        .as_ref()
        .and_then(|j| j.get("keyName"))
        .and_then(|v| v.as_str())
        .unwrap_or("NONE")
        .to_string())
}

/// POST /api/options {"key": …, "value": …} — `value_json` is the JSON literal
/// for the value ("true", "0.35", "\"Normal\"", "#RRGGBB" as int…).
pub fn post_option(base: &str, token: &str, key: &str, value_json: &str) -> Result<(), String> {
    let body = format!("{{\"key\":{},\"value\":{}}}", json_string(key), value_json);
    let (code, resp) = request(base, "POST", "/api/options", token, Some(&body))?;
    if code != 200 {
        let parsed = parse_json(&resp);
        let err = parsed
            .as_ref()
            .and_then(|j| j.get("error"))
            .and_then(|v| v.as_str())
            .unwrap_or("invalid value or unknown key")
            .to_string();
        return Err(err);
    }
    Ok(())
}

// ── JSON ────────────────────────────────────────────────────────────────────

/// The subset of JSON the panel needs. Objects keep their insertion order.
#[derive(Clone, Debug, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    pub fn get(&self, key: &str) -> Option<&Json> {
        match self {
            Json::Obj(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Json::Str(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Json::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Json::Num(n) => Some(*n),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[Json]> {
        match self {
            Json::Arr(a) => Some(a),
            _ => None,
        }
    }
}

struct JsonParser<'a> {
    s: &'a [u8],
    i: usize,
}

impl<'a> JsonParser<'a> {
    fn new(s: &'a str) -> Self {
        JsonParser { s: s.as_bytes(), i: 0 }
    }

    fn ws(&mut self) {
        while self.i < self.s.len() && matches!(self.s[self.i], b' ' | b'\t' | b'\n' | b'\r') {
            self.i += 1;
        }
    }

    fn peek(&self) -> Option<u8> {
        self.s.get(self.i).copied()
    }

    fn eat(&mut self, b: u8) -> bool {
        self.ws();
        if self.peek() == Some(b) {
            self.i += 1;
            true
        } else {
            false
        }
    }

    fn lit(&mut self, word: &str) -> bool {
        self.ws();
        if self.s[self.i..].starts_with(word.as_bytes()) {
            self.i += word.len();
            true
        } else {
            false
        }
    }

    fn value(&mut self, depth: u32) -> Option<Json> {
        if depth > 24 {
            return None;
        }
        self.ws();
        match self.peek()? {
            b'{' => {
                self.i += 1;
                let mut pairs = Vec::new();
                loop {
                    self.ws();
                    if self.eat(b'}') {
                        return Some(Json::Obj(pairs));
                    }
                    if !pairs.is_empty() && !self.eat(b',') {
                        return None;
                    }
                    self.ws();
                    if self.peek()? != b'"' {
                        return None;
                    }
                    let k = self.string()?;
                    if !self.eat(b':') {
                        return None;
                    }
                    let v = self.value(depth + 1)?;
                    pairs.push((k, v));
                }
            }
            b'[' => {
                self.i += 1;
                let mut items = Vec::new();
                loop {
                    self.ws();
                    if self.eat(b']') {
                        return Some(Json::Arr(items));
                    }
                    if !items.is_empty() && !self.eat(b',') {
                        return None;
                    }
                    items.push(self.value(depth + 1)?);
                }
            }
            b'"' => Some(Json::Str(self.string()?)),
            b't' => self.lit("true").then_some(Json::Bool(true)),
            b'f' => self.lit("false").then_some(Json::Bool(false)),
            b'n' => self.lit("null").then_some(Json::Null),
            _ => self.number().map(Json::Num),
        }
    }

    fn string(&mut self) -> Option<String> {
        if !self.eat(b'"') {
            return None;
        }
        let mut out = String::new();
        loop {
            let c = *self.s.get(self.i)?;
            self.i += 1;
            match c {
                b'"' => return Some(out),
                b'\\' => {
                    let e = *self.s.get(self.i)?;
                    self.i += 1;
                    match e {
                        b'n' => out.push('\n'),
                        b't' => out.push('\t'),
                        b'r' => out.push('\r'),
                        b'b' => out.push('\u{8}'),
                        b'f' => out.push('\u{c}'),
                        b'u' => {
                            if self.i + 4 > self.s.len() {
                                return None;
                            }
                            let hex = std::str::from_utf8(&self.s[self.i..self.i + 4]).ok()?;
                            self.i += 4;
                            let mut cp = u32::from_str_radix(hex, 16).ok()?;
                            // Surrogate pair — the API never emits one today,
                            // but be kind and join it if present.
                            if (0xD800..0xDC00).contains(&cp)
                                && self.s.get(self.i) == Some(&b'\\')
                                && self.s.get(self.i + 1) == Some(&b'u')
                            {
                                let hex2 = std::str::from_utf8(&self.s[self.i + 2..self.i + 6]).ok()?;
                                if let Ok(lo) = u32::from_str_radix(hex2, 16) {
                                    if (0xDC00..0xE000).contains(&lo) {
                                        self.i += 6;
                                        cp = 0x1_0000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                    }
                                }
                            }
                            out.push(char::from_u32(cp).unwrap_or('\u{fffd}'));
                        }
                        other => out.push(other as char),
                    }
                }
                other => {
                    // Copy the whole UTF-8 sequence byte-for-byte.
                    if other < 0x80 {
                        out.push(other as char);
                    } else {
                        let start = self.i - 1;
                        let len = if other >= 0xF0 {
                            4
                        } else if other >= 0xE0 {
                            3
                        } else {
                            2
                        };
                        self.i = start + len;
                        if self.i > self.s.len() {
                            return None;
                        }
                        out.push_str(std::str::from_utf8(&self.s[start..self.i]).ok()?);
                    }
                }
            }
        }
    }

    fn number(&mut self) -> Option<f64> {
        self.ws();
        let start = self.i;
        if self.peek() == Some(b'-') || self.peek() == Some(b'+') {
            self.i += 1;
        }
        while let Some(c) = self.peek() {
            if c.is_ascii_digit() || c == b'.' || c == b'e' || c == b'E' {
                self.i += 1;
                if matches!(c, b'e' | b'E') && matches!(self.peek(), Some(b'+') | Some(b'-')) {
                    self.i += 1;
                }
            } else {
                break;
            }
        }
        if start == self.i {
            return None;
        }
        std::str::from_utf8(&self.s[start..self.i]).ok()?.parse().ok()
    }
}

pub fn parse_json(text: &str) -> Option<Json> {
    let mut p = JsonParser::new(text);
    let v = p.value(0)?;
    Some(v)
}

/// Escape a Rust string into a JSON string literal (no surrounding quotes).
pub fn json_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
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
    out.push('"');
    out
}

/// Percent-encode a module name for a URL path segment (spaces, +, % …).
fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

// ── /api/modules shape → PanelModule ────────────────────────────────────────

fn option_from(j: &Json) -> Option<PanelOption> {
    let key = j.get("key")?.as_str()?.to_string();
    let name = j.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let type_name = j.get("type").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let num = |k: &str| j.get(k).and_then(|v| v.as_f64()).unwrap_or(0.0);
    let choices = j
        .get("choices")
        .and_then(|v| v.as_array())
        .map(|a| a.iter().filter_map(|c| c.as_str().map(|s| s.to_string())).collect())
        .unwrap_or_default();
    let modes = j
        .get("modes")
        .and_then(|v| v.as_array())
        .map(|a| a.iter().filter_map(|c| c.as_str().map(|s| s.to_string())).collect())
        .unwrap_or_default();
    Some(PanelOption {
        key,
        name,
        type_name,
        value: j.get("value").cloned().unwrap_or(Json::Null),
        min: num("min"),
        max: num("max"),
        step: {
            let s = num("step");
            if s > 0.0 { s } else { 1.0 }
        },
        decimals: j
            .get("decimals")
            .and_then(|v| v.as_f64())
            .map(|d| d as i32)
            .unwrap_or(-1),
        unit: j.get("unit").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        choices,
        modes,
    })
}

pub fn parse_modules(text: &str) -> Vec<PanelModule> {
    let Some(Json::Arr(items)) = parse_json(text) else { return Vec::new() };
    items
        .iter()
        .filter_map(|m| {
            let name = m.get("name")?.as_str()?.to_string();
            let category = m.get("category").and_then(|v| v.as_str()).unwrap_or("MISC").to_string();
            let enabled = m.get("enabled").and_then(|v| v.as_bool()).unwrap_or(false);
            let key_name = m.get("keyName").and_then(|v| v.as_str()).unwrap_or("NONE").to_string();
            let options = m
                .get("options")
                .and_then(|v| v.as_array())
                .map(|a| a.iter().filter_map(option_from).collect())
                .unwrap_or_default();
            Some(PanelModule { name, category, enabled, key_name, options })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"[{"name":"AimAssist","category":"COMBAT","enabled":true,"keybind":-1,"keyName":"NONE","hudHidden":false,"hudShown":true,"redIndicator":false,"hudEnabled":false,"hudValue":null,"options":[{"key":"AimAssist.Mode","name":"Mode","type":"CHOICES","value":{"name":"Normal"},"modes":null,"min":null,"max":null,"unit":null,"step":null,"decimals":null,"choices":["Normal","Legit"]},{"key":"AimAssist.Range","name":"Range","type":"FLOAT","value":4.5,"modes":null,"min":3.0,"max":6.0,"unit":"blocks","step":0.1,"decimals":1},{"key":"AimAssist.Flag","name":"Flag","type":"BOOLEAN","value":false,"modes":null,"min":null,"max":null,"unit":null,"step":null,"decimals":null,"choices":null}]},{"name":"Sprint","category":"MOVEMENT","enabled":false,"keybind":82,"keyName":"R","hudHidden":false,"hudShown":true,"redIndicator":false,"hudEnabled":false,"hudValue":null,"options":[]}]"#;

    #[test]
    fn parses_the_server_shape() {
        let list = parse_modules(SAMPLE);
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "AimAssist");
        assert_eq!(list[0].category, "COMBAT");
        assert!(list[0].enabled);
        assert_eq!(list[0].key_name, "NONE");
        assert_eq!(list[1].key_name, "R");
        assert!(!list[1].enabled);
        assert_eq!(list[1].options.len(), 0);

        let opts = &list[0].options;
        assert_eq!(opts.len(), 3);
        assert_eq!(opts[0].type_name, "CHOICES");
        assert_eq!(opts[0].value_label(), "Normal");
        assert_eq!(opts[0].choices, vec!["Normal", "Legit"]);
        assert_eq!(opts[1].type_name, "FLOAT");
        assert!((opts[1].value_num() - 4.5).abs() < 1e-9);
        assert!((opts[1].min - 3.0).abs() < 1e-9);
        assert_eq!(opts[1].unit, "blocks");
        assert!((opts[1].step - 0.1).abs() < 1e-9);
        assert_eq!(opts[1].decimals, 1);
        assert_eq!(opts[2].type_name, "BOOLEAN");
        assert!(!opts[2].value_bool());
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

    #[test]
    fn json_values_round_trip() {
        assert_eq!(parse_json("true"), Some(Json::Bool(true)));
        assert_eq!(parse_json(" 3.25 "), Some(Json::Num(3.25)));
        assert_eq!(parse_json("\"a\\nb\""), Some(Json::Str("a\nb".into())));
        let obj = parse_json("{\"a\":[1,{\"b\":\"c\"}],\"d\":null}").unwrap();
        assert!(obj.get("a").unwrap().as_array().unwrap().len() == 2);
        assert!(matches!(obj.get("d"), Some(Json::Null)));
    }

    #[test]
    fn json_string_escapes() {
        assert_eq!(json_string("A\"B\\C"), "\"A\\\"B\\\\C\"");
    }

    #[test]
    fn urlencode_spaces_and_plus() {
        assert_eq!(urlencode("Auto Block"), "Auto%20Block");
        assert_eq!(urlencode("A+B"), "A%2BB");
    }
}
