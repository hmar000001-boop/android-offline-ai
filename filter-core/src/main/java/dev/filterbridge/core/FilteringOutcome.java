package dev.filterbridge.core;

import java.util.Objects;

public final class FilteringOutcome {
    private final AnalysisResult analysis;
    private final PolicyDecision decision;

    public FilteringOutcome(AnalysisResult analysis, PolicyDecision decision) {
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    public AnalysisResult analysis() { return analysis; }
    public PolicyDecision decision() { return decision; }
}
