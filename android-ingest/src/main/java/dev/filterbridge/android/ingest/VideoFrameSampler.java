package dev.filterbridge.android.ingest;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.net.Uri;

/**
 * Bounded local video preflight. It extracts and immediately releases a small number of frames.
 * No frame is persisted, uploaded, or considered classified by this utility.
 */
public final class VideoFrameSampler {
    private static final int MAX_FRAMES = 6;
    private static final int MAX_FRAME_EDGE = 640;

    private VideoFrameSampler() { }

    public static Summary sample(Context context, Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        int sampled = 0;
        long durationMs = -1L;
        int width = -1;
        int height = -1;
        try {
            retriever.setDataSource(context.getApplicationContext(), uri);
            durationMs = parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION), -1L);
            width = (int) parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), -1L);
            height = (int) parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), -1L);

            int count = durationMs > 0L ? MAX_FRAMES : 1;
            for (int index = 0; index < count; index++) {
                long timeUs;
                if (durationMs > 0L) {
                    // Sample evenly inside the timeline, avoiding exact end-of-file boundaries.
                    double fraction = (index + 1.0d) / (count + 1.0d);
                    timeUs = Math.max(0L, (long) (durationMs * fraction * 1000.0d));
                } else {
                    timeUs = -1L;
                }
                Bitmap frame = null;
                try {
                    frame = extractBoundedFrame(retriever, timeUs, width, height);
                    if (frame != null) {
                        // Bound the live working set even on high-resolution source videos.
                        int edge = Math.max(frame.getWidth(), frame.getHeight());
                        if (edge > MAX_FRAME_EDGE) {
                            float scale = MAX_FRAME_EDGE / (float) edge;
                            Bitmap scaled = Bitmap.createScaledBitmap(frame,
                                    Math.max(1, Math.round(frame.getWidth() * scale)),
                                    Math.max(1, Math.round(frame.getHeight() * scale)), true);
                            if (scaled != frame) frame.recycle();
                            frame = scaled;
                        }
                        sampled++;
                    }
                } finally {
                    if (frame != null && !frame.isRecycled()) frame.recycle();
                }
            }
            return new Summary(durationMs, width, height, sampled);
        } finally {
            retriever.release();
        }
    }

    private static Bitmap extractBoundedFrame(MediaMetadataRetriever retriever,
                                               long timeUs, int sourceWidth, int sourceHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1
                || sourceWidth <= 0 || sourceHeight <= 0) {
            // Never decode an unknown/high-resolution source frame at full size. On API 26,
            // or when metadata is unavailable, this preflight abstains rather than risk OOM.
            return null;
        }
        int sourceEdge = Math.max(sourceWidth, sourceHeight);
        float scale = Math.min(1.0f, MAX_FRAME_EDGE / (float) sourceEdge);
        int targetWidth = Math.max(1, Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, Math.round(sourceHeight * scale));
        return retriever.getScaledFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight);
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public static final class Summary {
        private final long durationMs;
        private final int width;
        private final int height;
        private final int sampledFrames;

        Summary(long durationMs, int width, int height, int sampledFrames) {
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.sampledFrames = sampledFrames;
        }
        public long durationMs() { return durationMs; }
        public int width() { return width; }
        public int height() { return height; }
        public int sampledFrames() { return sampledFrames; }
    }
}
