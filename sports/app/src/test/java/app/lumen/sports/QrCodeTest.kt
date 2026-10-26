package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrCodeTest {
    @Test fun `GF(256) multiplication matches known products`() {
        assertEquals(0, QrCode.gfMul(0, 0x53))
        assertEquals(0x53, QrCode.gfMul(0x53, 1))
        assertEquals(0x1D, QrCode.gfMul(0x80, 2)) // x^8 reduces to x^4 + x^3 + x^2 + 1.
        assertEquals(QrCode.gfMul(0x57, 0x83), QrCode.gfMul(0x83, 0x57))
    }

    @Test fun `the smallest version that fits is chosen`() {
        assertEquals(21, QrCode.encode("hi").size) // Version 1.
        // A pairing link: http://<ip>:8737/#k=<43 base64url chars> is 69-71 bytes, version 5 at level M.
        assertEquals(37, QrCode.encode("http://10.0.0.213:8737/#k=" + "A".repeat(43)).size)
    }

    @Test fun `finder patterns sit in three corners`() {
        val qr = QrCode.encode("http://192.168.100.200:8737/#k=" + "x".repeat(43))
        for ((cx, cy) in listOf(3 to 3, qr.size - 4 to 3, 3 to qr.size - 4)) {
            assertTrue(qr[cx, cy])               // Centre.
            assertTrue(!qr[cx + 2, cy])          // Light ring.
            assertTrue(qr[cx + 3, cy + 3])       // Outer ring.
        }
    }

    /**
     * Writes codes for decoding outside the JVM (OpenCV: see the commit's verification note), one
     * per version 1-10, as PGM images with a 4-module quiet zone, next to their expected text.
     */
    @Test fun `write samples for decoding`() {
        val dir = File("build/qr-samples").apply { deleteRecursively(); mkdirs() }
        val lengths = listOf(5, 20, 40, 60, 75, 100, 120, 150, 170, 185)
        for ((i, n) in lengths.withIndex()) {
            val text = "http://10.0.0.213:8737/#k=" + (0 until n).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[(it * 7 + i) % 64] }.joinToString("")
            val qr = QrCode.encode(text)
            val scale = 8
            val side = (qr.size + 8) * scale
            val pixels = StringBuilder("P2\n$side $side\n255\n")
            for (py in 0 until side) {
                for (px in 0 until side) {
                    val x = px / scale - 4
                    val y = py / scale - 4
                    val dark = x in 0 until qr.size && y in 0 until qr.size && qr[x, y]
                    pixels.append(if (dark) "0 " else "255 ")
                }
                pixels.append('\n')
            }
            File(dir, "qr$i.pgm").writeText(pixels.toString())
            File(dir, "qr$i.txt").writeText(text)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too long is refused`() {
        QrCode.encode("x".repeat(300))
    }
}
