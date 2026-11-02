/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * A list of every installed widget, grouped by app, with previews and sizes,
 * plus a search box. Picking one hands its provider info back.
 */
final class WidgetPicker {

    private final Activity activity;
    private final ToIntFunction<AppWidgetProviderInfo>[] spans; // {width, height} in cells
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @SafeVarargs
    WidgetPicker(Activity activity, ToIntFunction<AppWidgetProviderInfo>... spans) {
        this.activity = activity;
        this.spans = spans;
    }

    void show(Consumer<AppWidgetProviderInfo> onPick) {
        AppWidgetManager awm = AppWidgetManager.getInstance(activity);
        PackageManager pm = activity.getPackageManager();
        List<AppWidgetProviderInfo> all = new ArrayList<>();
        for (UserHandle user : activity.getSystemService(LauncherApps.class).getProfiles()) {
            for (AppWidgetProviderInfo info : awm.getInstalledProvidersForProfile(user)) {
                if ((info.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0) all.add(info);
            }
        }
        // Group by app name, apps alphabetically.
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Map<String, String> appNames = new LinkedHashMap<>();
        for (AppWidgetProviderInfo info : all) {
            String pkg = info.provider.getPackageName();
            if (appNames.containsKey(pkg)) continue;
            String name;
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                name = pkg;
            }
            appNames.put(pkg, name);
        }
        all.sort((a, b) -> {
            int c = collator.compare(appNames.get(a.provider.getPackageName()), appNames.get(b.provider.getPackageName()));
            return c != 0 ? c : collator.compare(a.loadLabel(pm), b.loadLabel(pm));
        });

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        EditText search = new EditText(activity);
        search.setHint(R.string.widget_search);
        search.setSingleLine(true);
        search.setTextColor(activity.getColor(R.color.text_primary));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(pad, dp(4), pad, 0);
        box.addView(search, slp);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, 0, pad, pad);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.widgets)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> previews.shutdownNow());

        Runnable populate = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            String lastPkg = null;
            int shown = 0;
            for (AppWidgetProviderInfo info : all) {
                String pkg = info.provider.getPackageName();
                String app = appNames.get(pkg);
                String label = info.loadLabel(pm);
                if (!q.isEmpty() && !app.toLowerCase(Locale.ROOT).contains(q)
                        && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (!pkg.equals(lastPkg)) {
                    lastPkg = pkg;
                    list.addView(header(app, info));
                }
                list.addView(row(info, label, v -> {
                    dialog.dismiss();
                    onPick.accept(info);
                }));
                shown++;
            }
            if (shown == 0) {
                TextView none = text(activity.getString(R.string.widget_none), 14, R.color.text_muted);
                none.setPadding(0, dp(16), 0, 0);
                list.addView(none);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { populate.run(); }
        });
        populate.run();
        dialog.show();
        // Tall enough to browse.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
    }

    private View header(String app, AppWidgetProviderInfo info) {
        LinearLayout h = new LinearLayout(activity);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, dp(20), 0, dp(6));
        ImageView icon = new ImageView(activity);
        h.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        load(icon, () -> info.loadIcon(activity, activity.getResources().getDisplayMetrics().densityDpi));
        TextView t = text(app, 15, R.color.text_primary);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(10), 0, 0, 0);
        h.addView(t);
        return h;
    }

    private View row(AppWidgetProviderInfo info, String label, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(8), dp(4), dp(8));
        r.setBackgroundResource(android.R.drawable.list_selector_background);
        ImageView preview = new ImageView(activity);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        r.addView(preview, new LinearLayout.LayoutParams(dp(120), dp(80)));
        int density = activity.getResources().getDisplayMetrics().densityDpi;
        load(preview, () -> {
            Drawable d = info.loadPreviewImage(activity, density);
            return d != null ? d : info.loadIcon(activity, density);
        });
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);
        texts.addView(text(label, 15, R.color.text_primary));
        String size = activity.getString(R.string.widget_size, spans[0].applyAsInt(info), spans[1].applyAsInt(info));
        texts.addView(text(size, 13, R.color.text_muted));
        CharSequence desc = info.loadDescription(activity);
        if (desc != null && desc.length() > 0) texts.addView(text(desc.toString(), 13, R.color.text_muted));
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(click);
        r.setContentDescription(label + ", " + size);
        return r;
    }

    // Previews can be large images; load them off the main thread.
    private void load(ImageView target, java.util.concurrent.Callable<Drawable> source) {
        if (previews.isShutdown()) return;
        previews.execute(() -> {
            Drawable d;
            try {
                d = source.call();
            } catch (Exception e) {
                d = null;
            }
            Drawable result = d;
            main.post(() -> { if (result != null) target.setImageDrawable(result); });
        });
    }

    private TextView text(String s, int sp, int colorRes) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(activity.getColor(colorRes));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
