// theme.rs — Doppel's single source of truth for the look.
//
// Palette transcribed from the OpenCommunity launcher so the native window
// (window.rs) and the terminal fallback (ui.rs) cannot drift apart. Every value
// below is the DARK entry of the reference palette.
//
// Source: E:\swl\OpenCommunity\deps\imgui\colors.h lines 86-111 (dark branch of
// every Get*Vec4 helper), plus the drawing idioms in
// E:\swl\OpenCommunity\launcher\src\ui\Screen.cpp:
//   DrawWindowBase            -> background, 0.5 px inset
//   DrawTopographicBackground -> contour lines (topographicLine @ 80/255)
//   DrawGlassCard             -> panel @ 0.72, border @ 0.40, inner highlight @ 0.26
//   SetupImGuiStyle           -> Button/ButtonHovered/ButtonActive = panel/panelHover/panelActive
//
// Reference hex values (colors.h:86-111):
//   background      #0B0E13      strongText      #F5F7FB
//   moduleText      #E6EBF3      moduleAltText   #C7D0DC
//   glassSoft       #171B22      glassStrong     #1F252E
//   glassHighlight  #2B323E      glassShadow     #05070B
//   secondaryText   #9FACBC      mutedText       #737F90
//   panel           #151920      panelHover      #1C222C      panelActive   #262E3A
//   sidebar         #10141A      fieldBg         #11161D
//   fieldHover      #171D26      fieldActive     #202834
//   border          #414C5D
//   link            #8ABCFF      linkHover       #A8D0FF
//   success         #79D39A      warning         #F0B863      danger        #EF8888
//   topographicLine #4D5969      iconTint        #F5F7FB
//
// GDI brushes have no alpha channel, so every translucent reference value is
// pre-baked over a known backdrop with `mix` (see the section at the bottom).
//
// The palette is a complete transcription even where this crate does not use
// every entry yet (the reference has more UI surface than the injector does).
// The `allow(dead_code)` below is deliberately scoped to THIS module only — it
// keeps the unshortened transcription from spraying warnings, and it is not a
// crate-wide blanket.
#![allow(dead_code)]

/// #0B0E13 — the reference window background.
pub const BACKGROUND: [u8; 3] = [0x0B, 0x0E, 0x13];
/// #F5F7FB — headings, primary action text, the reference's "accent".
pub const STRONG_TEXT: [u8; 3] = [0xF5, 0xF7, 0xFB];
/// #E6EBF3 — regular body text.
pub const MODULE_TEXT: [u8; 3] = [0xE6, 0xEB, 0xF3];
/// #C7D0DC — secondary body text.
pub const MODULE_ALT_TEXT: [u8; 3] = [0xC7, 0xD0, 0xDC];
/// #171B22 — glass, soft.
pub const GLASS_SOFT: [u8; 3] = [0x17, 0x1B, 0x22];
/// #1F252E — glass, strong.
pub const GLASS_STRONG: [u8; 3] = [0x1F, 0x25, 0x2E];
/// #2B323E — glass highlight (used for the 1 px inner edge of cards).
pub const GLASS_HIGHLIGHT: [u8; 3] = [0x2B, 0x32, 0x3E];
/// #05070B — glass shadow.
pub const GLASS_SHADOW: [u8; 3] = [0x05, 0x07, 0x0B];
/// #9FACBC — labels that support a heading.
pub const SECONDARY_TEXT: [u8; 3] = [0x9F, 0xAC, 0xBC];
/// #737F90 — section headers, hints, disabled text.
pub const MUTED_TEXT: [u8; 3] = [0x73, 0x7F, 0x90];
/// #151920 — panels and secondary buttons.
pub const PANEL: [u8; 3] = [0x15, 0x19, 0x20];
/// #1C222C — panel/button hover.
pub const PANEL_HOVER: [u8; 3] = [0x1C, 0x22, 0x2C];
/// #262E3A — panel/button active (pressed) and selected rows.
pub const PANEL_ACTIVE: [u8; 3] = [0x26, 0x2E, 0x3A];
/// #10141A — the sidebar rail.
pub const SIDEBAR: [u8; 3] = [0x10, 0x14, 0x1A];
/// #11161D — input/list background.
pub const FIELD_BG: [u8; 3] = [0x11, 0x16, 0x1D];
/// #171D26 — input hover.
pub const FIELD_HOVER: [u8; 3] = [0x17, 0x1D, 0x26];
/// #202834 — input active.
pub const FIELD_ACTIVE: [u8; 3] = [0x20, 0x28, 0x34];
/// #414C5D — 1 px borders (drawn at ~0.40 alpha in the reference).
pub const BORDER: [u8; 3] = [0x41, 0x4C, 0x5D];
/// #8ABCFF — the reference's link/primary colour.
pub const LINK: [u8; 3] = [0x8A, 0xBC, 0xFF];
/// #A8D0FF — link hover.
pub const LINK_HOVER: [u8; 3] = [0xA8, 0xD0, 0xFF];
/// #79D39A — success.
pub const SUCCESS: [u8; 3] = [0x79, 0xD3, 0x9A];
/// #F0B863 — warning.
pub const WARNING: [u8; 3] = [0xF0, 0xB8, 0x63];
/// #EF8888 — danger.
pub const DANGER: [u8; 3] = [0xEF, 0x88, 0x88];
/// #4D5969 — the topographic background lines.
pub const TOPOGRAPHIC_LINE: [u8; 3] = [0x4D, 0x59, 0x69];
/// #F5F7FB — icon tint.
pub const ICON_TINT: [u8; 3] = [0xF5, 0xF7, 0xFB];

/// GDI wants COLORREF = 0x00BBGGRR (low byte is red).
pub const fn colorref(rgb: [u8; 3]) -> u32 {
    (rgb[0] as u32) | ((rgb[1] as u32) << 8) | ((rgb[2] as u32) << 16)
}

/// Integer-weighted blend, usable in `const` context: `num/den` of the way from
/// `a` to `b`. Used to bake the reference's alpha values into solid GDI colours
/// (GDI brushes have no alpha channel).
pub const fn mix(a: [u8; 3], b: [u8; 3], num: u32, den: u32) -> [u8; 3] {
    let mut out = [0u8; 3];
    let mut i = 0;
    while i < 3 {
        let from = a[i] as u32;
        let to = b[i] as u32;
        out[i] = ((from * (den - num) + to * num) / den) as u8;
        i += 1;
    }
    out
}

/// Runtime blend of `a` toward `b` by `t` (clamped 0..1).
pub fn blend(a: [u8; 3], b: [u8; 3], t: f32) -> [u8; 3] {
    let t = t.clamp(0.0, 1.0);
    [
        (a[0] as f32 + (b[0] as f32 - a[0] as f32) * t) as u8,
        (a[1] as f32 + (b[1] as f32 - a[1] as f32) * t) as u8,
        (a[2] as f32 + (b[2] as f32 - a[2] as f32) * t) as u8,
    ]
}

/// A COLORREF blended toward the background — the "fade" of a whole layer.
pub fn faded(rgb: [u8; 3], alpha: f32) -> u32 {
    colorref(blend(BACKGROUND, rgb, alpha))
}

/// `faded` for a value that is already a COLORREF: splits the BGR channels back
/// out and blends them toward the background. This is what the launcher's
/// global content fade leans on — GDI cannot composite, so "alpha" means
/// "blend toward the window background" instead.
pub fn fade_colorref(color: u32, alpha: f32) -> u32 {
    let rgb = [color as u8, (color >> 8) as u8, (color >> 16) as u8];
    faded(rgb, alpha)
}

// ── values the reference states as alpha, baked into solid colours ──

/// The contour lines: topographicLine at 80/255 over the background.
pub const TOPO_LINE_ON_BG: [u8; 3] = mix(BACKGROUND, TOPOGRAPHIC_LINE, 31, 100);
/// A card body: panel at 0.72 over the background (DrawGlassCard).
pub const CARD_ON_BG: [u8; 3] = mix(BACKGROUND, PANEL, 72, 100);
/// A card border: border at 0.40 over the card body (DrawGlassCard).
pub const CARD_BORDER: [u8; 3] = mix(PANEL, BORDER, 40, 100);
/// A card's inner 1 px edge: glassHighlight at 0.26 over the card body.
pub const CARD_INNER: [u8; 3] = mix(PANEL, GLASS_HIGHLIGHT, 26, 100);
/// The rail's right edge: border at 0.40 over the sidebar.
pub const RAIL_EDGE: [u8; 3] = mix(SIDEBAR, BORDER, 40, 100);
/// Row separators: border at 0.18 over the field.
pub const ROW_SEPARATOR: [u8; 3] = mix(FIELD_BG, BORDER, 18, 100);
/// Frame around an input: border at 0.55 over the field.
pub const FIELD_BORDER: [u8; 3] = mix(FIELD_BG, BORDER, 55, 100);
/// A status chip's fill: the state colour at 0.14 over the background.
pub const fn chip_fill(state: [u8; 3]) -> [u8; 3] {
    mix(BACKGROUND, state, 14, 100)
}
/// A rail marker's fill: the state colour at 0.18 over the sidebar.
pub const fn marker_fill(state: [u8; 3]) -> [u8; 3] {
    mix(SIDEBAR, state, 18, 100)
}

/// A secondary button at rest: same as a card body (Button = panel @ 0.72).
pub const BUTTON_ON_BG: [u8; 3] = CARD_ON_BG;
/// A secondary button hovered: panelHover at 0.82 over the background.
pub const BTN_HOVER: [u8; 3] = mix(BACKGROUND, PANEL_HOVER, 82, 100);
/// A secondary button pressed: panelActive at 0.90 over the background.
pub const BTN_ACTIVE: [u8; 3] = mix(BACKGROUND, PANEL_ACTIVE, 90, 100);
/// A disabled button: panelActive at 0.72 over the background, as the reference
/// draws its disabled action buttons (Screen.cpp:2599-2656).
pub const BTN_DISABLED: [u8; 3] = mix(BACKGROUND, PANEL_ACTIVE, 72, 100);
/// The header rule / separators: border at 0.38 over the background.
pub const SEPARATOR: [u8; 3] = mix(BACKGROUND, BORDER, 38, 100);
/// The process list body: an opaque field background (ChildBg, baked).
pub const LIST_FILL: [u8; 3] = FIELD_BG;
/// A selected list row: panelActive at 0.70 over the list body.
pub const ROW_SELECTED: [u8; 3] = mix(FIELD_BG, PANEL_ACTIVE, 70, 100);
/// Text drawn on a `LINK`-filled button. The reference uses IM_COL32(20,24,32)
/// (Screen.cpp:3154-3157) because near-white on blue washes out.
pub const ON_LINK_TEXT: [u8; 3] = [0x14, 0x18, 0x20];

// ── metrics ─────────────────────────────────────────────────────────────────
//
// The reference's `Screen::SetupImGuiStyle` (Screen.cpp:3486-3547) expressed as
// plain integers, plus the geometry the launcher actually uses. `RoundRect`
// takes *ellipse sizes*, not radii, so a radius of 15 is passed as 30.

pub mod metrics {
    /// Client area of the launcher window (Main.cpp:97-98).
    pub const WINDOW_W: i32 = 900;
    pub const WINDOW_H: i32 = 550;

    /// WindowPadding (18, 18).
    pub const PAD_WINDOW: i32 = 18;
    /// Card padding, from the reference's update card (Screen.cpp:4346-4474).
    pub const PAD_CARD: i32 = 28;
    /// ItemSpacing (10, 10).
    pub const ITEM_SPACING: i32 = 10;
    /// ItemInnerSpacing (8, 6).
    pub const ITEM_INNER_X: i32 = 8;
    pub const ITEM_INNER_Y: i32 = 6;
    /// WindowRounding / FrameRounding in ImGui are 0 in the reference; the
    /// rounding users see comes from the draw-list primitives below.
    pub const CARD_ROUNDING: i32 = 24;
    pub const BUTTON_ROUNDING: i32 = 15;
    /// Half of the row height, i.e. a pill.
    pub const CHIP_ROUNDING: i32 = 11;
    /// FrameBorderSize / PopupBorderSize = 1.
    pub const FRAME_BORDER: i32 = 1;

    /// Action buttons (update card: 180 x 44, radius 15).
    pub const BTN_LARGE_W: i32 = 180;
    pub const BTN_LARGE_H: i32 = 44;
    /// One row of the process list.
    pub const ROW_H: i32 = 30;

    /// Font heights, matching the reference's Inter sizes (Screen.cpp:3751-3793)
    /// as closely as Segoe UI can: body 17, bold 17, med 22, title 32.
    pub const FONT_TITLE: i32 = -32;
    pub const FONT_MED: i32 = -22;
    pub const FONT_BODY: i32 = -17;
    pub const FONT_SMALL: i32 = -14;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colorref_is_bgr() {
        // #0B0E13 -> 0x0013_0E0B
        assert_eq!(colorref(BACKGROUND), 0x0013_0E0B);
        assert_eq!(colorref(STRONG_TEXT), 0x00FB_F7F5);
    }

    #[test]
    fn mix_endpoints_and_middle() {
        assert_eq!(mix(BACKGROUND, PANEL, 0, 100), BACKGROUND);
        assert_eq!(mix(BACKGROUND, PANEL, 100, 100), PANEL);
        // Halfway between #0B0E13 and #151920.
        assert_eq!(mix(BACKGROUND, PANEL, 50, 100), [0x10, 0x13, 0x19]);
    }

    #[test]
    fn blend_clamps() {
        assert_eq!(blend(BACKGROUND, LINK, -1.0), BACKGROUND);
        assert_eq!(blend(BACKGROUND, LINK, 2.0), LINK);
        assert_eq!(faded(LINK, 1.0), colorref(LINK));
        assert_eq!(faded(LINK, 0.0), colorref(BACKGROUND));
    }

    /// The global content fade goes through `fade_colorref`, so it must be the
    /// exact inverse of `colorref` at full alpha and land on the background at 0.
    #[test]
    fn fade_colorref_round_trips() {
        assert_eq!(fade_colorref(colorref(LINK), 1.0), colorref(LINK));
        assert_eq!(fade_colorref(colorref(LINK), 0.0), colorref(BACKGROUND));
        // GDI layers are baked over the background, so a fully faded card is the
        // window background, not black.
        assert_eq!(fade_colorref(colorref(CARD_ON_BG), 0.0), colorref(BACKGROUND));
    }
}
