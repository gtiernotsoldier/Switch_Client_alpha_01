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

    /// Fill a rounded rectangle whose colour runs as a linear gradient from
    /// `from` at the box's start edge to `to` at its opposite edge — horizontal
    /// when `horizontal` (left → right), vertical otherwise (top → bottom).
    ///
    /// Aurora's spine and watermark dot are the reason this exists: the ported
    /// modes fill every shape with one flat colour, and the one new mode needed a
    /// two-stop accent inside the same antialiased rounded silhouette. The shape
    /// maths are `fill_rounded_rect_corners`'s, shared line for line; the only
    /// difference is that the colour is resolved **per pixel** from the gradient
    /// before the blend, with `t` measured from the pixel centre so a 1 px bar
    /// still shows both stops.
    ///
    /// Both stops should carry the same alpha (they always do here — the callers
    /// build them from the same `with_alpha`), because the blend takes the source
    /// colour's alpha as-is; a mismatched stop would fade the shape from one side
    /// to the other. `from`'s alpha wins wherever the two disagree, exactly as
    /// `lerp_rgb` behaves.
    pub fn fill_rounded_rect_gradient(
        &mut self,
        x: i32,
        y: i32,
        w: u32,
        h: u32,
        corners: [f32; 4],
        from: u32,
        to: u32,
        horizontal: bool,
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

        // One pixel past the box on every side, like the flat fill: the antialiased
        // rim of even a sharp rectangle reaches into the neighbouring pixel.
        let x0 = (x - 1).max(0);
        let y0 = (y - 1).max(0);
        let x1 = (x + w as i32 + 1).min(self.w as i32);
        let y1 = (y + h as i32 + 1).min(self.h as i32);
        if x0 >= x1 || y0 >= y1 {
            return;
        }

        // The span the gradient runs over, in pixels. Guarded against zero so a
        // degenerate 1 px box cannot divide by zero — its single column/row simply
        // takes `t` ≈ 1, the far stop, which is what "the box ends there" means.
        let span = if horizontal { w as f32 } else { h as f32 };

        for py in y0..y1 {
            for px in x0..x1 {
                let distance = rounded_box_sdf(px, py, cx, cy, half_w, half_h, r);
                let coverage = (0.5 - distance).max(0.0).min(1.0);
                if coverage > 0.0 {
                    // The gradient coordinate from the pixel centre, so the stops
                    // land where the box's edges are rather than half a pixel in.
                    let centre = if horizontal {
                        px as f32 + 0.5 - x as f32
                    } else {
                        py as f32 + 0.5 - y as f32
                    };
                    let t = if span > 0.0 { centre / span } else { 1.0 };
                    self.blend_pixel(px, py, lerp_rgb(from, to, t), coverage);
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

/// Linear blend between two 0xAARRGGBB colours, channel by channel on the RGB
/// bytes, with `t` clamped to 0..1. The result keeps **`from`'s alpha**: every
/// caller here is recolouring a run it has already resolved, and the fade is
/// applied to that colour afterwards (`with_alpha`), so a lerp that took the
/// target's alpha would quietly change how opaque the run is.
///
/// `t = 0` returns `from` exactly and `t = 1` its RGB replaced by `to`'s, both
/// without a round trip through floats, so "no tint" is bit-for-bit the colour
/// the caller passed. A non-finite `t` is treated as 0 rather than as a wild
/// colour.
pub fn lerp_rgb(from: u32, to: u32, t: f32) -> u32 {
    let t = if t.is_finite() { t.clamp(0.0, 1.0) } else { 0.0 };
    if t <= 0.0 {
        return from;
    }
    if t >= 1.0 {
        return (from & 0xFF00_0000) | (to & 0x00FF_FFFF);
    }
    let mix = |shift: u32| {
        let a = ((from >> shift) & 0xFF) as f32;
        let b = ((to >> shift) & 0xFF) as f32;
        (a + (b - a) * t).round().clamp(0.0, 255.0) as u32
    };
    (from & 0xFF00_0000) | (mix(16) << 16) | (mix(8) << 8) | mix(0)
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

    /// The work-pulse tint's channel maths, in isolation: 0 leaves the colour
    /// exactly alone, 1 replaces its RGB with the target's and keeps its alpha, and
    /// a decaying value lands on the straight line between the two.
    #[test]
    fn lerp_rgb_walks_each_channel_and_keeps_the_bases_alpha() {
        let base = argb(0x20, 0x40, 0x80, 0x7F);
        let red = argb(0xFF, 0x00, 0x00, 0xFF);

        assert_eq!(lerp_rgb(base, red, 0.0), base, "0 is the untouched colour");
        assert_eq!(lerp_rgb(base, red, 1.0), argb(0xFF, 0x00, 0x00, 0x7F), "1 is the target's RGB");
        assert_eq!(lerp_rgb(base, red, 0.5), argb(0x90, 0x20, 0x40, 0x7F), "midway, channel-wise");

        // Out-of-range and non-finite strengths cannot wrap a channel or produce a
        // colour outside the two endpoints.
        assert_eq!(lerp_rgb(base, red, -1.0), base);
        assert_eq!(lerp_rgb(base, red, 2.0), lerp_rgb(base, red, 1.0));
        assert_eq!(lerp_rgb(base, red, f32::NAN), base, "NaN is no tint, not a wild colour");
    }

    /// A gradient fill runs from `from` at the start edge to `to` at the far edge
    /// and keeps the flat fill's antialiased silhouette.
    #[test]
    fn a_gradient_fill_runs_from_one_stop_to_the_other() {
        let mut c = Canvas::new(8, 4);
        c.fill_rounded_rect_gradient(
            0,
            0,
            8,
            4,
            [0.0; 4],
            argb(0x00, 0x00, 0x00, 0xFF),
            argb(0xFF, 0xFF, 0xFF, 0xFF),
            true,
        );

        // The gradient is sampled from each pixel's centre, so the outermost
        // columns sit *near* the stops rather than exactly on them: near-stop at
        // the left, far-stop at the right, monotonic in between.
        let red = |x: u32| c.pixel(x, 2) >> 16 & 0xFF;
        assert!(red(0) <= 0x20, "left edge is at the near stop ({})", red(0));
        assert!(red(7) >= 0xDF, "right edge is at the far stop ({})", red(7));
        assert!(red(0) < red(4) && red(4) < red(7), "the run is monotonic");

        // Vertical runs the same way down the rows.
        let mut v = Canvas::new(4, 8);
        v.fill_rounded_rect_gradient(
            0,
            0,
            4,
            8,
            [0.0; 4],
            argb(0x00, 0x00, 0x00, 0xFF),
            argb(0xFF, 0xFF, 0xFF, 0xFF),
            false,
        );
        let vred = |y: u32| v.pixel(2, y) >> 16 & 0xFF;
        assert!(vred(0) <= 0x20, "top row is at the near stop ({})", vred(0));
        assert!(vred(7) >= 0xDF, "bottom row is at the far stop ({})", vred(7));
        assert!(vred(0) < vred(4) && vred(4) < vred(7), "the run is monotonic");
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
