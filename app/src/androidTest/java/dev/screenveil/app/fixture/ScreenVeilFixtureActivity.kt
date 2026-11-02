package dev.screenveil.app.fixture

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Separate instrumentation-APK window used as a capturable target outside ScreenVeil's package. */
class ScreenVeilFixtureActivity : Activity() {
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
        content = TextView(this).apply {
            text = "SCREENVEIL TEST FIXTURE\nThis window is deliberately capturable."
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 75, 115))
            gravity = android.view.Gravity.CENTER
            contentDescription = "screenveil-capture-fixture"
        }
        setContentView(content)
    }

    fun emitContentChange(index: Int) {
        runOnUiThread {
            content.text = "SCREENVEIL TEST FIXTURE\ncontent revision $index"
            content.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }
}
