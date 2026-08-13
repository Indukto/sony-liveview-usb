package com.example.sonyliveview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Rule-of-thirds grid overlay for the video page. Purely display-side, like
 * peaking/zebra/histogram: it draws the classic 2x2 thirds lines plus a small
 * center reticle over the live view frame. The lines follow the actual fitted
 * image rectangle (the preview is FIT_CENTER, so the frame may be letterboxed)
 * rather than the whole screen; pass the decoded frame's dimensions via
 * {@link #setImageAspect(int, int)}.
 */
final class GridOverlayView extends View {
    private static final int GRID_COLOR = 0x59FFFFFF;    // white, ~35%
    private static final int RETICLE_COLOR = 0x8CFFFFFF; // white, ~55%

    private final Paint linePaint = new Paint();
    private final Paint reticlePaint = new Paint();
    private boolean thirds;
    private int aspectWidth;
    private int aspectHeight;

    GridOverlayView(Context context) {
        super(context);
        linePaint.setColor(GRID_COLOR);
        linePaint.setStrokeWidth(dp(1));
        linePaint.setAntiAlias(true);
        reticlePaint.setColor(RETICLE_COLOR);
        reticlePaint.setStrokeWidth(dp(1));
        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setAntiAlias(true);
    }

    /** Turns the thirds grid on/off (hides the view entirely when off). */
    void setThirds(boolean on) {
        thirds = on;
        setVisibility(on ? View.VISIBLE : View.GONE);
        invalidate();
    }

    /** Feeds the decoded frame size so the grid matches the fitted image rect. */
    void setImageAspect(int width, int height) {
        aspectWidth = width;
        aspectHeight = height;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!thirds) return;

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        // FIT_CENTER destination rectangle for the latest frame; falls back to
        // the full view before the first frame arrives.
        float imageWidth = aspectWidth > 0 ? aspectWidth : viewWidth;
        float imageHeight = aspectHeight > 0 ? aspectHeight : viewHeight;
        float scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
        float fittedWidth = imageWidth * scale;
        float fittedHeight = imageHeight * scale;
        float left = (viewWidth - fittedWidth) / 2f;
        float top = (viewHeight - fittedHeight) / 2f;

        // Rule of thirds: four lines through the third marks.
        canvas.drawLine(left + fittedWidth / 3f, top, left + fittedWidth / 3f, top + fittedHeight, linePaint);
        canvas.drawLine(left + 2 * fittedWidth / 3f, top, left + 2 * fittedWidth / 3f, top + fittedHeight, linePaint);
        canvas.drawLine(left, top + fittedHeight / 3f, left + fittedWidth, top + fittedHeight / 3f, linePaint);
        canvas.drawLine(left, top + 2 * fittedHeight / 3f, left + fittedWidth, top + 2 * fittedHeight / 3f, linePaint);

        // Small center reticle at the frame center.
        float centerX = left + fittedWidth / 2f;
        float centerY = top + fittedHeight / 2f;
        float radius = Math.min(fittedWidth, fittedHeight) * 0.03f;
        canvas.drawCircle(centerX, centerY, radius, reticlePaint);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
