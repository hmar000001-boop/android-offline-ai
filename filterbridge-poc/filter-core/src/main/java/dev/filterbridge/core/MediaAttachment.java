package dev.filterbridge.core;

import java.util.Objects;

public final class MediaAttachment {
    private final MediaKind kind;
    private final MediaSource source;

    public MediaAttachment(MediaKind kind, MediaSource source) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.source = Objects.requireNonNull(source, "source");
    }

    public MediaKind kind() { return kind; }
    public MediaSource source() { return source; }
}
