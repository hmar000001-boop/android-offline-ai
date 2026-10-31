package dev.filterbridge.core;

import java.io.IOException;
import java.io.InputStream;

/** Lazy media access contract. Implementations may be Android Uri, file, or another provider. */
public interface MediaSource {
    String mimeType();
    String displayName();
    long sizeBytes();
    InputStream openStream() throws IOException;
}
