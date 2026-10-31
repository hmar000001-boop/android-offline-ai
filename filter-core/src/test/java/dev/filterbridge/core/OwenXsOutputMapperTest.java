package dev.filterbridge.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class OwenXsOutputMapperTest {
    @Test public void nsfwMapsToBroadAdultVisualCategoryWithoutMislabelingItAsSpecificNudity() {
        AnalysisResult result = OwenXsOutputMapper.toAnalysisResult(new float[]{0.01f, 0.98f, 0.01f});
        assertEquals(AnalysisStatus.PARTIALLY_ANALYZED, result.status());
        assertTrue(result.detections().stream().anyMatch(d -> d.category().equals(CategoryId.ADULT_VISUAL_CONTENT)
                && d.confidence() > 0.97 && d.modelLabel().startsWith("NSFW")));
        assertFalse(result.detections().stream().anyMatch(d -> d.category().equals(CategoryId.NUDITY)));
        assertTrue(result.coveredCategories().isEmpty());
    }

    @Test public void sfwDoesNotCertifyContentAsSafe() {
        AnalysisResult result = OwenXsOutputMapper.toAnalysisResult(new float[]{0.0f, 0.0f, 1.0f});
        assertEquals(AnalysisStatus.PARTIALLY_ANALYZED, result.status());
        assertTrue(result.partiallyCoveredCategories().contains(CategoryId.ADULT_VISUAL_CONTENT));
        assertTrue(result.message().contains("NOT a safety verdict"));
    }

    @Test public void invalidProbabilitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OwenXsOutputMapper.validateProbabilities(new float[]{0.1f, 0.2f}));
        assertThrows(IllegalArgumentException.class,
                () -> OwenXsOutputMapper.validateProbabilities(new float[]{0.1f, Float.NaN, 0.9f}));
        assertThrows(IllegalArgumentException.class,
                () -> OwenXsOutputMapper.validateProbabilities(new float[]{0.1f, 0.2f, 0.2f}));
    }
}
