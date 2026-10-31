package dev.filterbridge.runtime.onnx;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;

/** Installs and verifies immutable model assets into app-private internal storage. */
final class OnnxModelAssetInstaller {
    static final String MODEL_ASSET = "models/viddexa-nano.onnx";
    static final String MANIFEST_ASSET = "models/model.properties";
    private static final String MODEL_FILE_NAME = "viddexa-nano.onnx";
    private static final int BUFFER_SIZE = 64 * 1024;

    private OnnxModelAssetInstaller() { }

    static InstalledModel install(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        Properties manifest = new Properties();
        try (InputStream input = assets.open(MANIFEST_ASSET)) {
            manifest.load(input);
        }

        String modelId = required(manifest, "modelId");
        String revision = required(manifest, "revision");
        String expectedSha256 = required(manifest, "onnxSha256").toLowerCase(Locale.ROOT);
        if (!expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("The bundled model manifest contains an invalid ONNX checksum.");
        }
        if (!"224".equals(required(manifest, "inputWidth"))
                || !"224".equals(required(manifest, "inputHeight"))) {
            throw new IOException("Unsupported model input dimensions in the bundled manifest.");
        }

        File directory = new File(context.getFilesDir(), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create the private model directory.");
        }
        File modelFile = new File(directory, MODEL_FILE_NAME);
        if (!modelFile.isFile() || !expectedSha256.equals(sha256(modelFile))) {
            File temporary = new File(directory, MODEL_FILE_NAME + ".part");
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("Unable to remove an incomplete model file.");
            }
            try (InputStream input = assets.open(MODEL_ASSET);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            } catch (IOException exception) {
                temporary.delete();
                throw exception;
            }
            String actualSha256 = sha256(temporary);
            if (!expectedSha256.equals(actualSha256)) {
                temporary.delete();
                throw new IOException("The bundled ONNX model failed SHA-256 verification.");
            }
            if (modelFile.exists() && !modelFile.delete()) {
                temporary.delete();
                throw new IOException("Unable to replace the previous model file.");
            }
            if (!temporary.renameTo(modelFile)) {
                temporary.delete();
                throw new IOException("Unable to atomically install the ONNX model.");
            }
        }

        return new InstalledModel(modelFile, manifest);
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing required model manifest property: " + key);
        }
        return value.trim();
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable on this Android runtime.", impossible);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    static final class InstalledModel {
        final File file;
        final Properties manifest;
        InstalledModel(File file, Properties manifest) {
            this.file = file;
            this.manifest = manifest;
        }
    }
}
