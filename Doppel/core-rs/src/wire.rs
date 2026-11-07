//! Wire format v1 — the only contract between the game-side stub and the core process.
//!
//! Every message is a fixed 256-byte frame: a 32-byte header plus up to 224 bytes of
//! payload. Fixed size is deliberate — it makes the read side a single `read_exact`
//! into a stack buffer, keeps the kernel copy one page at most, and lets the shared
//! memory path (if we ever need it) reuse the exact same layout with no protocol change.
//!
//! All integers are little-endian. Floats are IEEE-754 binary32, sent as raw bits so
//! `f32::to_bits` on the Rust side and `Float.floatToRawIntBits` on the JVM side agree
//! bit for bit — this is what makes a golden replay diff meaningful.
//!
//! See `Doppel/docs/IPC-PROTOCOL.md` for the byte table.

use std::convert::TryInto;

/// Frame size.
///
/// 256 was enough while velocity was the only module: its snapshot is 160
/// bytes and the rest was headroom. Aim needs the target's per-tick velocity
/// and world-space box — the predictive lead and the multipoint both read them,
/// and substituting a default player box makes the core's answer diverge from
/// the in-process one for any entity that is not a standing player. That is 72
/// more bytes, and the generic modules need an op plus up to 14 arguments after
/// the snapshot. 384 keeps everything in one shape instead of three.
///
/// Still one page, still a single `read_exact` into a stack buffer.
pub const FRAME: usize = 384;
/// Header size; payload starts here.
pub const HEADER: usize = 32;
/// Maximum payload length.
pub const MAX_PAYLOAD: usize = FRAME - HEADER;

/// "DPL1" — rejects a wrong-endian or garbage stream on the first read.
pub const MAGIC: u32 = 0x4450_4C31;
pub const VERSION: u16 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum Kind {
    Hello = 1,
    HelloAck = 2,
    Snapshot = 3,
    Decision = 4,
    Config = 5,
    Ping = 6,
    Pong = 7,
    Error = 8,
    /// Client tells the core it is shutting down; core exits cleanly.
    Bye = 9,
    /// Client forgets one module's cross-tick state.
    ///
    /// The module was just disabled (or re-enabled on a different player), so
    /// whatever the core remembers from the last session — which key was held,
    /// how long the tap had left, which direction the velocity queue was on —
    /// is about to be wrong. Zero payload; the target module is in the header.
    Reset = 10,
}

impl Kind {
    pub fn from_u16(v: u16) -> Option<Kind> {
        Some(match v {
            1 => Kind::Hello,
            2 => Kind::HelloAck,
            3 => Kind::Snapshot,
            4 => Kind::Decision,
            5 => Kind::Config,
            6 => Kind::Ping,
            7 => Kind::Pong,
            8 => Kind::Error,
            9 => Kind::Bye,
            10 => Kind::Reset,
            _ => return None,
        })
    }
}

/// Status / error code carried in the header. Non-zero on a Decision means the
/// core could not produce a real answer and the stub must degrade this tick.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum Status {
    Ok = 0,
    BadVersion = 1,
    BadToken = 2,
    UnknownKind = 3,
    BadPayload = 4,
    /// The strategy panicked; caught by `catch_unwind`, the module is degraded
    /// for this tick and the process survives. (PROTECTION.md 9.5.3)
    Degraded = 5,
    /// No session: hello was never completed, or the core restarted.
    NoSession = 6,
}

impl Status {
    pub fn from_u16(v: u16) -> Status {
        match v {
            1 => Status::BadVersion,
            2 => Status::BadToken,
            3 => Status::UnknownKind,
            4 => Status::BadPayload,
            5 => Status::Degraded,
            6 => Status::NoSession,
            _ => Status::Ok,
        }
    }
}

/// Module id in the header. One core process serves every module; the id picks
/// the strategy for this frame.
///
/// The ids are append-only. A number, once shipped, never changes meaning: the
/// stub and the core are separate binaries that can be upgraded independently,
/// and a renumbering would turn a stale stub into a process that silently
/// computes the wrong strategy rather than one that fails loudly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Module {
    Velocity = 1,
    Click = 2,
    Aim = 3,
    Movement = 4,
    Tap = 5,
    Combat = 6,
    Reach = 7,
    /// Player-family modules (no combat objectives): currently Eagle only.
    Player = 8,
}

impl Module {
    pub fn from_u8(v: u8) -> Option<Module> {
        Some(match v {
            1 => Module::Velocity,
            2 => Module::Click,
            3 => Module::Aim,
            4 => Module::Movement,
            5 => Module::Tap,
            6 => Module::Combat,
            7 => Module::Reach,
            8 => Module::Player,
            _ => return None,
        })
    }
}

/// What the core decided. Mirrors `VelocityResult` / `PlatformCommand` one to one.
///
/// The first four are velocity's; the rest exist because a decision frame is the
/// only channel back to the stub and every module needs to name its outcome.
/// Two generic arms — [`Action::Scalar`] and [`Action::Bool`] — cover the pure
/// predicates (reach intersection, still-sliding, wrap180) so that adding one
/// does not force a protocol revision.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Action {
    NoOp = 0,
    Pass = 1,
    Modify = 2,
    ClickBurst = 3,
    Cancel = 4,
    /// Aim: apply the rotation in the payload.
    ApplyRotation = 5,
    /// Click: attack this tick.
    Click = 6,
    StopSprint = 7,
    RestoreSprint = 8,
    /// Movement: one or more keys damped.
    Suppress = 9,
    /// Tap: emit a strafe side.
    TapSide = 10,
    /// Combat: block verdict.
    Block = 11,
    /// Generic f64 result, carried in `motion.x`.
    Scalar = 12,
    /// Generic boolean result, carried in `clicks`.
    Bool = 13,
}

impl Action {
    pub fn from_u8(v: u8) -> Action {
        match v {
            1 => Action::Pass,
            2 => Action::Modify,
            3 => Action::ClickBurst,
            4 => Action::Cancel,
            5 => Action::ApplyRotation,
            6 => Action::Click,
            7 => Action::StopSprint,
            8 => Action::RestoreSprint,
            9 => Action::Suppress,
            10 => Action::TapSide,
            11 => Action::Block,
            12 => Action::Scalar,
            13 => Action::Bool,
            _ => Action::NoOp,
        }
    }
}

/// Why the decision came out the way it did. Purely diagnostic — the stub never
/// branches on it — but it is what makes a failing golden replay readable
/// ("tick 4127 diverged at reason=gate_skip" beats "bytes differ").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Reason {
    None = 0,
    ConditionFailed = 1,
    HitFrameFilter = 2,
    GateSkip = 3,
    Modified = 4,
    NoTarget = 5,
    HurtTimeMismatch = 6,
    Facing = 7,
    OutOfRange = 8,
    Delayed = 9,
    Released = 10,
    PassThrough = 11,
    Panic = 12,
    /// SPRINT: the hit opened a brace window. The packet still passes through
    /// untouched — this only tells the adapter that the key press is coming, so
    /// it can flash the HUD and start the wall-clock deadline.
    SprintArmed = 13,
}

pub fn reason_name(r: u8) -> &'static str {
    match r {
        0 => "none",
        1 => "condition_failed",
        2 => "hit_frame_filter",
        3 => "gate_skip",
        4 => "modified",
        5 => "no_target",
        6 => "hurt_time_mismatch",
        7 => "facing",
        8 => "out_of_range",
        9 => "delayed",
        10 => "released",
        11 => "pass_through",
        12 => "panic",
        13 => "sprint_armed",
        _ => "unknown",
    }
}

/// Header fields, decoded.
#[derive(Debug, Clone, Copy)]
pub struct Header {
    pub kind: Kind,
    pub seq: u32,
    pub tick: u32,
    pub status: Status,
    pub module: u8,
    pub payload_len: u16,
    pub correlation: u64,
}

/// A decoded frame: header plus a bounded view over the payload bytes.
#[derive(Debug)]
pub struct Frame {
    pub header: Header,
    pub payload: [u8; MAX_PAYLOAD],
}

impl Frame {
    pub fn new(kind: Kind, seq: u32, tick: u32, module: Module) -> Frame {
        Frame {
            header: Header {
                kind,
                seq,
                tick,
                status: Status::Ok,
                module: module as u8,
                payload_len: 0,
                correlation: 0,
            },
            payload: [0u8; MAX_PAYLOAD],
        }
    }

    pub fn with_status(mut self, status: Status) -> Frame {
        self.header.status = status;
        self
    }

    pub fn with_correlation(mut self, c: u64) -> Frame {
        self.header.correlation = c;
        self
    }

    pub fn payload_mut(&mut self) -> &mut [u8; MAX_PAYLOAD] {
        &mut self.payload
    }

    /// Mark the payload as `len` bytes long. Panics in debug if it would overflow
    /// the frame — a payload that does not fit is a programming error, not an
    /// input error, and we would rather fail at the call site than truncate.
    pub fn set_len(&mut self, len: usize) {
        debug_assert!(len <= MAX_PAYLOAD);
        self.header.payload_len = len as u16;
    }

    pub fn encode(&self) -> [u8; FRAME] {
        let mut out = [0u8; FRAME];
        out[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        out[4..6].copy_from_slice(&VERSION.to_le_bytes());
        out[6..8].copy_from_slice(&(self.header.kind as u16).to_le_bytes());
        out[8..12].copy_from_slice(&self.header.seq.to_le_bytes());
        out[12..16].copy_from_slice(&self.header.tick.to_le_bytes());
        out[16..18].copy_from_slice(&self.header.payload_len.to_le_bytes());
        out[18..20].copy_from_slice(&(self.header.status as u16).to_le_bytes());
        out[20] = self.header.module;
        out[21] = 0; // flags, reserved
        out[22..24].copy_from_slice(&0u16.to_le_bytes());
        out[24..32].copy_from_slice(&self.header.correlation.to_le_bytes());
        out[HEADER..HEADER + self.header.payload_len as usize]
            .copy_from_slice(&self.payload[..self.header.payload_len as usize]);
        out
    }

    /// Decode a full frame. Returns `Err(Status)` so the caller can answer with a
    /// proper error frame instead of dropping the connection.
    pub fn decode(buf: &[u8; FRAME]) -> Result<Frame, Status> {
        let magic = u32::from_le_bytes(buf[0..4].try_into().unwrap());
        if magic != MAGIC {
            return Err(Status::BadPayload);
        }
        let version = u16::from_le_bytes(buf[4..6].try_into().unwrap());
        if version != VERSION {
            return Err(Status::BadVersion);
        }
        let kind = Kind::from_u16(u16::from_le_bytes(buf[6..8].try_into().unwrap()))
            .ok_or(Status::UnknownKind)?;
        let payload_len = u16::from_le_bytes(buf[16..18].try_into().unwrap());
        if payload_len as usize > MAX_PAYLOAD {
            return Err(Status::BadPayload);
        }
        let mut payload = [0u8; MAX_PAYLOAD];
        payload[..payload_len as usize]
            .copy_from_slice(&buf[HEADER..HEADER + payload_len as usize]);
        Ok(Frame {
            header: Header {
                kind,
                seq: u32::from_le_bytes(buf[8..12].try_into().unwrap()),
                tick: u32::from_le_bytes(buf[12..16].try_into().unwrap()),
                status: Status::from_u16(u16::from_le_bytes(buf[18..20].try_into().unwrap())),
                module: buf[20],
                payload_len,
                correlation: u64::from_le_bytes(buf[24..32].try_into().unwrap()),
            },
            payload,
        })
    }
}

/// Little-endian cursor over a payload. Every read is bounds-checked and returns
/// `Err(Status::BadPayload)` instead of panicking — a malformed frame from a
/// half-dead peer must degrade the module, never kill the core.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Reader<'a> {
        Reader { buf, pos: 0 }
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], Status> {
        if self.pos + n > self.buf.len() {
            return Err(Status::BadPayload);
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    pub fn u8(&mut self) -> Result<u8, Status> {
        Ok(self.take(1)?[0])
    }
    pub fn u16(&mut self) -> Result<u16, Status> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    pub fn u32(&mut self) -> Result<u32, Status> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    pub fn i32(&mut self) -> Result<i32, Status> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    pub fn f32(&mut self) -> Result<f32, Status> {
        Ok(f32::from_bits(u32::from_le_bytes(
            self.take(4)?.try_into().unwrap(),
        )))
    }
    /// f64 for anything that feeds a decision. Minecraft's position and motion
    /// are doubles, and a value that goes through f32 on the way to the core comes
    /// back a few ulp off — which is enough to break a bit-exact replay on tick 0.
    pub fn f64(&mut self) -> Result<f64, Status> {
        Ok(f64::from_bits(u64::from_le_bytes(
            self.take(8)?.try_into().unwrap(),
        )))
    }
    pub fn u64(&mut self) -> Result<u64, Status> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    pub fn skip(&mut self, n: usize) -> Result<(), Status> {
        self.take(n).map(|_| ())
    }
}

/// Little-endian writer. Writes past the end are a caller bug, so it panics —
/// every call site is a fixed layout with a known total size.
pub struct Writer<'a> {
    buf: &'a mut [u8],
    pos: usize,
}

impl<'a> Writer<'a> {
    pub fn new(buf: &'a mut [u8]) -> Writer<'a> {
        Writer { buf, pos: 0 }
    }

    pub fn pos(&self) -> usize {
        self.pos
    }

    fn put(&mut self, bytes: &[u8]) {
        self.buf[self.pos..self.pos + bytes.len()].copy_from_slice(bytes);
        self.pos += bytes.len();
    }

    pub fn u8(&mut self, v: u8) {
        self.put(&[v]);
    }
    pub fn u16(&mut self, v: u16) {
        self.put(&v.to_le_bytes());
    }
    pub fn u32(&mut self, v: u32) {
        self.put(&v.to_le_bytes());
    }
    pub fn i32(&mut self, v: i32) {
        self.put(&v.to_le_bytes());
    }
    pub fn f32(&mut self, v: f32) {
        self.put(&v.to_bits().to_le_bytes());
    }
    pub fn f64(&mut self, v: f64) {
        self.put(&v.to_bits().to_le_bytes());
    }
    pub fn u64(&mut self, v: u64) {
        self.put(&v.to_le_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrips() {
        let mut f = Frame::new(Kind::Snapshot, 7, 4242, Module::Velocity);
        f.set_len(16);
        f.payload[0] = 0xAB;
        let bytes = f.encode();
        let back = Frame::decode(&bytes).unwrap();
        assert_eq!(back.header.kind, Kind::Snapshot);
        assert_eq!(back.header.seq, 7);
        assert_eq!(back.header.tick, 4242);
        assert_eq!(back.header.module, Module::Velocity as u8);
        assert_eq!(back.payload[0], 0xAB);
    }

    #[test]
    fn rejects_wrong_magic() {
        let mut bytes = [0u8; FRAME];
        bytes[0] = 0xFF;
        assert_eq!(Frame::decode(&bytes).unwrap_err(), Status::BadPayload);
    }

    #[test]
    fn rejects_future_version() {
        let mut f = Frame::new(Kind::Ping, 1, 1, Module::Velocity);
        f.set_len(0);
        let mut bytes = f.encode();
        bytes[4..6].copy_from_slice(&2u16.to_le_bytes());
        assert_eq!(Frame::decode(&bytes).unwrap_err(), Status::BadVersion);
    }

    #[test]
    fn rejects_overlong_payload() {
        let f = Frame::new(Kind::Ping, 1, 1, Module::Velocity);
        let mut bytes = f.encode();
        // One past the payload capacity. The exact number is derived, not
        // written down: a hardcoded value silently became valid when the frame
        // grew for the aim snapshot.
        let over = (MAX_PAYLOAD + 1) as u16;
        bytes[16..18].copy_from_slice(&over.to_le_bytes());
        assert_eq!(Frame::decode(&bytes).unwrap_err(), Status::BadPayload);
    }

    #[test]
    fn reader_errors_instead_of_panicking() {
        let buf = [0u8; 4];
        let mut r = Reader::new(&buf);
        assert!(r.u32().is_ok());
        assert_eq!(r.u32().unwrap_err(), Status::BadPayload);
    }

    #[test]
    fn f32_bits_are_exact() {
        let mut buf = [0u8; 4];
        Writer::new(&mut buf).f32(0.1f32);
        assert_eq!(buf, 0.1f32.to_bits().to_le_bytes());
    }
}
