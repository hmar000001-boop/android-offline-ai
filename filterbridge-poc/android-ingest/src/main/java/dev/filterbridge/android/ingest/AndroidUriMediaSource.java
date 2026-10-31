package dev.filterbridge.android.ingest;

import android.content.ContentResolver;
import android.net.Uri;

import dev.filterbridge.core.MediaSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Lazy bridge from an Android content:// Uri to the platform-neutral MediaSource contract.
 * The source app grants read access to the receiving activity; consume this while that grant
 * remains valid. This class deliberately does not copy media or retain it on disk.
 */
public final class AndroidUriMediaSource implements MediaSource {
    private final ContentResolver resolver;
    private final Uri uri;
    private final String mimeType;
    private final String displayName;
    private final long sizeBytes;

    public AndroidUriMediaSource(ContentResolver resolver, Uri uri, String mimeType,
                                 String displayName, long sizeBytes) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.displayName = displayName == null ? "Shared media" : displayName;
        this.sizeBytes = sizeBytes;
    }

    @Override public String mimeType() { return mimeType; }
    @Override public String displayName() { return displayName; }
    @Override public long sizeBytes() { return sizeBytes; }

    @Override
    public InputStream openStream() throws IOException {
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) throw new FileNotFoundException("Unable to open shared content: " + uri);
        return stream;
    }

    public Uri uri() { return uri; }
}
