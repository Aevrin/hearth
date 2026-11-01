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
import java.util.Iterator;
import java.util.List;

/**
 * Which app sits in which home-screen cell, saved as readable JSON in the
 * app's private storage (files/home.json).
 */
final class HomeLayout {

    private static final String TAG = "HomeLayout";
    private static final int VERSION = 1;

    static final class Item {
        final String key;
        int x, y;
        Item(String key, int x, int y) { this.key = key; this.x = x; this.y = y; }
    }

    private final File file;
    final int columns;
    final int rows;
    private final List<Item> items = new ArrayList<>();

    private HomeLayout(File file, int columns, int rows) {
        this.file = file;
        this.columns = columns;
        this.rows = rows;
    }

    static HomeLayout load(File dir, int columns, int rows) {
        HomeLayout layout = new HomeLayout(new File(dir, "home.json"), columns, rows);
        if (!layout.file.isFile()) return layout;
        try {
            String text = new String(Files.readAllBytes(layout.file.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(text).getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int x = o.getInt("x"), y = o.getInt("y");
                // Cells outside the grid (or doubled up) drop out rather than overlap.
                if (x < 0 || y < 0 || x >= columns || y >= rows || layout.at(x, y) != null) continue;
                layout.items.add(new Item(o.getString("key"), x, y));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "home.json unreadable, starting empty", e);
        }
        return layout;
    }

    List<Item> items() { return items; }

    boolean isEmpty() { return items.isEmpty(); }

    Item at(int x, int y) {
        for (Item it : items) if (it.x == x && it.y == y) return it;
        return null;
    }

    boolean contains(String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        return false;
    }

    /** First free cell, left to right then top to bottom, or null when full. */
    int[] firstFree() {
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                if (at(x, y) == null) return new int[] {x, y};
            }
        }
        return null;
    }

    /** Puts the app at the first free cell. Returns false when the screen is full. */
    boolean add(String key) {
        int[] cell = firstFree();
        if (cell == null) return false;
        items.add(new Item(key, cell[0], cell[1]));
        save();
        return true;
    }

    /** Moves an item; if the target is taken, the two swap places. */
    void move(Item item, int x, int y) {
        Item other = at(x, y);
        if (other == item) return;
        if (other != null) {
            other.x = item.x;
            other.y = item.y;
        }
        item.x = x;
        item.y = y;
        save();
    }

    void remove(Item item) {
        items.remove(item);
        save();
    }

    /** Drops every cell holding this app (e.g. after uninstall confirms it's gone). */
    void removeKey(String key) {
        boolean changed = false;
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            if (it.next().key.equals(key)) { it.remove(); changed = true; }
        }
        if (changed) save();
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                arr.put(new JSONObject().put("key", it.key).put("x", it.x).put("y", it.y));
            }
            JSONObject root = new JSONObject()
                    .put("version", VERSION)
                    .put("columns", columns)
                    .put("rows", rows)
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
