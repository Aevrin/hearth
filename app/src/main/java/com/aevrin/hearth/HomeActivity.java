/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.AlarmClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * The home screen: a clock, pages of apps, shortcuts and folders, and a dock,
 * over the wallpaper. Swipe up for the app drawer, sideways for pages (or across
 * the dock for dock pages). Long-press and drag to move things; the cell under
 * your finger lights up to show whether a drop moves, makes a folder, or slides
 * the others along. Long-press without moving for a menu.
 */
public class HomeActivity extends Activity
        implements AppRepository.Listener, DrawerAdapter.Actions, FolderPanel.Host {

    private static final long EDGE_PAGE_DELAY_MS = 550;
    private static final int MENU_SHORTCUT_BASE = 1000;

    private AppRepository repo;
    private HomeLayout layout;
    private Prefs prefs;
    private FolderPanel folders;
    private WallpaperManager wallpaper;

    private View homeContent;
    private View clock;
    private TextClock clockTime;
    private TextClock clockDate;
    private CellLayout grid;
    private LinearLayout pageDots;
    private CellLayout dock;
    private LinearLayout dockDots;
    private ImageView handle;
    private TextView emptyHint;
    private View dropBar;
    private TextView dropRemove, dropInfo, dropUninstall;
    private View drawer;
    private EditText search;
    private RecyclerView drawerList;
    private GridLayoutManager drawerGrid;
    private AlphaScroller alpha;
    private DrawerAdapter adapter;
    private View popupAnchor;

    private GestureDetector gestures;
    private boolean drawerOpen;
    private int currentPage;
    private int currentDockPage;
    private int homeIconPx, dockIconPx, drawerIconPx;
    private float wallpaperPage;   // animated page position for wallpaper scrolling
    private float lastTouchX, lastTouchY; // screen coordinates of the latest press

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingEdgeFlip;
    private int pendingEdgeDirection;
    private final Rect scratch = new Rect();
    private DragState pendingDrag;  // a drawer long-press that turns into a drag if the finger moves
    private PopupMenu pendingMenu;  // ...in which case its menu goes away

    private final OnBackInvokedCallback closeDrawerOnBack = this::closeDrawer;

    /** What's being dragged: a home/dock item, or an app picked up in the drawer. */
    private static final class DragState {
        final HomeLayout.Item item; // null when it comes from the drawer
        final String key;
        final AppEntry app;         // null for folders and shortcuts
        final View view;
        final boolean fromDrawer;
        float startX, startY;       // screen coordinates where the drag began
        boolean movedFar;
        DragState(HomeLayout.Item item, String key, AppEntry app, View view, boolean fromDrawer) {
            this.item = item; this.key = key; this.app = app; this.view = view; this.fromDrawer = fromDrawer;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        prefs = new Prefs(this);
        wallpaper = WallpaperManager.getInstance(this);

        homeContent = findViewById(R.id.home_content);
        clock = findViewById(R.id.clock);
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);
        grid = findViewById(R.id.grid);
        pageDots = findViewById(R.id.page_dots);
        dock = findViewById(R.id.dock);
        dockDots = findViewById(R.id.dock_dots);
        handle = findViewById(R.id.drawer_handle);
        emptyHint = findViewById(R.id.empty_hint);
        dropBar = findViewById(R.id.drop_bar);
        dropRemove = findViewById(R.id.drop_remove);
        dropInfo = findViewById(R.id.drop_info);
        dropUninstall = findViewById(R.id.drop_uninstall);
        drawer = findViewById(R.id.drawer);
        search = findViewById(R.id.search);
        drawerList = findViewById(R.id.drawer_list);
        alpha = findViewById(R.id.alpha);
        popupAnchor = findViewById(R.id.popup_anchor);

        folders = new FolderPanel(this, this, findViewById(R.id.folder_overlay), findViewById(R.id.folder_card),
                findViewById(R.id.folder_title), findViewById(R.id.folder_grid));

        // Edge to edge over the wallpaper: keep content clear of the system bars,
        // and the drawer clear of the keyboard too.
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            homeContent.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            drawer.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });

        repo = new AppRepository(this, this);
        adapter = new DrawerAdapter(repo, prefs, this);
        adapter.setTitles(getString(R.string.most_used), getString(R.string.all_apps));
        repo.setLabeler(adapter::labelOf); // letter icons use custom names
        drawerGrid = new GridLayoutManager(this, prefs.drawerColumns());
        drawerGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.isHeader(position) ? drawerGrid.getSpanCount() : 1;
            }
        });
        drawerList.setLayoutManager(drawerGrid);
        drawerList.setAdapter(adapter);
        alpha.setOnLetter(l -> {
            int pos = adapter.positionFor(l);
            if (pos >= 0) drawerGrid.scrollToPositionWithOffset(pos, 0);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString());
                drawerList.scrollToPosition(0);
                refreshAlpha();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) return false;
            AppEntry first = adapter.first();
            if (first != null) onAppClick(first, v);
            return true;
        });

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(vx) > Math.abs(vy)) {
                    if (drawerOpen || Math.abs(vx) < 600 || Math.abs(dx) < dp(60)) return false;
                    int dir = dx < 0 ? 1 : -1;
                    if (layout.dockPages() > 1 && dock.getVisibility() == View.VISIBLE
                            && dock.getGlobalVisibleRect(scratch)
                            && scratch.contains((int) e1.getRawX(), (int) e1.getRawY())) {
                        return flipDockPage(dir);
                    }
                    return flipPage(dir);
                }
                if (Math.abs(vy) < 800) return false;
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

        grid.setOnLongClickListener(v -> { showHomeMenu(); return true; });
        grid.setOnDragListener((v, e) -> onCellDrag(grid, HomeLayout.HOME, e));
        dock.setOnDragListener((v, e) -> onCellDrag(dock, HomeLayout.DOCK, e));
        dropRemove.setOnDragListener((v, e) -> onTargetDrag(dropRemove, e));
        dropInfo.setOnDragListener((v, e) -> onTargetDrag(dropInfo, e));
        dropUninstall.setOnDragListener((v, e) -> onTargetDrag(dropUninstall, e));
        findViewById(R.id.root).setOnDragListener(this::onRootDrag);

        currentPage = prefs.defaultPage();
        reloadConfig();
        repo.reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRequestedOrientation(prefs.allowRotation()
                ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        reloadConfig(); // settings, a restored backup or a newly pinned shortcut may have changed things
        for (String k : layout.allKeys()) {
            if (Shortcuts.isShortcutKey(k) && repo.findShortcut(k) == null && repo.isLoaded()) {
                repo.reload(); // a shortcut was pinned while we were away
                break;
            }
        }
        maybePromptForHome();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        repo.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyAppearance();
        afterLayout(grid, this::refreshHome); // cells changed size
    }

    // Home while already home: close what's open, then return to the default page.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (folders.isOpen()) {
            folders.close();
        } else if (drawerOpen) {
            closeDrawer();
        } else if (currentPage != prefs.defaultPage()) {
            animateToPage(prefs.defaultPage());
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.getRawX();
            lastTouchY = ev.getRawY();
        }
        if (pendingDrag != null) trackPendingDrag(ev);
        if (!folders.isOpen()) gestures.onTouchEvent(ev); // no page/drawer swipes over an open folder
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateWallpaper(currentPage);
    }

    @Override
    public void onAppsChanged() {
        adapter.setApps(repo.apps());
        refreshAlpha();
        refreshHome();
        folders.refresh();
    }

    /* ---------- FolderPanel.Host ---------- */

    @Override public HomeLayout layout() { return layout; }
    @Override public AppRepository repo() { return repo; }
    @Override public Prefs prefs() { return prefs; }
    @Override public int homeIconPx() { return homeIconPx; }
    @Override public void syncPins() { repo.syncPins(layout.allKeys()); }

    /** An icon view for an app or shortcut key, or null if it can't be shown right now. */
    @Override
    public View makeTile(String key, ViewGroup parent, boolean showLabel, int iconPx) {
        String name;
        View v = LayoutInflater.from(this).inflate(R.layout.item_icon, parent, false);
        ImageView icon = v.findViewById(R.id.icon);
        if (Shortcuts.isShortcutKey(key)) {
            ShortcutInfo si = repo.findShortcut(key);
            if (si == null) return null;
            name = repo.shortcutLabel(si);
            repo.loadShortcutIcon(key, si, icon::setImageDrawable);
        } else {
            AppEntry app = repo.find(key);
            if (app == null) return null; // still loading, or on a paused profile
            name = adapter.labelOf(app);
            repo.loadIcon(app, icon::setImageDrawable);
        }
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = iconPx;
        lp.height = iconPx;
        icon.setLayoutParams(lp);
        TextView label = v.findViewById(R.id.label);
        label.setText(name);
        prefs.styleLabel(label);
        label.setVisibility(showLabel ? View.VISIBLE : View.GONE);
        v.setContentDescription(name);
        return v;
    }

    @Override
    public void launchKey(String key, View from) {
        if (Shortcuts.isShortcutKey(key)) {
            ShortcutInfo si = repo.findShortcut(key);
            if (si != null) repo.launchShortcut(si, from);
            return;
        }
        AppEntry app = repo.find(key);
        if (app == null) return;
        prefs.recordLaunch(key);
        repo.launch(app, from);
    }

    /** Runs once, right after the view's next layout pass. */
    @Override
    public void afterLayout(View v, Runnable r) {
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                r.run();
                return true;
            }
        });
        v.requestLayout();
    }

    /* ---------- configuration ---------- */

    private void reloadConfig() {
        layout = HomeLayout.load(getFilesDir(), prefs);
        if (currentPage >= layout.pages()) currentPage = layout.pages() - 1;
        if (currentDockPage >= layout.dockPages()) currentDockPage = 0;
        grid.setGrid(layout.columns(), layout.rows());
        dock.setGrid(layout.dockSlots(), 1);
        float base = getResources().getDimension(R.dimen.icon_size);
        homeIconPx = Math.round(base * Prefs.ICON_SCALES[prefs.iconSize()]);
        dockIconPx = Math.round(base * Prefs.ICON_SCALES[prefs.dockIconSize()]);
        drawerIconPx = Math.round(base * Prefs.ICON_SCALES[prefs.drawerIconSize()]);
        repo.setTheme(IconTheme.from(prefs)); // re-renders icons only if the theme changed
        drawerGrid.setSpanCount(prefs.drawerColumns());
        adapter.applyPrefs(drawerIconPx, prefs.drawerColumns());
        applyAppearance();
        refreshAlpha();
        refreshHome();
        folders.refresh();
        updateWallpaper(currentPage);
    }

    private void applyAppearance() {
        int accent = prefs.accent();
        drawer.setBackgroundColor(Prefs.background(prefs.drawerOpacity()));
        grid.setAccent(accent);
        dock.setAccent(accent);
        alpha.setAccent(accent);

        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(Prefs.background(prefs.dockOpacity()));
        dockBg.setCornerRadius(getResources().getDimension(R.dimen.dock_radius));
        dock.setBackground(dockBg);
        boolean showDock = prefs.showDock();
        dock.setVisibility(showDock ? View.VISIBLE : View.GONE);
        // The arrow only matters when there's no dock to swipe up from.
        handle.setVisibility(showDock ? View.GONE : View.VISIBLE);
        handle.setImageTintList(ColorStateList.valueOf(accent));

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        clock.setVisibility(prefs.showClock() && !landscape ? View.VISIBLE : View.GONE); // no room sideways
        clockDate.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
        clockTime.setTextColor(accent);
        clockDate.setTextColor(accent);
        // TextClock ticks every second on its own once the format includes seconds.
        String sec = prefs.showSeconds() ? ":ss" : "";
        switch (prefs.clockFormat()) {
            case Prefs.CLOCK_12:
                clockTime.setFormat12Hour("h:mm" + sec);
                clockTime.setFormat24Hour("h:mm" + sec); // used when the phone is set to 24-hour
                break;
            case Prefs.CLOCK_24:
                clockTime.setFormat12Hour("HH:mm" + sec);
                clockTime.setFormat24Hour("HH:mm" + sec);
                break;
            default:
                clockTime.setFormat12Hour("h:mm" + sec);
                clockTime.setFormat24Hour("HH:mm" + sec);
                break;
        }
        clockTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.showSeconds() ? 52 : 64);
        drawerList.setVerticalScrollbarThumbDrawable(roundedBar(accent));
    }

    private GradientDrawable roundedBar(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(2));
        d.setSize(Math.round(dp(4)), Math.round(dp(32)));
        return d;
    }

    private void refreshAlpha() {
        boolean show = prefs.azScroller() && !adapter.isSearching();
        alpha.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) alpha.setLetters(adapter.letters());
    }

    private void openClockApp() {
        try {
            startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
        } catch (ActivityNotFoundException ignored) {
            // no clock app; nothing to do
        }
    }

    // Once, when Hearth is opened but isn't the default home app yet.
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

    /* ---------- home screen, pages and dock ---------- */

    @Override
    public void refreshHome() {
        grid.removeAllViews();
        dock.removeAllViews();
        boolean homeLabels = prefs.homeLabels();
        // In landscape (or with many rows) cells get small: shrink icons to fit.
        int gridIcon = fitIcon(grid, homeIconPx, homeLabels);
        int dockIcon = fitIcon(dock, dockIconPx, false);
        for (HomeLayout.Item item : layout.items()) {
            if (!layout.fits(item.container, item.page, item.x, item.y)) continue; // waiting for room
            boolean inDock = HomeLayout.DOCK.equals(item.container);
            if (item.page != (inDock ? currentDockPage : currentPage)) continue;
            CellLayout parent = inDock ? dock : grid;
            boolean label = !inDock && homeLabels;
            View v;
            HomeLayout.Folder folder = layout.folder(item);
            if (folder != null) {
                v = makeFolderIcon(folder, parent, label, inDock ? dockIcon : gridIcon);
                v.setOnClickListener(view -> folders.show(folder, view));
            } else {
                v = makeTile(item.key, parent, label, inDock ? dockIcon : gridIcon);
                if (v == null) continue;
                v.setOnClickListener(view -> launchKey(item.key, view));
            }
            v.setOnLongClickListener(view -> { onItemLongPress(item, view); return true; });
            parent.addView(v, new CellLayout.LayoutParams(item.x, item.y));
        }
        emptyHint.setVisibility(layout.isEmpty() ? View.VISIBLE : View.GONE);
        dots(pageDots, layout.pages(), currentPage, 7);
        dots(dockDots, layout.dockPages(), currentDockPage, 5);
        if (dock.getVisibility() != View.VISIBLE) dockDots.setVisibility(View.GONE);
    }

    private int fitIcon(CellLayout cl, int wanted, boolean label) {
        if (cl.cellHeight() <= 0 || cl.cellWidth() <= 0) return wanted;
        int labelH = label ? Math.round(dp(prefs.labelTwoLines() ? 36 : 20)) : 0;
        int room = Math.min(cl.cellWidth() - Math.round(dp(10)), cl.cellHeight() - labelH - Math.round(dp(14)));
        return Math.max(Math.round(dp(24)), Math.min(wanted, room));
    }

    private void dots(LinearLayout strip, int count, int current, int sizeDp) {
        strip.removeAllViews();
        strip.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        if (count <= 1) return;
        int size = Math.round(dp(sizeDp));
        int gap = Math.round(dp(sizeDp * 0.7f));
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == current ? prefs.accent() : 0x80E6F0FF);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            strip.addView(dot, lp);
        }
    }

    /** Slides to the next (+1) or previous (−1) page. Returns false at either end. */
    private boolean flipPage(int direction) {
        int target = currentPage + direction;
        if (target < 0 || target >= layout.pages()) return false;
        slide(grid, direction, () -> currentPage = target);
        animateWallpaper(target);
        return true;
    }

    private void animateToPage(int target) {
        if (target == currentPage || target < 0 || target >= layout.pages()) return;
        int direction = target > currentPage ? 1 : -1;
        slide(grid, direction, () -> currentPage = target);
        animateWallpaper(target);
    }

    private boolean flipDockPage(int direction) {
        int target = currentDockPage + direction;
        if (target < 0 || target >= layout.dockPages()) return false;
        slide(dock, direction, () -> currentDockPage = target);
        return true;
    }

    private void slide(View v, int direction, Runnable switchPage) {
        float w = v.getWidth();
        v.animate().cancel();
        v.animate().translationX(-direction * w * 0.3f).alpha(0f).setDuration(110)
                .withEndAction(() -> {
                    switchPage.run();
                    refreshHome();
                    v.setTranslationX(direction * w * 0.3f);
                    v.animate().translationX(0).alpha(1f).setDuration(140).withEndAction(null).start();
                })
                .start();
    }

    /* ---------- wallpaper scrolling ---------- */

    private void animateWallpaper(int target) {
        ValueAnimator a = ValueAnimator.ofFloat(wallpaperPage, target);
        a.setDuration(260);
        a.addUpdateListener(an -> updateWallpaper((float) an.getAnimatedValue()));
        a.start();
    }

    // Pans a wide wallpaper across pages, like other launchers. Off, or with one
    // page, it stays centred.
    private void updateWallpaper(float page) {
        wallpaperPage = page;
        IBinder token = grid.getWindowToken();
        if (token == null) return;
        int pages = layout == null ? 1 : layout.pages();
        boolean scroll = prefs.scrollWallpaper() && pages > 1;
        try {
            wallpaper.setWallpaperOffsetSteps(scroll ? 1f / (pages - 1) : 0f, 0f);
            wallpaper.setWallpaperOffsets(token, scroll ? page / (pages - 1) : 0.5f, 0.5f);
        } catch (IllegalArgumentException ignored) {
            // window not attached yet
        }
    }

    /* ---------- tiles ---------- */

    /** Same layout as an app icon, with the folder's mini-icon plate in place of the icon. */
    private View makeFolderIcon(HomeLayout.Folder folder, ViewGroup parent, boolean showLabel, int iconPx) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_icon, parent, false);
        ViewGroup box = (ViewGroup) v;
        View img = v.findViewById(R.id.icon);
        int index = box.indexOfChild(img);
        box.removeView(img);
        FolderIconView fi = new FolderIconView(this);
        fi.setTheme(repo.theme());
        box.addView(fi, index, new LinearLayout.LayoutParams(iconPx, iconPx));
        int shown = 0;
        for (String key : folder.apps) {
            if (shown >= 4) break;
            int slot = shown;
            if (Shortcuts.isShortcutKey(key)) {
                ShortcutInfo si = repo.findShortcut(key);
                if (si == null) continue;
                repo.loadShortcutIcon(key, si, d -> fi.setMini(slot, d));
            } else {
                AppEntry app = repo.find(key);
                if (app == null) continue;
                repo.loadIcon(app, d -> fi.setMini(slot, d));
            }
            shown++;
        }
        TextView label = v.findViewById(R.id.label);
        String name = folders.nameOf(folder);
        label.setText(name);
        prefs.styleLabel(label);
        label.setVisibility(showLabel ? View.VISIBLE : View.GONE);
        v.setContentDescription(getString(R.string.folder_description, name, folder.apps.size()));
        return v;
    }

    /* ---------- drag and drop ---------- */

    // Same as the drawer: the menu opens straight away; moving the finger closes it
    // and picks the item up. Locked: menu only, nothing moves.
    private void onItemLongPress(HomeLayout.Item item, View view) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu menu = showItemMenu(item, view);
        if (prefs.lockLayout()) return;
        armDrag(new DragState(item, item.key, repo.find(item.key), view, false), menu);
    }

    /** Waits to see whether a long-press turns into a drag (see trackPendingDrag). */
    private void armDrag(DragState st, PopupMenu menu) {
        // Stop a scrolling parent (the drawer list) taking the gesture meanwhile.
        if (st.view.getParent() != null) st.view.getParent().requestDisallowInterceptTouchEvent(true);
        st.startX = lastTouchX;
        st.startY = lastTouchY;
        pendingDrag = st;
        pendingMenu = menu;
    }

    private void startDrag(DragState st) {
        st.startX = lastTouchX;
        st.startY = lastTouchY;
        ClipData data = ClipData.newPlainText("hearth", st.key);
        if (!st.view.startDragAndDrop(data, new View.DragShadowBuilder(st.view), st, 0)) return;
        if (!st.fromDrawer) st.view.setVisibility(View.INVISIBLE);
        dropRemove.setVisibility(st.item != null ? View.VISIBLE : View.GONE);
        dropInfo.setVisibility(st.app != null ? View.VISIBLE : View.GONE);
        dropUninstall.setVisibility(st.app != null && repo.canUninstall(st.app) ? View.VISIBLE : View.GONE);
        dropBar.setVisibility(View.VISIBLE);
    }

    /** What a drop at (x, y) would do: {mode, cellX, cellY, page}. */
    private int[] dropPlan(CellLayout target, String container, DragState st, float x, float y) {
        int page = HomeLayout.HOME.equals(container) ? currentPage : currentDockPage;
        int[] cell = target.cellAt(x, y);
        HomeLayout.Item other = layout.at(container, page, cell[0], cell[1]);
        int mode;
        if (other == null || other == st.item) mode = CellLayout.HL_MOVE;
        else if (HomeLayout.isFolderKey(st.key)) mode = CellLayout.HL_INSERT; // folders don't nest
        else if (target.nearCellCentre(x, y)) mode = CellLayout.HL_FOLDER;
        else mode = CellLayout.HL_INSERT;
        return new int[] {mode, cell[0], cell[1], page};
    }

    // The grid and the dock. Tidy-up when a drag ends happens once, in onRootDrag.
    private boolean onCellDrag(CellLayout target, String container, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState st = (DragState) e.getLocalState();
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION: {
                if (target == grid) checkEdge(e.getX());
                int[] plan = dropPlan(target, container, st, e.getX(), e.getY());
                target.setHighlight(plan[1], plan[2], plan[0]);
                return true;
            }
            case DragEvent.ACTION_DRAG_EXITED:
                target.clearHighlight();
                if (target == grid) cancelEdgeFlip();
                return true;
            case DragEvent.ACTION_DROP: {
                cancelEdgeFlip();
                target.clearHighlight();
                int[] plan = dropPlan(target, container, st, e.getX(), e.getY());
                int x = plan[1], y = plan[2], page = plan[3];
                HomeLayout.Item other = layout.at(container, page, x, y);
                if (st.item != null && other == st.item) {
                    // put back where it was: nothing to do
                } else if (plan[0] == CellLayout.HL_MOVE) {
                    if (st.item != null) layout.move(st.item, container, page, x, y);
                    else layout.addAt(st.key, container, page, x, y);
                } else if (plan[0] == CellLayout.HL_FOLDER) {
                    HomeLayout.Folder f = layout.folder(other);
                    if (f != null) layout.addToFolder(f, st.key, st.item);
                    else layout.createFolder(other, st.key, st.item, "");
                } else if (!layout.insertAt(st.item, st.key, container, page, x, y)) {
                    Toast.makeText(this, HomeLayout.DOCK.equals(container) ? R.string.dock_full : R.string.home_full,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            default:
                return true;
        }
    }

    private boolean onTargetDrag(TextView target, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState st = (DragState) e.getLocalState();
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED: target.setActivated(true); return true;
            case DragEvent.ACTION_DRAG_EXITED: target.setActivated(false); return true;
            case DragEvent.ACTION_DROP:
                if (target == dropRemove && st.item != null) layout.remove(st.item);
                else if (target == dropInfo && st.app != null) repo.openAppInfo(st.app, st.view);
                else if (target == dropUninstall && st.app != null) repo.uninstall(this, st.app);
                return true;
            default:
                return true;
        }
    }

    // Sees every drag start and end, so the end-of-drag tidy-up happens exactly once.
    private boolean onRootDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState st = (DragState) e.getLocalState();
        if (e.getAction() == DragEvent.ACTION_DROP) return false;
        if (e.getAction() != DragEvent.ACTION_DRAG_ENDED) return true;
        cancelEdgeFlip();
        grid.clearHighlight();
        dock.clearHighlight();
        if (!st.fromDrawer) st.view.setVisibility(View.VISIBLE);
        dropBar.setVisibility(View.GONE);
        for (TextView t : new TextView[] {dropRemove, dropInfo, dropUninstall}) t.setActivated(false);
        refreshHome();
        syncPins();
        return true;
    }

    // Holding a dragged icon at the left or right edge flips to that page.
    private void checkEdge(float x) {
        float edge = dp(28);
        int direction = x < edge ? -1 : x > grid.getWidth() - edge ? 1 : 0;
        if (direction == pendingEdgeDirection) return;
        cancelEdgeFlip();
        if (direction == 0) return;
        int target = currentPage + direction;
        if (target < 0 || target >= layout.pages()) return;
        pendingEdgeDirection = direction;
        pendingEdgeFlip = () -> {
            pendingEdgeFlip = null;
            pendingEdgeDirection = 0;
            flipPage(direction);
        };
        handler.postDelayed(pendingEdgeFlip, EDGE_PAGE_DELAY_MS);
    }

    private void cancelEdgeFlip() {
        if (pendingEdgeFlip != null) handler.removeCallbacks(pendingEdgeFlip);
        pendingEdgeFlip = null;
        pendingEdgeDirection = 0;
    }

    /* ---------- menus ---------- */

    /** Menu for something on the home screen or dock. */
    private PopupMenu showItemMenu(HomeLayout.Item item, View anchor) {
        HomeLayout.Folder folder = layout.folder(item);
        if (folder != null) return showFolderMenu(item, folder, anchor);
        AppEntry app = repo.find(item.key);
        if (app != null) return showAppMenu(app, anchor, item);
        if (prefs.lockLayout()) return null;
        PopupMenu menu = new PopupMenu(this, anchor); // a pinned shortcut
        menu.getMenu().add(0, 1, 0, HomeLayout.DOCK.equals(item.container) ? R.string.remove_from_dock : R.string.remove_from_home);
        menu.setOnMenuItemClickListener(mi -> {
            layout.remove(item);
            refreshHome();
            syncPins();
            return true;
        });
        menu.show();
        return menu;
    }

    /**
     * An app's menu, wherever it is: its own shortcuts first (Nova-style), then
     * actions. `item` is its home/dock cell, or null in the drawer.
     */
    private PopupMenu showAppMenu(AppEntry app, View anchor, HomeLayout.Item item) {
        boolean locked = prefs.lockLayout();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        Menu m = menu.getMenu();
        m.setGroupDividerEnabled(true);
        List<ShortcutInfo> shortcuts = repo.appShortcuts(app);
        LauncherApps la = getSystemService(LauncherApps.class);
        int density = getResources().getDisplayMetrics().densityDpi;
        for (int i = 0; i < shortcuts.size(); i++) {
            ShortcutInfo si = shortcuts.get(i);
            android.view.MenuItem mi = m.add(1, MENU_SHORTCUT_BASE + i, i, repo.shortcutLabel(si));
            try {
                Drawable d = la.getShortcutIconDrawable(si, density);
                if (d != null) mi.setIcon(d);
            } catch (SecurityException | IllegalStateException ignored) { }
        }
        if (item == null && !locked && !layout.contains(app.key)) {
            m.add(2, 1, 10, R.string.add_to_home);
            if (prefs.showDock()) m.add(2, 4, 11, R.string.add_to_dock);
        }
        m.add(2, 5, 12, R.string.rename);
        m.add(2, 7, 13, R.string.change_icon);
        if (item == null) {
            boolean hidden = prefs.hidden().contains(app.key);
            m.add(2, 6, 14, hidden ? R.string.unhide : R.string.hide);
        } else if (!locked) {
            m.add(2, 8, 14, HomeLayout.DOCK.equals(item.container) ? R.string.remove_from_dock : R.string.remove_from_home);
        }
        m.add(2, 2, 15, R.string.app_info);
        if (repo.canUninstall(app)) m.add(2, 3, 16, R.string.uninstall);
        menu.setOnMenuItemClickListener(mi -> {
            int id = mi.getItemId();
            if (id >= MENU_SHORTCUT_BASE) {
                repo.launchShortcut(shortcuts.get(id - MENU_SHORTCUT_BASE), anchor);
                if (drawerOpen) drawer.postDelayed(this::closeDrawer, 300);
                return true;
            }
            switch (id) {
                case 1: pin(app, HomeLayout.HOME, R.string.added_to_home, R.string.home_full); return true;
                case 4: pin(app, HomeLayout.DOCK, R.string.added_to_dock, R.string.dock_full); return true;
                case 5: rename(app); return true;
                case 7: changeIcon(app); return true;
                case 6: {
                    boolean hidden = prefs.hidden().contains(app.key);
                    prefs.setHidden(app.key, !hidden);
                    adapter.applyPrefs(drawerIconPx, prefs.drawerColumns());
                    refreshAlpha();
                    Toast.makeText(this, getString(hidden ? R.string.unhidden : R.string.hidden_toast,
                            adapter.labelOf(app)), Toast.LENGTH_SHORT).show();
                    return true;
                }
                case 8: layout.remove(item); refreshHome(); return true;
                case 2: repo.openAppInfo(app, anchor); return true;
                case 3: repo.uninstall(this, app); return true;
                default: return false;
            }
        });
        menu.show();
        return menu;
    }

    private PopupMenu showFolderMenu(HomeLayout.Item item, HomeLayout.Folder folder, View anchor) {
        boolean locked = prefs.lockLayout();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.open_folder);
        if (!locked) {
            menu.getMenu().add(0, 2, 1, R.string.rename_folder);
            menu.getMenu().add(0, 3, 2, R.string.ungroup);
            menu.getMenu().add(0, 4, 3, HomeLayout.DOCK.equals(item.container)
                    ? R.string.remove_from_dock : R.string.remove_from_home);
        }
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: folders.show(folder, anchor); return true;
                case 2: folders.rename(folder); return true;
                case 3: {
                    int lost = layout.ungroup(item);
                    refreshHome();
                    syncPins();
                    if (lost > 0) Toast.makeText(this, getString(R.string.ungroup_no_room, lost), Toast.LENGTH_LONG).show();
                    return true;
                }
                case 4: layout.remove(item); refreshHome(); syncPins(); return true;
                default: return false;
            }
        });
        menu.show();
        return menu;
    }

    /** A popup menu that opens at the last press, for presses on empty space. */
    private PopupMenu menuAtTouch() {
        int[] origin = new int[2];
        ((View) popupAnchor.getParent()).getLocationOnScreen(origin);
        popupAnchor.setX(lastTouchX - origin[0]);
        popupAnchor.setY(lastTouchY - origin[1]);
        return new PopupMenu(this, popupAnchor, Gravity.NO_GRAVITY);
    }

    /** Long-press on empty home space: the menu opens where you pressed. */
    private void showHomeMenu() {
        boolean locked = prefs.lockLayout();
        PopupMenu menu = menuAtTouch();
        Menu m = menu.getMenu();
        m.add(0, 1, 0, R.string.open_drawer);
        m.add(0, 2, 1, R.string.wallpaper);
        m.add(0, 3, 2, R.string.settings);
        if (!locked) {
            SubMenu pages = m.addSubMenu(0, 0, 3, R.string.pages);
            pages.add(1, 10, 0, R.string.page_add);
            if (layout.pages() > 1) pages.add(1, 11, 1, R.string.page_delete);
            if (currentPage > 0) pages.add(1, 12, 2, R.string.page_move_left);
            if (currentPage < layout.pages() - 1) pages.add(1, 13, 3, R.string.page_move_right);
            if (currentPage != prefs.defaultPage()) pages.add(1, 14, 4, R.string.page_make_default);
        }
        m.add(0, 4, 4, locked ? R.string.unlock_layout : R.string.lock_layout);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: openDrawer(); return true;
                case 2: pickWallpaper(); return true;
                case 3: startActivity(new Intent(this, SettingsActivity.class)); return true;
                case 4:
                    prefs.setLockLayout(!locked);
                    Toast.makeText(this, locked ? R.string.layout_unlocked : R.string.layout_locked, Toast.LENGTH_SHORT).show();
                    return true;
                case 10: addPage(); return true;
                case 11: deletePage(); return true;
                case 12: movePage(-1); return true;
                case 13: movePage(1); return true;
                case 14:
                    prefs.setDefaultPage(currentPage);
                    Toast.makeText(this, R.string.page_is_default, Toast.LENGTH_SHORT).show();
                    return true;
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

    /* ---------- pages ---------- */

    private void addPage() {
        if (layout.pages() >= 7) {
            Toast.makeText(this, R.string.page_limit, Toast.LENGTH_SHORT).show();
            return;
        }
        int def = prefs.defaultPage();
        layout.insertPage(currentPage + 1);
        prefs.setPages(layout.pages());
        if (def > currentPage) prefs.setDefaultPage(def + 1);
        flipPage(1);
        updateWallpaper(currentPage);
    }

    private void deletePage() {
        Runnable delete = () -> {
            int def = prefs.defaultPage();
            int deleted = currentPage;
            layout.deletePage(deleted);
            prefs.setPages(layout.pages());
            if (def > deleted) prefs.setDefaultPage(def - 1);
            else if (def == deleted) prefs.setDefaultPage(0);
            currentPage = Math.min(currentPage, layout.pages() - 1);
            refreshHome();
            updateWallpaper(currentPage);
        };
        if (layout.pageIsEmpty(currentPage)) {
            delete.run();
        } else {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.page_delete_confirm)
                    .setPositiveButton(R.string.page_delete, (d, w) -> delete.run())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void movePage(int direction) {
        int target = currentPage + direction;
        if (target < 0 || target >= layout.pages()) return;
        int def = prefs.defaultPage();
        layout.swapPages(currentPage, target);
        if (def == currentPage) prefs.setDefaultPage(target);
        else if (def == target) prefs.setDefaultPage(currentPage);
        currentPage = target; // stay with the page you're moving
        refreshHome();
        updateWallpaper(currentPage);
    }

    /* ---------- rename / icon style ---------- */

    @Override
    public void rename(AppEntry app) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(adapter.labelOf(app));
        input.setSelectAllOnFocus(true);
        input.setHint(app.label);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(dp(20));
        box.setPadding(pad, Math.round(dp(8)), pad, 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> applyRename(app, input.getText().toString()))
                .setNeutralButton(R.string.reset_name, (d, w) -> applyRename(app, null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
    }

    private void applyRename(AppEntry app, String name) {
        prefs.setCustomLabel(app.key, name != null && name.trim().equals(app.label) ? null : name);
        repo.setTheme(IconTheme.from(prefs));
        adapter.setApps(repo.apps());
        refreshAlpha();
        refreshHome();
        folders.refresh();
    }

    @Override
    public void changeIcon(AppEntry app) {
        String[] styles = IconTheme.STYLES;
        String[] names = new String[styles.length + 1];
        names[0] = getString(R.string.icon_follow_theme);
        for (int i = 0; i < styles.length; i++) names[i + 1] = getString(styleName(styles[i]));
        String current = prefs.iconOverrides().get(app.key);
        int checked = 0;
        for (int i = 0; i < styles.length; i++) if (styles[i].equals(current)) checked = i + 1;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.change_icon_for, adapter.labelOf(app)))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs.setIconOverride(app.key, which == 0 ? null : styles[which - 1]);
                    repo.setTheme(IconTheme.from(prefs));
                    adapter.applyPrefs(drawerIconPx, prefs.drawerColumns());
                    refreshHome();
                    folders.refresh();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static int styleName(String style) {
        switch (style) {
            case IconTheme.THEMED: return R.string.style_themed;
            case IconTheme.DUOTONE: return R.string.style_duotone;
            case IconTheme.GRAYSCALE: return R.string.style_grayscale;
            case IconTheme.LETTER: return R.string.style_letter;
            default: return R.string.style_original;
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
        // Hide the home screen behind it, so a see-through drawer shows only the wallpaper.
        homeContent.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> { if (drawerOpen) homeContent.setVisibility(View.INVISIBLE); })
                .start();
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
        homeContent.setVisibility(View.VISIBLE);
        homeContent.animate().alpha(1f).setDuration(150).withEndAction(null).start();
        drawer.animate().alpha(0f).translationY(dp(80)).setDuration(150)
                .withEndAction(() -> {
                    if (drawerOpen) return;
                    drawer.setVisibility(View.GONE);
                    search.setText("");
                })
                .start();
    }

    // Instantly, mid-drag: the home screen must be visible to receive the drop.
    private void hideDrawerForDrag() {
        drawerOpen = false;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeDrawerOnBack);
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        drawer.animate().cancel();
        homeContent.animate().cancel();
        homeContent.setAlpha(1f);
        homeContent.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.GONE);
        search.setText("");
    }

    @Override
    public void onAppClick(AppEntry app, View view) {
        launchKey(app.key, view);
        // Close after the launch animation starts, so returning home lands on the home screen.
        drawer.postDelayed(this::closeDrawer, 300);
    }

    // Long-press in the drawer: the menu opens straight away, and if the finger
    // then moves, the menu closes and the app is picked up instead (the drawer
    // gets out of the way so it can be dropped on the home screen). Locked: menu only.
    @Override
    public void onAppLongClick(AppEntry app, View view) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu menu = showAppMenu(app, view, null);
        if (prefs.lockLayout()) return;
        armDrag(new DragState(null, app.key, app, view, true), menu);
    }

    // The rest of the long-press gesture still comes to this window, even with the menu up.
    private void trackPendingDrag(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(ev.getRawX() - pendingDrag.startX, ev.getRawY() - pendingDrag.startY) < dp(12)) return;
                DragState st = pendingDrag;
                pendingDrag = null;
                if (pendingMenu != null) pendingMenu.dismiss();
                pendingMenu = null;
                st.movedFar = true;
                startDrag(st);        // captures the icon for the drag shadow first...
                if (st.fromDrawer) hideDrawerForDrag(); // ...then reveals the home screen to drop on
                return;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pendingDrag = null;   // let go without moving: the menu stays
                pendingMenu = null;
                return;
            default:
        }
    }

    private void pin(AppEntry app, String container, int addedMsg, int fullMsg) {
        int page = HomeLayout.HOME.equals(container) ? currentPage : currentDockPage;
        if (layout.add(app.key, container, page)) {
            refreshHome();
            Toast.makeText(this, getString(addedMsg, adapter.labelOf(app)), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, fullMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
