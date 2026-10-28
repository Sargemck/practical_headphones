package io.github.sargemck.practicalheadphones

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * M0 spike screen, debug builds only. Keeps the screen on and shows over the lock screen
 * so hands-off runs work on a locked phone. Commands come in as intent extras.
 */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val padding = (8 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
        }
        val scroll = ScrollView(this).apply {
            addView(text)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left + padding, bars.top, bars.right + padding, bars.bottom)
                insets
            }
        }
        setContentView(scroll)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SpikeLog.lines.collect { lines ->
                    text.text = lines.joinToString("\n")
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val extras = intent.extras?.takeIf { it.containsKey("cmd") } ?: return
        // Silent audio starts from here so it's always started while this activity is in front.
        if (extras.getString("cmd") == "audio") {
            val service = Intent(this, SilentAudioService::class.java)
            if (extras.getInt("on", 1) == 1) {
                SpikeLog.log("CMD audio on")
                startForegroundService(service)
            } else {
                SpikeLog.log("CMD audio off")
                stopService(service)
            }
            return
        }
        SpikeSession.handle(this, extras)
    }
}
