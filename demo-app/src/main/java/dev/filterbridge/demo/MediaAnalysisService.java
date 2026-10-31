package dev.filterbridge.demo;

import android.content.Context;

import dev.filterbridge.android.ingest.PickedMedia;
import dev.filterbridge.android.ingest.VideoFrameSampler;
import dev.filterbridge.core.AnalysisRequest;
import dev.filterbridge.core.FilteringEngine;
import dev.filterbridge.core.FilteringOutcome;
import dev.filterbridge.core.FilteringPolicy;
import dev.filterbridge.core.ImportOrigin;
import dev.filterbridge.core.InboundContent;
import dev.filterbridge.core.ContentAnalyzer;
import dev.filterbridge.core.PolicyEngine;

import java.util.Collections;
import java.util.Locale;

/** Background media preflight plus policy orchestration. Does not claim to classify content. */
public final class MediaAnalysisService {
    private final FilteringEngine filteringEngine;

    public MediaAnalysisService(ContentAnalyzer analyzer) {
        this.filteringEngine = new FilteringEngine(analyzer, new PolicyEngine());
    }

    public Report analyze(Context context, PickedMedia media, FilteringPolicy policy) {
        VideoFrameSampler.Summary videoSummary = null;
        String preflightFailure = null;
        if (media.kind() == dev.filterbridge.core.MediaKind.VIDEO) {
            try {
                videoSummary = VideoFrameSampler.sample(context.getApplicationContext(), media.uri());
                if (videoSummary.sampledFrames() == 0) {
                    preflightFailure = "No video frames could be extracted from this file.";
                }
            } catch (Exception exception) {
                preflightFailure = safeMessage(exception);
            }
        }

        InboundContent content = new InboundContent("",
                Collections.singletonList(media.attachment()), ImportOrigin.FILE_PICKER,
                dev.filterbridge.core.ContentSourcePlatform.UNKNOWN);
        FilteringOutcome outcome = filteringEngine.analyze(new AnalysisRequest(content), policy);

        StringBuilder text = new StringBuilder();
        text.append("STATUS: ").append(outcome.analysis().status()).append('\n');
        text.append("POLICY: ").append(outcome.decision().action()).append('\n');
        text.append("CATEGORY COVERAGE: ")
                .append(outcome.decision().analysisComplete() ? "COMPLETE" : "INCOMPLETE")
                .append('\n');
        text.append(outcome.decision().explanation()).append("\n\n");
        if (preflightFailure != null) {
            text.append("Media preflight: ").append(preflightFailure).append("\n\n");
        } else if (videoSummary != null) {
            text.append("Local video preflight: ").append(videoSummary.sampledFrames())
                    .append(" frame samples extracted and discarded.\n");
            if (videoSummary.durationMs() >= 0) {
                text.append("Duration: ").append(formatDuration(videoSummary.durationMs())).append('\n');
            }
            if (videoSummary.width() > 0 && videoSummary.height() > 0) {
                text.append("Source: ").append(videoSummary.width()).append(" × ")
                        .append(videoSummary.height()).append('\n');
            }
        } else {
            text.append("Image preview was decoded locally.\n");
        }
        text.append("\nNo on-device content-classification model is installed in this build. ")
                .append("The media has NOT been classified and must not be treated as safe.");
        return new Report(text.toString(), outcome.analysis().status().name(),
                outcome.decision().action().name());
    }

    private static String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", totalSeconds / 3600L,
                (totalSeconds / 60L) % 60L, totalSeconds % 60L);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    public static final class Report {
        private final String message;
        private final String analysisStatus;
        private final String policyAction;

        Report(String message, String analysisStatus, String policyAction) {
            this.message = message;
            this.analysisStatus = analysisStatus;
            this.policyAction = policyAction;
        }
        public String message() { return message; }
        public String analysisStatus() { return analysisStatus; }
        public String policyAction() { return policyAction; }
    }
}
