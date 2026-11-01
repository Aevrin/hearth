/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Settings and home layout in one readable JSON file, written to and read from
 * wherever the user picks (Downloads, a cloud folder, a USB stick…).
 *
 * <pre>
 * { "app": "hearth", "backup_version": 1,
 *   "settings": { ...Prefs.toJson()... },
 *   "layout":   { ...files/home.json... } }
 * </pre>
 */
final class Backup {

    private static final int BACKUP_VERSION = 1;
    private static final int MAX_BYTES = 1 << 20; // a real backup is a few KB

    private Backup() { }

    static void export(Context c, Uri uri) throws IOException, JSONException {
        JSONObject root = new JSONObject()
                .put("app", "hearth")
                .put("backup_version", BACKUP_VERSION)
                .put("settings", new Prefs(c).toJson());
        File home = HomeLayout.fileIn(c.getFilesDir());
        if (home.isFile()) {
            root.put("layout", new JSONObject(new String(Files.readAllBytes(home.toPath()), StandardCharsets.UTF_8)));
        }
        try (OutputStream out = c.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("cannot open destination");
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Replaces settings and layout with the backup's. Throws if it isn't a Hearth backup. */
    static void restore(Context c, Uri uri) throws IOException, JSONException {
        JSONObject root;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open file");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BYTES) throw new IOException("file too large to be a Hearth backup");
            }
            root = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
        }
        if (!"hearth".equals(root.optString("app"))) throw new JSONException("not a Hearth backup");

        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) new Prefs(c).fromJson(settings);

        JSONObject layout = root.optJSONObject("layout");
        if (layout != null) {
            layout.getJSONArray("items"); // must at least have items
            HomeLayout.writeAtomically(HomeLayout.fileIn(c.getFilesDir()), layout.toString(2));
        }
    }
}
