package dev.filterbridge.core;

/** Explicit placeholder: it never claims that imported content is safe. */
public final class NotConfiguredAnalyzer implements ContentAnalyzer {
    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        return AnalysisResult.notAnalyzed("No on-device model is connected yet.");
    }
}
