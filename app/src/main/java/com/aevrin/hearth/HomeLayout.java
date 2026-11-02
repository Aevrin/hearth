/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What sits where: home pages, dock pages and folders. Saved as readable JSON
 * in the app's private storage (files/home.json).
 *
 * Home pages can run at double resolution ("subgrid"): every home cell is split
 * 2×2, icons and folders cover 2×2 of those fine cells, and anything can sit at
 * half-cell offsets. All home coordinates and sizes here are in fine cells;
 * {@link #columns()} and {@link #rows()} are the fine-cell counts. The dock is
 * always a plain row.
 *
 * Keys are app keys, "folder:<id>" or "shortcut:<...>" (see Shortcuts). Nothing
 * is ever dropped when the grid shrinks: an item that no longer fits moves to the
 * nearest free cell, and if there is none it keeps its old position, hidden,
 * until there's room again.
 */
final class HomeLayout {

    private static final String TAG = "HomeLayout";
    // v1 home only · v2 +container · v3 +page · v4 +folders · v5 dock pages · v6 widget spans
    // · v7 subgrid. Older files load as-is.
    private static final int VERSION = 7;

    static final String HOME = "home";
    static final String DOCK = "dock";
    static final String FOLDER_PREFIX = "folder:";
    static final String WIDGET_PREFIX = "widget:";

    static final class Item {
        /** An app key, a shortcut key, FOLDER_PREFIX + folder id, or WIDGET_PREFIX + widget id. */
        String key;
        String container;
        int page, x, y;
        /** Cells covered, from (x, y) right and down. Only widgets are bigger than 1×1. */
        int spanX = 1, spanY = 1;
        Item(String key, String container, int page, int x, int y) {
            this.key = key; this.container = container; this.page = page; this.x = x; this.y = y;
        }
        boolean covers(String c, int p, int cx, int cy) {
            return container.equals(c) && page == p && cx >= x && cx < x + spanX && cy >= y && cy < y + spanY;
        }
        boolean overlaps(String c, int p, int ax, int ay, int w, int h) {
            return container.equals(c) && page == p && ax < x + spanX && x < ax + w && ay < y + spanY && y < ay + h;
        }
    }

    /** A named group of apps and shortcuts occupying one cell. */
    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();
        Folder(String id, String name) { this.id = id; this.name = name; }
    }

    private final File file;
    private int columns, rows, pages, dockSlots, dockPages; // home columns/rows in fine cells
    private int sub = 1; // 1 normal, 2 subgrid
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Folder> folders = new LinkedHashMap<>();

    private HomeLayout(File file) {
        this.file = file;
    }

    static File fileIn(File dir) {
        return new File(dir, "home.json");
    }

    /** Loads with the dimensions currently set in Prefs. */
    static HomeLayout load(File dir, Prefs p) {
        return load(dir, p.columns(), p.rows(), p.pages(), p.dockSlots(), p.dockPages(), p.subgrid() ? 2 : 1);
    }

    /** Columns and rows are the home grid's normal (coarse) size. */
    static HomeLayout load(File dir, int columns, int rows, int pages, int dockSlots, int dockPages, int sub) {
        HomeLayout layout = new HomeLayout(fileIn(dir));
        layout.sub = sub == 2 ? 2 : 1;
        layout.columns = columns * layout.sub;
        layout.rows = rows * layout.sub;
        layout.pages = pages;
        layout.dockSlots = dockSlots;
        layout.dockPages = dockPages;
        boolean converted = false;
        if (layout.file.isFile()) {
            try {
                String text = new String(Files.readAllBytes(layout.file.toPath()), StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(text);
                JSONArray fs = root.optJSONArray("folders");
                if (fs != null) {
                    for (int i = 0; i < fs.length(); i++) {
                        JSONObject f = fs.getJSONObject(i);
                        Folder folder = new Folder(f.getString("id"), f.optString("name", ""));
                        JSONArray apps = f.optJSONArray("apps");
                        if (apps != null) for (int j = 0; j < apps.length(); j++) folder.apps.add(apps.getString(j));
                        if (!folder.apps.isEmpty()) layout.folders.put(folder.id, folder);
                    }
                }
                int fileSub = root.optInt("subgrid", 1) == 2 ? 2 : 1;
                JSONArray arr = root.getJSONArray("items");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String c = DOCK.equals(o.optString("container")) ? DOCK : HOME;
                    String key = o.getString("key");
                    if (isFolderKey(key) && !layout.folders.containsKey(folderId(key))) continue;
                    Item it = new Item(key, c, o.optInt("page", 0), o.getInt("x"), o.getInt("y"));
                    if (isWidgetKey(key)) {
                        it.container = HOME; // widgets live on home pages only
                        it.spanX = Math.max(1, o.optInt("w", 1));
                        it.spanY = Math.max(1, o.optInt("h", 1));
                    } else if (HOME.equals(c)) {
                        it.spanX = it.spanY = fileSub;
                    }
                    layout.items.add(it);
                }
                // Saved at the other resolution (subgrid was just switched): convert.
                if (fileSub != layout.sub) {
                    layout.convert(fileSub, layout.sub);
                    converted = true;
                }
                // Folders nothing points at are leftovers; drop them.
                Set<String> used = new HashSet<>();
                for (Item it : layout.items) if (isFolderKey(it.key)) used.add(folderId(it.key));
                layout.folders.keySet().retainAll(used);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "home.json unreadable, starting empty", e);
            }
        }
        if (layout.reflow() || converted) layout.save();
        return layout;
    }

    /** Scales home positions and sizes between normal (1) and subgrid (2) units. */
    private void convert(int from, int to) {
        for (Item it : items) {
            if (!HOME.equals(it.container)) continue;
            if (to > from) {
                it.x *= 2;
                it.y *= 2;
                it.spanX *= 2;
                it.spanY *= 2;
            } else {
                it.x /= 2;
                it.y /= 2;
                it.spanX = isWidgetKey(it.key) ? Math.max(1, (it.spanX + 1) / 2) : 1;
                it.spanY = isWidgetKey(it.key) ? Math.max(1, (it.spanY + 1) / 2) : 1;
            }
        }
        // Anything that now overlaps is moved by reflow().
    }

    /** 2 when home is on the subgrid, else 1. */
    int sub() { return sub; }

    /** How many cells an icon or folder covers in each direction, in this container. */
    int iconSpan(String container) {
        return HOME.equals(container) ? sub : 1;
    }

    private Item newItem(String key, String container, int page, int x, int y) {
        Item it = new Item(key, container, page, x, y);
        it.spanX = it.spanY = iconSpan(container);
        return it;
    }

    int columns() { return columns; }
    int rows() { return rows; }
    int pages() { return pages; }
    int dockSlots() { return dockSlots; }
    int dockPages() { return dockPages; }

    List<Item> items() { return items; }

    boolean isEmpty() {
        for (Item it : items) if (fitsItem(it, it.container, it.page, it.x, it.y)) return false;
        return true;
    }

    boolean pageIsEmpty(int page) {
        for (Item it : items) if (HOME.equals(it.container) && it.page == page) return false;
        return true;
    }

    /** True when the cell exists in the current grid (home page or dock page). */
    boolean fits(String container, int page, int x, int y) {
        if (DOCK.equals(container)) return page >= 0 && page < dockPages && y == 0 && x >= 0 && x < dockSlots;
        return page >= 0 && page < pages && x >= 0 && y >= 0 && x < columns && y < rows;
    }

    /** Whether an item, with its span, fits entirely on the grid at (x, y). */
    boolean fitsItem(Item it, String container, int page, int x, int y) {
        return fitsArea(container, page, x, y, it.spanX, it.spanY);
    }

    /** Whether a w×h area at (x, y) is entirely on the grid. */
    boolean fitsArea(String container, int page, int x, int y, int w, int h) {
        if (DOCK.equals(container)) return w == 1 && h == 1 && fits(container, page, x, y);
        return fits(container, page, x, y) && x + w <= columns && y + h <= rows;
    }

    /** True when no item other than `ignore` covers any of the w×h cells at (x, y). */
    boolean areaFree(String container, int page, int x, int y, int w, int h, Item ignore) {
        for (Item it : items) if (it != ignore && it.overlaps(container, page, x, y, w, h)) return false;
        return true;
    }

    private int cols(String container) { return DOCK.equals(container) ? dockSlots : columns; }
    private int rowsOf(String container) { return DOCK.equals(container) ? 1 : rows; }
    private int pagesOf(String container) { return DOCK.equals(container) ? dockPages : pages; }

    /** The item covering a cell (a widget covers several), or null. */
    Item at(String container, int page, int x, int y) {
        for (Item it : items) if (it.covers(container, page, x, y)) return it;
        return null;
    }

    /** Whether the key is pinned anywhere: home, dock, or inside a folder. */
    boolean contains(String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        for (Folder f : folders.values()) if (f.apps.contains(key)) return true;
        return false;
    }

    /** Every key on the home screen, dock and in folders (for pinned-shortcut bookkeeping). */
    Set<String> allKeys() {
        Set<String> s = new HashSet<>();
        for (Item it : items) if (!isFolderKey(it.key) && !isWidgetKey(it.key)) s.add(it.key);
        for (Folder f : folders.values()) s.addAll(f.apps);
        return s;
    }

    /**
     * First free spot for an icon as {page, x, y}, trying the preferred page first;
     * null when full. On the subgrid it keeps to whole cells, so icons line up.
     */
    int[] firstFree(String container, int preferredPage) {
        int n = pagesOf(container), span = iconSpan(container);
        for (int i = 0; i < n; i++) {
            int p = (Math.max(0, preferredPage) + i) % n;
            for (int y = 0; y + span <= rowsOf(container); y += span) {
                for (int x = 0; x + span <= cols(container); x += span) {
                    if (areaFree(container, p, x, y, span, span, null)) return new int[] {p, x, y};
                }
            }
        }
        return null;
    }

    /** Top-left {page, x, y} of the first free w×h area on home pages, preferred page first. */
    int[] firstFreeArea(int preferredPage, int w, int h) {
        for (int i = 0; i < pages; i++) {
            int p = (Math.max(0, preferredPage) + i) % pages;
            for (int y = 0; y + h <= rows; y++) {
                for (int x = 0; x + w <= columns; x++) {
                    if (areaFree(HOME, p, x, y, w, h, null)) return new int[] {p, x, y};
                }
            }
        }
        return null;
    }

    /** Places a widget in the first free area big enough. Returns null when there's no room. */
    Item addWidget(int widgetId, int preferredPage, int w, int h) {
        w = Math.max(1, Math.min(columns, w));
        h = Math.max(1, Math.min(rows, h));
        int[] a = firstFreeArea(preferredPage, w, h);
        if (a == null) return null;
        Item it = new Item(WIDGET_PREFIX + widgetId, HOME, a[0], a[1], a[2]);
        it.spanX = w;
        it.spanY = h;
        items.add(it);
        save();
        return it;
    }

    /** Whether a widget could take the area (x, y, w, h) on its page. */
    boolean canResize(Item it, int x, int y, int w, int h) {
        return w >= 1 && h >= 1 && fitsArea(it.container, it.page, x, y, w, h)
                && areaFree(it.container, it.page, x, y, w, h, it);
    }

    /** Moves and resizes a widget to an area. Returns false (and changes nothing) if it doesn't fit. */
    boolean resizeItemTo(Item it, int x, int y, int w, int h) {
        if (!canResize(it, x, y, w, h)) return false;
        it.x = x;
        it.y = y;
        it.spanX = w;
        it.spanY = h;
        save();
        return true;
    }

    static boolean isWidgetKey(String key) {
        return key.startsWith(WIDGET_PREFIX);
    }

    static int widgetId(Item it) {
        try {
            return Integer.parseInt(it.key.substring(WIDGET_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Puts the key in the first free cell. Returns false when there's no room. */
    boolean add(String key, String container, int preferredPage) {
        int[] cell = firstFree(container, preferredPage);
        if (cell == null) return false;
        items.add(newItem(key, container, cell[0], cell[1], cell[2]));
        save();
        return true;
    }

    /** Puts a new key at a specific free spot (drag from the drawer); (x, y) is its top-left. */
    void addAt(String key, String container, int page, int x, int y) {
        int span = iconSpan(container);
        if (!fitsArea(container, page, x, y, span, span) || !areaFree(container, page, x, y, span, span, null)) return;
        items.add(newItem(key, container, page, x, y));
        save();
    }

    /**
     * Moves an item, possibly to the other container or page; if the target is a
     * single taken cell, the two swap. A widget only moves into free cells.
     */
    void move(Item item, String container, int page, int x, int y) {
        // Icons take the target container's icon size; widgets keep theirs.
        boolean widget = isWidgetKey(item.key);
        if (widget && DOCK.equals(container)) return;
        int w = widget ? item.spanX : iconSpan(container), h = widget ? item.spanY : iconSpan(container);
        if (!fitsArea(container, page, x, y, w, h)) return;
        if (w > 1 || h > 1) {
            if (!areaFree(container, page, x, y, w, h, item)) return;
            place(item, container, page, x, y, w, h);
            save();
            return;
        }
        Item other = at(container, page, x, y);
        if (other == item) return;
        if (other != null && (other.spanX > 1 || other.spanY > 1)) return; // can't swap with a widget
        if (other != null) {
            // Swap: the other takes the dragged item's old spot (which fits it, being an icon's spot).
            int span = iconSpan(item.container);
            place(other, item.container, item.page, item.x, item.y, span, span);
        }
        place(item, container, page, x, y, w, h);
        save();
    }

    private static void place(Item it, String container, int page, int x, int y, int w, int h) {
        it.container = container;
        it.page = page;
        it.x = x;
        it.y = y;
        it.spanX = w;
        it.spanY = h;
    }

    /**
     * Where the icons would go if something were dropped at (x, y) with the others
     * sliding along by one, in reading order, into the next free cell (or back
     * toward the previous one): a map of each icon that moves to its new {x, y}.
     * Null if there's no room. Changes nothing; used to preview a drop.
     */
    Map<Item, int[]> insertPlan(Item dragged, String container, int page, int x, int y) {
        if (!fits(container, page, x, y)) return null;
        if (iconSpan(container) > 1) return null; // no sliding on the subgrid
        if (dragged != null && (dragged.spanX > 1 || dragged.spanY > 1)) return null;
        int cols = cols(container), rws = rowsOf(container), n = cols * rws;
        // Cells under widgets stay put; icons slide through the other cells only.
        Item[] cell = new Item[n];
        boolean[] blocked = new boolean[n];
        for (Item it : items) {
            if (it == dragged || !it.container.equals(container) || it.page != page) continue;
            for (int dy = 0; dy < it.spanY; dy++) {
                for (int dx = 0; dx < it.spanX; dx++) {
                    int cx = it.x + dx, cy = it.y + dy;
                    if (cx >= cols || cy >= rws) continue;
                    if (it.spanX > 1 || it.spanY > 1) blocked[cy * cols + cx] = true;
                    else cell[cy * cols + cx] = it;
                }
            }
        }
        int target = y * cols + x;
        if (blocked[target]) return null;
        List<Integer> seq = new ArrayList<>(); // the free-flowing cells, in reading order
        for (int i = 0; i < n; i++) if (!blocked[i]) seq.add(i);
        int t = seq.indexOf(target);
        Map<Item, int[]> moves = new LinkedHashMap<>();
        int empty = -1;
        for (int i = t; i < seq.size(); i++) if (cell[seq.get(i)] == null) { empty = i; break; }
        if (empty >= 0) {
            for (int i = empty; i > t; i--) moves.put(cell[seq.get(i - 1)], xy(seq.get(i), cols));
        } else {
            for (int i = t; i >= 0; i--) if (cell[seq.get(i)] == null) { empty = i; break; }
            if (empty < 0) return null;
            for (int i = empty; i < t; i++) moves.put(cell[seq.get(i + 1)], xy(seq.get(i), cols));
        }
        return moves;
    }

    private static int[] xy(int index, int cols) {
        return new int[] {index % cols, index / cols};
    }

    /**
     * Drops `dragged` (or a new item for `key` when dragged is null) at a cell,
     * sliding the others along (see insertPlan). Returns false if the page is
     * full, in which case nothing changes.
     */
    boolean insertAt(Item dragged, String key, String container, int page, int x, int y) {
        Map<Item, int[]> moves = insertPlan(dragged, container, page, x, y);
        if (moves == null) return false;
        for (Map.Entry<Item, int[]> m : moves.entrySet()) {
            m.getKey().x = m.getValue()[0];
            m.getKey().y = m.getValue()[1];
        }
        if (dragged != null) {
            place(dragged, container, page, x, y, 1, 1);
        } else {
            items.add(newItem(key, container, page, x, y));
        }
        save();
        return true;
    }

    void remove(Item item) {
        items.remove(item);
        if (isFolderKey(item.key)) folders.remove(folderId(item.key));
        save();
    }

    /* ---------- pages ---------- */

    /** Adds an empty home page at `index`, shifting later pages along. */
    void insertPage(int index) {
        index = Math.max(0, Math.min(pages, index));
        for (Item it : items) if (HOME.equals(it.container) && it.page >= index) it.page++;
        pages++;
        save();
    }

    /**
     * Deletes a home page. Its icons move to free cells on other pages; any that
     * don't fit wait, hidden, until there's room. Never deletes the last page.
     */
    void deletePage(int index) {
        if (pages <= 1 || index < 0 || index >= pages) return;
        for (Item it : items) {
            if (!HOME.equals(it.container)) continue;
            if (it.page == index) it.page = -1; // homeless; reflow re-homes it
            else if (it.page > index) it.page--;
        }
        pages--;
        reflow();
        save();
    }

    /** Swaps two home pages. */
    void swapPages(int a, int b) {
        if (a < 0 || b < 0 || a >= pages || b >= pages || a == b) return;
        for (Item it : items) {
            if (!HOME.equals(it.container)) continue;
            if (it.page == a) it.page = b;
            else if (it.page == b) it.page = a;
        }
        save();
    }

    /* ---------- folders ---------- */

    static boolean isFolderKey(String key) {
        return key.startsWith(FOLDER_PREFIX);
    }

    private static String folderId(String key) {
        return key.substring(FOLDER_PREFIX.length());
    }

    Folder folder(Item item) {
        return isFolderKey(item.key) ? folders.get(folderId(item.key)) : null;
    }

    Item itemFor(Folder f) {
        for (Item it : items) if (it.key.equals(FOLDER_PREFIX + f.id)) return it;
        return null;
    }

    /**
     * Turns `target` into a folder holding its key plus `key`. If the key came from
     * another cell (`dragged`), that cell is freed.
     */
    Folder createFolder(Item target, String key, Item dragged, String name) {
        Folder f = new Folder(Long.toString(System.currentTimeMillis(), 36), name);
        f.apps.add(target.key);
        f.apps.add(key);
        folders.put(f.id, f);
        target.key = FOLDER_PREFIX + f.id;
        if (dragged != null) items.remove(dragged);
        save();
        return f;
    }

    /** Adds a key to a folder; frees the dragged item's cell if it came from one. */
    void addToFolder(Folder f, String key, Item dragged) {
        if (!f.apps.contains(key)) f.apps.add(key);
        if (dragged != null) items.remove(dragged);
        save();
    }

    /**
     * Reorders a folder. `shown` is the new order of the entries currently shown;
     * any others (an app still loading, say) keep their place at the end.
     */
    void setFolderOrder(Folder f, List<String> shown) {
        List<String> order = new ArrayList<>();
        for (String k : shown) if (f.apps.contains(k) && !order.contains(k)) order.add(k);
        for (String k : f.apps) if (!order.contains(k)) order.add(k);
        f.apps.clear();
        f.apps.addAll(order);
        save();
    }

    /** Whether the key is placed somewhere other than folder `f` (home, dock or another folder). */
    boolean containsElsewhere(Folder f, String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        for (Folder o : folders.values()) if (o != f && o.apps.contains(key)) return true;
        return false;
    }

    void renameFolder(Folder f, String name) {
        f.name = name == null ? "" : name.trim();
        save();
    }

    /**
     * Takes a key out of a folder onto the home screen (same page if possible). A
     * folder left with one entry turns back into that entry. Returns false if
     * there was no free cell, in which case nothing changes.
     */
    boolean moveOutOfFolder(Folder f, String key) {
        Item folderItem = itemFor(f);
        if (folderItem == null) return false;
        int[] cell = firstFree(HOME, HOME.equals(folderItem.container) ? folderItem.page : 0);
        if (cell == null) return false;
        f.apps.remove(key);
        items.add(newItem(key, HOME, cell[0], cell[1], cell[2]));
        if (f.apps.size() == 1) {
            folderItem.key = f.apps.get(0);
            folders.remove(f.id);
        }
        save();
        return true;
    }

    /** Removes a key from a folder entirely (back to the drawer only). */
    void removeFromFolder(Folder f, String key) {
        Item folderItem = itemFor(f);
        f.apps.remove(key);
        if (folderItem != null && f.apps.size() == 1) {
            folderItem.key = f.apps.get(0);
            folders.remove(f.id);
        } else if (f.apps.isEmpty()) {
            if (folderItem != null) items.remove(folderItem);
            folders.remove(f.id);
        }
        save();
    }

    /**
     * Spreads a folder's entries back onto the home screen: the first takes the
     * folder's cell, the rest go to free cells. Returns how many didn't fit
     * (those stay in the drawer only).
     */
    int ungroup(Item folderItem) {
        Folder f = folder(folderItem);
        if (f == null) return 0;
        folders.remove(f.id);
        folderItem.key = f.apps.get(0);
        int lost = 0;
        for (int i = 1; i < f.apps.size(); i++) {
            int[] cell = firstFree(HOME, HOME.equals(folderItem.container) ? folderItem.page : 0);
            if (cell == null) { lost++; continue; }
            items.add(newItem(f.apps.get(i), HOME, cell[0], cell[1], cell[2]));
        }
        save();
        return lost;
    }

    /* ---------- tidying ---------- */

    /**
     * Moves items that are off the grid, or sharing a cell, to free cells. Dock
     * overflow goes to the home screen. Returns whether anything moved.
     */
    private boolean reflow() {
        boolean moved = false;
        List<Item> placed = new ArrayList<>();
        List<Item> homeless = new ArrayList<>();
        for (Item it : items) {
            if (fitsItem(it, it.container, it.page, it.x, it.y)
                    && !taken(placed, it.container, it.page, it.x, it.y, it.spanX, it.spanY)) {
                placed.add(it);
            } else {
                homeless.add(it);
            }
        }
        for (Item it : homeless) {
            if (isWidgetKey(it.key)) {
                // A widget: shrink it to the grid if it's now bigger, then find room for it.
                int w = Math.min(it.spanX, columns), h = Math.min(it.spanY, rows);
                int[] a = freeAreaAmong(placed, Math.max(0, Math.min(it.page, pages - 1)), w, h);
                if (a == null) continue; // waits, hidden, until there's room
                it.container = HOME;
                it.page = a[0];
                it.x = a[1];
                it.y = a[2];
                it.spanX = w;
                it.spanY = h;
                placed.add(it);
                moved = true;
                continue;
            }
            int[] cell = DOCK.equals(it.container) ? freeAmong(placed, DOCK, it.page) : null;
            String target = DOCK;
            if (cell == null) {
                cell = freeAmong(placed, HOME, HOME.equals(it.container) ? Math.max(0, Math.min(it.page, pages - 1)) : 0);
                target = HOME;
            }
            if (cell == null) continue; // no room anywhere: keep it as-is, hidden, until there is
            int span = iconSpan(target);
            place(it, target, cell[0], cell[1], cell[2], span, span);
            placed.add(it);
            moved = true;
        }
        return moved;
    }

    private int[] freeAmong(List<Item> placed, String container, int preferredPage) {
        int n = pagesOf(container), span = iconSpan(container);
        for (int i = 0; i < n; i++) {
            int p = (Math.max(0, preferredPage) + i) % n;
            for (int y = 0; y + span <= rowsOf(container); y += span) {
                for (int x = 0; x + span <= cols(container); x += span) {
                    if (!taken(placed, container, p, x, y, span, span)) return new int[] {p, x, y};
                }
            }
            // Nothing on whole cells: try half-cell spots too.
            if (span > 1) {
                for (int y = 0; y + span <= rowsOf(container); y++) {
                    for (int x = 0; x + span <= cols(container); x++) {
                        if (!taken(placed, container, p, x, y, span, span)) return new int[] {p, x, y};
                    }
                }
            }
        }
        return null;
    }

    private int[] freeAreaAmong(List<Item> placed, int preferredPage, int w, int h) {
        for (int i = 0; i < pages; i++) {
            int p = (preferredPage + i) % pages;
            for (int y = 0; y + h <= rows; y++) {
                for (int x = 0; x + w <= columns; x++) {
                    if (!taken(placed, HOME, p, x, y, w, h)) return new int[] {p, x, y};
                }
            }
        }
        return null;
    }

    private static boolean taken(List<Item> placed, String container, int page, int x, int y, int w, int h) {
        for (Item p : placed) if (p.overlaps(container, page, x, y, w, h)) return true;
        return false;
    }

    /* ---------- storage ---------- */

    /** The file's contents, for backups. */
    String toJsonString() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject()
                    .put("key", it.key)
                    .put("container", it.container)
                    .put("page", it.page)
                    .put("x", it.x)
                    .put("y", it.y);
            if (isWidgetKey(it.key)) o.put("w", it.spanX).put("h", it.spanY);
            arr.put(o);
        }
        JSONArray fs = new JSONArray();
        for (Folder f : folders.values()) {
            fs.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", new JSONArray(f.apps)));
        }
        return new JSONObject()
                .put("version", VERSION)
                .put("subgrid", sub)
                .put("columns", columns / sub)
                .put("rows", rows / sub)
                .put("pages", pages)
                .put("dock_slots", dockSlots)
                .put("dock_pages", dockPages)
                .put("folders", fs)
                .put("items", arr)
                .toString(2);
    }

    private void save() {
        try {
            writeAtomically(file, toJsonString());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "could not save home.json", e);
        }
    }

    static void writeAtomically(File target, String text) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException("rename failed");
    }
}
