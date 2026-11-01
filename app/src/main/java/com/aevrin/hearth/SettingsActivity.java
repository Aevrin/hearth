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
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Phase 2 settings: default home app, accent colour, transparency, clock, dock. */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private TextView homeStatus;
    private Button homeButton;
    private LinearLayout swatches;

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

        homeStatus = findViewById(R.id.home_status);
        homeButton = findViewById(R.id.home_button);
        homeButton.setOnClickListener(v -> DefaultHome.request(this));

        swatches = findViewById(R.id.swatches);
        buildSwatches();

        bindSlider(R.id.drawer_opacity, R.id.drawer_opacity_value, prefs.drawerOpacity(), prefs::setDrawerOpacity);
        bindSlider(R.id.dock_opacity, R.id.dock_opacity_value, prefs.dockOpacity(), prefs::setDockOpacity);

        Switch clock = findViewById(R.id.show_clock);
        clock.setChecked(prefs.showClock());
        clock.setOnCheckedChangeListener((b, on) -> prefs.setShowClock(on));

        Switch dock = findViewById(R.id.show_dock);
        dock.setChecked(prefs.showDock());
        dock.setOnCheckedChangeListener((b, on) -> prefs.setShowDock(on));

        findViewById(R.id.about).setOnClickListener(v -> showAbout());
        applyAccent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isDefault = DefaultHome.isDefault(this);
        homeStatus.setText(isDefault ? R.string.home_is_default : R.string.home_not_default);
        homeButton.setVisibility(isDefault ? View.GONE : View.VISIBLE);
    }

    private interface IntSetter { void set(int v); }

    private void bindSlider(int seekId, int labelId, int value, IntSetter setter) {
        SeekBar seek = findViewById(seekId);
        TextView label = findViewById(labelId);
        seek.setMax(100);
        seek.setProgress(value);
        label.setText(getString(R.string.percent, value));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                label.setText(getString(R.string.percent, p));
                if (fromUser) setter.set(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
    }

    private void buildSwatches() {
        swatches.removeAllViews();
        int size = Math.round(36 * getResources().getDisplayMetrics().density);
        int gap = Math.round(10 * getResources().getDisplayMetrics().density);
        int current = prefs.accent();
        for (int color : Prefs.ACCENTS) {
            View v = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            if (color == current) d.setStroke(Math.round(size / 9f), 0xFFE6F0FF);
            v.setBackground(d);
            v.setContentDescription(String.format("#%06X", color & 0xFFFFFF));
            v.setOnClickListener(x -> {
                prefs.setAccent(color);
                buildSwatches();
                applyAccent();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            swatches.addView(v, lp);
        }
    }

    // Tint the controls on this screen too, so the choice is visible straight away.
    private void applyAccent() {
        ColorStateList accent = ColorStateList.valueOf(prefs.accent());
        for (int id : new int[] {R.id.drawer_opacity, R.id.dock_opacity}) {
            SeekBar s = findViewById(id);
            s.setThumbTintList(accent);
            s.setProgressTintList(accent);
        }
        for (int id : new int[] {R.id.show_clock, R.id.show_dock}) {
            Switch s = findViewById(id);
            s.setThumbTintList(accent);
        }
        homeButton.setBackgroundTintList(accent);
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
