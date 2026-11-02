/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Every user setting, stored in the app's private shared preferences. */
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

    static final float[] ICON_SCALES = {0.85f, 1f, 1.15f}; // small, medium, large
    static final String CLOCK_SYSTEM = "system", CLOCK_12 = "12", CLOCK_24 = "24";

    static final int BG_RGB = 0x0F1A2A; // drawer and dock base colour, without alpha

    private static final String FILE = "hearth";

    // Keys. Each one below is also covered by toJson/fromJson for backups.
    static final String DRAWER_OPACITY = "drawer_opacity";
    static final String DOCK_OPACITY = "dock_opacity";
    static final String ACCENT = "accent";            // solid colour, or the gradient's start
    static final String ACCENT_END = "accent_end";
    static final String ACCENT_GRADIENT = "accent_gradient";
    static final String ACCENT_DIRECTION = "accent_direction";
    static final String SHOW_CLOCK = "show_clock";
    static final String SHOW_DATE = "show_date";
    static final String SHOW_SECONDS = "show_seconds";
    static final String CLOCK_FORMAT = "clock_format";
    static final String SHOW_DOCK = "show_dock";
    static final String COLUMNS = "columns";
    static final String ROWS = "rows";
    static final String PAGES = "pages";
    static final String DOCK_SLOTS = "dock_slots";
    static final String DRAWER_COLUMNS = "drawer_columns";
    static final String ICON_SIZE = "icon_size";
    static final String HOME_LABELS = "home_labels";
    static final String DRAWER_LABELS = "drawer_labels";
    static final String HIDDEN = "hidden_apps";
    static final String HIDDEN_SEARCHABLE = "hidden_searchable";
    static final String LABELS = "custom_labels";
    static final String ICON_STYLE = "icon_style";
    static final String ICON_SHAPE = "icon_shape";
    static final String ICON_OVERRIDES = "icon_overrides";
    static final String DOCK_ICON_SIZE = "dock_icon_size";
    static final String DRAWER_ICON_SIZE = "drawer_icon_size";
    static final String LABEL_SIZE = "label_size";
    static final String LABEL_ACCENT = "label_accent";
    static final String LABEL_SHADOW = "label_shadow";
    static final String LABEL_TWO_LINES = "label_two_lines";
    static final String LOCK_LAYOUT = "lock_layout";
    static final String DEFAULT_PAGE = "default_page";
    static final String SCROLL_WALLPAPER = "scroll_wallpaper";
    static final String ALLOW_ROTATION = "allow_rotation";
    static final String DOCK_PAGES = "dock_pages";
    static final String AZ_SCROLLER = "az_scroller";
    static final String MOST_USED = "most_used";
    static final String SUBGRID = "subgrid";
    static final String SEARCH_ON_PULL = "search_on_pull";
    static final String STATUS_ICONS = "status_icons";
    static final String NAV_ICONS = "nav_icons";
    /** System bar icon shade: follow the wallpaper, always light, always dark. */
    static final int ICONS_AUTO = 0, ICONS_LIGHT = 1, ICONS_DARK = 2;
    static final String GESTURE_UP = "gesture_up";
    static final String GESTURE_DOWN = "gesture_down";
    static final String GESTURE_LEFT = "gesture_left";
    static final String GESTURE_RIGHT = "gesture_right";

    /** Gesture actions. Also "app:<key>" to open a particular app. */
    static final String G_NONE = "none", G_DRAWER = "drawer", G_SEARCH = "search",
            G_NOTIFICATIONS = "notifications", G_QUICK_SETTINGS = "quick_settings", G_SETTINGS = "settings";
    static final String G_APP_PREFIX = "app:";
    static final String[] GESTURE_ACTIONS = {G_NONE, G_DRAWER, G_SEARCH, G_NOTIFICATIONS, G_QUICK_SETTINGS, G_SETTINGS};
    private static final String LAUNCH_COUNTS = "launch_counts"; // not backed up: usage, not settings
    private static final String HOME_PROMPT_SHOWN = "home_prompt_shown"; // not backed up

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /* ---------- appearance ---------- */

    /** 0–100. */
    int drawerOpacity() { return sp.getInt(DRAWER_OPACITY, 95); }
    void setDrawerOpacity(int v) { sp.edit().putInt(DRAWER_OPACITY, clamp(v, 0, 100)).apply(); }

    /** 0–100; 0 makes the dock background invisible. */
    int dockOpacity() { return sp.getInt(DOCK_OPACITY, 35); }
    void setDockOpacity(int v) { sp.edit().putInt(DOCK_OPACITY, clamp(v, 0, 100)).apply(); }

    /** The accent as a single colour: the solid colour, or a gradient's midpoint. */
    int accent() { return accentStyle().solid(); }

    /** The full accent, gradient included. */
    Accent accentStyle() {
        int start = sp.getInt(ACCENT, ACCENTS[0]);
        return new Accent(start, sp.getInt(ACCENT_END, start),
                sp.getBoolean(ACCENT_GRADIENT, false), sp.getInt(ACCENT_DIRECTION, Accent.DIAGONAL));
    }

    /** A single accent colour (turns a gradient off). */
    void setAccent(int color) {
        sp.edit().putInt(ACCENT, color).putBoolean(ACCENT_GRADIENT, false).apply();
    }

    void setAccentGradient(int start, int end, int direction) {
        sp.edit().putInt(ACCENT, start).putInt(ACCENT_END, end)
                .putInt(ACCENT_DIRECTION, direction).putBoolean(ACCENT_GRADIENT, true).apply();
    }

    boolean showClock() { return sp.getBoolean(SHOW_CLOCK, true); }
    void setShowClock(boolean v) { sp.edit().putBoolean(SHOW_CLOCK, v).apply(); }

    boolean showDate() { return sp.getBoolean(SHOW_DATE, true); }

    boolean showSeconds() { return sp.getBoolean(SHOW_SECONDS, false); }
    void setShowSeconds(boolean v) { sp.edit().putBoolean(SHOW_SECONDS, v).apply(); }
    void setShowDate(boolean v) { sp.edit().putBoolean(SHOW_DATE, v).apply(); }

    /** CLOCK_SYSTEM, CLOCK_12 or CLOCK_24. */
    String clockFormat() { return sp.getString(CLOCK_FORMAT, CLOCK_SYSTEM); }
    void setClockFormat(String v) { sp.edit().putString(CLOCK_FORMAT, v).apply(); }

    boolean showDock() { return sp.getBoolean(SHOW_DOCK, true); }
    void setShowDock(boolean v) { sp.edit().putBoolean(SHOW_DOCK, v).apply(); }

    /** 0 small, 1 medium, 2 large. */
    int iconSize() { return clamp(sp.getInt(ICON_SIZE, 1), 0, ICON_SCALES.length - 1); }
    void setIconSize(int v) { sp.edit().putInt(ICON_SIZE, clamp(v, 0, ICON_SCALES.length - 1)).apply(); }
    float iconScale() { return ICON_SCALES[iconSize()]; }

    boolean homeLabels() { return sp.getBoolean(HOME_LABELS, true); }
    void setHomeLabels(boolean v) { sp.edit().putBoolean(HOME_LABELS, v).apply(); }

    boolean drawerLabels() { return sp.getBoolean(DRAWER_LABELS, true); }
    void setDrawerLabels(boolean v) { sp.edit().putBoolean(DRAWER_LABELS, v).apply(); }

    /** Dock and drawer icon sizes: 0 small, 1 medium, 2 large, like the home one. */
    int dockIconSize() { return clamp(sp.getInt(DOCK_ICON_SIZE, 1), 0, ICON_SCALES.length - 1); }
    void setDockIconSize(int v) { sp.edit().putInt(DOCK_ICON_SIZE, clamp(v, 0, ICON_SCALES.length - 1)).apply(); }

    int drawerIconSize() { return clamp(sp.getInt(DRAWER_ICON_SIZE, 1), 0, ICON_SCALES.length - 1); }
    void setDrawerIconSize(int v) { sp.edit().putInt(DRAWER_ICON_SIZE, clamp(v, 0, ICON_SCALES.length - 1)).apply(); }

    /** Label text: 0 small (11sp), 1 medium (12sp), 2 large (14sp). */
    int labelSize() { return clamp(sp.getInt(LABEL_SIZE, 1), 0, 2); }
    void setLabelSize(int v) { sp.edit().putInt(LABEL_SIZE, clamp(v, 0, 2)).apply(); }
    float labelSp() { return new float[] {11f, 12f, 14f}[labelSize()]; }

    boolean labelAccent() { return sp.getBoolean(LABEL_ACCENT, false); }
    void setLabelAccent(boolean v) { sp.edit().putBoolean(LABEL_ACCENT, v).apply(); }

    boolean labelShadow() { return sp.getBoolean(LABEL_SHADOW, true); }
    void setLabelShadow(boolean v) { sp.edit().putBoolean(LABEL_SHADOW, v).apply(); }

    boolean labelTwoLines() { return sp.getBoolean(LABEL_TWO_LINES, false); }
    void setLabelTwoLines(boolean v) { sp.edit().putBoolean(LABEL_TWO_LINES, v).apply(); }

    /** Applies the label settings to one label. */
    void styleLabel(android.widget.TextView t) {
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelSp());
        if (labelAccent()) accentStyle().paint(t); else Accent.plain(t, 0xFFE6F0FF);
        if (labelShadow()) t.setShadowLayer(4f, 0f, 1f, 0xCC000000); else t.setShadowLayer(0f, 0f, 0f, 0);
        t.setMaxLines(labelTwoLines() ? 2 : 1);
    }

    /* ---------- behaviour ---------- */

    boolean lockLayout() { return sp.getBoolean(LOCK_LAYOUT, false); }
    void setLockLayout(boolean v) { sp.edit().putBoolean(LOCK_LAYOUT, v).apply(); }

    /** The page Home returns to, 0-based. */
    int defaultPage() { return Math.max(0, Math.min(pages() - 1, sp.getInt(DEFAULT_PAGE, 0))); }
    void setDefaultPage(int v) { sp.edit().putInt(DEFAULT_PAGE, Math.max(0, v)).apply(); }

    boolean scrollWallpaper() { return sp.getBoolean(SCROLL_WALLPAPER, true); }
    void setScrollWallpaper(boolean v) { sp.edit().putBoolean(SCROLL_WALLPAPER, v).apply(); }

    boolean allowRotation() { return sp.getBoolean(ALLOW_ROTATION, false); }
    void setAllowRotation(boolean v) { sp.edit().putBoolean(ALLOW_ROTATION, v).apply(); }

    boolean azScroller() { return sp.getBoolean(AZ_SCROLLER, true); }
    void setAzScroller(boolean v) { sp.edit().putBoolean(AZ_SCROLLER, v).apply(); }

    boolean mostUsed() { return sp.getBoolean(MOST_USED, true); }

    /** Home on a half-cell grid: icons and widgets can sit between whole cells. */
    boolean subgrid() { return sp.getBoolean(SUBGRID, false); }

    /** The drawer opens without its search bar; pulling down at the top reveals it. */
    boolean searchOnPull() { return sp.getBoolean(SEARCH_ON_PULL, false); }
    void setSearchOnPull(boolean v) { sp.edit().putBoolean(SEARCH_ON_PULL, v).apply(); }

    int statusIcons() { return clamp(sp.getInt(STATUS_ICONS, ICONS_AUTO), ICONS_AUTO, ICONS_DARK); }
    void setStatusIcons(int v) { sp.edit().putInt(STATUS_ICONS, v).apply(); }
    int navIcons() { return clamp(sp.getInt(NAV_ICONS, ICONS_AUTO), ICONS_AUTO, ICONS_DARK); }
    void setNavIcons(int v) { sp.edit().putInt(NAV_ICONS, v).apply(); }
    void setSubgrid(boolean v) { sp.edit().putBoolean(SUBGRID, v).apply(); }

    /** What a swipe does: one of GESTURE_ACTIONS, or "app:<key>". */
    String gesture(String which) {
        String def = GESTURE_UP.equals(which) ? G_DRAWER : GESTURE_DOWN.equals(which) ? G_NOTIFICATIONS : G_NONE;
        return sp.getString(which, def);
    }
    void setGesture(String which, String action) { sp.edit().putString(which, action).apply(); }

    private static boolean validGesture(String a) {
        if (a.startsWith(G_APP_PREFIX)) return a.length() > G_APP_PREFIX.length();
        for (String g : GESTURE_ACTIONS) if (g.equals(a)) return true;
        return false;
    }
    void setMostUsed(boolean v) { sp.edit().putBoolean(MOST_USED, v).apply(); }

    /** Counts launches made from Hearth itself; no usage-access permission involved. */
    void recordLaunch(String key) {
        JSONObject o = jsonPref(LAUNCH_COUNTS);
        try { o.put(key, o.optInt(key, 0) + 1); } catch (JSONException ignored) { }
        sp.edit().putString(LAUNCH_COUNTS, o.toString()).apply();
    }

    /** Most-launched keys, highest first, with at least `min` launches. */
    java.util.List<String> mostLaunched(int limit, int min) {
        JSONObject o = jsonPref(LAUNCH_COUNTS);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (o.optInt(k, 0) >= min) keys.add(k);
        }
        keys.sort((a, b) -> Integer.compare(o.optInt(b, 0), o.optInt(a, 0)));
        return keys.size() > limit ? keys.subList(0, limit) : keys;
    }

    /* ---------- layout ---------- */

    int columns() { return clamp(sp.getInt(COLUMNS, 5), 3, 8); }
    void setColumns(int v) { sp.edit().putInt(COLUMNS, clamp(v, 3, 8)).apply(); }

    int rows() { return clamp(sp.getInt(ROWS, 6), 3, 9); }
    void setRows(int v) { sp.edit().putInt(ROWS, clamp(v, 3, 9)).apply(); }

    int pages() { return clamp(sp.getInt(PAGES, 1), 1, 7); }
    void setPages(int v) { sp.edit().putInt(PAGES, clamp(v, 1, 7)).apply(); }

    int dockSlots() { return clamp(sp.getInt(DOCK_SLOTS, 5), 3, 7); }

    /** 1 = a fixed dock; more = a dock you can swipe sideways through. */
    int dockPages() { return clamp(sp.getInt(DOCK_PAGES, 1), 1, 3); }
    void setDockPages(int v) { sp.edit().putInt(DOCK_PAGES, clamp(v, 1, 3)).apply(); }
    void setDockSlots(int v) { sp.edit().putInt(DOCK_SLOTS, clamp(v, 3, 7)).apply(); }

    int drawerColumns() { return clamp(sp.getInt(DRAWER_COLUMNS, 5), 3, 7); }
    void setDrawerColumns(int v) { sp.edit().putInt(DRAWER_COLUMNS, clamp(v, 3, 7)).apply(); }

    /* ---------- apps ---------- */

    Set<String> hidden() {
        return Collections.unmodifiableSet(new HashSet<>(sp.getStringSet(HIDDEN, Collections.emptySet())));
    }

    void setHidden(String key, boolean hide) {
        Set<String> s = new HashSet<>(sp.getStringSet(HIDDEN, Collections.emptySet()));
        if (hide) s.add(key); else s.remove(key);
        sp.edit().putStringSet(HIDDEN, s).apply();
    }

    void setHiddenAll(Set<String> keys) {
        sp.edit().putStringSet(HIDDEN, new HashSet<>(keys)).apply();
    }

    /** Hidden apps still turn up when you search, so they stay reachable. */
    boolean hiddenSearchable() { return sp.getBoolean(HIDDEN_SEARCHABLE, true); }
    void setHiddenSearchable(boolean v) { sp.edit().putBoolean(HIDDEN_SEARCHABLE, v).apply(); }

    /** Custom name for an app, or null for its own label. */
    String customLabel(String key) {
        String v = labels().optString(key, "");
        return v.isEmpty() ? null : v;
    }

    void setCustomLabel(String key, String label) {
        JSONObject o = labels();
        try {
            if (label == null || label.trim().isEmpty()) o.remove(key); else o.put(key, label.trim());
        } catch (JSONException ignored) { }
        sp.edit().putString(LABELS, o.toString()).apply();
    }

    private JSONObject labels() {
        try {
            return new JSONObject(sp.getString(LABELS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /* ---------- icon theme ---------- */

    String iconStyle() { return oneOf(sp.getString(ICON_STYLE, IconTheme.ORIGINAL), IconTheme.STYLES, IconTheme.ORIGINAL); }
    void setIconStyle(String v) { sp.edit().putString(ICON_STYLE, v).apply(); }

    String iconShape() { return oneOf(sp.getString(ICON_SHAPE, IconTheme.SHAPE_SYSTEM), IconTheme.SHAPES, IconTheme.SHAPE_SYSTEM); }
    void setIconShape(String v) { sp.edit().putString(ICON_SHAPE, v).apply(); }

    /** Per-app style overrides, app key to style. */
    java.util.Map<String, String> iconOverrides() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        JSONObject o = jsonPref(ICON_OVERRIDES);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = o.optString(k);
            for (String st : IconTheme.STYLES) if (st.equals(v)) m.put(k, v);
        }
        return m;
    }

    /** null clears the override, so the app follows the theme again. */
    void setIconOverride(String key, String style) {
        JSONObject o = jsonPref(ICON_OVERRIDES);
        try {
            if (style == null) o.remove(key); else o.put(key, style);
        } catch (JSONException ignored) { }
        sp.edit().putString(ICON_OVERRIDES, o.toString()).apply();
    }

    private JSONObject jsonPref(String key) {
        try {
            return new JSONObject(sp.getString(key, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static String oneOf(String v, String[] allowed, String fallback) {
        for (String a : allowed) if (a.equals(v)) return v;
        return fallback;
    }

    /* ---------- first-run prompt ---------- */

    boolean homePromptShown() { return sp.getBoolean(HOME_PROMPT_SHOWN, false); }
    void setHomePromptShown() { sp.edit().putBoolean(HOME_PROMPT_SHOWN, true).apply(); }

    /* ---------- backup ---------- */

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put(DRAWER_OPACITY, drawerOpacity());
        o.put(DOCK_OPACITY, dockOpacity());
        Accent a = accentStyle();
        o.put(ACCENT, String.format("#%08X", a.start));
        o.put(ACCENT_END, String.format("#%08X", a.end));
        o.put(ACCENT_GRADIENT, a.gradient);
        o.put(ACCENT_DIRECTION, a.direction);
        o.put(SHOW_CLOCK, showClock());
        o.put(SHOW_DATE, showDate());
        o.put(SHOW_SECONDS, showSeconds());
        o.put(CLOCK_FORMAT, clockFormat());
        o.put(SHOW_DOCK, showDock());
        o.put(COLUMNS, columns());
        o.put(ROWS, rows());
        o.put(PAGES, pages());
        o.put(DOCK_SLOTS, dockSlots());
        o.put(DRAWER_COLUMNS, drawerColumns());
        o.put(ICON_SIZE, iconSize());
        o.put(HOME_LABELS, homeLabels());
        o.put(DRAWER_LABELS, drawerLabels());
        o.put(HIDDEN, new org.json.JSONArray(hidden()));
        o.put(HIDDEN_SEARCHABLE, hiddenSearchable());
        o.put(LABELS, labels());
        o.put(ICON_STYLE, iconStyle());
        o.put(ICON_SHAPE, iconShape());
        o.put(ICON_OVERRIDES, jsonPref(ICON_OVERRIDES));
        o.put(DOCK_ICON_SIZE, dockIconSize());
        o.put(DRAWER_ICON_SIZE, drawerIconSize());
        o.put(LABEL_SIZE, labelSize());
        o.put(LABEL_ACCENT, labelAccent());
        o.put(LABEL_SHADOW, labelShadow());
        o.put(LABEL_TWO_LINES, labelTwoLines());
        o.put(LOCK_LAYOUT, lockLayout());
        o.put(DEFAULT_PAGE, defaultPage());
        o.put(SCROLL_WALLPAPER, scrollWallpaper());
        o.put(ALLOW_ROTATION, allowRotation());
        o.put(DOCK_PAGES, dockPages());
        o.put(AZ_SCROLLER, azScroller());
        o.put(MOST_USED, mostUsed());
        o.put(SUBGRID, subgrid());
        o.put(SEARCH_ON_PULL, searchOnPull());
        o.put(STATUS_ICONS, statusIcons());
        o.put(NAV_ICONS, navIcons());
        for (String g : new String[] {GESTURE_UP, GESTURE_DOWN, GESTURE_LEFT, GESTURE_RIGHT}) o.put(g, gesture(g));
        return o;
    }

    /** Applies a backup. Unknown keys are ignored; missing keys keep their current value. */
    void fromJson(JSONObject o) throws JSONException {
        SharedPreferences.Editor e = sp.edit();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            switch (k) {
                case DRAWER_OPACITY: case DOCK_OPACITY: case COLUMNS: case ROWS: case PAGES:
                case DOCK_SLOTS: case DRAWER_COLUMNS: case ICON_SIZE:
                case DOCK_ICON_SIZE: case DRAWER_ICON_SIZE: case LABEL_SIZE: case DEFAULT_PAGE: case DOCK_PAGES:
                case ACCENT_DIRECTION: case STATUS_ICONS: case NAV_ICONS:
                    e.putInt(k, o.getInt(k));
                    break;
                case SHOW_CLOCK: case SHOW_DATE: case SHOW_SECONDS: case SHOW_DOCK: case HOME_LABELS:
                case DRAWER_LABELS: case HIDDEN_SEARCHABLE:
                case LABEL_ACCENT: case LABEL_SHADOW: case LABEL_TWO_LINES: case LOCK_LAYOUT:
                case SCROLL_WALLPAPER: case ALLOW_ROTATION: case AZ_SCROLLER: case MOST_USED:
                case ACCENT_GRADIENT: case SUBGRID: case SEARCH_ON_PULL:
                    e.putBoolean(k, o.getBoolean(k));
                    break;
                case GESTURE_UP: case GESTURE_DOWN: case GESTURE_LEFT: case GESTURE_RIGHT: {
                    String g = o.getString(k);
                    if (validGesture(g)) e.putString(k, g);
                    break;
                }
                case CLOCK_FORMAT:
                    String f = o.getString(k);
                    if (f.equals(CLOCK_SYSTEM) || f.equals(CLOCK_12) || f.equals(CLOCK_24)) e.putString(k, f);
                    break;
                case ACCENT:
                case ACCENT_END:
                    e.putInt(k, Color.parseColor(o.getString(k)));
                    break;
                case HIDDEN: {
                    Set<String> s = new HashSet<>();
                    org.json.JSONArray a = o.getJSONArray(k);
                    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                    e.putStringSet(k, s);
                    break;
                }
                case LABELS:
                case ICON_OVERRIDES:
                    e.putString(k, o.getJSONObject(k).toString());
                    break;
                case ICON_STYLE:
                    e.putString(k, oneOf(o.getString(k), IconTheme.STYLES, IconTheme.ORIGINAL));
                    break;
                case ICON_SHAPE:
                    e.putString(k, oneOf(o.getString(k), IconTheme.SHAPES, IconTheme.SHAPE_SYSTEM));
                    break;
                default:
                    break;
            }
        }
        e.apply();
    }

    /** The base background colour at a 0–100 opacity. */
    static int background(int opacity) {
        return Color.argb(Math.round(clamp(opacity, 0, 100) * 2.55f),
                Color.red(BG_RGB), Color.green(BG_RGB), Color.blue(BG_RGB));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
