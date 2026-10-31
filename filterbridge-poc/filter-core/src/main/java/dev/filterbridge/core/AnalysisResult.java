package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AnalysisResult {
    private final AnalysisStatus status;
    private final List<Detection> detections;
    private final String message;

    public AnalysisResult(AnalysisStatus status, List<Detection> detections, String message) {
        if (status == null) throw new IllegalArgumentException("status is required");
        this.status = status;
        this.detections = Collections.unmodifiableList(new ArrayList<>(
                detections == null ? Collections.emptyList() : detections));
        this.message = message == null ? "" : message;
    }

    public static AnalysisResult notAnalyzed(String message) {
        return new AnalysisResult(AnalysisStatus.NOT_ANALYZED, Collections.emptyList(), message);
    }

    public AnalysisStatus status() { return status; }
    public List<Detection> detections() { return detections; }
    public String message() { return message; }
}
