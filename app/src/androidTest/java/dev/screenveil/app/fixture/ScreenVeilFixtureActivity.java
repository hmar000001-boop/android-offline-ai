package dev.screenveil.app.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

/**
 * Separate instrumentation-APK window used as a capturable target outside ScreenVeil's package.
 *
 * <p>Written in Java on purpose: this Activity runs in the {@code dev.screenveil.app.test}
 * process, where the Kotlin standard library is not on the class path. See
 * {@link ScreenVeilFixtureStateProvider}. Do not convert this file back to Kotlin.
 */
public class ScreenVeilFixtureActivity extends Activity {
    public static final String KEY_ACTIVITY_CREATED = "activity_created";
    public static final String KEY_WINDOW_FOCUSED = "window_focused";
    public static final String KEY_SECURE_FLAG_APPLIED = "secure_flag_applied";
    public static final String KEY_WINDOW_FLAGS = "window_flags";

    private TextView content;

    public TextView getContent() {
        return content;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("secure", false)) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        boolean secureFlagApplied =
                (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;

        TextView view = new TextView(this);
        view.setText(secureFlagApplied
                ? "SCREENVEIL_SECURE_FLAG_CONFIRMED\nAndroid must refuse this window screenshot."
                : "SCREENVEIL TEST FIXTURE\nThis window is deliberately capturable.");
        view.setTextSize(22f);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.rgb(35, 75, 115));
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(secureFlagApplied
                ? "screenveil-secure-flag-confirmed"
                : "screenveil-capture-fixture");
        content = view;
        setContentView(view);
        publishRuntimeWindowState(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        publishRuntimeWindowState(hasFocus);
    }

    private void publishRuntimeWindowState(boolean windowFocused) {
        int flags = getWindow().getAttributes().flags;
        boolean secureFlagApplied = (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        // The fixture runs in the instrumentation APK's process while the test runner executes
        // against the target APK; publish the report through an explicit ContentProvider call.
        Bundle report = new Bundle();
        report.putBoolean(KEY_ACTIVITY_CREATED, true);
        report.putBoolean(KEY_WINDOW_FOCUSED, windowFocused);
        report.putBoolean(KEY_SECURE_FLAG_APPLIED, secureFlagApplied);
        report.putInt(KEY_WINDOW_FLAGS, flags);
        getContentResolver().call(
                ScreenVeilFixtureStateProvider.CONTENT_URI,
                ScreenVeilFixtureStateProvider.METHOD_PUBLISH,
                null,
                report);
    }

    public void emitContentChange(final int index) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                content.setText("SCREENVEIL TEST FIXTURE\ncontent revision " + index);
                content.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            }
        });
    }
}
