package de.mitauri.app

/** Decides what must never reach Mitauri, however the allow list is set. */
object NotificationFilter {
    private const val MAX_TEXT_CHARS = 500

    private val PROTECTED_WORDS = listOf(
        "bank", "sparkasse", "volksbank", "sfinanz", "starfinanz", "paypal", "klarna", "n26", "revolut",
        "comdirect", "ing.diba", "trade", "broker", "wallet", "finanz", "crypto", "coinbase",
        "authenticator", "authy", "2fas", "aegis", "otp", "password", "bitwarden", "keepass", "lastpass",
        "onepassword", "1password", "proton.android.pass", "dashlane", "ausweis", "elster",
    )
    private val SECURITY_WORDS = Regex(
        """(?i)\b(code|tan|otp|pin|passwort|kennwort|password|verifizierung\w*|bestätigungs\w*|verification|login|2fa|sicherheits\w*)\b"""
    )
    private val DIGITS = Regex("""\d[\d\s-]{2,}\d""")

    fun isProtectedApp(packageName: String): Boolean {
        val name = packageName.lowercase()
        return PROTECTED_WORDS.any { it in name }
    }

    /** Hides anything that looks like a one-time code, and limits the length. */
    fun clean(text: String): String {
        if (SECURITY_WORDS.containsMatchIn(text) && DIGITS.containsMatchIn(text)) {
            return "(hidden: this looks like a security code)"
        }
        return text.take(MAX_TEXT_CHARS)
    }
}
