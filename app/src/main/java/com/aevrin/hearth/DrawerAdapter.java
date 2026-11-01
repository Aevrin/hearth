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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The app drawer grid, filtered by the search box. */
final class DrawerAdapter extends RecyclerView.Adapter<DrawerAdapter.Holder> {

    interface Actions {
        void onAppClick(AppEntry app, View view);
        void onAppLongClick(AppEntry app, View view);
    }

    private final AppRepository repo;
    private final Actions actions;
    private List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private String query = "";

    DrawerAdapter(AppRepository repo, Actions actions) {
        this.repo = repo;
        this.actions = actions;
    }

    void setApps(List<AppEntry> apps) {
        all = apps;
        refilter();
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
            shown.addAll(all);
        } else {
            // Names starting with the query first, then names containing it.
            List<AppEntry> contains = new ArrayList<>();
            for (AppEntry e : all) {
                String name = fold(e.label);
                if (name.startsWith(query)) shown.add(e);
                else if (name.contains(query)) contains.add(e);
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
        h.app = e;
        h.label.setText(e.label);
        h.itemView.setContentDescription(e.label);
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
