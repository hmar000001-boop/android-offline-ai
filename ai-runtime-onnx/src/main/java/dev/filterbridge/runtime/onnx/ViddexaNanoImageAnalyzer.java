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
import dev.filterbridge.core.AnalysisStatus;
import dev.filterbridge.core.CategoryId;
import dev.filterbridge.core.ContentAnalyzer;
import dev.filterbridge.core.EvidenceType;
import dev.filterbridge.core.MediaAttachment;
import dev.filterbridge.core.MediaKind;
import dev.filterbridge.core.ViddexaNanoOutputMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline still-image classifier using a checksum-verified, CI-exported ONNX model.
 * This adapter intentionally does not claim full category coverage; see the mapper's model-card
 * caveat. Video analysis is not implemented by this adapter.
 */
public final class ViddexaNanoImageAnalyzer implements ContentAnalyzer, AutoCloseable {
    private static final int MAX_SOURCE_EDGE = 2048;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final String INPUT_NAME = "pixel_values";

    private final Context applicationContext;
    private OrtEnvironment environment;
    private OrtSession session;
    private Properties manifest;
    private String startupFailure;
    private boolean initialized;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Construction is deliberately cheap; model install/session creation happens on the worker thread. */
    public ViddexaNanoImageAnalyzer(Context context) {
        this.applicationContext = context.getApplicationContext();
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;
        try {
            OnnxModelAssetInstaller.InstalledModel installed =
                    OnnxModelAssetInstaller.install(applicationContext);
            manifest = installed.manifest;
            if (!ViddexaNanoOutputMapper.MODEL_ID.equals(manifest.getProperty("modelId"))) {
                throw new IOException("The bundled ONNX model identity does not match this adapter.");
            }
            if (!ViddexaNanoOutputMapper.MODEL_REVISION.equals(manifest.getProperty("revision"))) {
                throw new IOException("The bundled ONNX model revision does not match this adapter.");
            }
            if (!"safe,hentai,porn,sexy,drawing".equals(required("labels"))) {
                throw new IOException("The bundled ONNX model labels do not match this adapter.");
            }
            if (!"NCHW".equals(required("inputLayout")) || !"RGB".equals(required("colorOrder"))) {
                throw new IOException("Unsupported model input layout or color order.");
            }
            if (!Boolean.parseBoolean(required("doRescale"))
                    || !Boolean.parseBoolean(required("doNormalize"))) {
                throw new IOException("Unsupported model preprocessing configuration.");
            }
            if (!"0".equals(required("resample"))) {
                throw new IOException("Unsupported image resampling method in the model manifest.");
            }
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                int processors = Runtime.getRuntime().availableProcessors();
                options.setIntraOpNumThreads(Math.max(1, Math.min(4, processors - 1)));
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(installed.file.getAbsolutePath(), options);
            }
            if (!session.getInputNames().contains(INPUT_NAME)) {
                throw new IOException("The ONNX model is missing its expected image input.");
            }
            if (!session.getOutputNames().contains("logits")) {
                throw new IOException("The ONNX model is missing its expected logits output.");
            }
        } catch (Exception exception) {
            startupFailure = safeMessage(exception);
            if (session != null) {
                try { session.close(); } catch (OrtException ignored) { }
            }
            session = null;
        }
    }

    @Override
    public synchronized AnalysisResult analyze(AnalysisRequest request) {
        if (closed.get()) return AnalysisResult.failed("The local image analyzer has been closed.");
        if (request == null || request.content().attachments().isEmpty()) {
            return AnalysisResult.notAnalyzed("No media attachment was supplied to the image analyzer.");
        }
        List<MediaAttachment> attachments = request.content().attachments();
        for (MediaAttachment attachment : attachments) {
            if (attachment.kind() != MediaKind.IMAGE) {
                return AnalysisResult.notAnalyzed(
                        "This model build analyses still images only. Video/audio inference is not connected; content remains unverified.");
            }
        }
        if (attachments.size() != 1) {
            return AnalysisResult.notAnalyzed(
                    "This release processes one selected image per analysis request.");
        }

        // Do not load the native runtime or copy model assets for an unsupported request.
        ensureInitialized();
        if (startupFailure != null || session == null || environment == null) {
            return AnalysisResult.failed("The local image model is unavailable: "
                    + (startupFailure == null ? "ONNX session was not initialized." : startupFailure));
        }

        Bitmap decoded = null;
        Bitmap resized = null;
        try {
            decoded = decodeBounded(attachments.get(0));
            int width = integer("inputWidth");
            int height = integer("inputHeight");
            resized = Bitmap.createScaledBitmap(decoded, width, height, false);
            if (resized != decoded) {
                decoded.recycle();
                decoded = null;
            }
            float[] input = toNormalizedNchw(resized, width, height);
            float[] logits = infer(input, width, height);
            return ViddexaNanoOutputMapper.toAnalysisResult(logits);
        } catch (Exception exception) {
            return AnalysisResult.failed("Local image inference failed: " + safeMessage(exception));
        } finally {
            if (resized != null && !resized.isRecycled()) resized.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    private float[] infer(float[] input, int width, int height) throws OrtException {
        long[] shape = new long[]{1L, 3L, height, width};
        FloatBuffer directInput = ByteBuffer.allocateDirect(input.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        directInput.put(input);
        directInput.rewind();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, directInput, shape);
             OrtSession.Result result = session.run(Collections.singletonMap(INPUT_NAME, tensor))) {
            Object raw = result.get(0).getValue();
            if (!(raw instanceof float[][])) {
                throw new IllegalStateException("Unexpected ONNX logits tensor representation.");
            }
            float[][] rows = (float[][]) raw;
            if (rows.length != 1 || rows[0] == null || rows[0].length != 5) {
                throw new IllegalStateException("Unexpected ONNX logits tensor shape.");
            }
            return Arrays.copyOf(rows[0], rows[0].length);
        }
    }

    private Bitmap decodeBounded(MediaAttachment attachment) throws IOException {
        int orientation = readOrientation(attachment);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = attachment.source().openStream()) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("The selected image could not be decoded.");
        }
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (pixels > MAX_SOURCE_PIXELS) {
            throw new IOException("Image exceeds the 100-megapixel safety limit.");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SOURCE_EDGE);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = false;
        Bitmap decoded;
        try (InputStream input = attachment.source().openStream()) {
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IOException("Android returned no decoded image bitmap.");
        return applyOrientation(decoded, orientation);
    }

    private static int readOrientation(MediaAttachment attachment) {
        try (InputStream input = attachment.source().openStream()) {
            if (input == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static int sampleSize(int width, int height, int maximumEdge) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maximumEdge && sample < 64) sample *= 2;
        return sample;
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1f, -1f); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f); matrix.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90f); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f); matrix.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270f); break;
            default: return source;
        }
        try {
            Bitmap oriented = Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, true);
            if (oriented != source) source.recycle();
            return oriented;
        } catch (RuntimeException exception) {
            return source;
        }
    }

    /** Matches the pinned Hugging Face EfficientNetImageProcessor: direct resize, RGB, rescale, normalize. */
    private float[] toNormalizedNchw(Bitmap bitmap, int width, int height) throws IOException {
        boolean doRescale = Boolean.parseBoolean(required("doRescale"));
        boolean doNormalize = Boolean.parseBoolean(required("doNormalize"));
        float rescaleFactor = Float.parseFloat(required("rescaleFactor"));
        float[] mean = triple("imageMean");
        float[] standardDeviation = triple("imageStd");
        int plane = width * height;
        float[] tensor = new float[3 * plane];
        int[] pixels = new int[plane];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < plane; i++) {
            int colour = pixels[i];
            float red = ((colour >> 16) & 0xff) / 255.0f;
            float green = ((colour >> 8) & 0xff) / 255.0f;
            float blue = (colour & 0xff) / 255.0f;
            tensor[i] = normalize(red, 0, doRescale, rescaleFactor, doNormalize, mean, standardDeviation);
            tensor[plane + i] = normalize(green, 1, doRescale, rescaleFactor, doNormalize, mean, standardDeviation);
            tensor[2 * plane + i] = normalize(blue, 2, doRescale, rescaleFactor, doNormalize, mean, standardDeviation);
        }
        return tensor;
    }

    private static float normalize(float channel, int index, boolean rescale, float factor,
                                   boolean normalize, float[] mean, float[] standardDeviation) {
        float value = rescale ? channel * factor : channel;
        return normalize ? (value - mean[index]) / standardDeviation[index] : value;
    }

    private int integer(String key) throws IOException {
        try { return Integer.parseInt(required(key)); }
        catch (NumberFormatException exception) { throw new IOException("Invalid model manifest integer: " + key); }
    }

    private String required(String key) throws IOException {
        String value = manifest == null ? null : manifest.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing model property: " + key);
        return value.trim();
    }

    private float[] triple(String key) throws IOException {
        String[] pieces = required(key).split(",");
        if (pieces.length != 3) throw new IOException("Invalid RGB model preprocessing property: " + key);
        try {
            return new float[]{Float.parseFloat(pieces[0]),
                    Float.parseFloat(pieces[1]), Float.parseFloat(pieces[2])};
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid numeric preprocessing property: " + key, exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) { }
            session = null;
        }
        // OrtEnvironment is a process singleton shared by ONNX Runtime sessions; do not close it here.
    }
}
