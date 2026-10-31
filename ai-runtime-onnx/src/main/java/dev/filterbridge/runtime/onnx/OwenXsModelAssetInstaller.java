package dev.filterbridge.runtime.onnx;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;

/** Installs the pinned Owen XS ONNX asset into app-private storage and verifies its immutable hash. */
final class OwenXsModelAssetInstaller {
    static final String MODEL_ID = "OwenElliott/image-safety-classifier-xs";
    static final String REVISION = "606ad3dfd6a023215e3ab0797040437cc365977b";
    static final String EXPECTED_SHA256 = "8c28c49d9075f3ad15ebdc2961f02d5b3f99be944815b848b49c9f0e6f3fb689";
    private static final String MODEL_ASSET = "models/owen-image-safety-xs.onnx";
    private static final String MANIFEST_ASSET = "models/owen-image-safety-xs.properties";
    private static final int BUFFER_SIZE = 64 * 1024;

    private OwenXsModelAssetInstaller() { }

    static InstalledModel install(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        Properties manifest = new Properties();
        try (InputStream in = assets.open(MANIFEST_ASSET)) { manifest.load(in); }
        require(manifest, "modelId", MODEL_ID);
        require(manifest, "revision", REVISION);
        require(manifest, "onnxSha256", EXPECTED_SHA256);
        require(manifest, "inputWidth", "224");
        require(manifest, "inputHeight", "224");
        require(manifest, "inputName", "image");
        require(manifest, "labels", "NSFL,NSFW,SFW");
        require(manifest, "inputLayout", "NCHW");
        require(manifest, "colorOrder", "RGB");
        require(manifest, "preprocessing", "BILINEAR_RGB_0_255");
        require(manifest, "outputActivation", "SOFTMAX_BAKED_IN");

        File directory = new File(context.getFilesDir(), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private model storage.");
        }
        File destination = new File(directory, "owen-image-safety-xs.onnx");
        if (!destination.isFile() || !EXPECTED_SHA256.equals(sha256(destination))) {
            File temporary = new File(directory, destination.getName() + ".part");
            if (temporary.exists() && !temporary.delete()) throw new IOException("Unable to clear partial model file.");
            try (InputStream input = assets.open(MODEL_ASSET);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush(); output.getFD().sync();
            } catch (IOException ex) { temporary.delete(); throw ex; }
            if (!EXPECTED_SHA256.equals(sha256(temporary))) {
                temporary.delete(); throw new IOException("Owen XS model failed SHA-256 verification.");
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete(); throw new IOException("Unable to replace previous Owen XS model.");
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete(); throw new IOException("Unable to atomically install Owen XS model.");
            }
        }
        return new InstalledModel(destination, manifest);
    }

    private static void require(Properties p, String key, String expected) throws IOException {
        String value = p.getProperty(key);
        if (!expected.equals(value == null ? null : value.trim())) {
            throw new IOException("Unexpected Owen XS manifest value for " + key + ".");
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    static final class InstalledModel {
        final File file; final Properties manifest;
        InstalledModel(File file, Properties manifest) { this.file = file; this.manifest = manifest; }
    }
}
