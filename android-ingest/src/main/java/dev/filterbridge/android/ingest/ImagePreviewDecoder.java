package dev.filterbridge.android.ingest;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

/** Bounds image decoding and applies EXIF orientation before previewing. Call off the UI thread. */
public final class ImagePreviewDecoder {
    private static final int MAX_PREVIEW_EDGE = 1600;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;

    private ImagePreviewDecoder() { }

    public static Bitmap decode(ContentResolver resolver, Uri uri) throws IOException {
        int orientation = readOrientation(resolver, uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open image stream.");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("The selected image format could not be decoded.");
        }
        long sourcePixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (sourcePixels > MAX_SOURCE_PIXELS) {
            throw new IOException("The selected image is too large to preview safely (over 100 megapixels).");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_PREVIEW_EDGE);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = true;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to reopen image stream.");
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("The image decoder returned no bitmap.");
        return applyOrientation(bitmap, orientation);
    }

    private static int readOrientation(ContentResolver resolver, Uri uri) {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static int sampleSize(int width, int height, int maxEdge) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maxEdge) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            case ExifInterface.ORIENTATION_NORMAL:
            default:
                return source;
        }
        try {
            Bitmap corrected = Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, true);
            if (corrected != source) source.recycle();
            return corrected;
        } catch (RuntimeException exception) {
            // A preview should remain available even if malformed EXIF data cannot be applied.
            return source;
        }
    }
}
