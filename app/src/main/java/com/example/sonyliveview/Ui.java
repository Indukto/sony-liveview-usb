package com.example.sonyliveview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared design tokens and view factories for the app's two pages.
 *
 * <p>Both pages are built in code (no layout XML), so the palette, spacing
 * and button styles live here instead of being repeated in MainActivity.
 */
final class Ui {
    // Surfaces
    static final int COLOR_BG = 0xFF0B0E11;
    static final int COLOR_PANEL = 0xFF141A20;
    static final int COLOR_PANEL_SOFT = 0xFF10161B;
    static final int COLOR_HAIRLINE = 0xFF243039;
    static final int COLOR_PREVIEW = 0xFF050607;

    // Text
    static final int COLOR_TEXT = 0xFFC3CFD7;
    static final int COLOR_DIM = 0xFF7C8A95;

    // Accents
    static final int COLOR_ACCENT = 0xFF80CBC4;
    static final int COLOR_OK = 0xFF6FDB94;
    static final int COLOR_WARN = 0xFFFFC24B;
    static final int COLOR_ERR = 0xFFFF6B6B;
    static final int COLOR_REC = 0xFFE53935;
    static final int COLOR_SCOPE = 0xFFD0A9F5;

    // Type scale (sp)
    static final float SIZE_TITLE = 15f;
    static final float SIZE_SECTION = 11f;
    static final float SIZE_BODY = 13f;
    static final float SIZE_LABEL = 12f;
    static final float SIZE_MONO = 11f;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** A rounded surface with an optional hairline border. */
    static GradientDrawable surface(Context context, int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static TextView text(Context context, String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    /** Small uppercase section heading, e.g. "SETUP" or "SESSION LOG". */
    static TextView sectionLabel(Context context, String value) {
        TextView view = text(context, value.toUpperCase(java.util.Locale.US),
                SIZE_SECTION, COLOR_DIM, true);
        view.setLetterSpacing(0.18f);
        return view;
    }

    /** A thin horizontal rule used to separate stacked blocks. */
    static View divider(Context context, int color) {
        View view = new View(context);
        view.setBackgroundColor(color);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return view;
    }

    /** Primary action button: filled with the accent, dark label. */
    static Button primaryButton(Context context, String label, int accent) {
        Button button = baseButton(context, label, accent);
        GradientDrawable background = surface(context, accent, Color.TRANSPARENT, 10);
        button.setBackground(background);
        button.setTextColor(0xFF06110F);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return button;
    }

    /** Secondary action button: outlined in the accent, accent label. */
    static Button secondaryButton(Context context, String label, int accent) {
        Button button = baseButton(context, label, accent);
        button.setBackground(surface(context, COLOR_PANEL, accent, 10));
        button.setTextColor(accent);
        return button;
    }

    /** Low-emphasis action button: no border, dim label. */
    static Button ghostButton(Context context, String label, int color) {
        Button button = baseButton(context, label, color);
        button.setBackground(surface(context, Color.TRANSPARENT, Color.TRANSPARENT, 10));
        button.setTextColor(color);
        return button;
    }

    /** Tool chip for the video stage toolbar. */
    static Button toolChip(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(SIZE_LABEL);
        button.setAllCaps(false);
        button.setLetterSpacing(0.02f);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setStateListAnimator(null);
        button.setBackground(surface(context, 0x00000000, COLOR_HAIRLINE, 8));
        return button;
    }

    /** Colors a chip's label and border to show its active state. */
    static void styleChip(Button button, boolean active, int activeColor) {
        button.setTextColor(active ? activeColor : COLOR_TEXT);
        GradientDrawable background = (GradientDrawable) button.getBackground();
        background.setColor(active ? withAlpha(activeColor, 0x26) : Color.TRANSPARENT);
        background.setStroke(Ui.dp(button.getContext(), 1),
                active ? activeColor : COLOR_HAIRLINE);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static Button baseButton(Context context, String label, int accent) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(SIZE_BODY);
        button.setAllCaps(false);
        button.setLetterSpacing(0.01f);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        button.setStateListAnimator(null);
        return button;
    }
}
