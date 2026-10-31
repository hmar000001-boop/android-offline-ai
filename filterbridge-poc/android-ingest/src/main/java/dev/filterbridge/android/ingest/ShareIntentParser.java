package dev.filterbridge.android.ingest;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.provider.OpenableColumns;

import dev.filterbridge.core.ContentSourcePlatform;
import dev.filterbridge.core.ImportOrigin;
import dev.filterbridge.core.InboundContent;
import dev.filterbridge.core.MediaAttachment;
import dev.filterbridge.core.MediaKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts Android Sharesheet intents into a platform-neutral InboundContent object. */
public final class ShareIntentParser {
    private static final int MAX_ATTACHMENTS = 20;
    private static final int MAX_TEXT_CHARS = 100_000;

    private final ContentResolver resolver;

    public ShareIntentParser(Context context) {
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    public InboundContent parse(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("Share intent is missing");
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            throw new IllegalArgumentException("Unsupported share action: " + action);
        }

        StringBuilder text = new StringBuilder();
        CharSequence sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        appendText(text, sharedText);

        Map<String, Uri> uris = new LinkedHashMap<>();
        addStreamExtras(intent, action, uris);
        addClipData(intent.getClipData(), text, uris);

        Uri data = intent.getData();
        if (data != null && "content".equalsIgnoreCase(data.getScheme())) {
            uris.put(data.toString(), data);
        }

        if (uris.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("A maximum of " + MAX_ATTACHMENTS + " attachments can be imported at once.");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("Shared text exceeds the supported size limit.");
        }

        List<MediaAttachment> attachments = new ArrayList<>();
        for (Uri uri : uris.values()) {
            attachments.add(toAttachment(uri, intent.getType()));
        }

        String normalizedText = text.toString().trim();
        return new InboundContent(normalizedText, attachments, ImportOrigin.SHARE_SHEET,
                ContentSourcePlatform.detectFromText(normalizedText));
    }

    private void addStreamExtras(Intent intent, String action, Map<String, Uri> uris) {
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = getSingleStream(intent);
            addContentUri(uri, uris);
            return;
        }

        ArrayList<Uri> multiple = getMultipleStreams(intent);
        if (multiple != null) {
            for (Uri uri : multiple) addContentUri(uri, uris);
        }
        // Some senders place only one URI in EXTRA_STREAM even for SEND_MULTIPLE.
        addContentUri(getSingleStream(intent), uris);
    }

    private Uri getSingleStream(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        @SuppressWarnings("deprecation")
        Parcelable value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return value instanceof Uri ? (Uri) value : null;
    }

    @SuppressWarnings("deprecation")
    private ArrayList<Uri> getMultipleStreams(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        ArrayList<? extends Parcelable> values = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (values == null) return null;
        ArrayList<Uri> result = new ArrayList<>();
        for (Parcelable value : values) if (value instanceof Uri) result.add((Uri) value);
        return result;
    }

    private void addClipData(ClipData clipData, StringBuilder text, Map<String, Uri> uris) {
        if (clipData == null) return;
        for (int i = 0; i < clipData.getItemCount(); i++) {
            ClipData.Item item = clipData.getItemAt(i);
            if (item == null) continue;
            if (text.length() == 0) appendText(text, item.getText());
            addContentUri(item.getUri(), uris);
        }
    }

    private void appendText(StringBuilder target, CharSequence value) {
        if (value == null || value.length() == 0 || target.length() >= MAX_TEXT_CHARS) return;
        if (target.length() > 0) target.append('\n');
        int remaining = MAX_TEXT_CHARS - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
    }

    private void addContentUri(Uri uri, Map<String, Uri> uris) {
        if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
            uris.put(uri.toString(), uri);
        }
    }

    private MediaAttachment toAttachment(Uri uri, String fallbackMimeType) {
        String mime = null;
        try {
            mime = resolver.getType(uri);
        } catch (SecurityException ignored) {
            // Metadata can be unavailable even when the share itself was delivered.
        }
        if (mime == null) mime = fallbackMimeType;
        if (mime == null) mime = "application/octet-stream";

        String displayName = "Shared media";
        long size = -1L;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {
            // Some content providers do not expose display name or size metadata.
        }

        MediaKind kind;
        if (mime.startsWith("image/")) kind = MediaKind.IMAGE;
        else if (mime.startsWith("video/")) kind = MediaKind.VIDEO;
        else if (mime.startsWith("audio/")) kind = MediaKind.AUDIO;
        else kind = MediaKind.OTHER;

        return new MediaAttachment(kind, new AndroidUriMediaSource(resolver, uri, mime, displayName, size));
    }
}
