// draw.rs — the pixel half of the overlay, deliberately free of Win32.
//
// The layered window is fed a top-down 32bpp DIB whose pixels are BGRA with
// premultiplied alpha: that is the one format UpdateLayeredWindow blends
// correctly per pixel. GDI cannot produce it for us — RoundRect has no alpha
// channel and no antialiasing — so every shape is rasterised here and the result
// is copied straight into the DIB. Keeping this side pure (numbers in, pixels
// out, no handles and no globals) is what lets the geometry be unit-tested on any
// host, including a CI box that has no desktop session.

/// Pack a colour the way the rest of the plan writes it, 0xAARRGGBB.
///
/// Kept as a `const fn` so the palette can live in `const` items instead of
/// being recomputed on every frame.
pub const fn argb(r: u8, g: u8, b: u8, a: u8) -> u32 {
    ((a as u32) << 24) | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)
}

/// A CPU-side surface of premultiplied BGRA pixels, laid out exactly like the
/// DIB section it will be memcpy'd into (word 0 = top-left pixel).
pub struct Canvas {
    /// Where this surface's `(0, 0)` lands on the monitor. The live overlay's
    /// canvas *is* the layered window's surface, so the window's screen position
    /// is what the HUD layout anchors against. Nothing in this file reads it:
    /// pixels stay surface-local, and no drawing code has to know the window moved.
    screen_x: i32,
    screen_y: i32,
    w: u32,
    h: u32,
    px: Vec<u32>,
}

impl Canvas {
    /// Allocate a fully transparent surface. `w`/`h` are physical pixels, which
    /// is also what the caller passes to CreateDIBSection — the two must agree
    /// or the copy at blit time would run off the end of the DIB.
    pub fn new(w: u32, h: u32) -> Canvas {
        Canvas::placed(0, 0, w, h)
    }

    /// The same surface, declared to sit at `(screen_x, screen_y)` on the monitor.
    /// Used by the live overlay, whose canvas covers the whole layered window: the
    /// window is created at that corner, so the HUD's margins are measured from
    /// the screen edge rather than from a small stage parked at the origin.
    pub fn placed(screen_x: i32, screen_y: i32, w: u32, h: u32) -> Canvas {
        Canvas { screen_x, screen_y, w, h, px: vec![0u32; w as usize * h as usize] }
    }

    /// Where this surface's `(0, 0)` sits on the monitor, for the layout.
    pub fn screen_origin(&self) -> (i32, i32) {
        (self.screen_x, self.screen_y)
    }

    /// The raw words, for handing to the DIB. BGRA byte order on little-endian
    /// Windows, which is the only target this binary has.
    pub fn pixels(&self) -> &[u32] {
        &self.px
    }

    pub fn width(&self) -> u32 {
        self.w
    }

    pub fn height(&self) -> u32 {
        self.h
    }

    /// One premultiplied BGRA word, for the dump/preview paths.
    pub fn pixel(&self, x: u32, y: u32) -> u32 {
        if x >= self.w || y >= self.h {
            return 0;
        }
        self.px[y as usize * self.w as usize + x as usize]
    }

    /// Back to fully transparent. The HUD is redrawn from scratch every frame,
    /// so the previous frame's pixels must not survive underneath it.
    pub fn clear(&mut self) {
        for p in self.px.iter_mut() {
            *p = 0;
        }
    }

    /// A crisp axis-aligned rectangle: the rounded-rect fill still antialiases the
    /// rim, but a rectangle whose edges land on pixel boundaries covers those
    /// pixels completely, so nothing is softened.
    pub fn fill_rect(&mut self, x: i32, y: i32, w: u32, h: u32, colour: u32) {
        self.fill_rounded_rect_corners(x, y, w, h, [0.0; 4], colour);
    }

    /// Fill a rectangle whose four corners have independent radii — in the order
    /// (top-left, top-right, bottom-right, bottom-left), in pixels — blended over
    /// whatever is already there.
    ///
    /// ── the GDI adaptation ──────────────────────────────────────────────────
    ///
    /// The ported HUD (OpenCommunity's `HUD.cpp`) rounds whole shapes with ImGui's
    /// `AddRectFilled(..., rounding, ImDrawFlags_RoundCornersLeft)`: the Tesseract
    /// row chips and the VapeV4 panel/watermark are rounded **only on their left
    /// side**, because the accent bar sits square against their right edge.
    ///
    /// GDI's own answer to rounding is `RoundRect`, which takes one radius and
    /// applies it to all four corners; the shape cannot be expressed with it.
    /// `CreateRoundRectRgn` + `CombineRgn` can build the region, but a region is an
    /// opaque 1-bit mask: no alpha and no antialiasing, and this surface is
    /// premultiplied per-pixel alpha fed to UpdateLayeredWindow, so a region would
    /// have to be converted back into coverage anyway (and would have hard, jagged
    /// rims). The third option, which is what this does, is to notice that the fill
    /// is *already* a hand-written signed distance field sampled once per pixel:
    /// the single radius is simply selected per quadrant, so a per-corner shape
    /// costs exactly what the single-radius one did and keeps the antialiased rim.
    ///
    /// The radius is clamped to `min(half_w, half_h)`, so callers may pass "half the
    /// height" for a pill without checking the box first.
    pub fn fill_rounded_rect_corners(
        &mut self,
        x: i32,
        y: i32,
        w: u32,
        h: u32,
        corners: [f32; 4],
        colour: u32,
    ) {
        if w == 0 || h == 0 {
            return;
        }

        let half_w = w as f32 / 2.0;
        let half_h = h as f32 / 2.0;
        let limit = half_w.min(half_h).max(0.0);
        let r = [
            corners[0].min(limit).max(0.0),
            corners[1].min(limit).max(0.0),
            corners[2].min(limit).max(0.0),
            corners[3].min(limit).max(0.0),
        ];
        let cx = x as f32 + half_w;
        let cy = y as f32 + half_h;

        // Iterate one pixel past the box on every side: the antialiased rim of a
        // sharp rectangle reaches into the neighbouring pixel, and clipping the
        // loop to the box would shave that half-covered column off.
        let x0 = (x - 1).max(0);
        let y0 = (y - 1).max(0);
        let x1 = (x + w as i32 + 1).min(self.w as i32);
        let y1 = (y + h as i32 + 1).min(self.h as i32);
        if x0 >= x1 || y0 >= y1 {
            return;
        }

        for py in y0..y1 {
            for px in x0..x1 {
                // Distance from the pixel centre to the rounded box, as a signed
                // value: negative inside, positive outside. This is the usual
                // rounded-box distance field, and `0.5 - d` turns it into an
                // approximate coverage in 0..1, which is the antialiasing.
                let distance = rounded_box_sdf(px, py, cx, cy, half_w, half_h, r);
                let coverage = (0.5 - distance).max(0.0).min(1.0);
                if coverage > 0.0 {
                    self.blend_pixel(px, py, colour, coverage);
                }
            }
        }
    }

    /// Source-over blend of one pixel with an explicit coverage. Both the source
    /// we build here and the destination already in the buffer are premultiplied,
    /// so the whole composite reduces to `src + dst * (1 - src_alpha)` on every
    /// channel — including alpha itself, which is why no separate divide is
    /// needed. Public because the text rasteriser feeds it the coverage it read
    /// out of GDI's scratch DIB.
    pub fn blend_pixel(&mut self, x: i32, y: i32, colour: u32, coverage: f32) {
        if x < 0 || y < 0 || x >= self.w as i32 || y >= self.h as i32 {
            return;
        }
        if coverage <= 0.0 {
            return;
        }
        let index = y as usize * self.w as usize + x as usize;

        let (sr, sg, sb, sa) = unpack(colour);
        // Coverage multiplies the source alpha, so a pixel on the rim of the card
        // is exactly as transparent as the rim covers it.
        let a = sa as f32 / 255.0 * coverage;
        let inv = 1.0 - a;

        let dst = self.px[index];
        // Colour channels: `straight * a` premultiplies the source, then the
        // destination (already premultiplied) is attenuated by 1 - a.
        let b = to_byte(sb as f32 * a + (dst & 0xFF) as f32 * inv);
        let g = to_byte(sg as f32 * a + ((dst >> 8) & 0xFF) as f32 * inv);
        let r = to_byte(sr as f32 * a + ((dst >> 16) & 0xFF) as f32 * inv);
        // Alpha accumulates the coverage itself, *not* sa * a: the source alpha is
        // `a`, already scaled by sa once. Multiplying by sa again left the stored
        // alpha below the premultiplied colour (c > a, an invalid premultiplied
        // pixel) and made the whole card fade towards transparent a second time.
        let alpha = to_byte(a * 255.0 + ((dst >> 24) & 0xFF) as f32 * inv);
        self.px[index] = b | (g << 8) | (r << 16) | (alpha << 24);
    }
}

/// Signed distance from a pixel centre to a rounded box centred at `(cx, cy)`
/// with half-extents `(half_w, half_h)` and one radius per corner, in the order
/// (top-left, top-right, bottom-right, bottom-left): negative inside, positive
/// outside, in pixels. The radius of the pixel's own quadrant is the one used, so
/// a corner asked for radius 0 stays square while its neighbours round.
///
/// At radius zero — and with all four radii equal — this reduces term for term to
/// the plain rounded-box field, which is why one function serves every fill the
/// HUD draws.
fn rounded_box_sdf(
    px: i32,
    py: i32,
    cx: f32,
    cy: f32,
    half_w: f32,
    half_h: f32,
    corners: [f32; 4],
) -> f32 {
    let dx = px as f32 + 0.5 - cx;
    let dy = py as f32 + 0.5 - cy;
    let radius = match (dx > 0.0, dy > 0.0) {
        (false, false) => corners[0],
        (true, false) => corners[1],
        (true, true) => corners[2],
        (false, true) => corners[3],
    };
    let qx = dx.abs() - (half_w - radius);
    let qy = dy.abs() - (half_h - radius);
    let ox = qx.max(0.0);
    let oy = qy.max(0.0);
    let outside = (ox * ox + oy * oy).sqrt();
    outside + qx.max(qy).min(0.0) - radius
}

/// Split 0xAARRGGBB into its channel bytes, as (r, g, b, a).
fn unpack(colour: u32) -> (u8, u8, u8, u8) {
    (
        ((colour >> 16) & 0xFF) as u8,
        ((colour >> 8) & 0xFF) as u8,
        (colour & 0xFF) as u8,
        ((colour >> 24) & 0xFF) as u8,
    )
}

/// Round a 0..255 float to a byte, clamping explicitly: a float-to-int cast
/// saturates in Rust, so an out-of-range value would silently become 255 and
/// hide the blending bug that produced it.
fn to_byte(value: f32) -> u32 {
    if value <= 0.0 {
        0
    } else if value >= 255.0 {
        255
    } else {
        (value + 0.5) as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn radius_clips_the_corners_and_leaves_the_middle_solid() {
        let mut c = Canvas::new(20, 20);
        c.fill_rounded_rect_corners(0, 0, 20, 20, [10.0; 4], argb(0x0D, 0x11, 0x1C, 0xCC));

        // (0,0) is outside a 10px corner arc, so nothing may be written there.
        assert_eq!(c.pixels()[0], 0);
        // The centre is fully covered, so it must carry the requested alpha.
        assert_eq!(c.pixels()[10 * 20 + 10] >> 24, 0xCC);
    }

    /// The shape the ported HUD needs and `RoundRect` cannot draw: rounded on the
    /// left two corners, square on the right, where the accent bar sits.
    #[test]
    fn left_only_corners_round_the_left_and_leave_the_right_square() {
        let mut c = Canvas::new(20, 20);
        c.fill_rounded_rect_corners(0, 0, 20, 20, [10.0, 0.0, 0.0, 10.0], argb(0xFF, 0xFF, 0xFF, 0xFF));

        assert_eq!(c.pixel(0, 0), 0, "top-left is cut away by its 10px arc");
        assert_eq!(c.pixel(0, 19), 0, "bottom-left too");
        assert_eq!(c.pixel(19, 0) >> 24, 0xFF, "top-right is a square corner");
        assert_eq!(c.pixel(19, 19) >> 24, 0xFF, "bottom-right is a square corner");
        assert_eq!(c.pixel(10, 10) >> 24, 0xFF, "the middle is solid");

        // The right half is a plain rectangle: its rightmost column is fully covered
        // from the very first row down to the last.
        for y in 0..20 {
            assert_eq!(c.pixel(19, y) >> 24, 0xFF, "row {y} of the square right edge");
        }
    }

    #[test]
    fn every_channel_stays_within_the_premultiplied_alpha() {
        let mut c = Canvas::new(4, 4);
        c.fill_rect(0, 0, 4, 4, argb(0xFF, 0xFF, 0xFF, 0x80));

        for &p in c.pixels() {
            let a = p >> 24;
            assert!((p & 0xFF) <= a, "blue channel exceeds alpha");
            assert!(((p >> 8) & 0xFF) <= a, "green channel exceeds alpha");
            assert!(((p >> 16) & 0xFF) <= a, "red channel exceeds alpha");
        }
    }

    /// A canvas knows where its surface sits on the monitor, which is what lets
    /// the HUD anchor to the screen's corner instead of to a small stage's.
    #[test]
    fn a_placed_canvas_reports_where_its_surface_sits() {
        let c = Canvas::placed(1920, 0, 8, 4);
        assert_eq!(c.screen_origin(), (1920, 0));
        assert_eq!((c.width(), c.height()), (8, 4));
        assert_eq!(Canvas::new(8, 4).screen_origin(), (0, 0), "the default surface is at the origin");

        // The origin is metadata: drawing is still surface-local.
        let mut c = Canvas::placed(1920, 0, 8, 4);
        c.fill_rect(0, 0, 1, 1, argb(0xFF, 0xFF, 0xFF, 0xFF));
        assert_eq!(c.pixel(0, 0) >> 24, 0xFF, "x=0 is the surface's own first column");
    }
}
