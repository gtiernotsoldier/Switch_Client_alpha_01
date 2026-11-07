package io.doppel.core.math

/**
 * `sin`, `cos`, `atan`, `atan2`, `acos` — one implementation, both languages.
 *
 * ## Why the platform maths are not used
 *
 * The reference implementation is Kotlin and the port is Rust, and the two are
 * required to agree **bit for bit**. That requirement is incompatible with
 * `java.lang.Math`, `kotlin.math`, or Rust's `f64::sin`:
 *
 *  - `Math.sin` is a HotSpot intrinsic. It differs from `StrictMath.sin` on 6.7%
 *    of inputs in `[-6, 6]` (measured, 2M samples), and the intrinsic is
 *    generated per CPU architecture, so it is not even stable across the
 *    machines the CI runs on.
 *  - `StrictMath.sin` looks stable but is a `native` method. Its body comes from
 *    the JDK build, not from a specification: on OpenJDK 20 (Zulu) it disagrees
 *    with glibc's `sin` on 51.6% of inputs, and with both `Math.sin` and the
 *    fdlibm transcription in this file on `cos(0.1)` specifically.
 *  - glibc's `sin`/`cos` are correctly rounded and therefore disagree with
 *    fdlibm roughly half the time.
 *
 * A one-ulp difference is not cosmetic here. These values feed scores that are
 * compared with `maxByOrNull` and a `MIN_GAIN` threshold; one ulp moves a
 * near-tie, and then the two languages emit different keystrokes.
 *
 * ## What this does instead
 *
 * `io.doppel.core.math.PortableMath` and `core-rs/src/trig.rs` are both
 * transcriptions of the same fdlibm routines (FreeBSD `lib/msun`, as carried by
 * musl). Java guarantees strict IEEE-754 evaluation — JLS 15.4 forbids
 * intermediate extended precision and forbids contracting `a*b+c` into an FMA —
 * and Rust/LLVM does not contract either, so identical expressions produce
 * identical bits on both sides.
 *
 * The Rust side was verified against a compiled build of the original C for
 * 200 000 inputs x 5 functions: **zero differences**. This file is verified
 * against the same fixture in `PortableMathParityTest`.
 *
 * ## Editing this file
 *
 * Do not reorder a sum, do not fuse a multiply-add, do not "simplify" an
 * expression. Each line mirrors a line of C, and any algebraic "improvement"
 * changes which bit comes out. If a value must change, the fixture has to be
 * regenerated too — that is the point of it being checked in.
 */
object PortableMath {

    // ── bit plumbing ──────────────────────────────────────────────────────

    private fun bits(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

    private fun fromBits(b: Long): Double = java.lang.Double.longBitsToDouble(b)

    private fun highWord(d: Double): Long = bits(d) ushr 32

    /**
     * `scalbn(x, n)` — exact, because multiplying by a power of two cannot round.
     *
     * Every call site keeps `n` inside the normal range, so the subnormal and
     * overflow paths of the C original never run here.
     */
    private fun scalbn(x: Double, n: Int): Double {
        if (x == 0.0 || !x.isFinite()) return x
        var y = x
        var m = n
        while (m > 1000) {
            y *= pow2(1000)
            m -= 1000
        }
        while (m < -1000) {
            y *= pow2(-1000)
            m += 1000
        }
        return y * pow2(m)
    }

    private fun pow2(n: Int): Double = fromBits((1023L + n) shl 52)

    // ── kernels on [-pi/4, pi/4] ──────────────────────────────────────────

    private const val S1 = -1.66666666666666324348e-01
    private const val S2 = 8.33333333332248946124e-03
    private const val S3 = -1.98412698298579493134e-04
    private const val S4 = 2.75573137070700676789e-06
    private const val S5 = -2.50507602534068634195e-08
    private const val S6 = 1.58969099521155010221e-10

    private const val C1 = 4.16666666666666019037e-02
    private const val C2 = -1.38888888888741095749e-03
    private const val C3 = 2.48015872894767294178e-05
    private const val C4 = -2.75573143513906633035e-07
    private const val C5 = 2.08757232129817482790e-09
    private const val C6 = -1.13596475577881948265e-11

    /** `iy` false is the C `iy == 0` case: the tail is known to be zero. */
    private fun kernelSin(x: Double, y: Double, iy: Boolean): Double {
        val z = x * x
        val w = z * z
        val r = S2 + z * (S3 + z * S4) + z * w * (S5 + z * S6)
        val v = z * x
        return if (!iy) {
            x + v * (S1 + z * r)
        } else {
            x - ((z * (0.5 * y - v * r) - y) - v * S1)
        }
    }

    private fun kernelCos(x: Double, y: Double): Double {
        val z = x * x
        val w = z * z
        val r = z * (C1 + z * (C2 + z * C3)) + w * w * (C4 + z * (C5 + z * C6))
        val hz = 0.5 * z
        val w2 = 1.0 - hz
        // `1.0 - w2` is exact; it recovers the bits the subtraction dropped.
        return w2 + (((1.0 - w2) - hz) + (z * r - x * y))
    }

    // ── argument reduction ────────────────────────────────────────────────

    /** `1.5 / DBL_EPSILON`, i.e. 1.5 * 2^52. */
    private const val TOINT = 6755399441055744.0
    private const val PIO4 = 0.7853981633974483
    private const val INVPIO2 = 6.36619772367581382433e-01
    private const val PIO2_1 = 1.57079632673412561417e+00
    private const val PIO2_1T = 6.07710050650619224932e-11
    private const val PIO2_2 = 6.07710050630396597660e-11
    private const val PIO2_2T = 2.02226624879595063154e-21
    private const val PIO2_3 = 2.02226624871116645580e-21
    private const val PIO2_3T = 8.47842766036889956997e-32

    private val INIT_JK = intArrayOf(3, 4, 4, 6)

    /**
     * Returns `n` such that `x - (n mod 4) * pi/2` lies in `[-pi/4, pi/4]`,
     * writing the residual head and tail into `y`.
     */
    private fun remPio2(x: Double, y: DoubleArray): Int {
        val sign = (bits(x) ushr 63) != 0L
        val ix = highWord(x) and 0x7fffffffL

        fun small(n: Int): Int {
            val k = n.toDouble()
            val z = if (!sign) x - k * PIO2_1 else x + k * PIO2_1
            y[0] = if (!sign) z - k * PIO2_1T else z + k * PIO2_1T
            y[1] = if (!sign) (z - y[0]) - k * PIO2_1T else (z - y[0]) + k * PIO2_1T
            return if (!sign) n else -n
        }

        if (ix <= 0x400f6a7aL) {
            // |x| ~<= 5pi/4
            if ((ix and 0xfffffL) == 0x921fbL) {
                // |x| ~= pi/2 or pi: the head cancels, so the fast path would
                // lose everything. Fall through to the medium path.
            } else if (ix <= 0x4002d97cL) {
                return small(1) // |x| ~<= 3pi/4
            } else {
                return small(2)
            }
        } else if (ix <= 0x401c463bL) {
            // |x| ~<= 9pi/4
            if (ix <= 0x4015fdbcL) {
                if (ix != 0x4012d97cL) {
                    // |x| ~<= 7pi/4, and not 3pi/2
                    return small(3)
                }
            } else if (ix != 0x401921fbL) {
                // |x| ~<= 9pi/4, and not 2pi
                return small(4)
            }
        }

        if (ix < 0x413921fbL) {
            // |x| ~< 2^20 * (pi/2): Cody-Waite, up to three rounds.
            val fnInit = x * INVPIO2 + TOINT - TOINT
            var n = fnInit.toInt()
            var fn = fnInit
            var r = x - fn * PIO2_1
            var w = fn * PIO2_1T

            if (r - w < -PIO4) {
                n -= 1
                fn -= 1.0
                r = x - fn * PIO2_1
                w = fn * PIO2_1T
            } else if (r - w > PIO4) {
                n += 1
                fn += 1.0
                r = x - fn * PIO2_1
                w = fn * PIO2_1T
            }

            y[0] = r - w
            val ey = ((bits(y[0]) ushr 52) and 0x7ffL).toInt()
            val ex = (ix ushr 20).toInt()
            if (ex - ey > 16) {
                // 2nd round, good to 118 bits
                val t = r
                w = fn * PIO2_2
                r = t - w
                w = fn * PIO2_2T - ((t - r) - w)
                y[0] = r - w
                val ey2 = ((bits(y[0]) ushr 52) and 0x7ffL).toInt()
                if (ex - ey2 > 49) {
                    // 3rd round, good to 151 bits — covers every double
                    val t2 = r
                    w = fn * PIO2_3
                    r = t2 - w
                    w = fn * PIO2_3T - ((t2 - r) - w)
                    y[0] = r - w
                }
            }
            y[1] = (r - y[0]) - w
            return n
        }

        if (ix >= 0x7ff00000L) {
            // inf or NaN
            y[0] = x - x
            y[1] = x - x
            return 0
        }

        // Large arguments: Payne-Hanek over 24-bit chunks.
        var z = fromBits((bits(x) and 0xfffffffffffffL) or ((0x3ff + 23).toLong() shl 52))
        val tx = DoubleArray(3)
        var i = 0
        while (i < 2) {
            tx[i] = z.toInt().toDouble()
            z = (z - tx[i]) * 16777216.0 // 2^24
            i++
        }
        tx[i] = z
        while (tx[i] == 0.0) i--
        val ty = DoubleArray(3)
        val n = remPio2Large(tx, ty, (ix ushr 20).toInt() - (0x3ff + 23), i + 1, 1)
        return if (sign) {
            y[0] = -ty[0]
            y[1] = -ty[1]
            -n
        } else {
            y[0] = ty[0]
            y[1] = ty[1]
            n
        }
    }

    /** `2/pi` to 396 hex digits, 24 bits per entry. */
    private val IPIO2 = intArrayOf(
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
        0x5B2746, 0xED3400, 0x7700D2, 0x55F4FC, 0x4D5901, 0x8071E0,
    )

    /** `pi/2` cut into 24-bit chunks. */
    private val PIO2 = doubleArrayOf(
        1.57079625129699707031e+00,
        7.54978941586159635335e-08,
        5.39030252995776476554e-15,
        3.28200341580791294123e-22,
        1.27065575308067607349e-29,
        1.22933308981111328932e-36,
        2.73370053816464559624e-44,
        2.16741683877804819444e-51
    )

    /**
     * Payne-Hanek reduction for large `x`. `x` holds `nx` 24-bit chunks of the
     * input, scaled by `2^e0`.
     */
    private fun remPio2Large(x: DoubleArray, y: DoubleArray, e0: Int, nx: Int, prec: Int): Int {
        val iq = IntArray(20)
        val f = DoubleArray(20)
        val fq = DoubleArray(20)
        val q = DoubleArray(20)

        val jk = INIT_JK[prec]
        val jp = jk

        val jx = nx - 1
        var jv = (e0 - 3) / 24
        if (jv < 0) jv = 0
        var q0 = e0 - 24 * (jv + 1)

        var j = jv - jx
        val m = jx + jk
        for (i in 0..m) {
            f[i] = if (j < 0) 0.0 else IPIO2[j].toDouble()
            j++
        }

        for (i in 0..jk) {
            var fw = 0.0
            for (jj in 0..jx) fw += x[jj] * f[jx + i - jj]
            q[i] = fw
        }

        var jz = jk

        recompute@ while (true) {
            // Distil q[] into iq[], least significant chunk first.
            var i = 0
            var jj = jz
            var z = q[jz]
            while (jj > 0) {
                val fw = (z * (1.0 / 16777216.0)).toInt().toDouble()
                iq[i] = (z - 16777216.0 * fw).toInt()
                z = q[jj - 1] + fw
                i++
                jj--
            }

            z = scalbn(z, q0)
            z -= 8.0 * kotlin.math.floor(z * 0.125) // trim off integer >= 8
            var n = z.toInt()
            z -= n.toDouble()
            var ih = 0
            if (q0 > 0) {
                val v = iq[jz - 1] shr (24 - q0)
                n += v
                iq[jz - 1] -= v shl (24 - q0)
                ih = iq[jz - 1] shr (23 - q0)
            } else if (q0 == 0) {
                ih = iq[jz - 1] shr 23
            } else if (z >= 0.5) {
                ih = 2
            }

            if (ih > 0) {
                // q > 0.5: compute 1 - q.
                n += 1
                var carry = 0
                for (t in 0 until jz) {
                    val v = iq[t]
                    if (carry == 0) {
                        if (v != 0) {
                            carry = 1
                            iq[t] = 0x1000000 - v
                        }
                    } else {
                        iq[t] = 0xffffff - v
                    }
                }
                if (q0 > 0) {
                    // Rare: one chance in twelve.
                    when (q0) {
                        1 -> iq[jz - 1] = iq[jz - 1] and 0x7fffff
                        2 -> iq[jz - 1] = iq[jz - 1] and 0x3fffff
                    }
                }
                if (ih == 2) {
                    z = 1.0 - z
                    if (carry != 0) z -= scalbn(1.0, q0)
                }
            }

            // If the residual vanished, more terms of 2/pi may be needed.
            if (z == 0.0) {
                var acc = 0
                var i2 = jz - 1
                while (i2 >= jk) {
                    acc = acc or iq[i2]
                    i2--
                }
                if (acc == 0) {
                    var k = 1
                    while (iq[jk - k] == 0) k++
                    var i3 = jz + 1
                    while (i3 <= jz + k) {
                        f[jx + i3] = IPIO2[jv + i3].toDouble()
                        var fw = 0.0
                        for (jj2 in 0..jx) fw += x[jj2] * f[jx + i3 - jj2]
                        q[i3] = fw
                        i3++
                    }
                    jz += k
                    continue@recompute
                }
            }

            // Chop off zero terms.
            if (z == 0.0) {
                jz -= 1
                q0 -= 24
                while (iq[jz] == 0) {
                    jz -= 1
                    q0 -= 24
                }
            } else {
                z = scalbn(z, -q0)
                if (z >= 16777216.0) {
                    val fw = (z * (1.0 / 16777216.0)).toInt().toDouble()
                    iq[jz] = (z - 16777216.0 * fw).toInt()
                    jz += 1
                    q0 += 24
                    iq[jz] = fw.toInt()
                } else {
                    iq[jz] = z.toInt()
                }
            }

            // Chunks back to doubles.
            var fw = scalbn(1.0, q0)
            var i4 = jz
            while (i4 >= 0) {
                q[i4] = fw * iq[i4].toDouble()
                fw *= 1.0 / 16777216.0
                i4--
            }

            // Multiply by pi/2.
            var i5 = jz
            while (i5 >= 0) {
                var acc = 0.0
                var k = 0
                while (k <= jp && k <= jz - i5) {
                    acc += PIO2[k] * q[i5 + k]
                    k++
                }
                fq[jz - i5] = acc
                i5--
            }

            // Compress into y[].
            when (prec) {
                0 -> {
                    var acc = 0.0
                    var i6 = jz
                    while (i6 >= 0) {
                        acc += fq[i6]
                        i6--
                    }
                    y[0] = if (ih == 0) acc else -acc
                }

                3 -> {
                    var i6 = jz
                    while (i6 > 0) {
                        val t = fq[i6 - 1] + fq[i6]
                        fq[i6] += fq[i6 - 1] - t
                        fq[i6 - 1] = t
                        i6--
                    }
                    i6 = jz
                    while (i6 > 1) {
                        val t = fq[i6 - 1] + fq[i6]
                        fq[i6] += fq[i6 - 1] - t
                        fq[i6 - 1] = t
                        i6--
                    }
                    var acc = 0.0
                    i6 = jz
                    while (i6 >= 2) {
                        acc += fq[i6]
                        i6--
                    }
                    if (ih == 0) {
                        y[0] = fq[0]
                        y[1] = fq[1]
                        y[2] = acc
                    } else {
                        y[0] = -fq[0]
                        y[1] = -fq[1]
                        y[2] = -acc
                    }
                }

                else -> {
                    // prec 1 (double) and 2 (extended) share this branch.
                    var acc = 0.0
                    var i6 = jz
                    while (i6 >= 0) {
                        acc += fq[i6]
                        i6--
                    }
                    y[0] = if (ih == 0) acc else -acc
                    var tail = fq[0] - acc
                    var i7 = 1
                    while (i7 <= jz) {
                        tail += fq[i7]
                        i7++
                    }
                    y[1] = if (ih == 0) tail else -tail
                }
            }

            return n and 7
        }
    }

    // ── public entry points ───────────────────────────────────────────────

    /** Sine, bit-for-bit the fdlibm value. */
    fun sin(x: Double): Double {
        val ix = highWord(x) and 0x7fffffffL
        if (ix <= 0x3fe921fbL) {
            // |x| ~< pi/4
            if (ix < 0x3e500000L) return x // |x| < 2^-26
            return kernelSin(x, 0.0, false)
        }
        if (ix >= 0x7ff00000L) return x - x // inf or NaN

        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return when (n and 3) {
            0 -> kernelSin(y[0], y[1], true)
            1 -> kernelCos(y[0], y[1])
            2 -> -kernelSin(y[0], y[1], true)
            else -> -kernelCos(y[0], y[1])
        }
    }

    /** Cosine, bit-for-bit the fdlibm value. */
    fun cos(x: Double): Double {
        val ix = highWord(x) and 0x7fffffffL
        if (ix <= 0x3fe921fbL) {
            // |x| ~< pi/4
            if (ix < 0x3e46a09eL) return 1.0 // |x| < 2^-27 * sqrt(2)
            return kernelCos(x, 0.0)
        }
        if (ix >= 0x7ff00000L) return x - x

        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return when (n and 3) {
            0 -> kernelCos(y[0], y[1])
            1 -> -kernelSin(y[0], y[1], true)
            2 -> -kernelCos(y[0], y[1])
            else -> kernelSin(y[0], y[1], true)
        }
    }

    // ── atan / atan2 ──────────────────────────────────────────────────────

    private val ATANHI = doubleArrayOf(
        4.63647609000806093515e-01, // atan(0.5)
        7.85398163397448278999e-01, // atan(1.0)
        9.82793723247329054082e-01, // atan(1.5)
        1.57079632679489655800e+00 // atan(inf)
    )
    private val ATANLO = doubleArrayOf(
        2.26987774529616870924e-17,
        3.06161699786838301793e-17,
        1.39033110312309984516e-17,
        6.12323399573676603587e-17
    )
    private val AT = doubleArrayOf(
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
        1.62858201153657823623e-02
    )

    private const val PI_FD = 3.1415926535897931160E+00
    private const val PI_LO = 1.2246467991473531772E-16

    /** Arc tangent, bit-for-bit the fdlibm value. */
    fun atan(x0: Double): Double {
        val b = bits(x0)
        val sign = (b ushr 63) != 0L
        val ix = highWord(x0) and 0x7fffffffL

        if (ix >= 0x44100000L) {
            // |x| >= 2^66
            if (x0.isNaN()) return x0
            return if (sign) -ATANHI[3] else ATANHI[3]
        }

        var x = x0
        val id: Int
        if (ix < 0x3fdc0000L) {
            // |x| < 0.4375
            if (ix < 0x3e400000L) return x // |x| < 2^-27
            id = -1
        } else {
            x = kotlin.math.abs(x)
            if (ix < 0x3ff30000L) {
                if (ix < 0x3fe60000L) {
                    // 7/16 <= |x| < 11/16
                    id = 0
                    x = (2.0 * x - 1.0) / (2.0 + x)
                } else {
                    // 11/16 <= |x| < 19/16
                    id = 1
                    x = (x - 1.0) / (x + 1.0)
                }
            } else if (ix < 0x40038000L) {
                // |x| < 2.4375
                id = 2
                x = (x - 1.5) / (1.0 + 1.5 * x)
            } else {
                // 2.4375 <= |x| < 2^66
                id = 3
                x = -1.0 / x
            }
        }

        val z = x * x
        val w = z * z
        // The degree-11 polynomial is split into odd and even halves so each can
        // be evaluated as a straight Horner chain.
        val s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))))
        val s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))))
        if (id < 0) return x - x * (s1 + s2)
        val z2 = ATANHI[id] - (x * (s1 + s2) - ATANLO[id] - x)
        return if (sign) -z2 else z2
    }

    /** Two-argument arc tangent, bit-for-bit the fdlibm value. */
    fun atan2(y: Double, x: Double): Double {
        if (x.isNaN() || y.isNaN()) return x + y

        val xb = bits(x)
        val yb = bits(y)
        var ix = (xb ushr 32).toInt()
        val lx = xb.toInt()
        var iy = (yb ushr 32).toInt()
        val ly = yb.toInt()

        if (((ix - 0x3ff00000) or lx) == 0) return atan(y) // x == 1.0

        // 2*sign(x) + sign(y)
        val m = ((iy shr 31) and 1) or ((ix shr 30) and 2)
        ix = ix and 0x7fffffff
        iy = iy and 0x7fffffff

        if ((iy or ly) == 0) {
            // y == 0
            return when (m) {
                0, 1 -> y
                2 -> PI_FD
                else -> -PI_FD
            }
        }
        if ((ix or lx) == 0) return if (m and 1 != 0) -PI_FD / 2.0 else PI_FD / 2.0
        if (ix == 0x7ff00000) {
            // x is infinite
            if (iy == 0x7ff00000) {
                return when (m) {
                    0 -> PI_FD / 4.0
                    1 -> -PI_FD / 4.0
                    2 -> 3.0 * PI_FD / 4.0
                    else -> -3.0 * PI_FD / 4.0
                }
            }
            return when (m) {
                0 -> 0.0
                1 -> -0.0
                2 -> PI_FD
                else -> -PI_FD
            }
        }
        // Unsigned compares: these values are bit patterns, not quantities.
        val uix = ix.toLong() and 0xffffffffL
        val uiy = iy.toLong() and 0xffffffffL
        if (uix + (64L shl 20) < uiy || iy == 0x7ff00000) {
            // |y/x| > 2^64
            return if (m and 1 != 0) -PI_FD / 2.0 else PI_FD / 2.0
        }

        val z = if ((m and 2) != 0 && uiy + (64L shl 20) < uix) {
            0.0
        } else {
            atan(kotlin.math.abs(y / x))
        }

        return when (m) {
            0 -> z
            1 -> -z
            2 -> PI_FD - (z - PI_LO)
            else -> (z - PI_LO) - PI_FD
        }
    }

    // ── acos ──────────────────────────────────────────────────────────────

    private const val PIO2_HI = 1.57079632679489655800e+00
    private const val PIO2_LO = 6.12323399573676603587e-17
    private const val PS0 = 1.66666666666666657415e-01
    private const val PS1 = -3.25565818622400915405e-01
    private const val PS2 = 2.01212532134862925881e-01
    private const val PS3 = -4.00555345006794114027e-02
    private const val PS4 = 7.91534994289814532176e-04
    private const val PS5 = 3.47933107596021167570e-05
    private const val QS1 = -2.40339491173441421878e+00
    private const val QS2 = 2.02094576023350569471e+00
    private const val QS3 = -6.88283971605453293030e-01
    private const val QS4 = 7.70381505559019352791e-02

    private fun acosR(z: Double): Double {
        val p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))))
        val q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)))
        return p / q
    }

    /** Arc cosine, bit-for-bit the fdlibm value. */
    fun acos(x: Double): Double {
        val hx = (bits(x) ushr 32).toInt()
        val ix = hx and 0x7fffffff

        if (ix >= 0x3ff00000) {
            // |x| >= 1, or NaN
            val lx = bits(x).toInt()
            if (((ix - 0x3ff00000) or lx) == 0) {
                return if (hx shr 31 != 0) 2.0 * PIO2_HI else 0.0
            }
            return 0.0 / (x - x) // NaN with the invalid flag raised
        }

        if (ix < 0x3fe00000) {
            // |x| < 0.5
            if (ix <= 0x3c600000) return PIO2_HI // |x| < 2^-57
            return PIO2_HI - (x - (PIO2_LO - x * acosR(x * x)))
        }

        if (hx shr 31 != 0) {
            // x < -0.5
            val z = (1.0 + x) * 0.5
            val s = kotlin.math.sqrt(z)
            val w = acosR(z) * s - PIO2_LO
            return 2.0 * (PIO2_HI - (s + w))
        }

        // x > 0.5
        val z = (1.0 - x) * 0.5
        val s = kotlin.math.sqrt(z)
        // `df` keeps only the high half of `s`, which makes `z - df*df` exact and
        // leaves `c` as the correction term.
        val df = fromBits(bits(s) and -0x100000000L)
        val c = (z - df * df) / (s + df)
        val w = acosR(z) * s + c
        return 2.0 * (df + w)
    }

    // ── angle conversions ─────────────────────────────────────────────────

    /**
     * Degrees to radians, as `Math.toRadians` defines it: **divide, then
     * multiply**.
     *
     * The order matters. `deg / 180.0 * PI_FD` and `deg * (PI_FD / 180.0)` disagree by
     * one ulp for 27% of inputs, and Rust's `f64::to_radians` implements the
     * second form. Spelling it out on both sides removes the whole question.
     */
    fun toRadians(deg: Double): Double = deg / 180.0 * PI_FD

    /** Radians to degrees, the mirror of [toRadians]. */
    fun toDegrees(rad: Double): Double = rad * 180.0 / PI_FD
}
