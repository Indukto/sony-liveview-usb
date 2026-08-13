package com.example.sonyliveview;

import android.graphics.Bitmap;

/**
 * Display-side focus peaking: detects high-contrast edges in a live view
 * frame and tints them with the selected peaking color, mirroring Sony's
 * in-camera peaking (red / yellow / white). Purely a display effect - it
 * never writes to the camera, so it cannot trigger the a6300's pipe stall.
 */
final class FocusPeaking {
    static final int RED = 0xFFFF3B30;
    static final int YELLOW = 0xFFFFD60A;
    static final int WHITE = 0xFFFFFFFF;
    static final int[] COLORS = { RED, YELLOW, WHITE };

    // Reused per-frame buffers, sized to the largest frame seen. Only ever
    // touched from the single decoder thread, so no synchronization needed.
    private static int[] pixels;
    private static byte[] gray;
    private static byte[] magnitude;

    private FocusPeaking() {}

    /**
     * Returns a new bitmap equal to {@code source} with edge highlights drawn
     * in {@code color}. The source bitmap is left untouched.
     */
    static Bitmap apply(Bitmap source, int color) {
        int width = source.getWidth();
        int height = source.getHeight();
        int count = width * height;
        if (pixels == null || pixels.length < count) pixels = new int[count];
        if (gray == null || gray.length < count) gray = new byte[count];
        if (magnitude == null || magnitude.length < count) magnitude = new byte[count];

        source.getPixels(pixels, 0, width, 0, 0, width, height);

        // Luma (Rec. 601 coefficients) for edge detection.
        for (int i = 0; i < count; i++) {
            int p = pixels[i];
            gray[i] = (byte) ((((p >>> 16) & 0xFF) * 299 + ((p >>> 8) & 0xFF) * 587
                    + (p & 0xFF) * 114) / 1000);
        }

        // Gradient magnitude (|Gx| + |Gy|) over a 3x3 neighborhood, with a
        // running average used to adapt the highlight threshold to the scene.
        long magSum = 0;
        int interior = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                int gx = (gray[i + 1] & 0xFF) - (gray[i - 1] & 0xFF);
                int gy = (gray[i + width] & 0xFF) - (gray[i - width] & 0xFF);
                int mag = Math.abs(gx) + Math.abs(gy);
                magnitude[i] = (byte) Math.min(255, mag);
                magSum += mag;
                interior++;
            }
        }
        long avg = interior > 0 ? magSum / interior : 0;
        int threshold = (int) Math.max(40, Math.min(160, avg * 2.5));

        // Tint strong edges with the peaking color blended toward the original.
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                if ((magnitude[i] & 0xFF) > threshold) {
                    int p = pixels[i];
                    pixels[i] = 0xFF000000
                            | ((r * 6 + ((p >>> 16) & 0xFF)) / 7 << 16)
                            | ((g * 6 + ((p >>> 8) & 0xFF)) / 7 << 8)
                            | ((b * 6 + (p & 0xFF)) / 7);
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
