package de.mitauri.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime

/**
 * The watch's wire format, ported from src/mitauri/watch.py. Pure, so it's tested against the same captured frames.
 * The watch has no pairing or encryption: whatever comes back is untrusted, and whatever goes out can be overheard.
 */
object WatchProtocol {
    const val TITLE_MAX = 64
    const val TEXT_MAX = 250
    const val APP_TYPE_MESSAGE = 0x08 // the only type captured so far (WhatsApp)
    private const val CHUNK = 16
    private val BRIGHTNESS_LEVELS = listOf(20, 40, 60, 80, 100)
    private val BIDI_CONTROLS = setOf('‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩')

    enum class Ack { NOT_THIS_FRAME, ACCEPTED, REJECTED }

    val GET_STATUS = byteArrayOf(0x02, 0x01)

    private fun displayText(text: String) = text
        .filter { it !in BIDI_CONTROLS }
        .map { if (Character.getType(it) == Character.CONTROL.toInt()) ' ' else it }
        .joinToString("")

    /** Cuts to at most maxBytes without splitting a character. */
    fun utf8Cut(text: String, maxBytes: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return bytes.copyOf(end)
    }

    fun notification(title: String, text: String, appType: Int = APP_TYPE_MESSAGE): List<ByteArray> {
        val titleBytes = utf8Cut(displayText(title), TITLE_MAX)
        val textBytes = utf8Cut(displayText(text), TEXT_MAX)
        val header = byteArrayOf(appType.toByte(), textBytes.size.toByte(), (textBytes.size shr 8).toByte(), titleBytes.size.toByte())
        val content = header + titleBytes + textBytes
        val chunks = content.toList().chunked(CHUNK).map { it.toByteArray().copyOf(CHUNK) }
        return chunks.mapIndexed { index, chunk ->
            byteArrayOf(0x05, 0x03, chunks.size.toByte(), (index + 1).toByte()) + chunk
        }
    }

    fun setTime(time: LocalDateTime): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03).put(0x01).putShort(time.year.toShort())
        listOf(time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second, time.dayOfWeek.value - 1)
            .forEach { buffer.put(it.toByte()) }
        buffer.putInt(0).put(0x01).put(0x00)
        return buffer.array()
    }

    /** Only levels seen on the wire are sent, so the screen can't be set to black. */
    fun setBrightness(percent: Int): ByteArray {
        val level = BRIGHTNESS_LEVELS.minByOrNull { kotlin.math.abs(it - percent) }!!
        return byteArrayOf(0x03, 0x32, level.toByte(), 0x01, 0x00, 0x02, 0, 0, 0, 0, 0, 0)
    }

    fun setFace(face: Int): ByteArray {
        require(face in 1..4) { "Unknown face" }
        return byteArrayOf(0x03, 0x12, face.toByte())
    }

    /** Status bytes after the 2-byte opcode: the battery is the sixth. Null if it isn't a believable percent. */
    fun battery(response: ByteArray): Int? {
        if (response.size <= 7 || response[0].toInt() != 0x02 || response[1].toInt() != 0x01) return null
        return (response[7].toInt() and 0xFF).takeIf { it <= 100 }
    }

    /** Notification acks are 05 03 <total> <index> <status>. A late ack for an earlier packet doesn't count. */
    fun ackFor(frame: ByteArray, response: ByteArray): Ack {
        if (response.size < 2 || response[0] != frame[0] || response[1] != frame[1]) return Ack.NOT_THIS_FRAME
        if (frame[0].toInt() != 0x05) return Ack.ACCEPTED
        if (response.size < 4 || response[3] != frame[3]) return Ack.NOT_THIS_FRAME
        return if (response.size > 4 && response[4].toInt() != 0) Ack.REJECTED else Ack.ACCEPTED
    }
}

/** Decides what may cross the air unencrypted to the watch, and how often. */
class WatchForwardRules(private val maxPerMinute: Int = 12, private val repeatMillis: Long = 30_000) {
    private val sent = ArrayDeque<Long>()
    private val recent = LinkedHashMap<String, Long>()

    fun mayShow(packageName: String, ownPackage: String, allowed: Set<String>, ongoing: Boolean, groupSummary: Boolean, secret: Boolean): Boolean =
        packageName != ownPackage && packageName in allowed && !NotificationFilter.isProtectedApp(packageName) &&
            !ongoing && !groupSummary && !secret

    /** Apps repost the same notification constantly, and a chatty or hostile one mustn't buzz the wrist nonstop. */
    fun take(key: String, content: String, now: Long): Boolean {
        val id = "$key|${content.hashCode()}"
        recent.entries.removeAll { now - it.value >= repeatMillis }
        if (id in recent) return false
        while (sent.isNotEmpty() && now - sent.first() >= 60_000) sent.removeFirst()
        if (sent.size >= maxPerMinute) return false
        sent.addLast(now)
        recent[id] = now
        return true
    }
}
