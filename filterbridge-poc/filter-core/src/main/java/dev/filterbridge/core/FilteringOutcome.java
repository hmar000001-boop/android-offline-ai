package dev.filterbridge.core;

public final class FilteringOutcome {
    private final AnalysisResult analysis;
    private final PolicyDecision decision;

    public FilteringOutcome(AnalysisResult analysis, PolicyDecision decision) {
        this.analysis = analysis;
        this.decision = decision;
    }

    public AnalysisResult analysis() { return analysis; }
    public PolicyDecision decision() { return decision; }
}
