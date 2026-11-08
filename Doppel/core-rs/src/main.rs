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

mod codec;
mod condition;
mod geom;
mod model;
mod rng;
mod safety;
mod session;
mod strategy;
mod transport;
mod trig;
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
            "--trig-probe" => {
                // Internal audit hook: replay a table of raw f64 bit patterns
                // through `trig` and print the results as bits. The point is to
                // diff this against a compiled fdlibm reference, so nothing is
                // formatted as decimal — a decimal round-trip can hide a 1 ulp
                // difference, which is the only thing this mode exists to see.
                let input = args.get(i + 1).cloned();
                let output = args.get(i + 2).cloned();
                match (input, output) {
                    (Some(inp), Some(out)) => match trig_probe(&inp, &out) {
                        Ok(n) => {
                            println!("TRIG PROBE OK {} rows", n);
                            return;
                        }
                        Err(e) => {
                            eprintln!("trig probe failed: {}", e);
                            std::process::exit(1);
                        }
                    },
                    _ => {
                        eprintln!("--trig-probe needs <input.tsv> <output.tsv>");
                        std::process::exit(2);
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
    println!("  --trig-probe <in.tsv> <out.tsv>      audit trig against an fdlibm build");
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

/// Writes the input/expected pair that `AimParityTest.kt` replays.
///
/// Two independent RNG streams, one per mode, so a divergence in one mode
/// cannot be masked by the other's draws. The input stream is separate again:
/// if the port ever consumed an extra draw inside a branch, the *input* would
/// stop matching first, which localises the fault to the branch rather than to
/// the whole tick.
fn emit_aim_golden(dir: &str, seed: u64) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-aim-golden v1\n");
    input.push_str(&format!("# seed {}\n", seed));
    input.push_str(
        "idx\tpx\tpy\tpz\tyaw\tpitch\tflags\thurt\ttx\tty\ttz\ttmx\ttmy\ttmz\tthealth\tthurt\ttid\t\
         hminx\thminy\thminz\thmaxx\thmaxy\thmaxz\n",
    );

    let mut expected = String::new();
    expected.push_str("# idx\tmode\taction\twx_bits\twy_bits\twz_bits\tfy_bits\tfp_bits\n");

    let mut in_rng = Pcg32::new(0x00AA_11BB_22CC_33DD);
    let mut st_legit = strategy::aim::AimState::default();
    let mut st_self = strategy::aim::AimState::default();
    let mut rng_legit = Pcg32::new(seed ^ 1);
    let mut rng_self = Pcg32::new(seed ^ 2);

    let cfg_legit = strategy::aim::AimConfig {
        mode: strategy::aim::AimMode::Legit,
        ..Default::default()
    };
    let cfg_self = strategy::aim::AimConfig {
        mode: strategy::aim::AimMode::SelfAdaptive,
        ..Default::default()
    };

    for i in 0..1000u32 {
        let px = in_rng.random_float(-2.0, 2.0) as f64;
        let py = 64.0f64 + in_rng.random_float(-1.0, 1.0) as f64;
        let pz = in_rng.random_float(-2.0, 2.0) as f64;
        let yaw = in_rng.random_float(-180.0, 180.0);
        let pitch = in_rng.random_float(-90.0, 90.0);

        let mut flags: u16 = 0;
        if in_rng.next_int(2) == 1 { flags |= model::F_ON_GROUND; }
        if in_rng.next_int(2) == 1 { flags |= model::F_MOVING; }
        if in_rng.next_int(2) == 1 { flags |= model::F_SPRINTING; }
        if in_rng.next_int(2) == 1 { flags |= model::F_ATTACK_KEY_DOWN; }
        let hurt = in_rng.next_int(10) as i32;

        let angle = in_rng.random_float(-3.14159, 3.14159) as f64;
        let dist = in_rng.random_float(2.0, 8.0) as f64;
        let tx = px + trig::cos(angle) * dist;
        let tz = pz + trig::sin(angle) * dist;
        let ty = in_rng.random_float(0.0, 1.2) as f64 + 63.0;

        let tmx = in_rng.random_float(-0.2, 0.2) as f64;
        let tmy = in_rng.random_float(-0.2, 0.2) as f64;
        let tmz = in_rng.random_float(-0.2, 0.2) as f64;

        let hw = in_rng.random_float(0.2, 0.4) as f64;
        let hh = in_rng.random_float(1.6, 2.0) as f64;
        let ox = in_rng.random_float(-0.05, 0.05) as f64;
        let oz = in_rng.random_float(-0.05, 0.05) as f64;

        let thealth = in_rng.random_float(1.0, 20.0);
        let thurt = in_rng.next_int(10) as i32;
        let tid = 10 + in_rng.next_int(5) as i32;

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            i, px, py, pz, yaw, pitch, flags, hurt,
            tx, ty, tz, tmx, tmy, tmz, thealth, thurt, tid,
            tx - hw + ox, ty, tz - hw + oz, tx + hw + ox, ty + hh, tz + hw + oz
        ));

        let player = model::PlayerState {
            position: Vec3::new(px, py, pz),
            motion: Vec3::new(0.0, 0.0, 0.0),
            yaw,
            pitch,
            health: 20.0,
            hurt_time: hurt,
            hurt_resistant_time: hurt,
            weapon: model::WeaponType::Sword,
            flags,
            ticks: i,
        };
        let target = model::TargetState {
            id: tid,
            position: Vec3::new(tx, ty, tz),
            motion: Vec3::new(tmx, tmy, tmz),
            hitbox: crate::geom::Hitbox::new(
                tx - hw + ox, ty, tz - hw + oz,
                tx + hw + ox, ty + hh, tz + hw + oz,
            ),
            distance: dist as f32,
            health: thealth,
            hurt_time: thurt,
            is_moving_backward: false,
            is_moving_towards_player: false,
        };

        let input_bundle = strategy::aim::AimInput {
            player,
            target: Some(target),
            mouse_delta_x: 0.0,
            mouse_delta_y: 0.0,
        };

        let r_legit = strategy::aim::execute(&cfg_legit, &mut st_legit, &input_bundle, &mut rng_legit);
        let r_self = strategy::aim::execute(&cfg_self, &mut st_self, &input_bundle, &mut rng_self);

        for (mode, r) in [(0u8, r_legit), (1u8, r_self)] {
            match r {
                strategy::aim::AimResult::ApplyRotation { world_point, hitbox: _, fraction_y, fraction_p } => {
                    expected.push_str(&format!(
                        "{}\t{}\t1\t{:016x}\t{:016x}\t{:016x}\t{:08x}\t{:08x}\n",
                        i, mode,
                        world_point.x.to_bits(), world_point.y.to_bits(), world_point.z.to_bits(),
                        fraction_y.to_bits(), fraction_p.to_bits()
                    ));
                }
                strategy::aim::AimResult::Skip => {
                    expected.push_str(&format!("{}\t{}\t0\t0\t0\t0\t0\t0\n", i, mode));
                }
            }
        }
    }

    fs::write(format!("{}/aim_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/aim_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}


/// Writes the input/expected pair that `ClickParityTest.kt` replays.
///
/// Five configurations, each with its own state and RNG stream, because the
/// interesting behaviour is path-dependent: DOUBLE holds a pending click across
/// a tick, and the crit FSM spans five. Sharing one stream would let a
/// divergence in one configuration hide behind the others' draws.
fn emit_click_golden(dir: &str, seed: u64) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-click-golden v1\n");
    input.push_str(&format!("# seed {}\n", seed));
    input.push_str("idx\tflags\tcooldown\tfalling\thas_target\ttx\ttz\ttdist\thurt\n");

    let mut expected = String::new();
    expected.push_str("# idx\tstrategy\taction\n");

    let mut in_rng = Pcg32::new(0x0C1C_0000_0000_BEEF);

    let cfg_prob_normal = strategy::click::ClickConfig::default();
    let cfg_prob_legit = strategy::click::ClickConfig {
        mode: strategy::click::ClickOperatingMode::Legit,
        click_mode: strategy::click::ClickMode::Double,
        min_cps: 6,
        max_cps: 12,
        ..Default::default()
    };
    let cfg_cd_off = strategy::click::CooldownClickConfig::default();
    let cfg_cd_on = strategy::click::CooldownClickConfig {
        crit_mode: strategy::click::CritMode::On,
        cooldown_mode: strategy::click::CooldownClickMode::Legit,
        cooldown_threshold: 0.9,
        ..Default::default()
    };
    let cfg_cd_smart = strategy::click::CooldownClickConfig {
        crit_mode: strategy::click::CritMode::Smart,
        ..Default::default()
    };

    let mut st0 = strategy::click::ClickState::default();
    let mut st1 = strategy::click::ClickState::default();
    let mut st2 = strategy::click::CritState::default();
    let mut st3 = strategy::click::CritState::default();
    let mut st4 = strategy::click::CritState::default();

    let mut rng0 = Pcg32::new(seed ^ 0x10);
    let mut rng1 = Pcg32::new(seed ^ 0x11);
    let mut rng2 = Pcg32::new(seed ^ 0x12);
    let mut rng3 = Pcg32::new(seed ^ 0x13);
    let mut rng4 = Pcg32::new(seed ^ 0x14);

    for i in 0..800u32 {
        let mut flags: u16 = 0;
        if in_rng.next_int(3) != 0 { flags |= model::F_ON_GROUND; }
        if in_rng.next_int(2) == 1 { flags |= model::F_SPRINTING; }
        if in_rng.next_int(8) == 0 { flags |= model::F_MINING; }
        if in_rng.next_int(2) == 1 { flags |= model::F_ATTACK_KEY_DOWN; }
        let hurt = in_rng.next_int(10) as i32;

        // Cooldown swings so the gate opens and closes repeatedly.
        let cooldown = in_rng.random_float(0.0, 1.05);
        let falling = in_rng.next_int(2) == 1;

        // A target is present most ticks; her distance varies around the LEGIT
        // CPS adjustment thresholds (1.5 and 2.5).
        let has_target = in_rng.next_int(6) != 0;
        let tdist = in_rng.random_float(0.8, 4.0);
        let tx = tdist as f64;
        let tz = 0.0f64;

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            i, flags, cooldown, if falling { 1 } else { 0 },
            if has_target { 1 } else { 0 }, tx, tz, tdist, hurt
        ));

        let player = model::PlayerState {
            position: Vec3::new(0.0, 64.0, 0.0),
            motion: Vec3::new(0.0, 0.0, 0.0),
            yaw: 0.0,
            pitch: 0.0,
            health: 20.0,
            hurt_time: hurt,
            hurt_resistant_time: hurt,
            weapon: model::WeaponType::Sword,
            flags,
            ticks: i,
        };
        let target = if has_target {
            Some(model::TargetState {
                id: 5,
                position: Vec3::new(tx, 64.0, tz),
                motion: Vec3::new(0.0, 0.0, 0.0),
                hitbox: crate::geom::Hitbox::around(Vec3::new(tx, 64.0, tz), 0.3, 1.8),
                distance: tdist,
                health: 20.0,
                hurt_time: 0,
                is_moving_backward: false,
                is_moving_towards_player: false,
            })
        } else {
            None
        };

        let click_input = strategy::click::ClickInput {
            player,
            target,
            attack_cooldown: cooldown,
            is_falling: falling,
        };

        let r0 = strategy::click::probabilistic_tick(&cfg_prob_normal, &mut st0, &click_input, &mut rng0);
        let r1 = strategy::click::probabilistic_tick(&cfg_prob_legit, &mut st1, &click_input, &mut rng1);
        let r2 = strategy::click::cooldown_tick(&cfg_cd_off, &mut st2, &click_input, &mut rng2);
        let r3 = strategy::click::cooldown_tick(&cfg_cd_on, &mut st3, &click_input, &mut rng3);
        let r4 = strategy::click::cooldown_tick(&cfg_cd_smart, &mut st4, &click_input, &mut rng4);

        for (sid, r) in [(0u8, r0), (1, r1), (2, r2), (3, r3), (4, r4)] {
            let code: u8 = match r {
                strategy::click::ClickResult::Skip => 0,
                strategy::click::ClickResult::Click => 1,
                strategy::click::ClickResult::StopSprint => 2,
                strategy::click::ClickResult::RestoreSprint { was_sprinting: true } => 3,
                strategy::click::ClickResult::RestoreSprint { was_sprinting: false } => 4,
            };
            expected.push_str(&format!("{}\t{}\t{}\n", i, sid, code));
        }
    }

    fs::write(format!("{}/click_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/click_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}


/// Fixture for the movement family: SOCD, instant-stop and the strafe corrector.
/// All three are deterministic, so this one needs no RNG stream at all — a
/// mismatch here is arithmetic, never a lost draw.
fn emit_movement_golden(dir: &str) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-movement-golden v1\n");
    input.push_str("idx\tfwd\tback\tleft\tright\tyaw\tmx\tmz\tdist\tbearing\treach\n");

    let mut expected = String::new();
    expected.push_str("# idx\tkind\tvalue\ta\tb\n");

    let mut in_rng = Pcg32::new(0x0000_0A0B_0000_0001);
    let mut socd = strategy::movement::SocdResolver::default();
    let mut gate_state = strategy::combat::RangeGateState::default();

    for i in 0..600u32 {
        let fwd = in_rng.next_int(2) == 1;
        let back = in_rng.next_int(2) == 1;
        let left = in_rng.next_int(2) == 1;
        let right = in_rng.next_int(2) == 1;
        let yaw = in_rng.random_float(-180.0, 180.0);
        let mx = in_rng.random_float(-0.5, 0.5) as f64;
        let mz = in_rng.random_float(-0.5, 0.5) as f64;
        let dist = in_rng.random_float(0.5, 6.0);
        let bearing = in_rng.random_float(-3.14159, 3.14159) as f64;
        let reach = in_rng.random_float(2.5, 3.5);

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            i,
            if fwd { 1 } else { 0 },
            if back { 1 } else { 0 },
            if left { 1 } else { 0 },
            if right { 1 } else { 0 },
            yaw, mx, mz, dist, bearing, reach
        ));

        // 0 — SOCD: bitmask of the four suppressions
        let c = socd.resolve(fwd, back, left, right);
        let mask = (c.suppress_forward as u8)
            | ((c.suppress_back as u8) << 1)
            | ((c.suppress_left as u8) << 2)
            | ((c.suppress_right as u8) << 3);
        expected.push_str(&format!("{}\t0\t{}\t0\t0\n", i, mask));

        // 1 — instant stop, forward release
        let sliding = strategy::movement::still_sliding(mx, mz, yaw, true, strategy::movement::MIN_SPEED);
        expected.push_str(&format!("{}\t1\t{}\t0\t0\n", i, if sliding { 1 } else { 0 }));

        // 2 — strafe corrector: mask, before-bits, after-bits
        let si = strategy::movement::StrafeInput { forward: fwd, back, left, right };
        let ss = strategy::movement::StrafeState {
            distance: dist as f64,
            bearing_rad: bearing,
            yaw_deg: yaw as f64,
            reach: reach as f64,
        };
        let sc = strategy::movement::strafe_correct(si, ss);
        let smask = (sc.suppress_forward as u8)
            | ((sc.suppress_back as u8) << 1)
            | ((sc.suppress_left as u8) << 2)
            | ((sc.suppress_right as u8) << 3);
        expected.push_str(&format!(
            "{}\t2\t{}\t{:016x}\t{:016x}\n",
            i, smask, sc.before.to_bits(), sc.after.to_bits()
        ));

        // 3 — range gate, ON_ENTER with hysteresis
        let cfg = strategy::combat::RangeGateConfig {
            mode: strategy::combat::RangeMode::OnEnter,
            min_distance: 0.0,
            max_distance: 4.0,
            hysteresis: 1.5,
        };
        let in_range = strategy::combat::range_gate_compute(Some(&mut gate_state), &cfg, Some(dist));
        expected.push_str(&format!("{}\t3\t{}\t0\t0\n", i, if in_range { 1 } else { 0 }));
    }

    fs::write(format!("{}/movement_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/movement_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}

/// Replays `(x, y, a)` bit-pattern triples through `trig` and writes
/// `(sin x, cos x, atan y, atan2(y, x), acos a)` back as bits.
fn trig_probe(input: &str, output: &str) -> Result<usize, String> {
    use std::io::{BufRead, BufReader};

    let fin = std::fs::File::open(input).map_err(|e| format!("open {}: {}", input, e))?;
    let mut fout = std::fs::File::create(output).map_err(|e| format!("create {}: {}", output, e))?;
    let mut rows = 0usize;

    for line in BufReader::new(fin).lines() {
        let line = line.map_err(|e| e.to_string())?;
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') {
            continue;
        }
        let mut it = t.split_whitespace();
        let xb: u64 = it.next().ok_or("short row")?.parse().map_err(|e| format!("{}", e))?;
        let yb: u64 = it.next().ok_or("short row")?.parse().map_err(|e| format!("{}", e))?;
        let ab: u64 = it.next().ok_or("short row")?.parse().map_err(|e| format!("{}", e))?;

        let x = f64::from_bits(xb);
        let y = f64::from_bits(yb);
        let a = f64::from_bits(ab);

        writeln!(
            fout,
            "{} {} {} {} {}",
            trig::sin(x).to_bits(),
            trig::cos(x).to_bits(),
            trig::atan(y).to_bits(),
            trig::atan2(y, x).to_bits(),
            trig::acos(a).to_bits()
        )
        .map_err(|e| e.to_string())?;
        rows += 1;
    }
    Ok(rows)
}

fn emit_golden(dir: &str) -> Result<(), String> {    use std::fs;

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

    emit_aim_golden(dir, 0x0000_5EED_A1A1_0001)?;
    emit_click_golden(dir, 0x0000_5EED_C11C_0001)?;
    emit_movement_golden(dir)?;
    emit_eagle_golden(dir)?;
    emit_backtrack_golden(dir)?;
    emit_kbd_golden(dir)?;
    emit_reach_golden(dir)?;
    Ok(())
}

/// Eagle's sneak machine, replayed over a fixed input sequence.
///
/// The roll is written into the input file instead of being drawn on both
/// sides: the two RNGs (Pcg32 here, `kotlin.random` there) are not the same
/// generator, and this fixture is about the state machine, not the stream.
/// `rolls` in the expected file is the count the machine asked for — a port
/// that rolls on an idle tick passes every other column and fails this one.
fn emit_eagle_golden(dir: &str) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-eagle-golden v1\n");
    input.push_str("idx\ton_edge\tsettings_met\tsneak_held\troll\n");

    let mut expected = String::new();
    expected.push_str("# idx\tpress\trelease\tauto_active\tdelay\trolls\n");

    let mut state = strategy::eagle::State::default();
    let mut rng = Pcg32::new(0x0000_5EED_E461_0001);

    for i in 0..300 {
        // Skewed so every branch is hit: mostly on the edge, occasionally with
        // the player holding their own key, occasionally with the pitch gate off.
        let pick = rng.random_int(0, 99);
        let on_edge = pick % 3 != 0;
        let settings_met = pick % 11 != 0;
        let sneak_held = pick % 5 == 0;
        let roll = rng.random_int(1, 5);

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\n",
            i, on_edge as u8, settings_met as u8, sneak_held as u8, roll
        ));

        let mut rolls = 0;
        let (d, next) = strategy::eagle::tick(
            state,
            strategy::eagle::Input {
                on_edge,
                settings_met,
                sneak_key_held: sneak_held,
            },
            &mut || {
                rolls += 1;
                roll
            },
        );
        state = next;
        expected.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\n",
            i,
            d.press as u8,
            d.release as u8,
            next.auto_sneak_active as u8,
            next.unsneak_delay_ticks,
            rolls
        ));
    }

    fs::write(format!("{}/eagle_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/eagle_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}

/// Backtrack's hold window, replayed over a synthetic clock.
///
/// The clock advances 50 ms per tick from a non-zero origin so that a sentinel
/// of 0 (`hold_start_ns == 0`, which the first Kotlin version used) would show
/// up as *held since the epoch* rather than as a silent no-op. The roll is
/// scripted per row for the same reason as Eagle's: the two sides do not share
/// a generator, and this fixture is about the window, not the draw.
fn emit_backtrack_golden(dir: &str) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-backtrack-golden v1\n");
    input.push_str(
        "idx\tnow_ns\tmodern\tpulse\tsmart\tmin_ms\tmax_ms\tdistance\tlegacy_distance\tmust_release\troll_ms\n",
    );

    let mut expected = String::new();
    expected.push_str("# idx\thold\n");

    let mut state = strategy::backtrack::State::new();
    let mut rng = Pcg32::new(0x0000_5EED_BACC_0001);
    let mut now: i64 = 1_000_000_000;

    for i in 0..400 {
        now += 50_000_000;

        let pick = rng.random_int(0, 99);
        let modern = pick % 7 != 0;
        let pulse = pick % 3 == 0;
        let smart = pick % 5 != 0;
        let min_ms = 80;
        let max_ms = 250;
        let has_target = pick % 4 != 0;
        let distance = if has_target {
            (pick % 70) as f32 / 10.0
        } else {
            -1.0
        };
        let legacy_distance = 3.5f32;
        let must_release = pick % 13 == 0;
        let roll_ms = rng.random_int(min_ms, max_ms);

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            i,
            now,
            modern as u8,
            pulse as u8,
            smart as u8,
            min_ms,
            max_ms,
            distance,
            legacy_distance,
            must_release as u8,
            roll_ms
        ));

        let hold = strategy::backtrack::tick(
            &mut state,
            strategy::backtrack::Input {
                now_ns: now,
                modern,
                pulse,
                smart,
                min_delay_ms: min_ms,
                max_delay_ms: max_ms,
                distance,
                legacy_distance,
                must_release,
            },
            &mut |_, _| roll_ms,
        );
        expected.push_str(&format!("{}\t{}\n", i, hold as u8));
    }

    fs::write(format!("{}/backtrack_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/backtrack_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}

/// KnockbackDisplace's flick, replayed over a synthetic attack stream.
///
/// Deliberately excludes `Direction::Random`: the Kotlin oracle would have to
/// share a generator with Pcg32 for that column to mean anything, and the
/// random branch is covered by the Rust unit test. What is left is the part a
/// port actually gets wrong — the cooldown boundary, the strafe fallback, and
/// whether a vetoed attack still spends the cooldown.
fn emit_kbd_golden(dir: &str) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-kbd-golden v1\n");
    input.push_str(
        "idx\tnow_ns\tdirection\tuse_last_strafe\tcooldown_ms\tangle\tnot_taking_kb\tvelocity_tick\tkey_left\tkey_right\n",
    );

    let mut expected = String::new();
    expected.push_str("# idx\toffset_bits\n");

    let mut state = strategy::knockback_displace::State::new();
    let mut rng = Pcg32::new(0x0000_5EED_B0D0_0001);
    let mut now: i64 = 1_000_000_000;

    for i in 0..300 {
        // 20 ms per attack: inside the 100 ms cooldown often enough to hit the
        // boundary from both sides.
        now += 20_000_000;

        let pick = rng.random_int(0, 99);
        let direction = match pick % 4 {
            0 => strategy::knockback_displace::Direction::Left,
            1 => strategy::knockback_displace::Direction::Right,
            2 => strategy::knockback_displace::Direction::Strafe,
            _ => strategy::knockback_displace::Direction::StrafeInverted,
        };
        let use_last_strafe = pick % 3 != 0;
        let cooldown_ms = if pick % 5 == 0 { 0 } else { 100 };
        let angle = if pick % 2 == 0 { 90.0f32 } else { 45.0f32 };
        let not_taking_knockback = pick % 7 == 0;
        let velocity_tick = pick % 9 == 0;
        let key_left = pick % 6 == 0;
        let key_right = pick % 6 == 1;

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            i,
            now,
            direction as u8,
            use_last_strafe as u8,
            cooldown_ms,
            angle,
            not_taking_knockback as u8,
            velocity_tick as u8,
            key_left as u8,
            key_right as u8
        ));

        let offset = strategy::knockback_displace::attack(
            &mut state,
            strategy::knockback_displace::Input {
                now_ns: now,
                direction,
                use_last_strafe,
                cooldown_ms,
                angle,
                not_taking_knockback,
                velocity_this_tick: velocity_tick,
                key_left,
                key_right,
            },
            &mut rng,
        );
        // Raw bits: the offset is a product of a sign and a float, and a port
        // that multiplies in a different order lands one ulp away.
        expected.push_str(&format!("{}\t{:08x}\n", i, offset.to_bits()));
    }

    fs::write(format!("{}/kbd_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/kbd_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}

/// Reach's sampling gates, with the randomness held still.
///
/// `chance` is always 100 and `reach_min == reach_max`, which is exactly the
/// path where the function draws nothing from the RNG. That makes the fixture a
/// test of the gate order — throttle, cooldown, blocks — rather than of the two
/// generators agreeing, which they cannot be made to do.
fn emit_reach_golden(dir: &str) -> Result<(), String> {
    use std::fs;

    let mut input = String::new();
    input.push_str("# doppel-reach-golden v1\n");
    input.push_str(
        "idx\tnow_ns\tconditions_ok\tgui_open\tchance\thit_per\tdelay_ms\thit_through_blocks\tlooking_at_block\treach_min\treach_max\n",
    );

    let mut expected = String::new();
    expected.push_str("# idx\tdistance_bits\n");

    let mut state = strategy::reach_sample::State::new();
    let mut rng = Pcg32::new(0x0000_5EED_EAC4_0001);
    let mut now: i64 = 1_000_000_000;

    for i in 0..300 {
        now += 120_000_000; // 120 ms between presses

        let pick = rng.random_int(0, 99);
        let conditions_ok = pick % 8 != 0;
        let gui_open = pick % 17 == 0;
        let hit_per = match pick % 5 {
            0 => 1,
            1 => 2,
            2 => 3,
            _ => 1,
        };
        let delay_ms = if pick % 3 == 0 { 250 } else { 0 };
        let hit_through_blocks = pick % 4 != 0;
        let looking_at_block = pick % 6 == 0;
        let reach_min = 3.0f32;

        input.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            i,
            now,
            conditions_ok as u8,
            gui_open as u8,
            100,
            hit_per,
            delay_ms,
            hit_through_blocks as u8,
            looking_at_block as u8,
            reach_min,
            reach_min
        ));

        let d = strategy::reach_sample::sample(
            &mut state,
            strategy::reach_sample::Input {
                now_ns: now,
                conditions_ok,
                gui_open,
                chance: 100,
                hit_per,
                delay_ms,
                hit_through_blocks,
                looking_at_block,
                reach_min,
                reach_max: reach_min,
            },
            &mut rng,
        );
        expected.push_str(&format!("{}\t{:016x}\n", i, d.to_bits()));
    }

    fs::write(format!("{}/reach_input.tsv", dir), input).map_err(|e| e.to_string())?;
    fs::write(format!("{}/reach_expected.tsv", dir), expected).map_err(|e| e.to_string())?;
    Ok(())
}
