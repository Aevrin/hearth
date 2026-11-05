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
    static final String GESTURE_DOUBLE_TAP = "gesture_double_tap";
    static final String GESTURE_TWO_UP = "gesture_two_up";
    static final String GESTURE_TWO_DOWN = "gesture_two_down";
    static final String[] ALL_GESTURES = {GESTURE_UP, GESTURE_DOWN, GESTURE_LEFT, GESTURE_RIGHT,
            GESTURE_DOUBLE_TAP, GESTURE_TWO_UP, GESTURE_TWO_DOWN};
    static final String SIDEBAR_LEFT = "sidebar_left";
    static final String SIDEBAR_RIGHT = "sidebar_right";
    static final String SIDEBAR_LEFT_ITEMS = "sidebar_left_items";
    static final String SIDEBAR_RIGHT_ITEMS = "sidebar_right_items";
    static final String SIDEBAR_HANDLE_POS = "sidebar_handle_pos";
    static final String SIDEBAR_HANDLE_SHOWN = "sidebar_handle_shown";
    static final String SIDEBAR_ZONE = "sidebar_zone";
    static final String FOLDER_SPACING = "folder_spacing";
    static final String SHOW_NEXT_ALARM = "show_next_alarm";
    static final String SHOW_BATTERY = "show_battery";
    static final String NEW_DOTS = "new_dots";
    static final String SEEN_NEW = "seen_new"; // not backed up
    static final String DRAWER_SORT = "drawer_sort";
    static final String DOCK_WRAP = "dock_wrap";
    static final String DOCK_HOME_RESET = "dock_home_reset";
    static final int SORT_AZ = 0, SORT_MOST_USED = 1, SORT_RECENT = 2;
    /** How long a newly installed app keeps its dot (unless opened sooner). */
    static final long NEW_FOR_MS = 3L * 24 * 60 * 60 * 1000;
    /** Swipe-in zone widths, in dp: narrow, wide, ultra wide. */
    static final int[] ZONE_WIDTHS = {24, 48, 72};
    static final int HANDLE_TOP = 0, HANDLE_MIDDLE = 1, HANDLE_BOTTOM = 2;
    /** Sidebar styles: a narrow column of shortcuts, or a wide panel of widget cards. */
    static final int SB_STYLE_SHORTCUTS = 0, SB_STYLE_PANEL = 1;
    private static final String SB_STYLE = "_style", SB_CARDS = "_cards";
    /** Surfaces with their own background: each sidebar, the open folder, the folder icon's plate. */
    static final String FOLDER_PANEL = "folder_panel", FOLDER_PLATE = "folder_plate";
    static final String[] BG_SURFACES = {SIDEBAR_LEFT, SIDEBAR_RIGHT, FOLDER_PANEL, FOLDER_PLATE};
    /** A surface's background: the theme's, a solid colour, or a gradient. Keys are "<surface>" + suffix. */
    static final int SB_THEME = 0, SB_SOLID = 1, SB_GRADIENT = 2;
    private static final String BG_MODE = "_bg_mode", BG_COLOR = "_bg_color", BG_COLOR2 = "_bg_color2",
            BG_DIRECTION = "_bg_direction", BG_OPACITY = "_bg_opacity";

    /** Gesture actions. Also "app:<key>" to open a particular app. */
    static final String G_NONE = "none", G_DRAWER = "drawer", G_SEARCH = "search",
            G_NOTIFICATIONS = "notifications", G_QUICK_SETTINGS = "quick_settings", G_SETTINGS = "settings",
            G_SIDEBAR_LEFT = "open_sidebar_left", G_SIDEBAR_RIGHT = "open_sidebar_right";
    static final String G_APP_PREFIX = "app:";
    static final String[] GESTURE_ACTIONS = {G_NONE, G_DRAWER, G_SEARCH, G_NOTIFICATIONS, G_QUICK_SETTINGS, G_SETTINGS,
            G_SIDEBAR_LEFT, G_SIDEBAR_RIGHT};
    private static final String LAUNCH_COUNTS = "launch_counts"; // not backed up: usage, not settings
    private static final String HOME_PROMPT_SHOWN = "home_prompt_shown"; // not backed up

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        surfaceRgb = sp.getInt(SURFACE, 0xFF000000 | BG_RGB) & 0x00FFFFFF;
    }

    /* ---------- base surface (themes) ---------- */

    static final String SURFACE = "surface_color";
    static final String THEME_UNDO = "theme_undo"; // not backed up
    /**
     * The base colour behind the drawer, dock, folders and sidebars. Kept in a
     * static so the many static helpers (and every screen) share it; refreshed
     * whenever a Prefs is created or the colour changes.
     */
    private static int surfaceRgb = BG_RGB;

    /** The look saved before the last theme was applied (for Undo), or null. */
    String themeUndo() { return sp.getString(THEME_UNDO, null); }
    void saveThemeUndo(String json) {
        if (json == null) sp.edit().remove(THEME_UNDO).apply();
        else sp.edit().putString(THEME_UNDO, json).apply();
    }

    int surface() { return 0xFF000000 | surfaceRgb; }
    void setSurface(int color) {
        sp.edit().putInt(SURFACE, color | 0xFF000000).apply();
        surfaceRgb = color & 0x00FFFFFF;
    }

    /** A light base (the White and Paper themes): text and icons go dark. */
    static boolean lightSurface() {
        return Color.luminance(0xFF000000 | surfaceRgb) > 0.5f;
    }

    /** Main text on the base colour (and labels over the wallpaper). */
    static int textColor() { return lightSurface() ? 0xFF1A2230 : 0xFFE6F0FF; }
    static int mutedTextColor() { return lightSurface() ? 0x991A2230 : 0x99E6F0FF; }

    /** A slightly raised version of the base: icon plates, search box. */
    static int elevated() {
        int base = 0xFF000000 | surfaceRgb;
        int toward = lightSurface() ? 0xFF000000 : 0xFFFFFFFF;
        float t = lightSurface() ? 0.07f : 0.08f;
        return Color.rgb(Math.round(Color.red(base) + (Color.red(toward) - Color.red(base)) * t),
                Math.round(Color.green(base) + (Color.green(toward) - Color.green(base)) * t),
                Math.round(Color.blue(base) + (Color.blue(toward) - Color.blue(base)) * t));
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
        if (labelAccent()) accentStyle().paint(t); else Accent.plain(t, textColor());
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
        if (Targets.isTarget(a)) return a.indexOf(':') < a.length() - 1;
        for (String g : GESTURE_ACTIONS) if (g.equals(a)) return true;
        return false;
    }

    /* ---------- sidebars ---------- */

    boolean sidebar(String side) { return sp.getBoolean(side, false); }
    void setSidebar(String side, boolean v) { sp.edit().putBoolean(side, v).apply(); }
    int sidebarHandlePos() { return clamp(sp.getInt(SIDEBAR_HANDLE_POS, HANDLE_MIDDLE), HANDLE_TOP, HANDLE_BOTTOM); }
    void setSidebarHandlePos(int v) { sp.edit().putInt(SIDEBAR_HANDLE_POS, v).apply(); }
    boolean sidebarHandleShown() { return sp.getBoolean(SIDEBAR_HANDLE_SHOWN, true); }
    /** Swiping past the last dock page goes round to the first. */
    boolean dockWrap() { return sp.getBoolean(DOCK_WRAP, false); }
    void setDockWrap(boolean v) { sp.edit().putBoolean(DOCK_WRAP, v).apply(); }
    /** Home on the home screen also returns the dock to its first page. */
    boolean dockHomeReset() { return sp.getBoolean(DOCK_HOME_RESET, true); }
    void setDockHomeReset(boolean v) { sp.edit().putBoolean(DOCK_HOME_RESET, v).apply(); }

    boolean showNextAlarm() { return sp.getBoolean(SHOW_NEXT_ALARM, false); }
    void setShowNextAlarm(boolean v) { sp.edit().putBoolean(SHOW_NEXT_ALARM, v).apply(); }
    boolean showBattery() { return sp.getBoolean(SHOW_BATTERY, false); }
    void setShowBattery(boolean v) { sp.edit().putBoolean(SHOW_BATTERY, v).apply(); }
    boolean newDots() { return sp.getBoolean(NEW_DOTS, false); }
    void setNewDots(boolean v) { sp.edit().putBoolean(NEW_DOTS, v).apply(); }
    int drawerSort() { return clamp(sp.getInt(DRAWER_SORT, SORT_AZ), SORT_AZ, SORT_RECENT); }
    void setDrawerSort(int v) { sp.edit().putInt(DRAWER_SORT, v).apply(); }

    /** Installed in the last few days and not opened from Hearth since. */
    boolean isNew(AppEntry e) {
        if (!newDots() || System.currentTimeMillis() - e.installTime > NEW_FOR_MS) return false;
        return !sp.getStringSet(SEEN_NEW, Collections.emptySet()).contains(e.key + "@" + e.installTime);
    }

    /** Opened: no more "new" dot. Only recent installs are remembered, so the list stays short. */
    void markSeen(AppEntry e) {
        if (System.currentTimeMillis() - e.installTime > NEW_FOR_MS) return;
        Set<String> seen = new HashSet<>();
        long cutoff = System.currentTimeMillis() - NEW_FOR_MS;
        for (String s : sp.getStringSet(SEEN_NEW, Collections.emptySet())) {
            int at = s.lastIndexOf('@');
            try {
                if (at > 0 && Long.parseLong(s.substring(at + 1)) >= cutoff) seen.add(s);
            } catch (NumberFormatException ignored) { }
        }
        seen.add(e.key + "@" + e.installTime);
        sp.edit().putStringSet(SEEN_NEW, seen).apply();
    }

    /** Launch counts for every app opened from Hearth, read once. */
    java.util.Map<String, Integer> launchCounts() {
        JSONObject o = jsonPref(LAUNCH_COUNTS);
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.optInt(k, 0));
        }
        return m;
    }

    /** 0–100: space between apps in an open folder (30 matches the original spacing). */
    int folderSpacing() { return clamp(sp.getInt(FOLDER_SPACING, 30), 0, 100); }
    void setFolderSpacing(int v) { sp.edit().putInt(FOLDER_SPACING, clamp(v, 0, 100)).apply(); }

    int sidebarZone() { return clamp(sp.getInt(SIDEBAR_ZONE, 0), 0, ZONE_WIDTHS.length - 1); }
    void setSidebarZone(int v) { sp.edit().putInt(SIDEBAR_ZONE, v).apply(); }
    void setSidebarHandleShown(boolean v) { sp.edit().putBoolean(SIDEBAR_HANDLE_SHOWN, v).apply(); }

    int bgMode(String side) { return clamp(sp.getInt(side + BG_MODE, SB_THEME), SB_THEME, SB_GRADIENT); }
    void setBgMode(String side, int v) { sp.edit().putInt(side + BG_MODE, v).apply(); }
    int bgColor(String side) { return sp.getInt(side + BG_COLOR, 0xFF375A82); }
    void setBgColor(String side, int v) { sp.edit().putInt(side + BG_COLOR, v | 0xFF000000).apply(); }
    int bgColor2(String side) { return sp.getInt(side + BG_COLOR2, 0xFF06B6D4); }
    void setBgColor2(String side, int v) { sp.edit().putInt(side + BG_COLOR2, v | 0xFF000000).apply(); }
    int bgDirection(String side) { return clamp(sp.getInt(side + BG_DIRECTION, Accent.VERTICAL), Accent.DIAGONAL, Accent.VERTICAL); }
    void setBgDirection(String side, int v) { sp.edit().putInt(side + BG_DIRECTION, v).apply(); }
    /** 0–100; 0 is fully see-through. The folder icon's plate starts lighter, as it always was. */
    int bgOpacity(String side) {
        return clamp(sp.getInt(side + BG_OPACITY, FOLDER_PLATE.equals(side) ? 70 : 90), 0, 100);
    }
    void setBgOpacity(String side, int v) { sp.edit().putInt(side + BG_OPACITY, clamp(v, 0, 100)).apply(); }

    /** A surface's background colours as an Accent (for its gradient handling), or null for the theme's. */
    Accent bgFill(String side) {
        int mode = bgMode(side);
        if (mode == SB_THEME) return null;
        int c = bgColor(side);
        return new Accent(c, mode == SB_GRADIENT ? bgColor2(side) : c, mode == SB_GRADIENT, bgDirection(side));
    }

    int sidebarStyle(String side) { return clamp(sp.getInt(side + SB_STYLE, SB_STYLE_SHORTCUTS), 0, 1); }
    void setSidebarStyle(String side, int v) { sp.edit().putInt(side + SB_STYLE, v).apply(); }

    /** One card in a wide sidebar: a widget, a note or a checklist, with its height in dp. */
    static final class Card {
        static final String WIDGET = "widget", NOTE = "note", LIST = "list";
        final String type;
        final int id; // widget id, or note/checklist id
        int height;
        Card(String type, int id, int height) { this.type = type; this.id = id; this.height = height; }
    }

    java.util.List<Card> sidebarCards(String side) {
        java.util.List<Card> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp.getString(side + SB_CARDS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null || !c.has("id")) continue;
                String t = c.optString("type", Card.WIDGET); // older cards were all widgets
                if (!t.equals(Card.WIDGET) && !t.equals(Card.NOTE) && !t.equals(Card.LIST)) continue;
                out.add(new Card(t, c.optInt("id"), clamp(c.optInt("h", 160), 48, 2000)));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    void setSidebarCards(String side, java.util.List<Card> cards) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Card c : cards) a.put(new JSONObject().put("type", c.type).put("id", c.id).put("h", c.height));
        } catch (JSONException ignored) { }
        sp.edit().putString(side + SB_CARDS, a.toString()).apply();
    }

    /** Every widget id used by a sidebar panel (so it isn't tidied away). */
    Set<Integer> sidebarWidgetIds() {
        Set<Integer> ids = new HashSet<>();
        for (String side : new String[] {SIDEBAR_LEFT, SIDEBAR_RIGHT}) {
            for (Card c : sidebarCards(side)) if (Card.WIDGET.equals(c.type)) ids.add(c.id);
        }
        return ids;
    }

    /* ---------- notes and checklists (kept on the phone only) ---------- */

    private static final String NOTE_TEXT = "note_text_", NOTE_LIST = "note_list_", NEXT_NOTE = "next_note_id";
    static final String BACKUP_NOTES = "backup_notes";

    int newNoteId() {
        int id = Math.max(1, sp.getInt(NEXT_NOTE, 1));
        sp.edit().putInt(NEXT_NOTE, id + 1).apply();
        return id;
    }

    String noteText(int id) { return sp.getString(NOTE_TEXT + id, ""); }
    void setNoteText(int id, String text) { sp.edit().putString(NOTE_TEXT + id, text).apply(); }

    /** A checklist as JSON: [{"t": text, "d": done}, …]. */
    String checklist(int id) { return sp.getString(NOTE_LIST + id, "[]"); }
    void setChecklist(int id, String json) { sp.edit().putString(NOTE_LIST + id, json).apply(); }

    void deleteNote(int id) { sp.edit().remove(NOTE_TEXT + id).remove(NOTE_LIST + id).apply(); }

    boolean backupNotes() { return sp.getBoolean(BACKUP_NOTES, true); }
    void setBackupNotes(boolean v) { sp.edit().putBoolean(BACKUP_NOTES, v).apply(); }

    private static String itemsKey(String side) {
        return SIDEBAR_RIGHT.equals(side) ? SIDEBAR_RIGHT_ITEMS : SIDEBAR_LEFT_ITEMS;
    }

    /** A sidebar's entries, in order: targets (see Targets). */
    java.util.List<String> sidebarItems(String side) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp.getString(itemsKey(side), "[]"));
            for (int i = 0; i < a.length(); i++) if (Targets.isTarget(a.optString(i))) out.add(a.optString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    void setSidebarItems(String side, java.util.List<String> items) {
        sp.edit().putString(itemsKey(side), new org.json.JSONArray(items).toString()).apply();
    }

    /** Shortcut keys used by gestures and sidebars (kept pinned alongside the home screen's). */
    Set<String> pinnedTargets() {
        Set<String> s = new HashSet<>();
        for (String g : ALL_GESTURES) if (Shortcuts.isShortcutKey(gesture(g))) s.add(gesture(g));
        for (String side : new String[] {SIDEBAR_LEFT, SIDEBAR_RIGHT}) {
            for (String t : sidebarItems(side)) if (Shortcuts.isShortcutKey(t)) s.add(t);
        }
        return s;
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
        for (String g : ALL_GESTURES) o.put(g, gesture(g));
        o.put(SIDEBAR_LEFT, sidebar(SIDEBAR_LEFT));
        o.put(SIDEBAR_RIGHT, sidebar(SIDEBAR_RIGHT));
        o.put(SIDEBAR_HANDLE_POS, sidebarHandlePos());
        o.put(SIDEBAR_HANDLE_SHOWN, sidebarHandleShown());
        o.put(SIDEBAR_ZONE, sidebarZone());
        o.put(FOLDER_SPACING, folderSpacing());
        o.put(SHOW_NEXT_ALARM, showNextAlarm());
        o.put(SHOW_BATTERY, showBattery());
        o.put(NEW_DOTS, newDots());
        o.put(DRAWER_SORT, drawerSort());
        o.put(DOCK_WRAP, dockWrap());
        o.put(DOCK_HOME_RESET, dockHomeReset());
        o.put(SIDEBAR_LEFT_ITEMS, new org.json.JSONArray(sidebarItems(SIDEBAR_LEFT)));
        for (String side : BG_SURFACES) {
            o.put(side + BG_MODE, bgMode(side));
            o.put(side + BG_COLOR, String.format("#%08X", bgColor(side)));
            o.put(side + BG_COLOR2, String.format("#%08X", bgColor2(side)));
            o.put(side + BG_DIRECTION, bgDirection(side));
            o.put(side + BG_OPACITY, bgOpacity(side));
        }
        o.put(SIDEBAR_RIGHT_ITEMS, new org.json.JSONArray(sidebarItems(SIDEBAR_RIGHT)));
        o.put(SURFACE, String.format("#%08X", surface()));
        o.put(BACKUP_NOTES, backupNotes());
        JSONObject notes = new JSONObject(), lists = new JSONObject();
        for (String side : new String[] {SIDEBAR_LEFT, SIDEBAR_RIGHT}) {
            o.put(side + SB_STYLE, sidebarStyle(side));
            o.put(side + SB_CARDS, new org.json.JSONArray(sp.getString(side + SB_CARDS, "[]")));
            if (!backupNotes()) continue;
            for (Card c : sidebarCards(side)) {
                if (Card.NOTE.equals(c.type)) notes.put(String.valueOf(c.id), noteText(c.id));
                if (Card.LIST.equals(c.type)) lists.put(String.valueOf(c.id), new org.json.JSONArray(checklist(c.id)));
            }
        }
        if (backupNotes()) {
            o.put("notes", notes);
            o.put("checklists", lists);
            o.put(NEXT_NOTE, sp.getInt(NEXT_NOTE, 1));
        }
        return o;
    }

    /** Applies a backup. Unknown keys are ignored; missing keys keep their current value. */
    void fromJson(JSONObject o) throws JSONException {
        SharedPreferences.Editor e = sp.edit();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            // Per-surface background settings.
            if (k.equals(SIDEBAR_LEFT + SB_STYLE) || k.equals(SIDEBAR_RIGHT + SB_STYLE)) {
                e.putInt(k, clamp(o.getInt(k), 0, 1));
                continue;
            }
            if (k.equals(SIDEBAR_LEFT + SB_CARDS) || k.equals(SIDEBAR_RIGHT + SB_CARDS)) {
                // Widget ids belong to the old install; they show as "unavailable" until removed.
                org.json.JSONArray a = o.getJSONArray(k), keep = new org.json.JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.optJSONObject(i);
                    if (c == null || !c.has("id")) continue;
                    String t = c.optString("type", Card.WIDGET);
                    if (!t.equals(Card.WIDGET) && !t.equals(Card.NOTE) && !t.equals(Card.LIST)) continue;
                    keep.put(new JSONObject().put("type", t).put("id", c.optInt("id")).put("h", c.optInt("h", 160)));
                }
                e.putString(k, keep.toString());
                continue;
            }
            if (k.equals("notes")) {
                JSONObject n = o.getJSONObject(k);
                for (Iterator<String> ni = n.keys(); ni.hasNext(); ) {
                    String id = ni.next();
                    if (id.matches("\\d+")) e.putString(NOTE_TEXT + id, n.optString(id));
                }
                continue;
            }
            if (k.equals("checklists")) {
                JSONObject n = o.getJSONObject(k);
                for (Iterator<String> ni = n.keys(); ni.hasNext(); ) {
                    String id = ni.next();
                    org.json.JSONArray items = n.optJSONArray(id);
                    if (id.matches("\\d+") && items != null) e.putString(NOTE_LIST + id, items.toString());
                }
                continue;
            }
            if (k.equals(NEXT_NOTE)) {
                e.putInt(k, Math.max(sp.getInt(NEXT_NOTE, 1), o.getInt(k)));
                continue;
            }
            if (k.equals(SURFACE)) {
                int c = Color.parseColor(o.getString(k)) | 0xFF000000;
                e.putInt(k, c);
                surfaceRgb = c & 0x00FFFFFF;
                continue;
            }
            if (k.equals(BACKUP_NOTES)) {
                e.putBoolean(k, o.getBoolean(k));
                continue;
            }
            boolean surface = false;
            for (String p : BG_SURFACES) if (k.startsWith(p + "_bg_")) surface = true;
            if (surface) {
                if (k.endsWith(BG_COLOR) || k.endsWith(BG_COLOR2)) e.putInt(k, Color.parseColor(o.getString(k)));
                else if (k.endsWith(BG_MODE) || k.endsWith(BG_DIRECTION) || k.endsWith(BG_OPACITY)) e.putInt(k, o.getInt(k));
                continue;
            }
            switch (k) {
                case DRAWER_OPACITY: case DOCK_OPACITY: case COLUMNS: case ROWS: case PAGES:
                case DOCK_SLOTS: case DRAWER_COLUMNS: case ICON_SIZE:
                case DOCK_ICON_SIZE: case DRAWER_ICON_SIZE: case LABEL_SIZE: case DEFAULT_PAGE: case DOCK_PAGES:
                case ACCENT_DIRECTION: case STATUS_ICONS: case NAV_ICONS: case SIDEBAR_HANDLE_POS: case SIDEBAR_ZONE:
                case FOLDER_SPACING: case DRAWER_SORT:
                    e.putInt(k, o.getInt(k));
                    break;
                case SHOW_CLOCK: case SHOW_DATE: case SHOW_SECONDS: case SHOW_DOCK: case HOME_LABELS:
                case DRAWER_LABELS: case HIDDEN_SEARCHABLE:
                case LABEL_ACCENT: case LABEL_SHADOW: case LABEL_TWO_LINES: case LOCK_LAYOUT:
                case SCROLL_WALLPAPER: case ALLOW_ROTATION: case AZ_SCROLLER: case MOST_USED:
                case ACCENT_GRADIENT: case SUBGRID: case SEARCH_ON_PULL:
                case SIDEBAR_LEFT: case SIDEBAR_RIGHT: case SIDEBAR_HANDLE_SHOWN:
                case SHOW_NEXT_ALARM: case SHOW_BATTERY: case NEW_DOTS: case DOCK_WRAP: case DOCK_HOME_RESET:
                    e.putBoolean(k, o.getBoolean(k));
                    break;
                case SIDEBAR_LEFT_ITEMS: case SIDEBAR_RIGHT_ITEMS: {
                    org.json.JSONArray a = o.getJSONArray(k), keep = new org.json.JSONArray();
                    for (int i = 0; i < a.length(); i++) if (Targets.isTarget(a.optString(i))) keep.put(a.optString(i));
                    e.putString(k, keep.toString());
                    break;
                }
                case GESTURE_UP: case GESTURE_DOWN: case GESTURE_LEFT: case GESTURE_RIGHT:
                case GESTURE_DOUBLE_TAP: case GESTURE_TWO_UP: case GESTURE_TWO_DOWN: {
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
                Color.red(surfaceRgb), Color.green(surfaceRgb), Color.blue(surfaceRgb));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
