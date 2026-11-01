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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
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

    private Prefs prefs;
    private AppRepository repo;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView homeStatus;
    private Button homeButton;
    private Button hiddenButton;

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
        findViewById(R.id.back).setOnClickListener(v -> finish());
        content = findViewById(R.id.settings_content);
        scroll = (ScrollView) content.getParent();

        repo = new AppRepository(this, this); // for the hidden-apps list
        repo.reload();
        build();
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
        if (hiddenButton != null) {
            hiddenButton.setEnabled(true);
            hiddenButton.setText(getString(R.string.hidden_apps_button, prefs.hidden().size()));
        }
    }

    /** (Re)builds every row; used again when the accent colour changes. */
    private void build() {
        int scrollY = scroll.getScrollY();
        content.removeAllViews();

        // Home app
        header(R.string.section_home);
        homeStatus = text("");
        homeButton = button(R.string.set_as_home, v -> DefaultHome.request(this));
        refreshHomeStatus();

        // Appearance
        header(R.string.section_appearance);
        label(R.string.accent_colour);
        swatches();
        slider(R.string.drawer_opacity, prefs.drawerOpacity(), prefs::setDrawerOpacity);
        slider(R.string.dock_opacity, prefs.dockOpacity(), prefs::setDockOpacity);
        choice(R.string.icon_size, new int[] {R.string.small, R.string.medium, R.string.large},
                prefs.iconSize(), prefs::setIconSize);
        toggle(R.string.home_labels, prefs.homeLabels(), prefs::setHomeLabels);
        toggle(R.string.drawer_labels, prefs.drawerLabels(), prefs::setDrawerLabels);

        // Clock
        header(R.string.section_clock);
        toggle(R.string.show_clock, prefs.showClock(), prefs::setShowClock);
        toggle(R.string.show_date, prefs.showDate(), prefs::setShowDate);
        String[] formats = {Prefs.CLOCK_SYSTEM, Prefs.CLOCK_12, Prefs.CLOCK_24};
        int fmt = 0;
        for (int i = 0; i < formats.length; i++) if (formats[i].equals(prefs.clockFormat())) fmt = i;
        choice(R.string.clock_format, new int[] {R.string.clock_system, R.string.clock_12, R.string.clock_24},
                fmt, i -> prefs.setClockFormat(formats[i]));

        // Layout
        header(R.string.section_layout);
        stepper(R.string.home_columns, prefs.columns(), 3, 8, prefs::setColumns);
        stepper(R.string.home_rows, prefs.rows(), 3, 9, prefs::setRows);
        stepper(R.string.home_pages, prefs.pages(), 1, 7, prefs::setPages);
        toggle(R.string.show_dock, prefs.showDock(), prefs::setShowDock);
        stepper(R.string.dock_slots, prefs.dockSlots(), 3, 7, prefs::setDockSlots);
        stepper(R.string.drawer_columns, prefs.drawerColumns(), 3, 7, prefs::setDrawerColumns);
        note(R.string.layout_note);

        // Apps
        header(R.string.section_apps);
        hiddenButton = button(R.string.hidden_apps_loading, v -> showHiddenApps());
        if (repo.isLoaded()) onAppsChanged(); else hiddenButton.setEnabled(false);
        toggle(R.string.hidden_searchable, prefs.hiddenSearchable(), prefs::setHiddenSearchable);

        // Backup
        header(R.string.section_backup);
        note(R.string.backup_note);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(row);
        Button export = makeButton(R.string.backup_export, v -> startExport());
        Button restore = makeButton(R.string.backup_restore, v -> startImport());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        row.addView(export, lp);
        row.addView(restore);

        // About
        header(R.string.section_about);
        TextView about = text(getString(R.string.about));
        about.setBackgroundResource(android.R.drawable.list_selector_background);
        about.setPadding(0, dp(12), 0, dp(12));
        about.setOnClickListener(v -> showAbout());

        scroll.post(() -> scroll.scrollTo(0, scrollY));
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
        t.setTextColor(prefs.accent());
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
        group.setOrientation(RadioGroup.HORIZONTAL);
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
        int current = prefs.accent();
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
                build(); // re-tint everything on this screen
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(dp(10));
            strip.addView(v, lp);
        }
        content.addView(hs, matchWrap());
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
                Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show();
                build(); // show the restored values
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
