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

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final LauncherApps.Callback packageCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackageAdded(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { changed(pkgs); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { changed(pkgs); }
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
            main.post(() -> {
                apps = Collections.unmodifiableList(list);
                byKey = map;
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

    /** Calls back with the app's icon (badged for work-profile apps), now or once loaded. */
    void loadIcon(AppEntry e, Consumer<Drawable> callback) {
        Drawable cached = icons.get(e.key);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        worker.execute(() -> {
            Drawable d = e.info.getBadgedIcon(0);
            main.post(() -> {
                icons.put(e.key, d);
                callback.accept(d);
            });
        });
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
