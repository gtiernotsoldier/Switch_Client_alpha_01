// draw.rs — the pixel half of the overlay, deliberately free of Win32.
//
// The layered window is fed a top-down 32bpp DIB whose pixels are BGRA with
// premultiplied alpha: that is the one format UpdateLayeredWindow blends
// correctly per pixel. GDI cannot produce it for us — RoundRect has no alpha
// channel and no antialiasing — so the card is rasterised here and the result is
// copied straight into the DIB. Keeping this side pure (numbers in, pixels out,
// no handles and no globals) is what lets the geometry be unit-tested on any
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
    w: u32,
    h: u32,
    px: Vec<u32>,
}

impl Canvas {
    /// Allocate a fully transparent surface. `w`/`h` are physical pixels, which
    /// is also what the caller passes to CreateDIBSection — the two must agree
    /// or the copy at blit time would run off the end of the DIB.
    pub fn new(w: u32, h: u32) -> Canvas {
        Canvas { w, h, px: vec![0u32; w as usize * h as usize] }
    }

    /// The raw words, for handing to the DIB. BGRA byte order on little-endian
    /// Windows, which is the only target this binary has.
    pub fn pixels(&self) -> &[u32] {
        &self.px
    }

    /// A crisp axis-aligned rectangle: the rounded-rect path with radius 0 still
    /// antialiases the rim, but a rectangle whose edges land on pixel boundaries
    /// covers those pixels completely, so nothing is softened.
    pub fn fill_rect(&mut self, x: i32, y: i32, w: u32, h: u32, colour: u32) {
        self.fill_rounded_rect(x, y, w, h, 0.0, colour);
    }

    /// Fill a rounded rectangle, blended over whatever is already there.
    ///
    /// `colour` is 0xAARRGGBB and `radius` is in pixels; the radius is clamped to
    /// half the shorter side, so callers can pass "half the height" for a pill
    /// without checking the box first.
    pub fn fill_rounded_rect(&mut self, x: i32, y: i32, w: u32, h: u32, radius: f32, colour: u32) {
        if w == 0 || h == 0 {
            return;
        }

        let half_w = w as f32 / 2.0;
        let half_h = h as f32 / 2.0;
        let r = radius.min(half_w).min(half_h).max(0.0);
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
                let dx = (px as f32 + 0.5 - cx).abs() - (half_w - r);
                let dy = (py as f32 + 0.5 - cy).abs() - (half_h - r);
                let ox = dx.max(0.0);
                let oy = dy.max(0.0);
                let outside = (ox * ox + oy * oy).sqrt();
                let distance = outside + dx.max(dy).min(0.0) - r;
                let coverage = (0.5 - distance).max(0.0).min(1.0);
                if coverage > 0.0 {
                    self.blend(px, py, colour, coverage);
                }
            }
        }
    }

    /// Source-over blend of one pixel. Both the source we build here and the
    /// destination already in the buffer are premultiplied, so the whole
    /// composite reduces to `src + dst * (1 - src_alpha)` on every channel —
    /// including alpha itself, which is why no separate divide is needed.
    fn blend(&mut self, x: i32, y: i32, colour: u32, coverage: f32) {
        if x < 0 || y < 0 || x >= self.w as i32 || y >= self.h as i32 {
            return;
        }
        let index = y as usize * self.w as usize + x as usize;

        let (sr, sg, sb, sa) = unpack(colour);
        // Coverage multiplies the source alpha, so a pixel on the rim of the card
        // is exactly as transparent as the rim covers it.
        let a = sa as f32 / 255.0 * coverage;
        let inv = 1.0 - a;

        let dst = self.px[index];
        let b = to_byte(sb as f32 * a + (dst & 0xFF) as f32 * inv);
        let g = to_byte(sg as f32 * a + ((dst >> 8) & 0xFF) as f32 * inv);
        let r = to_byte(sr as f32 * a + ((dst >> 16) & 0xFF) as f32 * inv);
        let alpha = to_byte(sa as f32 * a + ((dst >> 24) & 0xFF) as f32 * inv);
        self.px[index] = b | (g << 8) | (r << 16) | (alpha << 24);
    }
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
        c.fill_rounded_rect(0, 0, 20, 20, 10.0, argb(0x0D, 0x11, 0x1C, 0xCC));

        // (0,0) is outside a 10px corner arc, so nothing may be written there.
        assert_eq!(c.pixels()[0], 0);
        // The centre is fully covered, so it must carry the requested alpha.
        assert_eq!(c.pixels()[10 * 20 + 10] >> 24, 0xCC);
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
}
