/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The home screen: a clock, a grid of pinned apps and a dock, over the wallpaper.
 * Swipe up (or tap the handle) for the app drawer. Long-press and drag an icon to
 * move it, including between the grid and the dock; drop it on "Remove" to unpin
 * it; long-press without moving for its menu.
 */
public class HomeActivity extends Activity implements AppRepository.Listener, DrawerAdapter.Actions {

    // Phase 3 makes these configurable.
    private static final int COLUMNS = 5;
    private static final int ROWS = 6; // fewer would drop icons already saved in the last row
    private static final int DOCK_SLOTS = 5;

    private AppRepository repo;
    private HomeLayout layout;
    private Prefs prefs;

    private View homeContent;
    private View clock;
    private TextClock clockDate;
    private CellLayout grid;
    private CellLayout dock;
    private ImageView handle;
    private TextView emptyHint;
    private TextView removeZone;
    private View drawer;
    private EditText search;
    private RecyclerView drawerList;
    private DrawerAdapter adapter;

    private GestureDetector gestures;
    private boolean drawerOpen;

    private final OnBackInvokedCallback closeDrawerOnBack = this::closeDrawer;

    /** What's being dragged around the home screen. */
    private static final class DragState {
        final HomeLayout.Item item;
        final View view;
        boolean droppedInPlace;
        DragState(HomeLayout.Item item, View view) { this.item = item; this.view = view; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        prefs = new Prefs(this);

        homeContent = findViewById(R.id.home_content);
        clock = findViewById(R.id.clock);
        clockDate = findViewById(R.id.clock_date);
        grid = findViewById(R.id.grid);
        dock = findViewById(R.id.dock);
        handle = findViewById(R.id.drawer_handle);
        emptyHint = findViewById(R.id.empty_hint);
        removeZone = findViewById(R.id.remove_zone);
        drawer = findViewById(R.id.drawer);
        search = findViewById(R.id.search);
        drawerList = findViewById(R.id.drawer_list);

        // Edge to edge over the wallpaper: keep content clear of the system bars,
        // and the drawer clear of the keyboard too.
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            homeContent.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            drawer.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });

        layout = HomeLayout.load(getFilesDir(), COLUMNS, ROWS, DOCK_SLOTS);
        grid.setGrid(COLUMNS, ROWS);
        dock.setGrid(DOCK_SLOTS, 1);
        repo = new AppRepository(this, this);

        adapter = new DrawerAdapter(repo, this);
        drawerList.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        drawerList.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString());
                drawerList.scrollToPosition(0);
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) return false;
            AppEntry first = adapter.first();
            if (first != null) onAppClick(first, v);
            return true;
        });

        // Swipe up on the home screen opens the drawer; swipe down at the top of the
        // drawer's list closes it.
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || Math.abs(vy) < 800 || Math.abs(vy) < Math.abs(vx)) return false;
                float dy = e2.getY() - e1.getY();
                if (!drawerOpen && dy < -dp(60)) {
                    openDrawer();
                    return true;
                }
                if (drawerOpen && dy > dp(60) && !drawerList.canScrollVertically(-1)) {
                    closeDrawer();
                    return true;
                }
                return false;
            }
        });
        handle.setOnClickListener(v -> openDrawer());
        clock.setOnClickListener(v -> openClockApp());

        grid.setOnLongClickListener(v -> { showHomeMenu(v); return true; });
        grid.setOnDragListener((v, e) -> onCellDrag(grid, HomeLayout.HOME, e));
        dock.setOnDragListener((v, e) -> onCellDrag(dock, HomeLayout.DOCK, e));
        removeZone.setOnDragListener(this::onRemoveDrag);

        repo.reload();
        refreshHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAppearance(); // settings may have changed
        maybePromptForHome();
    }

    @Override
    protected void onDestroy() {
        repo.destroy();
        super.onDestroy();
    }

    // Pressing Home while already home closes the drawer, like every launcher.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (drawerOpen) closeDrawer();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onAppsChanged() {
        adapter.setApps(repo.apps());
        refreshHome();
    }

    /* ---------- appearance ---------- */

    private void applyAppearance() {
        int accent = prefs.accent();
        drawer.setBackgroundColor(Prefs.background(prefs.drawerOpacity()));

        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(Prefs.background(prefs.dockOpacity()));
        dockBg.setCornerRadius(getResources().getDimension(R.dimen.dock_radius));
        dock.setBackground(dockBg);
        dock.setVisibility(prefs.showDock() ? View.VISIBLE : View.GONE);

        clock.setVisibility(prefs.showClock() ? View.VISIBLE : View.GONE);
        clockDate.setTextColor(accent);
        handle.setImageTintList(ColorStateList.valueOf(accent));
        drawerList.setVerticalScrollbarThumbDrawable(roundedBar(accent));
    }

    private GradientDrawable roundedBar(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(2));
        d.setSize(Math.round(dp(4)), Math.round(dp(32)));
        return d;
    }

    private void openClockApp() {
        try {
            startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
        } catch (ActivityNotFoundException ignored) {
            // no clock app; nothing to do
        }
    }

    /* ---------- default home prompt ---------- */

    // Once, when Hearth is opened but isn't the default home app yet. Settings has
    // the same button for later.
    private void maybePromptForHome() {
        if (prefs.homePromptShown() || DefaultHome.isDefault(this)) return;
        prefs.setHomePromptShown();
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_prompt_title)
                .setMessage(R.string.home_prompt_body)
                .setPositiveButton(R.string.home_prompt_yes, (d, w) -> DefaultHome.request(this))
                .setNegativeButton(R.string.home_prompt_no, null)
                .show();
    }

    /* ---------- home screen + dock ---------- */

    private void refreshHome() {
        grid.removeAllViews();
        dock.removeAllViews();
        for (HomeLayout.Item item : layout.items()) {
            AppEntry app = repo.find(item.key);
            // Not found: still loading, or on a paused work profile. Keep the cell, skip the icon.
            if (app == null) continue;
            boolean inDock = HomeLayout.DOCK.equals(item.container);
            View v = makeIcon(app, inDock ? dock : grid, !inDock);
            v.setOnClickListener(view -> repo.launch(app, view));
            v.setOnLongClickListener(view -> { startDrag(item, view); return true; });
            (inDock ? dock : grid).addView(v, new CellLayout.LayoutParams(item.x, item.y));
        }
        emptyHint.setVisibility(layout.isEmpty(HomeLayout.HOME) && layout.isEmpty(HomeLayout.DOCK)
                ? View.VISIBLE : View.GONE);
    }

    private View makeIcon(AppEntry app, CellLayout parent, boolean showLabel) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_icon, parent, false);
        ImageView icon = v.findViewById(R.id.icon);
        TextView label = v.findViewById(R.id.label);
        label.setText(app.label);
        label.setVisibility(showLabel ? View.VISIBLE : View.GONE); // dock icons go label-less
        v.setContentDescription(app.label);
        repo.loadIcon(app, icon::setImageDrawable);
        return v;
    }

    private void startDrag(HomeLayout.Item item, View view) {
        DragState state = new DragState(item, view);
        ClipData data = ClipData.newPlainText("hearth", item.key);
        if (view.startDragAndDrop(data, new View.DragShadowBuilder(view), state, 0)) {
            view.setVisibility(View.INVISIBLE);
            removeZone.setVisibility(View.VISIBLE);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    // Shared by the grid and the dock. Only the grid's listener tidies up when a
    // drag ends, so the end-of-drag work happens once.
    private boolean onCellDrag(CellLayout target, String container, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState state = (DragState) e.getLocalState();
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DROP: {
                int[] cell = target.cellAt(e.getX(), e.getY());
                if (container.equals(state.item.container)
                        && cell[0] == state.item.x && cell[1] == state.item.y) {
                    state.droppedInPlace = true; // a long-press that didn't move: show the menu
                } else {
                    layout.move(state.item, container, cell[0], cell[1]);
                }
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                if (target == grid) {
                    state.view.setVisibility(View.VISIBLE);
                    removeZone.setVisibility(View.GONE);
                    removeZone.setActivated(false);
                    refreshHome();
                    if (state.droppedInPlace) showIconMenu(state.item);
                }
                return true;
            default:
                return true;
        }
    }

    private boolean onRemoveDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState state = (DragState) e.getLocalState();
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED: removeZone.setActivated(true); return true;
            case DragEvent.ACTION_DRAG_EXITED: removeZone.setActivated(false); return true;
            case DragEvent.ACTION_DROP: layout.remove(state.item); return true;
            default: return true;
        }
    }

    /** Menu for a home or dock icon. The views were rebuilt, so find the icon again. */
    private void showIconMenu(HomeLayout.Item item) {
        AppEntry app = repo.find(item.key);
        CellLayout parent = HomeLayout.DOCK.equals(item.container) ? dock : grid;
        View anchor = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) parent.getChildAt(i).getLayoutParams();
            if (lp.cellX == item.x && lp.cellY == item.y) anchor = parent.getChildAt(i);
        }
        if (app == null || anchor == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, HomeLayout.DOCK.equals(item.container)
                ? R.string.remove_from_dock : R.string.remove_from_home);
        menu.getMenu().add(0, 2, 1, R.string.app_info);
        if (repo.canUninstall(app)) menu.getMenu().add(0, 3, 2, R.string.uninstall);
        View finalAnchor = anchor;
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: layout.remove(item); refreshHome(); return true;
                case 2: repo.openAppInfo(app, finalAnchor); return true;
                case 3: repo.uninstall(this, app); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** Long-press on empty home space. */
    private void showHomeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.open_drawer);
        menu.getMenu().add(0, 2, 1, R.string.wallpaper);
        menu.getMenu().add(0, 3, 2, R.string.settings);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: openDrawer(); return true;
                case 2: pickWallpaper(); return true;
                case 3: startActivity(new Intent(this, SettingsActivity.class)); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void pickWallpaper() {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                    getString(R.string.wallpaper)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_wallpaper_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- app drawer ---------- */

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(80));
        drawer.animate().alpha(1f).translationY(0).setDuration(180).withEndAction(null).start();
        drawerList.scrollToPosition(0);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeDrawerOnBack);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeDrawerOnBack);
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        drawer.animate().alpha(0f).translationY(dp(80)).setDuration(150)
                .withEndAction(() -> {
                    if (drawerOpen) return;
                    drawer.setVisibility(View.GONE);
                    search.setText("");
                })
                .start();
    }

    @Override
    public void onAppClick(AppEntry app, View view) {
        repo.launch(app, view);
        // Close after the launch animation starts, so returning home lands on the home screen.
        drawer.postDelayed(this::closeDrawer, 300);
    }

    @Override
    public void onAppLongClick(AppEntry app, View view) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu menu = new PopupMenu(this, view);
        boolean pinned = layout.contains(app.key);
        if (!pinned) {
            menu.getMenu().add(0, 1, 0, R.string.add_to_home);
            if (prefs.showDock()) menu.getMenu().add(0, 4, 1, R.string.add_to_dock);
        }
        menu.getMenu().add(0, 2, 2, R.string.app_info);
        if (repo.canUninstall(app)) menu.getMenu().add(0, 3, 3, R.string.uninstall);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: pin(app, HomeLayout.HOME, R.string.added_to_home, R.string.home_full); return true;
                case 4: pin(app, HomeLayout.DOCK, R.string.added_to_dock, R.string.dock_full); return true;
                case 2: repo.openAppInfo(app, view); return true;
                case 3: repo.uninstall(this, app); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void pin(AppEntry app, String container, int addedMsg, int fullMsg) {
        if (layout.add(app.key, container)) {
            refreshHome();
            Toast.makeText(this, getString(addedMsg, app.label), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, fullMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
