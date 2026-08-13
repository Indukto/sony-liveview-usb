package com.example.sonyliveview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * RGB histogram overlay for the live view, styled after the one in Imaging
 * Edge. The channel data is computed on the decoder thread from the frame
 * being displayed and pushed in via {@link #setHistogram}; this view only
 * draws, so it never touches the USB or decode paths.
 */
final class HistogramView extends View {
    static final int BINS = 64;

    private final int[] rBins = new int[BINS];
    private final int[] gBins = new int[BINS];
    private final int[] bBins = new int[BINS];

    private final Paint background = new Paint();
    private final Paint[] fills = new Paint[3];
    private final Paint[] lines = new Paint[3];
    private final Path[] paths = new Path[3];
    private final Path linePath = new Path();
    private final float[] pointY = new float[BINS];

    HistogramView(Context context) {
        super(context);
        background.setColor(0x99000000);

        // R / G / B channel colors (Sony-style pastel on dark).
        int[] colors = {0xFFFF453A, 0xFF32D74B, 0xFF64D2FF};
        for (int i = 0; i < 3; i++) {
            fills[i] = new Paint();
            fills[i].setColor((colors[i] & 0x00FFFFFF) | 0x2E000000);
            fills[i].setStyle(Paint.Style.FILL);
            fills[i].setAntiAlias(true);

            lines[i] = new Paint();
            lines[i].setColor(colors[i]);
            lines[i].setStyle(Paint.Style.STROKE);
            lines[i].setStrokeWidth(dp(1));
            lines[i].setAntiAlias(true);

            paths[i] = new Path();
        }
    }

    /** Copies fresh channel bins in and repaints (call from any thread). */
    void setHistogram(int[] r, int[] g, int[] b) {
        System.arraycopy(r, 0, rBins, 0, BINS);
        System.arraycopy(g, 0, gBins, 0, BINS);
        System.arraycopy(b, 0, bBins, 0, BINS);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        float corner = dp(6);
        canvas.drawRoundRect(0, 0, width, height, corner, corner, background);

        int max = 0;
        for (int i = 0; i < BINS; i++) {
            max = Math.max(max, Math.max(rBins[i], Math.max(gBins[i], bBins[i])));
        }
        if (max == 0) return;

        float pad = dp(3);
        float plotW = width - pad * 2;
        float plotH = height - pad * 2;
        float step = plotW / (BINS - 1);
        int[][] channels = {rBins, gBins, bBins};

        for (int c = 0; c < 3; c++) {
            int[] bins = channels[c];
            for (int i = 0; i < BINS; i++) {
                pointY[i] = pad + plotH * (1f - bins[i] / (float) max);
            }
            Path path = paths[c];
            path.reset();
            path.moveTo(pad, pad + plotH);
            for (int i = 0; i < BINS; i++) {
                path.lineTo(pad + i * step, pointY[i]);
            }
            path.lineTo(pad + (BINS - 1) * step, pad + plotH);
            path.close();
            canvas.drawPath(path, fills[c]);

            linePath.reset();
            linePath.moveTo(pad, pointY[0]);
            for (int i = 1; i < BINS; i++) {
                linePath.lineTo(pad + i * step, pointY[i]);
            }
            canvas.drawPath(linePath, lines[c]);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
