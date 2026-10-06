# Doppel HUD state contract v3 (frozen)

Written before the round-2 work so the Kotlin writer and the Rust reader can be implemented in
parallel against one document. **This is a protocol change**: both sides ship in the same injector
binary, and the reader keeps accepting v1 and v2 so a stale file from an older build cannot break
the overlay.

Little-endian, `#pragma pack(push,1)`, 4096 bytes total. `USED_SIZE` = 3648; 3648..4095 is the
reserved trailer.

```
offset size  field
 0      4    u32  magic      0x444F5050 ("DOPP")
 4      4    u32  version    3
 8      4    u32  count      entries written, <= 64
12      4    u32  flags
                    bit0     wave            (Default mode's accent animation)
                    bit1     spaced          (split CamelCase names)
                    bit2     watermark       (draw the watermark run)
                    bits 3..5 mode          0 default, 1 rise, 2 vape, 3 tesseract, 4 modern
                    bit6     orderPublished  draw rows in published order, not width-sorted
                    bits 7..31 reserved (ignored)
16      4    u32  primary    0x00RRGGBB
20      4    u32  secondary  0x00RRGGBB
24      4    u32  fps        0 = unknown, the FPS segment is omitted
28      4    u32  scale_x1000  0 = unset (use the overlay's own default, 1000)
32     24    char nickname[24] UTF-8, NUL padded
56      4    u32  opacity    0 = unset (100%); else background opacity percent 1..100
60      4    u32  line_spacing  0 = unset (100%); else the row gap as a percent 50..200
64   3584    entries[64], 56 bytes each
```

Entry (layout unchanged from v1/v2):

```
u32  id         FNV-1a 32 of the module name
u8   enabled    1 = currently enabled; the writer only publishes enabled rows
u8   flash      WORK-PULSE STRENGTH 0..255 (new in v3; see below)
u8   highlight  1 = draw the value in the accent colour
u8   pad        0
char name[24]   UTF-8, NUL padded
char value[24]  UTF-8, NUL padded, empty = no value shown
```

## What changed from v2, and why

| Field | v2 | v3 | Why |
| --- | --- | --- | --- |
| `version` | 2 | 3 | The two semantic changes below are a protocol change |
| mode bits | bits 3..4 (2 bits, 4 values) | bits 3..5 (3 bits, 5 values) | adds the Modern mode |
| new `orderPublished` | — | flags bit 6 | the user's manual HUD order must survive to the overlay, which otherwise sorts rows by width (the reference's wedge) |
| `scale_x1000` | reserved (28) | 0 = unset, else scale x1000 (500..4000) | the user can set the HUD scale from the WebUI instead of a CLI flag |
| `opacity` | reserved (56) | 0 = unset, else 1..100 percent | the user can set the background opacity |
| `line_spacing` | reserved (60) | 0 = unset, else 50..200 percent | the user can set the row gap (行距) without changing the scale, which would also change the font |
| entry `flash` | bool: "module is enabled, draw red" | 0..255 work-pulse strength | the user wants a red flash **while the module works**, decaying; `Module.markWorked()`/`flashStrength()` already exist on the Kotlin side and were never wired in |

An explicit CLI flag still wins over the header, exactly as with mode/wave/watermark today.

## Reading older blocks

* **v1** (3600 bytes, `magic version count pad` + entries at 16): no header at all; `Snapshot.header`
  is `None` and every header field keeps the caller's default. Unchanged.
* **v2** (4096, 64-byte header, entries at 64): decode the mode from bits **3..4** (2 bits), leave
  `scale`/`opacity` unset, `orderPublished = false`, and map an entry's `flash != 0` to `255`.
  A v2 file can only be a leftover from an older build; the entry rows themselves are identical.

## Writing

The Kotlin writer publishes `version = 3`. The reserved trailer stays zero.

## Animation semantics (overlay side, not part of the wire format)

The overlay animates rows itself; the contract only says which rows exist:

* a row that appears → entrance slide + fade (already implemented, `Anim::progress`);
* a row that disappears → **exit animation**, ~220 ms, fading and sliding back toward the edge it
  came from, so enabling/disabling a module in the WebUI is a transition rather than a jump;
* `flash` > 0 → the row is tinted toward red by `flash/255`, decaying to nothing. The overlay uses
  the value as-is each frame; the writer recomputes it every publish, so no decay state is needed
  on the wire.
