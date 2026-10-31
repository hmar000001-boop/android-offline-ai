package dev.filterbridge.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.List;

public class PolicyEngineTest {
    private final PolicyEngine engine = new PolicyEngine();
    private final FilteringPolicy balanced = FilteringPolicy.balancedDefault();

    @Test
    public void uncertainDetectionIsBlurred() {
        AnalysisResult result = analyzed(new Detection(CategoryId.NUDITY, 0.62, EvidenceType.IMAGE, -1, -1));
        assertEquals(PolicyAction.BLUR, engine.decide(result, balanced).action());
    }

    @Test
    public void highConfidenceDetectionIsHiddenInStrictMode() {
        AnalysisResult result = analyzed(new Detection(CategoryId.NUDITY, 0.96, EvidenceType.IMAGE, -1, -1));
        assertEquals(PolicyAction.HIDE, engine.decide(result, balanced).action());
    }

    @Test
    public void noDetectionsAfterCompleteAnalysisAllowsContent() {
        assertEquals(PolicyAction.ALLOW,
                engine.decide(new AnalysisResult(AnalysisStatus.ANALYZED, List.of(), ""), balanced).action());
    }

    @Test
    public void missingModelDoesNotMeanSafe() {
        assertEquals(PolicyAction.UNVERIFIED,
                engine.decide(AnalysisResult.notAnalyzed("Model unavailable"), balanced).action());
    }

    @Test
    public void categoryOverrideCanDisableOneCategory() {
        FilteringPolicy custom = FilteringPolicy.builder(CategoryRule.balancedDefault())
                .override(CategoryId.MUSIC, new CategoryRule(FilterLevel.NONE, 0.0, 1.0))
                .build();
        AnalysisResult result = analyzed(new Detection(CategoryId.MUSIC, 0.99, EvidenceType.AUDIO, -1, -1));
        assertEquals(PolicyAction.ALLOW, engine.decide(result, custom).action());
    }

    private static AnalysisResult analyzed(Detection detection) {
        return new AnalysisResult(AnalysisStatus.ANALYZED, List.of(detection), "");
    }
}
