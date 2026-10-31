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

    /**
     * Executes analysis and converts runtime/model failures into FAILED + UNVERIFIED.
     * It deliberately does not convert an exception into an empty, successful result.
     */
    public FilteringOutcome analyze(AnalysisRequest request, FilteringPolicy policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        AnalysisResult analysis;
        try {
            analysis = analyzer.analyze(request);
            if (analysis == null) {
                analysis = AnalysisResult.failed("The analyzer returned no result.");
            }
        } catch (RuntimeException exception) {
            String detail = exception.getMessage();
            analysis = AnalysisResult.failed(detail == null || detail.trim().isEmpty()
                    ? "The on-device analyzer failed."
                    : "The on-device analyzer failed: " + detail);
        }
        return new FilteringOutcome(analysis, policyEngine.decide(analysis, policy));
    }
}
