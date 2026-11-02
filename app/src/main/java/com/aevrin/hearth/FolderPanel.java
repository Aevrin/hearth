/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * An open folder: a compact card placed right above its icon (below it when
 * there's no room), sized to its contents, over an undimmed home screen.
 */
final class FolderPanel {

    interface Host {
        HomeLayout layout();
        AppRepository repo();
        Prefs prefs();
        /** An icon view for an app or shortcut key, or null if it can't be shown right now. */
        View makeTile(String key, ViewGroup parent, boolean showLabel, int iconPx);
        void launchKey(String key, View from);
        int homeIconPx();
        void refreshHome();
        void syncPins();
        void rename(AppEntry app);
        void changeIcon(AppEntry app);
        void afterLayout(View v, Runnable r);
    }

    private final Activity activity;
    private final Host host;
    private final View overlay;
    private final View card;
    private final TextView title;
    private final GridLayout grid;
    private final ScrollView scroller;
    private final Rect source = new Rect();
    private HomeLayout.Folder folder;
    private final OnBackInvokedCallback onBack = this::close;

    FolderPanel(Activity activity, Host host, View overlay, View card, TextView title, GridLayout grid) {
        this.activity = activity;
        this.host = host;
        this.overlay = overlay;
        this.card = card;
        this.title = title;
        this.grid = grid;
        this.scroller = (ScrollView) grid.getParent();
        overlay.setOnClickListener(v -> close());
        title.setOnClickListener(v -> { if (folder != null && !host.prefs().lockLayout()) rename(folder); });
    }

    boolean isOpen() { return folder != null; }

    String nameOf(HomeLayout.Folder f) {
        return f.name == null || f.name.isEmpty() ? activity.getString(R.string.folder_default_name) : f.name;
    }

    void show(HomeLayout.Folder f, View from) {
        folder = f;
        from.getGlobalVisibleRect(source);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Prefs.background(Math.max(host.prefs().drawerOpacity(), 85)));
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        populate();
        boolean opening = overlay.getVisibility() != View.VISIBLE;
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(1f);
        if (opening) {
            card.setAlpha(0f); // hidden until it's measured and placed
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, onBack);
        }
        host.afterLayout(card, () -> place(opening));
    }

    void close() {
        if (folder == null) return;
        folder = null;
        activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(onBack);
        overlay.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> { if (folder == null) overlay.setVisibility(View.GONE); })
                .start();
    }

    /** Re-reads the open folder's contents (after a rename, icon change, uninstall…). */
    void refresh() {
        if (folder == null) return;
        // The layout may have been reloaded from disk: find the same folder again.
        HomeLayout.Folder fresh = null;
        for (HomeLayout.Item it : host.layout().items()) {
            HomeLayout.Folder f = host.layout().folder(it);
            if (f != null && f.id.equals(folder.id)) fresh = f;
        }
        if (fresh == null || fresh.apps.size() < 2) {
            close();
            return;
        }
        folder = fresh;
        populate();
        host.afterLayout(card, () -> place(false));
    }

    private void place(boolean animate) {
        int[] origin = new int[2];
        overlay.getLocationOnScreen(origin);
        int w = card.getWidth(), h = card.getHeight();
        int ow = overlay.getWidth(), oh = overlay.getHeight();
        int margin = Math.round(dp(8));
        int gap = Math.round(dp(24)); // clear space between the card and its icon
        Insets bars = overlay.getRootWindowInsets() == null ? Insets.NONE
                : overlay.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars());
        float cx = source.exactCenterX() - origin[0];
        float x = Math.max(margin, Math.min(ow - w - margin, cx - w / 2f));
        float y = source.top - origin[1] - h - gap;                    // above the icon
        if (y < bars.top + margin) y = source.bottom - origin[1] + gap; // else below
        y = Math.max(bars.top + margin, Math.min(oh - bars.bottom - h - margin, y));
        card.setX(x);
        card.setY(y);
        if (animate) {
            card.setPivotX(cx - x);
            card.setPivotY(y < source.top - origin[1] ? h : 0);
            card.setScaleX(0.6f);
            card.setScaleY(0.6f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        }
    }

    private void populate() {
        HomeLayout.Folder f = folder;
        if (f == null) return;
        title.setText(nameOf(f));
        grid.removeAllViews();
        int cellW = Math.round(dp(76));
        int cellH = Math.round(dp(92));
        int count = 0;
        boolean labels = host.prefs().homeLabels();
        for (String key : f.apps) {
            View v = host.makeTile(key, grid, labels, host.homeIconPx());
            if (v == null) continue;
            count++;
            v.setOnClickListener(view -> {
                host.launchKey(key, view);
                overlay.postDelayed(this::close, 300);
            });
            v.setOnLongClickListener(view -> { showEntryMenu(f, key, view); return true; });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellH;
            grid.addView(v, lp);
        }
        // Square-ish: 2 columns up to 4 apps (2×1, 2×2), 3 up to 9, 4 up to 16, then 5 —
        // as many as fit across the screen. Taller than the screen scrolls.
        View root = activity.getWindow().getDecorView();
        int screenW = root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int wanted = count <= 4 ? 2 : count <= 9 ? 3 : count <= 16 ? 4 : 5;
        int fit = Math.max(2, (screenW - Math.round(dp(16 + 24))) / cellW);
        int cols = Math.min(wanted, fit);
        grid.setColumnCount(cols);
        int rows = (count + cols - 1) / cols;
        int maxH = screenH - Math.round(dp(220)); // room for the title, system bars and margins
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) scroller.getLayoutParams();
        slp.height = rows * cellH > maxH ? Math.max(cellH, maxH) : LinearLayout.LayoutParams.WRAP_CONTENT;
        scroller.setLayoutParams(slp);
        scroller.scrollTo(0, 0);
    }

    private void showEntryMenu(HomeLayout.Folder f, String key, View anchor) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        AppEntry app = host.repo().find(key);
        boolean locked = host.prefs().lockLayout();
        PopupMenu menu = new PopupMenu(activity, anchor);
        if (!locked) {
            menu.getMenu().add(0, 1, 0, R.string.move_to_home);
            menu.getMenu().add(0, 2, 1, R.string.remove_from_folder);
        }
        if (app != null) {
            menu.getMenu().add(0, 3, 2, R.string.rename);
            menu.getMenu().add(0, 4, 3, R.string.change_icon);
            menu.getMenu().add(0, 5, 4, R.string.app_info);
            if (host.repo().canUninstall(app)) menu.getMenu().add(0, 6, 5, R.string.uninstall);
        }
        if (menu.getMenu().size() == 0) return;
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1:
                    if (!host.layout().moveOutOfFolder(f, key)) {
                        Toast.makeText(activity, R.string.home_full, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    afterChange();
                    return true;
                case 2:
                    host.layout().removeFromFolder(f, key);
                    host.syncPins();
                    afterChange();
                    return true;
                case 3: host.rename(app); return true;
                case 4: host.changeIcon(app); return true;
                case 5: host.repo().openAppInfo(app, anchor); return true;
                case 6: host.repo().uninstall(activity, app); return true;
                default: return false;
            }
        });
        menu.show();
    }

    // A folder that dissolved (one entry left) closes; otherwise it stays open, updated.
    private void afterChange() {
        host.refreshHome();
        refresh();
    }

    void rename(HomeLayout.Folder f) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(f.name);
        input.setHint(R.string.folder_default_name);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(activity);
        int pad = Math.round(dp(20));
        box.setPadding(pad, Math.round(dp(8)), pad, 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.rename_folder)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    host.layout().renameFolder(f, input.getText().toString());
                    host.refreshHome();
                    if (folder != null && folder.id.equals(f.id)) refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            activity.getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
    }

    private float dp(float v) {
        return v * activity.getResources().getDisplayMetrics().density;
    }
}
