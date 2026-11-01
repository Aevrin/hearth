/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The app drawer grid, filtered by the search box. Hidden apps are left out of
 * the list, and included in search results when the user allows it.
 */
final class DrawerAdapter extends RecyclerView.Adapter<DrawerAdapter.Holder> {

    interface Actions {
        void onAppClick(AppEntry app, View view);
        void onAppLongClick(AppEntry app, View view);
    }

    private final AppRepository repo;
    private final Actions actions;
    private final Prefs prefs;

    private List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private String query = "";
    private Set<String> hidden = Collections.emptySet();
    private boolean hiddenSearchable = true;
    private boolean showLabels = true;
    private int iconPx;

    DrawerAdapter(AppRepository repo, Prefs prefs, Actions actions) {
        this.repo = repo;
        this.prefs = prefs;
        this.actions = actions;
    }

    /** The name shown for an app: the user's rename if any, else its own label. */
    String labelOf(AppEntry e) {
        String custom = prefs.customLabel(e.key);
        return custom != null ? custom : e.label;
    }

    void setApps(List<AppEntry> apps) {
        // Re-sort, since renamed apps may land somewhere else alphabetically.
        List<AppEntry> sorted = new ArrayList<>(apps);
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        sorted.sort((a, b) -> collator.compare(labelOf(a), labelOf(b)));
        all = sorted;
        refilter();
    }

    /** Re-reads hidden apps, labels and sizes from settings. */
    void applyPrefs(int iconPx) {
        this.hidden = prefs.hidden();
        this.hiddenSearchable = prefs.hiddenSearchable();
        this.showLabels = prefs.drawerLabels();
        this.iconPx = iconPx;
        setApps(all);
    }

    void setQuery(String q) {
        query = fold(q.trim());
        refilter();
    }

    /** The first match, for launching straight from the keyboard's Go key. */
    AppEntry first() {
        return shown.isEmpty() ? null : shown.get(0);
    }

    @SuppressWarnings("NotifyDataSetChanged") // the whole list changes on every keystroke anyway
    private void refilter() {
        shown.clear();
        if (query.isEmpty()) {
            for (AppEntry e : all) if (!hidden.contains(e.key)) shown.add(e);
        } else {
            // Names starting with the query first, then names containing it.
            List<AppEntry> contains = new ArrayList<>();
            for (AppEntry e : all) {
                if (hidden.contains(e.key) && !hiddenSearchable) continue;
                String name = fold(labelOf(e));
                if (name.startsWith(query)) shown.add(e);
                else if (name.contains(query) || fold(e.label).contains(query)) contains.add(e);
            }
            shown.addAll(contains);
        }
        notifyDataSetChanged();
    }

    // Case- and accent-insensitive matching: "cafe" finds "Café".
    private static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_icon, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        AppEntry e = shown.get(position);
        String name = labelOf(e);
        h.app = e;
        h.label.setText(name);
        h.label.setVisibility(showLabels ? View.VISIBLE : View.GONE);
        h.itemView.setContentDescription(name);
        if (iconPx > 0) {
            ViewGroup.LayoutParams lp = h.icon.getLayoutParams();
            lp.width = iconPx;
            lp.height = iconPx;
            h.icon.setLayoutParams(lp);
        }
        h.icon.setImageDrawable(null);
        repo.loadIcon(e, d -> { if (h.app == e) h.icon.setImageDrawable(d); });
        h.itemView.setOnClickListener(v -> actions.onAppClick(e, v));
        h.itemView.setOnLongClickListener(v -> { actions.onAppLongClick(e, v); return true; });
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        AppEntry app;
        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
        }
    }
}
