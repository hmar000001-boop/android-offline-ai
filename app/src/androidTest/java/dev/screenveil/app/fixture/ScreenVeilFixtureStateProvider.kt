package dev.screenveil.app.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Explicit IPC endpoint for the test-only fixture Activity's runtime window report.
 * It lives in the instrumentation APK process with the fixture Activity. Instrumentation
 * tests retrieve the report using ContentResolver.call(), avoiding SharedPreferences' lack
 * of multi-process consistency guarantees.
 */
class ScreenVeilFixtureStateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_RESET -> {
            synchronized(lock) { snapshot = Bundle() }
            Bundle().apply { putBoolean(KEY_ACCEPTED, true) }
        }
        METHOD_PUBLISH -> {
            synchronized(lock) { snapshot = Bundle(extras ?: Bundle()) }
            Bundle().apply { putBoolean(KEY_ACCEPTED, true) }
        }
        METHOD_READ -> synchronized(lock) { Bundle(snapshot) }
        else -> super.call(method, arg, extras)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "dev.screenveil.app.test.screenveil-fixture-state"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_RESET = "reset_fixture_state"
        const val METHOD_PUBLISH = "publish_fixture_state"
        const val METHOD_READ = "read_fixture_state"
        const val KEY_ACCEPTED = "accepted"

        private val lock = Any()
        private var snapshot = Bundle()
    }
}
