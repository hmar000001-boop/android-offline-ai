package dev.filterbridge.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class ViddexaNanoOutputMapperTest {
    @Test
    public void softmaxProducesFiniteProbabilitiesSummingToOne() {
        Map<String, Double> probabilities = ViddexaNanoOutputMapper.probabilities(
                new float[]{2.0f, 1.0f, 0.0f, -1.0f, -2.0f});
        double sum = probabilities.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0d, sum, 1.0e-9d);
        assertTrue(probabilities.get("safe") > probabilities.get("porn"));
    }

    @Test
    public void invalidOutputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ViddexaNanoOutputMapper.probabilities(new float[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> ViddexaNanoOutputMapper.probabilities(new float[]{1, 2, Float.NaN, 4, 5}));
    }

    @Test
    public void classifierOutputsAreMappedWithoutOverclaimingCoverage() {
        AnalysisResult result = ViddexaNanoOutputMapper.toAnalysisResult(
                new float[]{-2.0f, 0.5f, 3.0f, 1.0f, -1.0f});
        assertEquals(AnalysisStatus.PARTIALLY_ANALYZED, result.status());
        assertTrue(result.detections().stream().anyMatch(d -> d.category().equals(CategoryId.NUDITY)
                && "porn".equals(d.modelLabel())));
        assertTrue(result.detections().stream().anyMatch(d -> d.category().equals(CategoryId.SUGGESTIVE)
                && "sexy".equals(d.modelLabel())));
        assertTrue(result.detections().stream().anyMatch(d -> d.category().equals(CategoryId.SEXUALIZED_ILLUSTRATION)
                && "hentai".equals(d.modelLabel())));
        assertTrue(result.coveredCategories().isEmpty());
        assertTrue(result.partiallyCoveredCategories().contains(CategoryId.NUDITY));
        assertTrue(result.partiallyCoveredCategories().contains(CategoryId.SUGGESTIVE));
        assertFalse(result.message().isEmpty());
    }
}
