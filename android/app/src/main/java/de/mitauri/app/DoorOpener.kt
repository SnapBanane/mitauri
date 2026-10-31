package de.mitauri.app

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The door login. It never leaves this phone, and its key only works while the phone is unlocked. */
object DoorLogin {
    private const val ALIAS = "mitauri-door"
    private const val PREFERENCES = "door_login"

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isSet(context: Context) = preferences(context).contains("data")

    fun save(context: Context, user: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(JSONObject().put("u", user).put("p", password).toString().toByteArray())
        preferences(context).edit()
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): Pair<String, String>? {
        val saved = preferences(context)
        val data = saved.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(saved.getString("iv", ""), Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))))
            json.getString("u") to json.getString("p")
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUnlockedDeviceRequired(true)
                    .build()
            )
            generateKey()
        }
    }
}

/**
 * Opens the door from a spoken or typed command. Every opening is logged to the user by name, so it
 * never retries a press, ignores repeats, and leaves a few seconds to cancel a misheard command.
 */
object DoorOpener {
    private const val DOOR_URL = "https://door.example.com/"
    private const val CANCEL_MILLIS = 3_000L
    private const val PAGE_DELAY_MILLIS = 500L
    private const val RESULT_DELAY_MILLIS = 1_500L
    private const val TIMEOUT_MILLIS = 25_000L
    private const val REPEAT_BLOCK_MILLIS = 30_000L
    private const val MESSAGE_MILLIS = 4_000L
    private const val MAX_LOGIN_ATTEMPTS = 2
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var webView: WebView? = null
    private var lastCommand = 0L

    /** Called when the user tapped to stop it. Returns whether there was anything to cancel. */
    fun cancel(): Boolean {
        val waiting = pending ?: return false
        main.removeCallbacks(waiting)
        pending = null
        Mitauri.addVoiceReply("Tür nicht geöffnet.", false)
        return true
    }

    fun command(context: Context) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (pending != null || webView != null || (lastCommand != 0L && now - lastCommand < REPEAT_BLOCK_MILLIS)) return
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            finish(app, "Locked phone: door not opened")
            return
        }
        val login = DoorLogin.load(app)
        if (login == null) {
            // Without a login here, the door app and its fingerprint check still work.
            Door.open(app)
            Island.hide()
            return
        }
        lastCommand = now
        Island.show(app, IslandMode.MESSAGE, "Opening door, tap to cancel")
        val start = Runnable {
            pending = null
            press(app, login.first, login.second)
        }
        pending = start
        main.postDelayed(start, CANCEL_MILLIS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun press(app: Context, user: String, password: String) {
        Island.update(IslandMode.MESSAGE, "Opening door")
        var pressed = false
        var loginAttempts = 0
        var basicAuthTried = false
        val view = WebView(app)
        webView = view
        val giveUp = Runnable { end(app, view, "Door page didn't respond. Door not confirmed.") }
        main.postDelayed(giveUp, TIMEOUT_MILLIS)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.layout(0, 0, 1080, 1920)

        fun step() {
            if (pressed || webView !== view) return
            if (loginAttempts >= MAX_LOGIN_ATTEMPTS) {
                main.removeCallbacks(giveUp)
                end(app, view, "Door login failed. Check it in Settings.")
                return
            }
            view.evaluateJavascript("($FILL_FN)(${JSONObject.quote(user)}, ${JSONObject.quote(password)})") { raw ->
                when (raw?.trim('"')) {
                    "open" -> {
                        pressed = true
                        main.removeCallbacks(giveUp)
                        main.postDelayed({ end(app, view, "Door button pressed") }, RESULT_DELAY_MILLIS)
                    }
                    "login" -> loginAttempts++
                    else -> {
                        main.removeCallbacks(giveUp)
                        end(app, view, "No door button found. Door not opened.")
                    }
                }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = !isTrusted(request?.url)

            override fun onPageFinished(page: WebView?, url: String?) {
                if (isTrusted(url?.let(Uri::parse))) main.postDelayed({ step() }, PAGE_DELAY_MILLIS)
            }

            override fun onReceivedHttpAuthRequest(page: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                if (handler != null && !basicAuthTried && isTrustedHost(host)) {
                    basicAuthTried = true
                    handler.proceed(user, password)
                } else {
                    handler?.cancel()
                }
            }
        }
        view.loadUrl(DOOR_URL)
    }

    private fun end(app: Context, view: WebView, result: String) {
        if (webView !== view) return
        webView = null
        view.stopLoading()
        view.destroy()
        CookieManager.getInstance().removeAllCookies(null)
        finish(app, result)
    }

    private fun finish(app: Context, result: String) {
        Mitauri.addVoiceReply(result, false)
        Island.show(app, IslandMode.MESSAGE, result)
        main.postDelayed({ Island.hide() }, MESSAGE_MILLIS)
    }

    private fun isTrusted(uri: Uri?) = uri != null && uri.scheme == "https" && isTrustedHost(uri.host)

    private fun isTrustedHost(host: String?) = host != null && (host == "door.example.com" || host.endsWith(".door.example.com"))

    private val FILL_FN = """
            function (u, p) {
              function setVal(el, v) {
                var setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
                setter.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
              }
              function label(el) { return ((el.innerText || el.value || '') + '').toLowerCase(); }
              function isOpen(el) {
                var l = label(el);
                return l.indexOf('öffnen') >= 0 || l.indexOf('oeffnen') >= 0 || l.indexOf('open') >= 0;
              }
              function findOpen(scope) {
                var els = scope.querySelectorAll('button, input[type=submit], input[type=button], a');
                for (var i = 0; i < els.length; i++) { if (isOpen(els[i])) return els[i]; }
                return null;
              }
              var pw = document.querySelector('input[type=password]');
              if (pw) {
                var scope = pw.form || document;
                var inputs = scope.querySelectorAll('input');
                for (var i = 0; i < inputs.length; i++) {
                  var t = (inputs[i].getAttribute('type') || 'text').toLowerCase();
                  if ((t === 'text' || t === 'email') && !inputs[i].disabled) { setVal(inputs[i], u); break; }
                }
                setVal(pw, p);
                var btn = findOpen(scope) || scope.querySelector('button[type=submit], input[type=submit]');
                if (btn) { btn.click(); return isOpen(btn) ? 'open' : 'login'; }
                if (pw.form) {
                  if (pw.form.requestSubmit) { pw.form.requestSubmit(); } else { pw.form.submit(); }
                  return 'login';
                }
                return 'none';
              }
              var b = findOpen(document);
              if (b) { b.click(); return 'open'; }
              return 'none';
            }
        """.trimIndent()
}
