package dev.filterbridge.android.ingest;

import android.net.Uri;

import dev.filterbridge.core.MediaAttachment;
import dev.filterbridge.core.MediaKind;

import java.util.Objects;

/** Immutable description of one user-selected media item. */
public final class PickedMedia {
    private final Uri uri;
    private final MediaKind kind;
    private final String mimeType;
    private final String displayName;
    private final long sizeBytes;
    private final MediaAttachment attachment;

    PickedMedia(Uri uri, MediaKind kind, String mimeType, String displayName,
                long sizeBytes, MediaAttachment attachment) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.sizeBytes = sizeBytes;
        this.attachment = Objects.requireNonNull(attachment, "attachment");
    }

    public Uri uri() { return uri; }
    public MediaKind kind() { return kind; }
    public String mimeType() { return mimeType; }
    public String displayName() { return displayName; }
    public long sizeBytes() { return sizeBytes; }
    public MediaAttachment attachment() { return attachment; }
}
