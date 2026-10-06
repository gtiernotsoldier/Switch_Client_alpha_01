// state.rs — the reader half of the shared-state contract.
//
// The in-JVM agent publishes one fixed 3600-byte block to
// `%TEMP%\doppel-hud.state` every render frame (see
// adapter/common/hud/HudStateWriter.kt, §5.3 of the plan). This process only
// ever reads it: the file is opened read-only and nothing is ever written back,
// so there is no back channel through which the overlay could influence the game
// (§5.2).
//
// Contract, `#pragma pack(push,1)`, little-endian, 3600 bytes:
//
//   uint32 magic      0x444F5050 ("DOPP")
//   uint32 version    protocol version; a reader with a different version refuses to draw
//   uint32 count      valid entries, <= 64
//   uint32 pad
//   ModuleEntry[64]   56 bytes each:
//     uint32 id       stable hash of the module name (FNV-1a 32)
//     uint8  enabled
//     uint8  flash    the module worked recently; this side decays it (D4)
//     uint8  highlight draw the value in the accent colour
//     uint8  pad
//     char   name[24] UTF-8, NUL padded
//     char   value[24] UTF-8, NUL padded, empty = no value shown
//
// A short file, a wrong magic or a wrong version all mean "not ours": the caller
// renders nothing rather than half-decoding whatever happens to be at that path.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub const MAGIC: u32 = 0x444F_5050; // "DOPP"
pub const VERSION: u32 = 1;
pub const MAX_MODULES: usize = 64;
pub const ENTRY_SIZE: usize = 56;
pub const HEADER_SIZE: usize = 16;
pub const STATE_SIZE: usize = HEADER_SIZE + MAX_MODULES * ENTRY_SIZE; // 3600

const NAME_OFFSET: usize = 8;
const NAME_BYTES: usize = 24;
const VALUE_OFFSET: usize = 32;
const VALUE_BYTES: usize = 24;

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
}

/// `%TEMP%\doppel-hud.state`, the same path `HudStateWriter.defaultFile()` uses.
pub fn default_path() -> PathBuf {
    std::env::temp_dir().join("doppel-hud.state")
}

/// Read and validate the block. `None` covers every "not drawable" case: no file,
/// short file, wrong magic, wrong version.
pub fn read(path: &Path) -> Option<Snapshot> {
    let bytes = fs::read(path).ok()?; // read-only: no share mode, no write handle
    if bytes.len() < STATE_SIZE {
        return None;
    }

    if le_u32(&bytes, 0) != MAGIC || le_u32(&bytes, 4) != VERSION {
        return None;
    }

    let count = (le_u32(&bytes, 8) as usize).min(MAX_MODULES);
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        let base = HEADER_SIZE + i * ENTRY_SIZE;
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

    Some(Snapshot { entries, modified })
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

    fn block(magic: u32, version: u32, count: u32, name: &str) -> Vec<u8> {
        let mut b = vec![0u8; STATE_SIZE];
        b[0..4].copy_from_slice(&magic.to_le_bytes());
        b[4..8].copy_from_slice(&version.to_le_bytes());
        b[8..12].copy_from_slice(&count.to_le_bytes());
        b[16..20].copy_from_slice(&fnv1a(name).to_le_bytes());
        b[20] = 1;
        b[21] = 1;
        b[22] = 1;
        b[24..24 + name.len()].copy_from_slice(name.as_bytes());
        b[48..51].copy_from_slice(b"82%");
        b
    }

    #[test]
    fn parses_a_valid_block() {
        let path = std::env::temp_dir().join("doppel-hud-state-test-valid.bin");
        fs::write(&path, block(MAGIC, VERSION, 1, "Velocity")).unwrap();
        let snap = read(&path).expect("valid block must parse");
        assert_eq!(snap.entries.len(), 1);
        let e = &snap.entries[0];
        assert_eq!(e.name, "Velocity");
        assert_eq!(e.value, "82%");
        assert!(e.flash && e.highlight && e.enabled);
        assert_eq!(e.id, fnv1a("Velocity"));
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn rejects_magic_version_and_short_files() {
        let path = std::env::temp_dir().join("doppel-hud-state-test-bad.bin");
        fs::write(&path, block(0xDEAD_BEEF, VERSION, 1, "X")).unwrap();
        assert!(read(&path).is_none(), "wrong magic must not draw");
        fs::write(&path, block(MAGIC, 99, 1, "X")).unwrap();
        assert!(read(&path).is_none(), "wrong version must not draw");
        fs::write(&path, vec![0u8; 64]).unwrap();
        assert!(read(&path).is_none(), "short file must not draw");
        let _ = fs::remove_file(&path);
    }
}
