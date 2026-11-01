// state.rs — the reader half of the shared-state contract.
//
// The in-JVM agent publishes one fixed block to `%TEMP%\doppel-hud.state` every
// render frame (see adapter/common/hud/HudStateWriter.kt, §5.3 of the plan). This
// process only ever reads it: the file is opened read-only and nothing is ever
// written back, so there is no back channel through which the overlay could
// influence the game (§5.2).
//
// ── contract v3 (`#pragma pack(push,1)`, little-endian, 4096 bytes) ──────────
//
//   offset size field
//   0      4    uint32 magic      0x444F5050 ("DOPP")
//   4      4    uint32 version    3
//   8      4    uint32 count      entries written, <= 64
//   12     4    uint32 flags      bit0 wave on, bit1 spaced names, bit2 watermark on,
//                                 bits 3..5 mode (0 default, 1 rise, 2 vape,
//                                 3 tesseract, 4 modern),
//                                 bit6 orderPublished, bit7 positionLeft (the module
//                                 list on the left edge), bits 8..31 reserved (ignored)
//   16     4    uint32 primary    0x00RRGGBB
//   20     4    uint32 secondary  0x00RRGGBB
//   24     4    uint32 fps        0 = unknown, the FPS segment is omitted
//   28     4    uint32 scale_x1000 0 = unset, else the HUD scale x1000
//   32     24   char   nickname[24] UTF-8, NUL padded
//   56     4    uint32 opacity    0 = unset (100%), else background opacity percent
//   60     4    uint32 reserved
//   64     3584 entries[64]: the v1 entry, unchanged
//   3648   448  reserved trailer, so the block is exactly 4096 bytes
//
//   Entry, 56 bytes:
//     uint32 id       stable hash of the module name (FNV-1a 32)
//     uint8  enabled
//     uint8  flash    WORK-PULSE STRENGTH 0..255; the overlay tints the row by it
//     uint8  highlight draw the value in the accent colour
//     uint8  pad
//     char   name[24] UTF-8, NUL padded
//     char   value[24] UTF-8, NUL padded, empty = no value shown
//
// The declared block is `STATE_SIZE` = 4096 bytes; the header and the 64 entries
// occupy the first `USED_SIZE` = 3648 of them and the trailer is reserved. A
// reader has to find a full 4096-byte block there (a v1 block: a full 3600), and
// never reads past `USED_SIZE`.
//
// ── what v3 added, and why ──────────────────────────────────────────────────
//
//   * the mode widened from two bits (3..4) to three (3..5) for the Modern mode;
//   * `orderPublished` (flags bit 6): the user's manual module order must survive
//     to the overlay, which would otherwise sort the rows by width (the wedge);
//   * `scale_x1000` (offset 28) and `opacity` (offset 56): both were reserved in
//     v2 and are now the WebUI's own HUD scale and background opacity, each with
//     0 = "the writer has no opinion, use the overlay's default";
//   * `positionLeft` (flags bit 7): the WebUI's Position option — the module list
//     against the left edge rather than the reference's right one. This one is a
//     *reserved* bit of v3 rather than a new version: a reader that does not know
//     it already masks it off and no other field's meaning moves, so the wire
//     version stays 3 (the Kotlin writer sets the same bit);
//   * entry `flash` stopped being a bool and became a 0..255 work-pulse strength.
//     It is a *level*, not a flag: the overlay reads it as-is every frame and the
//     writer recomputes it every publish, so no decay state crosses the wire.
//
// ── contract v2, still accepted ─────────────────────────────────────────────
//
// v2 is the same 4096 bytes with mode in bits **3..4** only, `scale_x1000` and
// `opacity` left at reserved 0, no `orderPublished` and no `positionLeft` (both
// bits were still reserved there), and `flash` a bool. A v2 file
// can only be a leftover from an older build — the entries are identical — so it
// is decoded with the narrow mode mask, the two new fields stay unset and a
// non-zero `flash` byte reads as full strength (255) rather than as a strength of
// 1, which would be invisible.
//
// ── contract v1, still accepted ─────────────────────────────────────────────
//
// `HudStateWriter` used to publish the original 3600 bytes: the 16-byte header
// (`magic`, `version`, `count`, `pad`) followed by the same 64 entries. A *stale*
// file from a previous build can still be sitting at that path, so v1 stays
// readable rather than becoming a hard error. Nothing about the entries changed,
// so that block is read exactly as before and the header-only fields — nickname,
// FPS, flags, colours, scale, opacity, order — keep the caller's own defaults
// (`Snapshot::header` is `None`).
//
// A short file, a foreign magic or a version other than 1, 2 or 3 all mean "not
// ours": the caller renders nothing rather than half-decoding whatever happens to
// be at that path. `read_checked` additionally says *which* of those it was, so
// "%TEMP%\doppel-hud.state is a version 9 block" is a log line rather than a
// shrug.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub const MAGIC: u32 = 0x444F_5050; // "DOPP"
/// The version this reader prefers, and the one the v3 header is described for.
pub const VERSION: u32 = 3;
/// The previous block: the same 4096 bytes, but mode in two bits, `flash` a bool
/// and the two new header fields still reserved.
pub const V2_VERSION: u32 = 2;
/// The original block: a 16-byte header and the same entries.
pub const V1_VERSION: u32 = 1;
pub const MAX_MODULES: usize = 64;
pub const ENTRY_SIZE: usize = 56;
/// The v2/v3 header.
pub const HEADER_SIZE: usize = 64;
/// The v1 header: `magic`, `version`, `count`, `pad`.
pub const V1_HEADER_SIZE: usize = 16;
/// A v2/v3 block, entries included: the contract's declared 4096 bytes.
pub const STATE_SIZE: usize = 4096;
/// The bytes the header and the 64 entries actually occupy; the rest of the block
/// is the reserved trailer. Nothing past this offset is ever read.
pub const USED_SIZE: usize = HEADER_SIZE + MAX_MODULES * ENTRY_SIZE; // 3648
/// A v1 block, entries included — the original 3600 bytes, with no trailer.
pub const V1_STATE_SIZE: usize = V1_HEADER_SIZE + MAX_MODULES * ENTRY_SIZE; // 3600

// The contract's own arithmetic: the header and the entries have to fit inside
// the declared block, with the trailer left over. A mismatch here is a broken
// contract rather than a runtime condition, so it is checked at compile time.
const _: () = assert!(USED_SIZE <= STATE_SIZE);

// ── the header's `flags` word ───────────────────────────────────────────────

/// `m_Wave` — the Default mode's animation.
pub const FLAG_WAVE: u32 = 1 << 0;
/// `m_SpacedModules`.
pub const FLAG_SPACED: u32 = 1 << 1;
/// `m_Watermark`.
pub const FLAG_WATERMARK: u32 = 1 << 2;
/// The shift of the mode bits — the same in v2 and v3, only the width differs.
pub const MODE_SHIFT: u32 = 3;
/// v3: bits 3..5 inclusive — `ArrayListMode`, five values.
pub const MODE_MASK: u32 = 0b111 << MODE_SHIFT;
/// v2: bits 3..4 only. Bit 5 was reserved then, so a v2 writer that set it meant
/// nothing by it and a v2 reader must not turn it into a mode (it would read as
/// Modern, a mode v2 never had).
pub const V2_MODE_MASK: u32 = 0b11 << MODE_SHIFT;
/// `orderPublished` — draw the rows in the order they were published instead of
/// width-sorted (contract v3). The user's manual order in the WebUI is otherwise
/// destroyed by the reference's wedge.
pub const FLAG_ORDER_PUBLISHED: u32 = 1 << 6;
/// `positionLeft` — hang the module list off the **left** edge instead of the
/// reference's right one (the WebUI's Position option, the same switch `--position
/// left` gives). It is flags bit 7, which v3 left reserved: a reader that does not
/// know the bit already masks it off, no other field's meaning moves, so the wire
/// version stays 3.
pub const FLAG_POSITION_LEFT: u32 = 1 << 7;
/// Everything the v3 contract defines; bits 8..31 are reserved and ignored.
pub const DEFINED_MASK: u32 = FLAG_WAVE
    | FLAG_SPACED
    | FLAG_WATERMARK
    | MODE_MASK
    | FLAG_ORDER_PUBLISHED
    | FLAG_POSITION_LEFT;
/// The same for a v2 block, whose mode is two bits wide and which has neither an
/// `orderPublished` nor a `positionLeft` bit to read.
pub const V2_DEFINED_MASK: u32 = FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | V2_MODE_MASK;

/// The `ArrayListMode` combo (`ArrayList.h:14`), as the header's mode bits carry
/// it. The names are `hud::Mode`'s; the mapping between the two lives in `main`,
/// which is the only place that knows both vocabularies.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum StateMode {
    #[default]
    Default,
    Rise,
    Vape,
    Tesseract,
    /// v3 only. A v2 block can never decode to this (see `from_v2_flags`).
    Modern,
    /// v3 only, like Modern. The port's own glass-card mode — no source line,
    /// no v2 path can produce it.
    Aurora,
}

impl StateMode {
    /// bits 3..5, with every other bit of the word ignored — a writer that sets
    /// a reserved bit must not change which mode is drawn.
    pub fn from_flags(flags: u32) -> StateMode {
        match (flags & MODE_MASK) >> MODE_SHIFT {
            1 => StateMode::Rise,
            2 => StateMode::Vape,
            3 => StateMode::Tesseract,
            4 => StateMode::Modern,
            5 => StateMode::Aurora,
            _ => StateMode::Default,
        }
    }

    /// The v2 reading: bits 3..4 only. Kept as its own function rather than a
    /// parameter because the two masks are the whole difference between the
    /// versions, and getting it wrong silently changes the drawn mode.
    pub fn from_v2_flags(flags: u32) -> StateMode {
        match (flags & V2_MODE_MASK) >> MODE_SHIFT {
            1 => StateMode::Rise,
            2 => StateMode::Vape,
            3 => StateMode::Tesseract,
            _ => StateMode::Default,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            StateMode::Default => "default",
            StateMode::Rise => "rise",
            StateMode::Vape => "vape",
            StateMode::Tesseract => "tesseract",
            StateMode::Modern => "modern",
            StateMode::Aurora => "aurora",
        }
    }
}

/// The v2/v3 header, decoded. Only ever present for a version-2 or version-3
/// block: a v1 file has none of these fields, and `Snapshot::header` is `None`
/// for it.
#[derive(Clone, Debug, PartialEq)]
pub struct Header {
    /// Which version the block declared (2 or 3 for anything built here).
    pub version: u32,
    /// `flags` bit 0.
    pub wave: bool,
    /// `flags` bit 1.
    pub spaced: bool,
    /// `flags` bit 2.
    pub watermark: bool,
    /// The mode bits — 3..5 in v3, 3..4 in v2.
    pub mode: StateMode,
    /// `flags` bit 6. Always false for a v2 block, which has no such bit.
    pub order_published: bool,
    /// `flags` bit 7: the module list hangs off the left edge rather than the
    /// reference's right one. Always false for a v2 block, where the bit is still
    /// reserved — the same rule as `order_published`, one bit over.
    pub position_left: bool,
    /// `primary`, `0x00RRGGBB` → three 0..1 channels.
    pub primary: [f32; 3],
    /// `secondary`, same encoding.
    pub secondary: [f32; 3],
    /// 0 = unknown, and the watermark's FPS segment is dropped.
    pub fps: u32,
    /// `scale_x1000` → the multiplier, or `None` when the writer had no opinion
    /// (the field is 0). `None` is what lets `--scale` stay the default.
    pub scale: Option<f32>,
    /// `opacity` percent → 0..1, or `None` when it is 0 (unset = 100%).
    pub opacity: Option<f32>,
    /// `line_spacing` percent → the multiplier on the mode's own row gap, or `None`
    /// when it is 0 (unset = 100%). Separate from `scale` because the scale also
    /// changes the font, which cannot loosen a list that is merely too tight.
    pub line_spacing: Option<f32>,
    /// UTF-8, NUL padded. Empty = the nickname segment is dropped.
    pub nickname: String,
}

/// One published module row.
#[derive(Clone, Debug)]
pub struct Entry {
    /// FNV-1a of the name, as the writer computes it; the key for flash decay.
    pub id: u32,
    /// The writer only ever publishes enabled modules, so this is informational
    /// today; it is carried because the contract defines it.
    #[allow(dead_code)]
    pub enabled: bool,
    /// The work-pulse strength, 0..255 (contract v3). The overlay tints the row
    /// toward red by `flash / 255`; it never decays the value itself, because the
    /// writer recomputes it every publish.
    pub flash: u8,
    /// Draw the value in the accent colour instead of the dim one.
    pub highlight: bool,
    pub name: String,
    pub value: String,
}

/// One successful read plus the file's timestamp, which is how "the game is
/// still running" is decided (a writer that has stopped leaves the mtime behind).
pub struct Snapshot {
    pub entries: Vec<Entry>,
    pub modified: Option<SystemTime>,
    /// The v2 header, or `None` for a v1 block — see the module note.
    pub header: Option<Header>,
}

/// Why a read produced nothing. `read` folds all of these into `None`; the
/// startup log line prints `describe()` instead, because "there is no file" and
/// "the file is ours but version 3" are very different reports.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Refusal {
    /// No readable file at that path.
    Missing,
    /// Shorter than the block its header claims (the length actually found).
    Short(usize),
    /// Another product's file at that path (the magic actually found).
    Foreign(u32),
    /// Ours, but a version this reader does not know.
    Version(u32),
}

impl Refusal {
    pub fn describe(self) -> String {
        match self {
            Refusal::Missing => "no readable file".to_string(),
            Refusal::Short(len) => {
                format!("short ({len} bytes, need {V1_STATE_SIZE} for v1 or {STATE_SIZE} for v2/v3)")
            }
            Refusal::Foreign(magic) => format!("foreign magic 0x{magic:08X}"),
            Refusal::Version(version) => {
                format!(
                    "unknown version {version} (this reader knows {V1_VERSION}, \
                     {V2_VERSION} and {VERSION})"
                )
            }
        }
    }
}

/// `%TEMP%\doppel-hud.state`, the same path `HudStateWriter.defaultFile()` uses.
pub fn default_path() -> PathBuf {
    std::env::temp_dir().join("doppel-hud.state")
}

/// Read and validate the block. `None` covers every "not drawable" case: no file,
/// short file, wrong magic, unknown version.
pub fn read(path: &Path) -> Option<Snapshot> {
    read_checked(path).ok()
}

/// The same read, saying why it failed. The frame loop uses `read` — one refusal
/// is exactly like another there — while the startup line reports this.
pub fn read_checked(path: &Path) -> Result<Snapshot, Refusal> {
    let bytes = fs::read(path).map_err(|_| Refusal::Missing)?; // read-only: no write handle
    if bytes.len() < 8 {
        return Err(Refusal::Short(bytes.len()));
    }
    let magic = le_u32(&bytes, 0);
    if magic != MAGIC {
        return Err(Refusal::Foreign(magic));
    }

    let version = le_u32(&bytes, 4);
    let (header, header_size) = if version == VERSION || version == V2_VERSION {
        // One size covers both: the v3 header reuses v2's reserved words rather
        // than extending the block, so a v2 file is exactly as long as a v3 one.
        if bytes.len() < STATE_SIZE {
            return Err(Refusal::Short(bytes.len()));
        }
        (Some(decode_header(&bytes, version)), HEADER_SIZE)
    } else if version == V1_VERSION {
        if bytes.len() < V1_STATE_SIZE {
            return Err(Refusal::Short(bytes.len()));
        }
        (None, V1_HEADER_SIZE)
    } else {
        return Err(Refusal::Version(version));
    };

    let count = (le_u32(&bytes, 8) as usize).min(MAX_MODULES);
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        let base = header_size + i * ENTRY_SIZE;
        // v2's `flash` was a bool; mapping any non-zero byte to full strength
        // keeps an old file's "this module worked" looking the way it did, where
        // reading it as the raw strength would make it nearly invisible.
        let raw_flash = bytes[base + 5];
        entries.push(Entry {
            id: le_u32(&bytes, base),
            enabled: bytes[base + 4] != 0,
            flash: if version == V2_VERSION && raw_flash != 0 { 255 } else { raw_flash },
            highlight: bytes[base + 6] != 0,
            name: fixed_string(&bytes[base + NAME_OFFSET..base + NAME_OFFSET + NAME_BYTES]),
            value: fixed_string(&bytes[base + VALUE_OFFSET..base + VALUE_OFFSET + VALUE_BYTES]),
        });
    }

    // The mtime is read after the contents; a writer that is mid-update can only
    // make this look newer, which errs on "still alive" — the safe direction.
    let modified = fs::metadata(path).ok().and_then(|m| m.modified().ok());

    Ok(Snapshot { entries, modified, header })
}

/// The 64-byte header, already known to be present and to declare a version this
/// reader accepts (2 or 3). The version is what selects the two things it
/// changes: how wide the mode field is and whether the words v3 gave a meaning
/// to are read at all.
fn decode_header(bytes: &[u8], version: u32) -> Header {
    let v2 = version == V2_VERSION;
    // Reserved bits are masked off here so a writer that sets one cannot alias it
    // onto a defined bit downstream.
    let flags = le_u32(bytes, 12) & if v2 { V2_DEFINED_MASK } else { DEFINED_MASK };
    Header {
        version,
        wave: flags & FLAG_WAVE != 0,
        spaced: flags & FLAG_SPACED != 0,
        watermark: flags & FLAG_WATERMARK != 0,
        mode: if v2 { StateMode::from_v2_flags(flags) } else { StateMode::from_flags(flags) },
        order_published: flags & FLAG_ORDER_PUBLISHED != 0,
        // Bit 7 is defined for v3 and still reserved for v2, whose mask above
        // clears it — so a v2 block decodes `false` even if the byte is set.
        position_left: flags & FLAG_POSITION_LEFT != 0,
        primary: colour(le_u32(bytes, 16)),
        secondary: colour(le_u32(bytes, 20)),
        fps: le_u32(bytes, 24),
        // v2's words at these offsets are reserved and must stay unset: reading
        // them would invent a scale or an opacity no writer ever asked for.
        scale: if v2 { None } else { scale_of(le_u32(bytes, SCALE_OFFSET)) },
        opacity: if v2 { None } else { opacity_of(le_u32(bytes, OPACITY_OFFSET)) },
        line_spacing: if v2 { None } else { line_spacing_of(le_u32(bytes, LINE_SPACING_OFFSET)) },
        nickname: fixed_string(&bytes[NICKNAME_OFFSET..NICKNAME_OFFSET + NICKNAME_BYTES]),
    }
}

/// `scale_x1000` → the multiplier. 0 is the contract's "the writer has no
/// opinion" (the overlay's own default), and nothing else is special: the range
/// is the writer's business, and `Metrics::new` clamps whatever it is handed.
fn scale_of(raw: u32) -> Option<f32> {
    (raw != 0).then(|| raw as f32 / 1000.0)
}

/// `opacity` percent → 0..1. 0 is "unset" (100%), not "fully transparent", which
/// is why the two cases cannot be collapsed into one number.
fn opacity_of(raw: u32) -> Option<f32> {
    (raw != 0).then(|| raw.min(100) as f32 / 100.0)
}

/// `line_spacing` percent → the multiplier on the row gap. 0 is "unset" (100%),
/// the same convention as the other two v3 words, and the low end is clamped so a
/// writer cannot ask for a gap so small the rows overlap. The high end is clamped
/// to the contract's 200%.
fn line_spacing_of(raw: u32) -> Option<f32> {
    (raw != 0).then(|| raw.clamp(10, 200) as f32 / 100.0)
}

/// `0x00RRGGBB` → three 0..1 channels. The high byte is masked off rather than
/// read as an alpha the contract does not define.
fn colour(packed: u32) -> [f32; 3] {
    [
        ((packed >> 16) & 0xFF) as f32 / 255.0,
        ((packed >> 8) & 0xFF) as f32 / 255.0,
        (packed & 0xFF) as f32 / 255.0,
    ]
}

/// FNV-1a 32-bit, byte for byte what `HudStateWriter.idOf` computes, so a demo
/// or a test can key flash decay the way the real writer does.
pub fn fnv1a(name: &str) -> u32 {
    let mut hash: u32 = 0x811C_9DC5;
    for b in name.as_bytes() {
        hash ^= *b as u32;
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

const NICKNAME_OFFSET: usize = 32;
const NICKNAME_BYTES: usize = 24;
/// The three words v2 reserved and v3 defined.
const SCALE_OFFSET: usize = 28;
const OPACITY_OFFSET: usize = 56;
const LINE_SPACING_OFFSET: usize = 60;
const NAME_OFFSET: usize = 8;
const NAME_BYTES: usize = 24;
const VALUE_OFFSET: usize = 32;
const VALUE_BYTES: usize = 24;

fn le_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]])
}

/// A fixed field decoded up to its first NUL. Lossy on purpose: a truncated
/// multi-byte character must render as U+FFFD, never panic a cosmetic overlay.
fn fixed_string(field: &[u8]) -> String {
    let end = field.iter().position(|b| *b == 0).unwrap_or(field.len());
    String::from_utf8_lossy(&field[..end]).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A v1 block: 3600 bytes, the 16-byte header, one entry at offset 16.
    fn v1_block(magic: u32, version: u32, count: u32) -> Vec<u8> {
        let mut b = vec![0u8; V1_STATE_SIZE];
        b[0..4].copy_from_slice(&magic.to_le_bytes());
        b[4..8].copy_from_slice(&version.to_le_bytes());
        b[8..12].copy_from_slice(&count.to_le_bytes());
        put_entry(&mut b, V1_HEADER_SIZE, 0, "Velocity", "82%");
        b
    }

    /// A v2 block: 4096 bytes, the 64-byte header, entries from offset 64. It is
    /// the only block that is *not* the current contract, so everything that
    /// reads one has to say which version it means.
    fn v2_block(count: u32, flags: u32, nickname: &str, fps: u32) -> Vec<u8> {
        let mut b = vec![0u8; STATE_SIZE];
        b[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        b[4..8].copy_from_slice(&V2_VERSION.to_le_bytes());
        b[8..12].copy_from_slice(&count.to_le_bytes());
        b[12..16].copy_from_slice(&flags.to_le_bytes());
        b[16..20].copy_from_slice(&0x0078_566Bu32.to_le_bytes()); // 120,86,107
        b[20..24].copy_from_slice(&0x0092_96D6u32.to_le_bytes()); // 146,150,214
        b[24..28].copy_from_slice(&fps.to_le_bytes());
        b[32..32 + nickname.len()].copy_from_slice(nickname.as_bytes());
        for i in 0..count as usize {
            put_entry(&mut b, HEADER_SIZE, i, "Velocity", "82%");
        }
        b
    }

    /// A v3 block: the same 4096 bytes with the version-3 meaning attached to
    /// every word v2 left reserved.
    fn v3_block(
        count: u32,
        flags: u32,
        nickname: &str,
        fps: u32,
        scale_x1000: u32,
        opacity: u32,
    ) -> Vec<u8> {
        v3_block_spaced(count, flags, nickname, fps, scale_x1000, opacity, 0)
    }

    /// The same, with the line-spacing word filled in.
    fn v3_block_spaced(
        count: u32,
        flags: u32,
        nickname: &str,
        fps: u32,
        scale_x1000: u32,
        opacity: u32,
        line_spacing: u32,
    ) -> Vec<u8> {
        let mut b = vec![0u8; STATE_SIZE];
        b[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        b[4..8].copy_from_slice(&VERSION.to_le_bytes());
        b[8..12].copy_from_slice(&count.to_le_bytes());
        b[12..16].copy_from_slice(&flags.to_le_bytes());
        b[16..20].copy_from_slice(&0x0078_566Bu32.to_le_bytes());
        b[20..24].copy_from_slice(&0x0092_96D6u32.to_le_bytes());
        b[24..28].copy_from_slice(&fps.to_le_bytes());
        b[SCALE_OFFSET..SCALE_OFFSET + 4].copy_from_slice(&scale_x1000.to_le_bytes());
        b[32..32 + nickname.len()].copy_from_slice(nickname.as_bytes());
        b[OPACITY_OFFSET..OPACITY_OFFSET + 4].copy_from_slice(&opacity.to_le_bytes());
        b[LINE_SPACING_OFFSET..LINE_SPACING_OFFSET + 4].copy_from_slice(&line_spacing.to_le_bytes());
        for i in 0..count as usize {
            put_entry(&mut b, HEADER_SIZE, i, "Velocity", "82%");
        }
        b
    }

    fn put_entry(b: &mut [u8], header: usize, index: usize, name: &str, value: &str) {
        let o = header + index * ENTRY_SIZE;
        b[o..o + 4].copy_from_slice(&fnv1a(name).to_le_bytes());
        b[o + 4] = 1;
        b[o + 5] = 1;
        b[o + 6] = 1;
        b[o + 8..o + 8 + name.len()].copy_from_slice(name.as_bytes());
        b[o + 32..o + 32 + value.len()].copy_from_slice(value.as_bytes());
    }

    /// The entry's `flash` byte, which v3 redefined from a bool to a strength.
    fn put_flash(b: &mut [u8], header: usize, index: usize, flash: u8) {
        b[header + index * ENTRY_SIZE + 5] = flash;
    }

    fn write(name: &str, bytes: &[u8]) -> PathBuf {
        let path = std::env::temp_dir().join(name);
        fs::write(&path, bytes).unwrap();
        path
    }

    #[test]
    fn parses_a_valid_v2_block() {
        let path = write("doppel-hud-state-test-v2.bin", &v2_block(1, 0, "gtier", 144));
        let snap = read(&path).expect("a v2 block must parse");
        assert_eq!(snap.entries.len(), 1);
        let e = &snap.entries[0];
        assert_eq!(e.name, "Velocity");
        assert_eq!(e.value, "82%");
        assert_eq!(e.flash, 255, "a v2 bool flash reads as full strength");
        assert!(e.highlight && e.enabled);
        assert_eq!(e.id, fnv1a("Velocity"));
        let h = snap.header.expect("a v2 block carries a header");
        assert_eq!(h.version, V2_VERSION);
        assert_eq!(h.nickname, "gtier");
        assert_eq!(h.fps, 144);
        // The words v3 defined are still reserved in v2 and must stay unset.
        assert_eq!(h.scale, None);
        assert_eq!(h.opacity, None);
        assert!(!h.order_published);
        assert!(!h.position_left, "flags bit 7 is reserved in v2");
        let _ = fs::remove_file(&path);
    }

    /// The current contract, field by field: the three-bit mode with Modern in
    /// it, `orderPublished`, `scale_x1000`, `opacity` and a flash strength that
    /// is *not* collapsed to a bool.
    #[test]
    fn parses_a_v3_block_with_every_new_field() {
        let flags = FLAG_WAVE
            | FLAG_SPACED
            | FLAG_ORDER_PUBLISHED
            | ((StateMode::Modern as u32) << MODE_SHIFT);
        let mut bytes = v3_block(2, flags, "gtier", 144, 1250, 60);
        put_flash(&mut bytes, HEADER_SIZE, 0, 200);
        put_flash(&mut bytes, HEADER_SIZE, 1, 0);
        let path = write("doppel-hud-state-test-v3.bin", &bytes);

        let snap = read(&path).expect("a v3 block must parse");
        assert_eq!(snap.entries.len(), 2);
        assert_eq!(snap.entries[0].flash, 200, "the strength is read as-is");
        assert_eq!(snap.entries[1].flash, 0, "0 is 'not working', not 'unknown'");

        let h = snap.header.expect("a v3 block carries a header");
        assert_eq!(h.version, VERSION);
        assert_eq!(h.mode, StateMode::Modern);
        assert_eq!(h.mode.label(), "modern");
        assert!(h.wave && h.spaced && !h.watermark);
        assert!(h.order_published, "flags bit 6");
        assert_eq!(h.scale, Some(1.25), "scale_x1000/1000");
        assert_eq!(h.opacity, Some(0.6), "percent/100");
        assert_eq!(h.nickname, "gtier");
        assert_eq!(h.fps, 144);

        // The two new words are "unset" at 0 — the overlay's own default — which
        // is why they decode to an Option rather than to a zero.
        let bare = write("doppel-hud-state-test-v3-bare.bin", &v3_block(0, 0, "", 0, 0, 0));
        let h = read(&bare).unwrap().header.unwrap();
        assert_eq!(h.scale, None);
        assert_eq!(h.opacity, None);
        assert!(!h.order_published);
        assert_eq!(h.mode, StateMode::Default);

        // An opacity above 100 is clamped: the field is a percent, and a value
        // over it would be an alpha greater than 1.
        let over = write("doppel-hud-state-test-v3-over.bin", &v3_block(0, 0, "", 0, 4000, 250));
        let h = read(&over).unwrap().header.unwrap();
        assert_eq!(h.scale, Some(4.0));
        assert_eq!(h.opacity, Some(1.0));

        // The line-spacing word follows the same "0 = unset" rule, and its low end
        // is clamped so a writer cannot ask for rows that sit on top of each other.
        let spaced =
            write("doppel-hud-state-test-v3-spaced.bin", &v3_block_spaced(0, 0, "", 0, 0, 0, 150));
        assert_eq!(read(&spaced).unwrap().header.unwrap().line_spacing, Some(1.5));
        let squashed =
            write("doppel-hud-state-test-v3-squashed.bin", &v3_block_spaced(0, 0, "", 0, 0, 0, 1));
        assert_eq!(read(&squashed).unwrap().header.unwrap().line_spacing, Some(0.1));
        assert_eq!(read(&bare).unwrap().header.unwrap().line_spacing, None, "0 = unset");

        for path in [path, bare, over, spaced, squashed] {
            let _ = fs::remove_file(&path);
        }
    }

    /// A v1 block still reads, and reports no header at all — which is what
    /// leaves every header-only field at the caller's own default.
    #[test]
    fn a_v1_block_still_reads_and_carries_no_header() {
        let path = write("doppel-hud-state-test-v1.bin", &v1_block(MAGIC, V1_VERSION, 1));
        let snap = read(&path).expect("a v1 block must still parse");
        assert_eq!(snap.entries.len(), 1);
        assert_eq!(snap.entries[0].name, "Velocity");
        assert_eq!(snap.entries[0].flash, 1, "v1's flash byte is already a value");
        assert!(snap.header.is_none());
        let _ = fs::remove_file(&path);
    }

    /// The header's meta comes off the 4096-byte block at the documented offsets,
    /// and a v1 block of the same length is read from the v1 header size — the
    /// entries start 48 bytes earlier there, so a mis-sized header would decode
    /// garbage.
    #[test]
    fn the_versions_read_their_entries_from_their_own_header_size() {
        assert_eq!(HEADER_SIZE, 64);
        assert_eq!(V1_HEADER_SIZE, 16);
        assert_eq!(STATE_SIZE, 4096);
        assert_eq!(USED_SIZE, 3648, "the header and 64 entries, trailer excluded");
        assert_eq!(V1_STATE_SIZE, 3600);
        assert_eq!(SCALE_OFFSET, 28, "the contract's own offset");
        assert_eq!(OPACITY_OFFSET, 56);
        assert_eq!(DEFINED_MASK, 0xFF, "bits 0..7 are the whole defined set now that bit 7 is positionLeft");
        assert_eq!(V2_DEFINED_MASK, 0x1F, "v2 still has bit 7 reserved, and no order bit either");

        // The v1 entry sits at 16..72; the v3 entry at 64..120. A v3 header's
        // nickname bytes land inside the v1 entry's name field, so this pins the
        // two apart.
        let v1 = write("doppel-hud-state-test-v1-offset.bin", &v1_block(MAGIC, V1_VERSION, 1));
        let v1_snap = read_checked(&v1).unwrap();
        assert_eq!(v1_snap.entries[0].name, "Velocity");
        let v3 = write("doppel-hud-state-test-v3-offset.bin", &v3_block(1, 0, "Velocity", 60, 0, 0));
        let v3_snap = read_checked(&v3).unwrap();
        assert_eq!(v3_snap.entries[0].name, "Velocity");
        assert_eq!(v3_snap.header.unwrap().nickname, "Velocity");
        let _ = fs::remove_file(&v1);
        let _ = fs::remove_file(&v3);
    }

    /// `flags` bit for bit: the three switches, the three mode bits with Modern
    /// at 4, `orderPublished`, and reserved bits ignored rather than aliased onto
    /// a defined one.
    #[test]
    fn the_flags_word_decodes_switches_and_mode_bits() {
        let decode = |flags: u32| decode_header(&v3_block(0, flags, "", 0, 0, 0), VERSION);

        // Every defined bit on its own.
        assert!(decode(FLAG_WAVE).wave);
        assert!(decode(FLAG_SPACED).spaced);
        assert!(decode(FLAG_WATERMARK).watermark);
        assert!(decode(FLAG_ORDER_PUBLISHED).order_published);
        assert!(decode(FLAG_POSITION_LEFT).position_left);
        assert!(!decode(0).wave && !decode(0).spaced && !decode(0).watermark);
        assert!(!decode(0).position_left);
        assert!(!decode(FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK).order_published);

        // The mode is bits 3..5 and nothing else.
        for (bits, want) in [
            (0b000u32 << 3, StateMode::Default),
            (0b001 << 3, StateMode::Rise),
            (0b010 << 3, StateMode::Vape),
            (0b011 << 3, StateMode::Tesseract),
            (0b100 << 3, StateMode::Modern),
        ] {
            assert_eq!(decode(bits).mode, want, "mode bits {bits:#07b}");
            assert_eq!(
                StateMode::from_flags(FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | bits),
                want
            );
        }

        // Reserved bits 8..31 change nothing at all: not the switches, not the
        // order, not the position and not the mode. (Bit 7 used to be in this
        // set; it is `positionLeft` now.)
        let defined =
            decode(FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | FLAG_ORDER_PUBLISHED | (2 << MODE_SHIFT));
        let noisy = decode(
            FLAG_WAVE
                | FLAG_SPACED
                | FLAG_WATERMARK
                | FLAG_ORDER_PUBLISHED
                | (2 << MODE_SHIFT)
                | 0xFFFF_FF00,
        );
        assert_eq!(defined.wave, noisy.wave);
        assert_eq!(defined.spaced, noisy.spaced);
        assert_eq!(defined.watermark, noisy.watermark);
        assert_eq!(defined.order_published, noisy.order_published);
        assert_eq!(defined.position_left, noisy.position_left);
        assert_eq!(defined.mode, noisy.mode);
        assert_eq!(noisy.mode, StateMode::Vape);
        assert_eq!(MODE_MASK, 0x38);
        assert_eq!(V2_MODE_MASK, 0x18);
        assert_eq!(StateMode::from_flags(!DEFINED_MASK), StateMode::Default);
    }

    /// The v2 reading is genuinely two bits wide: a v2 writer's reserved bit 5
    /// must not come back as the Modern mode v2 never had, while the same word
    /// decoded as v3 does mean Modern.
    #[test]
    fn a_v2_block_decodes_its_mode_from_two_bits() {
        let modern_bits = 0b100 << MODE_SHIFT; // bit 5 alone
        let b = v2_block(0, modern_bits, "", 0);
        assert_eq!(decode_header(&b, V2_VERSION).mode, StateMode::Default);
        assert_eq!(decode_header(&b, VERSION).mode, StateMode::Modern);

        // The four values v2 does have still decode.
        for (bits, want) in [
            (0b00u32 << 3, StateMode::Default),
            (0b01 << 3, StateMode::Rise),
            (0b10 << 3, StateMode::Vape),
            (0b11 << 3, StateMode::Tesseract),
        ] {
            assert_eq!(StateMode::from_v2_flags(bits), want, "v2 mode bits {bits:#07b}");
            assert_eq!(decode_header(&v2_block(0, bits, "", 0), V2_VERSION).mode, want);
        }

        // And v2 has no `orderPublished` bit to read: bit 6 was reserved there.
        let b = v2_block(0, FLAG_ORDER_PUBLISHED | modern_bits, "", 0);
        assert!(!decode_header(&b, V2_VERSION).order_published);
        assert_eq!(decode_header(&b, V2_VERSION).mode, StateMode::Default);
    }

    /// `positionLeft` is a v3 use of a bit v3 left reserved: a reader that does not
    /// know it already masks it off and no other field's meaning moves, which is why
    /// the wire version stays 3. A v2 block really does have the bit reserved, so
    /// there it must decode as `false` however the byte is set.
    #[test]
    fn the_position_bit_decodes_only_for_v3() {
        // v3: bit 7 set is "the module list on the left edge"; clear is the
        // reference's own right-hand placement.
        let on = v3_block(0, FLAG_POSITION_LEFT, "", 0, 0, 0);
        assert!(decode_header(&on, VERSION).position_left);
        assert!(!decode_header(&v3_block(0, 0, "", 0, 0, 0), VERSION).position_left);

        // It is its own bit: the switches and the mode around it are untouched.
        let set = decode_header(
            &v3_block(
                0,
                FLAG_POSITION_LEFT | FLAG_WAVE | FLAG_ORDER_PUBLISHED | (2 << MODE_SHIFT),
                "",
                0,
                0,
                0,
            ),
            VERSION,
        );
        assert!(set.position_left && set.wave && set.order_published);
        assert!(!set.spaced && !set.watermark);
        assert_eq!(set.mode, StateMode::Vape);
        assert_eq!(DEFINED_MASK, 0x7F | FLAG_POSITION_LEFT, "bit 7 is in the defined set");

        // Through the file reader, not just the decoder: a v3 block whose flags word
        // is nothing but bit 7 still parses and reports the left placement.
        let path = write("doppel-hud-state-test-v3-position.bin", &on);
        assert!(read(&path).expect("a v3 block must parse").header.unwrap().position_left);
        let _ = fs::remove_file(&path);

        // v2 has no such bit: the same word reads as `false` there and as `true` when
        // decoded as v3, which is the whole reason the two masks differ.
        let v2 = v2_block(0, FLAG_POSITION_LEFT, "", 0);
        assert!(!decode_header(&v2, V2_VERSION).position_left);
        assert!(decode_header(&v2, VERSION).position_left);
        assert_eq!(V2_DEFINED_MASK & FLAG_POSITION_LEFT, 0, "not part of the v2 mask at all");

        // A v2 block with every defined bit plus bit 7: the switches and the mode
        // still decode and only the position is dropped.
        let h = decode_header(&v2_block(0, V2_DEFINED_MASK | FLAG_POSITION_LEFT, "", 0), V2_VERSION);
        assert!(h.wave && h.spaced && h.watermark);
        assert_eq!(h.mode, StateMode::Tesseract, "v2's 0b11 is Tesseract");
        assert!(!h.position_left && !h.order_published);
    }

    #[test]
    fn the_header_colours_are_0x00rrggbb() {
        let h = decode_header(&v3_block(0, 0, "", 0, 0, 0), VERSION);
        assert_eq!(h.primary, [120.0 / 255.0, 86.0 / 255.0, 107.0 / 255.0]);
        assert_eq!(h.secondary, [146.0 / 255.0, 150.0 / 255.0, 214.0 / 255.0]);

        // The high byte is not an alpha and must be ignored.
        let mut b = v3_block(0, 0, "", 0, 0, 0);
        b[16..20].copy_from_slice(&0xFF12_3456u32.to_le_bytes());
        assert_eq!(decode_header(&b, VERSION).primary, [0x12 as f32 / 255.0, 0x34 as f32 / 255.0, 0x56 as f32 / 255.0]);
    }

    #[test]
    fn the_nickname_and_fps_come_off_the_header() {
        let h = decode_header(&v3_block(0, 0, "player", 60, 0, 0), VERSION);
        assert_eq!(h.nickname, "player");
        assert_eq!(h.fps, 60);

        // NUL padded: the field ends at the first NUL, whatever follows it inside
        // the 24 bytes.
        let mut b = v3_block(0, 0, "", 30, 0, 0);
        b[32..56].copy_from_slice(b"nick\0xxxxxxxxxxxxxxxxxxx");
        assert_eq!(decode_header(&b, VERSION).nickname, "nick");
        // A field with no NUL at all reads its whole 24 bytes.
        let b = v3_block(0, 0, &"a".repeat(24), 30, 0, 0);
        assert_eq!(decode_header(&b, VERSION).nickname, "a".repeat(24));

        // An all-NUL field is the empty nickname, and fps 0 stays 0 (the painter
        // drops both segments rather than inventing one).
        let h = decode_header(&v3_block(0, 0, "", 0, 0, 0), VERSION);
        assert_eq!(h.nickname, "");
        assert_eq!(h.fps, 0);

        // A truncated multi-byte character is replaced, never a panic.
        let mut b = v3_block(0, 0, "", 0, 0, 0);
        b[32] = 0xE2; // the first byte of a three-byte character, then NULs
        assert_eq!(decode_header(&b, VERSION).nickname, "\u{FFFD}");
    }

    #[test]
    fn rejects_magic_version_and_short_files() {
        let path = std::env::temp_dir().join("doppel-hud-state-test-bad.bin");

        fs::write(&path, v1_block(0xDEAD_BEEF, V1_VERSION, 1)).unwrap();
        assert!(read(&path).is_none(), "wrong magic must not draw");
        assert!(matches!(read_checked(&path), Err(Refusal::Foreign(0xDEAD_BEEF))));

        // A version this reader has never known, and one from a future that
        // widened the block again: both are refused, never half-decoded.
        for bad in [99u32, 4] {
            fs::write(&path, v1_block(MAGIC, bad, 1)).unwrap();
            assert!(read(&path).is_none(), "unknown version must not draw");
            assert!(matches!(read_checked(&path), Err(Refusal::Version(v)) if v == bad));
        }
        assert!(Refusal::Version(99).describe().contains("99"), "the version seen is in the line");
        assert!(
            Refusal::Version(99).describe().contains('3'),
            "and which versions this reader does know"
        );

        // A file that is not even a header long, and one that is a header but no
        // entries: both are short, and neither is mistaken for another product's.
        fs::write(&path, vec![0u8; 4]).unwrap();
        assert!(matches!(read_checked(&path), Err(Refusal::Short(4))));
        let mut header_only = v1_block(MAGIC, V1_VERSION, 1);
        header_only.truncate(64);
        fs::write(&path, &header_only).unwrap();
        assert!(matches!(read_checked(&path), Err(Refusal::Short(64))));

        // A current-version header with a v1-sized body is short too: the entries
        // are what the length has to cover, trailer or no trailer.
        for mut truncated in [v2_block(1, 0, "n", 60), v3_block(1, 0, "n", 60, 1000, 50)] {
            truncated.truncate(V1_STATE_SIZE);
            fs::write(&path, &truncated).unwrap();
            assert!(matches!(read_checked(&path), Err(Refusal::Short(V1_STATE_SIZE))));
        }

        // A full 4096-byte block is not short: the reader is not waiting for
        // anything past `USED_SIZE`, and the trailer is simply ignored.
        let mut padded = v3_block(1, 0, "n", 60, 0, 0);
        assert_eq!(padded.len(), STATE_SIZE);
        padded[USED_SIZE..].fill(0xAB);
        fs::write(&path, &padded).unwrap();
        assert_eq!(read(&path).unwrap().entries.len(), 1);

        let _ = fs::remove_file(&path);
        assert!(matches!(
            read_checked(Path::new("no-such-file-here.bin")),
            Err(Refusal::Missing)
        ));
    }

    /// More entries than the block holds are clamped, never read past the end.
    #[test]
    fn the_count_is_clamped_to_the_block() {
        let path = write("doppel-hud-state-test-count.bin", &v3_block(1, 0, "", 0, 0, 0));
        let mut bytes = fs::read(&path).unwrap();
        bytes[8..12].copy_from_slice(&999u32.to_le_bytes());
        fs::write(&path, &bytes).unwrap();
        assert_eq!(read(&path).unwrap().entries.len(), MAX_MODULES);
        let _ = fs::remove_file(&path);
    }
}
