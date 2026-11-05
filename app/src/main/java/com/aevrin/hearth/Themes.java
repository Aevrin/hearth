/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Ready-made looks. A theme only sets colours, icon style and backgrounds (in the
 * same format as a backup, so applying one is a partial restore); the layout,
 * apps, folders, gestures and clock settings are left alone. The look in place
 * before a theme is kept so it can be undone.
 */
final class Themes {

    static final class Theme {
        final int name;
        final int surface, accent, accentEnd;
        final boolean gradient;
        final JSONObject settings;
        Theme(int name, int surface, int accent, int accentEnd, boolean gradient, JSONObject settings) {
            this.name = name;
            this.surface = surface;
            this.accent = accent;
            this.accentEnd = accentEnd;
            this.gradient = gradient;
            this.settings = settings;
        }
    }

    /** Every setting a theme may change; what's saved for Undo. */
    private static final String[] KEYS = {
            Prefs.SURFACE, Prefs.ACCENT, Prefs.ACCENT_END, Prefs.ACCENT_GRADIENT, Prefs.ACCENT_DIRECTION,
            Prefs.DRAWER_OPACITY, Prefs.DOCK_OPACITY, Prefs.ICON_STYLE, Prefs.ICON_SHAPE,
            Prefs.LABEL_ACCENT, Prefs.LABEL_SHADOW, Prefs.STATUS_ICONS, Prefs.NAV_ICONS,
    };
    private static final String[] BG_SUFFIXES = {"_bg_mode", "_bg_color", "_bg_color2", "_bg_direction", "_bg_opacity"};

    private Themes() { }

    private static String hex(int c) {
        return String.format("#%08X", c | 0xFF000000);
    }

    /**
     * One theme. Surfaces (folders, sidebars) go back to the theme's own base at
     * the given opacities; `drawer`/`dock` are opacities too.
     */
    private static Theme make(int name, int surface, int accent, int accentEnd, boolean gradient, int direction,
                              int drawer, int dock, String iconStyle, String iconShape,
                              boolean labelAccent, boolean labelShadow, int barIcons,
                              int folderPanel, int folderPlate, int sidebars) {
        JSONObject o = new JSONObject();
        try {
            o.put(Prefs.SURFACE, hex(surface))
                    .put(Prefs.ACCENT, hex(accent))
                    .put(Prefs.ACCENT_END, hex(accentEnd))
                    .put(Prefs.ACCENT_GRADIENT, gradient)
                    .put(Prefs.ACCENT_DIRECTION, direction)
                    .put(Prefs.DRAWER_OPACITY, drawer)
                    .put(Prefs.DOCK_OPACITY, dock)
                    .put(Prefs.ICON_STYLE, iconStyle)
                    .put(Prefs.ICON_SHAPE, iconShape)
                    .put(Prefs.LABEL_ACCENT, labelAccent)
                    .put(Prefs.LABEL_SHADOW, labelShadow)
                    .put(Prefs.STATUS_ICONS, barIcons)
                    .put(Prefs.NAV_ICONS, barIcons);
            for (String surfaceKey : Prefs.BG_SURFACES) {
                int opacity = Prefs.FOLDER_PANEL.equals(surfaceKey) ? folderPanel
                        : Prefs.FOLDER_PLATE.equals(surfaceKey) ? folderPlate : sidebars;
                o.put(surfaceKey + "_bg_mode", Prefs.SB_THEME).put(surfaceKey + "_bg_opacity", opacity);
            }
        } catch (JSONException ignored) { }
        return new Theme(name, surface, accent, accentEnd, gradient, o);
    }

    static final Theme[] ALL = {
            // The original look: navy, aquamarine.
            make(R.string.theme_hearth, 0x0F1A2A, 0x06B6D4, 0x06B6D4, false, Accent.DIAGONAL,
                    95, 35, IconTheme.ORIGINAL, IconTheme.SHAPE_SYSTEM, false, true, Prefs.ICONS_AUTO, 90, 70, 90),
            // True black, for OLED screens; white accent, monochrome icons.
            make(R.string.theme_black, 0x000000, 0xE6F0FF, 0xE6F0FF, false, Accent.DIAGONAL,
                    100, 0, IconTheme.THEMED, IconTheme.CIRCLE, false, true, Prefs.ICONS_LIGHT, 100, 100, 100),
            // Light grey-white, dark text, deep blue accent.
            make(R.string.theme_white, 0xF4F6FA, 0x3D52A0, 0x3D52A0, false, Accent.DIAGONAL,
                    97, 80, IconTheme.ORIGINAL, IconTheme.SQUIRCLE, false, false, Prefs.ICONS_DARK, 97, 85, 97),
            // From Aevrin's own setup: blue-to-aquamarine gradient, see-through drawer, themed circles.
            make(R.string.theme_aevrin, 0x0F1A2A, 0x3D52A0, 0x06B6D4, true, Accent.DIAGONAL,
                    0, 30, IconTheme.THEMED, IconTheme.CIRCLE, true, true, Prefs.ICONS_DARK, 90, 70, 20),
            // Deep green, green-to-teal.
            make(R.string.theme_forest, 0x0F1F17, 0x22C55E, 0x14B8A6, true, Accent.DIAGONAL,
                    92, 35, IconTheme.DUOTONE, IconTheme.SQUIRCLE, false, true, Prefs.ICONS_AUTO, 92, 75, 92),
            // Plum night, violet-to-rose.
            make(R.string.theme_dusk, 0x1A1326, 0xA855F7, 0xF43F5E, true, Accent.HORIZONTAL,
                    90, 30, IconTheme.THEMED, IconTheme.TEARDROP, true, true, Prefs.ICONS_AUTO, 90, 70, 90),
            // Warm paper, ink-blue accent.
            make(R.string.theme_paper, 0xFAF7F0, 0x375A82, 0x375A82, false, Accent.DIAGONAL,
                    98, 60, IconTheme.ORIGINAL, IconTheme.ROUNDED, false, false, Prefs.ICONS_DARK, 98, 80, 98),
            // Greys only.
            make(R.string.theme_mono, 0x121212, 0x9CA3AF, 0x9CA3AF, false, Accent.DIAGONAL,
                    95, 25, IconTheme.GRAYSCALE, IconTheme.CIRCLE, false, true, Prefs.ICONS_AUTO, 95, 80, 95),
    };

    /** Applies a theme, saving the current look first so it can be undone. */
    static void apply(Prefs prefs, Theme t) throws JSONException {
        prefs.saveThemeUndo(snapshot(prefs));
        prefs.fromJson(t.settings);
    }

    /** Puts back the look from before the last theme. */
    static boolean undo(Prefs prefs) throws JSONException {
        String saved = prefs.themeUndo();
        if (saved == null) return false;
        prefs.fromJson(new JSONObject(saved));
        prefs.saveThemeUndo(null);
        return true;
    }

    private static String snapshot(Prefs prefs) throws JSONException {
        JSONObject all = prefs.toJson(), out = new JSONObject();
        for (String k : KEYS) if (all.has(k)) out.put(k, all.get(k));
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            for (String surfaceKey : Prefs.BG_SURFACES) {
                for (String suffix : BG_SUFFIXES) if (k.equals(surfaceKey + suffix)) out.put(k, all.get(k));
            }
        }
        return out.toString();
    }
}
