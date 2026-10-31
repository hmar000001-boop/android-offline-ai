package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Maps OwenElliott's already-softmaxed [NSFL, NSFW, SFW] ONNX probabilities. */
public final class OwenXsOutputMapper {
    public static final String MODEL_ID = "OwenElliott/image-safety-classifier-xs";
    public static final String MODEL_REVISION = "606ad3dfd6a023215e3ab0797040437cc365977b";
    public static final String MODEL_VERSION = MODEL_REVISION;
    public static final List<String> LABELS = Collections.unmodifiableList(
            Arrays.asList("NSFL", "NSFW", "SFW"));

    private OwenXsOutputMapper() { }

    public static List<Double> validateProbabilities(float[] values) {
        if (values == null || values.length != 3) {
            throw new IllegalArgumentException("Expected exactly three Owen XS probabilities.");
        }
        double sum = 0.0d;
        List<Double> result = new ArrayList<>(3);
        for (float value : values) {
            if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
                throw new IllegalArgumentException("Owen XS output is not a finite probability vector.");
            }
            result.add((double) value);
            sum += value;
        }
        // The ONNX graph includes its own softmax. Do not apply another softmax.
        if (Math.abs(sum - 1.0d) > 0.03d) {
            throw new IllegalArgumentException("Owen XS output does not sum to one; expected baked-in softmax probabilities.");
        }
        return Collections.unmodifiableList(result);
    }

    public static AnalysisResult toAnalysisResult(float[] rawProbabilities) {
        List<Double> scores = validateProbabilities(rawProbabilities);
        double nsfl = scores.get(0);
        double nsfw = scores.get(1);
        double sfw = scores.get(2);
        List<Detection> detections = new ArrayList<>();
        // NSFW is deliberately a broad category: the model does not distinguish explicit
        // nudity from suggestive content. It is never re-labelled as either specific class.
        detections.add(new Detection(CategoryId.ADULT_VISUAL_CONTENT, nsfw, EvidenceType.IMAGE,
                -1L, -1L, null, MODEL_ID, MODEL_VERSION, "NSFW (broad adult visual content)"));
        // The publisher says NSFL is underrepresented; this is an experimental distress signal.
        detections.add(new Detection(CategoryId.VIOLENCE, nsfl, EvidenceType.IMAGE,
                -1L, -1L, null, MODEL_ID, MODEL_VERSION, "NSFL (experimental gore/distress signal)"));

        Set<CategoryId> partial = new LinkedHashSet<>(Arrays.asList(
                CategoryId.ADULT_VISUAL_CONTENT, CategoryId.VIOLENCE));
        String top = "SFW";
        double topScore = sfw;
        if (nsfw > topScore) { top = "NSFW"; topScore = nsfw; }
        if (nsfl > topScore) { top = "NSFL"; topScore = nsfl; }
        String message = String.format(Locale.ROOT,
                "Independent image-safety model %s (revision %s): NSFL=%.1f%% · NSFW=%.1f%% · SFW=%.1f%%. "
                        + "Top model class: %s. SFW is a classifier label, NOT a safety verdict. "
                        + "NSFW is broad and does not distinguish nudity from suggestive content. "
                        + "The publisher reports that NSFL training data is underrepresented. "
                        + "These model scores are not independently calibrated.",
                MODEL_ID, MODEL_VERSION, nsfl * 100.0d, nsfw * 100.0d, sfw * 100.0d, top);
        return new AnalysisResult(AnalysisStatus.PARTIALLY_ANALYZED, detections,
                Collections.emptySet(), partial, message);
    }
}
