// state.rs — the reader half of the shared-state contract.
//
// The in-JVM agent publishes one fixed block to `%TEMP%\doppel-hud.state` every
// render frame (see adapter/common/hud/HudStateWriter.kt, §5.3 of the plan). This
// process only ever reads it: the file is opened read-only and nothing is ever
// written back, so there is no back channel through which the overlay could
// influence the game (§5.2).
//
// ── contract v2 (`#pragma pack(push,1)`, little-endian, 4096 bytes) ──────────
//
//   offset size field
//   0      4    uint32 magic      0x444F5050 ("DOPP")
//   4      4    uint32 version    2
//   8      4    uint32 count      entries written, <= 64
//   12     4    uint32 flags      bit0 wave on, bit1 spaced names, bit2 watermark on,
//                                 bits 3..4 mode (0 default, 1 rise, 2 vape, 3 tesseract),
//                                 bits 5..31 reserved (ignored)
//   16     4    uint32 primary    0x00RRGGBB
//   20     4    uint32 secondary  0x00RRGGBB
//   24     4    uint32 fps        0 = unknown, the FPS segment is omitted
//   28     4    uint32 reserved
//   32     24   char   nickname[24] UTF-8, NUL padded
//   56     4    uint32 reserved
//   60     4    uint32 reserved
//   64     3584 entries[64]: the v1 entry, unchanged
//   3648   448  reserved trailer, so the block is exactly 4096 bytes
//
//   Entry, 56 bytes:
//     uint32 id       stable hash of the module name (FNV-1a 32)
//     uint8  enabled
//     uint8  flash    the module worked recently; this side decays it (D4)
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
// ── contract v1, still accepted ─────────────────────────────────────────────
//
// `HudStateWriter` used to publish the original 3600 bytes: the 16-byte header
// (`magic`, `version`, `count`, `pad`) followed by the same 64 entries. It writes
// v2 now, but a *stale* file from a previous build can still be sitting at that
// path, so v1 stays readable rather than becoming a hard error. Nothing about the
// entries changed, so that block is read exactly as before and the v2-only fields —
// nickname, FPS, flags, colours — simply keep the caller's own defaults
// (`Snapshot::header` is `None`).
//
// A short file, a foreign magic or a version other than 1 or 2 all mean "not
// ours": the caller renders nothing rather than half-decoding whatever happens to
// be at that path. `read_checked` additionally says *which* of those it was, so
// "%TEMP%\doppel-hud.state is a version 3 block" is a log line rather than a
// shrug.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub const MAGIC: u32 = 0x444F_5050; // "DOPP"
/// The version this reader prefers, and the one the v2 header is described for.
pub const VERSION: u32 = 2;
/// The original block: a 16-byte header and the same entries.
pub const V1_VERSION: u32 = 1;
pub const MAX_MODULES: usize = 64;
pub const ENTRY_SIZE: usize = 56;
/// The v2 header.
pub const HEADER_SIZE: usize = 64;
/// The v1 header: `magic`, `version`, `count`, `pad`.
pub const V1_HEADER_SIZE: usize = 16;
/// A v2 block, entries included: the contract's declared 4096 bytes.
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

// ── the v2 header's `flags` word ────────────────────────────────────────────

/// `m_Wave` — the Default mode's animation.
pub const FLAG_WAVE: u32 = 1 << 0;
/// `m_SpacedModules`.
pub const FLAG_SPACED: u32 = 1 << 1;
/// `m_Watermark`.
pub const FLAG_WATERMARK: u32 = 1 << 2;
/// The shift of the two mode bits.
pub const MODE_SHIFT: u32 = 3;
/// bits 3..4 inclusive: `ArrayListMode`.
pub const MODE_MASK: u32 = 0b11 << MODE_SHIFT;
/// Everything the contract defines; bits 5..31 are reserved and ignored.
pub const DEFINED_MASK: u32 = FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | MODE_MASK;

/// The `ArrayListMode` combo (`ArrayList.h:14`), as the header's bits 3..4 carry
/// it. The names are `hud::Mode`'s; the mapping between the two lives in `main`,
/// which is the only place that knows both vocabularies.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum StateMode {
    #[default]
    Default,
    Rise,
    Vape,
    Tesseract,
}

impl StateMode {
    /// bits 3..4, with every other bit of the word ignored — a writer that sets
    /// a reserved bit must not change which mode is drawn.
    pub fn from_flags(flags: u32) -> StateMode {
        match (flags & MODE_MASK) >> MODE_SHIFT {
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
        }
    }
}

/// The v2 header, decoded. Only ever present for a version-2 block: a v1 file has
/// none of these fields, and `Snapshot::header` is `None` for it.
#[derive(Clone, Debug, PartialEq)]
pub struct Header {
    /// Which version the block declared (2 for anything built here).
    pub version: u32,
    /// `flags` bit 0.
    pub wave: bool,
    /// `flags` bit 1.
    pub spaced: bool,
    /// `flags` bit 2.
    pub watermark: bool,
    /// `flags` bits 3..4.
    pub mode: StateMode,
    /// `primary`, `0x00RRGGBB` → three 0..1 channels.
    pub primary: [f32; 3],
    /// `secondary`, same encoding.
    pub secondary: [f32; 3],
    /// 0 = unknown, and the watermark's FPS segment is dropped.
    pub fps: u32,
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
    /// "This module worked recently." A flag, not a level — the fade lives here.
    pub flash: bool,
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
                format!("short ({len} bytes, need {V1_STATE_SIZE} for v1 or {STATE_SIZE} for v2)")
            }            Refusal::Foreign(magic) => format!("foreign magic 0x{magic:08X}"),
            Refusal::Version(version) => {
                format!("unknown version {version} (this reader knows {V1_VERSION} and {VERSION})")
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
    let (header, header_size) = if version == VERSION {
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
        entries.push(Entry {
            id: le_u32(&bytes, base),
            enabled: bytes[base + 4] != 0,
            flash: bytes[base + 5] != 0,
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

/// The 64-byte v2 header, already known to be present and to declare version 2.
fn decode_header(bytes: &[u8], version: u32) -> Header {
    // Bits 5..31 are reserved: masked off here so a writer that sets one cannot
    // alias it onto a defined bit downstream.
    let flags = le_u32(bytes, 12) & DEFINED_MASK;
    Header {
        version,
        wave: flags & FLAG_WAVE != 0,
        spaced: flags & FLAG_SPACED != 0,
        watermark: flags & FLAG_WATERMARK != 0,
        mode: StateMode::from_flags(flags),
        primary: colour(le_u32(bytes, 16)),
        secondary: colour(le_u32(bytes, 20)),
        fps: le_u32(bytes, 24),
        nickname: fixed_string(&bytes[NICKNAME_OFFSET..NICKNAME_OFFSET + NICKNAME_BYTES]),
    }
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

    /// A v2 block: 4096 bytes, the 64-byte header, entries from offset 64.
    fn v2_block(count: u32, flags: u32, nickname: &str, fps: u32) -> Vec<u8> {
        let mut b = vec![0u8; STATE_SIZE];
        b[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        b[4..8].copy_from_slice(&VERSION.to_le_bytes());
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

    fn put_entry(b: &mut [u8], header: usize, index: usize, name: &str, value: &str) {
        let o = header + index * ENTRY_SIZE;
        b[o..o + 4].copy_from_slice(&fnv1a(name).to_le_bytes());
        b[o + 4] = 1;
        b[o + 5] = 1;
        b[o + 6] = 1;
        b[o + 8..o + 8 + name.len()].copy_from_slice(name.as_bytes());
        b[o + 32..o + 32 + value.len()].copy_from_slice(value.as_bytes());
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
        assert!(e.flash && e.highlight && e.enabled);
        assert_eq!(e.id, fnv1a("Velocity"));
        let h = snap.header.expect("a v2 block carries a header");
        assert_eq!(h.version, VERSION);
        assert_eq!(h.nickname, "gtier");
        assert_eq!(h.fps, 144);
        let _ = fs::remove_file(&path);
    }

    /// A v1 block still reads, and reports no header at all — which is what
    /// leaves every v2-only field at the caller's own default.
    #[test]
    fn a_v1_block_still_reads_and_carries_no_header() {
        let path = write("doppel-hud-state-test-v1.bin", &v1_block(MAGIC, V1_VERSION, 1));
        let snap = read(&path).expect("a v1 block must still parse");
        assert_eq!(snap.entries.len(), 1);
        assert_eq!(snap.entries[0].name, "Velocity");
        assert!(snap.header.is_none());
        let _ = fs::remove_file(&path);
    }

    /// The header's meta comes off the v2 block at the documented offsets, and a
    /// v1 block of the same length is read from the v1 header size — the entries
    /// start 48 bytes earlier there, so a mis-sized header would decode garbage.
    #[test]
    fn the_two_versions_read_their_entries_from_their_own_header_size() {
        assert_eq!(HEADER_SIZE, 64);
        assert_eq!(V1_HEADER_SIZE, 16);
        assert_eq!(STATE_SIZE, 4096);
        assert_eq!(USED_SIZE, 3648, "the header and 64 entries, trailer excluded");
        assert_eq!(V1_STATE_SIZE, 3600);

        // The v1 entry sits at 16..72; the v2 entry at 64..120. A v2 header's
        // nickname bytes land inside the v1 entry's name field, so this pins the
        // two apart.
        let v1 = write("doppel-hud-state-test-v1-offset.bin", &v1_block(MAGIC, V1_VERSION, 1));
        let v1_snap = read_checked(&v1).unwrap();
        assert_eq!(v1_snap.entries[0].name, "Velocity");
        let v2 = write("doppel-hud-state-test-v2-offset.bin", &v2_block(1, 0, "Velocity", 60));
        let v2_snap = read_checked(&v2).unwrap();
        assert_eq!(v2_snap.entries[0].name, "Velocity");
        assert_eq!(v2_snap.header.unwrap().nickname, "Velocity");
        let _ = fs::remove_file(&v1);
        let _ = fs::remove_file(&v2);
    }

    /// `flags` bit for bit: the three switches, the two mode bits, and reserved
    /// bits ignored rather than aliased onto a defined one.
    #[test]
    fn the_flags_word_decodes_switches_and_mode_bits() {
        let decode = |flags: u32| {
            let b = v2_block(0, flags, "", 0);
            decode_header(&b, VERSION)
        };

        // Every defined bit on its own.
        assert!(decode(FLAG_WAVE).wave);
        assert!(decode(FLAG_SPACED).spaced);
        assert!(decode(FLAG_WATERMARK).watermark);
        assert!(!decode(0).wave && !decode(0).spaced && !decode(0).watermark);

        // The mode is bits 3..4 and nothing else.
        for (bits, want) in [
            (0b000u32 << 3, StateMode::Default),
            (0b001 << 3, StateMode::Rise),
            (0b010 << 3, StateMode::Vape),
            (0b011 << 3, StateMode::Tesseract),
        ] {
            assert_eq!(decode(bits).mode, want, "mode bits {bits:#07b}");
            assert_eq!(
                StateMode::from_flags(FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | bits),
                want
            );
        }

        // Reserved bits 5..31 change nothing at all: not the switches, not the mode.
        let defined = decode(FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | (2 << MODE_SHIFT));
        let noisy = decode(
            FLAG_WAVE | FLAG_SPACED | FLAG_WATERMARK | (2 << MODE_SHIFT) | 0xFFFF_FFE0,
        );
        assert_eq!(defined.wave, noisy.wave);
        assert_eq!(defined.spaced, noisy.spaced);
        assert_eq!(defined.watermark, noisy.watermark);
        assert_eq!(defined.mode, noisy.mode);
        assert_eq!(noisy.mode, StateMode::Vape);
        assert_eq!(MODE_MASK, 0x18);
        assert_eq!(DEFINED_MASK, 0x1F, "bits 0..4 are the whole defined set");
        assert_eq!(StateMode::from_flags(!DEFINED_MASK), StateMode::Default);
    }

    #[test]
    fn the_header_colours_are_0x00rrggbb() {
        let h = decode_header(&v2_block(0, 0, "", 0), VERSION);
        assert_eq!(h.primary, [120.0 / 255.0, 86.0 / 255.0, 107.0 / 255.0]);
        assert_eq!(h.secondary, [146.0 / 255.0, 150.0 / 255.0, 214.0 / 255.0]);

        // The high byte is not an alpha and must be ignored.
        let mut b = v2_block(0, 0, "", 0);
        b[16..20].copy_from_slice(&0xFF12_3456u32.to_le_bytes());
        assert_eq!(decode_header(&b, VERSION).primary, [0x12 as f32 / 255.0, 0x34 as f32 / 255.0, 0x56 as f32 / 255.0]);
    }

    #[test]
    fn the_nickname_and_fps_come_off_the_header() {
        let h = decode_header(&v2_block(0, 0, "player", 60), VERSION);
        assert_eq!(h.nickname, "player");
        assert_eq!(h.fps, 60);

        // NUL padded: the field ends at the first NUL, whatever follows it inside
        // the 24 bytes.
        let mut b = v2_block(0, 0, "", 30);
        b[32..56].copy_from_slice(b"nick\0xxxxxxxxxxxxxxxxxxx");
        assert_eq!(decode_header(&b, VERSION).nickname, "nick");
        // A field with no NUL at all reads its whole 24 bytes.
        let b = v2_block(0, 0, &"a".repeat(24), 30);
        assert_eq!(decode_header(&b, VERSION).nickname, "a".repeat(24));

        // An all-NUL field is the empty nickname, and fps 0 stays 0 (the painter
        // drops both segments rather than inventing one).
        let h = decode_header(&v2_block(0, 0, "", 0), VERSION);
        assert_eq!(h.nickname, "");
        assert_eq!(h.fps, 0);

        // A truncated multi-byte character is replaced, never a panic.
        let mut b = v2_block(0, 0, "", 0);
        b[32] = 0xE2; // the first byte of a three-byte character, then NULs
        assert_eq!(decode_header(&b, VERSION).nickname, "\u{FFFD}");
    }

    #[test]
    fn rejects_magic_version_and_short_files() {
        let path = std::env::temp_dir().join("doppel-hud-state-test-bad.bin");

        fs::write(&path, v1_block(0xDEAD_BEEF, V1_VERSION, 1)).unwrap();
        assert!(read(&path).is_none(), "wrong magic must not draw");
        assert!(matches!(read_checked(&path), Err(Refusal::Foreign(0xDEAD_BEEF))));

        fs::write(&path, v1_block(MAGIC, 99, 1)).unwrap();
        assert!(read(&path).is_none(), "unknown version must not draw");
        assert!(matches!(read_checked(&path), Err(Refusal::Version(99))));
        assert!(Refusal::Version(99).describe().contains("99"), "the version seen is in the line");

        // A file that is not even a header long, and one that is a header but no
        // entries: both are short, and neither is mistaken for another product's.
        fs::write(&path, vec![0u8; 4]).unwrap();
        assert!(matches!(read_checked(&path), Err(Refusal::Short(4))));
        let mut header_only = v1_block(MAGIC, V1_VERSION, 1);
        header_only.truncate(64);
        fs::write(&path, &header_only).unwrap();
        assert!(matches!(read_checked(&path), Err(Refusal::Short(64))));

        // A version-2 header with a v1-sized body is short too: the entries are
        // what the length has to cover, trailer or no trailer.
        let mut truncated = v2_block(1, 0, "n", 60);
        truncated.truncate(V1_STATE_SIZE);
        fs::write(&path, &truncated).unwrap();
        assert!(matches!(read_checked(&path), Err(Refusal::Short(V1_STATE_SIZE))));

        // A full 4096-byte block is not short: the reader is not waiting for
        // anything past `USED_SIZE`, and the trailer is simply ignored.
        let mut padded = v2_block(1, 0, "n", 60);
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
        let path = write("doppel-hud-state-test-count.bin", &v2_block(1, 0, "", 0));
        let mut bytes = fs::read(&path).unwrap();
        bytes[8..12].copy_from_slice(&999u32.to_le_bytes());
        fs::write(&path, &bytes).unwrap();
        assert_eq!(read(&path).unwrap().entries.len(), MAX_MODULES);
        let _ = fs::remove_file(&path);
    }
}
