package app.lumen.sports

/**
 * A minimal QR Code encoder for the phone-pairing link: byte mode, error correction level M (15 %),
 * versions 1-10 (up to 213 bytes), the mask with the lowest standard penalty. Written after Project
 * Nayuki's QR Code generator (MIT License, https://www.nayuki.io/page/qr-code-generator-library),
 * reduced to what one short URL needs. Lumen draws the modules; scripts/sports.sh prints them.
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {
    /** Dark module at column [x], row [y]. */
    operator fun get(x: Int, y: Int) = modules[y][x]

    /** Rows of '1' (dark) and '0', for passing across processes. */
    fun rows(): String = modules.joinToString("\n") { row -> row.joinToString("") { if (it) "1" else "0" } }

    companion object {
        private const val MAX_VERSION = 10
        /** Error correction codewords per block and number of blocks at level M, versions 1-10. */
        private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val FORMAT_M = 0 // Level M's format bits.

        fun encode(text: String): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { v -> 4 + countBits(v) + 8 * data.size <= 8 * dataCodewords(v) }
                ?: throw IllegalArgumentException("too long for a version $MAX_VERSION QR code: ${data.size} bytes")
            val bits = BitBuffer()
            bits.append(0b0100, 4) // Byte mode.
            bits.append(data.size, countBits(version))
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }
            val capacity = 8 * dataCodewords(version)
            bits.append(0, minOf(4, capacity - bits.size)) // Terminator.
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return Builder(version).build(withEcc(bits.bytes(), version))
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int) = rawModules(version) / 8 - ECC_PER_BLOCK[version - 1] * BLOCKS[version - 1]

        /** Splits [data] into blocks, appends each block's Reed-Solomon codewords and interleaves them. */
        private fun withEcc(data: ByteArray, version: Int): ByteArray {
            val numBlocks = BLOCKS[version - 1]
            val eccLen = ECC_PER_BLOCK[version - 1]
            val raw = rawModules(version) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val dat = data.copyOfRange(k, k + shortLen - eccLen + if (i < numShort) 0 else 1)
                k += dat.size
                val ecc = rsRemainder(dat, divisor)
                // Short blocks get a placeholder byte so all blocks have the same length; it is skipped below.
                blocks += (if (i < numShort) dat + 0 else dat) + ecc
            }
            val result = ByteArray(raw)
            var n = 0
            for (i in blocks[0].indices) for (j in blocks.indices) {
                if (i != shortLen - eccLen || j >= numShort) result[n++] = blocks[j][i]
            }
            return result
        }

        private fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        internal fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun append(value: Int, length: Int) { for (i in length - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
        fun bytes() = ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 }.toByte() }
    }

    private class Builder(private val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun build(codewords: ByteArray): QrCode {
            drawFunctionPatterns()
            drawCodewords(codewords)
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormatBits(mask)
                val p = penalty()
                if (p < bestPenalty) { best = mask; bestPenalty = p }
                applyMask(mask) // XOR again undoes it.
            }
            applyMask(best)
            drawFormatBits(best)
            return QrCode(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            function[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val align = alignmentPositions()
            val last = align.size - 1
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormatBits(0) // Reserves the format areas; redrawn once the mask is chosen.
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val bit = (bits ushr i) and 1 == 1
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) {
                    val dist = maxOf(Math.abs(dx), Math.abs(dy))
                    set(xx, yy, dist != 2 && dist != 4)
                }
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val step = (version * 8 + num * 3 + 5) / (num * 4 - 4) * 2
            val result = IntArray(num)
            result[0] = 6
            var pos = size - 7
            for (i in num - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun drawFormatBits(mask: Int) {
            val data = (FORMAT_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // The dark module.
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        private fun penalty(): Int {
            var result = 0
            for (pass in 0..1) for (a in 0 until size) {
                fun at(b: Int) = if (pass == 0) modules[a][b] else modules[b][a]
                var runColor = false
                var run = 0
                val history = IntArray(7)
                for (b in 0 until size) {
                    if (at(b) == runColor) {
                        run++
                        if (run == 5) result += 3 else if (run > 5) result++
                    } else {
                        addHistory(run, history)
                        if (!runColor) result += countFinderLike(history) * 40
                        runColor = at(b)
                        run = 1
                    }
                }
                if (runColor) { addHistory(run, history); run = 0 }
                addHistory(run + size, history)
                result += countFinderLike(history) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            result += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
            return result
        }

        private fun addHistory(length: Int, history: IntArray) {
            val run = if (history[0] == 0) length + size else length // The light border before the first run.
            System.arraycopy(history, 0, history, 1, history.size - 1)
            history[0] = run
        }

        private fun countFinderLike(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }
    }
}
