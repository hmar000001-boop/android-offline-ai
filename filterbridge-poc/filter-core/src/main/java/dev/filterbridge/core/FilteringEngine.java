package dev.filterbridge.core;

import java.util.Objects;

/** Orchestrates perception followed by deterministic user policy. */
public final class FilteringEngine {
    private final ContentAnalyzer analyzer;
    private final PolicyEngine policyEngine;

    public FilteringEngine(ContentAnalyzer analyzer, PolicyEngine policyEngine) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    public FilteringOutcome analyze(AnalysisRequest request, FilteringPolicy policy) {
        AnalysisResult analysis = analyzer.analyze(request);
        return new FilteringOutcome(analysis, policyEngine.decide(analysis, policy));
    }
}
