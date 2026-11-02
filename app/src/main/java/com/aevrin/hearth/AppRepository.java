/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Every launchable app across the user's profiles, kept current as apps are
 * installed, updated or removed. Icons load off the main thread and are cached.
 */
final class AppRepository {

    interface Listener {
        void onAppsChanged();
    }

    private final Context context;
    private final Listener listener;
    private final LauncherApps launcherApps;
    private final UserManager userManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private List<AppEntry> apps = Collections.emptyList();
    private Map<String, AppEntry> byKey = Collections.emptyMap();
    private final Map<String, Drawable> icons = new HashMap<>();
    private boolean loaded;
    private IconTheme theme;
    private java.util.function.Function<AppEntry, String> labeler = e -> e.label;
    private int themeGeneration; // icons rendered under an older theme are discarded
    private Map<String, ShortcutInfo> pinned = Collections.emptyMap(); // shortcuts this launcher has pinned

    private final LauncherApps.Callback packageCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackageAdded(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { changed(pkgs); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { changed(pkgs); }
        @Override public void onShortcutsChanged(String pkg, List<ShortcutInfo> shortcuts, UserHandle user) {
            icons.keySet().removeIf(k -> k.startsWith(Shortcuts.PREFIX) && k.contains(":" + pkg + "/"));
            reload();
        }
    };

    AppRepository(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.launcherApps = context.getSystemService(LauncherApps.class);
        this.userManager = context.getSystemService(UserManager.class);
        launcherApps.registerCallback(packageCallback, main);
    }

    void destroy() {
        launcherApps.unregisterCallback(packageCallback);
        worker.shutdownNow();
    }

    /** False until the first scan finishes; a missing app before then isn't really missing. */
    boolean isLoaded() { return loaded; }

    List<AppEntry> apps() { return apps; }

    AppEntry find(String key) { return byKey.get(key); }

    /** Rescans every profile in the background, then notifies on the main thread. */
    void reload() {
        worker.execute(() -> {
            String self = context.getPackageName();
            List<AppEntry> list = new ArrayList<>();
            for (UserHandle user : userManager.getUserProfiles()) {
                long serial = userManager.getSerialNumberForUser(user);
                for (LauncherActivityInfo info : launcherApps.getActivityList(null, user)) {
                    if (self.equals(info.getComponentName().getPackageName())) continue;
                    list.add(new AppEntry(info, serial));
                }
            }
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY); // ignore case and accents when sorting
            list.sort((a, b) -> collator.compare(a.label, b.label));

            Map<String, AppEntry> map = new HashMap<>();
            for (AppEntry e : list) map.put(e.key, e);

            // Shortcuts this launcher has pinned (websites, contacts…). Only the
            // default home app may read them; otherwise there are simply none.
            Map<String, ShortcutInfo> pins = new HashMap<>();
            if (hasShortcutAccess()) {
                for (UserHandle user : userManager.getUserProfiles()) {
                    long serial = userManager.getSerialNumberForUser(user);
                    try {
                        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                        List<ShortcutInfo> got = launcherApps.getShortcuts(q, user);
                        if (got != null) for (ShortcutInfo si : got) pins.put(Shortcuts.keyFor(si, serial), si);
                    } catch (SecurityException | IllegalStateException ignored) {
                        // profile locked or not allowed right now
                    }
                }
            }
            main.post(() -> {
                apps = Collections.unmodifiableList(list);
                byKey = map;
                pinned = pins;
                loaded = true;
                listener.onAppsChanged();
            });
        });
    }

    private void changed(String... packages) {
        for (String p : packages) {
            icons.keySet().removeIf(k -> k.startsWith(p + "/"));
        }
        reload();
    }

    /** Switches icon theme; a different theme clears the icon cache so icons re-render. */
    void setTheme(IconTheme t) {
        if (theme != null && theme.signature.equals(t.signature)) return;
        boolean replacing = theme != null;
        theme = t;
        themeGeneration++;
        icons.clear();
        if (replacing) worker.execute(this::pruneDiskCache); // old renders won't be used again
    }

    /* ---------- shortcuts ---------- */

    boolean hasShortcutAccess() {
        try {
            return launcherApps.hasShortcutHostPermission();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    ShortcutInfo findShortcut(String key) { return pinned.get(key); }

    /** An app's own shortcuts (manifest + dynamic), best first, at most four. */
    List<ShortcutInfo> appShortcuts(AppEntry e) {
        List<ShortcutInfo> all = allShortcuts(e);
        return all.size() > 4 ? all.subList(0, 4) : all;
    }

    /** Every enabled shortcut the app offers (static and dynamic), in the app's order. */
    List<ShortcutInfo> allShortcuts(AppEntry e) {
        if (!hasShortcutAccess()) return Collections.emptyList();
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                    .setPackage(e.packageName())
                    .setActivity(e.component)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
            List<ShortcutInfo> got = launcherApps.getShortcuts(q, e.user);
            if (got == null) return Collections.emptyList();
            List<ShortcutInfo> out = new ArrayList<>();
            for (ShortcutInfo si : got) if (si.isEnabled()) out.add(si);
            // Manifest (static) shortcuts first, then by the app's own ranking.
            out.sort((a, b) -> a.isDeclaredInManifest() != b.isDeclaredInManifest()
                    ? (a.isDeclaredInManifest() ? -1 : 1) : Integer.compare(a.getRank(), b.getRank()));
            return out;
        } catch (SecurityException | IllegalStateException ex) {
            return Collections.emptyList();
        }
    }

    String shortcutKey(ShortcutInfo si) {
        return Shortcuts.keyFor(si, userManager.getSerialNumberForUser(si.getUserHandle()));
    }

    String shortcutLabel(ShortcutInfo si) {
        CharSequence l = si.getShortLabel();
        if (l == null || l.length() == 0) l = si.getLongLabel();
        return l == null ? si.getId() : l.toString();
    }

    void launchShortcut(ShortcutInfo si, View from) {
        Rect bounds = new Rect();
        from.getGlobalVisibleRect(bounds);
        ActivityOptions opts = ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.getWidth(), from.getHeight());
        try {
            launcherApps.startShortcut(si, bounds, opts.toBundle());
        } catch (SecurityException | IllegalStateException | ActivityNotFoundException ex) {
            Toast.makeText(context, R.string.cannot_launch, Toast.LENGTH_SHORT).show();
        }
    }

    /** Keeps a shortcut pinned for this launcher (needed before it can sit on the home screen). */
    void pinShortcut(ShortcutInfo si) {
        Set<String> ids = new java.util.HashSet<>();
        for (ShortcutInfo p : pinned.values()) {
            if (p.getPackage().equals(si.getPackage()) && p.getUserHandle().equals(si.getUserHandle())) ids.add(p.getId());
        }
        ids.add(si.getId());
        try {
            launcherApps.pinShortcuts(si.getPackage(), new ArrayList<>(ids), si.getUserHandle());
        } catch (SecurityException | IllegalStateException ignored) { }
        reload();
    }

    /**
     * Unpins shortcuts that are no longer anywhere on the home screen, so apps
     * know they're gone (Android keeps them pinned until the launcher says so).
     */
    void syncPins(Set<String> layoutKeys) {
        if (!hasShortcutAccess()) return;
        Map<String, List<String>> keep = new HashMap<>();   // "pkg|serial" -> ids still used
        Map<String, ShortcutInfo> sample = new HashMap<>();
        boolean changed = false;
        for (Map.Entry<String, ShortcutInfo> en : pinned.entrySet()) {
            ShortcutInfo si = en.getValue();
            String group = si.getPackage() + "|" + si.getUserHandle().hashCode();
            sample.put(group, si);
            keep.computeIfAbsent(group, g -> new ArrayList<>());
            if (layoutKeys.contains(en.getKey())) keep.get(group).add(si.getId());
            else changed = true;
        }
        if (!changed) return;
        for (Map.Entry<String, List<String>> en : keep.entrySet()) {
            ShortcutInfo si = sample.get(en.getKey());
            try {
                launcherApps.pinShortcuts(si.getPackage(), en.getValue(), si.getUserHandle());
            } catch (SecurityException | IllegalStateException ignored) { }
        }
        reload();
    }

    /** A shortcut's own icon, cached like app icons. */
    void loadShortcutIcon(String key, ShortcutInfo si, Consumer<Drawable> callback) {
        Drawable cached = icons.get(key);
        if (cached != null) {
            callback.accept(copyOf(cached));
            return;
        }
        int generation = themeGeneration;
        int density = context.getResources().getDisplayMetrics().densityDpi;
        worker.execute(() -> {
            Drawable d;
            try {
                d = launcherApps.getShortcutBadgedIconDrawable(si, density);
            } catch (SecurityException | IllegalStateException ex) {
                d = null;
            }
            if (d == null) d = context.getPackageManager().getDefaultActivityIcon();
            Drawable result = d;
            main.post(() -> {
                if (generation != themeGeneration) return;
                icons.put(key, result);
                callback.accept(copyOf(result));
            });
        });
    }

    IconTheme theme() { return theme; }

    /** Supplies display names (custom labels) for letter icons. */
    void setLabeler(java.util.function.Function<AppEntry, String> labeler) {
        this.labeler = labeler;
    }

    /** Calls back with the app's icon (themed, and badged for work-profile apps), now or once loaded. */
    void loadIcon(AppEntry e, Consumer<Drawable> callback) {
        Drawable cached = icons.get(e.key);
        if (cached != null) {
            callback.accept(copyOf(cached));
            return;
        }
        IconTheme t = theme;
        int generation = themeGeneration;
        String label = labeler.apply(e);
        worker.execute(() -> {
            Drawable d = renderIcon(e, t, label);
            main.post(() -> {
                if (generation != themeGeneration) return; // theme changed meanwhile
                icons.put(e.key, d);
                callback.accept(copyOf(d));
            });
        });
    }

    /**
     * Each view gets its own drawable sharing the same pixels. A drawable carries
     * its own bounds and callback, so one instance shared between the grid, the
     * drawer and a folder's mini icons would be resized out from under the others.
     */
    private Drawable copyOf(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(context.getResources()).mutate() : d;
    }

    private Drawable renderIcon(AppEntry e, IconTheme t, String label) {
        String style = t == null ? IconTheme.ORIGINAL : t.styleFor(e.key);
        if (t == null || t.isPassThrough(style)) return e.info.getBadgedIcon(0);
        try {
            Bitmap bmp = readCached(e, t, style, label);
            if (bmp == null) {
                bmp = t.render(e.info.getIcon(0), style, label);
                writeCached(e, t, style, label, bmp);
            }
            Drawable d = new BitmapDrawable(context.getResources(), bmp);
            if (!Process.myUserHandle().equals(e.user)) {
                d = context.getPackageManager().getUserBadgedIcon(d, e.user); // work-profile briefcase
            }
            return d;
        } catch (RuntimeException ex) {
            return e.info.getBadgedIcon(0); // never lose an icon to a rendering bug
        }
    }

    /* ---------- disk cache for generated icons ---------- */

    // Rendering every icon again after each launcher restart is the slow part of
    // startup; finished icons are kept as PNGs in the app's cache folder. The file
    // name covers the app, its installed version (via its APK path, which changes
    // on every update), the theme and the style, so stale files are never read.
    private File cacheDir() {
        return new File(context.getCacheDir(), "icons");
    }

    private File cacheFile(AppEntry e, IconTheme t, String style, String label) {
        String apk = e.info.getApplicationInfo() == null ? "" : String.valueOf(e.info.getApplicationInfo().sourceDir);
        String id = e.key + "|" + apk + "|" + t.signature + "|" + style
                + (IconTheme.LETTER.equals(style) ? "|" + label : "");
        return new File(cacheDir(), sha1(id) + ".png");
    }

    private Bitmap readCached(AppEntry e, IconTheme t, String style, String label) {
        File f = cacheFile(e, t, style, label);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private void writeCached(AppEntry e, IconTheme t, String style, String label, Bitmap bmp) {
        File dir = cacheDir();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File f = cacheFile(e, t, style, label);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (java.io.IOException ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private void pruneDiskCache() {
        File[] files = cacheDir().listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 60_000; // keep anything just written for the new theme
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return Integer.toHexString(s.hashCode());
        }
    }

    void launch(AppEntry e, View from) {
        Rect bounds = new Rect();
        from.getGlobalVisibleRect(bounds);
        ActivityOptions opts = ActivityOptions.makeClipRevealAnimation(
                from, 0, 0, from.getWidth(), from.getHeight());
        try {
            launcherApps.startMainActivity(e.component, e.user, bounds, opts.toBundle());
        } catch (SecurityException | ActivityNotFoundException ex) {
            Toast.makeText(context, R.string.cannot_launch, Toast.LENGTH_SHORT).show();
        }
    }

    void openAppInfo(AppEntry e, View from) {
        Rect bounds = new Rect();
        from.getGlobalVisibleRect(bounds);
        launcherApps.startAppDetailsActivity(e.component, e.user, bounds, null);
    }

    /** Only for the personal profile; work apps are managed by their admin. */
    boolean canUninstall(AppEntry e) {
        return Process.myUserHandle().equals(e.user);
    }

    void uninstall(Context activity, AppEntry e) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.packageName(), null));
        try {
            activity.startActivity(i);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(context, R.string.cannot_uninstall, Toast.LENGTH_SHORT).show();
        }
    }
}
