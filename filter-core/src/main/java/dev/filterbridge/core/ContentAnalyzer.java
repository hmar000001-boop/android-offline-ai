package dev.filterbridge.core;

/** Replaceable perception boundary. Implementations will connect local vision/audio/text models. */
public interface ContentAnalyzer {
    AnalysisResult analyze(AnalysisRequest request);
}
