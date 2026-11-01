package de.mitauri.app

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The same captured frames are checked in tests/test_watch.py, so phone and laptop stay in agreement. */
    @Test
    fun reproducesTheCapturedNotification() {
        val packets = WatchProtocol.notification("Jana ❤️:", "MITAURI12345").map { it.hex() }
        assertEquals(listOf("05030201080c000c4a616e6120e29da4efb88f3a", "050302024d495441555249313233343500000000"), packets)
    }

    @Test
    fun reproducesTheCapturedCommands() {
        assertEquals("0301ea070b01002d2d06000000000100", WatchProtocol.setTime(LocalDateTime.of(2026, 11, 1, 0, 45, 45)).hex())
        assertEquals("03321401000200000000" + "0000", WatchProtocol.setBrightness(20).hex())
        assertEquals("031203", WatchProtocol.setFace(3).hex())
    }

    @Test
    fun brightnessOnlyUsesLevelsSeenOnTheWire() {
        assertEquals(20, WatchProtocol.setBrightness(0)[2].toInt())
        assertEquals(60, WatchProtocol.setBrightness(55)[2].toInt())
        assertEquals(100, WatchProtocol.setBrightness(9999)[2].toInt())
    }

    @Test
    fun longTextIsBoundedAndEveryFrameFitsOneWrite() {
        val packets = WatchProtocol.notification("🙂".repeat(500), "ä".repeat(5000))
        assertTrue(packets.all { it.size == 20 })
        assertEquals(20, packets.size)
        val content = packets.flatMap { it.drop(4) }.toByteArray()
        val textLength = (content[1].toInt() and 0xFF) or ((content[2].toInt() and 0xFF) shl 8)
        assertTrue(textLength <= WatchProtocol.TEXT_MAX)
        assertEquals(0, textLength % 2) // no half character
        assertEquals(64, content[3].toInt() and 0xFF)
    }

    @Test
    fun controlAndDirectionCharactersDontReachTheDisplay() {
        val content = WatchProtocol.notification("a‮b", "x\ny\u0000")[0].drop(8).toByteArray().toString(Charsets.UTF_8)
        assertTrue(content.startsWith("abx y "))
    }

    @Test
    fun batteryComesFromTheStatusFrameAndIsNotBelievedBlindly() {
        assertEquals(54, WatchProtocol.battery(bytes("0201b31a28010036010001000a")))
        assertNull(WatchProtocol.battery(bytes("0201b31a2801ff36".replace("ff36", "00ff"))))
        assertNull(WatchProtocol.battery(bytes("0201b3")))
        assertNull(WatchProtocol.battery(bytes("0301b31a28010036010001000a")))
    }

    @Test
    fun acksMatchTheirPacketAndCarryAStatus() {
        val second = bytes("050302024d495441555249313233343500000000")
        assertEquals(WatchProtocol.Ack.ACCEPTED, WatchProtocol.ackFor(second, bytes("0503020200")))
        assertEquals(WatchProtocol.Ack.REJECTED, WatchProtocol.ackFor(second, bytes("0503020201")))
        assertEquals(WatchProtocol.Ack.NOT_THIS_FRAME, WatchProtocol.ackFor(second, bytes("0503020100")))
        assertEquals(WatchProtocol.Ack.NOT_THIS_FRAME, WatchProtocol.ackFor(second, bytes("0301")))
        assertEquals(WatchProtocol.Ack.ACCEPTED, WatchProtocol.ackFor(bytes("031203"), bytes("0312")))
    }

    @Test
    fun onlyChosenSafeAppsReachTheWatch() {
        val rules = WatchForwardRules()
        fun may(app: String, ongoing: Boolean = false, summary: Boolean = false, secret: Boolean = false) =
            rules.mayShow(app, "de.mitauri.app", setOf("com.whatsapp", "com.paypal.android.p2pmobile", "de.mitauri.app"), ongoing, summary, secret)
        assertTrue(may("com.whatsapp"))
        assertFalse(may("org.other"))
        assertFalse(may("com.paypal.android.p2pmobile"))
        assertFalse(may("de.mitauri.app"))
        assertFalse(may("com.whatsapp", ongoing = true))
        assertFalse(may("com.whatsapp", summary = true))
        assertFalse(may("com.whatsapp", secret = true))
    }

    @Test
    fun repeatsAndFloodsAreHeldBack() {
        val rules = WatchForwardRules(maxPerMinute = 3)
        assertTrue(rules.take("k", "hi", 0))
        assertFalse(rules.take("k", "hi", 1_000))
        assertTrue(rules.take("k", "hi", 31_000))
        assertTrue(rules.take("k2", "a", 31_001))
        assertFalse(rules.take("k3", "b", 31_002))
        assertTrue(rules.take("k3", "b", 61_000))
    }
}
