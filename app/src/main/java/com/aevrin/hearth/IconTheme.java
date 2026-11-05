/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import java.util.Collections;
import java.util.Map;

/**
 * Generates themed icons on the device from each app's own icon. No icon packs,
 * no network. Modern icons are layered (background, foreground and, since
 * Android 13, an optional single-colour "monochrome" glyph), which is what makes
 * reshaping and recolouring clean; older flat icons get a best-effort treatment.
 */
final class IconTheme {

    static final String ORIGINAL = "original";
    static final String THEMED = "themed";
    static final String DUOTONE = "duotone";
    static final String GRAYSCALE = "grayscale";
    static final String LETTER = "letter";
    static final String[] STYLES = {ORIGINAL, THEMED, DUOTONE, GRAYSCALE, LETTER};

    static final String SHAPE_SYSTEM = "system";
    static final String CIRCLE = "circle";
    static final String SQUIRCLE = "squircle";
    static final String ROUNDED = "rounded";
    static final String SQUARE = "square";
    static final String TEARDROP = "teardrop";
    static final String[] SHAPES = {SHAPE_SYSTEM, CIRCLE, SQUIRCLE, ROUNDED, SQUARE, TEARDROP};

    /** Rendering size; ImageViews scale it to the chosen icon size. */
    static final int SIZE = 192;

    private final int PLATE;                           // themed / legacy plate: follows the base colour
    private static final int DUO_DARK = 0xFF12233A;   // duotone shadows
    private final int LETTER_TEXT;

    final String style;
    final String shape;
    final Accent accent;
    private final Map<String, String> overrides;
    /** Changes whenever anything that affects rendering changes; used to drop the cache. */
    final String signature;

    IconTheme(String style, String shape, Accent accent, Map<String, String> overrides) {
        this(style, shape, accent, overrides, 0xFF192534);
    }

    IconTheme(String style, String shape, Accent accent, Map<String, String> overrides, int plate) {
        this.PLATE = plate | 0xFF000000;
        this.LETTER_TEXT = Color.luminance(PLATE) > 0.5f ? 0xFF1A2230 : 0xFFE6F0FF;
        this.style = style;
        this.shape = shape;
        this.accent = accent;
        this.overrides = overrides == null ? Collections.emptyMap() : overrides;
        this.signature = style + "|" + shape + "|" + accent.signature() + "|" + Integer.toHexString(PLATE) + "|" + new java.util.TreeMap<>(this.overrides);
    }

    static IconTheme from(Prefs p) {
        return new IconTheme(p.iconStyle(), p.iconShape(), p.accentStyle(), p.iconOverrides(), Prefs.elevated());
    }

    String styleFor(String key) {
        String o = overrides.get(key);
        return o != null ? o : style;
    }

    /** Original style in the device's own shape: just use the system's icon untouched. */
    boolean isPassThrough(String style) {
        return ORIGINAL.equals(style) && SHAPE_SYSTEM.equals(shape);
    }

    /* ---------- rendering ---------- */

    Bitmap render(Drawable raw, String style, String label) {
        switch (style) {
            case THEMED: return themed(raw);
            case DUOTONE: return duotone(shaped(raw));
            case GRAYSCALE: return grayscale(shaped(raw));
            case LETTER: return letter(label);
            default: return shaped(raw);
        }
    }

    /** The app's own artwork, cut to the chosen shape. */
    Bitmap shaped(Drawable raw) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (raw instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) raw;
            drawLayer(c, a.getBackground());
            drawLayer(c, a.getForeground());
        } else {
            fillPlate(c, PLATE);
            drawInset(c, raw, 0.16f);
        }
        mask(bmp);
        return bmp;
    }

    /**
     * The app's glyph in the accent colour on a dark plate. Uses the app's own
     * monochrome layer when it has one; otherwise derives a silhouette.
     */
    Bitmap themed(Drawable raw) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        fillPlate(c, PLATE);
        Drawable mono = raw instanceof AdaptiveIconDrawable ? ((AdaptiveIconDrawable) raw).getMonochrome() : null;
        Bitmap glyph;
        if (mono != null) {
            glyph = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Drawable m = mono.mutate();
            m.setTint(accent.start);
            drawLayer(new Canvas(glyph), m);
        } else {
            glyph = silhouette(raw);
        }
        colourise(glyph);
        c.drawBitmap(glyph, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        glyph.recycle();
        mask(bmp);
        return bmp;
    }

    /** Light and dark areas mapped onto two theme colours, keeping transparency. */
    Bitmap duotone(Bitmap src) {
        int[] px = new int[SIZE * SIZE];
        src.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        for (int i = 0; i < px.length; i++) {
            int a = Color.alpha(px[i]);
            if (a == 0) continue;
            float l = (0.2126f * Color.red(px[i]) + 0.7152f * Color.green(px[i]) + 0.0722f * Color.blue(px[i])) / 255f;
            int x = i % SIZE, y = i / SIZE;
            int hi = accent.at(accent.position(x, y, SIZE, SIZE)); // the gradient runs across the icon
            px[i] = Color.argb(a,
                    lerp(Color.red(DUO_DARK), Color.red(hi), l),
                    lerp(Color.green(DUO_DARK), Color.green(hi), l),
                    lerp(Color.blue(DUO_DARK), Color.blue(hi), l));
        }
        src.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        return src;
    }

    Bitmap grayscale(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }

    /** First letter of the app's name on a plate whose colour comes from the name. */
    Bitmap letter(String label) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        String name = label == null || label.trim().isEmpty() ? "?" : label.trim();
        int base = Prefs.ACCENTS[Math.floorMod(name.toLowerCase().hashCode(), Prefs.ACCENTS.length - 1)];
        fillPlate(c, blend(base, PLATE, 0.45f)); // muted so white text stays readable
        String ch = new String(Character.toChars(name.codePointAt(0))).toUpperCase();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(LETTER_TEXT);
        t.setTextSize(SIZE * 0.46f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(ch, SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2f, t);
        mask(bmp);
        return bmp;
    }

    /** A picture (a folder's custom image), centre-cropped and cut to the icon shape. */
    Bitmap image(Bitmap src) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float scale = Math.max(SIZE / (float) src.getWidth(), SIZE / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((SIZE - src.getWidth() * scale) / 2f, (SIZE - src.getHeight() * scale) / 2f);
        c.drawBitmap(src, m, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        mask(bmp);
        return bmp;
    }

    /** One or two characters (a letter, an emoji) in the accent on a dark plate. */
    Bitmap glyph(String text) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        fillPlate(c, PLATE);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(accent.solid());
        t.setShader(accent.shader(0, 0, SIZE, SIZE, 255)); // emoji keep their own colours
        t.setTextSize(SIZE * (text.codePointCount(0, text.length()) > 1 ? 0.36f : 0.46f));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(text, SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2f, t);
        mask(bmp);
        return bmp;
    }

    /** A plain plate in the current shape, for folder backgrounds. */
    Path shapePath(float size) {
        Path p = new Path();
        float s = size;
        switch (shape) {
            case CIRCLE:
                p.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW);
                break;
            case SQUIRCLE:
                // Superellipse |x|^4 + |y|^4 = 1, sampled finely enough to look smooth.
                for (int i = 0; i <= 96; i++) {
                    double t = 2 * Math.PI * i / 96;
                    double cos = Math.cos(t), sin = Math.sin(t);
                    float x = (float) (Math.signum(cos) * Math.sqrt(Math.abs(cos)));
                    float y = (float) (Math.signum(sin) * Math.sqrt(Math.abs(sin)));
                    float px = s / 2 + x * s / 2, py = s / 2 + y * s / 2;
                    if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                }
                p.close();
                break;
            case ROUNDED:
                p.addRoundRect(new RectF(0, 0, s, s), s * 0.22f, s * 0.22f, Path.Direction.CW);
                break;
            case SQUARE:
                p.addRoundRect(new RectF(0, 0, s, s), s * 0.06f, s * 0.06f, Path.Direction.CW);
                break;
            case TEARDROP: {
                float r = s / 2, small = s * 0.12f;
                p.addRoundRect(new RectF(0, 0, s, s),
                        new float[] {r, r, r, r, small, small, r, r}, Path.Direction.CW);
                break;
            }
            default: {
                // The device's own icon shape, defined on a 100×100 canvas.
                Path sys = new AdaptiveIconDrawable(new ColorDrawable(), new ColorDrawable()).getIconMask();
                Matrix m = new Matrix();
                m.setScale(s / 100f, s / 100f);
                sys.transform(m, p);
                break;
            }
        }
        return p;
    }

    /* ---------- helpers ---------- */

    // Adaptive layers are 108 units with the middle 72 visible: draw them 1.5× and centred.
    private static void drawLayer(Canvas c, Drawable layer) {
        if (layer == null) return;
        int extra = SIZE / 4;
        layer.setBounds(-extra, -extra, SIZE + extra, SIZE + extra);
        layer.draw(c);
    }

    private static void drawInset(Canvas c, Drawable d, float inset) {
        int i = Math.round(SIZE * inset);
        d.setBounds(i, i, SIZE - i, SIZE - i);
        d.draw(c);
    }

    private void fillPlate(Canvas c, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawPath(shapePath(SIZE), p);
    }

    /** Keeps only what's inside the shape, with anti-aliased edges. */
    private void mask(Bitmap bmp) {
        Bitmap m = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        new Canvas(m).drawPath(shapePath(SIZE), fill);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        new Canvas(bmp).drawBitmap(m, 0, 0, p);
        m.recycle();
    }

    /**
     * An accent-coloured silhouette for icons without a monochrome layer.
     * Layered icons: the foreground's opaque parts. Flat icons: if the icon has its
     * own solid plate, the parts that differ from that plate's colour; otherwise
     * its opaque parts. Detailed or photographic icons come out rough.
     */
    private Bitmap silhouette(Drawable raw) {
        Bitmap src = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        boolean adaptive = raw instanceof AdaptiveIconDrawable;
        if (adaptive) drawLayer(c, ((AdaptiveIconDrawable) raw).getForeground());
        else drawInset(c, raw, 0.16f);

        int[] px = new int[SIZE * SIZE];
        src.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);

        int plateColor = 0;
        boolean hasPlate = false;
        if (!adaptive) {
            // Sample a ring just inside the drawn icon: mostly opaque means it brings its own plate.
            int lo = Math.round(SIZE * 0.20f), hi = SIZE - 1 - lo;
            long r = 0, g = 0, b = 0;
            int opaque = 0, total = 0;
            for (int i = lo; i <= hi; i += 2) {
                for (int[] xy : new int[][] {{i, lo}, {i, hi}, {lo, i}, {hi, i}}) {
                    int v = px[xy[1] * SIZE + xy[0]];
                    total++;
                    if (Color.alpha(v) > 200) {
                        opaque++;
                        r += Color.red(v); g += Color.green(v); b += Color.blue(v);
                    }
                }
            }
            if (total > 0 && opaque > total * 0.6f) {
                hasPlate = true;
                plateColor = Color.rgb((int) (r / opaque), (int) (g / opaque), (int) (b / opaque));
            }
        }

        // When a foreground fills the whole tile it has no usable shape; fall back to contrast.
        if (adaptive) {
            int opaque = 0;
            for (int v : px) if (Color.alpha(v) > 200) opaque++;
            if (opaque > px.length * 0.85f) {
                hasPlate = true;
                plateColor = px[(SIZE / 2) * SIZE + SIZE / 8]; // left-middle, usually backdrop
            }
        }

        for (int i = 0; i < px.length; i++) {
            int v = px[i];
            int a = Color.alpha(v);
            boolean on;
            if (hasPlate) on = a > 128 && distance(v, plateColor) > 70;
            else on = a > 96;
            px[i] = on ? (accent.start & 0x00FFFFFF) | (Math.min(255, a) << 24) : 0;
        }
        src.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        return src;
    }

    /** Recolours a glyph's opaque parts with the accent gradient, keeping its alpha. */
    private void colourise(Bitmap glyph) {
        if (!accent.gradient) return; // already drawn in the solid colour
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(accent.shader(0, 0, SIZE, SIZE, 255));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        new Canvas(glyph).drawRect(0, 0, SIZE, SIZE, p);
    }

    private static int distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b);
        int dg = Color.green(a) - Color.green(b);
        int db = Color.blue(a) - Color.blue(b);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private static int blend(int c1, int c2, float t) {
        return Color.rgb(lerp(Color.red(c1), Color.red(c2), t),
                lerp(Color.green(c1), Color.green(c2), t),
                lerp(Color.blue(c1), Color.blue(c2), t));
    }
}
