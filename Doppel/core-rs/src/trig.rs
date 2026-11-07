//! fdlibm `sin` and `cos`, transcribed.
//!
//! ## Why this file exists
//!
//! The reference implementation is Kotlin, and "cross-language parity" was
//! originally read as "the same decisions". That reading is wrong for anything
//! that compares a `Double`. `StrictMath.sin` and glibc's `sin` are two
//! different approximations of the same function and they disagree in the last
//! bit for **51.6% of inputs** in `[-6, 6]` — measured, 200k samples, not
//! estimated. A score that differs by one ulp is not a rounding curiosity when
//! it decides `maxByOrNull` on a near-tie or straddles `MIN_GAIN`.
//!
//! HotSpot's `Math.sin` is an intrinsic that differs from `StrictMath.sin` for
//! 6.7% of the same inputs, and the intrinsic is CPU-specific. So the JVM could
//! not serve as a stable reference either. Both sides therefore compute sine and
//! cosine with **this** code: Kotlin calls `StrictMath`, which is fdlibm, and
//! this module is a transcription of the same fdlibm routines.
//!
//! ## Provenance
//!
//! Transcribed from FreeBSD `lib/msun` via musl (`__sin.c`, `__cos.c`,
//! `__rem_pio2.c`, `__rem_pio2_large.c`), which are the fdlibm originals:
//!
//! > Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//! > Developed at SunSoft, a Sun Microsystems, Inc. business.
//! > Permission to use, copy, modify, and distribute this
//! > software is freely granted, provided that this notice
//! > is preserved.
//!
//! The arithmetic is transcribed operation for operation. Every reordering of a
//! sum, every fusion of a multiply-add, changes which bit comes out — the whole
//! point of the file is that there is no freedom here. Do not "clean up" the
//! expressions.

// ── constants ──────────────────────────────────────────────────────────────

// kernel sin, |x| <= pi/4
const S1: f64 = -1.66666666666666324348e-01;
const S2: f64 = 8.33333333332248946124e-03;
const S3: f64 = -1.98412698298579493134e-04;
const S4: f64 = 2.75573137070700676789e-06;
const S5: f64 = -2.50507602534068634195e-08;
const S6: f64 = 1.58969099521155010221e-10;

// kernel cos, |x| <= pi/4
const C1: f64 = 4.16666666666666019037e-02;
const C2: f64 = -1.38888888888741095749e-03;
const C3: f64 = 2.48015872894767294178e-05;
const C4: f64 = -2.75573143513906633035e-07;
const C5: f64 = 2.08757232129817482790e-09;
const C6: f64 = -1.13596475577881948265e-11;

// argument reduction
const TOINT: f64 = 1.5 / f64::EPSILON; // 1.5 * 2^52
const PIO4: f64 = 0.7853981633974483; // 0x1.921fb54442d18p-1
const INVPIO2: f64 = 6.36619772367581382433e-01;
const PIO2_1: f64 = 1.57079632673412561417e+00;
const PIO2_1T: f64 = 6.07710050650619224932e-11;
const PIO2_2: f64 = 6.07710050630396597660e-11;
const PIO2_2T: f64 = 2.02226624879595063154e-21;
const PIO2_3: f64 = 2.02226624871116645580e-21;
const PIO2_3T: f64 = 8.47842766036889956997e-32;

const INIT_JK: [usize; 4] = [3, 4, 4, 6];

/// `scalbn(x, n)` — exact, because multiplying by a power of two cannot round.
fn scalbn(x: f64, n: i32) -> f64 {
    fn pow2(n: i32) -> f64 {
        f64::from_bits(((1023 + n) as u64) << 52)
    }
    if x == 0.0 || !x.is_finite() {
        return x;
    }
    let mut y = x;
    let mut n = n;
    while n > 1000 {
        y *= pow2(1000);
        n -= 1000;
    }
    while n < -1000 {
        y *= pow2(-1000);
        n += 1000;
    }
    y * pow2(n)
}

// ── kernels on [-pi/4, pi/4] ───────────────────────────────────────────────

/// `iy == 0` means the tail `y` is known to be zero.
fn kernel_sin(x: f64, y: f64, iy: bool) -> f64 {
    let z = x * x;
    let w = z * z;
    let r = S2 + z * (S3 + z * S4) + z * w * (S5 + z * S6);
    let v = z * x;
    if !iy {
        x + v * (S1 + z * r)
    } else {
        x - ((z * (0.5 * y - v * r) - y) - v * S1)
    }
}

fn kernel_cos(x: f64, y: f64) -> f64 {
    let z = x * x;
    let w = z * z;
    let r = z * (C1 + z * (C2 + z * C3)) + w * w * (C4 + z * (C5 + z * C6));
    let hz = 0.5 * z;
    let w = 1.0 - hz;
    // `1.0 - w` is exact here; it recovers the low bits of `hz` that the
    // subtraction to `w` dropped.
    w + (((1.0 - w) - hz) + (z * r - x * y))
}

// ── argument reduction ─────────────────────────────────────────────────────

/// Returns X such that `x - (X mod 4) * pi/2` is in `[-pi/4, pi/4]`, writing
/// the reduction residual (head, tail) into `y`.
pub fn rem_pio2(x: f64, y: &mut [f64; 2]) -> i32 {
    let bits = x.to_bits();
    let sign = (bits >> 63) != 0;
    let ix = ((bits >> 32) as u32) & 0x7fff_ffff;

    let small = |n: i32, y: &mut [f64; 2]| -> i32 {
        // Reduction by at most 4 half-turns, each step good to 85 bits.
        let (p1, p1t) = (PIO2_1, PIO2_1T);
        let k = n as f64;
        let z = if !sign { x - k * p1 } else { x + k * p1 };
        y[0] = if !sign { z - k * p1t } else { z + k * p1t };
        y[1] = if !sign { (z - y[0]) - k * p1t } else { (z - y[0]) + k * p1t };
        if !sign { n } else { -n }
    };

    if ix <= 0x400f_6a7a {
        // |x| ~<= 5pi/4
        if (ix & 0xfffff) == 0x921fb {
            // |x| ~= pi/2 or pi: the head cancels, so the fast path would lose
            // everything. Fall through to the medium path.
        } else if ix <= 0x4002_d97c {
            return small(1, y); // |x| ~<= 3pi/4
        } else {
            return small(2, y);
        }
    } else if ix <= 0x401c_463b {
        // |x| ~<= 9pi/4
        if ix <= 0x4015_fdbc {
            if ix != 0x4012_d97c {
                // |x| ~<= 7pi/4, and not 3pi/2
                return small(3, y);
            }
        } else if ix != 0x4019_21fb {
            // |x| ~<= 9pi/4, and not 2pi
            return small(4, y);
        }
    }

    if ix < 0x4139_21fb {
        // |x| ~< 2^20 * (pi/2): medium size, Cody-Waite with up to 3 rounds.
        let fn_ = x * INVPIO2 + TOINT - TOINT; // rint(x / (pi/2))
        let mut n = fn_ as i32;
        let mut fnv = fn_;
        let mut r = x - fnv * PIO2_1;
        let mut w = fnv * PIO2_1T; // 1st round, good to 85 bits

        // The next two branches only fire under directed rounding, which we do
        // not use — but they are part of the algorithm, not an optimisation.
        if r - w < -PIO4 {
            n -= 1;
            fnv -= 1.0;
            r = x - fnv * PIO2_1;
            w = fnv * PIO2_1T;
        } else if r - w > PIO4 {
            n += 1;
            fnv += 1.0;
            r = x - fnv * PIO2_1;
            w = fnv * PIO2_1T;
        }

        y[0] = r - w;
        let ey = ((y[0].to_bits() >> 52) & 0x7ff) as i32;
        let ex = (ix >> 20) as i32;
        if ex - ey > 16 {
            // 2nd round, good to 118 bits
            let t = r;
            w = fnv * PIO2_2;
            r = t - w;
            w = fnv * PIO2_2T - ((t - r) - w);
            y[0] = r - w;
            let ey = ((y[0].to_bits() >> 52) & 0x7ff) as i32;
            if ex - ey > 49 {
                // 3rd round, good to 151 bits — covers every double
                let t = r;
                w = fnv * PIO2_3;
                r = t - w;
                w = fnv * PIO2_3T - ((t - r) - w);
                y[0] = r - w;
            }
        }
        y[1] = (r - y[0]) - w;
        return n;
    }

    if ix >= 0x7ff0_0000 {
        // inf or NaN
        y[0] = x - x;
        y[1] = x - x;
        return 0;
    }

    // Large arguments: Payne-Hanek. Split |x| into 24-bit chunks and hand them
    // to the bignum reduction.
    let mut z = f64::from_bits((bits & ((1u64 << 52) - 1)) | ((0x3ff + 23) as u64) << 52);
    let mut tx = [0.0f64; 3];
    let mut i = 0usize;
    while i < 2 {
        tx[i] = (z as i32) as f64;
        z = (z - tx[i]) * 16_777_216.0; // 2^24
        i += 1;
    }
    tx[i] = z;
    while tx[i] == 0.0 {
        i -= 1;
    }
    let mut ty = [0.0f64; 3];
    let n = rem_pio2_large(&tx, &mut ty, (ix >> 20) as i32 - (0x3ff + 23), i + 1, 1);
    if sign {
        y[0] = -ty[0];
        y[1] = -ty[1];
        -n
    } else {
        y[0] = ty[0];
        y[1] = ty[1];
        n
    }
}

/// `2/pi` to 396 hex digits, 24 bits per entry.
const IPIO2: [i32; 690] = [
    0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62,
    0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A,
    0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
    0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41,
    0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8,
    0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
    0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5,
    0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
    0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
    0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880,
    0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
    0x47C419, 0xC367CD, 0xDCE809, 0x2A8359, 0xC4768B, 0x961CA6,
    0xDDAF44, 0xD15719, 0x053EA5, 0xFF0705, 0x3F7E33, 0xE832C2,
    0xDE4F98, 0x327DBB, 0xC33D26, 0xEF6B1E, 0x5EF89F, 0x3A1F35,
    0xCAF27F, 0x1D87F1, 0x21907C, 0x7C246A, 0xFA6ED5, 0x772D30,
    0x433B15, 0xC614B5, 0x9D19C3, 0xC2C4AD, 0x414D2C, 0x5D000C,
    0x467D86, 0x2D71E3, 0x9AC69B, 0x006233, 0x7CD2B4, 0x97A7B4,
    0xD55537, 0xF63ED7, 0x1810A3, 0xFC764D, 0x2A9D64, 0xABD770,
    0xF87C63, 0x57B07A, 0xE71517, 0x5649C0, 0xD9D63B, 0x3884A7,
    0xCB2324, 0x778AD6, 0x23545A, 0xB91F00, 0x1B0AF1, 0xDFCE19,
    0xFF319F, 0x6A1E66, 0x615799, 0x47FBAC, 0xD87F7E, 0xB76522,
    0x89E832, 0x60BFE6, 0xCDC4EF, 0x09366C, 0xD43F5D, 0xD7DE16,
    0xDE3B58, 0x929BDE, 0x2822D2, 0xE88628, 0x4D58E2, 0x32CAC6,
    0x16E308, 0xCB7DE0, 0x50C017, 0xA71DF3, 0x5BE018, 0x34132E,
    0x621283, 0x014883, 0x5B8EF5, 0x7FB0AD, 0xF2E91E, 0x434A48,
    0xD36710, 0xD8DDAA, 0x425FAE, 0xCE616A, 0xA4280A, 0xB499D3,
    0xF2A606, 0x7F775C, 0x83C2A3, 0x883C61, 0x78738A, 0x5A8CAF,
    0xBDD76F, 0x63A62D, 0xCBBFF4, 0xEF818D, 0x67C126, 0x45CA55,
    0x36D9CA, 0xD2A828, 0x8D61C2, 0x77C912, 0x142604, 0x9B4612,
    0xC459C4, 0x44C5C8, 0x91B24D, 0xF31700, 0xAD43D4, 0xE54929,
    0x10D5FD, 0xFCBE00, 0xCC941E, 0xEECE70, 0xF53E13, 0x80F1EC,
    0xC3E7B3, 0x28F8C7, 0x940593, 0x3E71C1, 0xB3092E, 0xF3450B,
    0x9C1288, 0x7B20AB, 0x9FB52E, 0xC29247, 0x2F327B, 0x6D550C,
    0x90A772, 0x1FE76B, 0x96CB31, 0x4A1679, 0xE27941, 0x89DFF4,
    0x9794E8, 0x84E6E2, 0x973199, 0x6BED88, 0x365F5F, 0x0EFDBB,
    0xB49A48, 0x6CA467, 0x427271, 0x325D8D, 0xB8159F, 0x09E5BC,
    0x25318D, 0x3974F7, 0x1C0530, 0x010C0D, 0x68084B, 0x58EE2C,
    0x90AA47, 0x02E774, 0x24D6BD, 0xA67DF7, 0x72486E, 0xEF169F,
    0xA6948E, 0xF691B4, 0x5153D1, 0xF20ACF, 0x339820, 0x7E4BF5,
    0x6863B2, 0x5F3EDD, 0x035D40, 0x7F8985, 0x295255, 0xC06437,
    0x10D86D, 0x324832, 0x754C5B, 0xD4714E, 0x6E5445, 0xC1090B,
    0x69F52A, 0xD56614, 0x9D0727, 0x50045D, 0xDB3BB4, 0xC576EA,
    0x17F987, 0x7D6B49, 0xBA271D, 0x296996, 0xACCCC6, 0x5414AD,
    0x6AE290, 0x89D988, 0x50722C, 0xBEA404, 0x940777, 0x7030F3,
    0x27FC00, 0xA871EA, 0x49C266, 0x3DE064, 0x83DD97, 0x973FA3,
    0xFD9443, 0x8C860D, 0xDE4131, 0x9D3992, 0x8C70DD, 0xE7B717,
    0x3BDF08, 0x2B3715, 0xA0805C, 0x93805A, 0x921110, 0xD8E80F,
    0xAF806C, 0x4BFFDB, 0x0F9038, 0x761859, 0x15A562, 0xBBCB61,
    0xB989C7, 0xBD4010, 0x04F2D2, 0x277549, 0xF6B6EB, 0xBB22DB,
    0xAA140A, 0x2F2689, 0x768364, 0x333B09, 0x1A940E, 0xAA3A51,
    0xC2A31D, 0xAEEDAF, 0x12265C, 0x4DC26D, 0x9C7A2D, 0x9756C0,
    0x833F03, 0xF6F009, 0x8C402B, 0x99316D, 0x07B439, 0x15200C,
    0x5BC3D8, 0xC492F5, 0x4BADC6, 0xA5CA4E, 0xCD37A7, 0x36A9E6,
    0x9492AB, 0x6842DD, 0xDE6319, 0xEF8C76, 0x528B68, 0x37DBFC,
    0xABA1AE, 0x3115DF, 0xA1AE00, 0xDAFB0C, 0x664D64, 0xB705ED,
    0x306529, 0xBF5657, 0x3AFF47, 0xB9F96A, 0xF3BE75, 0xDF9328,
    0x3080AB, 0xF68C66, 0x15CB04, 0x0622FA, 0x1DE4D9, 0xA4B33D,
    0x8F1B57, 0x09CD36, 0xE9424E, 0xA4BE13, 0xB52333, 0x1AAAF0,
    0xA8654F, 0xA5C1D2, 0x0F3F0B, 0xCD785B, 0x76F923, 0x048B7B,
    0x721789, 0x53A6C6, 0xE26E6F, 0x00EBEF, 0x584A9B, 0xB7DAC4,
    0xBA66AA, 0xCFCF76, 0x1D02D1, 0x2DF1B1, 0xC1998C, 0x77ADC3,
    0xDA4886, 0xA05DF7, 0xF480C6, 0x2FF0AC, 0x9AECDD, 0xBC5C3F,
    0x6DDED0, 0x1FC790, 0xB6DB2A, 0x3A25A3, 0x9AAF00, 0x9353AD,
    0x0457B6, 0xB42D29, 0x7E804B, 0xA707DA, 0x0EAA76, 0xA1597B,
    0x2A1216, 0x2DB7DC, 0xFDE5FA, 0xFEDB89, 0xFDBE89, 0x6C76E4,
    0xFCA906, 0x70803E, 0x156E85, 0xFF87FD, 0x073E28, 0x336761,
    0x86182A, 0xEABD4D, 0xAFE7B3, 0x6E6D8F, 0x396795, 0x5BBF31,
    0x48D784, 0x16DF30, 0x432DC7, 0x356125, 0xCE70C9, 0xB8CB30,
    0xFD6CBF, 0xA200A4, 0xE46C05, 0xA0DD5A, 0x476F21, 0xD21262,
    0x845CB9, 0x496170, 0xE0566B, 0x015299, 0x375550, 0xB7D51E,
    0xC4F133, 0x5F6E13, 0xE4305D, 0xA92E85, 0xC3B21D, 0x3632A1,
    0xA4B708, 0xD4B1EA, 0x21F716, 0xE4698F, 0x77FF27, 0x80030C,
    0x2D408D, 0xA0CD4F, 0x99A520, 0xD3A2B3, 0x0A5D2F, 0x42F9B4,
    0xCBDA11, 0xD0BE7D, 0xC1DB9B, 0xBD17AB, 0x81A2CA, 0x5C6A08,
    0x17552E, 0x550027, 0xF0147F, 0x8607E1, 0x640B14, 0x8D4196,
    0xDEBE87, 0x2AFDDA, 0xB6256B, 0x34897B, 0xFEF305, 0x9EBFB9,
    0x4F6A68, 0xA82A4A, 0x5AC44F, 0xBCF82D, 0x985AD7, 0x95C7F4,
    0x8D4D0D, 0xA63A20, 0x5F57A4, 0xB13F14, 0x953880, 0x0120CC,
    0x86DD71, 0xB6DEC9, 0xF560BF, 0x11654D, 0x6B0701, 0xACB08C,
    0xD0C0B2, 0x485551, 0x0EFB1E, 0xC37295, 0x3B06A3, 0x3540C0,
    0x7BDC06, 0xCC45E0, 0xFA294E, 0xC8CAD6, 0x41F3E8, 0xDE647C,
    0xD8649B, 0x31BED9, 0xC397A4, 0xD45877, 0xC5E369, 0x13DAF0,
    0x3C3ABA, 0x461846, 0x5F7555, 0xF5BDD2, 0xC6926E, 0x5D2EAC,
    0xED440E, 0x423E1C, 0x87C461, 0xE9FD29, 0xF3D6E7, 0xCA7C22,
    0x35916F, 0xC5E008, 0x8DD7FF, 0xE26A6E, 0xC6FDB0, 0xC10893,
    0x745D7C, 0xB2AD6B, 0x9D6ECD, 0x7B723E, 0x6A11C6, 0xA9CFF7,
    0xDF7329, 0xBAC9B5, 0x5100B7, 0x0DB2E2, 0x24BA74, 0x607DE5,
    0x8AD874, 0x2C150D, 0x0C1881, 0x94667E, 0x162901, 0x767A9F,
    0xBEFDFD, 0xEF4556, 0x367ED9, 0x13D9EC, 0xB9BA8B, 0xFC97C4,
    0x27A831, 0xC36EF1, 0x36C594, 0x56A8D8, 0xB5A8B4, 0x0ECCCF,
    0x2D8912, 0x34576F, 0x89562C, 0xE3CE99, 0xB920D6, 0xAA5E6B,
    0x9C2A3E, 0xCC5F11, 0x4A0BFD, 0xFBF4E1, 0x6D3B8E, 0x2C86E2,
    0x84D4E9, 0xA9B4FC, 0xD1EEEF, 0xC9352E, 0x61392F, 0x442138,
    0xC8D91B, 0x0AFC81, 0x6A4AFB, 0xD81C2F, 0x84B453, 0x8C994E,
    0xCC2254, 0xDC552A, 0xD6C6C0, 0x96190B, 0xB8701A, 0x649569,
    0x605A26, 0xEE523F, 0x0F117F, 0x11B5F4, 0xF5CBFC, 0x2DBC34,
    0xEEBC34, 0xCC5DE8, 0x605EDD, 0x9B8E67, 0xEF3392, 0xB817C9,
    0x9B5861, 0xBC57E1, 0xC68351, 0x103ED8, 0x4871DD, 0xDD1C2D,
    0xA118AF, 0x462C21, 0xD7F359, 0x987AD9, 0xC0549E, 0xFA864F,
    0xFC0656, 0xAE79E5, 0x362289, 0x22AD38, 0xDC9367, 0xAAE855,
    0x382682, 0x9BE7CA, 0xA40D51, 0xB13399, 0x0ED7A9, 0x480569,
    0xF0B265, 0xA7887F, 0x974C88, 0x36D1F9, 0xB39221, 0x4A827B,
    0x21CF98, 0xDC9F40, 0x5547DC, 0x3A74E1, 0x42EB67, 0xDF9DFE,
    0x5FD45E, 0xA4677B, 0x7AACBA, 0xA2F655, 0x23882B, 0x55BA41,
    0x086E59, 0x862A21, 0x834739, 0xE6E389, 0xD49EE5, 0x40FB49,
    0xE956FF, 0xCA0F1C, 0x8A59C5, 0x2BFA94, 0xC5C1D3, 0xCFC50F,
    0xAE5ADB, 0x86C547, 0x624385, 0x3B8621, 0x94792C, 0x876110,
    0x7B4C2A, 0x1A2C80, 0x12BF43, 0x902688, 0x893C78, 0xE4C4A8,
    0x7BDBE5, 0xC23AC4, 0xEAF426, 0x8A67F7, 0xBF920D, 0x2BA365,
    0xB1933D, 0x0B7CBD, 0xDC51A4, 0x63DD27, 0xDDE169, 0x19949A,
    0x9529A8, 0x28CE68, 0xB4ED09, 0x209F44, 0xCA984E, 0x638270,
    0x237C7E, 0x32B90F, 0x8EF5A7, 0xE75614, 0x08F121, 0x2A9DB5,
    0x4D7E6F, 0x5119A5, 0xABF9B5, 0xD6DF82, 0x61DD96, 0x023616,
    0x9F3AC4, 0xA1A283, 0x6DED72, 0x7A8D39, 0xA9B882, 0x5C326B,
    0x5B2746, 0xED3400, 0x7700D2, 0x55F4FC, 0x4D5901, 0x8071E0,];

/// `pi/2` cut into 24-bit chunks.
const PIO2: [f64; 8] = [
    1.57079625129699707031e+00,
    7.54978941586159635335e-08,
    5.39030252995776476554e-15,
    3.28200341580791294123e-22,
    1.27065575308067607349e-29,
    1.22933308981111328932e-36,
    2.73370053816464559624e-44,
    2.16741683877804819444e-51,
];

/// Payne-Hanek reduction for large `x`. `x` holds `nx` 24-bit chunks of the
/// input, scaled by `2^e0`.
fn rem_pio2_large(x: &[f64], y: &mut [f64; 3], e0: i32, nx: usize, prec: usize) -> i32 {
    let mut iq = [0i32; 20];
    let mut f = [0.0f64; 20];
    let mut fq = [0.0f64; 20];
    let mut q = [0.0f64; 20];

    let jk = INIT_JK[prec];
    let jp = jk;

    let jx = nx - 1;
    let mut jv = (e0 - 3) / 24;
    if jv < 0 {
        jv = 0;
    }
    let mut q0 = e0 - 24 * (jv + 1);

    // f[jx + i] = ipio2[jv + i]
    let mut j = jv - jx as i32;
    let m = jx + jk;
    for i in 0..=m {
        f[i] = if j < 0 { 0.0 } else { IPIO2[j as usize] as f64 };
        j += 1;
    }

    for i in 0..=jk {
        let mut fw = 0.0;
        for jj in 0..=jx {
            fw += x[jj] * f[jx + i - jj];
        }
        q[i] = fw;
    }

    let mut jz = jk;

    'recompute: loop {
        // Distil q[] into iq[], least significant chunk first.
        let mut i = 0usize;
        let mut jj = jz;
        let mut z = q[jz];
        while jj > 0 {
            let fw = (z * (1.0 / 16_777_216.0)) as i32 as f64; // trunc toward zero
            iq[i] = (z - 16_777_216.0 * fw) as i32;
            z = q[jj - 1] + fw;
            i += 1;
            jj -= 1;
        }

        z = scalbn(z, q0);
        z -= 8.0 * (z * 0.125).floor(); // trim off integer >= 8
        let mut n = z as i32;
        z -= n as f64;
        let mut ih = 0i32;
        if q0 > 0 {
            let v = iq[jz - 1] >> (24 - q0);
            n += v;
            iq[jz - 1] -= v << (24 - q0);
            ih = iq[jz - 1] >> (23 - q0);
        } else if q0 == 0 {
            ih = iq[jz - 1] >> 23;
        } else if z >= 0.5 {
            ih = 2;
        }

        if ih > 0 {
            // q > 0.5: compute 1 - q.
            n += 1;
            let mut carry = 0i32;
            for item in iq.iter_mut().take(jz) {
                let v = *item;
                if carry == 0 {
                    if v != 0 {
                        carry = 1;
                        *item = 0x100_0000 - v;
                    }
                } else {
                    *item = 0xff_ffff - v;
                }
            }
            if q0 > 0 {
                // Rare: one chance in twelve.
                match q0 {
                    1 => iq[jz - 1] &= 0x7f_ffff,
                    2 => iq[jz - 1] &= 0x3f_ffff,
                    _ => {}
                }
            }
            if ih == 2 {
                z = 1.0 - z;
                if carry != 0 {
                    z -= scalbn(1.0, q0);
                }
            }
        }

        // If the residual vanished, more terms of 2/pi may be needed.
        if z == 0.0 {
            let mut acc = 0i32;
            let mut i = jz as isize - 1;
            while i >= jk as isize {
                acc |= iq[i as usize];
                i -= 1;
            }
            if acc == 0 {
                let mut k = 1usize;
                while iq[jk - k] == 0 {
                    k += 1;
                }
                let mut i = jz + 1;
                while i <= jz + k {
                    f[jx + i] = IPIO2[(jv + i as i32) as usize] as f64;
                    let mut fw = 0.0;
                    for jj in 0..=jx {
                        fw += x[jj] * f[jx + i - jj];
                    }
                    q[i] = fw;
                    i += 1;
                }
                jz += k;
                continue 'recompute;
            }
        }

        // Chop off zero terms.
        if z == 0.0 {
            jz -= 1;
            q0 -= 24;
            while iq[jz] == 0 {
                jz -= 1;
                q0 -= 24;
            }
        } else {
            z = scalbn(z, -q0);
            if z >= 16_777_216.0 {
                let fw = (z * (1.0 / 16_777_216.0)) as i32 as f64;
                iq[jz] = (z - 16_777_216.0 * fw) as i32;
                jz += 1;
                q0 += 24;
                iq[jz] = fw as i32;
            } else {
                iq[jz] = z as i32;
            }
        }

        // Chunks back to doubles.
        let mut fw = scalbn(1.0, q0);
        let mut i = jz as isize;
        while i >= 0 {
            q[i as usize] = fw * iq[i as usize] as f64;
            fw *= 1.0 / 16_777_216.0;
            i -= 1;
        }

        // Multiply by pi/2.
        let mut i = jz as isize;
        while i >= 0 {
            let mut acc = 0.0;
            let mut k = 0usize;
            while k <= jp && k as isize <= jz as isize - i {
                acc += PIO2[k] * q[(i as usize) + k];
                k += 1;
            }
            fq[jz - i as usize] = acc;
            i -= 1;
        }

        // Compress into y[].
        match prec {
            0 => {
                let mut acc = 0.0;
                let mut i = jz as isize;
                while i >= 0 {
                    acc += fq[i as usize];
                    i -= 1;
                }
                y[0] = if ih == 0 { acc } else { -acc };
            }
            3 => {
                let mut i = jz as isize;
                while i > 0 {
                    let t = fq[(i - 1) as usize] + fq[i as usize];
                    fq[i as usize] += fq[(i - 1) as usize] - t;
                    fq[(i - 1) as usize] = t;
                    i -= 1;
                }
                let mut i = jz as isize;
                while i > 1 {
                    let t = fq[(i - 1) as usize] + fq[i as usize];
                    fq[i as usize] += fq[(i - 1) as usize] - t;
                    fq[(i - 1) as usize] = t;
                    i -= 1;
                }
                let mut acc = 0.0;
                let mut i = jz as isize;
                while i >= 2 {
                    acc += fq[i as usize];
                    i -= 1;
                }
                if ih == 0 {
                    y[0] = fq[0];
                    y[1] = fq[1];
                    y[2] = acc;
                } else {
                    y[0] = -fq[0];
                    y[1] = -fq[1];
                    y[2] = -acc;
                }
            }
            _ => {
                // prec 1 (double) and 2 (extended) share this branch.
                let mut acc = 0.0;
                let mut i = jz as isize;
                while i >= 0 {
                    acc += fq[i as usize];
                    i -= 1;
                }
                y[0] = if ih == 0 { acc } else { -acc };
                let mut tail = fq[0] - acc;
                let mut i = 1usize;
                while i <= jz {
                    tail += fq[i];
                    i += 1;
                }
                y[1] = if ih == 0 { tail } else { -tail };
            }
        }

        return n & 7;
    }
}

// ── public entry points ────────────────────────────────────────────────────

/// Sine, bit-for-bit what fdlibm's `sin` returns.
pub fn sin(x: f64) -> f64 {
    let ix = ((x.to_bits() >> 32) as u32) & 0x7fff_ffff;

    if ix <= 0x3fe9_21fb {
        // |x| ~< pi/4
        if ix < 0x3e50_0000 {
            // |x| < 2^-26: sin(x) rounds to x
            return x;
        }
        return kernel_sin(x, 0.0, false);
    }
    if ix >= 0x7ff0_0000 {
        return x - x; // inf or NaN
    }

    let mut y = [0.0f64; 2];
    let n = rem_pio2(x, &mut y);
    match n & 3 {
        0 => kernel_sin(y[0], y[1], true),
        1 => kernel_cos(y[0], y[1]),
        2 => -kernel_sin(y[0], y[1], true),
        _ => -kernel_cos(y[0], y[1]),
    }
}

/// Cosine, bit-for-bit what fdlibm's `cos` returns.
pub fn cos(x: f64) -> f64 {
    let ix = ((x.to_bits() >> 32) as u32) & 0x7fff_ffff;

    if ix <= 0x3fe9_21fb {
        // |x| ~< pi/4
        if ix < 0x3e46_a09e {
            // |x| < 2^-27 * sqrt(2): cos(x) rounds to 1
            return 1.0;
        }
        return kernel_cos(x, 0.0);
    }
    if ix >= 0x7ff0_0000 {
        return x - x;
    }

    let mut y = [0.0f64; 2];
    let n = rem_pio2(x, &mut y);
    match n & 3 {
        0 => kernel_cos(y[0], y[1]),
        1 => -kernel_sin(y[0], y[1], true),
        2 => -kernel_cos(y[0], y[1]),
        _ => kernel_sin(y[0], y[1], true),
    }
}

// ── atan / atan2 ───────────────────────────────────────────────────────────

const ATANHI: [f64; 4] = [
    4.63647609000806093515e-01, // atan(0.5)
    7.85398163397448278999e-01, // atan(1.0)
    9.82793723247329054082e-01, // atan(1.5)
    1.57079632679489655800e+00, // atan(inf)
];
const ATANLO: [f64; 4] = [
    2.26987774529616870924e-17,
    3.06161699786838301793e-17,
    1.39033110312309984516e-17,
    6.12323399573676603587e-17,
];
const AT: [f64; 11] = [
    3.33333333333329318027e-01,
    -1.99999999998764832476e-01,
    1.42857142725034663711e-01,
    -1.11111104054623557880e-01,
    9.09088713343650656196e-02,
    -7.69187620504482999495e-02,
    6.66107313738753120669e-02,
    -5.83357013379057348645e-02,
    4.97687799461593236017e-02,
    -3.65315727442169155270e-02,
    1.62858201153657823623e-02,
];

const PI: f64 = 3.1415926535897931160E+00;
const PI_LO: f64 = 1.2246467991473531772E-16;

/// Arc tangent, fdlibm's `atan`.
pub fn atan(x: f64) -> f64 {
    let bits = x.to_bits();
    let sign = (bits >> 63) != 0;
    let ix = ((bits >> 32) as u32) & 0x7fff_ffff;

    if ix >= 0x4410_0000 {
        // |x| >= 2^66: saturates at +-pi/2
        if x.is_nan() {
            return x;
        }
        return if sign { -ATANHI[3] } else { ATANHI[3] };
    }

    let mut x = x;
    let id: i32;
    if ix < 0x3fdc_0000 {
        // |x| < 0.4375
        if ix < 0x3e40_0000 {
            // |x| < 2^-27: atan(x) rounds to x
            return x;
        }
        id = -1;
    } else {
        x = x.abs();
        if ix < 0x3ff3_0000 {
            // |x| < 1.1875
            if ix < 0x3fe6_0000 {
                // 7/16 <= |x| < 11/16
                id = 0;
                x = (2.0 * x - 1.0) / (2.0 + x);
            } else {
                // 11/16 <= |x| < 19/16
                id = 1;
                x = (x - 1.0) / (x + 1.0);
            }
        } else if ix < 0x4003_8000 {
            // |x| < 2.4375
            id = 2;
            x = (x - 1.5) / (1.0 + 1.5 * x);
        } else {
            // 2.4375 <= |x| < 2^66
            id = 3;
            x = -1.0 / x;
        }
    }

    let z = x * x;
    let w = z * z;
    // The degree-11 polynomial is split into odd and even halves so each can be
    // evaluated as a straight Horner chain.
    let s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))));
    let s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))));
    if id < 0 {
        return x - x * (s1 + s2);
    }
    let z = ATANHI[id as usize] - (x * (s1 + s2) - ATANLO[id as usize] - x);
    if sign {
        -z
    } else {
        z
    }
}

/// Two-argument arc tangent, fdlibm's `atan2`.
pub fn atan2(y: f64, x: f64) -> f64 {
    if x.is_nan() || y.is_nan() {
        return x + y;
    }

    let xb = x.to_bits();
    let yb = y.to_bits();
    let mut ix = (xb >> 32) as u32;
    let lx = xb as u32;
    let mut iy = (yb >> 32) as u32;
    let ly = yb as u32;

    if (ix.wrapping_sub(0x3ff0_0000) | lx) == 0 {
        // x == 1.0
        return atan(y);
    }

    // 2*sign(x) + sign(y)
    let m = ((iy >> 31) & 1) | ((ix >> 30) & 2);
    ix &= 0x7fff_ffff;
    iy &= 0x7fff_ffff;

    if (iy | ly) == 0 {
        // y == 0
        return match m {
            0 | 1 => y, // atan(+-0, +anything) = +-0
            2 => PI,    // atan(+0, -anything) = pi
            _ => -PI,   // atan(-0, -anything) = -pi
        };
    }
    if (ix | lx) == 0 {
        // x == 0
        return if m & 1 != 0 { -PI / 2.0 } else { PI / 2.0 };
    }
    if ix == 0x7ff0_0000 {
        // x is infinite
        if iy == 0x7ff0_0000 {
            return match m {
                0 => PI / 4.0,
                1 => -PI / 4.0,
                2 => 3.0 * PI / 4.0,
                _ => -3.0 * PI / 4.0,
            };
        }
        return match m {
            0 => 0.0,
            1 => -0.0,
            2 => PI,
            _ => -PI,
        };
    }
    if ix + (64 << 20) < iy || iy == 0x7ff0_0000 {
        // |y/x| > 2^64
        return if m & 1 != 0 { -PI / 2.0 } else { PI / 2.0 };
    }

    // atan(|y/x|), with the underflow case handled explicitly: for x < 0 and a
    // vanishing ratio the division itself would flush to zero and lose the sign
    // information the caller needs.
    let z = if (m & 2) != 0 && iy + (64 << 20) < ix {
        0.0
    } else {
        atan((y / x).abs())
    };

    match m {
        0 => z,
        1 => -z,
        2 => PI - (z - PI_LO),
        _ => (z - PI_LO) - PI,
    }
}

// ── acos ───────────────────────────────────────────────────────────────────

const PIO2_HI: f64 = 1.57079632679489655800e+00;
const PIO2_LO: f64 = 6.12323399573676603587e-17;
const PS0: f64 = 1.66666666666666657415e-01;
const PS1: f64 = -3.25565818622400915405e-01;
const PS2: f64 = 2.01212532134862925881e-01;
const PS3: f64 = -4.00555345006794114027e-02;
const PS4: f64 = 7.91534994289814532176e-04;
const PS5: f64 = 3.47933107596021167570e-05;
const QS1: f64 = -2.40339491173441421878e+00;
const QS2: f64 = 2.02094576023350569471e+00;
const QS3: f64 = -6.88283971605453293030e-01;
const QS4: f64 = 7.70381505559019352791e-02;

fn acos_r(z: f64) -> f64 {
    let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
    let q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
    p / q
}

/// Arc cosine, fdlibm's `acos`.
pub fn acos(x: f64) -> f64 {
    let hx = (x.to_bits() >> 32) as u32;
    let ix = hx & 0x7fff_ffff;

    if ix >= 0x3ff0_0000 {
        // |x| >= 1, or NaN
        let lx = x.to_bits() as u32;
        if (ix.wrapping_sub(0x3ff0_0000) | lx) == 0 {
            // acos(1) = 0, acos(-1) = pi
            return if (hx >> 31) != 0 { 2.0 * PIO2_HI } else { 0.0 };
        }
        return 0.0 / (x - x); // NaN with the invalid flag raised
    }

    if ix < 0x3fe0_0000 {
        // |x| < 0.5
        if ix <= 0x3c60_0000 {
            // |x| < 2^-57: acos(x) rounds to pi/2
            return PIO2_HI;
        }
        return PIO2_HI - (x - (PIO2_LO - x * acos_r(x * x)));
    }

    if (hx >> 31) != 0 {
        // x < -0.5
        let z = (1.0 + x) * 0.5;
        let s = z.sqrt();
        let w = acos_r(z) * s - PIO2_LO;
        return 2.0 * (PIO2_HI - (s + w));
    }

    // x > 0.5
    let z = (1.0 - x) * 0.5;
    let s = z.sqrt();
    // `df` keeps only the high half of `s`, which makes `z - df*df` exact and
    // leaves `c` as the correction term.
    let df = f64::from_bits(s.to_bits() & 0xffff_ffff_0000_0000);
    let c = (z - df * df) / (s + df);
    let w = acos_r(z) * s + c;
    2.0 * (df + w)
}

#[cfg(test)]
mod tests {
    use super::{acos, atan, atan2, cos, sin};

    /// Replays `golden/trig_input.tsv` against `golden/trig_expected.tsv`.
    ///
    /// The expected file was produced by compiling the **original C** (musl /
    /// FreeBSD `lib/msun`) with `-ffp-contract=off`, not by running this module.
    /// Kotlin's `PortableMathParityTest` replays the same two files, so the three
    /// implementations are pinned to one external reference instead of to each
    /// other. A self-generated fixture would only prove the code does what it
    /// does.
    ///
    /// If this fails after an intentional change, regenerate the fixture from the
    /// C build — do not re-record it from this module.
    #[test]
    fn matches_fdlibm_reference_bit_for_bit() {
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/golden");
        let input = std::fs::read_to_string(format!("{}/trig_input.tsv", dir))
            .expect("golden/trig_input.tsv missing");
        let expected = std::fs::read_to_string(format!("{}/trig_expected.tsv", dir))
            .expect("golden/trig_expected.tsv missing");

        // The fixture stores unsigned decimal, because that is what C's `%llu`
        // prints; half the bit patterns exceed i64::MAX.
        let parse = |text: &str| -> Vec<Vec<u64>> {
            text.lines()
                .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
                .map(|l| l.split_whitespace().map(|t| t.parse::<u64>().unwrap()).collect())
                .collect()
        };
        let input = parse(&input);
        let expected = parse(&expected);
        assert_eq!(input.len(), expected.len(), "fixture rows disagree");
        assert_eq!(input.len(), 4000, "fixture row count changed");

        for (i, (inp, want)) in input.iter().zip(expected.iter()).enumerate() {
            let x = f64::from_bits(inp[0]);
            let y = f64::from_bits(inp[1]);
            let a = f64::from_bits(inp[2]);

            assert_eq!(sin(x).to_bits(), want[0], "row {i}: sin({x:e})");
            assert_eq!(cos(x).to_bits(), want[1], "row {i}: cos({x:e})");
            assert_eq!(atan(y).to_bits(), want[2], "row {i}: atan({y:e})");
            assert_eq!(atan2(y, x).to_bits(), want[3], "row {i}: atan2({y:e}, {x:e})");
            assert_eq!(acos(a).to_bits(), want[4], "row {i}: acos({a:e})");
        }
    }

    #[test]
    fn pythagorean_identity_holds_to_rounding() {
        // Not a parity requirement — a smoke test that the reduction is not
        // simply wrong. The real guarantee is the fixture above.
        for k in 0..2000 {
            let x = (k as f64 - 1000.0) * 0.017;
            let (s, c) = (sin(x), cos(x));
            assert!((s * s + c * c - 1.0).abs() < 4.0 * f64::EPSILON, "x = {x}");
        }
    }

    #[test]
    fn exact_at_zero_and_tiny() {
        assert_eq!(sin(0.0).to_bits(), 0);
        assert_eq!(sin(-0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(cos(0.0), 1.0);
        // Below 2^-26 the polynomial has nothing to add.
        let tiny = 2f64.powi(-30);
        assert_eq!(sin(tiny).to_bits(), tiny.to_bits());
        assert_eq!(cos(tiny), 1.0);
    }

    #[test]
    fn atan2_quadrants_are_exact() {
        use std::f64::consts::PI;
        assert_eq!(atan2(0.0, 1.0), 0.0);
        assert_eq!(atan2(-0.0, 1.0), -0.0);
        assert_eq!(atan2(0.0, -1.0), PI);
        assert_eq!(atan2(-0.0, -1.0), -PI);
        assert_eq!(atan2(1.0, 0.0), PI / 2.0);
        assert_eq!(atan2(-1.0, 0.0), -PI / 2.0);
        assert_eq!(atan2(1.0, 1.0), PI / 4.0);
        assert!(atan2(f64::NAN, 1.0).is_nan());
    }

    #[test]
    fn acos_domain_edges() {
        assert_eq!(acos(1.0), 0.0);
        assert_eq!(acos(-1.0).to_bits(), (2.0 * std::f64::consts::FRAC_PI_2).to_bits());
        assert!(acos(2.0).is_nan());
        assert!(acos(-2.0).is_nan());
    }
}
