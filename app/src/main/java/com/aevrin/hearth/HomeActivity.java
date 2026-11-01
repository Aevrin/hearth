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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.view.Gravity;
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

/**
 * The home screen: a clock, pages of pinned apps and a dock, over the wallpaper.
 * Swipe up for the app drawer, sideways for other pages. Long-press and drag an
 * icon to move it, including between the grid and the dock, or to the screen's
 * edge to carry it to the next page; drop it on "Remove" to unpin it; long-press
 * without moving for its menu.
 */
public class HomeActivity extends Activity implements AppRepository.Listener, DrawerAdapter.Actions {

    private static final long EDGE_PAGE_DELAY_MS = 550;

    private AppRepository repo;
    private HomeLayout layout;
    private Prefs prefs;

    private View homeContent;
    private View clock;
    private TextClock clockTime;
    private TextClock clockDate;
    private CellLayout grid;
    private LinearLayout pageDots;
    private CellLayout dock;
    private ImageView handle;
    private TextView emptyHint;
    private TextView removeZone;
    private View drawer;
    private EditText search;
    private RecyclerView drawerList;
    private GridLayoutManager drawerGrid;
    private DrawerAdapter adapter;

    private GestureDetector gestures;
    private boolean drawerOpen;
    private int currentPage;
    private int iconPx;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingEdgeFlip;
    private int pendingEdgeDirection;

    private final OnBackInvokedCallback closeDrawerOnBack = this::closeDrawer;
    private final OnBackInvokedCallback closeFolderOnBack = this::closeFolder;

    private View folderOverlay;
    private View folderCard;
    private TextView folderTitle;
    private GridLayout folderGrid;
    private HomeLayout.Folder openFolder;
    private final Rect folderSource = new Rect(); // the folder icon's screen bounds
    private View popupAnchor;
    private float lastTouchX, lastTouchY;       // screen coordinates of the latest press

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
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);
        grid = findViewById(R.id.grid);
        pageDots = findViewById(R.id.page_dots);
        dock = findViewById(R.id.dock);
        handle = findViewById(R.id.drawer_handle);
        emptyHint = findViewById(R.id.empty_hint);
        removeZone = findViewById(R.id.remove_zone);
        drawer = findViewById(R.id.drawer);
        search = findViewById(R.id.search);
        drawerList = findViewById(R.id.drawer_list);
        folderOverlay = findViewById(R.id.folder_overlay);
        popupAnchor = findViewById(R.id.popup_anchor);
        folderCard = findViewById(R.id.folder_card);
        folderTitle = findViewById(R.id.folder_title);
        folderGrid = findViewById(R.id.folder_grid);
        folderOverlay.setOnClickListener(v -> closeFolder());
        folderTitle.setOnClickListener(v -> { if (openFolder != null) renameFolder(openFolder); });

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
        repo.setLabeler(adapter::labelOf); // letter icons use custom names
        drawerGrid = new GridLayoutManager(this, prefs.drawerColumns());
        drawerList.setLayoutManager(drawerGrid);
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

        // Home: swipe up for the drawer, sideways for pages. Drawer: swipe down at
        // the top of the list to close it.
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(vx) > Math.abs(vy)) {
                    if (drawerOpen || Math.abs(vx) < 600 || Math.abs(dx) < dp(60)) return false;
                    return flipPage(dx < 0 ? 1 : -1);
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

        grid.setOnLongClickListener(v -> { showHomeMenu(v); return true; });
        grid.setOnDragListener((v, e) -> onCellDrag(grid, HomeLayout.HOME, e));
        dock.setOnDragListener((v, e) -> onCellDrag(dock, HomeLayout.DOCK, e));
        removeZone.setOnDragListener(this::onRemoveDrag);

        reloadConfig();
        repo.reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadConfig(); // settings or a restored backup may have changed things
        maybePromptForHome();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        repo.destroy();
        super.onDestroy();
    }

    // Pressing Home while already home closes the drawer, then returns to page one.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFolder != null) {
            closeFolder();
        } else if (drawerOpen) {
            closeDrawer();
        } else if (currentPage != 0) {
            currentPage = 0;
            refreshHome();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.getRawX();
            lastTouchY = ev.getRawY();
        }
        if (openFolder == null) gestures.onTouchEvent(ev); // no page/drawer swipes over an open folder
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onAppsChanged() {
        adapter.setApps(repo.apps());
        refreshHome();
    }

    /* ---------- configuration ---------- */

    private void reloadConfig() {
        layout = HomeLayout.load(getFilesDir(), prefs.columns(), prefs.rows(), prefs.pages(), prefs.dockSlots());
        if (currentPage >= layout.pages()) currentPage = layout.pages() - 1;
        grid.setGrid(layout.columns(), layout.rows());
        dock.setGrid(layout.dockSlots(), 1);
        iconPx = Math.round(getResources().getDimension(R.dimen.icon_size) * prefs.iconScale());
        repo.setTheme(IconTheme.from(prefs)); // re-renders icons only if the theme changed
        drawerGrid.setSpanCount(prefs.drawerColumns());
        adapter.applyPrefs(iconPx);
        applyAppearance();
        refreshHome();
    }

    private void applyAppearance() {
        int accent = prefs.accent();
        drawer.setBackgroundColor(Prefs.background(prefs.drawerOpacity()));

        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(Prefs.background(prefs.dockOpacity()));
        dockBg.setCornerRadius(getResources().getDimension(R.dimen.dock_radius));
        dock.setBackground(dockBg);
        boolean showDock = prefs.showDock();
        dock.setVisibility(showDock ? View.VISIBLE : View.GONE);
        // The arrow only matters when there's no dock to swipe up from.
        handle.setVisibility(showDock ? View.GONE : View.VISIBLE);
        handle.setImageTintList(ColorStateList.valueOf(accent));

        clock.setVisibility(prefs.showClock() ? View.VISIBLE : View.GONE);
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

    /* ---------- home screen, pages and dock ---------- */

    private void refreshHome() {
        grid.removeAllViews();
        dock.removeAllViews();
        boolean homeLabels = prefs.homeLabels();
        for (HomeLayout.Item item : layout.items()) {
            if (!layout.fits(item.container, item.page, item.x, item.y)) continue; // waiting for room
            boolean inDock = HomeLayout.DOCK.equals(item.container);
            if (!inDock && item.page != currentPage) continue;
            HomeLayout.Folder folder = layout.folder(item);
            if (folder != null) {
                View fv = makeFolderIcon(folder, inDock ? dock : grid, !inDock && homeLabels);
                fv.setOnClickListener(view -> showFolder(folder, view));
                fv.setOnLongClickListener(view -> { startDrag(item, view); return true; });
                (inDock ? dock : grid).addView(fv, new CellLayout.LayoutParams(item.x, item.y));
                continue;
            }
            AppEntry app = repo.find(item.key);
            // Not found: still loading, or on a paused work profile. Keep the cell, skip the icon.
            if (app == null) continue;
            View v = makeIcon(app, inDock ? dock : grid, !inDock && homeLabels);
            v.setOnClickListener(view -> repo.launch(app, view));
            v.setOnLongClickListener(view -> { startDrag(item, view); return true; });
            (inDock ? dock : grid).addView(v, new CellLayout.LayoutParams(item.x, item.y));
        }
        emptyHint.setVisibility(layout.isEmpty() ? View.VISIBLE : View.GONE);
        refreshDots();
    }

    private void refreshDots() {
        pageDots.removeAllViews();
        int pages = layout.pages();
        pageDots.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        if (pages <= 1) return;
        int size = Math.round(dp(7));
        int gap = Math.round(dp(5));
        for (int i = 0; i < pages; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == currentPage ? prefs.accent() : 0x80E6F0FF);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            pageDots.addView(dot, lp);
        }
    }

    /** Slides to the next (+1) or previous (−1) page. Returns false at either end. */
    private boolean flipPage(int direction) {
        int target = currentPage + direction;
        if (target < 0 || target >= layout.pages()) return false;
        float w = grid.getWidth();
        grid.animate().cancel();
        grid.animate().translationX(-direction * w * 0.3f).alpha(0f).setDuration(110)
                .withEndAction(() -> {
                    currentPage = target;
                    refreshHome();
                    grid.setTranslationX(direction * w * 0.3f);
                    grid.animate().translationX(0).alpha(1f).setDuration(140).withEndAction(null).start();
                })
                .start();
        return true;
    }

    private View makeIcon(AppEntry app, CellLayout parent, boolean showLabel) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_icon, parent, false);
        ImageView icon = v.findViewById(R.id.icon);
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = iconPx;
        lp.height = iconPx;
        icon.setLayoutParams(lp);
        TextView label = v.findViewById(R.id.label);
        String name = adapter.labelOf(app);
        label.setText(name);
        label.setVisibility(showLabel ? View.VISIBLE : View.GONE);
        v.setContentDescription(name);
        repo.loadIcon(app, icon::setImageDrawable);
        return v;
    }

    /** Same layout as an app icon, with the folder's mini-icon plate in place of the icon. */
    private View makeFolderIcon(HomeLayout.Folder folder, CellLayout parent, boolean showLabel) {
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
            AppEntry app = repo.find(key);
            if (app == null) continue;
            int slot = shown++;
            repo.loadIcon(app, d -> fi.setMini(slot, d));
        }
        TextView label = v.findViewById(R.id.label);
        String name = folderName(folder);
        label.setText(name);
        label.setVisibility(showLabel ? View.VISIBLE : View.GONE);
        v.setContentDescription(getString(R.string.folder_description, name, folder.apps.size()));
        return v;
    }

    private String folderName(HomeLayout.Folder f) {
        return f.name == null || f.name.isEmpty() ? getString(R.string.folder_default_name) : f.name;
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
            case DragEvent.ACTION_DRAG_LOCATION:
                if (target == grid) checkEdge(e.getX());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                if (target == grid) cancelEdgeFlip();
                return true;
            case DragEvent.ACTION_DROP: {
                cancelEdgeFlip();
                int[] cell = target.cellAt(e.getX(), e.getY());
                int page = HomeLayout.HOME.equals(container) ? currentPage : 0;
                boolean samePlace = container.equals(state.item.container)
                        && cell[0] == state.item.x && cell[1] == state.item.y
                        && (HomeLayout.DOCK.equals(container) || state.item.page == page);
                HomeLayout.Item other = layout.at(container, page, cell[0], cell[1]);
                boolean draggingFolder = HomeLayout.isFolderKey(state.item.key);
                if (samePlace) {
                    state.droppedInPlace = true; // a long-press that didn't move: show the menu
                } else if (other == null || draggingFolder) {
                    layout.move(state.item, container, page, cell[0], cell[1]); // empty cell, or swap
                } else if (layout.folder(other) != null) {
                    layout.addToFolder(layout.folder(other), state.item); // onto a folder: join it
                } else {
                    layout.createFolder(other, state.item, ""); // app onto app: new folder
                }
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                if (target == grid) {
                    cancelEdgeFlip();
                    state.view.setVisibility(View.VISIBLE);
                    removeZone.setVisibility(View.GONE);
                    removeZone.setActivated(false);
                    refreshHome();
                    // The icons were just rebuilt; wait until they're laid out, or the
                    // menu anchors to a view still at (0,0) and opens at the top.
                    if (state.droppedInPlace) afterLayout(grid, () -> showIconMenu(state.item));
                }
                return true;
            default:
                return true;
        }
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
        if (layout.folder(item) != null) {
            showFolderMenu(item);
            return;
        }
        AppEntry app = repo.find(item.key);
        CellLayout parent = HomeLayout.DOCK.equals(item.container) ? dock : grid;
        View anchor = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) parent.getChildAt(i).getLayoutParams();
            if (lp.cellX == item.x && lp.cellY == item.y) anchor = parent.getChildAt(i);
        }
        if (app == null || anchor == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 5, 0, R.string.rename);
        menu.getMenu().add(0, 7, 0, R.string.change_icon);
        menu.getMenu().add(0, 1, 1, HomeLayout.DOCK.equals(item.container)
                ? R.string.remove_from_dock : R.string.remove_from_home);
        menu.getMenu().add(0, 2, 2, R.string.app_info);
        if (repo.canUninstall(app)) menu.getMenu().add(0, 3, 3, R.string.uninstall);
        View finalAnchor = anchor;
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 5: rename(app); return true;
                case 7: changeIcon(app); return true;
                case 1: layout.remove(item); refreshHome(); return true;
                case 2: repo.openAppInfo(app, finalAnchor); return true;
                case 3: repo.uninstall(this, app); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** Runs once, right after the view's next layout pass. */
    private static void afterLayout(View v, Runnable r) {
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                r.run();
                return true;
            }
        });
        v.requestLayout();
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
    private void showHomeMenu(View ignored) {
        PopupMenu menu = menuAtTouch();
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

    /** Rename an app everywhere it appears. Empty, or Reset, goes back to its own name. */
    private void rename(AppEntry app) {
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
        adapter.setApps(repo.apps());
        refreshHome();
    }

    /* ---------- folders ---------- */

    private void showFolderMenu(HomeLayout.Item item) {
        HomeLayout.Folder folder = layout.folder(item);
        CellLayout parent = HomeLayout.DOCK.equals(item.container) ? dock : grid;
        View anchor = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) parent.getChildAt(i).getLayoutParams();
            if (lp.cellX == item.x && lp.cellY == item.y) anchor = parent.getChildAt(i);
        }
        if (folder == null || anchor == null) return;
        View finalFolderAnchor = anchor;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.open_folder);
        menu.getMenu().add(0, 2, 1, R.string.rename_folder);
        menu.getMenu().add(0, 3, 2, R.string.ungroup);
        menu.getMenu().add(0, 4, 3, HomeLayout.DOCK.equals(item.container)
                ? R.string.remove_from_dock : R.string.remove_from_home);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: showFolder(folder, finalFolderAnchor); return true;
                case 2: renameFolder(folder); return true;
                case 3: {
                    int lost = layout.ungroup(item);
                    refreshHome();
                    if (lost > 0) Toast.makeText(this, getString(R.string.ungroup_no_room, lost), Toast.LENGTH_LONG).show();
                    return true;
                }
                case 4: layout.remove(item); refreshHome(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** Opens a folder as a compact card right above its icon (below it if there's no room). */
    private void showFolder(HomeLayout.Folder folder, View source) {
        openFolder = folder;
        source.getGlobalVisibleRect(folderSource);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Prefs.background(Math.max(prefs.drawerOpacity(), 85)));
        bg.setCornerRadius(dp(20));
        folderCard.setBackground(bg);
        populateFolder();
        boolean opening = folderOverlay.getVisibility() != View.VISIBLE;
        folderOverlay.setVisibility(View.VISIBLE);
        folderOverlay.setAlpha(1f);
        if (opening) {
            folderCard.setAlpha(0f); // hidden until it's measured and placed
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, closeFolderOnBack);
        }
        afterLayout(folderCard, () -> placeFolderCard(opening));
    }

    private void placeFolderCard(boolean animate) {
        int[] origin = new int[2];
        folderOverlay.getLocationOnScreen(origin);
        int w = folderCard.getWidth(), h = folderCard.getHeight();
        int ow = folderOverlay.getWidth(), oh = folderOverlay.getHeight();
        int margin = Math.round(dp(8));
        Insets bars = folderOverlay.getRootWindowInsets() == null ? Insets.NONE
                : folderOverlay.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars());
        float cx = folderSource.exactCenterX() - origin[0];
        float x = Math.max(margin, Math.min(ow - w - margin, cx - w / 2f));
        float y = folderSource.top - origin[1] - h - margin;           // above the icon
        if (y < bars.top + margin) y = folderSource.bottom - origin[1] + margin; // else below
        y = Math.max(bars.top + margin, Math.min(oh - bars.bottom - h - margin, y));
        folderCard.setX(x);
        folderCard.setY(y);
        if (animate) {
            folderCard.setPivotX(cx - x);
            folderCard.setPivotY(y < folderSource.top - origin[1] ? h : 0);
            folderCard.setScaleX(0.6f);
            folderCard.setScaleY(0.6f);
            folderCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        }
    }

    private void populateFolder() {
        HomeLayout.Folder folder = openFolder;
        if (folder == null) return;
        folderTitle.setText(folderName(folder));
        folderGrid.removeAllViews();
        int count = 0;
        for (String key : folder.apps) if (repo.find(key) != null) count++;
        // As wide as it needs to be, up to four columns.
        folderGrid.setColumnCount(Math.max(3, Math.min(4, count)));
        int cellW = Math.round(dp(76));
        boolean labels = prefs.homeLabels();
        for (String key : folder.apps) {
            AppEntry app = repo.find(key);
            if (app == null) continue;
            View v = makeIcon(app, grid, labels);
            v.setOnClickListener(view -> {
                repo.launch(app, view);
                folderOverlay.postDelayed(this::closeFolder, 300);
            });
            v.setMinimumWidth(cellW);
            v.setOnLongClickListener(view -> { showFolderAppMenu(folder, app, view); return true; });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = Math.round(dp(92));
            folderGrid.addView(v, lp);
        }
    }

    private void showFolderAppMenu(HomeLayout.Folder folder, AppEntry app, View anchor) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.move_to_home);
        menu.getMenu().add(0, 2, 1, R.string.remove_from_folder);
        menu.getMenu().add(0, 3, 2, R.string.rename);
        menu.getMenu().add(0, 4, 3, R.string.change_icon);
        menu.getMenu().add(0, 5, 4, R.string.app_info);
        if (repo.canUninstall(app)) menu.getMenu().add(0, 6, 5, R.string.uninstall);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1:
                    if (!layout.moveOutOfFolder(folder, app.key)) {
                        Toast.makeText(this, R.string.home_full, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    afterFolderChange(folder);
                    return true;
                case 2:
                    layout.removeFromFolder(folder, app.key);
                    afterFolderChange(folder);
                    return true;
                case 3: rename(app); return true;
                case 4: changeIcon(app); return true;
                case 5: repo.openAppInfo(app, anchor); return true;
                case 6: repo.uninstall(this, app); return true;
                default: return false;
            }
        });
        menu.show();
    }

    // A folder that dissolved (one app left) closes; otherwise it stays open, updated.
    private void afterFolderChange(HomeLayout.Folder folder) {
        refreshHome();
        if (layout.itemFor(folder) == null || folder.apps.size() < 2) {
            closeFolder();
        } else {
            populateFolder();
            afterLayout(folderCard, () -> placeFolderCard(false)); // size may have changed
        }
    }

    private void closeFolder() {
        if (openFolder == null) return;
        openFolder = null;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeFolderOnBack);
        folderOverlay.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> { if (openFolder == null) folderOverlay.setVisibility(View.GONE); })
                .start();
    }

    private void renameFolder(HomeLayout.Folder folder) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(folder.name);
        input.setHint(R.string.folder_default_name);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(dp(20));
        box.setPadding(pad, Math.round(dp(8)), pad, 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_folder)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    layout.renameFolder(folder, input.getText().toString());
                    refreshHome();
                    if (openFolder == folder) populateFolder();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
    }

    /* ---------- per-app icon style ---------- */

    private void changeIcon(AppEntry app) {
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
                    adapter.applyPrefs(iconPx);
                    refreshHome();
                    if (openFolder != null) populateFolder();
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
        boolean hidden = prefs.hidden().contains(app.key);
        if (!pinned) {
            menu.getMenu().add(0, 1, 0, R.string.add_to_home);
            if (prefs.showDock()) menu.getMenu().add(0, 4, 1, R.string.add_to_dock);
        }
        menu.getMenu().add(0, 5, 2, R.string.rename);
        menu.getMenu().add(0, 7, 2, R.string.change_icon);
        menu.getMenu().add(0, 6, 3, hidden ? R.string.unhide : R.string.hide);
        menu.getMenu().add(0, 2, 4, R.string.app_info);
        if (repo.canUninstall(app)) menu.getMenu().add(0, 3, 5, R.string.uninstall);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: pin(app, HomeLayout.HOME, R.string.added_to_home, R.string.home_full); return true;
                case 4: pin(app, HomeLayout.DOCK, R.string.added_to_dock, R.string.dock_full); return true;
                case 5: rename(app); return true;
                case 7: changeIcon(app); return true;
                case 6:
                    prefs.setHidden(app.key, !hidden);
                    adapter.applyPrefs(iconPx);
                    Toast.makeText(this, getString(hidden ? R.string.unhidden : R.string.hidden_toast,
                            adapter.labelOf(app)), Toast.LENGTH_SHORT).show();
                    return true;
                case 2: repo.openAppInfo(app, view); return true;
                case 3: repo.uninstall(this, app); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void pin(AppEntry app, String container, int addedMsg, int fullMsg) {
        if (layout.add(app.key, container, currentPage)) {
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
