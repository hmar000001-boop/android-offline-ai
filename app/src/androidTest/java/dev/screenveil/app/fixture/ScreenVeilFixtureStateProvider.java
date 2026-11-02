package dev.screenveil.app.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Explicit IPC endpoint for the test-only fixture Activity's runtime window report.
 *
 * <p>This class is deliberately written in Java. The fixture components are declared by the
 * instrumentation APK and therefore start in the {@code dev.screenveil.app.test} process, which
 * loads only the instrumentation APK. Android Gradle Plugin leaves libraries that the target app
 * already packages (including the Kotlin standard library) out of that APK, so Kotlin code here
 * fails with {@code NoClassDefFoundError: kotlin.jvm.internal.Intrinsics}. Java code needs no
 * Kotlin runtime. Do not convert this file back to Kotlin.
 */
public class ScreenVeilFixtureStateProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.screenveil.app.test.screenveil-fixture-state";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_RESET = "reset_fixture_state";
    public static final String METHOD_PUBLISH = "publish_fixture_state";
    public static final String METHOD_READ = "read_fixture_state";
    public static final String KEY_ACCEPTED = "accepted";

    private static final Object LOCK = new Object();
    private static Bundle snapshot = new Bundle();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_RESET.equals(method)) {
            synchronized (LOCK) {
                snapshot = new Bundle();
            }
            return accepted();
        }
        if (METHOD_PUBLISH.equals(method)) {
            synchronized (LOCK) {
                snapshot = extras == null ? new Bundle() : new Bundle(extras);
            }
            return accepted();
        }
        if (METHOD_READ.equals(method)) {
            synchronized (LOCK) {
                return new Bundle(snapshot);
            }
        }
        return super.call(method, arg, extras);
    }

    private static Bundle accepted() {
        Bundle result = new Bundle();
        result.putBoolean(KEY_ACCEPTED, true);
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
