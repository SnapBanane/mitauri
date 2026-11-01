package de.mitauri.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceTokenTest {
    /** The same values are checked on the server, so phone and server stay in agreement. */
    @Test
    fun matchesTheServer() {
        val key = ByteArray(32) { it.toByte() }
        val hex = PresenceToken.of(key, 5_972_000L).joinToString("") { "%02x".format(it) }
        assertEquals("bdea298293568dda", hex)
    }

    @Test
    fun recognisesOnlySensorBeacons() {
        val beacon = byteArrayOf('M'.code.toByte(), 'S'.code.toByte()) + ByteArray(12)
        assertEquals(true, SensorBeacon.isOurs(beacon))
        assertEquals(false, SensorBeacon.isOurs(beacon.copyOf(13)))
        assertEquals(false, SensorBeacon.isOurs(ByteArray(14)))
        assertEquals(false, SensorBeacon.isOurs(null))
    }
}
