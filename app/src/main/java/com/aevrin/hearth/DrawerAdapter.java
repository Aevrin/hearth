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
 * The app drawer grid, filtered by the search box. With no search it can lead
 * with a "Most used" row; hidden apps are left out of the list, and included in
 * search results when the user allows it. Section headers span the full width.
 */
final class DrawerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Actions {
        void onAppClick(AppEntry app, View view);
        void onAppLongClick(AppEntry app, View view);
    }

    private static final int TYPE_APP = 0;
    private static final int TYPE_HEADER = 1;

    private final AppRepository repo;
    private final Actions actions;
    private final Prefs prefs;

    private List<AppEntry> all = new ArrayList<>();
    /** AppEntry for apps, String for section headers. */
    private final List<Object> rows = new ArrayList<>();
    private String query = "";
    private Set<String> hidden = Collections.emptySet();
    private boolean hiddenSearchable = true;
    private boolean showLabels = true;
    private boolean mostUsed = true;
    private int columns = 5;
    private int iconPx;
    private String mostUsedTitle = "Most used", allTitle = "All apps";

    DrawerAdapter(AppRepository repo, Prefs prefs, Actions actions) {
        this.repo = repo;
        this.prefs = prefs;
        this.actions = actions;
    }

    void setTitles(String mostUsed, String all) {
        mostUsedTitle = mostUsed;
        allTitle = all;
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
        java.util.Comparator<AppEntry> az = (a, b) -> collator.compare(labelOf(a), labelOf(b));
        switch (prefs.drawerSort()) {
            case Prefs.SORT_MOST_USED: {
                java.util.Map<String, Integer> counts = prefs.launchCounts();
                sorted.sort(((java.util.Comparator<AppEntry>) (a, b) -> Integer.compare(
                        counts.getOrDefault(b.key, 0), counts.getOrDefault(a.key, 0))).thenComparing(az));
                break;
            }
            case Prefs.SORT_RECENT:
                sorted.sort(((java.util.Comparator<AppEntry>) (a, b) ->
                        Long.compare(b.installTime, a.installTime)).thenComparing(az));
                break;
            default:
                sorted.sort(az);
                break;
        }
        all = sorted;
        rebuild();
    }

    /** Re-reads hidden apps, labels, sizes and the most-used row from settings. */
    void applyPrefs(int iconPx, int columns) {
        this.hidden = prefs.hidden();
        this.hiddenSearchable = prefs.hiddenSearchable();
        this.showLabels = prefs.drawerLabels();
        this.mostUsed = prefs.mostUsed();
        this.iconPx = iconPx;
        this.columns = columns;
        setApps(all);
    }

    void setQuery(String q) {
        query = fold(q.trim());
        rebuild();
    }

    boolean isSearching() { return !query.isEmpty(); }

    boolean isHeader(int position) {
        return position >= 0 && position < rows.size() && rows.get(position) instanceof String;
    }

    /** The first match, for launching straight from the keyboard's Go key. */
    AppEntry first() {
        for (Object o : rows) if (o instanceof AppEntry) return (AppEntry) o;
        return null;
    }

    /** First letters present in the full list, in order, for the A–Z strip. */
    List<String> letters() {
        List<String> out = new ArrayList<>();
        if (prefs.drawerSort() != Prefs.SORT_AZ) return out; // letters only follow an A–Z list
        for (Object o : rows) {
            if (!(o instanceof AppEntry)) continue;
            String l = letterOf((AppEntry) o);
            if (out.isEmpty() || !out.get(out.size() - 1).equals(l)) {
                if (!out.contains(l)) out.add(l);
            }
        }
        return out;
    }

    /** Position of the first app under a letter, after the most-used section. */
    int positionFor(String letter) {
        int start = 0;
        for (int i = 0; i < rows.size(); i++) if (allTitle.equals(rows.get(i))) start = i;
        for (int i = start; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (o instanceof AppEntry && letterOf((AppEntry) o).equals(letter)) return i;
        }
        return -1;
    }

    private String letterOf(AppEntry e) {
        String n = fold(labelOf(e));
        if (n.isEmpty()) return "#";
        char c = Character.toUpperCase(n.charAt(0));
        return Character.isLetter(c) ? String.valueOf(c) : "#";
    }

    @SuppressWarnings("NotifyDataSetChanged") // the whole list changes on every keystroke anyway
    private void rebuild() {
        rows.clear();
        if (query.isEmpty()) {
            List<AppEntry> visible = new ArrayList<>();
            for (AppEntry e : all) if (!hidden.contains(e.key)) visible.add(e);
            List<AppEntry> top = new ArrayList<>();
            if (mostUsed) {
                for (String key : prefs.mostLaunched(columns, 2)) {
                    AppEntry e = repo.find(key);
                    if (e != null && !hidden.contains(e.key)) top.add(e);
                }
            }
            // The "Most used" row only makes sense on top of an alphabetical list.
            if (!top.isEmpty() && visible.size() > columns * 2 && prefs.drawerSort() == Prefs.SORT_AZ) {
                rows.add(mostUsedTitle);
                rows.addAll(top);
                rows.add(allTitle);
            }
            rows.addAll(visible);
        } else {
            // Names starting with the query first, then names containing it.
            List<AppEntry> starts = new ArrayList<>();
            List<AppEntry> contains = new ArrayList<>();
            for (AppEntry e : all) {
                if (hidden.contains(e.key) && !hiddenSearchable) continue;
                String name = fold(labelOf(e));
                if (name.startsWith(query)) starts.add(e);
                else if (name.contains(query) || fold(e.label).contains(query)) contains.add(e);
            }
            rows.addAll(starts);
            rows.addAll(contains);
        }
        notifyDataSetChanged();
    }

    // Case- and accent-insensitive matching: "cafe" finds "Café".
    private static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    @Override
    public int getItemViewType(int position) {
        return isHeader(position) ? TYPE_HEADER : TYPE_APP;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            TextView t = new TextView(parent.getContext());
            float d = parent.getResources().getDisplayMetrics().density;
            t.setPadding(Math.round(12 * d), Math.round(14 * d), 0, Math.round(6 * d));
            t.setTextSize(13);
            t.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(t) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_icon, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
        Object row = rows.get(position);
        if (row instanceof String) {
            TextView t = (TextView) vh.itemView;
            t.setText((String) row);
            prefs.accentStyle().paint(t);
            return;
        }
        Holder h = (Holder) vh;
        AppEntry e = (AppEntry) row;
        String name = labelOf(e);
        h.app = e;
        h.label.setText(name);
        prefs.styleLabel(h.label);
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
        // A small accent dot on apps installed in the last few days and not opened yet.
        h.icon.getOverlay().clear();
        if (prefs.isNew(e)) {
            int size = Math.max(1, iconPx > 0 ? iconPx / 5 : 18), px = iconPx > 0 ? iconPx : 96;
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(prefs.accent());
            dot.setStroke(Math.max(1, size / 6), 0xFF0F1A2A);
            dot.setBounds(px - size, 0, px, size);
            h.icon.getOverlay().add(dot);
        }
        h.itemView.setOnClickListener(v -> actions.onAppClick(e, v));
        h.itemView.setOnLongClickListener(v -> { actions.onAppLongClick(e, v); return true; });
    }

    @Override
    public int getItemCount() {
        return rows.size();
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
