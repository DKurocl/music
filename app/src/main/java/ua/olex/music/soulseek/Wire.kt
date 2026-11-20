package ua.olex.music.soulseek

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Soulseek's wire format (as Nicotine+ documents it): little-endian integers, strings as a uint32
 * length and the bytes, server and peer messages framed as uint32 length + uint32 code, peer init
 * messages as uint32 length + uint8 code.
 */
internal class Out {
    private val bytes = ByteArrayOutputStream()
    fun u8(v: Int) = apply { bytes.write(v and 0xFF) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun u32(v: Long) = apply { repeat(4) { bytes.write(((v shr (8 * it)) and 0xFF).toInt()) } }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) = apply { repeat(8) { bytes.write(((v shr (8 * it)) and 0xFF).toInt()) } }
    fun str(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); u32(b.size); bytes.write(b) }
    fun raw(b: ByteArray) = apply { bytes.write(b) }
    fun toByteArray(): ByteArray = bytes.toByteArray()

    companion object {
        /** A server or peer message: length (code + body), code, body. */
        fun message(code: Int, body: Out.() -> Unit): ByteArray {
            val content = Out().apply(body).toByteArray()
            return Out().u32(content.size + 4).u32(code).raw(content).toByteArray()
        }

        /** A peer init message: length (code + body), one-byte code, body. */
        fun init(code: Int, body: Out.() -> Unit): ByteArray {
            val content = Out().apply(body).toByteArray()
            return Out().u32(content.size + 1).u8(code).raw(content).toByteArray()
        }
    }
}

internal class In(private val buffer: ByteBuffer) {
    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    val remaining get() = buffer.remaining()
    fun u8(): Int = buffer.get().toInt() and 0xFF
    fun bool(): Boolean = u8() != 0
    fun u32(): Long = buffer.int.toLong() and 0xFFFFFFFFL
    fun u64(): Long = buffer.long
    fun str(): String {
        val n = u32().toInt()
        require(n in 0..buffer.remaining()) { "Bad string length" }
        val b = ByteArray(n); buffer.get(b)
        return String(b, Charsets.UTF_8)
    }
    fun rest(): ByteArray { val b = ByteArray(buffer.remaining()); buffer.get(b); return b }
}

/** Reads one framed message: its code and body. Init messages have a one-byte code. */
internal fun InputStream.readFrame(init: Boolean = false): Pair<Int, ByteArray>? {
    val head = readExactly(4) ?: return null
    val length = In(head).u32().toInt()
    require(length in (if (init) 1 else 4)..(64 shl 20)) { "Bad message length $length" }
    val body = readExactly(length) ?: return null
    return if (init) (body[0].toInt() and 0xFF) to body.copyOfRange(1, body.size)
    else In(body.copyOfRange(0, 4)).u32().toInt() to body.copyOfRange(4, body.size)
}

internal fun InputStream.readExactly(n: Int): ByteArray? {
    val out = ByteArray(n); var at = 0
    while (at < n) { val r = read(out, at, n - at); if (r < 0) return null; at += r }
    return out
}

/** An IPv4 address as Soulseek sends it: a uint32 whose bytes are the address, last octet first. */
internal fun ipv4(value: Long): String = listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xFF).toString() }
