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
}
