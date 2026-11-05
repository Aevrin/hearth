/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Point;
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
import android.view.animation.DecelerateInterpolator;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen: a clock, pages of apps, shortcuts and folders, and a dock,
 * over the wallpaper. Swipe up for the app drawer, sideways for pages (or across
 * the dock for dock pages). Long-press and drag to move things; the cell under
 * your finger lights up to show whether a drop moves, makes a folder, or slides
 * the others along. Long-press without moving for a menu.
 */
public class HomeActivity extends Activity
        implements AppRepository.Listener, DrawerAdapter.Actions, FolderPanel.Host, Sidebar.Host {

    private static final long EDGE_PAGE_DELAY_MS = 550;
    private static final int MENU_SHORTCUT_BASE = 1000;
    private static final int REQ_WIDGET_BIND = 21, REQ_WIDGET_CONFIGURE = 22, REQ_WIDGET_RECONFIGURE = 23;
    private static final int REQ_FOLDER_IMAGE = 31;

    private AppRepository repo;
    private HomeLayout layout;
    private Prefs prefs;
    private FolderPanel folders;
    private WallpaperManager wallpaper;
    private WidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    /** Widget views are kept and reused across refreshes, so they don't reload. */
    private final Map<Integer, WidgetHost.View> widgetViews = new HashMap<>();
    // A widget being added: waiting on the bind permission or its setup screen.
    private int pendingWidgetId = -1;
    private int pendingWidgetPage;
    private String pendingWidgetSidebar; // non-null when the widget being added goes to a sidebar panel
    private String pendingFolderImage; // folder id waiting for a picked picture
    /** Rendered custom folder icons, by folder id + icon + theme. */
    private final Map<String, android.graphics.Bitmap> folderIcons = new HashMap<>();
    private float dropRemoveScale = 1f; // the trash can is bigger while dragging a widget

    // Sidebars: dragged in from an edge zone (exempt from the system Back gesture).
    private Sidebar sidebar;
    private View handleLeft, handleRight;
    private final Rect edgeLeft = new Rect(), edgeRight = new Rect();
    private int edgeSide = Sidebar.NONE;
    private boolean edgeDragging;
    private float edgeStartX;
    private long edgeStartTime;
    private float closeStartX;          // swiping an open sidebar back toward its edge
    // Two-finger swipes.
    private boolean multiTouch, twoFired;
    private float twoStartY;
    // Live "make room" preview while hovering to insert.
    private CellLayout previewTarget;
    private int previewX = -1, previewY = -1, previewPage = -1;
    private ResizeFrame resizeFrame;   // non-null while a widget is being resized
    private HomeLayout.Item resizing;

    private View homeContent;
    private View clock;
    private TextClock clockTime;
    private TextClock clockDate;
    private View clockStatus;
    private TextView statusAlarm, statusBattery;
    private Intent lastBattery;
    // Next alarm and battery level, only listened for while the home screen is showing.
    private final android.content.BroadcastReceiver statusReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) lastBattery = i;
            updateStatus();
        }
    };
    private boolean statusRegistered;
    private CellLayout grid;
    private LinearLayout pageDots;
    private CellLayout dock;
    private LinearLayout dockDots;
    private ImageView handle;
    private TextView emptyHint;
    private final WallpaperManager.OnColorsChangedListener wallpaperColors = (colors, which) -> {
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) applySystemBars();
    };
    private View dropBar;
    private ImageView dropRemove, dropInfo, dropUninstall;
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
    private DragState pendingDrag;  // a long-press that turns into a drag if the finger moves...
    private PopupMenu pendingMenu;  // ...in which case its menu (if already showing) goes away
    /** After a long-press, how long to wait for movement before showing the menu. */
    private static final long MENU_DELAY_MS = 320;
    private Runnable pendingMenuShow; // shows the menu if the finger stays still

    private final OnBackInvokedCallback closeDrawerOnBack = this::closeDrawer;

    /** What's being dragged: a home/dock item, or an app picked up in the drawer. */
    private static final class DragState {
        final HomeLayout.Item item; // null when it comes from the drawer
        final String key;
        final AppEntry app;         // null for folders and shortcuts
        final View view;
        final boolean fromDrawer;
        float startX, startY;       // screen coordinates where the drag began
        int grabX, grabY;           // which cell of a widget the finger is on
        HomeLayout.Folder folder;   // set when the drag started inside an open folder
        float offX, offY;           // where the finger is on the view, for the shadow and landing
        float dropX = -1, dropY;    // screen point of a drop on the grid or dock
        Runnable onRelease;         // run if the long-press ends without moving (widgets: resize)
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
        // No translucent backing behind the system bars: it washes out dark button
        // icons. Hearth picks light or dark icons for the wallpaper instead.
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().setStatusBarContrastEnforced(false);
        widgetHost = new WidgetHost(this);
        widgetManager = AppWidgetManager.getInstance(this);
        if (savedInstanceState != null) {
            pendingWidgetId = savedInstanceState.getInt("pending_widget", -1);
            pendingWidgetPage = savedInstanceState.getInt("pending_widget_page", 0);
            pendingWidgetSidebar = savedInstanceState.getString("pending_widget_sidebar");
        }

        homeContent = findViewById(R.id.home_content);
        clock = findViewById(R.id.clock);
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);
        clockStatus = findViewById(R.id.clock_status);
        statusAlarm = findViewById(R.id.status_alarm);
        statusBattery = findViewById(R.id.status_battery);
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
        sidebar = new Sidebar(this, this, findViewById(R.id.sidebar_layer), findViewById(R.id.sidebar_scrim),
                findViewById(R.id.sidebar_panel), findViewById(R.id.sidebar_list), findViewById(R.id.sidebar_add));
        handleLeft = findViewById(R.id.handle_left);
        handleRight = findViewById(R.id.handle_right);
        findViewById(R.id.root).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) updateEdges();
        });

        // Edge to edge over the wallpaper: keep content clear of the system bars,
        // and the drawer clear of the keyboard too.
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            homeContent.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            drawer.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            sidebar.setInsets(bars.top, Math.max(bars.bottom, ime.bottom)); // notes stay above the keyboard
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
            // Double-tap on empty space (not on an icon or widget).
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (drawerOpen || sidebar.isOpen() || overItem(e.getRawX(), e.getRawY())) return false;
                return runGesture(prefs.gesture(Prefs.GESTURE_DOUBLE_TAP));
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || multiTouch || sidebar.isOpen()) return false; // two fingers: see dispatchTouchEvent
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
                    // Pages first; the left/right gesture only runs when there's no page that way.
                    if (flipPage(dir)) return true;
                    return runGesture(prefs.gesture(dir > 0 ? Prefs.GESTURE_LEFT : Prefs.GESTURE_RIGHT));
                }
                if (Math.abs(vy) < 800) return false;
                if (!drawerOpen && dy < -dp(60)) return runGesture(prefs.gesture(Prefs.GESTURE_UP));
                if (!drawerOpen && dy > dp(60)) return runGesture(prefs.gesture(Prefs.GESTURE_DOWN));
                if (drawerOpen && dy > dp(60) && !drawerList.canScrollVertically(-1)) {
                    // Pull-to-search: the first pull at the top shows the search bar.
                    if (search.getVisibility() != View.VISIBLE) revealSearch();
                    else closeDrawer();
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
        folders.grid().setOnDragListener(this::onFolderGridDrag);
        findViewById(R.id.folder_overlay).setOnDragListener(this::onFolderOverlayDrag);

        currentPage = prefs.defaultPage();
        reloadConfig();
        deleteOrphanWidgets();
        repo.reload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStatus();
        wallpaper.addOnColorsChangedListener(wallpaperColors, handler);
        try {
            widgetHost.startListening(); // widgets update while Hearth is visible
        } catch (RuntimeException ignored) {
            // can fail briefly while the system is starting up
        }
    }

    @Override
    protected void onPause() {
        endResize();
        sidebar.closeNow();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Screen off or leaving home: nothing stays open.
        sidebar.closeNow();
        folders.closeNow();
        if (statusRegistered) {
            unregisterReceiver(statusReceiver);
            statusRegistered = false;
        }
        wallpaper.removeOnColorsChangedListener(wallpaperColors);
        try {
            widgetHost.stopListening();
        } catch (RuntimeException ignored) { }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("pending_widget", pendingWidgetId);
        out.putInt("pending_widget_page", pendingWidgetPage);
        out.putString("pending_widget_sidebar", pendingWidgetSidebar);
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
        endResize(); // the grid's geometry is about to change
        applyAppearance();
        afterLayout(grid, this::refreshHome); // cells changed size
    }

    // Home while already home: close what's open, then return to the default page.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (resizeFrame != null) {
            endResize();
        } else if (sidebar.isOpen()) {
            sidebar.close();
        } else if (folders.isOpen()) {
            folders.close();
        } else if (drawerOpen) {
            closeDrawer();
        } else {
            if (currentPage != prefs.defaultPage()) animateToPage(prefs.defaultPage());
            // And the dock back to its first page (optional, on by default).
            if (prefs.dockHomeReset() && currentDockPage != 0) {
                slide(dock, -1, () -> currentDockPage = 0);
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.getRawX();
            lastTouchY = ev.getRawY();
        }
        if (pendingDrag != null) trackPendingDrag(ev);
        if (trackSidebarEdge(ev)) return true;
        trackTwoFingers(ev);
        // No page/drawer swipes over an open folder or while resizing a widget.
        if (!folders.isOpen() && resizeFrame == null) gestures.onTouchEvent(ev);
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
        sidebar.refresh();
    }

    /* ---------- FolderPanel.Host ---------- */

    @Override public HomeLayout layout() { return layout; }
    @Override public AppRepository repo() { return repo; }
    @Override public Prefs prefs() { return prefs; }
    @Override public int homeIconPx() { return homeIconPx; }
    @Override public void syncPins() {
        java.util.Set<String> keys = layout.allKeys();
        keys.addAll(prefs.pinnedTargets()); // shortcuts used by gestures and sidebars stay pinned
        repo.syncPins(keys);
    }

    /* ---------- Sidebar.Host ---------- */

    @Override public int sidebarIconPx() { return drawerIconPx; }
    @Override public boolean launchTarget(String spec, View from) { return Targets.launch(this, repo, prefs, spec, from); }
    @Override public void sidebarChanged() { syncPins(); }

    @Override public View sidebarWidget(int id) { return widgetViewFor(id); }

    @Override public void addSidebarWidget(String side) {
        new WidgetPicker(this, this::spanX, this::spanY).show(info -> {
            pendingWidgetSidebar = side;
            addWidget(info);
        });
    }

    @Override public void removeSidebarWidget(int id) {
        widgetViews.remove(id);
        widgetHost.deleteAppWidgetId(id);
    }

    @Override public boolean widgetHasSettings(int id) {
        return reconfigurable(widgetManager.getAppWidgetInfo(id));
    }

    @Override public void openWidgetSettings(int id) {
        try {
            widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_RECONFIGURE, null);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.widget_failed, Toast.LENGTH_SHORT).show();
        }
    }

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
        prefs.markSeen(app);
        repo.launch(app, from);
    }

    @Override
    public void armFolderDrag(HomeLayout.Folder f, String key, View v, java.util.function.Supplier<PopupMenu> menu) {
        DragState st = new DragState(null, key, repo.find(key), v, false);
        st.folder = f;
        armDrag(st, menu);
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
        endResize();
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
        updateEdges();
    }

    private void applyAppearance() {
        Accent style = prefs.accentStyle();
        int accent = style.solid();
        drawer.setBackgroundColor(Prefs.background(prefs.drawerOpacity()));
        grid.setAccent(style);
        dock.setAccent(style);
        alpha.setAccent(style);

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
        style.paint(clockTime);
        style.paint(clockDate);
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
        drawerList.setVerticalScrollbarThumbDrawable(roundedBar(style));
        GradientDrawable searchBg = new GradientDrawable(); // follows the base colour
        searchBg.setColor(Prefs.elevated());
        searchBg.setCornerRadius(dp(24));
        search.setBackground(searchBg);
        statusAlarm.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
        statusBattery.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
        style.paint(statusAlarm);
        style.paint(statusBattery);
        registerStatus();
        updateStatus();
        applySystemBars();
        // "Text in accent colour" reaches the search box and the empty-screen hint too.
        int text = Prefs.textColor();
        if (prefs.labelAccent()) {
            search.setTextColor(accent);
            search.setHintTextColor(Accent.withAlpha(accent, 150));
            style.paint(emptyHint);
        } else {
            search.setTextColor(text);
            search.setHintTextColor(Prefs.mutedTextColor());
            Accent.plain(emptyHint, text);
        }
    }

    /**
     * Light or dark status bar and navigation bar icons. Android doesn't let apps
     * colour them; Auto follows the wallpaper (and goes light over a dark drawer).
     */
    private void applySystemBars() {
        boolean wallpaperLight = false;
        try {
            android.app.WallpaperColors c = wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            wallpaperLight = c != null && (c.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        } catch (RuntimeException ignored) { }
        boolean overDarkDrawer = drawerOpen && prefs.drawerOpacity() >= 50;
        boolean autoDark = wallpaperLight && !overDarkDrawer;
        boolean darkStatus = shade(prefs.statusIcons(), autoDark);
        boolean darkNav = shade(prefs.navIcons(), autoDark);
        // "LIGHT_*_BARS" in Android's terms means a light bar, i.e. dark icons.
        int flags = (darkStatus ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                | (darkNav ? android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0);
        getWindow().getInsetsController().setSystemBarsAppearance(flags,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    /** True for dark icons. */
    private static boolean shade(int mode, boolean autoDark) {
        switch (mode) {
            case Prefs.ICONS_LIGHT: return false;
            case Prefs.ICONS_DARK: return true;
            default: return autoDark;
        }
    }

    private GradientDrawable roundedBar(Accent a) {
        GradientDrawable d = a.drawable();
        if (a.gradient) d.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); // it's a vertical bar
        d.setCornerRadius(dp(2));
        d.setSize(Math.round(dp(4)), Math.round(dp(32)));
        return d;
    }

    private void refreshAlpha() {
        boolean show = prefs.azScroller() && !adapter.isSearching() && prefs.drawerSort() == Prefs.SORT_AZ;
        alpha.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) alpha.setLetters(adapter.letters());
    }

    /* ---------- sidebars and extra gestures ---------- */

    // Each enabled sidebar gets an edge zone: up to 200dp tall (the most Android
    // lets an app take back from the Back gesture), top, middle or bottom.
    private void updateEdges() {
        View root = findViewById(R.id.root);
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int zoneH = Math.min(Math.round(dp(200)), h / 3);
        int zoneW = Math.round(dp(Prefs.ZONE_WIDTHS[prefs.sidebarZone()]));
        int top;
        switch (prefs.sidebarHandlePos()) {
            case Prefs.HANDLE_TOP: top = Math.round(h * 0.18f); break;
            case Prefs.HANDLE_BOTTOM: top = Math.round(h * 0.78f) - zoneH; break;
            default: top = (h - zoneH) / 2; break;
        }
        boolean left = prefs.sidebar(Prefs.SIDEBAR_LEFT), right = prefs.sidebar(Prefs.SIDEBAR_RIGHT);
        edgeLeft.set(0, top, left ? zoneW : 0, top + zoneH);
        edgeRight.set(right ? w - zoneW : w, top, w, top + zoneH);
        List<Rect> exclude = new java.util.ArrayList<>();
        if (left) exclude.add(new Rect(edgeLeft));
        if (right) exclude.add(new Rect(edgeRight));
        root.setSystemGestureExclusionRects(exclude);
        boolean shown = prefs.sidebarHandleShown();
        placeHandle(handleLeft, left && shown, top + (zoneH - Math.round(dp(80))) / 2);
        placeHandle(handleRight, right && shown, top + (zoneH - Math.round(dp(80))) / 2);
        if (sidebar.isOpen() && !prefs.sidebar(Sidebar.key(sidebar.side()))) sidebar.closeNow();
    }

    private void placeHandle(View handle, boolean show, int top) {
        handle.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        GradientDrawable d = new GradientDrawable();
        d.setColor(Accent.withAlpha(prefs.accent(), 140));
        d.setCornerRadius(dp(3));
        handle.setBackground(d);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) handle.getLayoutParams();
        lp.width = Math.round(dp(6));
        lp.height = Math.round(dp(80));
        lp.topMargin = top;
        handle.setLayoutParams(lp);
    }

    /**
     * Swiping in from an edge zone drags the sidebar out with the finger; swiping
     * an open sidebar back toward its edge closes it. Returns true when it took
     * the touch, so nothing underneath reacts.
     */
    private boolean trackSidebarEdge(MotionEvent ev) {
        float x = ev.getRawX(), y = ev.getRawY();
        int slop = Math.round(dp(10));
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeSide = Sidebar.NONE;
                edgeDragging = false;
                closeStartX = x;
                if (sidebar.isOpen() || drawerOpen || folders.isOpen() || resizeFrame != null) return false;
                if (edgeLeft.contains((int) x, (int) y)) edgeSide = Sidebar.LEFT;
                else if (edgeRight.contains((int) x, (int) y)) edgeSide = Sidebar.RIGHT;
                edgeStartX = x;
                edgeStartTime = ev.getEventTime();
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (sidebar.isOpen() && edgeSide == Sidebar.NONE && !sidebar.isPanel()) {
                    // Open: a swipe back toward its edge closes it.
                    float back = sidebar.side() == Sidebar.LEFT ? closeStartX - x : x - closeStartX;
                    if (back > dp(48)) {
                        cancelTouch(ev);
                        sidebar.close();
                        closeStartX = Float.NaN;
                        return true;
                    }
                    return false;
                }
                if (edgeSide == Sidebar.NONE) return false;
                if (pendingDrag != null && !edgeDragging) {
                    edgeSide = Sidebar.NONE; // an icon was long-pressed in the zone: it's an icon drag
                    return false;
                }
                float in = edgeSide == Sidebar.LEFT ? x - edgeStartX : edgeStartX - x;
                if (!edgeDragging) {
                    if (in < slop) return false;
                    edgeDragging = true;
                    cancelTouch(ev); // whatever was pressed underneath lets go
                    sidebar.beginDrag(edgeSide);
                }
                sidebar.dragTo(in);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!edgeDragging) {
                    edgeSide = Sidebar.NONE;
                    return false;
                }
                float in = edgeSide == Sidebar.LEFT ? x - edgeStartX : edgeStartX - x;
                boolean flick = ev.getEventTime() - edgeStartTime < 250 && in > dp(40);
                sidebar.endDrag(flick);
                edgeSide = Sidebar.NONE;
                edgeDragging = false;
                return true;
            }
            default:
                return edgeDragging;
        }
    }

    private void cancelTouch(MotionEvent ev) {
        MotionEvent c = MotionEvent.obtain(ev);
        c.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(c);
        gestures.onTouchEvent(c);
        c.recycle();
    }

    // Two fingers moving up or down together, on the home screen.
    private void trackTwoFingers(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multiTouch = false;
                twoFired = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() == 2) {
                    multiTouch = true;
                    twoStartY = (ev.getY(0) + ev.getY(1)) / 2f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!multiTouch || twoFired || ev.getPointerCount() < 2 || drawerOpen || sidebar.isOpen()) break;
                float dy = (ev.getY(0) + ev.getY(1)) / 2f - twoStartY;
                if (Math.abs(dy) > dp(80)) {
                    twoFired = true;
                    runGesture(prefs.gesture(dy < 0 ? Prefs.GESTURE_TWO_UP : Prefs.GESTURE_TWO_DOWN));
                }
                break;
            default:
                break;
        }
    }

    /** Whether a screen point is on an icon, folder or widget (double-tap only counts on empty space). */
    private boolean overItem(float rawX, float rawY) {
        for (CellLayout cl : new CellLayout[] {grid, dock}) {
            for (int i = 0; i < cl.getChildCount(); i++) {
                cl.getChildAt(i).getGlobalVisibleRect(scratch);
                if (scratch.contains((int) rawX, (int) rawY)) return true;
            }
        }
        return clock.getVisibility() == View.VISIBLE && clock.getGlobalVisibleRect(scratch)
                && scratch.contains((int) rawX, (int) rawY);
    }

    /* ---------- gestures ---------- */

    /** Runs a gesture action. Returns false for "nothing", so the swipe isn't swallowed. */
    private boolean runGesture(String action) {
        if (Targets.isTarget(action)) {
            Targets.launch(this, repo, prefs, action, homeContent); // an app, activity or shortcut
            return true;
        }
        switch (action) {
            case Prefs.G_SIDEBAR_LEFT:
                sidebar.open(Sidebar.LEFT);
                return true;
            case Prefs.G_SIDEBAR_RIGHT:
                sidebar.open(Sidebar.RIGHT);
                return true;
            case Prefs.G_DRAWER:
                openDrawer();
                return true;
            case Prefs.G_SEARCH:
                openDrawer();
                search.setVisibility(View.VISIBLE);
                search.requestFocus();
                search.post(() -> getSystemService(InputMethodManager.class)
                        .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
                return true;
            case Prefs.G_NOTIFICATIONS:
                expandStatusBar("expandNotificationsPanel");
                return true;
            case Prefs.G_QUICK_SETTINGS:
                expandStatusBar("expandSettingsPanel");
                return true;
            case Prefs.G_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return false;
        }
    }

    // There's no public API for this; every launcher that offers it calls the
    // status bar service like this (it needs the EXPAND_STATUS_BAR permission,
    // which is granted at install). If a future Android blocks it, say so.
    @android.annotation.SuppressLint({"WrongConstant", "PrivateApi"})
    private void expandStatusBar(String method) {
        try {
            Object service = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod(method).invoke(service);
        } catch (Exception | LinkageError e) {
            Toast.makeText(this, R.string.gesture_shade_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- next alarm and battery ---------- */

    // Both broadcasts come from the system with no permission; nothing is
    // registered unless one of the two is switched on.
    private void registerStatus() {
        boolean want = prefs.showNextAlarm() || prefs.showBattery();
        if (want && !statusRegistered) {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            lastBattery = registerReceiver(statusReceiver, f, RECEIVER_NOT_EXPORTED); // battery is sticky
            statusRegistered = true;
        } else if (!want && statusRegistered) {
            unregisterReceiver(statusReceiver);
            statusRegistered = false;
        }
    }

    private void updateStatus() {
        boolean alarm = prefs.showNextAlarm() && updateAlarm();
        boolean battery = prefs.showBattery() && updateBattery();
        statusAlarm.setVisibility(alarm ? View.VISIBLE : View.GONE);
        statusBattery.setVisibility(battery ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) statusBattery.getLayoutParams()).setMarginStart(alarm ? Math.round(dp(14)) : 0);
        clockStatus.setVisibility(alarm || battery ? View.VISIBLE : View.GONE);
    }

    /** "6:30" today or tomorrow morning, "Tue 6:30" further off. False when no alarm is set. */
    private boolean updateAlarm() {
        android.app.AlarmManager.AlarmClockInfo next = getSystemService(android.app.AlarmManager.class).getNextAlarmClock();
        if (next == null) return false;
        boolean h24;
        switch (prefs.clockFormat()) {
            case Prefs.CLOCK_12: h24 = false; break;
            case Prefs.CLOCK_24: h24 = true; break;
            default: h24 = android.text.format.DateFormat.is24HourFormat(this); break;
        }
        boolean soon = next.getTriggerTime() - System.currentTimeMillis() < 20L * 60 * 60 * 1000;
        String skeleton = (soon ? "" : "EEE") + (h24 ? "Hm" : "hma");
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), skeleton);
        statusAlarm.setText(android.text.format.DateFormat.format(pattern, next.getTriggerTime()));
        statusAlarm.setContentDescription(getString(R.string.next_alarm_description, statusAlarm.getText()));
        return true;
    }

    private boolean updateBattery() {
        Intent b = lastBattery;
        if (b == null) return false;
        int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return false;
        int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        int pct = Math.round(level * 100f / scale);
        statusBattery.setText(getString(R.string.percent, pct));
        statusBattery.setCompoundDrawablesRelativeWithIntrinsicBounds(
                charging ? R.drawable.ic_status_charging : R.drawable.ic_status_battery, 0, 0, 0);
        statusBattery.setContentDescription(getString(charging ? R.string.battery_charging : R.string.battery_level, pct));
        return true;
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
        previewTarget = null; // a page change mid-drag starts a fresh preview
        previewX = previewY = previewPage = -1;
        grid.removeAllViews();
        dock.removeAllViews();
        boolean homeLabels = prefs.homeLabels();
        // In landscape (or with many rows) cells get small: shrink icons to fit.
        int gridIcon = fitIcon(grid, homeIconPx, homeLabels, layout.iconSpan(HomeLayout.HOME));
        int dockIcon = fitIcon(dock, dockIconPx, false, 1);
        for (HomeLayout.Item item : layout.items()) {
            if (!layout.fitsItem(item, item.container, item.page, item.x, item.y)) continue; // waiting for room
            boolean inDock = HomeLayout.DOCK.equals(item.container);
            if (item.page != (inDock ? currentDockPage : currentPage)) continue;
            CellLayout parent = inDock ? dock : grid;
            boolean label = !inDock && homeLabels;
            View v;
            HomeLayout.Folder folder = layout.folder(item);
            if (HomeLayout.isWidgetKey(item.key)) {
                v = widgetView(item);
                v.setTag(R.id.home_item, item);
                v.setOnLongClickListener(view -> { onItemLongPress(item, view); return true; });
                parent.addView(v, new CellLayout.LayoutParams(item.x, item.y, item.spanX, item.spanY));
                continue;
            } else if (folder != null) {
                v = makeFolderIcon(folder, parent, label, inDock ? dockIcon : gridIcon);
                v.setOnClickListener(view -> folders.show(folder, view));
            } else {
                v = makeTile(item.key, parent, label, inDock ? dockIcon : gridIcon);
                if (v == null) continue;
                v.setOnClickListener(view -> launchKey(item.key, view));
            }
            v.setTag(R.id.home_item, item);
            v.setOnLongClickListener(view -> { onItemLongPress(item, view); return true; });
            parent.addView(v, new CellLayout.LayoutParams(item.x, item.y, item.spanX, item.spanY));
        }
        emptyHint.setVisibility(layout.isEmpty() ? View.VISIBLE : View.GONE);
        dots(pageDots, layout.pages(), currentPage, 7);
        dots(dockDots, layout.dockPages(), currentDockPage, 5);
        if (dock.getVisibility() != View.VISIBLE) dockDots.setVisibility(View.GONE);
    }

    // An icon covers `span` cells each way (2 on the subgrid).
    private int fitIcon(CellLayout cl, int wanted, boolean label, int span) {
        if (cl.cellHeight() <= 0 || cl.cellWidth() <= 0) return wanted;
        int labelH = label ? Math.round(dp(prefs.labelTwoLines() ? 36 : 20)) : 0;
        int room = Math.min(cl.cellWidth() * span - Math.round(dp(10)),
                cl.cellHeight() * span - labelH - Math.round(dp(14)));
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
            // With a gradient, the current dot takes its colour from its place along the row.
            d.setColor(i == current ? prefs.accentStyle().at(count > 1 ? (float) i / (count - 1) : 0f)
                    : Accent.withAlpha(Prefs.textColor(), 0x80));
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
        int n = layout.dockPages();
        int target = currentDockPage + direction;
        if (target < 0 || target >= n) {
            if (!prefs.dockWrap() || n < 2) return false;
            target = (target + n) % n; // infinite scroll: round to the other end
        }
        int to = target;
        slide(dock, direction, () -> currentDockPage = to);
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
        View custom = folder.icon == null ? null : makeCustomFolderIcon(folder, parent, iconPx);
        if (custom != null) {
            TextView label = custom.findViewById(R.id.label);
            String name = folders.nameOf(folder);
            label.setText(name);
            prefs.styleLabel(label);
            label.setVisibility(showLabel ? View.VISIBLE : View.GONE);
            custom.setContentDescription(getString(R.string.folder_description, name, folder.apps.size()));
            return custom;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.item_icon, parent, false);
        ViewGroup box = (ViewGroup) v;
        View img = v.findViewById(R.id.icon);
        int index = box.indexOfChild(img);
        box.removeView(img);
        FolderIconView fi = new FolderIconView(this);
        fi.setTheme(repo.theme());
        fi.setPlate(prefs.bgFill(Prefs.FOLDER_PLATE), prefs.bgOpacity(Prefs.FOLDER_PLATE));
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

    /** A folder shown with its own icon (an app's, a character or a picture), or null to use mini icons. */
    private View makeCustomFolderIcon(HomeLayout.Folder folder, ViewGroup parent, int iconPx) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_icon, parent, false);
        ImageView icon = v.findViewById(R.id.icon);
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = iconPx;
        lp.height = iconPx;
        icon.setLayoutParams(lp);
        String spec = folder.icon;
        if (spec.startsWith("app:")) {
            AppEntry app = repo.find(spec.substring(4));
            if (app == null) return null;
            repo.loadIcon(app, icon::setImageDrawable);
            return v;
        }
        String cacheKey = folder.id + "|" + spec.hashCode() + "|" + repo.theme().signature;
        android.graphics.Bitmap bmp = folderIcons.get(cacheKey);
        if (bmp == null) {
            try {
                if (spec.startsWith("text:")) {
                    bmp = repo.theme().glyph(spec.substring(5));
                } else if (spec.startsWith("image:")) {
                    byte[] png = android.util.Base64.decode(spec.substring(6), android.util.Base64.DEFAULT);
                    android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (src == null) return null;
                    bmp = repo.theme().image(src);
                } else {
                    return null;
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
            folderIcons.put(cacheKey, bmp);
        }
        icon.setImageBitmap(bmp);
        return v;
    }

    /** Long-press → Change icon on a folder. */
    private void changeFolderIcon(HomeLayout.Folder folder) {
        String[] choices = {getString(R.string.folder_icon_contents), getString(R.string.folder_icon_app),
                getString(R.string.folder_icon_text), getString(R.string.folder_icon_image)};
        int checked = folder.icon == null ? 0 : folder.icon.startsWith("app:") ? 1
                : folder.icon.startsWith("text:") ? 2 : 3;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.change_icon_for, folders.nameOf(folder)))
                .setSingleChoiceItems(choices, checked, (d, which) -> {
                    d.dismiss();
                    switch (which) {
                        case 0: setFolderIcon(folder, null); break;
                        case 1: pickFolderIconApp(folder); break;
                        case 2: pickFolderIconText(folder); break;
                        default: pickFolderIconImage(folder); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setFolderIcon(HomeLayout.Folder folder, String icon) {
        layout.setFolderIcon(folder, icon);
        refreshHome();
    }

    // The folder's own apps first, then everything else.
    private void pickFolderIconApp(HomeLayout.Folder folder) {
        List<AppEntry> apps = new java.util.ArrayList<>();
        for (String k : folder.apps) {
            AppEntry e = repo.find(k);
            if (e != null) apps.add(e);
        }
        for (AppEntry e : repo.apps()) if (!apps.contains(e)) apps.add(e);
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = adapter.labelOf(apps.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.folder_icon_app)
                .setItems(names, (d, i) -> setFolderIcon(folder, "app:" + apps.get(i).key))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickFolderIconText(HomeLayout.Folder folder) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.folder_icon_text_hint);
        String current = folder.icon != null && folder.icon.startsWith("text:") ? folder.icon.substring(5) : "";
        input.setText(current);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(dp(20));
        box.setPadding(pad, Math.round(dp(8)), pad, 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.folder_icon_text)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String t = input.getText().toString().trim();
                    if (t.isEmpty()) return;
                    // Up to two characters (an emoji with modifiers counts as one).
                    java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
                    it.setText(t);
                    int end = it.next(), second = it.next();
                    if (second != java.text.BreakIterator.DONE) end = second;
                    setFolderIcon(folder, "text:" + t.substring(0, end));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
    }

    // Android's own file picker: no storage permission needed.
    private void pickFolderIconImage(HomeLayout.Folder folder) {
        pendingFolderImage = folder.id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_FOLDER_IMAGE);
        } catch (ActivityNotFoundException e) {
            pendingFolderImage = null;
        }
    }

    // Shrinks the picture to icon size and keeps it inside the layout file, so it
    // goes into backups too.
    private void applyFolderImage(android.net.Uri uri) {
        String id = pendingFolderImage;
        pendingFolderImage = null;
        HomeLayout.Folder folder = null;
        for (HomeLayout.Item it : layout.items()) {
            HomeLayout.Folder f = layout.folder(it);
            if (f != null && f.id.equals(id)) folder = f;
        }
        if (folder == null) return;
        try {
            android.graphics.Bitmap src = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, s) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float scale = 256f / Math.min(w, h);
                        if (scale < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            android.graphics.Bitmap square = repo.theme().image(src); // also crops to a square
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            square.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            setFolderIcon(folder, "image:" + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, R.string.folder_icon_image_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- drag and drop ---------- */

    // Long-press: a buzz, then either a drag (if the finger moves within a moment)
    // or the menu (if it stays still or lets go). Locked: menu only, nothing moves.
    private void onItemLongPress(HomeLayout.Item item, View view) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (HomeLayout.isWidgetKey(item.key)) {
            // Widgets: picked up straight away, no menu. Move to drag it (to the
            // trash at the top to remove); let go without moving to resize it.
            if (prefs.lockLayout()) {
                showWidgetMenu(item, view);
                return;
            }
            DragState st = new DragState(item, item.key, null, view, false);
            st.onRelease = () -> widgetReleased(item, view);
            armDrag(st, null);
            return;
        }
        if (prefs.lockLayout()) {
            showItemMenu(item, view); // locked: menu only, nothing moves
            return;
        }
        armDrag(new DragState(item, item.key, repo.find(item.key), view, false), () -> showItemMenu(item, view));
    }

    /** Waits to see whether a long-press turns into a drag (see trackPendingDrag). */
    /**
     * After a long-press: moving the finger soon makes it a drag, with no menu.
     * Staying still a moment longer, or letting go, shows the menu (if any).
     */
    private void armDrag(DragState st, java.util.function.Supplier<PopupMenu> menu) {
        // Stop a scrolling parent (the drawer list) taking the gesture meanwhile.
        if (st.view.getParent() != null) st.view.getParent().requestDisallowInterceptTouchEvent(true);
        st.startX = lastTouchX;
        st.startY = lastTouchY;
        pendingDrag = st;
        pendingMenu = null;
        cancelMenuShow();
        if (menu != null) {
            pendingMenuShow = () -> {
                pendingMenuShow = null;
                if (pendingDrag == st) pendingMenu = menu.get();
            };
            handler.postDelayed(pendingMenuShow, MENU_DELAY_MS);
        }
    }

    private void cancelMenuShow() {
        if (pendingMenuShow != null) handler.removeCallbacks(pendingMenuShow);
        pendingMenuShow = null;
    }

    private void startDrag(DragState st) {
        st.startX = lastTouchX;
        st.startY = lastTouchY;
        CellLayout from = st.item != null && HomeLayout.DOCK.equals(st.item.container) ? dock : grid;
        if (st.item != null && isBig(st.item) && from.cellWidth() > 0) {
            int[] loc = new int[2];
            st.view.getLocationOnScreen(loc);
            st.grabX = Math.max(0, Math.min(st.item.spanX - 1, (int) ((st.startX - loc[0]) / from.cellWidth())));
            st.grabY = Math.max(0, Math.min(st.item.spanY - 1, (int) ((st.startY - loc[1]) / from.cellHeight())));
        }
        int[] at = new int[2];
        st.view.getLocationOnScreen(at);
        st.offX = Math.max(0, Math.min(st.view.getWidth(), st.startX - at[0]));
        st.offY = Math.max(0, Math.min(st.view.getHeight(), st.startY - at[1]));
        ClipData data = ClipData.newPlainText("hearth", st.key);
        if (!st.view.startDragAndDrop(data, new LiftShadow(st.view, st.offX, st.offY), st, 0)) return;
        if (st.folder != null) folders.grid().setLayoutTransition(reorderTransition());
        if (!st.fromDrawer) st.view.setVisibility(View.INVISIBLE);
        dropRemove.setVisibility(st.item != null || st.folder != null ? View.VISIBLE : View.GONE);
        // A widget can only be moved or removed, so its trash can is a big, easy target.
        boolean widget = st.item != null && HomeLayout.isWidgetKey(st.item.key);
        dropRemoveScale = widget ? 1.8f : 1f;
        dropRemove.setScaleX(dropRemoveScale);
        dropRemove.setScaleY(dropRemoveScale);
        ViewGroup.LayoutParams barLp = dropBar.getLayoutParams();
        barLp.height = Math.round(dp(widget ? 120 : 72));
        dropBar.setLayoutParams(barLp);
        dropInfo.setVisibility(st.app != null ? View.VISIBLE : View.GONE);
        dropUninstall.setVisibility(st.app != null && repo.canUninstall(st.app) ? View.VISIBLE : View.GONE);
        dropBar.setVisibility(View.VISIBLE);
    }

    /** What a drop at (x, y) would do: {mode, cellX, cellY, page}. */
    private int[] dropPlan(CellLayout target, String container, DragState st, float x, float y) {
        int[] plan = rawDropPlan(target, container, st, x, y);
        // Dragged out of a folder and back onto that same folder: nothing to do.
        if (st.folder != null && plan[0] == CellLayout.HL_FOLDER) {
            HomeLayout.Item other = layout.at(container, plan[3], plan[1], plan[2]);
            if (other != null && layout.folder(other) == st.folder) plan[0] = CellLayout.HL_NONE;
        }
        return plan;
    }

    private int[] rawDropPlan(CellLayout target, String container, DragState st, float x, float y) {
        int page = HomeLayout.HOME.equals(container) ? currentPage : currentDockPage;
        int[] cell = target.cellAt(x, y);
        if (st.item != null && isBig(st.item)) {
            // A widget: its top-left follows the finger; it only goes where it fits in free cells.
            // In the dock (one-row widgets only) it's measured in dock slots.
            int[] sp = layout.spanIn(st.item, container);
            if (sp == null) return new int[] {CellLayout.HL_NONE, cell[0], cell[1], page};
            boolean toDock = HomeLayout.DOCK.equals(container);
            int cols = toDock ? layout.dockSlots() : layout.columns(), rws = toDock ? 1 : layout.rows();
            int gx = Math.min(st.grabX, sp[0] - 1), gy = Math.min(st.grabY, sp[1] - 1);
            int tx = Math.max(0, Math.min(cols - sp[0], cell[0] - gx));
            int ty = Math.max(0, Math.min(rws - sp[1], cell[1] - gy));
            boolean ok = layout.fitsArea(container, page, tx, ty, sp[0], sp[1])
                    && layout.areaFree(container, page, tx, ty, sp[0], sp[1], st.item);
            return new int[] {ok ? CellLayout.HL_MOVE : CellLayout.HL_NONE, tx, ty, page};
        }
        int span = layout.iconSpan(container);
        if (span > 1) {
            // Subgrid: the icon is centred on the finger, snapped to half cells. Dropped on the
            // middle of another icon it makes a folder; otherwise it needs free space.
            HomeLayout.Item under = layout.at(container, page, cell[0], cell[1]);
            if (under != null && under != st.item && !isBig(under) && !HomeLayout.isFolderKey(st.key)
                    && nearItemCentre(target, under, x, y)) {
                return new int[] {CellLayout.HL_FOLDER, under.x, under.y, page};
            }
            int cw = Math.max(1, target.cellWidth()), ch = Math.max(1, target.cellHeight());
            int tx = Math.max(0, Math.min(layout.columns() - span, Math.round((x - target.getPaddingLeft()) / cw - span / 2f)));
            int ty = Math.max(0, Math.min(layout.rows() - span, Math.round((y - target.getPaddingTop()) / ch - span / 2f)));
            boolean ok = layout.fitsArea(container, page, tx, ty, span, span)
                    && layout.areaFree(container, page, tx, ty, span, span, st.item);
            return new int[] {ok ? CellLayout.HL_MOVE : CellLayout.HL_NONE, tx, ty, page};
        }
        HomeLayout.Item other = layout.at(container, page, cell[0], cell[1]);
        int mode;
        if (other != null && other != st.item && isBig(other)) mode = CellLayout.HL_NONE; // over a widget
        else if (other == null || other == st.item) mode = CellLayout.HL_MOVE;
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
                boolean big = st.item != null && isBig(st.item);
                int span = layout.iconSpan(container);
                int[] sp = big ? layout.spanIn(st.item, container) : null;
                if (sp == null) sp = new int[] {span, span};
                target.setHighlight(plan[1], plan[2], sp[0], sp[1], plan[0]);
                if (plan[0] == CellLayout.HL_INSERT) previewInsert(target, container, st, plan[3], plan[1], plan[2]);
                else clearPreview();
                return true;
            }
            case DragEvent.ACTION_DRAG_EXITED:
                clearPreview();
                target.clearHighlight();
                if (target == grid) cancelEdgeFlip();
                return true;
            case DragEvent.ACTION_DROP: {
                cancelEdgeFlip();
                target.clearHighlight();
                int[] loc = new int[2];
                target.getLocationOnScreen(loc);
                st.dropX = loc[0] + e.getX();
                st.dropY = loc[1] + e.getY();
                int[] plan = dropPlan(target, container, st, e.getX(), e.getY());
                int x = plan[1], y = plan[2], page = plan[3];
                HomeLayout.Item other = layout.at(container, page, x, y);
                if (plan[0] == CellLayout.HL_NONE) {
                    // nowhere to put it here: it goes back where it was
                } else if (st.item != null && isBig(st.item)) {
                    layout.move(st.item, container, page, x, y);
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
                // Out of a folder: once it has landed somewhere, take it out of the folder.
                if (st.folder != null && layout.containsElsewhere(st.folder, st.key)) {
                    layout.removeFromFolder(st.folder, st.key);
                }
                return true;
            }
            default:
                return true;
        }
    }

    private boolean onTargetDrag(ImageView target, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState st = (DragState) e.getLocalState();
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED: hoverTarget(target, true); return true;
            case DragEvent.ACTION_DRAG_EXITED: hoverTarget(target, false); return true;
            case DragEvent.ACTION_DROP:
                if (target == dropRemove && st.item != null) removeItem(st.item);
                else if (target == dropRemove && st.folder != null) layout.removeFromFolder(st.folder, st.key);
                else if (target == dropInfo && st.app != null) repo.openAppInfo(st.app, st.view);
                else if (target == dropUninstall && st.app != null) repo.uninstall(this, st.app);
                return true;
            default:
                return true;
        }
    }

    // Just an icon at the top; held over, it grows and takes the accent colour
    // (red for Uninstall).
    private void hoverTarget(ImageView target, boolean over) {
        int tint = !over ? 0xFFE6F0FF : target == dropUninstall ? 0xFFEF4444 : prefs.accent();
        target.setImageTintList(ColorStateList.valueOf(tint));
        float base = target == dropRemove ? dropRemoveScale : 1f;
        target.animate().scaleX(base * (over ? 1.3f : 1f)).scaleY(base * (over ? 1.3f : 1f)).setDuration(120).start();
        if (over) target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
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
        for (ImageView t : new ImageView[] {dropRemove, dropInfo, dropUninstall}) hoverTarget(t, false);
        // Still inside the open folder (even if let go over its title): keep the new order.
        if (st.folder != null && folders.isOpen() && folders.current() == st.folder) {
            GridLayout g = folders.grid();
            List<String> order = new java.util.ArrayList<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                Object tag = g.getChildAt(i).getTag();
                if (tag instanceof String) order.add((String) tag);
            }
            layout.setFolderOrder(st.folder, order);
        }
        folders.grid().setLayoutTransition(null);
        Map<String, float[]> before = snapshotPositions();
        previewTarget = null; // its views are about to be rebuilt where the preview showed them
        previewX = previewY = previewPage = -1;
        refreshHome();
        animateFrom(before, st.dropX >= 0 ? st.key : null, st.dropX - st.offX, st.dropY - st.offY);
        // A widget put down on the grid (moved or not) goes into resize mode, like
        // other launchers. Not if it was thrown in the trash.
        if (st.item != null && HomeLayout.isWidgetKey(st.item.key) && st.dropX >= 0
                && layout.items().contains(st.item)) {
            HomeLayout.Item w = st.item;
            afterLayout(grid, () -> {
                View wv = findItemView(w);
                if (wv != null) widgetReleased(w, wv);
            });
        }
        folders.refresh(); // shows the new order, or closes a folder that dissolved
        syncPins();
        return true;
    }

    // Inside an open folder: the other entries shift aside live as you drag; the
    // order is saved on drop.
    private boolean onFolderGridDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState st = (DragState) e.getLocalState();
        if (st.folder == null) return false;
        GridLayout g = folders.grid();
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION: {
                int to = -1;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (e.getX() >= c.getLeft() && e.getX() < c.getRight()
                            && e.getY() >= c.getTop() && e.getY() < c.getBottom()) { to = i; break; }
                }
                int from = g.indexOfChild(st.view);
                if (to >= 0 && from >= 0 && to != from) {
                    g.removeView(st.view);
                    g.addView(st.view, to);
                }
                return true;
            }
            case DragEvent.ACTION_DROP: {
                List<String> order = new java.util.ArrayList<>();
                for (int i = 0; i < g.getChildCount(); i++) {
                    Object tag = g.getChildAt(i).getTag();
                    if (tag instanceof String) order.add((String) tag);
                }
                layout.setFolderOrder(st.folder, order);
                return true;
            }
            default:
                return true;
        }
    }

    // Reached only outside the folder card (the card's grid takes events over it):
    // the folder closes so the app can be dropped on the home screen.
    private boolean onFolderOverlayDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof DragState)) return false;
        DragState st = (DragState) e.getLocalState();
        if (st.folder == null) return false;
        if (e.getAction() == DragEvent.ACTION_DRAG_LOCATION && folders.isOpen()) {
            View card = folders.card();
            boolean inside = e.getX() >= card.getX() && e.getX() < card.getX() + card.getWidth()
                    && e.getY() >= card.getY() && e.getY() < card.getY() + card.getHeight();
            if (!inside) folders.closeForDrag();
        }
        return e.getAction() != DragEvent.ACTION_DROP;
    }

    /** The view currently showing an item on the home screen or dock, or null. */
    private View findItemView(HomeLayout.Item item) {
        for (CellLayout cl : new CellLayout[] {grid, dock}) {
            for (int i = 0; i < cl.getChildCount(); i++) {
                View c = cl.getChildAt(i);
                if (c.getTag(R.id.home_item) == item) return c;
            }
        }
        return null;
    }

    /* ---------- drag animation ---------- */

    /** The picked-up icon, a little larger, held where your finger is on it. */
    private static final class LiftShadow extends View.DragShadowBuilder {
        private static final float SCALE = 1.1f;
        private final float offX, offY;
        LiftShadow(View v, float offX, float offY) {
            super(v);
            this.offX = offX;
            this.offY = offY;
        }
        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            View v = getView();
            if (v == null || v.getWidth() == 0) {
                super.onProvideShadowMetrics(size, touch);
                return;
            }
            size.set(Math.round(v.getWidth() * SCALE), Math.round(v.getHeight() * SCALE));
            touch.set(Math.round(offX * SCALE), Math.round(offY * SCALE));
        }
        @Override
        public void onDrawShadow(Canvas c) {
            View v = getView();
            if (v == null) return;
            c.scale(SCALE, SCALE);
            v.draw(c);
        }
    }

    // While hovering to insert, the icons that would move slide to where they'd go.
    private void previewInsert(CellLayout target, String container, DragState st, int page, int x, int y) {
        if (target == previewTarget && x == previewX && y == previewY && page == previewPage) return;
        clearPreview();
        Map<HomeLayout.Item, int[]> moves = layout.insertPlan(st.item, container, page, x, y);
        if (moves == null) return;
        previewTarget = target;
        previewX = x;
        previewY = y;
        previewPage = page;
        for (int i = 0; i < target.getChildCount(); i++) {
            View c = target.getChildAt(i);
            Object tag = c.getTag(R.id.home_item);
            int[] to = tag == null ? null : moves.get(tag);
            if (to == null) continue;
            HomeLayout.Item it = (HomeLayout.Item) tag;
            c.animate().translationX((to[0] - it.x) * target.cellWidth())
                    .translationY((to[1] - it.y) * target.cellHeight())
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void clearPreview() {
        if (previewTarget == null) return;
        for (int i = 0; i < previewTarget.getChildCount(); i++) {
            View c = previewTarget.getChildAt(i);
            if (c.getTranslationX() != 0 || c.getTranslationY() != 0) {
                c.animate().translationX(0).translationY(0).setDuration(170)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        previewTarget = null;
        previewX = previewY = previewPage = -1;
    }

    /** Where each home and dock item is on screen right now (mid-animation included). */
    private Map<String, float[]> snapshotPositions() {
        Map<String, float[]> m = new HashMap<>();
        int[] loc = new int[2];
        for (CellLayout cl : new CellLayout[] {grid, dock}) {
            for (int i = 0; i < cl.getChildCount(); i++) {
                View c = cl.getChildAt(i);
                Object tag = c.getTag(R.id.home_item);
                if (!(tag instanceof HomeLayout.Item)) continue;
                c.getLocationOnScreen(loc);
                m.put(((HomeLayout.Item) tag).key, new float[] {loc[0], loc[1]});
            }
        }
        return m;
    }

    // After the icons are rebuilt in their new places, each glides there from where
    // it was; the dropped one from where you let go. No jumps.
    private void animateFrom(Map<String, float[]> before, String droppedKey, float dropLeft, float dropTop) {
        afterLayout(grid, () -> {
            int[] loc = new int[2];
            for (CellLayout cl : new CellLayout[] {grid, dock}) {
                for (int i = 0; i < cl.getChildCount(); i++) {
                    View c = cl.getChildAt(i);
                    Object tag = c.getTag(R.id.home_item);
                    if (!(tag instanceof HomeLayout.Item)) continue;
                    String key = ((HomeLayout.Item) tag).key;
                    float[] old = key.equals(droppedKey) ? new float[] {dropLeft, dropTop} : before.get(key);
                    if (old == null) continue;
                    c.animate().cancel();
                    c.setTranslationX(0);
                    c.setTranslationY(0);
                    c.getLocationOnScreen(loc);
                    float dx = old[0] - loc[0], dy = old[1] - loc[1];
                    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;
                    c.setTranslationX(dx);
                    c.setTranslationY(dy);
                    c.animate().translationX(0).translationY(0).setDuration(230)
                            .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                }
            }
        });
    }

    /** Folder entries slide into their new places as you drag one around. */
    private static LayoutTransition reorderTransition() {
        LayoutTransition t = new LayoutTransition();
        t.setAnimator(LayoutTransition.APPEARING, null);
        t.setAnimator(LayoutTransition.DISAPPEARING, null);
        t.setDuration(160);
        t.setStartDelay(LayoutTransition.CHANGE_APPEARING, 0);
        t.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        return t;
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
        if (HomeLayout.isWidgetKey(item.key)) return showWidgetMenu(item, anchor);
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
            menu.getMenu().add(0, 5, 1, R.string.change_icon);
            menu.getMenu().add(0, 3, 2, R.string.ungroup);
            menu.getMenu().add(0, 4, 3, HomeLayout.DOCK.equals(item.container)
                    ? R.string.remove_from_dock : R.string.remove_from_home);
        }
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: folders.show(folder, anchor); return true;
                case 5: changeFolderIcon(folder); return true;
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
        if (!locked) m.add(0, 5, 3, R.string.widgets);
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
                case 5: pickWidget(); return true;
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

    /* ---------- widgets ---------- */

    /** Widgets drag differently from icons: they keep their size and only drop into free space. */
    private static boolean isBig(HomeLayout.Item it) {
        return HomeLayout.isWidgetKey(it.key);
    }

    /** Whether a point is in the middle of an item's footprint (a folder drop rather than a move). */
    private static boolean nearItemCentre(CellLayout t, HomeLayout.Item it, float x, float y) {
        if (t.cellWidth() == 0 || t.cellHeight() == 0) return false;
        float left = t.getPaddingLeft() + it.x * t.cellWidth(), top = t.getPaddingTop() + it.y * t.cellHeight();
        float fx = (x - left) / (it.spanX * t.cellWidth()), fy = (y - top) / (it.spanY * t.cellHeight());
        return fx > 0.22f && fx < 0.78f && fy > 0.12f && fy < 0.88f;
    }

    /** Removes anything from the home screen; a widget also gives up its widget id. */
    private void removeItem(HomeLayout.Item item) {
        if (HomeLayout.isWidgetKey(item.key)) {
            int id = HomeLayout.widgetId(item);
            widgetViews.remove(id);
            if (id >= 0) widgetHost.deleteAppWidgetId(id);
        }
        layout.remove(item);
    }

    // Widget ids the system still holds for Hearth but the layout no longer uses
    // (removed while Hearth wasn't running, or left by an interrupted add).
    private void deleteOrphanWidgets() {
        int[] ids = widgetHost.getAppWidgetIds();
        if (ids == null) return;
        for (int id : ids) {
            if (id == pendingWidgetId) continue;
            if (!layout.contains(HomeLayout.WIDGET_PREFIX + id) && !prefs.sidebarWidgetIds().contains(id)) {
                widgetHost.deleteAppWidgetId(id);
            }
        }
    }

    /** Cells a widget wants, from its own declared size. */
    private int spanX(AppWidgetProviderInfo info) {
        if (info.targetCellWidth > 0) return Math.min(layout.columns(), info.targetCellWidth * layout.sub());
        return cellsFor(info.minWidth, grid.cellWidth(), layout.columns());
    }

    private int spanY(AppWidgetProviderInfo info) {
        if (info.targetCellHeight > 0) return Math.min(layout.rows(), info.targetCellHeight * layout.sub());
        return cellsFor(info.minHeight, grid.cellHeight(), layout.rows());
    }

    private int cellsFor(int px, int cell, int max) {
        if (cell <= 0) cell = Math.round(dp(80));
        return Math.max(1, Math.min(max, (int) Math.ceil(px / (double) cell)));
    }

    private void pickWidget() {
        pendingWidgetSidebar = null;
        new WidgetPicker(this, this::spanX, this::spanY).show(this::addWidget);
    }

    // Allocate an id, ask permission to bind if needed, run the widget's setup if
    // it has one, then place it.
    private void addWidget(AppWidgetProviderInfo info) {
        int id = widgetHost.allocateAppWidgetId();
        pendingWidgetId = id;
        pendingWidgetPage = currentPage;
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null)) {
            configureOrPlace(id);
            return;
        }
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            startActivityForResult(bind, REQ_WIDGET_BIND);
        } catch (ActivityNotFoundException e) {
            abandonWidget();
        }
    }

    private void configureOrPlace(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info == null) {
            abandonWidget();
            return;
        }
        boolean optional = (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (info.configure != null && !optional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                abandonWidget();
                return;
            }
        }
        placeWidget(id);
    }

    private void placeWidget(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        pendingWidgetId = -1;
        if (info == null) {
            widgetHost.deleteAppWidgetId(id);
            pendingWidgetSidebar = null;
            return;
        }
        if (pendingWidgetSidebar != null) {
            // Into a sidebar panel: a card at the widget's preferred height, then show it.
            String side = pendingWidgetSidebar;
            pendingWidgetSidebar = null;
            float d = getResources().getDisplayMetrics().density;
            int h = info.targetCellHeight > 0 && grid.cellHeight() > 0
                    ? info.targetCellHeight * grid.cellHeight() / layout.sub() : info.minHeight;
            int dpH = Math.max(96, Math.min(480, Math.round(h / d) + 16));
            List<Prefs.Card> cards = prefs.sidebarCards(side);
            cards.add(new Prefs.Card(Prefs.Card.WIDGET, id, dpH));
            prefs.setSidebarCards(side, cards);
            sidebar.open(Prefs.SIDEBAR_RIGHT.equals(side) ? Sidebar.RIGHT : Sidebar.LEFT);
            sidebar.refresh();
            return;
        }
        HomeLayout.Item it = layout.addWidget(id, pendingWidgetPage, spanX(info), spanY(info));
        if (it == null) {
            widgetHost.deleteAppWidgetId(id);
            Toast.makeText(this, R.string.widget_no_room, Toast.LENGTH_LONG).show();
            return;
        }
        if (it.page != currentPage) animateToPage(it.page);
        else refreshHome();
    }

    private void abandonWidget() {
        if (pendingWidgetId >= 0) widgetHost.deleteAppWidgetId(pendingWidgetId);
        pendingWidgetId = -1;
        pendingWidgetSidebar = null;
        Toast.makeText(this, R.string.widget_failed, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WIDGET_RECONFIGURE) return; // the widget updates itself
        if (requestCode == REQ_FOLDER_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) applyFolderImage(data.getData());
            else pendingFolderImage = null;
            return;
        }
        if (requestCode != REQ_WIDGET_BIND && requestCode != REQ_WIDGET_CONFIGURE) return;
        int id = pendingWidgetId;
        if (id < 0) return;
        if (resultCode != RESULT_OK) {
            widgetHost.deleteAppWidgetId(id); // cancelled: let the id go quietly
            pendingWidgetId = -1;
            pendingWidgetSidebar = null;
            return;
        }
        if (requestCode == REQ_WIDGET_BIND) configureOrPlace(id);
        else placeWidget(id);
    }

    /** The view for a widget item: reused if we have it, a placeholder if the widget's gone. */
    private View widgetView(HomeLayout.Item item) {
        return widgetViewFor(HomeLayout.widgetId(item));
    }

    /** A widget's view by id (home or sidebar), reused if we have it; a placeholder if it's gone. */
    private View widgetViewFor(int id) {
        AppWidgetProviderInfo info = id < 0 ? null : widgetManager.getAppWidgetInfo(id);
        if (info == null) {
            // Restored from a backup on another install, or its app was uninstalled.
            TextView t = new TextView(this);
            t.setText(R.string.widget_unavailable);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Accent.withAlpha(Prefs.textColor(), 0xCC));
            t.setPadding(Math.round(dp(8)), 0, Math.round(dp(8)), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Prefs.background(50));
            bg.setCornerRadius(dp(16));
            t.setBackground(bg);
            return t;
        }
        WidgetHost.View v = widgetViews.get(id);
        if (v == null) {
            v = (WidgetHost.View) widgetHost.createView(getApplicationContext(), id, info);
            WidgetHost.View view = v;
            // Tell the widget its real size whenever it changes, so it can pick a layout.
            v.addOnLayoutChangeListener((x, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l == or - ol && b - t == ob - ot) return;
                float d = getResources().getDisplayMetrics().density;
                android.util.SizeF size = new android.util.SizeF((r - l) / d, (b - t) / d);
                view.updateAppWidgetSize(new Bundle(), java.util.Collections.singletonList(size));
            });
            widgetViews.put(id, v);
        }
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        v.setVisibility(View.VISIBLE);
        return v;
    }

    private PopupMenu showWidgetMenu(HomeLayout.Item item, View anchor) {
        boolean locked = prefs.lockLayout();
        int id = HomeLayout.widgetId(item);
        AppWidgetProviderInfo info = id < 0 ? null : widgetManager.getAppWidgetInfo(id);
        boolean reconfigurable = reconfigurable(info);
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        if (!locked && info != null && info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) m.add(0, 1, 0, R.string.widget_resize);
        if (reconfigurable) m.add(0, 2, 1, R.string.widget_settings);
        if (!locked) m.add(0, 3, 2, R.string.remove_from_home);
        if (m.size() == 0) return null;
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: resizeWidget(item, info); return true;
                case 2:
                    try {
                        widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_RECONFIGURE, null);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(this, R.string.widget_failed, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 3: removeItem(item); refreshHome(); return true;
                default: return false;
            }
        });
        menu.show();
        return menu;
    }

    // A widget long-pressed and let go without moving: resize it if it can be
    // resized, otherwise offer what there is (its settings, remove).
    private void widgetReleased(HomeLayout.Item item, View view) {
        int id = HomeLayout.widgetId(item);
        AppWidgetProviderInfo info = id < 0 ? null : widgetManager.getAppWidgetInfo(id);
        if (info != null && info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) resizeWidget(item, info);
        else showWidgetMenu(item, view);
    }

    private static boolean reconfigurable(AppWidgetProviderInfo info) {
        return info != null && info.configure != null
                && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0;
    }

    /**
     * Resize mode: a frame with handles around the widget; drag them, and it snaps
     * to the grid (half cells on the subgrid). Tap outside, Back or Home to finish.
     */
    private void resizeWidget(HomeLayout.Item item, AppWidgetProviderInfo info) {
        endResize();
        // A dock widget resizes along the dock (one row, in dock slots).
        boolean inDock = HomeLayout.DOCK.equals(item.container);
        CellLayout cl = inDock ? dock : grid;
        int cols = inDock ? layout.dockSlots() : layout.columns(), rws = inDock ? 1 : layout.rows();
        boolean horiz = (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
        boolean vert = !inDock && (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
        int minW = horiz ? cellsFor(Math.max(1, info.minResizeWidth), cl.cellWidth(), cols) : item.spanX;
        int minH = vert ? cellsFor(Math.max(1, info.minResizeHeight), cl.cellHeight(), rws) : item.spanY;
        int maxW = horiz ? (info.maxResizeWidth > 0
                ? cellsFor(info.maxResizeWidth, cl.cellWidth(), cols) : cols) : item.spanX;
        int maxH = vert ? (info.maxResizeHeight > 0
                ? cellsFor(info.maxResizeHeight, cl.cellHeight(), rws) : rws) : item.spanY;
        resizing = item;
        resizeFrame = new ResizeFrame(this, new ResizeFrame.Host() {
            @Override public boolean canPlace(int x, int y, int w, int h) {
                return layout.canResize(item, x, y, w, h);
            }
            @Override public boolean commit(int x, int y, int w, int h) {
                if (!layout.resizeItemTo(item, x, y, w, h)) return false;
                refreshHome();
                return true;
            }
            @Override public void preview(int x, int y, int w, int h, boolean ok) {
                if (x < 0) cl.clearHighlight();
                else cl.setHighlight(x, y, w, h, ok ? CellLayout.HL_MOVE : CellLayout.HL_NONE);
            }
            @Override public void done() {
                endResize();
            }
            @Override public void pickUp() {
                // Drag the widget out of resize mode; putting it down brings resize back.
                View v = findItemView(item);
                if (v == null) return;
                DragState st = new DragState(item, item.key, null, v, false);
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                startDrag(st);
                handler.post(HomeActivity.this::endResize); // not while the frame is handling this touch
            }
            @Override public void settings() {
                endResize();
                try {
                    widgetHost.startAppWidgetConfigureActivityForResult(HomeActivity.this,
                            HomeLayout.widgetId(item), 0, REQ_WIDGET_RECONFIGURE, null);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(HomeActivity.this, R.string.widget_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
        if (reconfigurable(info)) resizeFrame.setSettingsLabel(getString(R.string.widget_settings));
        resizeFrame.setAccent(prefs.accent());
        int[] g = new int[2], r = new int[2];
        View root = findViewById(R.id.root);
        cl.getLocationInWindow(g);
        root.getLocationInWindow(r);
        resizeFrame.setGrid(g[0] - r[0] + cl.getPaddingLeft(), g[1] - r[1] + cl.getPaddingTop(),
                cl.cellWidth(), cl.cellHeight(), cols, rws);
        resizeFrame.setWidget(item.x, item.y, item.spanX, item.spanY, minW, minH, maxW, maxH, horiz, vert);
        ((ViewGroup) root).addView(resizeFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, endResizeOnBack);
        Toast.makeText(this, R.string.widget_resize_hint, Toast.LENGTH_SHORT).show();
    }

    private final OnBackInvokedCallback endResizeOnBack = this::endResize;

    private void endResize() {
        if (resizeFrame == null) return;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(endResizeOnBack);
        ((ViewGroup) resizeFrame.getParent()).removeView(resizeFrame);
        resizeFrame = null;
        resizing = null;
        grid.clearHighlight();
        dock.clearHighlight();
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
        adapter.applyPrefs(drawerIconPx, prefs.drawerColumns()); // most-used order and "new" dots move on
        refreshAlpha();
        drawerList.scrollToPosition(0);
        search.setVisibility(prefs.searchOnPull() ? View.GONE : View.VISIBLE);
        applySystemBars();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeDrawerOnBack);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        applySystemBars();
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

    /** Slides the drawer's search bar in and puts the keyboard up. */
    private void revealSearch() {
        search.setVisibility(View.VISIBLE);
        search.setAlpha(0f);
        search.setTranslationY(-dp(24));
        search.animate().alpha(1f).translationY(0).setDuration(160).withEndAction(null).start();
        search.requestFocus();
        search.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
    }

    // Instantly, mid-drag: the home screen must be visible to receive the drop.
    private void hideDrawerForDrag() {
        drawerOpen = false;
        applySystemBars();
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
        if (prefs.lockLayout()) {
            showAppMenu(app, view, null);
            return;
        }
        armDrag(new DragState(null, app.key, app, view, true), () -> showAppMenu(app, view, null));
    }

    // The rest of the long-press gesture still comes to this window, even with the menu up.
    private void trackPendingDrag(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(ev.getRawX() - pendingDrag.startX, ev.getRawY() - pendingDrag.startY) < dp(12)) return;
                DragState st = pendingDrag;
                pendingDrag = null;
                cancelMenuShow(); // it's a drag: no menu
                if (pendingMenu != null) pendingMenu.dismiss();
                pendingMenu = null;
                st.movedFar = true;
                startDrag(st);        // captures the icon for the drag shadow first...
                if (st.fromDrawer) hideDrawerForDrag(); // ...then reveals the home screen to drop on
                return;
            case MotionEvent.ACTION_UP: {
                Runnable release = pendingDrag.onRelease;
                Runnable menu = pendingMenuShow;
                cancelMenuShow();
                if (menu != null) menu.run(); // let go before the menu appeared: show it now
                pendingDrag = null;   // let go without moving: the menu stays
                pendingMenu = null;
                if (release != null) handler.post(release);
                return;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelMenuShow();
                pendingDrag = null;
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
