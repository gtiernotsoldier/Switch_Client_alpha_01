//! Transport: the byte pipe under the protocol.
//!
//! Two implementations, same `Conn` surface:
//!
//!  - **TCP loopback** — the production default. `PROTECTION.md` 9.5.1 chose a
//!    named pipe; the reason it does not work *here* is the JVM side: the client
//!    runs Java 8, which has no `UnixDomainSocketAddress` and no way to open a
//!    Win32 pipe without JNA. Adding JNA to the agent jar is a bigger change
//!    (and a bigger supply-chain surface) than it is worth, and loopback TCP is
//!    the same order of magnitude — 9.5 µs p50 vs 7.9 µs for a pipe, against a
//!    1 ms deadline. The protocol is transport-agnostic, so swapping it later
//!    changes this file only.
//!  - **Unix domain socket** — Linux/macOS only. Used by tooling and by the
//!    golden-replay harness; also what the latency bench measures.
//!
//! Both are loopback-only. A core process listening on a routable interface
//! would be a remote decision oracle for anyone who found the port; the token
//! handshake is the backstop, not the primary defence.

use std::io::{ErrorKind, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;

use crate::wire::{Frame, FRAME};

/// Result of one frame read.
pub enum ReadOutcome {
    Frame,
    /// Peer closed cleanly (EOF before any byte of a frame).
    Closed,
}

pub trait Conn: Send {
    fn read_frame(&mut self, buf: &mut [u8; FRAME]) -> std::io::Result<ReadOutcome>;
    fn write_frame(&mut self, buf: &[u8; FRAME]) -> std::io::Result<()>;
}

pub struct TcpConn {
    stream: TcpStream,
}

impl TcpConn {
    pub fn new(stream: TcpStream) -> TcpConn {
        // A 256-byte frame is smaller than the MSS; Nagle would add up to 40 ms
        // to a round trip that is supposed to cost microseconds.
        let _ = stream.set_nodelay(true);
        TcpConn { stream }
    }
}

impl Conn for TcpConn {
    fn read_frame(&mut self, buf: &mut [u8; FRAME]) -> std::io::Result<ReadOutcome> {
        read_exact_or_eof(&mut self.stream, buf)
    }
    fn write_frame(&mut self, buf: &[u8; FRAME]) -> std::io::Result<()> {
        self.stream.write_all(buf)
    }
}

#[cfg(unix)]
pub struct UnixConn {
    stream: std::os::unix::net::UnixStream,
}

#[cfg(unix)]
impl UnixConn {
    pub fn new(stream: std::os::unix::net::UnixStream) -> UnixConn {
        UnixConn { stream }
    }
}

#[cfg(unix)]
impl Conn for UnixConn {
    fn read_frame(&mut self, buf: &mut [u8; FRAME]) -> std::io::Result<ReadOutcome> {
        read_exact_or_eof(&mut self.stream, buf)
    }
    fn write_frame(&mut self, buf: &[u8; FRAME]) -> std::io::Result<()> {
        self.stream.write_all(buf)
    }
}

/// Read a full frame, distinguishing "clean close" from "truncated mid-frame".
/// A truncated frame is an error, not a close: the peer died holding half a
/// message and the session state is suspect.
fn read_exact_or_eof<R: Read>(r: &mut R, buf: &mut [u8; FRAME]) -> std::io::Result<ReadOutcome> {
    let mut filled = 0;
    while filled < FRAME {
        match r.read(&mut buf[filled..]) {
            Ok(0) => {
                if filled == 0 {
                    return Ok(ReadOutcome::Closed);
                }
                return Err(std::io::Error::new(
                    ErrorKind::UnexpectedEof,
                    "peer closed mid-frame",
                ));
            }
            Ok(n) => filled += n,
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(ReadOutcome::Frame)
}

#[derive(Debug, Clone)]
pub enum Endpoint {
    Tcp(String),
    #[cfg(unix)]
    Unix(PathBuf),
}

impl Endpoint {
    /// Parses `tcp:127.0.0.1:0` or `unix:/tmp/doppel.sock`.
    pub fn parse(s: &str) -> Result<Endpoint, String> {
        if let Some(rest) = s.strip_prefix("tcp:") {
            return Ok(Endpoint::Tcp(rest.to_string()));
        }
        if let Some(rest) = s.strip_prefix("unix:") {
            #[cfg(unix)]
            return Ok(Endpoint::Unix(PathBuf::from(rest)));
            #[cfg(not(unix))]
            return Err("unix sockets are not supported on this platform".to_string());
        }
        Err(format!("bad endpoint '{}': expected tcp:host:port or unix:path", s))
    }
}

/// Binds and returns the concrete bound address, so `tcp:...:0` can report the
/// port the kernel picked.
pub fn bind(ep: &Endpoint) -> std::io::Result<Listener> {
    match ep {
        Endpoint::Tcp(addr) => {
            let l = TcpListener::bind(addr)?;
            let local = l.local_addr()?;
            Ok(Listener::Tcp(l, local.to_string()))
        }
        #[cfg(unix)]
        Endpoint::Unix(path) => {
            // Stale socket from a crashed run: unlink before bind, never after.
            let _ = std::fs::remove_file(path);
            let l = std::os::unix::net::UnixListener::bind(path)?;
            Ok(Listener::Unix(l, path.display().to_string()))
        }
    }
}

pub enum Listener {
    Tcp(TcpListener, String),
    #[cfg(unix)]
    Unix(std::os::unix::net::UnixListener, String),
}

impl Listener {
    pub fn local(&self) -> &str {
        match self {
            Listener::Tcp(_, s) => s,
            #[cfg(unix)]
            Listener::Unix(_, s) => s,
        }
    }

    pub fn accept(&self) -> std::io::Result<Box<dyn Conn>> {
        match self {
            Listener::Tcp(l, _) => {
                let (s, _) = l.accept()?;
                Ok(Box::new(TcpConn::new(s)))
            }
            #[cfg(unix)]
            Listener::Unix(l, _) => {
                let (s, _) = l.accept()?;
                Ok(Box::new(UnixConn::new(s)))
            }
        }
    }
}

/// Client-side connect, used by `--replay` against a live core and by tests.
pub fn connect(ep: &Endpoint) -> std::io::Result<Box<dyn Conn>> {
    match ep {
        Endpoint::Tcp(addr) => Ok(Box::new(TcpConn::new(TcpStream::connect(addr)?))),
        #[cfg(unix)]
        Endpoint::Unix(path) => Ok(Box::new(UnixConn::new(
            std::os::unix::net::UnixStream::connect(path)?,
        ))),
    }
}

pub fn send(c: &mut Box<dyn Conn>, f: &Frame) -> std::io::Result<()> {
    c.write_frame(&f.encode())
}

pub fn recv(c: &mut Box<dyn Conn>) -> std::io::Result<Option<Frame>> {
    let mut buf = [0u8; FRAME];
    match c.read_frame(&mut buf)? {
        ReadOutcome::Closed => Ok(None),
        ReadOutcome::Frame => Ok(Frame::decode(&buf).ok()),
    }
}
