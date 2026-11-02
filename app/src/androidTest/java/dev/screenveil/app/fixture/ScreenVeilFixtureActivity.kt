package dev.screenveil.app.fixture

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Separate instrumentation-APK window used as a capturable target outside ScreenVeil's package. */
class ScreenVeilFixtureActivity : Activity() {
    companion object {
        const val STATE_PREFERENCES = "screenveil_fixture_runtime_state"
        const val KEY_ACTIVITY_CREATED = "activity_created"
        const val KEY_WINDOW_FOCUSED = "window_focused"
        const val KEY_SECURE_FLAG_APPLIED = "secure_flag_applied"
        const val KEY_WINDOW_FLAGS = "window_flags"
    }

    lateinit var content: TextView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("secure", false)) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val secureFlagApplied = window.attributes.flags and
            android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
        content = TextView(this).apply {
            text = if (secureFlagApplied) {
                "SCREENVEIL_SECURE_FLAG_CONFIRMED\nAndroid must refuse this window screenshot."
            } else {
                "SCREENVEIL TEST FIXTURE\nThis window is deliberately capturable."
            }
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 75, 115))
            gravity = android.view.Gravity.CENTER
            contentDescription = if (secureFlagApplied) {
                "screenveil-secure-flag-confirmed"
            } else {
                "screenveil-capture-fixture"
            }
        }
        setContentView(content)
        publishRuntimeWindowState(windowFocused = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        publishRuntimeWindowState(windowFocused = hasFocus)
    }

    private fun publishRuntimeWindowState(windowFocused: Boolean) {
        val flags = window.attributes.flags
        val secureFlagApplied = flags and
            android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
        getSharedPreferences(STATE_PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVITY_CREATED, true)
            .putBoolean(KEY_WINDOW_FOCUSED, windowFocused)
            .putBoolean(KEY_SECURE_FLAG_APPLIED, secureFlagApplied)
            .putInt(KEY_WINDOW_FLAGS, flags)
            .commit()
    }

    fun emitContentChange(index: Int) {
        runOnUiThread {
            content.text = "SCREENVEIL TEST FIXTURE\ncontent revision $index"
            content.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }
}
