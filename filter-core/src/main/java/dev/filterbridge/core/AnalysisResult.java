package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable detector output. `coveredCategories` is explicit: a successful model run for one
 * category must never imply that all other enabled categories were checked.
 */
public final class AnalysisResult {
    private final AnalysisStatus status;
    private final List<Detection> detections;
    private final Set<CategoryId> coveredCategories;
    private final String message;

    /** Creates a result with no declared category coverage (safe default). */
    public AnalysisResult(AnalysisStatus status, List<Detection> detections, String message) {
        this(status, detections, Collections.emptySet(), message);
    }

    public AnalysisResult(AnalysisStatus status, List<Detection> detections,
                          Set<CategoryId> coveredCategories, String message) {
        this.status = Objects.requireNonNull(status, "status");
        List<Detection> safeDetections = detections == null
                ? Collections.emptyList() : detections;
        for (Detection detection : safeDetections) {
            if (detection == null) {
                throw new IllegalArgumentException("detections must not contain null entries");
            }
        }
        this.detections = Collections.unmodifiableList(new ArrayList<>(safeDetections));
        Set<CategoryId> safeCoverage = coveredCategories == null
                ? Collections.emptySet() : coveredCategories;
        for (CategoryId category : safeCoverage) {
            if (category == null) {
                throw new IllegalArgumentException("coveredCategories must not contain null entries");
            }
        }
        this.coveredCategories = Collections.unmodifiableSet(new HashSet<>(safeCoverage));
        this.message = message == null ? "" : message;
    }

    public static AnalysisResult notAnalyzed(String message) {
        return new AnalysisResult(AnalysisStatus.NOT_ANALYZED, Collections.emptyList(),
                Collections.emptySet(), message);
    }

    public static AnalysisResult failed(String message) {
        return new AnalysisResult(AnalysisStatus.FAILED, Collections.emptyList(),
                Collections.emptySet(), message);
    }

    public AnalysisStatus status() { return status; }
    public List<Detection> detections() { return detections; }
    public Set<CategoryId> coveredCategories() { return coveredCategories; }
    public String message() { return message; }
}
