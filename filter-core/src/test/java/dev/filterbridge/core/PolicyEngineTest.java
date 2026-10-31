package dev.filterbridge.core;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class PolicyEngineTest {
    private final PolicyEngine engine = new PolicyEngine();
    private final FilteringPolicy balanced = FilteringPolicy.balancedDefault();

    @Test
    public void uncertainDetectionIsBlurred() {
        AnalysisResult result = analyzed(new Detection(
                CategoryId.NUDITY, 0.62, EvidenceType.IMAGE, -1, -1));
        assertEquals(PolicyAction.BLUR, engine.decide(result, balanced).action());
    }

    @Test
    public void highConfidenceDetectionIsHiddenAtThreshold() {
        AnalysisResult result = analyzed(new Detection(
                CategoryId.NUDITY, 0.90, EvidenceType.IMAGE, -1, -1));
        assertEquals(PolicyAction.HIDE, engine.decide(result, balanced).action());
    }

    @Test
    public void confidenceBelowActivationDoesNotTriggerPolicy() {
        AnalysisResult result = analyzed(new Detection(
                CategoryId.NUDITY, 0.44, EvidenceType.IMAGE, -1, -1));
        assertEquals(PolicyAction.ALLOW, engine.decide(result, balanced).action());
    }

    @Test
    public void noDetectionsAfterCompleteAnalysisAllowsContent() {
        assertEquals(PolicyAction.ALLOW,
                engine.decide(new AnalysisResult(AnalysisStatus.ANALYZED,
                        Collections.emptyList(), balanced.requiredCategories(), ""), balanced).action());
    }

    @Test
    public void missingModelDoesNotMeanSafe() {
        assertEquals(PolicyAction.UNVERIFIED,
                engine.decide(AnalysisResult.notAnalyzed("Model unavailable"), balanced).action());
    }

    @Test
    public void partialAnalysisWithoutDetectionsRemainsUnverified() {
        AnalysisResult partial = new AnalysisResult(AnalysisStatus.PARTIALLY_ANALYZED,
                Collections.emptyList(), "Some frames were not processed.");
        assertEquals(PolicyAction.UNVERIFIED, engine.decide(partial, balanced).action());
    }

    @Test
    public void positiveDetectionDoesNotAutomaticallyCertifyCategoryCoverage() {
        AnalysisResult partial = new AnalysisResult(AnalysisStatus.PARTIALLY_ANALYZED,
                Collections.singletonList(new Detection(CategoryId.NUDITY, 0.72,
                        EvidenceType.IMAGE, -1, -1)), Collections.emptySet(),
                Collections.singleton(CategoryId.NUDITY), "Experimental classifier ran.");
        PolicyDecision decision = engine.decide(partial, balanced);
        assertEquals(PolicyAction.BLUR, decision.action());
        org.junit.Assert.assertFalse(decision.analysisComplete());
    }

    @Test
    public void partialAndFullCoverageCannotOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisResult(
                AnalysisStatus.PARTIALLY_ANALYZED, Collections.emptyList(),
                Collections.singleton(CategoryId.NUDITY),
                Collections.singleton(CategoryId.NUDITY), "invalid"));
    }

    @Test
    public void categoryOverrideCanDisableOneCategory() {
        FilteringPolicy custom = FilteringPolicy.builder(CategoryRule.balancedDefault())
                .override(CategoryId.MUSIC, new CategoryRule(FilterLevel.NONE, 0.0, 1.0))
                .build();
        AnalysisResult result = analyzed(new Detection(
                CategoryId.MUSIC, 0.99, EvidenceType.AUDIO, -1, -1));
        AnalysisResult complete = new AnalysisResult(AnalysisStatus.ANALYZED,
                result.detections(), custom.requiredCategories(), "");
        assertEquals(PolicyAction.ALLOW, engine.decide(complete, custom).action());
    }

    @Test
    public void categoryIdsAreNormalizedAndExtensible() {
        assertEquals(CategoryId.of("custom-rule"), CategoryId.of(" CUSTOM-RULE "));
        assertNotEquals(CategoryId.NUDITY, CategoryId.of("custom-rule"));
    }

    @Test
    public void hideThresholdCannotBeLowerThanActivationThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new CategoryRule(FilterLevel.STRICT, 0.80, 0.70));
    }


    @Test
    public void analyzerExceptionFailsClosedAsUnverified() {
        FilteringEngine failing = new FilteringEngine(request -> {
            throw new IllegalStateException("runtime unavailable");
        }, engine);
        FilteringOutcome outcome = failing.analyze(
                new AnalysisRequest(new InboundContent("", Collections.emptyList(),
                        ImportOrigin.INTERNAL, ContentSourcePlatform.UNKNOWN)), balanced);
        assertEquals(AnalysisStatus.FAILED, outcome.analysis().status());
        assertEquals(PolicyAction.UNVERIFIED, outcome.decision().action());
    }

    @Test
    public void nullAnalyzerResultFailsClosedAsUnverified() {
        FilteringEngine failing = new FilteringEngine(request -> null, engine);
        FilteringOutcome outcome = failing.analyze(
                new AnalysisRequest(new InboundContent("", Collections.emptyList(),
                        ImportOrigin.INTERNAL, ContentSourcePlatform.UNKNOWN)), balanced);
        assertEquals(AnalysisStatus.FAILED, outcome.analysis().status());
        assertEquals(PolicyAction.UNVERIFIED, outcome.decision().action());
    }

    @Test
    public void detectionCanCarryNormalizedRegionForRedaction() {
        NormalizedBox box = new NormalizedBox(0.1, 0.2, 0.7, 0.9);
        Detection detection = new Detection(CategoryId.NUDITY, 0.8,
                EvidenceType.VIDEO_FRAME, 1000, 1250, box);
        assertEquals(box, detection.region());
        assertEquals(1000L, detection.startTimeMs());
    }

    @Test
    public void normalizedRegionRejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedBox(-0.1, 0.0, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedBox(0.4, 0.4, 0.4, 0.8));
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedBox(Double.POSITIVE_INFINITY, 0.0, 0.5, 0.5));
    }

    @Test
    public void normalizedRegionsUseValueEquality() {
        assertEquals(new NormalizedBox(0.1, 0.2, 0.7, 0.9),
                new NormalizedBox(0.1, 0.2, 0.7, 0.9));
    }

    @Test
    public void oneCategoryModelCannotMarkAllEnabledCategoriesSafe() {
        AnalysisResult nudityOnly = new AnalysisResult(AnalysisStatus.ANALYZED,
                Collections.emptyList(), Collections.singleton(CategoryId.NUDITY), "");
        PolicyDecision decision = engine.decide(nudityOnly, balanced);
        assertEquals(PolicyAction.UNVERIFIED, decision.action());
        org.junit.Assert.assertFalse(decision.analysisComplete());
    }

    @Test
    public void positiveDetectionIsAppliedEvenWhenOtherCategoriesRemainUnverified() {
        AnalysisResult nudityOnly = new AnalysisResult(AnalysisStatus.ANALYZED,
                Collections.singletonList(new Detection(CategoryId.NUDITY, 0.70,
                        EvidenceType.IMAGE, -1, -1)), Collections.singleton(CategoryId.NUDITY), "");
        PolicyDecision decision = engine.decide(nudityOnly, balanced);
        assertEquals(PolicyAction.BLUR, decision.action());
        org.junit.Assert.assertFalse(decision.analysisComplete());
    }

    private static AnalysisResult analyzed(Detection detection) {
        return new AnalysisResult(AnalysisStatus.ANALYZED, List.of(detection),
                CategoryId.builtIns(), "");
    }
}
