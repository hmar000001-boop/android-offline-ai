package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps Viddexa NSFW Detection 2 Nano logits to evidence. The model's "sexy" label is kept as
 * an experimental SUGGESTIVE signal only: the model card's intended-use section says it is
 * designed for explicit nudity and may label suggestive clothing as safe. No category is
 * declared fully covered by this adapter until local validation establishes that claim.
 */
public final class ViddexaNanoOutputMapper {
    public static final String MODEL_ID = "viddexa/nsfw-detection-2-nano";
    public static final String MODEL_REVISION = "12e57200346246b37382f746e4d94d10b014f6a1";
    public static final String MODEL_VERSION = MODEL_REVISION;
    public static final List<String> LABELS = Collections.unmodifiableList(Arrays.asList(
            "safe", "hentai", "porn", "sexy", "drawing"));

    private ViddexaNanoOutputMapper() { }

    /** Stable softmax with strict shape and finite-value validation. */
    public static Map<String, Double> probabilities(float[] logits) {
        if (logits == null || logits.length != LABELS.size()) {
            throw new IllegalArgumentException("Expected exactly five model logits.");
        }
        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            if (Float.isNaN(logit) || Float.isInfinite(logit)) {
                throw new IllegalArgumentException("Model output contains a non-finite logit.");
            }
            max = Math.max(max, logit);
        }
        double sum = 0.0d;
        double[] exponentials = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exponentials[i] = Math.exp((double) logits[i] - max);
            sum += exponentials[i];
        }
        if (!(sum > 0.0d) || Double.isInfinite(sum) || Double.isNaN(sum)) {
            throw new IllegalArgumentException("Unable to normalize model output.");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < logits.length; i++) {
            result.put(LABELS.get(i), exponentials[i] / sum);
        }
        return Collections.unmodifiableMap(result);
    }

    public static AnalysisResult toAnalysisResult(float[] logits) {
        Map<String, Double> scores = probabilities(logits);
        List<Detection> detections = new ArrayList<>();
        addIfSupported(detections, scores, "porn", CategoryId.NUDITY);
        addIfSupported(detections, scores, "hentai", CategoryId.SEXUALIZED_ILLUSTRATION);
        addIfSupported(detections, scores, "sexy", CategoryId.SUGGESTIVE);

        Set<CategoryId> partial = new LinkedHashSet<>(Arrays.asList(
                CategoryId.NUDITY, CategoryId.SUGGESTIVE,
                CategoryId.SEXUALIZED_ILLUSTRATION));
        StringBuilder message = new StringBuilder();
        message.append("On-device image inference completed using ")
                .append(MODEL_ID).append(" (revision ").append(MODEL_VERSION).append("). ")
                .append("Whole-image classification only; this model does not return blur regions. ")
                .append("Scores: ");
        for (int i = 0; i < LABELS.size(); i++) {
            if (i > 0) message.append(" · ");
            String label = LABELS.get(i);
            message.append(label).append('=')
                    .append(String.format(Locale.ROOT, "%.1f%%", scores.get(label) * 100.0d));
        }
        message.append(". The 'sexy' score is experimental and is not sufficient to verify suggestive content. ")
                .append("The model card describes explicit nudity as its intended use. ")
                .append("No enabled category is certified fully covered by this experimental adapter.");
        return new AnalysisResult(AnalysisStatus.PARTIALLY_ANALYZED, detections,
                Collections.emptySet(), partial, message.toString());
    }

    private static void addIfSupported(List<Detection> detections, Map<String, Double> scores,
                                       String label, CategoryId category) {
        detections.add(new Detection(category, scores.get(label), EvidenceType.IMAGE,
                -1L, -1L, null, MODEL_ID, MODEL_VERSION, label));
    }
}
