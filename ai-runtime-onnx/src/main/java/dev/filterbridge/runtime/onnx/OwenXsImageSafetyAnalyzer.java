package dev.filterbridge.runtime.onnx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.filterbridge.core.AnalysisRequest;
import dev.filterbridge.core.AnalysisResult;
import dev.filterbridge.core.ContentAnalyzer;
import dev.filterbridge.core.MediaAttachment;
import dev.filterbridge.core.MediaKind;
import dev.filterbridge.core.OwenXsOutputMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local second-opinion image classifier. Its NSFW class is broad, not a nude/suggestive distinction. */
public final class OwenXsImageSafetyAnalyzer implements ContentAnalyzer, AutoCloseable {
    private static final int EDGE = 224;
    private static final int MAX_SOURCE_EDGE = 2048;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private final Context context;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private OrtEnvironment environment;
    private OrtSession session;
    private String startupFailure;
    private boolean initialized;

    public OwenXsImageSafetyAnalyzer(Context context) { this.context = context.getApplicationContext(); }

    @Override public synchronized AnalysisResult analyze(AnalysisRequest request) {
        if (closed.get()) return AnalysisResult.failed("Owen XS analyzer has been closed.");
        if (request == null || request.content().attachments().isEmpty()) {
            return AnalysisResult.notAnalyzed("Owen XS received no media attachment.");
        }
        List<MediaAttachment> attachments = request.content().attachments();
        if (attachments.size() != 1 || attachments.get(0).kind() != MediaKind.IMAGE) {
            return AnalysisResult.notAnalyzed("Owen XS analyses one still image per request; video/audio are not supported by this model.");
        }
        ensureInitialized();
        if (startupFailure != null || session == null || environment == null) {
            return AnalysisResult.failed("Owen XS model is unavailable: " + startupFailure);
        }
        Bitmap decoded = null; Bitmap resized = null;
        try {
            decoded = decodeBounded(attachments.get(0));
            // Model-card preprocessing: RGB, bilinear 224x224, float32 [0,255], NCHW.
            resized = Bitmap.createScaledBitmap(decoded, EDGE, EDGE, true);
            if (resized != decoded) { decoded.recycle(); decoded = null; }
            float[] input = toNchwRgb255(resized);
            float[] probabilities = infer(input);
            return OwenXsOutputMapper.toAnalysisResult(probabilities);
        } catch (Exception ex) {
            return AnalysisResult.failed("Owen XS inference failed: " + safeMessage(ex));
        } finally {
            if (resized != null && !resized.isRecycled()) resized.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;
        try {
            OwenXsModelAssetInstaller.InstalledModel model = OwenXsModelAssetInstaller.install(context);
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                int cpus = Runtime.getRuntime().availableProcessors();
                options.setIntraOpNumThreads(Math.max(1, Math.min(4, cpus - 1)));
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(model.file.getAbsolutePath(), options);
            }
            if (!session.getInputNames().contains("image") || session.getOutputNames().size() != 1) {
                throw new IOException("Unexpected Owen XS ONNX input/output signature.");
            }
        } catch (Exception ex) {
            startupFailure = safeMessage(ex);
            if (session != null) { try { session.close(); } catch (OrtException ignored) { } }
            session = null;
        }
    }

    private float[] infer(float[] values) throws OrtException {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values); buffer.rewind();
        long[] shape = {1L, 3L, EDGE, EDGE};
        String outputName = session.getOutputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer, shape);
             OrtSession.Result result = session.run(Collections.singletonMap("image", tensor))) {
            Object raw = result.get(0).getValue();
            if (!(raw instanceof float[][])) throw new IllegalStateException("Unexpected Owen XS output tensor.");
            float[][] rows = (float[][]) raw;
            if (rows.length != 1 || rows[0] == null || rows[0].length != 3) {
                throw new IllegalStateException("Owen XS output shape is not [1,3].");
            }
            return rows[0].clone();
        }
    }

    private static float[] toNchwRgb255(Bitmap bitmap) {
        int plane = EDGE * EDGE;
        int[] pixels = new int[plane];
        bitmap.getPixels(pixels, 0, EDGE, 0, 0, EDGE, EDGE);
        float[] result = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int pixel = pixels[i];
            result[i] = (pixel >> 16) & 0xff;
            result[plane + i] = (pixel >> 8) & 0xff;
            result[2 * plane + i] = pixel & 0xff;
        }
        return result;
    }

    private static Bitmap decodeBounded(MediaAttachment attachment) throws IOException {
        int orientation = readOrientation(attachment);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream input = attachment.source().openStream()) { BitmapFactory.decodeStream(input, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Selected image cannot be decoded.");
        long pixels = (long) bounds.outWidth * bounds.outHeight;
        if (pixels > MAX_SOURCE_PIXELS) throw new IOException("Image exceeds the 100-megapixel safety limit.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inDither = false;
        Bitmap bitmap;
        try (InputStream input = attachment.source().openStream()) { bitmap = BitmapFactory.decodeStream(input, null, options); }
        if (bitmap == null) throw new IOException("Android returned no decoded bitmap.");
        return orient(bitmap, orientation);
    }

    private static int readOrientation(MediaAttachment attachment) {
        try (InputStream input = attachment.source().openStream()) {
            if (input == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { return ExifInterface.ORIENTATION_NORMAL; }
    }
    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > MAX_SOURCE_EDGE && sample < 64) sample *= 2;
        return sample;
    }
    private static Bitmap orient(Bitmap source, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1f, -1f); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90f); m.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90f); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90f); m.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(270f); break;
            default: return source;
        }
        try { Bitmap fixed = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
            if (fixed != source) source.recycle(); return fixed;
        } catch (RuntimeException ex) { return source; }
    }
    private static String safeMessage(Exception ex) {
        String message = ex.getMessage(); return message == null || message.trim().isEmpty()
                ? ex.getClass().getSimpleName() : message;
    }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (session != null) { try { session.close(); } catch (OrtException ignored) { } session = null; }
    }
}
