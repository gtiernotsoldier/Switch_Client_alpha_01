//! `doppel-core` — the standalone core process.
//!
//! This is the P6 v2 shape from `PROTECTION.md` 9.5: the strategies and their
//! session state live here, in their own process, and the game only talks to
//! them through a 256-byte framed pipe. Nothing in this binary knows what
//! Minecraft is.
//!
//! Modes:
//!   --listen <endpoint>   serve a stub (tcp:127.0.0.1:0 | unix:/path)
//!   --bench               measure real round-trip latency over loopback
//!   --emit-golden <dir>   write the parity fixture the JVM test replays
//!   --selftest            run internal consistency checks, exit non-zero on failure
//!
//! The process is deliberately boring: one accept loop, one thread per stub,
//! no allocator tricks. A core that is clever is a core that is hard to reason
//! about when the replay diverges.

// The wire vocabulary is defined whole (every module id, every reason code,
// every action) because it is a contract, not because this slice uses all of
// it. Silencing dead_code here keeps the module complete for the next port
// instead of inviting someone to delete the unused half and re-add it later.
#![allow(dead_code)]

mod condition;
mod model;
mod rng;
mod session;
mod strategy;
mod transport;
mod wire;

use std::io::Write;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use model::*;
use rng::Pcg32;
use session::Session;
use strategy::velocity;
use transport::{Endpoint, Listener};
use wire::{Frame, Kind, Module, Status, Writer, FRAME};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut listen: Option<String> = None;
    let mut token: Option<String> = None;
    let mut seed: Option<u64> = None;
    let mut port_file: Option<String> = None;
    let mut mode = "serve";

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--listen" => {
                i += 1;
                listen = args.get(i).cloned();
            }
            "--token" => {
                i += 1;
                token = args.get(i).cloned();
            }
            "--seed" => {
                i += 1;
                seed = args.get(i).and_then(|s| s.parse::<u64>().ok());
            }
            "--port-file" => {
                i += 1;
                port_file = args.get(i).cloned();
            }
            "--bench" => mode = "bench",
            "--selftest" => mode = "selftest",
            "--emit-golden" => {
                mode = "emit-golden";
                i += 1;
                if let Some(d) = args.get(i) {
                    match emit_golden(d) {
                        Ok(()) => {
                            println!("GOLDEN OK {}", d);
                            return;
                        }
                        Err(e) => {
                            eprintln!("golden failed: {}", e);
                            std::process::exit(1);
                        }
                    }
                }
            }
            "--help" | "-h" => {
                print_help();
                return;
            }
            other => {
                eprintln!("unknown argument: {}", other);
                print_help();
                std::process::exit(2);
            }
        }
        i += 1;
    }

    match mode {
        "bench" => bench(),
        "selftest" => selftest(),
        _ => serve(listen, token, seed, port_file),
    }
}

fn print_help() {
    println!("doppel-core {}", env!("CARGO_PKG_VERSION"));
    println!("  --listen <tcp:host:port|unix:path>   serve a stub");
    println!("  --token <s>                          shared secret for the handshake");
    println!("  --seed <u64>                         deterministic RNG seed");
    println!("  --port-file <path>                   write the bound address here");
    println!("  --bench                              loopback round-trip benchmark");
    println!("  --emit-golden <dir>                  write the JVM parity fixture");
    println!("  --selftest                           internal consistency checks");
}

fn now_nanos() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64
}

fn serve(listen: Option<String>, token: Option<String>, seed: Option<u64>, port_file: Option<String>) {
    let spec = match listen {
        Some(s) => s,
        None => {
            eprintln!("--listen is required");
            std::process::exit(2);
        }
    };
    let ep = match Endpoint::parse(&spec) {
        Ok(e) => e,
        Err(e) => {
            eprintln!("{}", e);
            std::process::exit(2);
        }
    };
    let listener: Listener = match transport::bind(&ep) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("bind failed: {}", e);
            std::process::exit(1);
        }
    };

    // The stub reads this line to learn the ephemeral port when it passed :0.
    println!("LISTEN {}", listener.local());
    let _ = std::io::stdout().flush();

    if let Some(pf) = port_file {
        if let Err(e) = std::fs::write(&pf, listener.local()) {
            eprintln!("warn: could not write port file: {}", e);
        }
    }

    let base_seed = seed.unwrap_or_else(now_nanos);
    if token.is_none() {
        // Loud on purpose: an unauthenticated loopback port is reachable by any
        // process that can guess it, and on a shared box that is every process.
        eprintln!("warn: --token not set; any local process may connect");
    }
    let token = Arc::new(token);
    let next_id = Arc::new(AtomicU64::new(1));

    for incoming in accept_loop(listener) {
        let token = Arc::clone(&token);
        let next_id = Arc::clone(&next_id);
        std::thread::spawn(move || {
            let mut conn = incoming;
            let mut sess: Option<Session> = None;

            loop {
                let frame = match transport::recv(&mut conn) {
                    Ok(Some(f)) => f,
                    Ok(None) => break,
                    Err(e) => {
                        eprintln!("read: {}", e);
                        break;
                    }
                };

                let reply = match frame.header.kind {
                    Kind::Hello => match check_hello(&frame, token.as_deref()) {
                        Ok(()) => {
                            let id = next_id.fetch_add(1, Ordering::Relaxed);
                            // Seed is derived, not the raw clock, so two stubs that
                            // connect in the same millisecond still diverge.
                            let s = base_seed ^ (id.wrapping_mul(0x9E37_79B9_7F4A_7C15));
                            let session = Session::new(id, s);
                            let seed = session.seed;
                            sess = Some(session);
                            Some(hello_ack(&frame, id, seed))
                        }
                        Err(status) => Some(
                            Frame::new(Kind::Error, frame.header.seq, frame.header.tick, Module::Velocity)
                                .with_status(status),
                        ),
                    },
                    Kind::Bye => break,
                    _ => match sess.as_mut() {
                        Some(s) => Some(s.handle(&frame)),
                        None => Some(
                            Frame::new(Kind::Error, frame.header.seq, frame.header.tick, Module::Velocity)
                                .with_status(Status::NoSession),
                        ),
                    },
                };

                if let Some(r) = reply {
                    if let Err(e) = transport::send(&mut conn, &r) {
                        eprintln!("write: {}", e);
                        break;
                    }
                }
            }
        });
    }
}

/// The accept loop as an iterator, so a bind error on one connection does not
/// tear down the listener — a stub that reconnect-loops must be able to succeed
/// on its second try.
fn accept_loop(listener: Listener) -> impl Iterator<Item = Box<dyn transport::Conn>> {
    std::iter::from_fn(move || loop {
        match listener.accept() {
            Ok(c) => return Some(c),
            Err(e) => {
                eprintln!("accept: {}", e);
                std::thread::sleep(Duration::from_millis(50));
            }
        }
    })
}

fn check_hello(frame: &Frame, expected: Option<&str>) -> Result<(), Status> {
    let expected = match expected {
        Some(t) => t,
        None => return Ok(()),
    };
    let len = frame.header.payload_len as usize;
    let end = frame.payload[..len].iter().position(|b| *b == 0).unwrap_or(len);
    let got = std::str::from_utf8(&frame.payload[..end]).unwrap_or("");
    if got == expected {
        Ok(())
    } else {
        Err(Status::BadToken)
    }
}

fn hello_ack(hello: &Frame, session_id: u64, seed: u64) -> Frame {
    let mut f = Frame::new(Kind::HelloAck, hello.header.seq, hello.header.tick, Module::Velocity);
    let mut buf = [0u8; 24];
    {
        let mut w = Writer::new(&mut buf);
        w.u64(session_id);
        w.u64(seed);
        w.u32(1); // protocol build
        w.u32(1 << 0 | 1 << 1 | 1 << 2); // features: velocity, delay, click
    }
    f.payload[..24].copy_from_slice(&buf);
    f.set_len(24);
    f
}

// ── bench ─────────────────────────────────────────────────────────────────────

/// Measures a full stub→core→stub round trip through the real socket path and
/// the real session code — not a memcpy, so the number includes framing, the
/// syscall pair, and one velocity decision.
fn bench() {
    const N: usize = 20_000;
    const WARM: usize = 2_000;

    let ep = Endpoint::parse("tcp:127.0.0.1:0").unwrap();
    let listener = transport::bind(&ep).unwrap();
    let addr = listener.local().to_string();

    // No "server is ready" handshake between the threads: the listener is
    // already bound, so the client's connect() succeeds into the backlog and
    // accept() returns as soon as the server thread gets there. Waiting for
    // accept() before connecting deadlocks — which is exactly what the first
    // version of this function did.
    let server = std::thread::spawn(move || {
        let mut conn = listener.accept().unwrap();
        let mut sess = Session::new(1, 42);
        while let Ok(Some(f)) = transport::recv(&mut conn) {
            if f.header.kind == Kind::Bye {
                break;
            }
            let reply = sess.handle(&f);
            if transport::send(&mut conn, &reply).is_err() {
                break;
            }
        }
    });

    let mut client = transport::connect(&Endpoint::Tcp(addr)).unwrap();
    let mut samples = Vec::with_capacity(N);

    for i in 0..(WARM + N) {
        let mut f = Frame::new(Kind::Snapshot, i as u32, i as u32, Module::Velocity);
        {
            let p = f.payload_mut();
            let mut w = Writer::new(p);
            w.u8(0);
            w.u8(1);
            w.u16(model::F_ON_GROUND | model::F_MOVING);
            w.f64(0.4);
            w.f64(0.4);
            w.f64(-0.3);
            w.f64(0.0);
            w.f64(64.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f32(0.0);
            w.f32(0.0);
            w.f32(20.0);
            w.i32(0);
            w.i32(0);
            w.u8(1);
            w.u8(0);
            w.u16(0);
            w.i32(0);
            w.f64(0.0);
            w.f64(0.0);
            w.f64(0.0);
            w.f32(0.0);
            w.f32(0.0);
            w.i32(0);
            w.u8(0);
            w.u8(0);
            w.u16(0);
            w.u32(i as u32);
            w.u32(1);
            w.u32(0);
            w.u32(0);
            let n = w.pos();
            f.set_len(n);
        }
        let bytes = f.encode();

        let t0 = Instant::now();
        client.write_frame(&bytes).unwrap();
        let mut buf = [0u8; FRAME];
        loop {
            match client.read_frame(&mut buf) {
                Ok(transport::ReadOutcome::Frame) => break,
                Ok(transport::ReadOutcome::Closed) => panic!("server closed"),
                Err(e) => panic!("{}", e),
            }
        }
        let dt = t0.elapsed().as_nanos() as u64;
        if i >= WARM {
            samples.push(dt);
        }
    }

    let mut bye = Frame::new(Kind::Bye, 0, 0, Module::Velocity);
    bye.set_len(0);
    let _ = transport::send(&mut client, &bye);
    let _ = server.join();

    samples.sort_unstable();
    let pct = |p: f64| samples[((samples.len() as f64 * p) as usize).min(samples.len() - 1)] as f64 / 1000.0;

    println!("round trip, real session, {} samples (256 B frame)", N);
    println!("  p50  {:>8.2} us", pct(0.50));
    println!("  p99  {:>8.2} us", pct(0.99));
    println!("  p999 {:>8.2} us", pct(0.999));
    println!("  max  {:>8.2} us", samples[samples.len() - 1] as f64 / 1000.0);
    println!(
        "  deadline budget used at p99: {:.4}% of 1 ms",
        pct(0.99) / 1000.0 * 100.0
    );
}

// ── selftest ──────────────────────────────────────────────────────────────────

fn selftest() {
    let mut failures = 0;

    // 1. Determinism: same seed, same decision.
    let cfg = VelocityConfig {
        reduce_given_reduced: 70,
        reduce_given_skipped: 30,
        ..Default::default()
    };
    let ctx = selftest_ctx();
    let mut sa = velocity::State::new();
    let mut ra = Pcg32::new(1234);
    let mut first: Vec<(u8, f32, f32, f32)> = Vec::new();
    for _ in 0..500 {
        let d = velocity::legit(&cfg, &mut sa, &ctx, &mut ra);
        first.push((
            d.action as u8,
            d.motion.x as f32,
            d.motion.y as f32,
            d.motion.z as f32,
        ));
    }

    let mut sb = velocity::State::new();
    let mut rb = Pcg32::new(1234);
    let mut second: Vec<(u8, f32, f32, f32)> = Vec::new();
    for _ in 0..500 {
        let d = velocity::legit(&cfg, &mut sb, &ctx, &mut rb);
        second.push((
            d.action as u8,
            d.motion.x as f32,
            d.motion.y as f32,
            d.motion.z as f32,
        ));
    }
    if first != second {
        eprintln!("FAIL determinism: same seed produced different decisions");
        failures += 1;
    } else {
        println!("ok   determinism (500 ticks, seed 1234)");
    }

    // 2. Reduction stays inside the configured band (0.4..0.6 on both axes).
    //    Back out the factor from the decision: h = x / motion.x, v = y / motion.y.
    let mut checked = 0;
    let mut band_ok = true;
    for (action, x, y, _) in &first {
        if *action == Action::Modify as u8 {
            let h = *x as f64 / 0.4;
            let v = *y as f64 / 0.36;
            checked += 1;
            if !(0.4 - 1e-5..=0.6 + 1e-5).contains(&h) || !(0.4 - 1e-5..=0.6 + 1e-5).contains(&v) {
                band_ok = false;
                eprintln!("  out of band: h={} v={}", h, v);
            }
        }
    }
    if !band_ok || checked == 0 {
        eprintln!("FAIL reduction out of band ({} modified ticks)", checked);
        failures += 1;
    } else {
        println!("ok   reduction band respected ({} modified ticks)", checked);
    }

    // 3. Wire round trip through the encoder.
    let mut f = Frame::new(Kind::Decision, 9, 77, Module::Velocity);
    f.set_len(8);
    let bytes = f.encode();
    match Frame::decode(&bytes) {
        Ok(d) if d.header.seq == 9 && d.header.tick == 77 => println!("ok   wire round trip"),
        _ => {
            eprintln!("FAIL wire round trip");
            failures += 1;
        }
    }

    // 4. Cross-language RNG vectors.
    let mut r = Pcg32::new(0x0123_4567_89AB_CDEF);
    let v = [r.next_u32(), r.next_u32()];
    if v == [3_983_312_133, 118_466_452] {
        println!("ok   rng golden vectors");
    } else {
        eprintln!("FAIL rng golden vectors: {:?}", v);
        failures += 1;
    }

    if failures == 0 {
        println!("SELFTEST OK");
    } else {
        eprintln!("SELFTEST FAILED ({} checks)", failures);
        std::process::exit(1);
    }
}

fn selftest_ctx() -> VelocityContext {
    VelocityContext {
        original_motion: Vec3::new(0.4, 0.36, -0.3),
        player: PlayerState {
            position: Vec3::new(0.0, 64.0, 0.0),
            motion: Vec3::new(0.1, 0.0, 0.1),
            yaw: 0.0,
            pitch: 0.0,
            health: 20.0,
            hurt_time: 0,
            hurt_resistant_time: 0,
            weapon: WeaponType::Sword,
            flags: model::F_ON_GROUND | model::F_MOVING | model::F_MOVING_FORWARD,
            ticks: 0,
        },
        target: None,
        is_knockback_hit: true,
        packet_id: 1,
    }
}

// ── golden fixture ────────────────────────────────────────────────────────────

/// Writes the input/expected pair that `VelocityParityTest.kt` replays.
///
/// The fixture is generated from this implementation and checked by the other.
/// That sounds circular — the Kotlin side is the *reference*, though: if the two
/// disagree, the test fails, and the disagreement is real. Regenerating the
/// fixture after a deliberate behaviour change is a two-line diff in git, which
/// is the point: a behaviour change cannot pass silently.
fn emit_golden(dir: &str) -> Result<(), String> {
    use std::fs;

    fs::create_dir_all(dir).map_err(|e| e.to_string())?;

    let cfg = VelocityConfig {
        reduce_given_reduced: 70,
        reduce_given_skipped: 30,
        only_on_hit_frame: false,
        ..Default::default()
    };
    let seed: u64 = 0x0123_4567_89AB_CDEF;

    let mut input = String::new();
    input.push_str("# doppel-velocity-golden v1\n");
    input.push_str(&format!(
        "# seed {}\n# hmin {} hmax {} vmin {} vmax {}\n# p_reduced {} p_skipped {}\n",
        seed, cfg.horizontal_min, cfg.horizontal_max, cfg.vertical_min, cfg.vertical_max,
        cfg.reduce_given_reduced, cfg.reduce_given_skipped
    ));
    input.push_str("idx\tmx\tmy\tmz\n");

    let mut expected = String::new();
    expected.push_str("# idx\taction\treason\tmx_bits\tmy_bits\tmz_bits\n");

    let mut state = velocity::State::new();
    let mut rng = Pcg32::new(seed);
    let mut input_rng = Pcg32::new(0xDEAD_BEEF);

    for i in 0..2000 {
        let mx = input_rng.random_float(-1.0, 1.0) as f64;
        let my = input_rng.random_float(0.0, 0.5) as f64;
        let mz = input_rng.random_float(-1.0, 1.0) as f64;

        input.push_str(&format!("{}\t{}\t{}\t{}\n", i, mx, my, mz));

        let ctx = VelocityContext {
            original_motion: Vec3::new(mx, my, mz),
            player: selftest_ctx().player,
            target: None,
            is_knockback_hit: true,
            packet_id: i as u32,
        };
        let d = strategy::velocity::legit(&cfg, &mut state, &ctx, &mut rng);
        // Raw bits, not decimal: a parity check that compares printed floats is a
        // check that can pass while the last mantissa bit differs, which is
        // exactly the drift a replay is supposed to catch.
        expected.push_str(&format!(
            "{}\t{}\t{}\t{:016x}\t{:016x}\t{:016x}\n",
            i,
            d.action as u8,
            d.reason,
            d.motion.x.to_bits(),
            d.motion.y.to_bits(),
            d.motion.z.to_bits()
        ));
    }

    fs::write(format!("{}/velocity_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/velocity_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}
