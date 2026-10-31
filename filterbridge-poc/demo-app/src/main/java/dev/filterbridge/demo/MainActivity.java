package dev.filterbridge.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.filterbridge.android.ingest.ShareIntentParser;
import dev.filterbridge.core.AnalysisRequest;
import dev.filterbridge.core.FilteringEngine;
import dev.filterbridge.core.FilteringOutcome;
import dev.filterbridge.core.FilteringPolicy;
import dev.filterbridge.core.InboundContent;
import dev.filterbridge.core.MediaAttachment;
import dev.filterbridge.core.NotConfiguredAnalyzer;
import dev.filterbridge.core.PolicyEngine;

public final class MainActivity extends Activity {
    private ShareIntentParser shareParser;
    private InboundContent inboundContent;
    private TextView contentView;
    private TextView resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shareParser = new ShareIntentParser(this);
        buildUi();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private void buildUi() {
        int padding = dp(20);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("FilterBridge Lab");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        column.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Offline filtering SDK · Share/import proof of concept");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        column.addView(subtitle);

        contentView = new TextView(this);
        contentView.setTextSize(15);
        contentView.setTextIsSelectable(true);
        contentView.setPadding(dp(12), dp(12), dp(12), dp(12));
        contentView.setBackgroundColor(0xFFF0F2F5);
        column.addView(contentView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button analyzeButton = new Button(this);
        analyzeButton.setText("Run filtering pipeline");
        analyzeButton.setOnClickListener(v -> runFilteringPipeline());
        column.addView(analyzeButton);

        Button clearButton = new Button(this);
        clearButton.setText("Clear imported content");
        clearButton.setOnClickListener(v -> {
            inboundContent = null;
            resultView.setText("No content is currently loaded.");
            contentView.setText("Share text, an image, a video, or audio from another app to begin.");
        });
        column.addView(clearButton);

        resultView = new TextView(this);
        resultView.setTextSize(14);
        resultView.setPadding(0, dp(12), 0, 0);
        column.addView(resultView);

        TextView privacy = new TextView(this);
        privacy.setText("Prototype privacy: no INTERNET permission, no upload, and no media file copy. " +
                "Shared media is represented by a lazy content stream and is not opened until an analyzer requests it.");
        privacy.setTextSize(12);
        privacy.setPadding(0, dp(24), 0, dp(8));
        column.addView(privacy);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private void handleIncoming(android.content.Intent intent) {
        // ACTION_MAIN is the normal launcher action, not a failed share.
        if (intent == null
                || (!android.content.Intent.ACTION_SEND.equals(intent.getAction())
                && !android.content.Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
            inboundContent = null;
            contentView.setText("Ready to receive content. Share text, an image, a video, or audio from another app and choose FilterBridge Lab.");
            resultView.setText("Waiting for shared content.");
            return;
        }
        try {
            inboundContent = shareParser.parse(intent);
            renderInboundContent();
        } catch (IllegalArgumentException ex) {
            inboundContent = null;
            contentView.setText("This share could not be imported: " + ex.getMessage());
            resultView.setText("No analysis was performed.");
        } catch (RuntimeException ex) {
            inboundContent = null;
            contentView.setText("The shared content could not be read safely.");
            resultView.setText("No analysis was performed. Details: " + ex.getClass().getSimpleName());
        }
    }

    private void renderInboundContent() {
        if (inboundContent == null) return;
        StringBuilder report = new StringBuilder();
        report.append("Import source: ").append(inboundContent.importOrigin()).append('\n');
        report.append("Platform hint: ").append(inboundContent.platformHint()).append('\n');
        report.append("Attachments: ").append(inboundContent.attachments().size()).append("\n\n");
        if (!inboundContent.text().isEmpty()) {
            report.append("Shared text / URL:\n").append(inboundContent.text()).append("\n\n");
        }
        for (int i = 0; i < inboundContent.attachments().size(); i++) {
            MediaAttachment attachment = inboundContent.attachments().get(i);
            report.append(i + 1).append(". ")
                    .append(attachment.kind()).append(" · ")
                    .append(attachment.source().displayName()).append('\n')
                    .append("   MIME: ").append(attachment.source().mimeType()).append('\n')
                    .append("   Size: ").append(formatBytes(attachment.source().sizeBytes())).append("\n\n");
        }
        if (inboundContent.text().isEmpty() && inboundContent.attachments().isEmpty()) {
            report.append("The sender did not provide readable text or media attachments.\n");
        }
        contentView.setText(report.toString());
        resultView.setText("Imported. No model has run yet; this content is not marked safe.");
    }

    private void runFilteringPipeline() {
        if (inboundContent == null) {
            resultView.setText("Import content first.");
            return;
        }
        FilteringEngine engine = new FilteringEngine(new NotConfiguredAnalyzer(), new PolicyEngine());
        FilteringOutcome outcome = engine.analyze(new AnalysisRequest(inboundContent),
                FilteringPolicy.balancedDefault());
        resultView.setText("Analysis status: " + outcome.analysis().status() + "\n" +
                "Policy action: " + outcome.decision().action() + "\n" +
                "Result: " + outcome.decision().explanation() + "\n\n" +
                "Important: the model runtime is deliberately not connected in this milestone. " +
                "UNVERIFIED is not the same as ALLOW.");
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
