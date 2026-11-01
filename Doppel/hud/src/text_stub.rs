// Linux-only test stub standing in for the GDI text engine (text.rs). Same
// public surface the pure code and its tests touch; measure is a crude
// monospace so only geometry/colour assertions are meaningful under it.
// NOT compiled into the Windows binary (main.rs keeps `mod text;` → text.rs).
pub const FONT_PX: f32 = 16.0;
pub const MIN_SCALE: f32 = 0.5;
pub const MAX_SCALE: f32 = 4.0;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FontId {
    Name,
    Body,
}

pub struct TextEngine;

impl TextEngine {
    pub fn new(_scale: f32) -> Result<TextEngine, String> {
        Ok(TextEngine)
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
