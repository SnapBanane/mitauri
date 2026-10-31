package de.mitauri.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    @Test
    fun protectsMoneyAndPasswordApps() {
        listOf("com.starfinanz.smob.android.sfinanzstatus", "de.fiducia.smartphone.android.banking.vr", "com.paypal.android.p2pmobile",
            "proton.android.pass", "com.google.android.apps.authenticator2", "com.x8bit.bitwarden").forEach {
            assertTrue(it, NotificationFilter.isProtectedApp(it))
        }
        listOf("com.whatsapp", "org.thoughtcrime.securesms", "ch.protonmail.android").forEach {
            assertFalse(it, NotificationFilter.isProtectedApp(it))
        }
    }

    @Test
    fun hidesSecurityCodesButNotNormalMessages() {
        assertTrue(NotificationFilter.clean("Dein Code ist 482913").startsWith("(hidden"))
        assertTrue(NotificationFilter.clean("Your verification code: 12-34-56").startsWith("(hidden"))
        assertEquals("Treffen um 1530 am Bahnhof", NotificationFilter.clean("Treffen um 1530 am Bahnhof"))
        assertEquals("Der Code ist schön geworden", NotificationFilter.clean("Der Code ist schön geworden"))
    }

    @Test
    fun limitsLength() {
        assertEquals(500, NotificationFilter.clean("x".repeat(900)).length)
    }
}
