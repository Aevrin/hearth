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
    // v1: home only. v2: + container. v3: + page. v4: + folders. Older files load as-is.
    private static final int VERSION = 4;
    static final String FOLDER_PREFIX = "folder:";

    static final String HOME = "home";
    static final String DOCK = "dock";

    static final class Item {
        /** An app key, or FOLDER_PREFIX + folder id. */
        String key;
        String container;
        int page, x, y;
        Item(String key, String container, int page, int x, int y) {
            this.key = key; this.container = container; this.page = page; this.x = x; this.y = y;
        }
    }

    /** A named group of apps occupying one cell. */
    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();
        Folder(String id, String name) { this.id = id; this.name = name; }
    }

    private final File file;
    private int columns, rows, pages, dockSlots;
    private final List<Item> items = new ArrayList<>();
    private final java.util.Map<String, Folder> folders = new java.util.LinkedHashMap<>();

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
                JSONArray arr = root.getJSONArray("items");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String c = DOCK.equals(o.optString("container")) ? DOCK : HOME;
                    String key = o.getString("key");
                    if (isFolderKey(key) && !layout.folders.containsKey(folderId(key))) continue;
                    layout.items.add(new Item(key, c,
                            Math.max(0, o.optInt("page", 0)), o.getInt("x"), o.getInt("y")));
                }
                // Folders nothing points at are leftovers; drop them.
                java.util.Set<String> used = new java.util.HashSet<>();
                for (Item it : layout.items) if (isFolderKey(it.key)) used.add(folderId(it.key));
                layout.folders.keySet().retainAll(used);
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

    /** Whether the app is pinned anywhere: home, dock, or inside a folder. */
    boolean contains(String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        for (Folder f : folders.values()) if (f.apps.contains(key)) return true;
        return false;
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

    /** Turns `target` (an app) into a folder holding it and `dragged`, which leaves its cell. */
    Folder createFolder(Item target, Item dragged, String name) {
        Folder f = new Folder(Long.toString(System.currentTimeMillis(), 36), name);
        f.apps.add(target.key);
        f.apps.add(dragged.key);
        folders.put(f.id, f);
        target.key = FOLDER_PREFIX + f.id;
        items.remove(dragged);
        save();
        return f;
    }

    /** Moves an app item into an existing folder. */
    void addToFolder(Folder f, Item dragged) {
        if (!f.apps.contains(dragged.key)) f.apps.add(dragged.key);
        items.remove(dragged);
        save();
    }

    void renameFolder(Folder f, String name) {
        f.name = name == null ? "" : name.trim();
        save();
    }

    /**
     * Takes an app out of a folder and puts it on the home screen (same page if
     * possible). A folder left with one app turns back into that app.
     * Returns false if there was no free cell, in which case nothing changes.
     */
    boolean moveOutOfFolder(Folder f, String appKey) {
        Item folderItem = itemFor(f);
        if (folderItem == null) return false;
        if (f.apps.size() > 2) {
            int[] cell = firstFree(HOME, folderItem.page);
            if (cell == null) return false;
            f.apps.remove(appKey);
            items.add(new Item(appKey, HOME, cell[0], cell[1], cell[2]));
        } else {
            // Two apps: the folder dissolves; the other app keeps the folder's cell.
            int[] cell = firstFree(HOME, folderItem.page);
            if (cell == null) return false;
            f.apps.remove(appKey);
            items.add(new Item(appKey, HOME, cell[0], cell[1], cell[2]));
            folderItem.key = f.apps.get(0);
            folders.remove(f.id);
        }
        save();
        return true;
    }

    /** Removes an app from a folder entirely (back to the drawer only). */
    void removeFromFolder(Folder f, String appKey) {
        Item folderItem = itemFor(f);
        f.apps.remove(appKey);
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
     * Spreads a folder's apps back onto the home screen: the first takes the
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
            int[] cell = firstFree(HOME, folderItem.page);
            if (cell == null) { lost++; continue; }
            items.add(new Item(f.apps.get(i), HOME, cell[0], cell[1], cell[2]));
        }
        save();
        return lost;
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
        if (isFolderKey(item.key)) folders.remove(folderId(item.key));
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
        JSONArray fs = new JSONArray();
        for (Folder f : folders.values()) {
            fs.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", new JSONArray(f.apps)));
        }
        return new JSONObject()
                .put("version", VERSION)
                .put("folders", fs)
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
