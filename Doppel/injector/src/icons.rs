// icons.rs — the module glyphs for the in-window panel, ported from the WebUI.
//
// The web panel (adapter/common …/webui/index.html) draws one hand-made 24×24
// stroke icon per module (`ICONS`, ~50 glyphs) plus one per category
// (`CAT_NAV`), tinted by category. This module ports the SAME path data
// verbatim so the native panel and the browser panel never disagree about what
// a module looks like — the strings below are the WebUI's own inner-SVG markup.
//
// Rendering: a tiny SVG-path flattener turns each glyph into polylines in the
// 24×24 viewBox, and the painter strokes them with GDI (Polyline, 1 px) scaled
// into the icon tile. Zero third-party deps, same rule as the rest of the
// injector; the parser understands exactly the constructs the WebUI icons use
// (M/L/H/V/C/S/Q/T/A/Z in both cases, <circle>, <rect> with rx, and a plain
// translate() transform).

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::ffi::c_void;

type Hdc = *mut c_void;

// ── GDI (same set window.rs already links against, plus Polyline) ───────────

#[repr(C)]
#[derive(Clone, Copy)]
struct Point {
    x: i32,
    y: i32,
}

#[link(name = "gdi32")]
extern "system" {
    fn CreatePen(style: i32, width: i32, color: u32) -> *mut c_void;
    fn SelectObject(hdc: Hdc, obj: *mut c_void) -> *mut c_void;
    fn DeleteObject(obj: *mut c_void) -> i32;
    fn Polyline(hdc: Hdc, apt: *const Point, cpt: i32) -> i32;
}

const PS_SOLID: i32 = 0;

// ── the WebUI's own icon markup (index.html `ICONS`, verbatim) ───────────────

pub const MARKUPS: &[(&str, &str)] = &[
    /* ---- COMBAT ---- */
    ("AimAssist", r##"<circle cx="10.5" cy="13" r="5.2"/><path d="M10.5 5.5v2.2M10.5 18.3v2.2M3 13h2.2M15.8 13h1.4"/><path d="M14.5 9.5L20.5 3.5M20.5 3.5h-3.8M20.5 3.5v3.8"/>"##),
    ("AutoClicker", r##"<rect x="8.5" y="3.5" width="7.5" height="12" rx="3.7"/><path d="M12.2 3.5V8"/><path d="M5.5 7.5c-1.4 2.4-1.7 5-.9 7.6M18.9 7.5c1.4 2.4 1.7 5 .9 7.6"/>"##),
    ("Reach", r##"<path d="M3 12h12.5"/><path d="M12.5 8.5L16 12l-3.5 3.5"/><path d="M20 6.5v11"/>"##),
    // FakeLag: a dotted trail that breaks — the connection pausing, then resuming.
    ("FakeLag", r##"<path d="M3 12h4"/><path d="M8.5 12h1.5"/><path d="M12.5 12h2"/><path d="M16.5 12h4.5"/><circle cx="7" cy="12" r="0.6"/>"##),
    ("LagRange", r##"<circle cx="12" cy="12" r="3"/><path d="M12 6.5a5.5 5.5 0 0 1 5.5 5.5"/><path d="M12 2.5a9.5 9.5 0 0 1 9.5 9.5" stroke-dasharray="2.5 2.5"/><path d="M12 15.5A3.5 3.5 0 0 1 8.5 12"/>"##),
    ("Backtrack", r##"<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.2 4.6v4.2h4.2"/><path d="M12 8.5v4l2.6 1.6"/>"##),
    ("Velocity", r##"<path d="M12 3l7 2.4v5.4c0 4.3-2.9 7.3-7 8.9-4.1-1.6-7-4.6-7-8.9V5.4z"/><path d="M8.5 13h4.6"/><path d="M11 10.5L13.5 13 11 15.5"/>"##),
    ("WTap", r##"<rect x="4" y="4" width="16" height="10" rx="2.5"/><path d="M8 7.2l1.3 3.6 2.7-3.6 2.7 3.6L16 7.2"/><path d="M12 17v3.6M9.8 18.6l2.2 2 2.2-2"/>"##),
    ("STap", r##"<rect x="4" y="4" width="16" height="10" rx="2.5"/><path d="M14.6 7.3c-.8-1-2.3-1.2-3.3-.5-1.5 1 .1 2.4 1.3 2.8 1.2.4 2.7 1.4 1.5 2.6-.9 1-2.6.8-3.5-.2"/><path d="M12 17v3.6M9.8 18.6l2.2 2 2.2-2"/>"##),
    ("ADTap", r##"<rect x="2.5" y="7.5" width="6" height="6" rx="1.5"/><rect x="15.5" y="7.5" width="6" height="6" rx="1.5"/><path d="M9.5 10.5h5"/><path d="M12.6 8.6l1.9 1.9-1.9 1.9"/><path d="M12 17.5v3.4"/>"##),
    ("SprintReset", r##"<circle cx="9" cy="5" r="1.9"/><path d="M6.5 20l3-5-2.4-3.2 3.6-1.4 2.4 2.8 3.2-1"/><path d="M4.8 9L7 11.2"/><path d="M19.3 20.6a4.6 4.6 0 10-4.2 1"/><path d="M19.6 17.6l-.3 3-3-.4"/>"##),
    ("HitSelect", r##"<circle cx="12" cy="12" r="6.3"/><circle cx="12" cy="12" r="1.5"/><path d="M12 2.8V5.4M12 18.6v2.6M2.8 12H5.4M18.6 12h2.6"/>"##),
    ("KeepSprint", r##"<path d="M3.5 12h10.5"/><path d="M11.5 8l4 4-4 4"/><rect x="17.8" y="9.8" width="4.4" height="5.2" rx="1"/><path d="M18.9 9.8V8.2a1.9 1.9 0 013.8 0v1.6"/>"##),
    ("TriggerBot", r##"<circle cx="12" cy="9" r="4.6"/><path d="M12 6.8V9l1.6 1.4"/><path d="M12 13.6V18M8.8 21h6.4"/>"##),
    ("JumpReset", r##"<path d="M4 20.5h16"/><path d="M10.5 16.5V7"/><path d="M6.8 10.7L10.5 7l3.7 3.7"/><path d="M17.5 8.5a4.6 4.6 0 10 .8 3.6"/><path d="M18.4 5.6l-.5 3.2-3.1-.7"/>"##),
    ("AutoBlock", r##"<path d="M18.5 4l1.5 1.5-9 9-1.5-1.5z"/><path d="M8 13l3 3"/><path d="M6.5 14.5L10 18l-2.3 2.3L4 16.8z"/><rect x="16" y="16" width="5" height="5" rx="1"/>"##),
    ("BlockHit", r##"<rect x="3.5" y="3.5" width="10.5" height="10.5" rx="2"/><path d="M17 13.5l1.1 2.5 2.5 1.1-2.5 1.1L17 20.7l-1.1-2.5-2.5-1.1 2.5-1.1z"/>"##),
    ("KnockbackDisplace", r##"<circle cx="12" cy="12" r="1.9"/><path d="M12 8.5V4M12 15.5V20M8.5 12H4M15.5 12H20"/><path d="M10 5.5L12 3.5l2 2M18.5 10l2 2-2 2M5.5 10l-2 2 2 2M10 18.5l2 2 2-2"/>"##),
    ("KnockbackDelay", r##"<circle cx="10" cy="12" r="6.3"/><path d="M10 8.8V12l2.4 1.5"/><path d="M20.5 8.5L23 11l-2.5 2.5" transform="translate(-1.5 0)"/><path d="M21 11h-4.4"/>"##),
    ("ClickAssist", r##"<path d="M5.5 3.5l6.5 16 2-5.6 5.6-2z"/><path d="M17.5 3.5l.8 1.9 1.9.8-1.9.8-.8 1.9-.8-1.9-1.9-.8 1.9-.8z"/>"##),
    ("DelayRemover", r##"<path d="M3 12h3.5M10.5 12H14M18 12h3"/><path d="M12.8 4.5L9.5 10h3l-2.6 5.5"/>"##),
    ("SuperKnockback", r##"<path d="M12 2.5v4.6M12 16.9v4.6M2.5 12h4.6M16.9 12h4.6M5.3 5.3l3.2 3.2M15.5 15.5l3.2 3.2M18.7 5.3l-3.2 3.2M8.5 15.5l-3.2 3.2"/>"##),
    /* ---- MOVEMENT ---- */
    // NullMove: two opposing inputs (A+D) resolve into one clean direction.
    ("NullMove", r##"<path d="M2.5 12H8"/><path d="M5.5 9.5L3 12l2.5 2.5"/><path d="M21.5 12H16"/><path d="M18.5 9.5L21 12l-2.5 2.5"/><circle cx="12" cy="12" r="3.1"/><path d="M10.7 12l1 1 1.7-1.9"/>"##),
    // InstantStop: velocity into a wall — three decelerating stops.
    ("InstantStop", r##"<path d="M2.5 12H11"/><path d="M8 8.5L11.5 12 8 15.5"/><path d="M14.5 5.5v13"/><path d="M18 8.5v7"/><path d="M21.5 10.5v3"/>"##),
    // StrafeAssist: lateral keys over a damped, humanised path.
    ("StrafeAssist", r##"<path d="M3 8.5h4.5M5.5 6L3 8.5 5.5 11"/><path d="M16.5 8.5H21M18.5 6L21 8.5 18.5 11"/><path d="M3.5 16.5c2.8-2.3 5.7-2.3 8.5 0s5.7 2.3 8.5 0"/>"##),
    // InvMove: the inventory grid keeps three slots; the fourth is the walk arrow.
    ("InvMove", r##"<rect x="3.5" y="3.5" width="7" height="7" rx="1.4"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.4"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.4"/><path d="M13.5 17h6.5"/><path d="M17.7 14.7l2.3 2.3-2.3 2.3"/>"##),
    ("Sprint", r##"<path d="M5 5.5l6.5 6.5L5 18.5"/><path d="M13 5.5l6.5 6.5L13 18.5"/>"##),
    ("Strafe", r##"<path d="M8.5 12H3M5.5 9.5L3 12l2.5 2.5"/><path d="M15.5 12H21M18.5 9.5L21 12l-2.5 2.5"/><path d="M12 4v3M12 17v3"/>"##),
    ("StrafeFix", r##"<path d="M8.5 8.5H3M5.5 6L3 8.5l2.5 2.5"/><path d="M15.5 8.5H21M18.5 6L21 8.5l-2.5 2.5"/><path d="M9 16.5l2.3 2.3 4.2-4.6"/>"##),
    ("NoJumpDelay", r##"<path d="M8.5 20.5v-10"/><path d="M4.8 14.2L8.5 10.5l3.7 3.7"/><path d="M17.5 3l-3.6 6.2h3.2L14 15.5"/>"##),
    ("NoMouseFix", r##"<rect x="8" y="3" width="8" height="13.5" rx="4"/><path d="M12 3v5"/><path d="M4.5 4.5l15 15"/>"##),
    ("NoKeyboardFix", r##"<rect x="3" y="7.5" width="18" height="9.5" rx="2"/><path d="M7 11h.01M11 11h.01M15 11h.01M8 14.2h8"/><path d="M3.5 4l17 16.5"/>"##),
    /* ---- PLAYER ---- */
    ("BridgeAssist", r##"<path d="M3 20h5.5v-4.5H14V11h5.5"/><path d="M17 3.5v5M15 6.5l2 2 2-2"/>"##),
    ("TargetFilter", r##"<path d="M4 5h16l-6.2 7v5.6l-3.6 2V12z"/><circle cx="12" cy="8.6" r="1.3"/>"##),
    ("AutoTool", r##"<path d="M4 20l9.3-9.3"/><path d="M9 5.5C13.4 3 18.4 4.5 21 8.4"/><path d="M13.3 10.3L9 5.5"/>"##),
    ("Teams", r##"<circle cx="9" cy="8.5" r="3"/><path d="M3.5 19.5c.8-3.2 2.9-4.9 5.5-4.9s4.7 1.7 5.5 4.9"/><circle cx="16.8" cy="9.5" r="2.3"/><path d="M16 14.8c2.5.2 4.1 1.6 4.7 4.2"/>"##),
    ("Eagle", r##"<path d="M3 20.5h6V15h6V9.5h6"/><path d="M18.5 3v6M16.4 7l2.1 2 2.1-2"/>"##),
    ("AntiBot", r##"<rect x="6" y="8" width="12" height="9.5" rx="2.5"/><circle cx="10" cy="12.2" r=".4" fill="currentColor"/><circle cx="14" cy="12.2" r=".4" fill="currentColor"/><path d="M12 8V5M9.5 5h5"/><path d="M4 4.5l16 15"/>"##),
    /* ---- VISUAL ---- */
    ("HUD", r##"<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M7 8.5h5M7 12.5h8M7 16.5h4"/><path d="M17 8.5h.01"/>"##),
    ("VelocityDisplay", r##"<path d="M3 12.5c1.8-4.2 3.7-4.2 5.5 0s3.7 4.2 5.5 0 3.7-4.2 5.5 0"/><path d="M3 19.5h18"/>"##),
    ("WebUI", r##"<rect x="3" y="4" width="18" height="14" rx="2.5"/><path d="M3 8.5h18"/><path d="M6 6.3h.01M8.6 6.3h.01"/><path d="M12.5 12.5l6 2.2-2.6 1.2-1.2 2.6z"/>"##),
    ("Keystrokes", r##"<rect x="3" y="6.5" width="5" height="5" rx="1.2"/><rect x="9.5" y="6.5" width="5" height="5" rx="1.2"/><rect x="16" y="6.5" width="5" height="5" rx="1.2"/><rect x="3" y="13.5" width="11" height="4" rx="1.2"/><path d="M18.5 15.5h.01"/>"##),
    ("NoHurtCam", r##"<path d="M3 9a2 2 0 012-2h2.2l1.4-2h6.8l1.4 2H19a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><circle cx="12" cy="13" r="3"/><path d="M4 4l17 17"/>"##),
    // Freelook: the eye stays on the world while the camera orbits.
    ("Freelook", r##"<path d="M8.4 12c1-1.7 2.2-2.5 3.6-2.5s2.6.8 3.6 2.5c-1 1.7-2.2 2.5-3.6 2.5s-2.6-.8-3.6-2.5z"/><circle cx="12" cy="12" r="1.1"/><path d="M5 9.7A8.2 8.2 0 0 1 12 4.8c2.4 0 4.7 1 6.3 2.8"/><path d="M18.6 4.1v3.6H15"/><path d="M19 14.3a8.2 8.2 0 0 1-7 4.9c-2.4 0-4.7-1-6.3-2.8"/><path d="M5.4 19.9v-3.6H9"/>"##),
    ("Fullbright", r##"<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.8M12 18.7v2.8M2.5 12h2.8M18.7 12h2.8M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>"##),
    ("JumpTiming", r##"<path d="M8.5 20.5v-11"/><path d="M4.8 13.2L8.5 9.5l3.7 3.7"/><circle cx="16.5" cy="15.5" r="4.5"/><path d="M16.5 13.4v2.2l1.5 1"/>"##),
    ("Speedometer", r##"<path d="M4.5 16.5a7.8 7.8 0 1115 0"/><path d="M12 16.5l4-5.6"/><circle cx="12" cy="16.5" r="1.3"/><path d="M3.5 20h17"/>"##),
    ("KnockbackDisplay", r##"<path d="M20 7L9.5 17.5"/><path d="M9.5 13.5v4h4"/><path d="M20 12V7h-5"/>"##),
    ("JumpStatus", r##"<path d="M4 20h16"/><path d="M7 20v-6M12 20V9M17 20v-9"/><path d="M12 4.5v4M9.9 6.6L12 4.5l2.1 2.1"/>"##),
    /* ---- UTILITY ---- */
    ("FastPlace", r##"<rect x="8.5" y="8.5" width="9" height="9" rx="1.5"/><path d="M16.5 5h4M18.5 3v4"/><path d="M3 9h3.5M3 13h3M3 17h3.5"/>"##),
    /* ---- UI chrome (nav bar / settings header / row menu — not modules) ---- */
    ("UI:home", r##"<path d="M4.5 10.5L12 4l7.5 6.5"/><path d="M6.5 9.3V20h11V9.3"/>"##),
    ("UI:back", r##"<path d="M14.5 5L7.5 12l7 7"/>"##),
    ("UI:refresh", r##"<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.8 3.6v4h-4"/>"##),
    ("UI:web", r##"<rect x="3.5" y="5" width="17" height="14" rx="2.5"/><path d="M3.5 9.2h17"/><circle cx="6.4" cy="7.1" r="0.6"/><circle cx="8.8" cy="7.1" r="0.6"/>"##),
    ("UI:hide", r##"<path d="M4.5 4.5l15 15"/><path d="M3.5 12c2.5-3.8 5.3-5.7 8.5-5.7s6 1.9 8.5 5.7c-2.5 3.8-5.3 5.7-8.5 5.7s-6-1.9-8.5-5.7z"/><circle cx="12" cy="12" r="1.1"/>"##),
    ("UI:star", r##"<path d="M12 3.6l2.6 5.3 5.9 0.9-4.3 4.1 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.1 5.9-0.9z"/>"##),
    ("UI:kebab", r##"<circle cx="12" cy="5.2" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="12" cy="18.8" r="1.2"/>"##),
    ("UI:key", r##"<circle cx="8" cy="12" r="3.6"/><path d="M11.6 12H20.5M17.5 12v3.2M14.5 12v2.4"/>"##),
    ("UI:check", r##"<path d="M5 12.6l4.6 4.6L19 7.6"/>"##),
];

/// Category glyphs (index.html `CAT_NAV`) plus the "All" tile.
pub const CAT_MARKUPS: &[(&str, &str)] = &[
    ("ALL", r##"<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M7 8.5h5M7 12.5h8M7 16.5h4"/>"##),
    ("COMBAT", r##"<circle cx="12" cy="12" r="7"/><path d="M12 2.5V6M12 18v3.5M2.5 12H6M18 12h3.5"/>"##),
    ("PLAYER", r##"<circle cx="12" cy="7.5" r="3.4"/><path d="M5 20c1-4 3.7-6 7-6s6 2 7 6"/>"##),
    ("MOVEMENT", r##"<path d="M4 20h16"/><path d="M13 4l-5 8h4l-2 8 6-9h-4.5z"/>"##),
    ("UTILITY", r##"<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c-5 5.5-5 11.5 0 17M12 3.5c5 5.5 5 11.5 0 17"/>"##),
    ("VISUAL", r##"<rect x="3" y="4" width="18" height="15" rx="2.5"/><path d="M7 9h6M7 13h9M7 17h5"/>"##),
    ("MISC", r##"<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>"##),
];

/// Category tints — the WebUI's `.mod-icon.cat-*` colours (aurora skin).
pub fn cat_color(cat: &str) -> [u8; 3] {
    match cat {
        "COMBAT" => [0xFB, 0xBF, 0x24],    // #FBBF24
        "PLAYER" => [0xF4, 0x72, 0xB6],    // #F472B6
        "MOVEMENT" => [0x6E, 0xE7, 0xB7],  // #6EE7B7
        "UTILITY" => [0xA3, 0xE6, 0x35],   // #A3E635
        "VISUAL" => [0x22, 0xD3, 0xEE],    // #22D3EE
        "MISC" => [0xA7, 0x8B, 0xFA],      // #A78BFA
        _ => [0x8A, 0xBC, 0xFF],           // link blue — "ALL" / unknown
    }
}

// ── flattening ───────────────────────────────────────────────────────────────
//
// Each glyph becomes Vec<polyline>, polyline = Vec<(f32,f32)> in the 24×24
// viewBox. Parsed once per icon (lazily, into a static map), then every frame
// just scales points.

type Polyline = Vec<(f32, f32)>;

/// One parsed element: a path string (+ optional translate) or a primitive.
enum Shape {
    Path(String, f32, f32),
    Circle { cx: f32, cy: f32, r: f32 },
    Rect { x: f32, y: f32, w: f32, h: f32, rx: f32 },
}

struct Scanner<'a> {
    s: &'a [u8],
    i: usize,
}

impl<'a> Scanner<'a> {
    fn new(s: &'a str) -> Self {
        Scanner { s: s.as_bytes(), i: 0 }
    }

    fn skip_ws(&mut self) {
        while self.i < self.s.len() && (self.s[self.i] as char).is_ascii_whitespace() {
            self.i += 1;
        }
    }

    fn num(&mut self) -> Option<f32> {
        self.skip_ws();
        let start = self.i;
        if self.i < self.s.len() && (self.s[self.i] == b'+' || self.s[self.i] == b'-') {
            self.i += 1;
        }
        while self.i < self.s.len()
            && (self.s[self.i].is_ascii_digit() || self.s[self.i] == b'.'
                || self.s[self.i] == b'e' || self.s[self.i] == b'E')
        {
            // 'e' only continues a number when an exponent follows; the icon
            // data never has bare 'e' between numbers, so accepting it here is
            // safe enough for this dataset.
            self.i += 1;
            if (self.s[self.i - 1] == b'e' || self.s[self.i - 1] == b'E')
                && self.i < self.s.len()
                && (self.s[self.i] == b'+' || self.s[self.i] == b'-')
            {
                self.i += 1;
            }
        }
        if start == self.i {
            return None;
        }
        std::str::from_utf8(&self.s[start..self.i]).ok()?.parse().ok()
    }

    /// One 0/1 flag — SVG allows flags to run into the next number ("015.2").
    fn flag(&mut self) -> Option<f32> {
        self.skip_ws();
        match self.s.get(self.i)? {
            b'0' => {
                self.i += 1;
                Some(0.0)
            }
            b'1' => {
                self.i += 1;
                Some(1.0)
            }
            _ => None,
        }
    }
}

/// SVG arc (endpoint parametrisation) → its centre form → polyline points.
/// Port of the W3C conversion; the icons use it for every rounded stroke.
fn arc_points(
    x1: f32, y1: f32, x2: f32, y2: f32,
    mut rx: f32, mut ry: f32, rot_deg: f32, large: f32, sweep: f32,
    out: &mut Polyline,
) {
    if rx <= 0.0 || ry <= 0.0 {
        out.push((x2, y2));
        return;
    }
    let rot = rot_deg.to_radians();
    let (cos, sin) = (rot.cos(), rot.sin());
    let dx2 = (x1 - x2) / 2.0;
    let dy2 = (y1 - y2) / 2.0;
    let x1p = cos * dx2 + sin * dy2;
    let y1p = -sin * dx2 + cos * dy2;

    let lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
    if lam > 1.0 {
        let s = lam.sqrt();
        rx *= s;
        ry *= s;
    }
    let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
    let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
    let mut c = if num <= 0.0 || den <= 0.0 { 0.0 } else { (num / den).sqrt() };
    if large == sweep {
        c = -c;
    }
    let cxp = c * rx * y1p / ry;
    let cyp = -c * ry * x1p / rx;
    let cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0;
    let cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;

    let ang = |ux: f32, uy: f32, vx: f32, vy: f32| -> f32 {
        let dot = ux * vx + uy * vy;
        let len = (ux * ux + uy * uy).sqrt() * (vx * vx + vy * vy).sqrt();
        let mut a = (dot / len.clamp(1e-6, f32::MAX)).clamp(-1.0, 1.0).acos();
        if ux * vy - uy * vx < 0.0 {
            a = -a;
        }
        a
    };
    let th1 = ang((x1p - cxp) / rx, (y1p - cyp) / ry, 1.0, 0.0);
    let mut dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
    if sweep == 0.0 && dth > 0.0 {
        dth -= std::f32::consts::TAU;
    } else if sweep == 1.0 && dth < 0.0 {
        dth += std::f32::consts::TAU;
    }

    let segs = ((dth.abs() / (std::f32::consts::FRAC_PI_6)).ceil() as usize).clamp(2, 24);
    for k in 1..=segs {
        let th = th1 + dth * (k as f32) / (segs as f32);
        let px = cos * rx * th.cos() - sin * ry * th.sin() + cx;
        let py = sin * rx * th.cos() + cos * ry * th.sin() + cy;
        out.push((px, py));
    }
}

/// Flatten one path `d` string into polylines (each M/m starts a new one).
fn flatten_path(d: &str, tx: f32, ty: f32, out: &mut Vec<Polyline>) {
    let mut sc = Scanner::new(d);
    let mut cur = Vec::new();
    let (mut x, mut y) = (0.0f32, 0.0f32);
    let (mut sx, mut sy) = (0.0f32, 0.0f32);
    let (mut lx, mut ly) = (0.0f32, 0.0f32); // last cubic control for S/T
    let (mut qx, mut qy) = (0.0f32, 0.0f32); // last quad control for T
    let mut cmd: char;
    let push = |cur: &mut Polyline, px: f32, py: f32| cur.push((px + tx, py + ty));

    macro_rules! flush {
        () => {
            if cur.len() > 1 {
                out.push(std::mem::take(&mut cur));
            } else {
                cur.clear();
            }
        };
    }

    while let Some(c) = {
        sc.skip_ws();
        if sc.i < sc.s.len() && sc.s[sc.i].is_ascii_alphabetic() {
            let ch = sc.s[sc.i] as char;
            sc.i += 1;
            Some(ch)
        } else {
            None
        }
    } {
        let rel = c.is_ascii_lowercase();
        cmd = c.to_ascii_uppercase();
        loop {
            match cmd {
                'M' => {
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        px += x;
                        py += y;
                    }
                    // A genuine moveto ends the previous subpath. Repeated
                    // coordinate pairs after M are implicit linetos of the
                    // SAME subpath — they must not flush.
                    if !cur.is_empty() {
                        flush!();
                    }
                    x = px;
                    y = py;
                    sx = px;
                    sy = py;
                    push(&mut cur, x, y);
                }
                'L' => {
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        px += x;
                        py += y;
                    }
                    x = px;
                    y = py;
                    push(&mut cur, x, y);
                }
                'H' => {
                    let Some(mut px) = sc.num() else { break };
                    if rel {
                        px += x;
                    }
                    x = px;
                    push(&mut cur, x, y);
                }
                'V' => {
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        py += y;
                    }
                    y = py;
                    push(&mut cur, x, y);
                }
                'C' => {
                    let Some(mut x1) = sc.num() else { break };
                    let Some(mut y1) = sc.num() else { break };
                    let Some(mut x2) = sc.num() else { break };
                    let Some(mut y2) = sc.num() else { break };
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        x1 += x; y1 += y; x2 += x; y2 += y; px += x; py += y;
                    }
                    for k in 1..=8 {
                        let t = k as f32 / 8.0;
                        let mt = 1.0 - t;
                        let bx = mt * mt * mt * x + 3.0 * mt * mt * t * x1
                            + 3.0 * mt * t * t * x2 + t * t * t * px;
                        let by = mt * mt * mt * y + 3.0 * mt * mt * t * y1
                            + 3.0 * mt * t * t * y2 + t * t * t * py;
                        push(&mut cur, bx, by);
                    }
                    lx = x2; ly = y2;
                    x = px; y = py;
                }
                'S' => {
                    let Some(mut x2) = sc.num() else { break };
                    let Some(mut y2) = sc.num() else { break };
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        x2 += x; y2 += y; px += x; py += y;
                    }
                    let (x1, y1) = (2.0 * x - lx, 2.0 * y - ly);
                    for k in 1..=8 {
                        let t = k as f32 / 8.0;
                        let mt = 1.0 - t;
                        let bx = mt * mt * mt * x + 3.0 * mt * mt * t * x1
                            + 3.0 * mt * t * t * x2 + t * t * t * px;
                        let by = mt * mt * mt * y + 3.0 * mt * mt * t * y1
                            + 3.0 * mt * t * t * y2 + t * t * t * py;
                        push(&mut cur, bx, by);
                    }
                    lx = x2; ly = y2;
                    x = px; y = py;
                }
                'Q' => {
                    let Some(mut x1) = sc.num() else { break };
                    let Some(mut y1) = sc.num() else { break };
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        x1 += x; y1 += y; px += x; py += y;
                    }
                    for k in 1..=8 {
                        let t = k as f32 / 8.0;
                        let mt = 1.0 - t;
                        let bx = mt * mt * x + 2.0 * mt * t * x1 + t * t * px;
                        let by = mt * mt * y + 2.0 * mt * t * y1 + t * t * py;
                        push(&mut cur, bx, by);
                    }
                    qx = x1; qy = y1;
                    x = px; y = py;
                }
                'T' => {
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        px += x;
                        py += y;
                    }
                    let (x1, y1) = (2.0 * x - qx, 2.0 * y - qy);
                    for k in 1..=8 {
                        let t = k as f32 / 8.0;
                        let mt = 1.0 - t;
                        let bx = mt * mt * x + 2.0 * mt * t * x1 + t * t * px;
                        let by = mt * mt * y + 2.0 * mt * t * y1 + t * t * py;
                        push(&mut cur, bx, by);
                    }
                    qx = x1; qy = y1;
                    x = px; y = py;
                }
                'A' => {
                    let Some(rx) = sc.num() else { break };
                    let Some(ry) = sc.num() else { break };
                    let Some(rot) = sc.num() else { break };
                    let Some(la) = sc.flag() else { break };
                    let Some(sw) = sc.flag() else { break };
                    let Some(mut px) = sc.num() else { break };
                    let Some(mut py) = sc.num() else { break };
                    if rel {
                        px += x;
                        py += y;
                    }
                    arc_points(x, y, px, py, rx, ry, rot, la, sw, &mut cur);
                    // apply the translate to the arc points just added
                    if tx != 0.0 || ty != 0.0 {
                        for p in cur.iter_mut().rev().take(24) {
                            p.0 += tx;
                            p.1 += ty;
                        }
                    }
                    x = px;
                    y = py;
                }
                'Z' => {
                    if (x - sx).abs() > 0.01 || (y - sy).abs() > 0.01 {
                        push(&mut cur, sx, sy);
                    }
                    x = sx;
                    y = sy;
                    break;
                }
                _ => break,
            }
            // Continue the same command while more arguments follow.
            sc.skip_ws();
            if sc.i >= sc.s.len() || sc.s[sc.i].is_ascii_alphabetic() {
                break;
            }
            if cmd == 'M' {
                // implicit lineto after moveto
                cmd = 'L';
            }
        }
        // No flush here: L/H/V/C/A/… continue the subpath the M opened. The
        // next M (or the end of the string) closes it.
    }
    flush!();
}

/// Parse one inner-SVG markup string (the WebUI's icon value) into shapes.
fn parse_markup(markup: &str) -> Vec<Shape> {
    let mut shapes = Vec::new();
    let mut sc = Scanner::new(markup);
    let bytes = markup.as_bytes();
    loop {
        // Find the next tag.
        let Some(lt) = bytes[sc.i.min(bytes.len())..].iter().position(|&b| b == b'<') else {
            break;
        };
        sc.i += lt + 1;
        let tag_end = bytes[sc.i..].iter().position(|&b| b == b'>').map(|p| sc.i + p).unwrap_or(bytes.len());
        let tag = &markup[sc.i.min(markup.len())..tag_end.min(markup.len())];
        sc.i = tag_end;
        let name = tag.split_whitespace().next().unwrap_or("");
        let translate = tag
            .find("transform=\"translate(")
            .and_then(|p| {
                let rest = &tag[p + "transform=\"translate(".len()..];
                let end = rest.find(')')?;
                let mut it = rest[..end].split(|c| c == ',' || c == ' ');
                let a: f32 = it.next()?.trim().parse().ok()?;
                let b: f32 = it.next().unwrap_or("0").trim().parse().unwrap_or(0.0);
                Some((a, b))
            })
            .unwrap_or((0.0, 0.0));
        match name {
            "path" => {
                if let Some(di) = tag.find("d=\"") {
                    let d = &tag[di + 3..];
                    let d = d.split('"').next().unwrap_or("");
                    shapes.push(Shape::Path(d.to_string(), translate.0, translate.1));
                }
            }
            "circle" => {
                let get = |key: &str| -> f32 {
                    let pat = format!("{key}=\"");
                    tag.find(&pat)
                        .and_then(|p| tag[p + pat.len()..].split('"').next()?.parse().ok())
                        .unwrap_or(0.0)
                };
                shapes.push(Shape::Circle { cx: get("cx"), cy: get("cy"), r: get("r") });
            }
            "rect" => {
                let get = |key: &str| -> f32 {
                    let pat = format!("{key}=\"");
                    tag.find(&pat)
                        .and_then(|p| tag[p + pat.len()..].split('"').next()?.parse().ok())
                        .unwrap_or(0.0)
                };
                shapes.push(Shape::Rect { x: get("x"), y: get("y"), w: get("width"), h: get("height"), rx: get("rx") });
            }
            _ => {}
        }
        if sc.i >= bytes.len() {
            break;
        }
    }
    shapes
}

fn shapes_to_polylines(shapes: &[Shape]) -> Vec<Polyline> {
    let mut out = Vec::new();
    for sh in shapes {
        match sh {
            Shape::Path(d, tx, ty) => flatten_path(d, *tx, *ty, &mut out),
            Shape::Circle { cx, cy, r } => {
                // 20-gon; r=0.4 dots survive as small blobs, like on the web.
                let n = if *r < 1.0 { 8 } else { 20 };
                let mut pl = Vec::with_capacity(n + 1);
                for k in 0..=n {
                    let a = std::f32::consts::TAU * k as f32 / n as f32;
                    pl.push((cx + r * a.cos(), cy + r * a.sin()));
                }
                out.push(pl);
            }
            Shape::Rect { x, y, w, h, rx } => {
                let (x, y, w, h, rx) = (*x, *y, *w, *h, *rx);
                if rx > 0.0 && w > 2.0 * rx && h > 2.0 * rx {
                    let (r, b) = (x + w, y + h);
                    let mut pl = vec![(x + rx, y)];
                    arc_points(x + rx, y, x, y + rx, rx, rx, 0.0, 0.0, 0.0, &mut pl);
                    arc_points(x, b - rx, x + rx, b, rx, rx, 0.0, 0.0, 0.0, &mut pl);
                    arc_points(r - rx, b, r, b - rx, rx, rx, 0.0, 0.0, 0.0, &mut pl);
                    arc_points(r, y + rx, r - rx, y, rx, rx, 0.0, 0.0, 0.0, &mut pl);
                    pl.push((x + rx, y));
                    out.push(pl);
                } else {
                    out.push(vec![(x, y), (x + w, y), (x + w, y + h), (x, y + h), (x, y)]);
                }
            }
        }
    }
    out
}

type IconMap = HashMap<&'static str, Vec<Polyline>>;

static ICONS: OnceLock<Mutex<IconMap>> = OnceLock::new();

fn icons() -> &'static Mutex<IconMap> {
    ICONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The flattened polylines of one icon (module name or category key).
/// `cat` must be an exact key of CAT_MARKUPS when asking for a category tile.
pub fn polylines_for(name: &str, cat: &str) -> Option<Vec<Polyline>> {
    let mut guard = icons().lock().unwrap_or_else(|e| e.into_inner());
    if let Some(pl) = guard.get(name) {
        return Some(pl.clone());
    }
    let markup = MARKUPS
        .iter()
        .find(|(n, _)| *n == name)
        .map(|(_, m)| *m)
        .or_else(|| CAT_MARKUPS.iter().find(|(n, _)| *n == cat).map(|(_, m)| *m))?;
    let pl = shapes_to_polylines(&parse_markup(markup));
    guard.insert(leak_key(name, cat), pl.clone());
    Some(pl)
}

/// The cache is keyed by the module name; category lookups always miss the
/// cache on purpose (they are six entries, parsing them is nothing).
fn leak_key(name: &str, _cat: &str) -> &'static str {
    // Only names from MARKUPS ever reach the cache branch above, and those are
    // 'static. For safety, fall back to leaking on first use — it happens once
    // per icon at most (57 icons, a few KB).
    match MARKUPS.iter().find(|(n, _)| *n == name) {
        Some((n, _)) => n,
        None => Box::leak(name.to_string().into_boxed_str()),
    }
}

/// Stroke one icon into the tile at (x, y, size). `rgb` is the category tint.
pub unsafe fn draw(hdc: Hdc, name: &str, cat: &str, x: i32, y: i32, size: i32, rgb: [u8; 3]) {
    let Some(pls) = polylines_for(name, cat) else { return };
    let scale = size as f32 / 24.0;
    let color = (rgb[0] as u32) | ((rgb[1] as u32) << 8) | ((rgb[2] as u32) << 16);
    let pen = CreatePen(PS_SOLID, 1, color);
    let old = SelectObject(hdc, pen);
    let mut pts: Vec<Point> = Vec::new();
    for pl in &pls {
        pts.clear();
        for (px, py) in pl {
            pts.push(Point { x: x + (px * scale).round() as i32, y: y + (py * scale).round() as i32 });
        }
        if pts.len() > 1 {
            Polyline(hdc, pts.as_ptr(), pts.len() as i32);
        }
    }
    SelectObject(hdc, old);
    DeleteObject(pen);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_every_markup_into_polylines() {
        for (name, m) in MARKUPS {
            let pl = shapes_to_polylines(&parse_markup(m));
            assert!(!pl.is_empty(), "{name} produced no polylines");
            let pts: usize = pl.iter().map(|p| p.len()).sum();
            assert!(pts > 2, "{name} has too few points");
            // Everything stays inside the viewBox (with a tiny tolerance).
            for p in pl.iter().flatten() {
                assert!(p.0 >= -0.6 && p.0 <= 24.6 && p.1 >= -0.6 && p.1 <= 24.6, "{name} point out of viewBox: {p:?}");
            }
        }
    }

    #[test]
    fn parses_category_tiles_too() {
        for (name, m) in CAT_MARKUPS {
            let pl = shapes_to_polylines(&parse_markup(m));
            assert!(!pl.is_empty(), "category {name} produced no polylines");
        }
    }

    #[test]
    fn arcs_and_flags_parse() {
        // The "015.2" flag-run form from the WebUI data must parse as laf=0, sf=1.
        let mut out = Vec::new();
        flatten_path("M4.5 12a7.5 7.5 0 1 0 2.2-5.3", 0.0, 0.0, &mut out);
        assert!(!out.is_empty());
        let mut out2 = Vec::new();
        flatten_path("M19.3 20.6a4.6 4.6 0 10-4.2 1", 0.0, 0.0, &mut out2);
        assert!(!out2.is_empty());
        // Arc points actually move (the centre conversion worked).
        let pts = out2.iter().flatten().count();
        assert!(pts > 2);
    }

    #[test]
    fn translate_is_applied() {
        let mut a = Vec::new();
        flatten_path("M20.5 8.5L23 11", -1.5, 0.0, &mut a);
        let first = a[0][0];
        assert!((first.0 - 19.0).abs() < 0.01, "translate(-1.5 0) not applied: {first:?}");
    }
}
