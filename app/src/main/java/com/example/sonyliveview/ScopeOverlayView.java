package com.example.sonyliveview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Cinescope framing overlay for the video page, like the aspect-ratio masks on
 * external monitors (Atomos/SmallHD style). When enabled it darkens everything
 * outside the chosen cinematic frame (2.39:1 or 1.85:1) and outlines the frame
 * with a thin white border. Purely display-side. The frame is computed from the
 * actual fitted image rectangle (the preview is FIT_CENTER, so the frame may be
 * letterboxed) via {@link #setImageAspect(int, int)}, so it hugs the image,
 * not the screen.
 */
final class ScopeOverlayView extends View {
    private static final int MASK_COLOR = 0xB0000000;   // ~69% black letterbox
    private static final int FRAME_COLOR = 0x99FFFFFF;  // white, ~60%

    private final Paint maskPaint = new Paint();
    private final Paint framePaint = new Paint();
    private float scopeAspect; // 0 = off
    private int aspectWidth;
    private int aspectHeight;

    ScopeOverlayView(Context context) {
        super(context);
        maskPaint.setColor(MASK_COLOR);
        framePaint.setColor(FRAME_COLOR);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(1));
        framePaint.setAntiAlias(true);
    }

    /** Enables (aspect > 0) or disables the scope mask. */
    void setScopeAspect(float aspect) {
        scopeAspect = aspect;
        setVisibility(aspect > 0 ? View.VISIBLE : View.GONE);
        invalidate();
    }

    /** Feeds the decoded (possibly desqueezed) frame size. */
    void setImageAspect(int width, int height) {
        aspectWidth = width;
        aspectHeight = height;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (scopeAspect <= 0) return;

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

        // Largest scope-aspect frame that fits inside the image rectangle.
        float frameW, frameH, frameLeft, frameTop;
        if (fittedWidth / fittedHeight > scopeAspect) {
            frameH = fittedHeight;
            frameW = frameH * scopeAspect;
            frameLeft = left + (fittedWidth - frameW) / 2f;
            frameTop = top;
        } else {
            frameW = fittedWidth;
            frameH = frameW / scopeAspect;
            frameLeft = left;
            frameTop = top + (fittedHeight - frameH) / 2f;
        }
        float frameRight = frameLeft + frameW;
        float frameBottom = frameTop + frameH;

        // Darken everything outside the cinematic frame (the bars).
        canvas.drawRect(left, top, frameLeft, frameBottom, maskPaint);
        canvas.drawRect(frameRight, top, left + fittedWidth, frameBottom, maskPaint);
        canvas.drawRect(left, top, left + fittedWidth, frameTop, maskPaint);
        canvas.drawRect(left, frameBottom, left + fittedWidth, top + fittedHeight, maskPaint);

        // Thin border outlining the scope frame.
        canvas.drawRect(frameLeft, frameTop, frameRight, frameBottom, framePaint);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
