package dev.filterbridge.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class CompositeContentAnalyzerTest {
    @Test public void aNormalPredictionFromOneModelCannotCancelAnotherModelsDetection() {
        AnalysisResult positive = OwenXsOutputMapper.toAnalysisResult(new float[]{0f, 0.96f, 0.04f});
        AnalysisResult normal = OwenXsOutputMapper.toAnalysisResult(new float[]{0f, 0f, 1f});
        CompositeContentAnalyzer composite = new CompositeContentAnalyzer(Arrays.asList(
                request -> normal, request -> positive));
        FilteringPolicy policy = FilteringPolicy.balancedDefault();
        AnalysisRequest request = new AnalysisRequest(new InboundContent("",
                Collections.singletonList(new MediaAttachment(MediaKind.IMAGE,
                        new TestMediaSource())), ImportOrigin.FILE_PICKER,
                ContentSourcePlatform.UNKNOWN));
        AnalysisResult combined = composite.analyze(request);
        PolicyDecision decision = new PolicyEngine().decide(combined, policy);
        assertTrue(combined.detections().stream().anyMatch(d -> d.category().equals(CategoryId.ADULT_VISUAL_CONTENT)
                && d.confidence() > 0.9));
        assertEquals(PolicyAction.HIDE, decision.action());
        assertFalse(decision.analysisComplete());
    }

    @Test public void normalClassFromBothModelsStillCannotMarkAllCategoriesSafe() {
        AnalysisResult viddexaNormal = ViddexaNanoOutputMapper.toAnalysisResult(
                new float[]{100.0f, -100.0f, -100.0f, -100.0f, -100.0f});
        AnalysisResult owenSfw = OwenXsOutputMapper.toAnalysisResult(new float[]{0f, 0f, 1f});
        CompositeContentAnalyzer composite = new CompositeContentAnalyzer(Arrays.asList(
                request -> viddexaNormal, request -> owenSfw));
        AnalysisRequest request = new AnalysisRequest(new InboundContent("",
                Collections.singletonList(new MediaAttachment(MediaKind.IMAGE, new TestMediaSource())),
                ImportOrigin.FILE_PICKER, ContentSourcePlatform.UNKNOWN));
        FilteringOutcome outcome = new FilteringEngine(composite, new PolicyEngine()).analyze(
                request, FilteringPolicy.balancedDefault());
        assertEquals(PolicyAction.UNVERIFIED, outcome.decision().action());
        assertFalse(outcome.decision().analysisComplete());
    }

    @Test public void oneModelFailureDoesNotDiscardAnotherModelsEvidence() {
        AnalysisResult positive = OwenXsOutputMapper.toAnalysisResult(new float[]{0f, 0.8f, 0.2f});
        CompositeContentAnalyzer composite = new CompositeContentAnalyzer(Arrays.asList(
                request -> AnalysisResult.failed("test failure"), request -> positive));
        AnalysisRequest request = new AnalysisRequest(new InboundContent("",
                Collections.singletonList(new MediaAttachment(MediaKind.IMAGE, new TestMediaSource())),
                ImportOrigin.FILE_PICKER, ContentSourcePlatform.UNKNOWN));
        AnalysisResult combined = composite.analyze(request);
        assertEquals(AnalysisStatus.PARTIALLY_ANALYZED, combined.status());
        assertTrue(combined.message().contains("test failure"));
        assertTrue(combined.message().contains("OwenElliott"));
    }

    private static final class TestMediaSource implements MediaSource {
        public String mimeType() { return "image/jpeg"; }
        public String displayName() { return "test.jpg"; }
        public long sizeBytes() { return 0; }
        public java.io.InputStream openStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
    }
}
