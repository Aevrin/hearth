/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

/**
 * The accent: one colour, or a two-colour gradient with a direction. Everything
 * that's drawn in the accent goes through here, so it looks the same everywhere.
 * Things that can only take a single colour (system switches, sliders) use
 * {@link #solid()}, which for a gradient is the colour halfway along.
 */
final class Accent {

    static final int DIAGONAL = 0, HORIZONTAL = 1, VERTICAL = 2;

    /** Preset gradients offered in Settings: {start, end}. */
    static final int[][] PRESETS = {
            {0xFF3D52A0, 0xFF06B6D4}, // deep blue → aquamarine
            {0xFF3B82F6, 0xFF14B8A6}, // blue → teal
            {0xFF6C7EC5, 0xFF06B6D4}, // periwinkle → aquamarine
            {0xFFA855F7, 0xFF06B6D4}, // violet → cyan
            {0xFF14B8A6, 0xFF22C55E}, // teal → green
            {0xFFF43F5E, 0xFFA855F7}, // rose → violet
    };

    final int start, end;
    final boolean gradient;
    final int direction;

    Accent(int start, int end, boolean gradient, int direction) {
        this.start = start | 0xFF000000;
        this.end = end | 0xFF000000;
        this.gradient = gradient && start != end;
        this.direction = Math.max(DIAGONAL, Math.min(VERTICAL, direction));
    }

    static Accent solid(int color) {
        return new Accent(color, color, false, DIAGONAL);
    }

    /** The single colour to use where a gradient can't be drawn. */
    int solid() {
        return gradient ? at(0.5f) : start;
    }

    /** The colour at a point along the gradient, 0 = start, 1 = end. */
    int at(float t) {
        if (!gradient) return start;
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(255,
                Math.round(Color.red(start) + (Color.red(end) - Color.red(start)) * t),
                Math.round(Color.green(start) + (Color.green(end) - Color.green(start)) * t),
                Math.round(Color.blue(start) + (Color.blue(end) - Color.blue(start)) * t));
    }

    /** Where a point sits along the gradient, inside a w×h box. */
    float position(float x, float y, float w, float h) {
        switch (direction) {
            case HORIZONTAL: return w <= 0 ? 0 : x / w;
            case VERTICAL: return h <= 0 ? 0 : y / h;
            default: return w + h <= 0 ? 0 : (x + y) / (w + h);
        }
    }

    /** A shader filling the box (l, t)–(r, b), or null for a plain colour. */
    Shader shader(float l, float t, float r, float b, int alpha) {
        if (!gradient) return null;
        int a = withAlpha(start, alpha), z = withAlpha(end, alpha);
        switch (direction) {
            case HORIZONTAL: return new LinearGradient(l, 0, r, 0, a, z, Shader.TileMode.CLAMP);
            case VERTICAL: return new LinearGradient(0, t, 0, b, a, z, Shader.TileMode.CLAMP);
            default: return new LinearGradient(l, t, r, b, a, z, Shader.TileMode.CLAMP);
        }
    }

    GradientDrawable.Orientation orientation() {
        switch (direction) {
            case HORIZONTAL: return GradientDrawable.Orientation.LEFT_RIGHT;
            case VERTICAL: return GradientDrawable.Orientation.TOP_BOTTOM;
            default: return GradientDrawable.Orientation.TL_BR;
        }
    }

    /** A filled drawable in the accent (for swatches, scrollbar thumbs…). */
    GradientDrawable drawable() {
        GradientDrawable d = new GradientDrawable();
        if (gradient) {
            d.setOrientation(orientation());
            d.setColors(new int[] {start, end});
        } else {
            d.setColor(start);
        }
        return d;
    }

    /** Paints a TextView's text in the accent, following its size as it changes. */
    void paint(TextView t) {
        t.setTextColor(solid());
        Object old = t.getTag(R.id.accent_listener);
        if (old instanceof View.OnLayoutChangeListener) t.removeOnLayoutChangeListener((View.OnLayoutChangeListener) old);
        t.setTag(R.id.accent_listener, null);
        if (!gradient) {
            t.getPaint().setShader(null);
            t.invalidate();
            return;
        }
        View.OnLayoutChangeListener l = (v, a, b, c, d, e, f, g, h) -> applyShader(t);
        t.addOnLayoutChangeListener(l);
        t.setTag(R.id.accent_listener, l);
        applyShader(t);
    }

    /** Back to a plain colour, removing any gradient set by {@link #paint}. */
    static void plain(TextView t, int color) {
        Object old = t.getTag(R.id.accent_listener);
        if (old instanceof View.OnLayoutChangeListener) t.removeOnLayoutChangeListener((View.OnLayoutChangeListener) old);
        t.setTag(R.id.accent_listener, null);
        t.getPaint().setShader(null);
        t.setTextColor(color);
    }

    // The text is drawn in the layout's own coordinates, so span the text itself:
    // from the left of the widest line to its right, top line to bottom line.
    private void applyShader(TextView t) {
        Layout layout = t.getLayout();
        float w, h, left = 0;
        if (layout != null && layout.getLineCount() > 0) {
            left = Float.MAX_VALUE;
            float right = 0;
            for (int i = 0; i < layout.getLineCount(); i++) {
                left = Math.min(left, layout.getLineLeft(i));
                right = Math.max(right, layout.getLineRight(i));
            }
            w = right;
            h = layout.getHeight();
        } else {
            w = t.getWidth();
            h = t.getHeight();
        }
        if (w <= left) w = left + 1;
        t.getPaint().setShader(shader(left, 0, w, Math.max(1, h), 255));
        t.invalidate();
    }

    /** Changes whenever the look changes; part of the icon cache key. */
    String signature() {
        return gradient ? String.format("g%08X-%08X-%d", start, end, direction) : String.format("s%08X", start);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
