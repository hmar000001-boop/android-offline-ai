package dev.filterbridge.android.ingest;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import dev.filterbridge.core.MediaAttachment;
import dev.filterbridge.core.MediaKind;

import java.util.Objects;

/**
 * Validates and describes content selected through Android's Photo Picker.
 * It never copies media bytes and never requests broad library permissions.
 */
public final class MediaPickerRepository {
    public static final long MAX_IMAGE_BYTES = 150L * 1024L * 1024L;
    public static final long MAX_VIDEO_BYTES = 1024L * 1024L * 1024L;

    private final ContentResolver resolver;

    public MediaPickerRepository(Context context) {
        this.resolver = Objects.requireNonNull(context, "context")
                .getApplicationContext().getContentResolver();
    }

    public PickedMedia inspect(Uri uri, MediaKind expectedKind) {
        if (uri == null || expectedKind == null) {
            throw new IllegalArgumentException("A selected media URI and media type are required.");
        }
        if (!"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only Android content URIs are supported.");
        }
        if (expectedKind != MediaKind.IMAGE && expectedKind != MediaKind.VIDEO) {
            throw new IllegalArgumentException("Only photos and videos can be selected here.");
        }

        String mimeType = null;
        try {
            mimeType = resolver.getType(uri);
        } catch (SecurityException ignored) {
            // The picker type filter is the fallback when a provider hides MIME metadata.
        }
        if (mimeType == null || mimeType.trim().isEmpty()
                || "application/octet-stream".equalsIgnoreCase(mimeType.trim())) {
            // Some cloud/document providers do not publish a useful MIME type.
            // The system picker already constrained the selection; the decoder/retriever
            // still has to open and validate the media before it can be previewed.
            mimeType = expectedKind == MediaKind.IMAGE ? "image/*" : "video/*";
        }
        boolean actualImage = mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
        boolean actualVideo = mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("video/");
        if ((expectedKind == MediaKind.IMAGE && !actualImage)
                || (expectedKind == MediaKind.VIDEO && !actualVideo)) {
            throw new IllegalArgumentException("The selected file type does not match the requested media type.");
        }

        String displayName = "Selected media";
        long sizeBytes = -1L;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    String candidate = cursor.getString(nameIndex);
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        displayName = sanitizeDisplayName(candidate);
                    }
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex);
            }
        } catch (SecurityException ignored) {
            // Some document providers expose a URI but restrict metadata queries.
        } catch (RuntimeException ignored) {
            // Metadata is optional; opening the stream below is the definitive access test.
        }

        long maximum = expectedKind == MediaKind.IMAGE ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
        if (sizeBytes > maximum) {
            throw new IllegalArgumentException(expectedKind == MediaKind.IMAGE
                    ? "The selected image exceeds the 150 MB import limit."
                    : "The selected video exceeds the 1 GB import limit.");
        }

        AndroidUriMediaSource source = new AndroidUriMediaSource(
                resolver, uri, mimeType, displayName, sizeBytes);
        // Verify that the URI is readable now; the stream is closed without copying the file.
        try (java.io.InputStream ignored = source.openStream()) {
            // Access check only.
        } catch (Exception exception) {
            throw new IllegalArgumentException("Android could not open the selected media. Please choose it again.", exception);
        }

        return new PickedMedia(uri, expectedKind, mimeType, displayName, sizeBytes,
                new MediaAttachment(expectedKind, source));
    }

    private static String sanitizeDisplayName(String rawName) {
        StringBuilder safe = new StringBuilder(Math.min(rawName.length(), 160));
        for (int i = 0; i < rawName.length() && safe.length() < 160; i++) {
            char ch = rawName.charAt(i);
            if (!Character.isISOControl(ch) && ch != '/' && ch != '\\') safe.append(ch);
        }
        String result = safe.toString().trim();
        return result.isEmpty() ? "Selected media" : result;
    }

    /** Returns true when a persistable read permission is held or can be retained. */
    public boolean tryTakePersistableReadPermission(Uri uri) {
        if (uri == null) return false;
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isReadPermission()) return true;
        }
        try {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    public void releasePersistableReadPermission(Uri uri) {
        if (uri == null) return;
        try {
            resolver.releasePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // The picker provider may have issued a transient rather than persistable grant.
        }
    }
}
