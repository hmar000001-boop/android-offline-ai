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

    /** Creates a detection without a spatial region (for whole-image or non-visual evidence). */
    public Detection(CategoryId category, double confidence, EvidenceType evidenceType,
                     long startTimeMs, long endTimeMs) {
        this(category, confidence, evidenceType, startTimeMs, endTimeMs, null);
    }

    /** Creates a detection with an optional normalized image/video-frame region. */
    public Detection(CategoryId category, double confidence, EvidenceType evidenceType,
                     long startTimeMs, long endTimeMs, NormalizedBox region) {
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
}
