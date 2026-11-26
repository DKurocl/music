package ua.olex.music.soulseek

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.tags.ByteArraySource
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DownloadAudioHeaderTest {
    @Test fun byteCompleteNonAudioAndMislabeledFilesCannotBePublishedAsCheckedFormats() {
        val wave = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(44).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(48_000).putInt(192_000).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(8).putLong(0).array()
        assertTrue(DownloadAudioHeader.matches("wav", ByteArraySource(wave)))
        assertFalse(DownloadAudioHeader.matches("flac", ByteArraySource(wave)))
        assertFalse(DownloadAudioHeader.matches("wav", ByteArraySource(ByteArray(wave.size))))
    }

    @Test fun rawAacRequiresACompleteFirstFrameAndSupportsAnId3Prefix() {
        val frame = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 0x01, 0xff.toByte(), 0xfc.toByte()) + ByteArray(8)
        val tags = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)
        assertTrue(DownloadAudioHeader.matches("aac", ByteArraySource(frame)))
        assertTrue(DownloadAudioHeader.matches("aac", ByteArraySource(tags + frame)))
        assertFalse(DownloadAudioHeader.matches("aac", ByteArraySource(frame.copyOf(14))))
    }
}
