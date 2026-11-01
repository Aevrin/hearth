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
 * Which app sits in which cell of the home grid or the dock, saved as readable
 * JSON in the app's private storage (files/home.json).
 */
final class HomeLayout {

    private static final String TAG = "HomeLayout";
    private static final int VERSION = 2; // v2 added "container"; v1 files load as all-home

    static final String HOME = "home";
    static final String DOCK = "dock";

    static final class Item {
        final String key;
        String container;
        int x, y;
        Item(String key, String container, int x, int y) {
            this.key = key; this.container = container; this.x = x; this.y = y;
        }
    }

    private final File file;
    final int columns;
    final int rows;
    final int dockSlots;
    private final List<Item> items = new ArrayList<>();

    private HomeLayout(File file, int columns, int rows, int dockSlots) {
        this.file = file;
        this.columns = columns;
        this.rows = rows;
        this.dockSlots = dockSlots;
    }

    static HomeLayout load(File dir, int columns, int rows, int dockSlots) {
        HomeLayout layout = new HomeLayout(new File(dir, "home.json"), columns, rows, dockSlots);
        if (!layout.file.isFile()) return layout;
        try {
            String text = new String(Files.readAllBytes(layout.file.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(text).getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String c = DOCK.equals(o.optString("container")) ? DOCK : HOME;
                int x = o.getInt("x"), y = o.getInt("y");
                // Cells outside the grid (or doubled up) drop out rather than overlap.
                if (!layout.inBounds(c, x, y) || layout.at(c, x, y) != null) continue;
                layout.items.add(new Item(o.getString("key"), c, x, y));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "home.json unreadable, starting empty", e);
        }
        return layout;
    }

    List<Item> items() { return items; }

    boolean isEmpty(String container) {
        for (Item it : items) if (it.container.equals(container)) return false;
        return true;
    }

    private boolean inBounds(String container, int x, int y) {
        if (DOCK.equals(container)) return y == 0 && x >= 0 && x < dockSlots;
        return x >= 0 && y >= 0 && x < columns && y < rows;
    }

    Item at(String container, int x, int y) {
        for (Item it : items) {
            if (it.container.equals(container) && it.x == x && it.y == y) return it;
        }
        return null;
    }

    boolean contains(String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        return false;
    }

    /** First free cell, left to right then top to bottom, or null when full. */
    int[] firstFree(String container) {
        int r = DOCK.equals(container) ? 1 : rows;
        int c = DOCK.equals(container) ? dockSlots : columns;
        for (int y = 0; y < r; y++) {
            for (int x = 0; x < c; x++) {
                if (at(container, x, y) == null) return new int[] {x, y};
            }
        }
        return null;
    }

    /** Puts the app in the first free cell of a container. Returns false when it's full. */
    boolean add(String key, String container) {
        int[] cell = firstFree(container);
        if (cell == null) return false;
        items.add(new Item(key, container, cell[0], cell[1]));
        save();
        return true;
    }

    /** Moves an item, possibly to the other container; if the target is taken, they swap. */
    void move(Item item, String container, int x, int y) {
        if (!inBounds(container, x, y)) return;
        Item other = at(container, x, y);
        if (other == item) return;
        if (other != null) {
            other.container = item.container;
            other.x = item.x;
            other.y = item.y;
        }
        item.container = container;
        item.x = x;
        item.y = y;
        save();
    }

    void remove(Item item) {
        items.remove(item);
        save();
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                arr.put(new JSONObject()
                        .put("key", it.key)
                        .put("container", it.container)
                        .put("x", it.x)
                        .put("y", it.y));
            }
            JSONObject root = new JSONObject()
                    .put("version", VERSION)
                    .put("columns", columns)
                    .put("rows", rows)
                    .put("dock_slots", dockSlots)
                    .put("items", arr);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "could not save home.json", e);
        }
    }
}
