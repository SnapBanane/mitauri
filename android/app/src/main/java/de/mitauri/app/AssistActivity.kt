package de.mitauri.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/**
 * The invisible screen behind the power button. It stays open only while listening, because
 * Android lends the microphone to visible screens. On a locked phone it asks you to unlock first,
 * so nobody can reach your calendar or location without your screen lock.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(this)
        Mitauri.init(this)
        if (!ready || !Mitauri.isPaired) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            start()
            return
        }
        setShowWhenLocked(true)
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = start()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            },
        )
    }

    private fun start() {
        MitauriService.start(this)
        VoiceMode.listen(this) { finish() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            VoiceMode.stop()
            finish()
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        if (VoiceMode.isListening) VoiceMode.stop()
        if (!isFinishing) finish()
    }
}
