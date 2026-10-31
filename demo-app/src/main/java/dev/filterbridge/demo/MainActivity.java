package dev.filterbridge.demo;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import dev.filterbridge.android.ingest.ImagePreviewDecoder;
import dev.filterbridge.android.ingest.MediaPickerRepository;
import dev.filterbridge.android.ingest.PickedMedia;
import dev.filterbridge.core.MediaKind;
import dev.filterbridge.core.CompositeContentAnalyzer;
import dev.filterbridge.core.PolicyAction;
import dev.filterbridge.runtime.onnx.ViddexaNanoImageAnalyzer;
import dev.filterbridge.runtime.onnx.OwenXsImageSafetyAnalyzer;

import java.util.Arrays;

import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Main local-media test surface. All media I/O and decoding runs off the main thread. */
public final class MainActivity extends ComponentActivity {
    private static final String STATE_URI = "selected_uri";
    private static final String STATE_KIND = "selected_kind";
    private static final int COLOR_BACKGROUND = Color.rgb(246, 247, 250);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(25, 33, 48);
    private static final int COLOR_MUTED = Color.rgb(103, 113, 128);
    private static final int COLOR_PRIMARY = Color.rgb(44, 83, 180);
    private static final int COLOR_WARNING = Color.rgb(154, 91, 0);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "filterbridge-media");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicInteger selectionGeneration = new AtomicInteger();

    private ActivityResultLauncher<PickVisualMediaRequest> imagePicker;
    private ActivityResultLauncher<PickVisualMediaRequest> videoPicker;
    private MediaPickerRepository mediaRepository;
    private FilteringSettingsStore settingsStore;
    private MediaAnalysisService analysisService;

    private PickedMedia selectedMedia;
    private Bitmap imagePreviewBitmap;
    private Bitmap filteredPreviewBitmap;
    private boolean selectedUriPermissionPersisted;

    private ImageView imagePreview;
    private VideoView videoPreview;
    private TextView emptyPreview;
    private TextView mediaDetails;
    private TextView analysisStatus;
    private TextView selectionStatus;
    private Button analyzeButton;
    private Button clearButton;
    private Button revealButton;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaRepository = new MediaPickerRepository(this);
        settingsStore = new FilteringSettingsStore(this);
        // ONNX model weights are embedded by the GitHub CI export step. If they are missing,
        // the analyzer reports FAILED/UNVERIFIED rather than silently behaving as an empty model.
        analysisService = new MediaAnalysisService(new CompositeContentAnalyzer(Arrays.asList(
                new ViddexaNanoImageAnalyzer(getApplicationContext()),
                new OwenXsImageSafetyAnalyzer(getApplicationContext()))));
        registerPickers();
        buildUi();

        if (savedInstanceState != null) {
            restoreSelection(savedInstanceState);
        } else {
            showEmptyState("Choose a photo or video stored on your device.");
        }
    }

    private void registerPickers() {
        imagePicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) {
                setSelectionStatus("No photo selected. Your current selection was not changed.");
            } else {
                importSelectedUri(uri, MediaKind.IMAGE);
            }
        });
        videoPicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) {
                setSelectionStatus("No video selected. Your current selection was not changed.");
            } else {
                importSelectedUri(uri, MediaKind.VIDEO);
            }
        });
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(28));

        TextView title = text("FilterBridge", 29, COLOR_TEXT, true);
        page.addView(title);
        TextView subtitle = text("Private, on-device media filtering", 15, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(2));
        page.addView(subtitle);
        TextView buildLabel = text("Build " + appVersionName(), 11, COLOR_MUTED, false);
        buildLabel.setPadding(0, 0, 0, dp(18));
        page.addView(buildLabel);

        LinearLayout pickerCard = card();
        addSectionTitle(pickerCard, "Choose media", "Select a photo or video directly from your device.");
        LinearLayout pickerRow = new LinearLayout(this);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        Button photoButton = primaryButton("Choose photo");
        photoButton.setOnClickListener(v -> launchImagePicker());
        Button videoButton = secondaryButton("Choose video");
        videoButton.setOnClickListener(v -> launchVideoPicker());
        pickerRow.addView(photoButton, weightedParams());
        addHorizontalGap(pickerRow, 10);
        pickerRow.addView(videoButton, weightedParams());
        pickerCard.addView(pickerRow);
        page.addView(pickerCard, matchWrap());

        addVerticalGap(page, 14);
        LinearLayout previewCard = card();
        addSectionTitle(previewCard, "Preview", "The selected file is read locally. It is not uploaded.");
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(roundDrawable(Color.rgb(24, 29, 39), 16));
        previewFrame.setMinimumHeight(dp(250));

        emptyPreview = text("Your selected media will appear here", 14, Color.WHITE, false);
        emptyPreview.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        previewFrame.addView(emptyPreview, emptyParams);

        imagePreview = new ImageView(this);
        imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imagePreview.setContentDescription("Selected image preview");
        imagePreview.setAdjustViewBounds(true);
        imagePreview.setVisibility(View.GONE);
        previewFrame.addView(imagePreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(280)));

        videoPreview = new VideoView(this);
        videoPreview.setVisibility(View.GONE);
        videoPreview.setContentDescription("Selected video preview");
        previewFrame.addView(videoPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(250), Gravity.CENTER));
        previewCard.addView(previewFrame, matchWrap());
        revealButton = secondaryButton("Reveal once");
        revealButton.setVisibility(View.GONE);
        revealButton.setOnClickListener(v -> revealSelectedMediaOnce());
        LinearLayout.LayoutParams revealParams = matchWrap();
        revealParams.topMargin = dp(8);
        previewCard.addView(revealButton, revealParams);

        mediaDetails = text("No media selected", 13, COLOR_MUTED, false);
        mediaDetails.setPadding(0, dp(12), 0, dp(2));
        previewCard.addView(mediaDetails);
        page.addView(previewCard, matchWrap());

        addVerticalGap(page, 14);
        LinearLayout controls = card();
        addSectionTitle(controls, "Analysis", "Run the local pipeline against the selected media.");
        analyzeButton = primaryButton("Analyze on device");
        analyzeButton.setEnabled(false);
        analyzeButton.setOnClickListener(v -> analyzeSelectedMedia());
        controls.addView(analyzeButton, matchWrap());
        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(8);
        controls.addView(progressBar, progressParams);
        analysisStatus = text("Not analysed. No AI model has been run.", 14, COLOR_MUTED, false);
        analysisStatus.setPadding(0, dp(10), 0, 0);
        controls.addView(analysisStatus);

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        Button settingsButton = secondaryButton("Filtering settings");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        clearButton = secondaryButton("Clear");
        clearButton.setEnabled(false);
        clearButton.setOnClickListener(v -> clearSelection());
        secondaryActions.addView(settingsButton, weightedParams());
        addHorizontalGap(secondaryActions, 10);
        secondaryActions.addView(clearButton, weightedParams());
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(12);
        controls.addView(secondaryActions, actionsParams);
        page.addView(controls, matchWrap());

        addVerticalGap(page, 14);
        LinearLayout privacyCard = card();
        addSectionTitle(privacyCard, "Privacy", "Only the media you choose is accessible to this app.");
        TextView privacy = text("No account login. No network upload. No broad photo-library permission. " +
                "The selected content is processed in memory; this test build does not save media or analysis history.",
                12, COLOR_MUTED, false);
        privacyCard.addView(privacy);
        page.addView(privacyCard, matchWrap());

        selectionStatus = text("Ready", 12, COLOR_MUTED, false);
        selectionStatus.setPadding(dp(2), dp(12), dp(2), 0);
        page.addView(selectionStatus);

        scroll.addView(page);
        setContentView(scroll);
    }

    private void launchImagePicker() {
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        imagePicker.launch(request);
    }

    private void launchVideoPicker() {
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build();
        videoPicker.launch(request);
    }

    private void importSelectedUri(Uri uri, MediaKind expectedKind) {
        final int generation = selectionGeneration.incrementAndGet();
        setBusy(true);
        analysisStatus.setText("Opening selected media locally…");
        setSelectionStatus("Reading selected file metadata and preparing a preview.");

        boolean persisted = mediaRepository.tryTakePersistableReadPermission(uri);
        mediaExecutor.execute(() -> {
            PickedMedia candidate = null;
            Bitmap bitmap = null;
            try {
                candidate = mediaRepository.inspect(uri, expectedKind);
                if (expectedKind == MediaKind.IMAGE) {
                    bitmap = ImagePreviewDecoder.decode(getApplicationContext().getContentResolver(), uri);
                }
                final PickedMedia readyMedia = candidate;
                final Bitmap readyBitmap = bitmap;
                final boolean hasPersistedPermission = persisted;
                mainHandler.post(() -> {
                    if (generation != selectionGeneration.get() || isFinishing() || isDestroyed()) {
                        if (readyBitmap != null && !readyBitmap.isRecycled()) readyBitmap.recycle();
                        boolean stillSelected = selectedMedia != null
                                && selectedMedia.uri().equals(readyMedia.uri());
                        if (hasPersistedPermission && !stillSelected) {
                            mediaRepository.releasePersistableReadPermission(readyMedia.uri());
                        }
                        return;
                    }
                    boolean retainedExistingPermission = selectedMedia != null
                            && selectedMedia.uri().equals(readyMedia.uri()) && selectedUriPermissionPersisted;
                    releaseCurrentSelectionExcept(readyMedia.uri());
                    selectedMedia = readyMedia;
                    selectedUriPermissionPersisted = hasPersistedPermission || retainedExistingPermission;
                    imagePreviewBitmap = readyBitmap;
                    renderSelectedMedia();
                    setBusy(false);
                    analysisStatus.setTextColor(COLOR_MUTED);
                    analysisStatus.setText("Ready. No AI analysis has been performed yet.");
                    setSelectionStatus(isFilteringEnabled()
                            ? "Selected locally. The image preview is pixelated until analysis; use Reveal once to view it unfiltered."
                            : "Selected locally. Filtering is disabled in your settings; this image has not been analysed.");
                });
            } catch (Exception exception) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                boolean sameAsCurrent = selectedMedia != null && selectedMedia.uri().equals(uri);
                if (persisted && !sameAsCurrent) mediaRepository.releasePersistableReadPermission(uri);
                String message = safeMessage(exception);
                mainHandler.post(() -> {
                    if (generation != selectionGeneration.get() || isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    analysisStatus.setText("Import failed: " + message);
                    analysisStatus.setTextColor(COLOR_WARNING);
                    setSelectionStatus("The previous selection, if any, was kept.");
                });
            }
        });
    }

    private void renderSelectedMedia() {
        if (selectedMedia == null) return;
        clearFilteredPreview();
        revealButton.setVisibility(View.GONE);
        if (selectedMedia.kind() == MediaKind.IMAGE) {
            revealButton.setText("Reveal once");
            stopVideoPreview();
            videoPreview.setVisibility(View.GONE);
            emptyPreview.setVisibility(View.GONE);
            imagePreview.setVisibility(View.VISIBLE);
            imagePreview.setImageBitmap(imagePreviewBitmap);
            if (imagePreviewBitmap != null && isFilteringEnabled()) {
                // Do not expose a newly selected, unanalysed image before the detector runs.
                // The user can explicitly reveal it once, or run analysis first.
                showPixelatedImage();
                revealButton.setText("Reveal unfiltered image once");
                revealButton.setVisibility(View.VISIBLE);
            }
            String dimensionText = imagePreviewBitmap == null ? "" : "\nPreview: " +
                    imagePreviewBitmap.getWidth() + " × " + imagePreviewBitmap.getHeight() + " px";
            mediaDetails.setText(formatMediaDetails(selectedMedia) + dimensionText);
        } else {
            if (imagePreviewBitmap != null && !imagePreviewBitmap.isRecycled()) imagePreviewBitmap.recycle();
            imagePreviewBitmap = null;
            imagePreview.setImageDrawable(null);
            imagePreview.setVisibility(View.GONE);
            // No video inference exists in this release. Do not expose unfiltered playback by
            // default; require an explicit one-time reveal until a video pipeline is connected.
            emptyPreview.setText("Video analysis is not available yet. Playback is hidden until you explicitly reveal it.");
            emptyPreview.setVisibility(View.VISIBLE);
            videoPreview.setVisibility(View.GONE);
            revealButton.setText("Reveal unfiltered video once");
            revealButton.setVisibility(View.VISIBLE);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoPreview);
            videoPreview.setMediaController(controller);
            videoPreview.setOnPreparedListener(player -> player.setScreenOnWhilePlaying(true));
            videoPreview.setOnErrorListener((player, what, extra) -> {
                analysisStatus.setText("This video could not be previewed by the device media player.");
                analysisStatus.setTextColor(COLOR_WARNING);
                return true;
            });
            videoPreview.setVideoURI(selectedMedia.uri());
            mediaDetails.setText(formatMediaDetails(selectedMedia)
                    + "\nVideo frames and audio are not analysed in this release.");
        }
        analyzeButton.setEnabled(true);
        clearButton.setEnabled(true);
    }

    private String formatMediaDetails(PickedMedia item) {
        String size = item.sizeBytes() < 0 ? "Unknown size" : formatBytes(item.sizeBytes());
        return item.kind() + " · " + item.displayName() + "\n" + item.mimeType() + " · " + size;
    }

    private void analyzeSelectedMedia() {
        final PickedMedia media = selectedMedia;
        if (media == null) {
            analysisStatus.setText("Choose a photo or video first.");
            return;
        }
        final int generation = selectionGeneration.get();
        setBusy(true);
        analysisStatus.setTextColor(COLOR_MUTED);
        analysisStatus.setText(media.kind() == MediaKind.VIDEO
                ? "Checking video support. Still-image inference is not available for video yet…"
                : "Running Viddexa Nano image inference locally…");

        try {
            mediaExecutor.execute(() -> {
                final MediaAnalysisService.Report report;
                try {
                    report = analysisService.analyze(
                            getApplicationContext(), media, settingsStore.buildPolicy());
                } catch (RuntimeException exception) {
                    String detail = safeMessage(exception);
                    mainHandler.post(() -> {
                        if (generation != selectionGeneration.get() || isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        analysisStatus.setText("Analysis could not complete: " + detail + "\nContent remains UNVERIFIED.");
                        analysisStatus.setTextColor(COLOR_WARNING);
                        applyPolicyActionToPreview(PolicyAction.UNVERIFIED);
                    });
                    return;
                }
                mainHandler.post(() -> {
                    if (generation != selectionGeneration.get() || isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    analysisStatus.setText(report.message());
                    analysisStatus.setTextColor(report.action() == PolicyAction.ALLOW
                            ? COLOR_PRIMARY : COLOR_WARNING);
                    applyPolicyActionToPreview(report.action());
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            setBusy(false);
            analysisStatus.setText("Analysis is unavailable because the screen is closing. Content remains UNVERIFIED.");
            analysisStatus.setTextColor(COLOR_WARNING);
        }
    }

    /**
     * This classifier emits whole-image scores, not localization boxes. BLUR therefore
     * pixelates the full image. Unsupported media (including video in this release) is
     * kept hidden until the user explicitly chooses Reveal once.
     */
    private boolean isFilteringEnabled() {
        return !settingsStore.buildPolicy().requiredCategories().isEmpty();
    }

    private void applyPolicyActionToPreview(PolicyAction action) {
        if (selectedMedia == null) return;
        clearFilteredPreview();
        revealButton.setVisibility(View.GONE);
        emptyPreview.setVisibility(View.GONE);

        if (selectedMedia.kind() == MediaKind.VIDEO) {
            stopVideoPreview();
            if (action == PolicyAction.ALLOW) {
                // The user explicitly disabled all filtering categories. Honour that preference,
                // while still making clear that this release did not analyse the video.
                videoPreview.setVisibility(View.VISIBLE);
                emptyPreview.setVisibility(View.GONE);
                revealButton.setVisibility(View.GONE);
                setSelectionStatus("Filtering is disabled in your settings. This video was not analysed.");
                return;
            }
            videoPreview.setVisibility(View.GONE);
            switch (action) {
                case WARN:
                    emptyPreview.setText("Video analysis is not available yet. The video has not been checked. Choose Reveal once to preview it without filtering.");
                    break;
                case UNVERIFIED:
                case BLUR:
                case HIDE:
                default:
                    emptyPreview.setText("Video is UNVERIFIED. This version does not analyse video frames or audio. Playback is hidden until you explicitly choose Reveal once.");
                    break;
            }
            emptyPreview.setVisibility(View.VISIBLE);
            revealButton.setText("Reveal unfiltered video once");
            revealButton.setVisibility(View.VISIBLE);
            setSelectionStatus("Video analysis is not implemented in this release. No claim of safety is made.");
            return;
        }

        if (selectedMedia.kind() != MediaKind.IMAGE
                || imagePreviewBitmap == null || imagePreviewBitmap.isRecycled()) {
            emptyPreview.setText("This media type is not analysed in this release and remains UNVERIFIED.");
            emptyPreview.setVisibility(View.VISIBLE);
            revealButton.setVisibility(View.GONE);
            setSelectionStatus("Unsupported media remains UNVERIFIED.");
            return;
        }

        switch (action) {
            case BLUR:
                showPixelatedImage();
                revealButton.setVisibility(View.VISIBLE);
                setSelectionStatus("The policy pixelated the entire image. This classifier does not localize individual regions.");
                break;
            case HIDE:
                imagePreview.setVisibility(View.GONE);
                emptyPreview.setText("Filtered content hidden by your settings.");
                emptyPreview.setVisibility(View.VISIBLE);
                revealButton.setText("Reveal once");
                revealButton.setVisibility(View.VISIBLE);
                setSelectionStatus("The policy hid this image. You can explicitly reveal it once.");
                break;
            case WARN:
                imagePreview.setImageBitmap(imagePreviewBitmap);
                imagePreview.setVisibility(View.VISIBLE);
                setSelectionStatus("The model produced a warning-level signal. Review the analysis details.");
                break;
            case ALLOW:
                imagePreview.setImageBitmap(imagePreviewBitmap);
                imagePreview.setVisibility(View.VISIBLE);
                setSelectionStatus("No configured detection threshold was triggered. Category coverage is reported separately.");
                break;
            case UNVERIFIED:
            default:
                // Balanced default: incomplete category coverage is uncertainty, not a safe result.
                showPixelatedImage();
                revealButton.setText("Reveal once");
                revealButton.setVisibility(View.VISIBLE);
                setSelectionStatus("Content is UNVERIFIED because this model does not cover every enabled category. The whole image is pixelated until you explicitly reveal it.");
                break;
        }
    }

    private void showPixelatedImage() {
        if (imagePreviewBitmap == null || imagePreviewBitmap.isRecycled()) return;
        clearFilteredPreview();
        int sourceWidth = imagePreviewBitmap.getWidth();
        int sourceHeight = imagePreviewBitmap.getHeight();
        float scale = Math.min(1.0f, Math.min(48.0f / sourceWidth, 48.0f / sourceHeight));
        if (scale >= 1.0f) scale = 0.5f;
        int smallWidth = Math.max(1, Math.round(sourceWidth * scale));
        int smallHeight = Math.max(1, Math.round(sourceHeight * scale));
        filteredPreviewBitmap = Bitmap.createScaledBitmap(
                imagePreviewBitmap, smallWidth, smallHeight, false);
        android.graphics.drawable.BitmapDrawable pixelated =
                new android.graphics.drawable.BitmapDrawable(getResources(), filteredPreviewBitmap);
        pixelated.setFilterBitmap(false);
        imagePreview.setImageDrawable(pixelated);
        imagePreview.setVisibility(View.VISIBLE);
        emptyPreview.setVisibility(View.GONE);
    }

    private void revealSelectedMediaOnce() {
        if (selectedMedia == null) return;
        clearFilteredPreview();
        emptyPreview.setVisibility(View.GONE);
        revealButton.setVisibility(View.GONE);
        if (selectedMedia.kind() == MediaKind.IMAGE
                && imagePreviewBitmap != null && !imagePreviewBitmap.isRecycled()) {
            imagePreview.setImageBitmap(imagePreviewBitmap);
            imagePreview.setVisibility(View.VISIBLE);
            setSelectionStatus("You explicitly revealed this image for the current viewing session. Run analysis again to reapply filtering.");
            return;
        }
        if (selectedMedia.kind() == MediaKind.VIDEO) {
            videoPreview.setVisibility(View.VISIBLE);
            setSelectionStatus("You explicitly revealed this unfiltered video for the current viewing session. Video analysis is not implemented yet.");
        }
    }

    private void clearFilteredPreview() {
        if (filteredPreviewBitmap == null) return;
        if (imagePreview != null && imagePreview.getDrawable()
                instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.drawable.BitmapDrawable drawable =
                    (android.graphics.drawable.BitmapDrawable) imagePreview.getDrawable();
            if (drawable.getBitmap() == filteredPreviewBitmap) {
                imagePreview.setImageDrawable(null);
            }
        }
        if (!filteredPreviewBitmap.isRecycled()) filteredPreviewBitmap.recycle();
        filteredPreviewBitmap = null;
    }

    private void clearSelection() {
        selectionGeneration.incrementAndGet();
        releaseCurrentSelection();
        stopVideoPreview();
        clearFilteredPreview();
        imagePreview.setImageDrawable(null);
        if (imagePreviewBitmap != null && !imagePreviewBitmap.isRecycled()) imagePreviewBitmap.recycle();
        imagePreviewBitmap = null;
        imagePreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);
        emptyPreview.setVisibility(View.VISIBLE);
        emptyPreview.setText("Your selected media will appear here");
        revealButton.setVisibility(View.GONE);
        mediaDetails.setText("No media selected");
        analysisStatus.setText("Not analysed. No media is currently selected.");
        analysisStatus.setTextColor(COLOR_MUTED);
        analyzeButton.setEnabled(false);
        clearButton.setEnabled(false);
        setBusy(false);
        setSelectionStatus("Selection cleared. Nothing was uploaded or saved.");
    }

    private void releaseCurrentSelection() {
        releaseCurrentSelectionExcept(null);
    }

    private void releaseCurrentSelectionExcept(Uri keepPermissionFor) {
        if (selectedMedia != null && selectedUriPermissionPersisted
                && (keepPermissionFor == null || !selectedMedia.uri().equals(keepPermissionFor))) {
            mediaRepository.releasePersistableReadPermission(selectedMedia.uri());
        }
        selectedMedia = null;
        selectedUriPermissionPersisted = false;
        clearFilteredPreview();
        if (imagePreview != null && imagePreview.getDrawable()
                instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.drawable.BitmapDrawable drawable =
                    (android.graphics.drawable.BitmapDrawable) imagePreview.getDrawable();
            if (drawable.getBitmap() == imagePreviewBitmap) imagePreview.setImageDrawable(null);
        }
        if (imagePreviewBitmap != null && !imagePreviewBitmap.isRecycled()) imagePreviewBitmap.recycle();
        imagePreviewBitmap = null;
    }

    private void stopVideoPreview() {
        try { videoPreview.stopPlayback(); }
        catch (RuntimeException ignored) { }
    }

    private void showEmptyState(String message) {
        emptyPreview.setVisibility(View.VISIBLE);
        imagePreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);
        mediaDetails.setText("No media selected");
        analysisStatus.setText("Not analysed. No media is currently selected.");
        analyzeButton.setEnabled(false);
        clearButton.setEnabled(false);
        setSelectionStatus(message);
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        analyzeButton.setEnabled(!busy && selectedMedia != null);
        clearButton.setEnabled(!busy && selectedMedia != null);
    }

    private void setSelectionStatus(String value) {
        selectionStatus.setText(value);
    }

    private void restoreSelection(Bundle state) {
        String uriText = state.getString(STATE_URI);
        String kindName = state.getString(STATE_KIND);
        if (uriText == null || kindName == null) {
            showEmptyState("Choose a photo or video stored on your device.");
            return;
        }
        try {
            importSelectedUri(Uri.parse(uriText), MediaKind.valueOf(kindName));
        } catch (Exception exception) {
            showEmptyState("The previous selection is no longer accessible. Please choose it again.");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (selectedMedia != null) {
            outState.putString(STATE_URI, selectedMedia.uri().toString());
            outState.putString(STATE_KIND, selectedMedia.kind().name());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        if (videoPreview != null && videoPreview.isPlaying()) videoPreview.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        selectionGeneration.incrementAndGet();
        if (videoPreview != null) stopVideoPreview();
        if (!isChangingConfigurations()) releaseCurrentSelection();
        // Release the ONNX session after any active inference on the single worker has finished.
        if (analysisService != null) {
            try {
                mediaExecutor.execute(() -> analysisService.close());
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // The executor is already stopping; the OS will reclaim this process-local session.
            }
        }
        mediaExecutor.shutdown();
        clearFilteredPreview();
        if (imagePreviewBitmap != null && !imagePreviewBitmap.isRecycled()) imagePreviewBitmap.recycle();
        super.onDestroy();
    }

    private void showSettingsDialog() {
        new FilteringSettingsDialog(this, settingsStore, this::setSelectionStatus).show();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(roundDrawable(COLOR_CARD, 16));
        return layout;
    }

    private void addSectionTitle(LinearLayout parent, String title, String subtitle) {
        TextView heading = text(title, 17, COLOR_TEXT, true);
        parent.addView(heading);
        TextView description = text(subtitle, 12, COLOR_MUTED, false);
        description.setPadding(0, dp(4), 0, dp(12));
        parent.addView(description);
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_PRIMARY));
        button.setMinHeight(dp(48));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(COLOR_TEXT);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(232, 236, 243)));
        button.setMinHeight(dp(46));
        return button;
    }

    private GradientDrawable roundDrawable(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void addVerticalGap(LinearLayout parent, int heightDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private void addHorizontalGap(LinearLayout parent, int widthDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(dp(widthDp), 1));
    }

    private String appVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "0.3.1-local-image-classifier" : info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "0.3.1-local-image-classifier";
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String formatBytes(long size) {
        if (size < 1024) return size + " B";
        double kb = size / 1024.0d;
        if (kb < 1024) return new DecimalFormat("#,##0.#").format(kb) + " KB";
        return new DecimalFormat("#,##0.#").format(kb / 1024.0d) + " MB";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }
}
