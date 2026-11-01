/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** Appearance settings, stored in the app's private shared preferences. */
final class Prefs {

    /** Accent choices offered in Settings, first is the default (aquamarine). */
    static final int[] ACCENTS = {
            0xFF06B6D4, // aquamarine
            0xFF3B82F6, // blue
            0xFF6C7EC5, // periwinkle
            0xFF14B8A6, // teal
            0xFF22C55E, // green
            0xFFA855F7, // violet
            0xFFF43F5E, // rose
            0xFFE6F0FF, // near-white
    };

    static final int BG_RGB = 0x0F1A2A; // drawer and dock base colour, without alpha

    private static final String FILE = "hearth";
    private static final String DRAWER_OPACITY = "drawer_opacity";
    private static final String DOCK_OPACITY = "dock_opacity";
    private static final String ACCENT = "accent";
    private static final String SHOW_CLOCK = "show_clock";
    private static final String SHOW_DOCK = "show_dock";
    private static final String HOME_PROMPT_SHOWN = "home_prompt_shown";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 0–100. */
    int drawerOpacity() { return sp.getInt(DRAWER_OPACITY, 95); }
    void setDrawerOpacity(int v) { sp.edit().putInt(DRAWER_OPACITY, clamp(v)).apply(); }

    /** 0–100; 0 makes the dock background invisible. */
    int dockOpacity() { return sp.getInt(DOCK_OPACITY, 35); }
    void setDockOpacity(int v) { sp.edit().putInt(DOCK_OPACITY, clamp(v)).apply(); }

    int accent() { return sp.getInt(ACCENT, ACCENTS[0]); }
    void setAccent(int color) { sp.edit().putInt(ACCENT, color).apply(); }

    boolean showClock() { return sp.getBoolean(SHOW_CLOCK, true); }
    void setShowClock(boolean v) { sp.edit().putBoolean(SHOW_CLOCK, v).apply(); }

    boolean showDock() { return sp.getBoolean(SHOW_DOCK, true); }
    void setShowDock(boolean v) { sp.edit().putBoolean(SHOW_DOCK, v).apply(); }

    boolean homePromptShown() { return sp.getBoolean(HOME_PROMPT_SHOWN, false); }
    void setHomePromptShown() { sp.edit().putBoolean(HOME_PROMPT_SHOWN, true).apply(); }

    /** The base background colour at a 0–100 opacity. */
    static int background(int opacity) {
        return Color.argb(Math.round(clamp(opacity) * 2.55f),
                Color.red(BG_RGB), Color.green(BG_RGB), Color.blue(BG_RGB));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
