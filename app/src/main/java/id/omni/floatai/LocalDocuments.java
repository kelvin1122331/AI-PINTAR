package id.omni.floatai;

import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class LocalDocuments {
    private static final int MAX_TEXT_BYTES = 128_000;
    private static final int MAX_DECK_BYTES = 1_000_000;
    private static final int IMAGE_LIMIT = 1920;

    static Intent open(String type) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
    }
    static Intent saveText(String name) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(name.endsWith(".tsv") ? "text/tab-separated-values" : "text/plain").putExtra(Intent.EXTRA_TITLE, name);
    }

    static String readText(ContentResolver resolver, Uri uri) throws IOException {
        return readUtf8(resolver, uri, MAX_TEXT_BYTES);
    }

    static String readDeck(ContentResolver resolver, Uri uri) throws IOException {
        return readUtf8(resolver, uri, MAX_DECK_BYTES);
    }

    private static String readUtf8(ContentResolver resolver, Uri uri, int limit) throws IOException {
        try (var in = resolver.openInputStream(uri); var out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Dokumen tidak dapat dibuka.");
            byte[] bytes = new byte[4096];
            int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > limit) throw new IOException("Berkas melebihi batas ukuran.");
                out.write(bytes, 0, count);
            }
            String value = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return value.startsWith("\ufeff") ? value.substring(1) : value;
        }
    }

    static void writeText(ContentResolver resolver, Uri uri, String contents) throws IOException {
        try (var out = resolver.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Tujuan tidak dapat dibuka.");
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    static Bitmap readImage(ContentResolver resolver, Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                double scale = Math.min(1, IMAGE_LIMIT / (double) Math.max(width, height));
                decoder.setTargetSize(Math.max(1, (int) Math.round(width * scale)),
                    Math.max(1, (int) Math.round(height * scale)));
            });
        }
        var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (var in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("Foto tidak dapat dibaca.");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > IMAGE_LIMIT) {
            options.inSampleSize *= 2;
        }
        Bitmap image;
        try (var in = resolver.openInputStream(uri)) { image = BitmapFactory.decodeStream(in, null, options); }
        if (image == null) throw new IOException("Foto tidak dapat dibaca.");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (var in = resolver.openInputStream(uri)) {
            if (in != null) orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, orientation);
        } catch (IOException ignored) {}
        var matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
            case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
            case ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90); matrix.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
            case ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90); matrix.postScale(-1, 1); }
            case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90);
        }
        if (matrix.isIdentity()) return image;
        Bitmap oriented = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
        if (oriented != image) image.recycle();
        return oriented;
    }

    private LocalDocuments() {}
}
