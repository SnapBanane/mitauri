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
}
