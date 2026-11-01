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
import java.util.List;

/**
 * Which app sits in which cell of which home page, or in the dock. Saved as
 * readable JSON in the app's private storage (files/home.json).
 *
 * Nothing is ever dropped when the grid shrinks: an item that no longer fits
 * moves to the nearest free cell, and if there is none it keeps its old
 * position, hidden, until the grid grows again.
 */
final class HomeLayout {

    private static final String TAG = "HomeLayout";
    // v1: home only. v2: + container. v3: + page. Older files load as-is (page 0).
    private static final int VERSION = 3;

    static final String HOME = "home";
    static final String DOCK = "dock";

    static final class Item {
        final String key;
        String container;
        int page, x, y;
        Item(String key, String container, int page, int x, int y) {
            this.key = key; this.container = container; this.page = page; this.x = x; this.y = y;
        }
    }

    private final File file;
    private int columns, rows, pages, dockSlots;
    private final List<Item> items = new ArrayList<>();

    private HomeLayout(File file) {
        this.file = file;
    }

    static File fileIn(File dir) {
        return new File(dir, "home.json");
    }

    static HomeLayout load(File dir, int columns, int rows, int pages, int dockSlots) {
        HomeLayout layout = new HomeLayout(fileIn(dir));
        layout.columns = columns;
        layout.rows = rows;
        layout.pages = pages;
        layout.dockSlots = dockSlots;
        if (layout.file.isFile()) {
            try {
                String text = new String(Files.readAllBytes(layout.file.toPath()), StandardCharsets.UTF_8);
                JSONArray arr = new JSONObject(text).getJSONArray("items");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String c = DOCK.equals(o.optString("container")) ? DOCK : HOME;
                    layout.items.add(new Item(o.getString("key"), c,
                            Math.max(0, o.optInt("page", 0)), o.getInt("x"), o.getInt("y")));
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "home.json unreadable, starting empty", e);
            }
        }
        if (layout.reflow()) layout.save();
        return layout;
    }

    int columns() { return columns; }
    int rows() { return rows; }
    int pages() { return pages; }
    int dockSlots() { return dockSlots; }

    List<Item> items() { return items; }

    boolean isEmpty() {
        for (Item it : items) if (fits(it.container, it.page, it.x, it.y)) return false;
        return true;
    }

    /** True when the cell exists in the current grid. */
    boolean fits(String container, int page, int x, int y) {
        if (DOCK.equals(container)) return y == 0 && x >= 0 && x < dockSlots;
        return page >= 0 && page < pages && x >= 0 && y >= 0 && x < columns && y < rows;
    }

    Item at(String container, int page, int x, int y) {
        for (Item it : items) {
            if (!it.container.equals(container) || it.x != x || it.y != y) continue;
            if (DOCK.equals(container) || it.page == page) return it;
        }
        return null;
    }

    boolean contains(String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        return false;
    }

    /** First free cell as {page, x, y}, trying the preferred page first; null when full. */
    int[] firstFree(String container, int preferredPage) {
        if (DOCK.equals(container)) {
            for (int x = 0; x < dockSlots; x++) if (at(DOCK, 0, x, 0) == null) return new int[] {0, x, 0};
            return null;
        }
        for (int i = 0; i < pages; i++) {
            int p = (preferredPage + i) % pages;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++) {
                    if (at(HOME, p, x, y) == null) return new int[] {p, x, y};
                }
            }
        }
        return null;
    }

    /** Puts the app in the first free cell. Returns false when there's no room. */
    boolean add(String key, String container, int preferredPage) {
        int[] cell = firstFree(container, preferredPage);
        if (cell == null) return false;
        items.add(new Item(key, container, cell[0], cell[1], cell[2]));
        save();
        return true;
    }

    /** Moves an item, possibly to the other container or page; if the target is taken, they swap. */
    void move(Item item, String container, int page, int x, int y) {
        if (!fits(container, page, x, y)) return;
        Item other = at(container, page, x, y);
        if (other == item) return;
        if (other != null) {
            other.container = item.container;
            other.page = item.page;
            other.x = item.x;
            other.y = item.y;
        }
        item.container = container;
        item.page = DOCK.equals(container) ? 0 : page;
        item.x = x;
        item.y = y;
        save();
    }

    void remove(Item item) {
        items.remove(item);
        save();
    }

    /** New grid dimensions: items that no longer fit are moved, never dropped. */
    void resize(int columns, int rows, int pages, int dockSlots) {
        if (columns == this.columns && rows == this.rows && pages == this.pages && dockSlots == this.dockSlots) return;
        this.columns = columns;
        this.rows = rows;
        this.pages = pages;
        this.dockSlots = dockSlots;
        reflow();
        save();
    }

    /**
     * Moves items that are off the grid, or sharing a cell, to free cells. Dock
     * overflow goes to the home screen. Returns whether anything moved.
     */
    private boolean reflow() {
        boolean moved = false;
        List<Item> placed = new ArrayList<>();
        List<Item> homeless = new ArrayList<>();
        for (Item it : items) {
            boolean clash = false;
            for (Item p : placed) {
                if (p.container.equals(it.container) && p.x == it.x && p.y == it.y
                        && (DOCK.equals(it.container) || p.page == it.page)) { clash = true; break; }
            }
            if (!clash && fits(it.container, it.page, it.x, it.y)) placed.add(it); else homeless.add(it);
        }
        for (Item it : homeless) {
            int[] cell = DOCK.equals(it.container) ? freeAmong(placed, DOCK, 0) : null;
            String target = DOCK;
            if (cell == null) {
                cell = freeAmong(placed, HOME, Math.min(it.page, pages - 1));
                target = HOME;
            }
            if (cell == null) continue; // no room anywhere: keep it as-is, hidden, until there is
            it.container = target;
            it.page = cell[0];
            it.x = cell[1];
            it.y = cell[2];
            placed.add(it);
            moved = true;
        }
        return moved;
    }

    private int[] freeAmong(List<Item> placed, String container, int preferredPage) {
        if (DOCK.equals(container)) {
            for (int x = 0; x < dockSlots; x++) if (!taken(placed, DOCK, 0, x, 0)) return new int[] {0, x, 0};
            return null;
        }
        for (int i = 0; i < pages; i++) {
            int p = (Math.max(0, preferredPage) + i) % pages;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++) {
                    if (!taken(placed, HOME, p, x, y)) return new int[] {p, x, y};
                }
            }
        }
        return null;
    }

    private static boolean taken(List<Item> placed, String container, int page, int x, int y) {
        for (Item p : placed) {
            if (p.container.equals(container) && p.x == x && p.y == y
                    && (DOCK.equals(container) || p.page == page)) return true;
        }
        return false;
    }

    /** The file's contents, for backups. */
    String toJsonString() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            arr.put(new JSONObject()
                    .put("key", it.key)
                    .put("container", it.container)
                    .put("page", it.page)
                    .put("x", it.x)
                    .put("y", it.y));
        }
        return new JSONObject()
                .put("version", VERSION)
                .put("columns", columns)
                .put("rows", rows)
                .put("pages", pages)
                .put("dock_slots", dockSlots)
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
