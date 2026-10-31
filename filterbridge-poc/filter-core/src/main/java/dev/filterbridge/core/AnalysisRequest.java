package dev.filterbridge.core;

import java.util.Objects;
import java.util.UUID;

public final class AnalysisRequest {
    private final String requestId;
    private final InboundContent content;

    public AnalysisRequest(InboundContent content) {
        this(UUID.randomUUID().toString(), content);
    }

    public AnalysisRequest(String requestId, InboundContent content) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.content = Objects.requireNonNull(content, "content");
    }

    public String requestId() { return requestId; }
    public InboundContent content() { return content; }
}
