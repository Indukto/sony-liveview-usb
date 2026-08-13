package com.example.sonyliveview;

import android.graphics.Bitmap;

/**
 * Display-side zebra: overlays diagonal black/white stripes on pixels whose
 * luma is at or above 100% (JPEG white, luma >= 254), mirroring Sony's
 * in-camera zebra exposure aid at its 100% level. Purely a display effect -
 * it never writes to the camera, so it cannot trigger the a6300's pipe stall.
 */
final class Zebra {
    // Classic zebra pair. Stripes alternate dark/light so the overlay stays
    // visible on both the white and the tinted parts of a clipped area.
    private static final int STRIPE_DARK = 0xFF222222;
    private static final int STRIPE_LIGHT = 0xFFE0E0E0;

    // Reused per-frame buffer, sized to the largest frame seen. Only ever
    // touched from the single decoder thread, so no synchronization needed.
    private static int[] pixels;

    private Zebra() {}

    /**
     * Returns a new bitmap equal to {@code source} with 100% zebra stripes
     * drawn over every pixel at or above JPEG white. The source bitmap is
     * left untouched.
     */
    static Bitmap apply(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int count = width * height;
        if (pixels == null || pixels.length < count) pixels = new int[count];

        source.getPixels(pixels, 0, width, 0, 0, width, height);

        int threshold = 254;
        // Diagonal 4-pixel stripes (period 8): the stripe phase advances with
        // y as well as x, so the pattern runs at 45 degrees like Sony's.
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                int p = pixels[i];
                int luma = (((p >>> 16) & 0xFF) * 299 + ((p >>> 8) & 0xFF) * 587
                        + (p & 0xFF) * 114) / 1000;
                if (luma >= threshold) {
                    pixels[i] = ((x + y) & 7) < 4 ? STRIPE_DARK : STRIPE_LIGHT;
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
