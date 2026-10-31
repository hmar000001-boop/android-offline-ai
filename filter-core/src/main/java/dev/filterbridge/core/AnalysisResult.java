package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable detector output. Fully and partially covered categories are explicit. A detection
 * alone does not certify that its entire category was exhaustively analysed.
 */
public final class AnalysisResult {
    private final AnalysisStatus status;
    private final List<Detection> detections;
    private final Set<CategoryId> coveredCategories;
    private final Set<CategoryId> partiallyCoveredCategories;
    private final String message;

    /** Creates a result with no declared category coverage (safe default). */
    public AnalysisResult(AnalysisStatus status, List<Detection> detections, String message) {
        this(status, detections, Collections.emptySet(), Collections.emptySet(), message);
    }

    public AnalysisResult(AnalysisStatus status, List<Detection> detections,
                          Set<CategoryId> coveredCategories, String message) {
        this(status, detections, coveredCategories, Collections.emptySet(), message);
    }

    public AnalysisResult(AnalysisStatus status, List<Detection> detections,
                          Set<CategoryId> coveredCategories,
                          Set<CategoryId> partiallyCoveredCategories, String message) {
        this.status = Objects.requireNonNull(status, "status");
        List<Detection> safeDetections = detections == null
                ? Collections.emptyList() : detections;
        for (Detection detection : safeDetections) {
            if (detection == null) {
                throw new IllegalArgumentException("detections must not contain null entries");
            }
        }
        this.detections = Collections.unmodifiableList(new ArrayList<>(safeDetections));
        this.coveredCategories = immutableCategories(coveredCategories, "coveredCategories");
        this.partiallyCoveredCategories = immutableCategories(
                partiallyCoveredCategories, "partiallyCoveredCategories");
        if (!Collections.disjoint(this.coveredCategories, this.partiallyCoveredCategories)) {
            throw new IllegalArgumentException("A category cannot be both fully and partially covered");
        }
        this.message = message == null ? "" : message;
    }

    public static AnalysisResult notAnalyzed(String message) {
        return new AnalysisResult(AnalysisStatus.NOT_ANALYZED, Collections.emptyList(),
                Collections.emptySet(), Collections.emptySet(), message);
    }

    public static AnalysisResult failed(String message) {
        return new AnalysisResult(AnalysisStatus.FAILED, Collections.emptyList(),
                Collections.emptySet(), Collections.emptySet(), message);
    }

    public AnalysisStatus status() { return status; }
    public List<Detection> detections() { return detections; }
    public Set<CategoryId> coveredCategories() { return coveredCategories; }
    public Set<CategoryId> partiallyCoveredCategories() { return partiallyCoveredCategories; }
    public String message() { return message; }

    private static Set<CategoryId> immutableCategories(Set<CategoryId> source, String name) {
        Set<CategoryId> safe = source == null ? Collections.emptySet() : source;
        for (CategoryId category : safe) {
            if (category == null) throw new IllegalArgumentException(name + " must not contain null entries");
        }
        return Collections.unmodifiableSet(new HashSet<>(safe));
    }
}
