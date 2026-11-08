/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.view.View;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Things a gesture or a sidebar can open, Nova-style: an app ("app:<key>"), a
 * specific activity inside an app ("activity:<app key>|<class>"), or an app
 * shortcut (its pinned-shortcut key, "shortcut:..."). Labels, icons, launching
 * and the picker live here so the home screen and Settings agree.
 */
final class Targets {

    static final String APP = Prefs.G_APP_PREFIX;
    static final String ACTIVITY = "activity:";

    private Targets() { }

    static boolean isTarget(String spec) {
        return spec != null && (spec.startsWith(APP) || spec.startsWith(ACTIVITY) || Shortcuts.isShortcutKey(spec));
    }

    /**
     * The package and profile serial behind an app key ("pkg/.Activity#serial"),
     * a target or a shortcut key, as {pkg, serial}; null if it can't be read.
     */
    static String[] packageOf(String key) {
        if (key == null) return null;
        if (Shortcuts.isShortcutKey(key)) {
            String[] p = Shortcuts.parse(key);
            return p == null ? null : new String[] {p[1], p[0]};
        }
        String app = isTarget(key) ? appKeyOf(key) : key;
        if (app == null) return null;
        int slash = app.indexOf('/'), hash = app.lastIndexOf('#');
        if (slash <= 0 || hash < slash) return null;
        return new String[] {app.substring(0, slash), app.substring(hash + 1)};
    }

    private static String appKeyOf(String spec) {
        if (spec.startsWith(APP)) return spec.substring(APP.length());
        if (spec.startsWith(ACTIVITY)) {
            String rest = spec.substring(ACTIVITY.length());
            int bar = rest.indexOf('|');
            return bar < 0 ? rest : rest.substring(0, bar);
        }
        return null;
    }

    private static String activityClassOf(String spec) {
        String rest = spec.substring(ACTIVITY.length());
        int bar = rest.indexOf('|');
        return bar < 0 ? null : rest.substring(bar + 1);
    }

    static String appName(Prefs prefs, AppEntry e) {
        String c = prefs.customLabel(e.key);
        return c != null ? c : e.label;
    }

    /** A short name for the target, or null if it no longer exists. */
    static String label(Activity a, AppRepository repo, Prefs prefs, String spec) {
        if (Shortcuts.isShortcutKey(spec)) {
            ShortcutInfo si = repo.findShortcut(spec);
            return si == null ? null : repo.shortcutLabel(si);
        }
        AppEntry app = repo.find(appKeyOf(spec));
        if (app == null) return null;
        if (spec.startsWith(APP)) return appName(prefs, app);
        ActivityInfo ai = activityInfo(a, app, activityClassOf(spec));
        return ai == null ? null : ai.loadLabel(a.getPackageManager()).toString();
    }

    /** The target's icon: an app's (themed) icon, an activity's app icon, or the shortcut's. */
    static void loadIcon(AppRepository repo, String spec, Consumer<Drawable> cb) {
        if (Shortcuts.isShortcutKey(spec)) {
            ShortcutInfo si = repo.findShortcut(spec);
            if (si != null) repo.loadShortcutIcon(spec, si, cb);
            return;
        }
        AppEntry app = repo.find(appKeyOf(spec));
        if (app != null) repo.loadIcon(app, cb);
    }

    /** Opens the target. Returns false (after saying so) if it can't. */
    static boolean launch(Activity a, AppRepository repo, Prefs prefs, String spec, View from) {
        if (Shortcuts.isShortcutKey(spec)) {
            ShortcutInfo si = repo.findShortcut(spec);
            if (si == null) return missing(a);
            repo.launchShortcut(si, from);
            return true;
        }
        AppEntry app = repo.find(appKeyOf(spec));
        if (app == null) return missing(a);
        if (spec.startsWith(APP)) {
            prefs.recordLaunch(app.key);
            prefs.markSeen(app);
            repo.launch(app, from);
            return true;
        }
        Intent i = new Intent().setComponent(new ComponentName(app.packageName(), activityClassOf(spec)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Rect r = new Rect();
        from.getGlobalVisibleRect(r);
        try {
            a.startActivity(i, ActivityOptions.makeClipRevealAnimation(from, 0, 0, r.width(), r.height()).toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, R.string.target_cant_open, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static boolean missing(Activity a) {
        Toast.makeText(a, R.string.gesture_app_missing, Toast.LENGTH_SHORT).show();
        return false;
    }

    private static ActivityInfo activityInfo(Activity a, AppEntry app, String cls) {
        if (cls == null) return null;
        for (ActivityInfo ai : activities(a, app)) if (ai.name.equals(cls)) return ai;
        return null;
    }

    /** An app's activities that other apps are allowed to open. */
    private static List<ActivityInfo> activities(Activity a, AppEntry app) {
        List<ActivityInfo> out = new ArrayList<>();
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(app.packageName(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES));
            if (pi.activities != null) {
                for (ActivityInfo ai : pi.activities) if (ai.exported && ai.enabled) out.add(ai);
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        return out;
    }

    /* ---------- picker ---------- */

    /** Asks what to open: an app, an activity in an app, or an app shortcut. */
    static void pick(Activity a, AppRepository repo, Prefs prefs, Consumer<String> done) {
        String[] kinds = {a.getString(R.string.target_app), a.getString(R.string.target_activity),
                a.getString(R.string.target_shortcut)};
        new AlertDialog.Builder(a)
                .setTitle(R.string.target_pick)
                .setItems(kinds, (d, which) -> pickApp(a, repo, prefs, which == 0 ? R.string.target_app
                        : which == 1 ? R.string.target_activity : R.string.target_shortcut, app -> {
                    if (which == 0) done.accept(APP + app.key);
                    else if (which == 1) pickActivity(a, prefs, app, done);
                    else pickShortcut(a, repo, app, done);
                }, which == 1))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void pickApp(Activity a, AppRepository repo, Prefs prefs, int title, Consumer<AppEntry> then,
                                boolean personalOnly) {
        List<AppEntry> apps = new ArrayList<>();
        for (AppEntry e : repo.apps()) {
            // Activities in a work profile can't be opened directly; only its apps can.
            if (!personalOnly || e.user.equals(Process.myUserHandle())) apps.add(e);
        }
        Collator c = Collator.getInstance();
        c.setStrength(Collator.PRIMARY);
        apps.sort((x, y) -> c.compare(appName(prefs, x), appName(prefs, y)));
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = appName(prefs, apps.get(i));
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setItems(names, (d, i) -> then.accept(apps.get(i)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void pickActivity(Activity a, Prefs prefs, AppEntry app, Consumer<String> done) {
        List<ActivityInfo> acts = activities(a, app);
        if (acts.isEmpty()) {
            Toast.makeText(a, R.string.target_no_activities, Toast.LENGTH_SHORT).show();
            return;
        }
        PackageManager pm = a.getPackageManager();
        Collator c = Collator.getInstance();
        acts.sort((x, y) -> c.compare(x.loadLabel(pm).toString(), y.loadLabel(pm).toString()));
        String[] names = new String[acts.size()];
        for (int i = 0; i < acts.size(); i++) {
            ActivityInfo ai = acts.get(i);
            String cls = ai.name.startsWith(app.packageName()) ? ai.name.substring(app.packageName().length()) : ai.name;
            names[i] = ai.loadLabel(pm) + "\n" + cls; // two activities often share a label
        }
        new AlertDialog.Builder(a)
                .setTitle(appName(prefs, app))
                .setItems(names, (d, i) -> done.accept(ACTIVITY + app.key + "|" + acts.get(i).name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Shortcuts have to be pinned to Hearth to be opened later; Hearth unpins them
    // again when nothing uses them any more.
    private static void pickShortcut(Activity a, AppRepository repo, AppEntry app, Consumer<String> done) {
        List<ShortcutInfo> list = repo.allShortcuts(app);
        if (list.isEmpty()) {
            Toast.makeText(a, R.string.target_no_shortcuts, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = repo.shortcutLabel(list.get(i));
        new AlertDialog.Builder(a)
                .setTitle(Targets.appName(new Prefs(a), app))
                .setItems(names, (d, i) -> {
                    ShortcutInfo si = list.get(i);
                    repo.pinShortcut(si);
                    done.accept(repo.shortcutKey(si));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
