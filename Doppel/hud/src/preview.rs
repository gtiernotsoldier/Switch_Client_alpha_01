// preview.rs — the review path for `--dump`: a 32-bit BMP of one frame plus an
// ASCII downsample of the same pixels.
//
// Neither is part of the live overlay. They exist because the card is only ever
// composited on top of a running game, which makes it impossible to look at from
// a test run: `--dump` renders a representative frame with no window at all and
// writes it out, and the ASCII preview makes the shape judgeable from a
// terminal even when nothing can display a bitmap.

use std::io;
use std::path::Path;

use crate::draw::Canvas;

/// The ramp, darkest to brightest. Luminance is mapped onto it.
const RAMP: &[u8] = b" .:-=+*#%@";

/// The card is translucent, so a BMP has to show it over *something*. This is
/// the opaque backdrop the dump composites onto — a neutral dark desk, chosen so
/// the pill's own translucency and the drop shadow stay visible.
pub fn flatten_pixel(src: u32, bg: u32) -> (u8, u8, u8) {
    let a = ((src >> 24) & 0xFF) as f32 / 255.0;
    let mix = |shift: u32| -> u8 {
        let s = ((src >> shift) & 0xFF) as f32;
        let b = ((bg >> shift) & 0xFF) as f32;
        (s + b * (1.0 - a)).round().clamp(0.0, 255.0) as u8
    };
    (mix(16), mix(8), mix(0))
}

fn luminance(src: u32, bg: u32) -> f32 {
    let (r, g, b) = flatten_pixel(src, bg);
    (0.299 * r as f32 + 0.587 * g as f32 + 0.114 * b as f32) / 255.0
}

/// Write `canvas` as a top-down-image / bottom-up-file 32bpp BMP, composited
/// over `bg` so the result is an ordinary opaque picture any viewer can open.
pub fn write_bmp(path: &Path, canvas: &Canvas, bg: u32) -> io::Result<()> {
    let w = canvas.width();
    let h = canvas.height();
    let image_size = w * h * 4;
    let file_size = 14 + 40 + image_size;

    let mut out: Vec<u8> = Vec::with_capacity(file_size as usize);
    // BITMAPFILEHEADER
    out.extend_from_slice(b"BM");
    out.extend_from_slice(&file_size.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&(14u32 + 40).to_le_bytes());
    // BITMAPINFOHEADER
    out.extend_from_slice(&40u32.to_le_bytes());
    out.extend_from_slice(&(w as i32).to_le_bytes());
    out.extend_from_slice(&(h as i32).to_le_bytes()); // positive = rows bottom-up
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&32u16.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // BI_RGB
    out.extend_from_slice(&image_size.to_le_bytes());
    out.extend_from_slice(&2835i32.to_le_bytes()); // ~72 dpi
    out.extend_from_slice(&2835i32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());

    for y in (0..h).rev() {
        for x in 0..w {
            let (r, g, b) = flatten_pixel(canvas.pixel(x, y), bg);
            out.push(b);
            out.push(g);
            out.push(r);
            out.push(0xFF);
        }
    }

    std::fs::write(path, out)
}

/// Downsample to `cols` x `rows` characters on mean block luminance.
///
/// The mean, not the brightest pixel: a peak sample makes every block that
/// touches a glyph as bright as the backdrop, and the shape being judged — the
/// pill's dark rounded body, its shadow, the row bands — disappears. Averaged,
/// the pill reads as a distinctly darker rounded block with the wordmark inside
/// it, and each row reads as a band with a name and a value group.
pub fn ascii_preview(canvas: &Canvas, bg: u32, cols: usize, rows: usize) -> String {
    let w = canvas.width().max(1) as usize;
    let h = canvas.height().max(1) as usize;
    let mut out = String::with_capacity((cols + 1) * rows);

    for ry in 0..rows {
        let y0 = ry * h / rows;
        let y1 = (((ry + 1) * h) / rows).max(y0 + 1).min(h);
        for rx in 0..cols {
            let x0 = rx * w / cols;
            let x1 = (((rx + 1) * w) / cols).max(x0 + 1).min(w);
            let mut sum = 0.0f32;
            let mut count = 0.0f32;
            for y in y0..y1 {
                for x in x0..x1 {
                    sum += luminance(canvas.pixel(x as u32, y as u32), bg);
                    count += 1.0;
                }
            }
            let level = sum / count.max(1.0);
            let index = (level * (RAMP.len() - 1) as f32).round() as usize;
            out.push(RAMP[index.min(RAMP.len() - 1)] as char);
        }
        out.push('\n');
    }

    out
}
