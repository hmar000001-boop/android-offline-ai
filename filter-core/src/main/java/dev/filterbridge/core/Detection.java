package dev.filterbridge.core;

import java.util.Objects;

/** A model-produced observation. It is not itself a policy decision or a religious ruling. */
public final class Detection {
    private final CategoryId category;
    private final double confidence;
    private final EvidenceType evidenceType;
    private final long startTimeMs;
    private final long endTimeMs;

    public Detection(CategoryId category, double confidence, EvidenceType evidenceType,
                     long startTimeMs, long endTimeMs) {
        this.category = Objects.requireNonNull(category, "category");
        this.evidenceType = Objects.requireNonNull(evidenceType, "evidenceType");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (startTimeMs < -1 || endTimeMs < -1 ||
                (startTimeMs >= 0 && endTimeMs >= 0 && endTimeMs < startTimeMs)) {
            throw new IllegalArgumentException("Invalid media time range");
        }
        this.confidence = confidence;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
    }

    public CategoryId category() { return category; }
    public double confidence() { return confidence; }
    public EvidenceType evidenceType() { return evidenceType; }
    public long startTimeMs() { return startTimeMs; }
    public long endTimeMs() { return endTimeMs; }
}
