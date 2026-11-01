// Linux-only test stub standing in for the GDI text engine (text.rs). Same
// public surface the pure code and its tests touch; measure is a crude
// monospace so only geometry/colour assertions are meaningful under it.
// NOT compiled into the Windows binary (main.rs keeps `mod text;` → text.rs).
pub const FONT_PX: f32 = 16.0;
/// The Aurora mode's font size — mirrors text.rs's own constant.
pub const SPINE_FONT_PX: f32 = 11.0;
pub const MIN_SCALE: f32 = 0.5;
pub const MAX_SCALE: f32 = 4.0;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FontId {
    Name,
    Body,
    SpineName,
    SpineBody,
    SpineTag,
}

pub struct TextEngine {
    setup: FontSetup,
}

/// The dump-facing half of text.rs's surface (`FontSetup` and the face
/// queries). Same shapes as the real engine: `--dump` prints them, so the
/// review build needs them to exist with the same types, carrying stub values
/// (`resolved` never matches a real face name, `em`/`cell` are the design
/// pixels without scale applied).
#[derive(Clone)]
pub struct FontSetup {
    pub play: bool,
    pub roboto: bool,
    pub registered_faces: u32,
    pub family: &'static str,
    pub spine_family: &'static str,
    pub resolved: [String; 5],
}

impl FontSetup {
    /// The one-line form both the log and `--dump` use (shape mirrors text.rs).
    pub fn summary(&self) -> String {
        format!(
            "{} ({} face(s) registered, family {}{}) — Aurora: {} (family {}{})",
            if self.play { "embedded Play" } else { "substitute Segoe UI" },
            self.registered_faces,
            self.family,
            if self.play { "" } else { " — embedded faces unusable, fell back" },
            if self.roboto { "embedded Roboto" } else { "substitute Segoe UI" },
            self.spine_family,
            if self.roboto { "" } else { " — embedded faces unusable, fell back" }
        )
    }
}

fn em_px_of(id: FontId) -> f32 {
    match id {
        FontId::SpineName | FontId::SpineBody | FontId::SpineTag => SPINE_FONT_PX,
        _ => FONT_PX,
    }
}

impl TextEngine {
    pub fn new(_scale: f32) -> Result<TextEngine, String> {
        Ok(TextEngine {
            setup: FontSetup {
                play: false,
                roboto: false,
                registered_faces: 0,
                family: "stub",
                spine_family: "stub",
                resolved: [
                    "stub".to_string(),
                    "stub".to_string(),
                    "stub".to_string(),
                    "stub".to_string(),
                    "stub".to_string(),
                ],
            },
        })
    }

    pub fn setup(&self) -> &FontSetup {
        &self.setup
    }

    pub fn face_name(&self, _id: FontId) -> &str {
        "stub"
    }

    pub fn em_px(&self, id: FontId) -> f32 {
        em_px_of(id)
    }

    pub fn cell_height(&self, id: FontId) -> i32 {
        em_px_of(id).round() as i32
    }

    /// The stub renders every codepoint it is given, so nothing is ever
    /// reported missing.
    pub fn missing_glyphs(&self, _id: FontId, _text: &str) -> Vec<char> {
        Vec::new()
    }

    pub fn measure(&self, _id: FontId, text: &str) -> i32 {
        text.chars().count() as i32 * 8
    }

    pub fn draw(
        &mut self,
        _canvas: &mut crate::draw::Canvas,
        _id: FontId,
        _s: &str,
        _x: i32,
        _y: i32,
        _colour: u32,
    ) -> i32 {
        0
    }
}
