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

    @Test fun readsWhatsAppExports() {
        val lines = ChatExport.parse(
            """
            31.10.26, 20:44 - Nachrichten und Anrufe sind Ende-zu-Ende-verschlüsselt.
            31.10.26, 20:44 - Alex: jo bin gleich da
            und bring chips mit
            31.10.26, 20:45 - Max: <Medien ausgelassen>
            [31.10.26, 20:46:12] Max: ok: passt
            10/31/26, 8:47 PM - Alex: lol
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ExportLine("Alex", "jo bin gleich da und bring chips mit"),
                ExportLine("Max", "ok: passt"),
                ExportLine("Alex", "lol"),
            ),
            lines,
        )
        assertEquals(listOf("Alex" to 2, "Max" to 1), ChatExport.senders(lines))
    }

    @Test fun onlyTheUsersOwnLinesCount() {
        assertEquals(true, OwnLines.isOwn(null, "Alex"))
        assertEquals(true, OwnLines.isOwn("Alex", "Alex"))
        assertEquals(false, OwnLines.isOwn("Max", "Alex"))
        assertEquals(false, OwnLines.isOwn("Max", null))
    }

    @Test fun aFlipJustAfterConnectingIsNotADropout() {
        assertEquals(false, wasRealConnection(270)) // what the user's Nothing Ear did
        assertEquals(true, wasRealConnection(60_000))
        assertEquals(true, isDropout(500))
        assertEquals(false, isDropout(3_050)) // back in the case for 3 seconds counts as reconnecting
    }

    @Test fun autoMusicStaysOutOfTheWay() {
        assertEquals(true, autoMusicMayStart(false, false, null, false, false)) // locked, in a pocket
        assertEquals(true, autoMusicMayStart(true, true, "com.android.chrome", false, false))
        assertEquals(false, autoMusicMayStart(true, true, "com.whatsapp", false, false))
        assertEquals(false, autoMusicMayStart(true, false, null, false, false)) // can't rule out WhatsApp
        assertEquals(false, autoMusicMayStart(false, false, null, true, false)) // in a call
        assertEquals(false, autoMusicMayStart(false, false, null, false, true)) // something's already on
    }
}
