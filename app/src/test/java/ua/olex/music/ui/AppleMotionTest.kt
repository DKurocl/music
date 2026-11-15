package ua.olex.music.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class AppleMotionTest {
    @Test fun signsAnEs256DeveloperTokenThatVerifies() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val p8 = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----"
        val token = AppleMotion.sign(AppleMotion.Key("TEAM123456", "KEY1234567", p8), 1_700_000_000)
        val (header, payload, signature) = token.split('.')
        val decode = { s: String -> String(Base64.getUrlDecoder().decode(s)) }
        assertEquals("""{"alg":"ES256","kid":"KEY1234567"}""", decode(header))
        assertTrue(decode(payload).contains(""""iss":"TEAM123456""""))
        // Back from JOSE r||s to DER, and the public key accepts it.
        val raw = Base64.getUrlDecoder().decode(signature)
        assertEquals(64, raw.size)
        fun int(b: ByteArray): ByteArray { val v = b.dropWhile { it.toInt() == 0 }.toByteArray(); return if (v[0] < 0) byteArrayOf(0) + v else v }
        val r = int(raw.copyOfRange(0, 32)); val s = int(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, (r.size + s.size + 4).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update("$header.$payload".toByteArray()); verify(der) }
        assertTrue(ok)
    }

    @Test fun picksTheMatchingAlbumsSquareVideo() {
        val json = """{"results":{"albums":{"data":[
          {"attributes":{"name":"Currents (Deluxe)","artistName":"Tame Impala","editorialVideo":{"motionDetailTall":{"video":"https://x/tall.m3u8"}}}},
          {"attributes":{"name":"Currents","artistName":"Tame Impala","editorialVideo":{"motionDetailSquare":{"video":"https://x/square.m3u8"}}}},
          {"attributes":{"name":"Currents","artistName":"Someone Else","editorialVideo":{"motionDetailSquare":{"video":"https://x/wrong.m3u8"}}}}]}}}"""
        assertEquals("https://x/square.m3u8", AppleMotion.pick(json, "Currents", "Tame Impala"))
        assertNull(AppleMotion.pick(json, "Innerspeaker", "Tame Impala"))
    }

    @Test fun joinsTheBestH264VariantIntoOneFile() {
        val files = mapOf(
            "https://v/master.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=2048x2048,CODECS=\"avc1.64\"\nbig.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1080x1080,CODECS=\"avc1.64\"\nmid.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1080x1080,CODECS=\"hvc1\"\nhevc.m3u8\n",
            "https://v/mid.m3u8" to "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\nseg1.m4s\n#EXTINF:2,\nseg2.m4s\n#EXT-X-ENDLIST\n",
        )
        val bytes = mapOf("https://v/init.mp4" to ByteArray(600) { 1 }, "https://v/seg1.m4s" to ByteArray(500) { 2 }, "https://v/seg2.m4s" to ByteArray(400) { 3 })
        val joined = AppleMotion.joinHls("https://v/master.m3u8") { url -> files[url]?.toByteArray() ?: bytes[url] }
        assertArrayEquals(ByteArray(600) { 1 } + ByteArray(500) { 2 } + ByteArray(400) { 3 }, joined)
    }

    @Test fun skipsEncryptedStreams() {
        val files = mapOf("https://v/a.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:2,\nseg.m4s\n")
        assertNull(AppleMotion.joinHls("https://v/a.m3u8") { files[it]?.toByteArray() ?: ByteArray(5_000) })
    }
}
