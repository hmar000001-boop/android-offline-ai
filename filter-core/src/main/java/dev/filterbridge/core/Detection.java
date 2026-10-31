package dev.filterbridge.core;

import java.util.Objects;

/** A model-produced observation; it is not itself a policy decision or religious ruling. */
public final class Detection {
    private final CategoryId category;
    private final double confidence;
    private final EvidenceType evidenceType;
    private final long startTimeMs;
    private final long endTimeMs;
    private final NormalizedBox region;
    private final String modelId;
    private final String modelVersion;
    private final String modelLabel;

    /** Creates a detection without a spatial region or model metadata. */
    public Detection(CategoryId category, double confidence, EvidenceType evidenceType,
                     long startTimeMs, long endTimeMs) {
        this(category, confidence, evidenceType, startTimeMs, endTimeMs, null, null, null, null);
    }

    /** Creates a detection with an optional normalized image/video-frame region. */
    public Detection(CategoryId category, double confidence, EvidenceType evidenceType,
                     long startTimeMs, long endTimeMs, NormalizedBox region) {
        this(category, confidence, evidenceType, startTimeMs, endTimeMs, region, null, null, null);
    }

    /** Creates a detection carrying provenance for auditability and model replacement. */
    public Detection(CategoryId category, double confidence, EvidenceType evidenceType,
                     long startTimeMs, long endTimeMs, NormalizedBox region,
                     String modelId, String modelVersion, String modelLabel) {
        this.category = Objects.requireNonNull(category, "category");
        this.evidenceType = Objects.requireNonNull(evidenceType, "evidenceType");
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)
                || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
        }
        if (startTimeMs < -1 || endTimeMs < -1
                || (startTimeMs >= 0 && endTimeMs >= 0 && endTimeMs < startTimeMs)) {
            throw new IllegalArgumentException("Invalid media time range");
        }
        this.confidence = confidence;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.region = region;
        this.modelId = normalizeOptional(modelId);
        this.modelVersion = normalizeOptional(modelVersion);
        this.modelLabel = normalizeOptional(modelLabel);
    }

    public CategoryId category() { return category; }
    public double confidence() { return confidence; }
    public EvidenceType evidenceType() { return evidenceType; }
    /** Returns -1 when this detection is not tied to a media timeline. */
    public long startTimeMs() { return startTimeMs; }
    /** Returns -1 when this detection is not tied to a media timeline. */
    public long endTimeMs() { return endTimeMs; }
    /** Returns null when the model classified the whole asset rather than a sub-region. */
    public NormalizedBox region() { return region; }
    /** Returns null for detections from legacy or unbranded analyzers. */
    public String modelId() { return modelId; }
    public String modelVersion() { return modelVersion; }
    public String modelLabel() { return modelLabel; }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
