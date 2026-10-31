package dev.filterbridge.demo;

import android.content.Context;

import dev.filterbridge.android.ingest.PickedMedia;
import dev.filterbridge.core.AnalysisRequest;
import dev.filterbridge.core.Detection;
import dev.filterbridge.core.FilteringEngine;
import dev.filterbridge.core.FilteringOutcome;
import dev.filterbridge.core.FilteringPolicy;
import dev.filterbridge.core.ImportOrigin;
import dev.filterbridge.core.InboundContent;
import dev.filterbridge.core.ContentAnalyzer;
import dev.filterbridge.core.PolicyAction;
import dev.filterbridge.core.PolicyEngine;

import java.util.Collections;
import java.util.Locale;

/** Background model execution and deterministic policy orchestration. */
public final class MediaAnalysisService implements AutoCloseable {
    private final FilteringEngine filteringEngine;

    public MediaAnalysisService(ContentAnalyzer analyzer) {
        this.filteringEngine = new FilteringEngine(analyzer, new PolicyEngine());
    }

    public Report analyze(Context context, PickedMedia media, FilteringPolicy policy) {
        InboundContent content = new InboundContent("",
                Collections.singletonList(media.attachment()), ImportOrigin.FILE_PICKER,
                dev.filterbridge.core.ContentSourcePlatform.UNKNOWN);
        FilteringOutcome outcome = filteringEngine.analyze(new AnalysisRequest(content), policy);

        StringBuilder text = new StringBuilder();
        text.append("ANALYSIS: ").append(outcome.analysis().status()).append('\n');
        text.append("POLICY ACTION: ").append(outcome.decision().action()).append('\n');
        text.append("CATEGORY COVERAGE: ")
                .append(outcome.decision().analysisComplete() ? "COMPLETE" : "INCOMPLETE")
                .append('\n');
        text.append(outcome.decision().explanation()).append("\n\n");
        if (!outcome.analysis().message().isEmpty()) {
            text.append(outcome.analysis().message()).append("\n\n");
        }
        if (!outcome.analysis().detections().isEmpty()) {
            text.append("MODEL SIGNALS (scores are not calibrated guarantees):\n");
            for (Detection detection : outcome.analysis().detections()) {
                text.append("• ").append(detection.category().value())
                        .append(" / ").append(detection.modelLabel() == null
                                ? "unlabelled" : detection.modelLabel())
                        .append(" — ")
                        .append(String.format(Locale.ROOT, "%.1f%%", detection.confidence() * 100.0d));
                if (detection.modelId() != null) {
                    text.append(" [").append(detection.modelId()).append(']');
                }
                text.append('\n');
            }
            text.append('\n');
        }
        if (media.kind() == dev.filterbridge.core.MediaKind.VIDEO) {
            text.append("VIDEO: NOT ANALYSED. This release has no video-frame inference yet. ")
                    .append("The video preview is not a content-safety check.\n\n");
        } else {
            text.append("IMAGE: analysed locally as a whole image. This classifier does not return region boxes.\n\n");
        }
        text.append("IMPORTANT: this experimental model is not a complete content-safety system. ")
                .append("Only its listed signals were evaluated; other enabled categories remain UNVERIFIED. ")
                .append("No media was uploaded.");
        return new Report(text.toString(), outcome.analysis().status().name(),
                outcome.decision().action().name(), outcome.decision().action());
    }

    @Override
    public void close() {
        filteringEngine.close();
    }

    public static final class Report {
        private final String message;
        private final String analysisStatus;
        private final String policyAction;
        private final PolicyAction action;

        Report(String message, String analysisStatus, String policyAction, PolicyAction action) {
            this.message = message;
            this.analysisStatus = analysisStatus;
            this.policyAction = policyAction;
            this.action = action;
        }

        public String message() { return message; }
        public String analysisStatus() { return analysisStatus; }
        public String policyAction() { return policyAction; }
        public PolicyAction action() { return action; }
    }
}
