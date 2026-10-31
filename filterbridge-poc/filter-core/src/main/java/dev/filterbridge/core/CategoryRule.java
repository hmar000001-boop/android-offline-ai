package dev.filterbridge.core;

/** Deterministic rule for one category. Confidence values are in the inclusive range [0, 1]. */
public final class CategoryRule {
    private final FilterLevel level;
    private final double activationConfidence;
    private final double hideConfidence;

    public CategoryRule(FilterLevel level, double activationConfidence, double hideConfidence) {
        if (level == null) throw new IllegalArgumentException("level is required");
        requireConfidence(activationConfidence, "activationConfidence");
        requireConfidence(hideConfidence, "hideConfidence");
        if (hideConfidence < activationConfidence) {
            throw new IllegalArgumentException("hideConfidence must be >= activationConfidence");
        }
        this.level = level;
        this.activationConfidence = activationConfidence;
        this.hideConfidence = hideConfidence;
    }

    /** Balanced default: uncertain detections are blurred; hiding requires high confidence. */
    public static CategoryRule balancedDefault() {
        return new CategoryRule(FilterLevel.STRICT, 0.45, 0.90);
    }

    public FilterLevel level() { return level; }
    public double activationConfidence() { return activationConfidence; }
    public double hideConfidence() { return hideConfidence; }

    private static void requireConfidence(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
