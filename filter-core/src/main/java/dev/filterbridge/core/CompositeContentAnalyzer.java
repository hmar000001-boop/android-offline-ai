package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs independent local analyzers and combines their evidence without allowing one model's
 * "safe" class to cancel another model's detection. Failed analyzers are reported, not ignored.
 */
public final class CompositeContentAnalyzer implements ContentAnalyzer, AutoCloseable {
    private final List<ContentAnalyzer> analyzers;

    public CompositeContentAnalyzer(List<ContentAnalyzer> analyzers) {
        if (analyzers == null || analyzers.isEmpty()) {
            throw new IllegalArgumentException("At least one analyzer is required.");
        }
        List<ContentAnalyzer> copy = new ArrayList<>();
        for (ContentAnalyzer analyzer : analyzers) {
            if (analyzer == null) throw new IllegalArgumentException("Analyzers must not contain null.");
            copy.add(analyzer);
        }
        this.analyzers = Collections.unmodifiableList(copy);
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        List<String> messages = new ArrayList<>();
        List<Detection> detections = new ArrayList<>();
        Set<CategoryId> fullyCovered = new HashSet<>();
        Set<CategoryId> partiallyCovered = new HashSet<>();
        boolean anyCompleted = false;
        boolean anyFailed = false;
        boolean anyNotAnalyzed = false;

        for (ContentAnalyzer analyzer : analyzers) {
            AnalysisResult result;
            try {
                result = analyzer.analyze(request);
                if (result == null) result = AnalysisResult.failed("An analyzer returned no result.");
            } catch (RuntimeException exception) {
                String detail = exception.getMessage();
                result = AnalysisResult.failed(detail == null || detail.trim().isEmpty()
                        ? "An analyzer threw an exception."
                        : "An analyzer failed: " + detail);
            }
            if (!result.message().isEmpty()) messages.add(result.message());
            detections.addAll(result.detections());
            fullyCovered.addAll(result.coveredCategories());
            partiallyCovered.addAll(result.partiallyCoveredCategories());
            anyCompleted |= result.status() == AnalysisStatus.ANALYZED
                    || result.status() == AnalysisStatus.PARTIALLY_ANALYZED;
            anyFailed |= result.status() == AnalysisStatus.FAILED;
            anyNotAnalyzed |= result.status() == AnalysisStatus.NOT_ANALYZED;
        }

        // Partial evidence from any constituent prevents the composite from claiming full coverage.
        fullyCovered.removeAll(partiallyCovered);
        String message = String.join("\n\n", messages);
        if (anyCompleted) {
            return new AnalysisResult(AnalysisStatus.PARTIALLY_ANALYZED, detections,
                    fullyCovered, partiallyCovered, message);
        }
        if (anyFailed) return AnalysisResult.failed(message.isEmpty()
                ? "All local analyzers failed." : message);
        if (anyNotAnalyzed) return AnalysisResult.notAnalyzed(message.isEmpty()
                ? "No configured analyzer supports this media." : message);
        return AnalysisResult.failed("No analyzer produced a usable result.");
    }

    @Override
    public void close() {
        for (ContentAnalyzer analyzer : analyzers) {
            if (analyzer instanceof AutoCloseable) {
                try { ((AutoCloseable) analyzer).close(); }
                catch (Exception ignored) { /* Best-effort resource release. */ }
            }
        }
    }
}
