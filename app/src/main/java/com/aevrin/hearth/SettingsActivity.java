/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.widget.EditText;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Every Hearth setting. Built in code rather than XML because most rows are the
 * same few shapes (switch, slider, stepper, choice). Changes save immediately;
 * the home screen picks them up when you go back.
 */
public class SettingsActivity extends Activity implements AppRepository.Listener {

    private static final int REQ_EXPORT = 11;
    private static final int REQ_IMPORT = 12;

    // Pages. MAIN lists the others.
    private static final int MAIN = 0, APPEARANCE = 1, ICONS = 2, LABELS = 3, CLOCK = 4,
            HOME = 5, DOCK = 6, DRAWER = 7, BACKUP = 8, GESTURES = 9;

    private Prefs prefs;
    private AppRepository repo;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private TextView homeStatus;
    private Button homeButton;
    private Button hiddenButton;
    private LinearLayout preview;
    private int page = MAIN;
    private int mainScroll;
    private boolean backRegistered;
    private final OnBackInvokedCallback backToMain = () -> showPage(MAIN);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        View root = findViewById(R.id.settings_root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        findViewById(R.id.back).setOnClickListener(v -> { if (page != MAIN) showPage(MAIN); else finish(); });
        title = findViewById(R.id.settings_title);
        content = findViewById(R.id.settings_content);
        scroll = (ScrollView) content.getParent();

        repo = new AppRepository(this, this); // for the hidden-apps list and icon preview
        repo.setTheme(IconTheme.from(prefs));
        repo.setLabeler(e -> {
            String c = prefs.customLabel(e.key);
            return c != null ? c : e.label;
        });
        repo.reload();
        if (savedInstanceState != null) page = savedInstanceState.getInt("page", MAIN);
        showPage(page);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHomeStatus();
    }

    @Override
    protected void onDestroy() {
        repo.destroy();
        super.onDestroy();
    }

    @Override
    public void onAppsChanged() {
        refreshPreview();
        if (hiddenButton != null) {
            hiddenButton.setEnabled(true);
            hiddenButton.setText(getString(R.string.hidden_apps_button, prefs.hidden().size()));
        }
    }

    /** Opens a page from the top (returning to the main list keeps its place). */
    private void showPage(int p) {
        if (page == MAIN && p != MAIN) mainScroll = scroll.getScrollY();
        page = p;
        // Back (gesture or button) goes up to the main list from a sub-page.
        if (p != MAIN && !backRegistered) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToMain);
            backRegistered = true;
        } else if (p == MAIN && backRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backToMain);
            backRegistered = false;
        }
        build();
        int y = p == MAIN ? mainScroll : 0;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** (Re)builds the current page; used again when the accent colour changes. */
    private void build() {
        int scrollY = scroll.getScrollY();
        content.removeAllViews();
        homeStatus = null;
        hiddenButton = null;
        preview = null;
        title.setText(page == MAIN ? R.string.settings : pageTitle(page));
        switch (page) {
            case APPEARANCE: buildAppearance(); break;
            case ICONS: buildIcons(); break;
            case LABELS: buildLabels(); break;
            case CLOCK: buildClock(); break;
            case HOME: buildHome(); break;
            case DOCK: buildDock(); break;
            case DRAWER: buildDrawer(); break;
            case BACKUP: buildBackup(); break;
            case GESTURES: buildGestures(); break;
            default: buildMain(); break;
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private static int pageTitle(int p) {
        switch (p) {
            case APPEARANCE: return R.string.section_appearance;
            case ICONS: return R.string.section_icons;
            case LABELS: return R.string.section_labels;
            case CLOCK: return R.string.section_clock;
            case HOME: return R.string.section_home_screen;
            case DOCK: return R.string.section_dock;
            case DRAWER: return R.string.section_drawer;
            case BACKUP: return R.string.section_backup;
            case GESTURES: return R.string.section_gestures;
            default: return R.string.settings;
        }
    }

    private void buildMain() {
        header(R.string.section_home);
        homeStatus = text("");
        homeButton = button(R.string.set_as_home, v -> DefaultHome.request(this));
        refreshHomeStatus();

        header(R.string.section_settings);
        category(APPEARANCE, R.string.summary_appearance);
        category(ICONS, R.string.summary_icons);
        category(LABELS, R.string.summary_labels);
        category(CLOCK, R.string.summary_clock);
        category(HOME, R.string.summary_home_screen);
        category(DOCK, R.string.summary_dock);
        category(DRAWER, R.string.summary_drawer);
        category(GESTURES, R.string.summary_gestures);
        category(BACKUP, R.string.summary_backup);

        header(R.string.section_about);
        TextView about = text(getString(R.string.about));
        about.setBackgroundResource(android.R.drawable.list_selector_background);
        about.setPadding(0, dp(12), 0, dp(12));
        about.setOnClickListener(v -> showAbout());
    }

    private void buildAppearance() {
        header(R.string.accent_colour);
        Accent a = prefs.accentStyle();
        choice(R.string.accent_mode, new int[] {R.string.accent_solid, R.string.accent_gradient},
                a.gradient ? 1 : 0, i -> {
                    if ((i == 1) == prefs.accentStyle().gradient) return;
                    if (i == 0) prefs.setAccent(prefs.accentStyle().start);
                    else startGradient();
                    accentChanged();
                });
        if (a.gradient) gradientControls(a); else swatches();
        header(R.string.section_system_bars);
        int[] bars = {R.string.bar_clear, R.string.bar_background, R.string.bar_accent};
        choice(R.string.status_bar, bars, prefs.statusBar(), prefs::setStatusBar);
        choice(R.string.nav_bar, bars, prefs.navBar(), prefs::setNavBar);
        header(R.string.section_backgrounds);
        slider(R.string.drawer_opacity, prefs.drawerOpacity(), prefs::setDrawerOpacity);
        slider(R.string.dock_opacity, prefs.dockOpacity(), prefs::setDockOpacity);
    }

    private void buildIcons() {
        preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams plp = matchWrap();
        plp.topMargin = dp(20);
        content.addView(preview, plp);
        refreshPreview();
        String[] styles = IconTheme.STYLES;
        int[] styleNames = new int[styles.length];
        int styleIdx = 0;
        for (int i = 0; i < styles.length; i++) {
            styleNames[i] = HomeActivity.styleName(styles[i]);
            if (styles[i].equals(prefs.iconStyle())) styleIdx = i;
        }
        choice(R.string.icon_style, styleNames, styleIdx, i -> { prefs.setIconStyle(styles[i]); themeChanged(); });
        String[] shapes = IconTheme.SHAPES;
        int[] shapeNames = {R.string.shape_system, R.string.shape_circle, R.string.shape_squircle,
                R.string.shape_rounded, R.string.shape_square, R.string.shape_teardrop};
        int shapeIdx = 0;
        for (int i = 0; i < shapes.length; i++) if (shapes[i].equals(prefs.iconShape())) shapeIdx = i;
        choice(R.string.icon_shape, shapeNames, shapeIdx, i -> { prefs.setIconShape(shapes[i]); themeChanged(); });
        choice(R.string.icon_size, sizes(), prefs.iconSize(), prefs::setIconSize);
        note(R.string.icon_note);
    }

    private void buildLabels() {
        toggle(R.string.home_labels, prefs.homeLabels(), prefs::setHomeLabels);
        toggle(R.string.drawer_labels, prefs.drawerLabels(), prefs::setDrawerLabels);
        choice(R.string.label_size, sizes(), prefs.labelSize(), prefs::setLabelSize);
        toggle(R.string.label_accent, prefs.labelAccent(), prefs::setLabelAccent);
        toggle(R.string.label_shadow, prefs.labelShadow(), prefs::setLabelShadow);
        toggle(R.string.label_two_lines, prefs.labelTwoLines(), prefs::setLabelTwoLines);
    }

    private void buildClock() {
        toggle(R.string.show_clock, prefs.showClock(), prefs::setShowClock);
        toggle(R.string.show_date, prefs.showDate(), prefs::setShowDate);
        toggle(R.string.show_seconds, prefs.showSeconds(), prefs::setShowSeconds);
        String[] formats = {Prefs.CLOCK_SYSTEM, Prefs.CLOCK_12, Prefs.CLOCK_24};
        int fmt = 0;
        for (int i = 0; i < formats.length; i++) if (formats[i].equals(prefs.clockFormat())) fmt = i;
        choice(R.string.clock_format, new int[] {R.string.clock_system, R.string.clock_12, R.string.clock_24},
                fmt, i -> prefs.setClockFormat(formats[i]));
    }

    private void buildHome() {
        stepper(R.string.home_columns, prefs.columns(), 3, 8, prefs::setColumns);
        stepper(R.string.home_rows, prefs.rows(), 3, 9, prefs::setRows);
        stepper(R.string.home_pages, prefs.pages(), 1, 7, prefs::setPages);
        toggle(R.string.subgrid, prefs.subgrid(), prefs::setSubgrid);
        toggle(R.string.lock_layout_setting, prefs.lockLayout(), prefs::setLockLayout);
        toggle(R.string.scroll_wallpaper, prefs.scrollWallpaper(), prefs::setScrollWallpaper);
        toggle(R.string.allow_rotation, prefs.allowRotation(), prefs::setAllowRotation);
        note(R.string.layout_note);
    }

    private void buildDock() {
        toggle(R.string.show_dock, prefs.showDock(), prefs::setShowDock);
        stepper(R.string.dock_slots, prefs.dockSlots(), 3, 7, prefs::setDockSlots);
        stepper(R.string.dock_pages, prefs.dockPages(), 1, 3, prefs::setDockPages);
        choice(R.string.dock_icon_size, sizes(), prefs.dockIconSize(), prefs::setDockIconSize);
    }

    private void buildDrawer() {
        stepper(R.string.drawer_columns, prefs.drawerColumns(), 3, 7, prefs::setDrawerColumns);
        choice(R.string.drawer_icon_size, sizes(), prefs.drawerIconSize(), prefs::setDrawerIconSize);
        toggle(R.string.az_scroller, prefs.azScroller(), prefs::setAzScroller);
        toggle(R.string.most_used_setting, prefs.mostUsed(), prefs::setMostUsed);
        header(R.string.section_apps);
        hiddenButton = button(R.string.hidden_apps_loading, v -> showHiddenApps());
        if (repo.isLoaded()) onAppsChanged(); else hiddenButton.setEnabled(false);
        toggle(R.string.hidden_searchable, prefs.hiddenSearchable(), prefs::setHiddenSearchable);
    }

    private void buildBackup() {
        note(R.string.backup_note);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(12);
        content.addView(row, rlp);
        Button export = makeButton(R.string.backup_export, v -> startExport());
        Button restore = makeButton(R.string.backup_restore, v -> startImport());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        row.addView(export, lp);
        row.addView(restore);
    }

    private void buildGestures() {
        gestureRow(R.string.gesture_up, Prefs.GESTURE_UP);
        gestureRow(R.string.gesture_down, Prefs.GESTURE_DOWN);
        gestureRow(R.string.gesture_left, Prefs.GESTURE_LEFT);
        gestureRow(R.string.gesture_right, Prefs.GESTURE_RIGHT);
        note(R.string.gesture_note);
    }

    private static final int[] GESTURE_NAMES = {R.string.g_none, R.string.g_drawer, R.string.g_search,
            R.string.g_notifications, R.string.g_quick_settings, R.string.g_settings};

    private String gestureName(String action) {
        if (action.startsWith(Prefs.G_APP_PREFIX)) {
            AppEntry e = repo.find(action.substring(Prefs.G_APP_PREFIX.length()));
            String name = e == null ? "?" : (prefs.customLabel(e.key) != null ? prefs.customLabel(e.key) : e.label);
            return getString(R.string.g_open_named, name);
        }
        for (int i = 0; i < Prefs.GESTURE_ACTIONS.length; i++) {
            if (Prefs.GESTURE_ACTIONS[i].equals(action)) return getString(GESTURE_NAMES[i]);
        }
        return getString(R.string.g_none);
    }

    /** "Swipe up — App drawer"; tap to choose. */
    private void gestureRow(int label, String which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(16);
        TextView v = new TextView(this);
        v.setText(gestureName(prefs.gesture(which)));
        v.setTextColor(prefs.accent());
        v.setTextSize(14);
        row.addView(t);
        row.addView(v);
        row.setOnClickListener(x -> pickGesture(label, which));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(4);
        content.addView(row, lp);
    }

    private void pickGesture(int label, String which) {
        String current = prefs.gesture(which);
        String[] names = new String[GESTURE_NAMES.length + 1];
        int checked = GESTURE_NAMES.length; // "Open an app…" when it's an app
        for (int i = 0; i < GESTURE_NAMES.length; i++) {
            names[i] = getString(GESTURE_NAMES[i]);
            if (Prefs.GESTURE_ACTIONS[i].equals(current)) checked = i;
        }
        names[GESTURE_NAMES.length] = current.startsWith(Prefs.G_APP_PREFIX)
                ? gestureName(current) : getString(R.string.g_open_app);
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(names, checked, (d, i) -> {
                    d.dismiss();
                    if (i < GESTURE_NAMES.length) {
                        prefs.setGesture(which, Prefs.GESTURE_ACTIONS[i]);
                        build();
                    } else {
                        pickGestureApp(label, which);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickGestureApp(int label, String which) {
        List<AppEntry> apps = repo.apps();
        if (apps.isEmpty()) return;
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry e = apps.get(i);
            names[i] = prefs.customLabel(e.key) != null ? prefs.customLabel(e.key) : e.label;
        }
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(names, (d, i) -> {
                    prefs.setGesture(which, Prefs.G_APP_PREFIX + apps.get(i).key);
                    build();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int[] sizes() {
        return new int[] {R.string.small, R.string.medium, R.string.large};
    }

    /** A row on the main page that opens a sub-page. */
    private void category(int target, int summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(pageTitle(target));
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(17);
        TextView sub = new TextView(this);
        sub.setText(summary);
        sub.setTextColor(getColor(R.color.text_muted));
        sub.setTextSize(13);
        texts.addView(t);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(26);
        prefs.accentStyle().paint(chevron);
        row.addView(chevron);
        row.setOnClickListener(v -> showPage(target));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(4);
        content.addView(row, lp);
    }

    /* ---------- accent ---------- */

    /** Accent changed: re-render icons and re-tint this page. */
    private void accentChanged() {
        repo.setTheme(IconTheme.from(prefs)); // themed/duotone icons use the accent
        build();
    }

    // Turning a gradient on: keep the current colour as the start and pick a partner.
    private void startGradient() {
        Accent a = prefs.accentStyle();
        int end = a.end != a.start ? a.end : partnerFor(a.start);
        prefs.setAccentGradient(a.start, end, a.direction);
    }

    private static int partnerFor(int color) {
        for (int[] p : Accent.PRESETS) {
            if (p[0] == color) return p[1];
            if (p[1] == color) return p[0];
        }
        return color == 0xFF06B6D4 ? 0xFF3D52A0 : 0xFF06B6D4;
    }

    private void gradientControls(Accent a) {
        // A wide preview bar of the current gradient.
        View bar = new View(this);
        GradientDrawable d = a.drawable();
        d.setCornerRadius(dp(12));
        bar.setBackground(d);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        blp.topMargin = dp(12);
        content.addView(bar, blp);

        // Presets.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dp(12), 0, dp(4));
        hs.addView(strip);
        for (int[] p : Accent.PRESETS) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {p[0], p[1]});
            g.setShape(GradientDrawable.OVAL);
            if (p[0] == a.start && p[1] == a.end) g.setStroke(dp(4), 0xFFE6F0FF);
            v.setBackground(g);
            v.setContentDescription(String.format("#%06X to #%06X", p[0] & 0xFFFFFF, p[1] & 0xFFFFFF));
            v.setOnClickListener(x -> {
                prefs.setAccentGradient(p[0], p[1], prefs.accentStyle().direction);
                accentChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMarginEnd(dp(10));
            strip.addView(v, lp);
        }
        content.addView(hs, matchWrap());

        // Start / end pickers and swap.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(8);
        content.addView(row, rlp);
        row.addView(colourButton(R.string.gradient_start, a.start, () -> showColorPicker(prefs.accentStyle().start, c -> {
            Accent cur = prefs.accentStyle();
            prefs.setAccentGradient(c, cur.end, cur.direction);
            accentChanged();
        })));
        row.addView(colourButton(R.string.gradient_end, a.end, () -> showColorPicker(prefs.accentStyle().end, c -> {
            Accent cur = prefs.accentStyle();
            prefs.setAccentGradient(cur.start, c, cur.direction);
            accentChanged();
        })));
        TextView swap = new TextView(this);
        swap.setText(R.string.gradient_swap);
        swap.setTextSize(15);
        swap.setTextColor(a.solid());
        swap.setPadding(dp(12), dp(12), dp(12), dp(12));
        swap.setBackgroundResource(android.R.drawable.list_selector_background);
        swap.setOnClickListener(x -> {
            Accent cur = prefs.accentStyle();
            prefs.setAccentGradient(cur.end, cur.start, cur.direction);
            accentChanged();
        });
        row.addView(swap);

        choice(R.string.gradient_direction,
                new int[] {R.string.direction_diagonal, R.string.direction_horizontal, R.string.direction_vertical},
                a.direction, i -> {
                    Accent cur = prefs.accentStyle();
                    prefs.setAccentGradient(cur.start, cur.end, i);
                    accentChanged();
                });
        note(R.string.gradient_note);
    }

    /** "● Start" — a dot of the colour and a label; tap to change it. */
    private View colourButton(int label, int color, Runnable onClick) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(4), dp(8), dp(16), dp(8));
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(1), 0x66E6F0FF);
        dot.setBackground(g);
        b.addView(dot, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(15);
        t.setPadding(dp(8), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private void refreshHomeStatus() {
        if (homeStatus == null) return;
        boolean isDefault = DefaultHome.isDefault(this);
        homeStatus.setText(isDefault ? R.string.home_is_default : R.string.home_not_default);
        homeButton.setVisibility(isDefault ? View.GONE : View.VISIBLE);
    }

    /* ---------- row builders ---------- */

    private ColorStateList accent() {
        return ColorStateList.valueOf(prefs.accent());
    }

    private void header(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        prefs.accentStyle().paint(t);
        t.setTextSize(14);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(28);
        content.addView(t, lp);
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(16);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        content.addView(t, lp);
        return t;
    }

    private void label(int s) {
        TextView t = text(getString(s));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = dp(12);
    }

    private void note(int s) {
        TextView t = text(getString(s));
        t.setTextSize(13);
        t.setTextColor(getColor(R.color.text_muted));
    }

    private Button makeButton(int s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(getColor(R.color.bg_primary));
        b.setBackgroundTintList(accent());
        b.setOnClickListener(l);
        return b;
    }

    private Button button(int s, View.OnClickListener l) {
        Button b = makeButton(s, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        content.addView(b, lp);
        return b;
    }

    private void toggle(int s, boolean value, Consumer<Boolean> setter) {
        Switch sw = new Switch(this);
        sw.setText(s);
        sw.setTextColor(getColor(R.color.text_primary));
        sw.setTextSize(16);
        sw.setMinHeight(dp(48));
        sw.setChecked(value);
        sw.setThumbTintList(accent());
        sw.setOnCheckedChangeListener((b, on) -> setter.accept(on));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        content.addView(sw, lp);
    }

    private void slider(int s, int value, IntConsumer setter) {
        LinearLayout row = labelRow(s);
        TextView v = valueText(row, getString(R.string.percent, value));
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(value);
        seek.setThumbTintList(accent());
        seek.setProgressTintList(accent());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                v.setText(getString(R.string.percent, p));
                if (fromUser) setter.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        content.addView(seek, matchWrap());
    }

    private void stepper(int s, int value, int min, int max, IntConsumer setter) {
        LinearLayout row = labelRow(s);
        int[] current = {value};
        TextView minus = stepButton(row, "−");
        TextView v = valueText(row, String.valueOf(value));
        v.setMinWidth(dp(32));
        v.setGravity(Gravity.CENTER);
        TextView plus = stepButton(row, "+");
        Runnable sync = () -> {
            v.setText(String.valueOf(current[0]));
            minus.setAlpha(current[0] > min ? 1f : 0.3f);
            plus.setAlpha(current[0] < max ? 1f : 0.3f);
        };
        minus.setOnClickListener(x -> {
            if (current[0] <= min) return;
            setter.accept(--current[0]);
            sync.run();
        });
        plus.setOnClickListener(x -> {
            if (current[0] >= max) return;
            setter.accept(++current[0]);
            sync.run();
        });
        sync.run();
    }

    private void choice(int s, int[] options, int selected, IntConsumer setter) {
        label(s);
        RadioGroup group = new RadioGroup(this);
        // Up to three short options fit side by side; longer lists stack.
        group.setOrientation(options.length > 3 ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(options[i]);
            rb.setTextColor(getColor(R.color.text_primary));
            rb.setButtonTintList(accent());
            rb.setPadding(dp(4), 0, dp(16), 0);
            group.addView(rb);
            if (i == selected) rb.setChecked(true);
            int index = i;
            rb.setOnCheckedChangeListener((b, on) -> { if (on) setter.accept(index); });
        }
        content.addView(group, matchWrap());
    }

    /** Accent or theme changed: re-render the preview (the home screen re-renders on return). */
    private void themeChanged() {
        repo.setTheme(IconTheme.from(prefs));
        refreshPreview();
    }

    private void refreshPreview() {
        if (preview == null) return;
        preview.removeAllViews();
        List<AppEntry> apps = repo.apps();
        int size = dp(48);
        for (int i = 0; i < Math.min(6, apps.size()); i++) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(dp(10));
            preview.addView(iv, lp);
            repo.loadIcon(apps.get(i), iv::setImageDrawable);
        }
    }

    private LinearLayout labelRow(int s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView l = new TextView(this);
        l.setText(s);
        l.setTextColor(getColor(R.color.text_primary));
        l.setTextSize(16);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        content.addView(row, lp);
        return row;
    }

    private TextView valueText(LinearLayout row, String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(getColor(R.color.text_muted));
        v.setTextSize(15);
        row.addView(v);
        return v;
    }

    private TextView stepButton(LinearLayout row, String s) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(22);
        b.setTextColor(prefs.accent());
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        row.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    private void swatches() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dp(8), 0, dp(4));
        hs.addView(strip);
        int size = dp(36);
        int current = prefs.accentStyle().start;
        for (int color : Prefs.ACCENTS) {
            View v = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            if (color == current) d.setStroke(dp(4), 0xFFE6F0FF);
            v.setBackground(d);
            v.setContentDescription(String.format("#%06X", color & 0xFFFFFF));
            v.setOnClickListener(x -> {
                prefs.setAccent(color);
                accentChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(dp(10));
            strip.addView(v, lp);
        }
        // Custom colour: a rainbow swatch, ringed when the accent isn't one of the presets.
        boolean custom = true;
        for (int c : Prefs.ACCENTS) if (c == current) custom = false;
        View pick = new View(this);
        GradientDrawable rainbow = new GradientDrawable();
        rainbow.setShape(GradientDrawable.OVAL);
        rainbow.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        rainbow.setColors(new int[] {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        if (custom) rainbow.setStroke(dp(4), 0xFFE6F0FF);
        pick.setBackground(rainbow);
        pick.setContentDescription(getString(R.string.custom_colour));
        pick.setOnClickListener(x -> showColorPicker(prefs.accentStyle().start, c -> {
            prefs.setAccent(c);
            accentChanged();
        }));
        strip.addView(pick, new LinearLayout.LayoutParams(size, size));
        content.addView(hs, matchWrap());
    }

    /** Hue / saturation / brightness sliders plus a hex field, with a live swatch. */
    private void showColorPicker(int initial, IntConsumer onSave) {
        float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);

        View swatch = new View(this);
        GradientDrawable sw = new GradientDrawable();
        sw.setCornerRadius(dp(12));
        swatch.setBackground(sw);
        box.addView(swatch, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setFilters(new InputFilter[] {new InputFilter.LengthFilter(7)});
        hex.setTextColor(getColor(R.color.text_primary));
        hex.setHint("#06B6D4");
        LinearLayout.LayoutParams hlp = matchWrap();
        hlp.topMargin = dp(8);
        box.addView(hex, hlp);

        SeekBar hue = pickerSlider(box, R.string.hue, 360, Math.round(hsv[0]));
        SeekBar sat = pickerSlider(box, R.string.saturation, 100, Math.round(hsv[1] * 100));
        SeekBar val = pickerSlider(box, R.string.brightness, 100, Math.round(hsv[2] * 100));
        GradientDrawable hueTrack = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        hueTrack.setCornerRadius(dp(4));
        hueTrack.setSize(1, dp(8));
        hue.setProgressDrawable(hueTrack);

        int[] color = {initial};
        boolean[] syncing = {false};
        Runnable fromSliders = () -> {
            color[0] = Color.HSVToColor(new float[] {hue.getProgress(), sat.getProgress() / 100f, val.getProgress() / 100f});
            sw.setColor(color[0]);
            for (SeekBar b : new SeekBar[] {hue, sat, val}) b.setThumbTintList(ColorStateList.valueOf(color[0]));
            syncing[0] = true;
            hex.setText(String.format("#%06X", color[0] & 0xFFFFFF));
            syncing[0] = false;
        };
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) { if (fromUser) fromSliders.run(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        };
        hue.setOnSeekBarChangeListener(l);
        sat.setOnSeekBarChangeListener(l);
        val.setOnSeekBarChangeListener(l);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (syncing[0]) return;
                String t = e.toString().trim();
                if (!t.startsWith("#")) t = "#" + t;
                if (!t.matches("#[0-9a-fA-F]{6}")) return;
                color[0] = Color.parseColor(t);
                float[] h = new float[3];
                Color.colorToHSV(color[0], h);
                hue.setProgress(Math.round(h[0]));
                sat.setProgress(Math.round(h[1] * 100));
                val.setProgress(Math.round(h[2] * 100));
                sw.setColor(color[0]);
            }
        });
        fromSliders.run();

        new AlertDialog.Builder(this)
                .setTitle(R.string.custom_colour)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> onSave.accept(color[0] | 0xFF000000))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private SeekBar pickerSlider(LinearLayout box, int label, int max, int value) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(getColor(R.color.text_muted));
        t.setTextSize(13);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        box.addView(t, lp);
        SeekBar b = new SeekBar(this);
        b.setMax(max);
        b.setProgress(value);
        box.addView(b, matchWrap());
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ---------- hidden apps ---------- */

    private void showHiddenApps() {
        List<AppEntry> apps = repo.apps();
        if (apps.isEmpty()) return;
        Set<String> hidden = new HashSet<>(prefs.hidden());
        String[] names = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry e = apps.get(i);
            String custom = prefs.customLabel(e.key);
            names[i] = custom != null ? custom : e.label;
            checked[i] = hidden.contains(e.key);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.hidden_apps_title)
                .setMultiChoiceItems(names, checked, (d, which, on) -> {
                    if (on) hidden.add(apps.get(which).key); else hidden.remove(apps.get(which).key);
                })
                .setPositiveButton(R.string.save, (d, w) -> {
                    prefs.setHiddenAll(hidden);
                    onAppsChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /* ---------- backup ---------- */

    private void startExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "hearth-backup-" + LocalDate.now() + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void startImport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore)
                .setMessage(R.string.restore_confirm)
                .setPositiveButton(R.string.backup_restore, (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "text/plain", "application/octet-stream"});
                    startActivityForResult(i, REQ_IMPORT);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                Backup.export(this, uri);
                Toast.makeText(this, R.string.backup_saved, Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT) {
                Backup.restore(this, uri);
                repo.setTheme(IconTheme.from(prefs));
                Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show();
                showPage(MAIN); // show the restored values
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.backup_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_title, version))
                .setMessage(R.string.about_body)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.source_code, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url))));
                    } catch (android.content.ActivityNotFoundException ignored) { }
                })
                .show();
    }
}
