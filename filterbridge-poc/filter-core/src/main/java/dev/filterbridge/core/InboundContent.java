package dev.filterbridge.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Normalized content received from any platform-specific intake adapter. */
public final class InboundContent {
    private final String text;
    private final List<MediaAttachment> attachments;
    private final ImportOrigin importOrigin;
    private final ContentSourcePlatform platformHint;
    private final Instant receivedAt;

    public InboundContent(String text, List<MediaAttachment> attachments,
                          ImportOrigin importOrigin, ContentSourcePlatform platformHint) {
        this.text = text == null ? "" : text;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(
                attachments == null ? Collections.emptyList() : attachments));
        this.importOrigin = importOrigin == null ? ImportOrigin.INTERNAL : importOrigin;
        this.platformHint = platformHint == null ? ContentSourcePlatform.UNKNOWN : platformHint;
        this.receivedAt = Instant.now();
    }

    public String text() { return text; }
    public List<MediaAttachment> attachments() { return attachments; }
    public ImportOrigin importOrigin() { return importOrigin; }
    public ContentSourcePlatform platformHint() { return platformHint; }
    public Instant receivedAt() { return receivedAt; }
}
