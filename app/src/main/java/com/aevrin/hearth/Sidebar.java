/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.List;

/**
 * A sidebar on the left or right: a column of apps, activities and shortcuts,
 * dragged in from the screen edge (it follows the finger) or opened by a gesture.
 * Tap outside, swipe it back or press Back to close; "+" adds entries.
 */
final class Sidebar {

    interface Host {
        AppRepository repo();
        Prefs prefs();
        int sidebarIconPx();
        /** Opens a target; returns whether it opened. */
        boolean launchTarget(String spec, View from);
        /** Entries changed (shortcut pins may need updating). */
        void sidebarChanged();
    }

    static final int NONE = 0, LEFT = 1, RIGHT = 2;

    private final Activity activity;
    private final Host host;
    private final View layer, scrim;
    private final LinearLayout panel, list;
    private final ImageView add;
    private int side = NONE;
    private float fraction; // 0 closed … 1 open
    private ValueAnimator anim;
    private int insetTop, insetBottom;
    private final OnBackInvokedCallback back = this::close;
    private boolean backRegistered;
    private boolean light; // a light background: dark text and icons on it

    Sidebar(Activity activity, Host host, View layer, View scrim, LinearLayout panel, LinearLayout list, ImageView add) {
        this.activity = activity;
        this.host = host;
        this.layer = layer;
        this.scrim = scrim;
        this.panel = panel;
        this.list = list;
        this.add = add;
        scrim.setOnClickListener(v -> close());
        add.setOnClickListener(v -> addEntry());
    }

    static String key(int side) {
        return side == RIGHT ? Prefs.SIDEBAR_RIGHT : Prefs.SIDEBAR_LEFT;
    }

    boolean isOpen() { return side != NONE; }
    int side() { return side; }

    void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        panel.setPadding(0, top, 0, bottom);
    }

    /** Opens with an animation (from a gesture). */
    void open(int s) {
        if (side != NONE && side != s) finishClose();
        if (side == NONE) prepare(s);
        animateTo(1f);
    }

    /** Starts following a finger dragging in from the edge. */
    void beginDrag(int s) {
        if (anim != null) anim.cancel();
        if (side != s) {
            if (side != NONE) finishClose();
            prepare(s);
        }
    }

    /** `dx` is how far the finger has come in from the edge, in pixels. */
    void dragTo(float dx) {
        setFraction(Math.max(0f, Math.min(1f, dx / Math.max(1f, width()))));
    }

    /** Let go: stays open past a third of the way, or with a quick flick. */
    void endDrag(boolean flick) {
        animateTo(fraction > 0.33f || flick ? 1f : 0f);
    }

    void close() {
        if (side == NONE) return;
        animateTo(0f);
    }

    /** Closed at once, no animation (leaving the home screen). */
    void closeNow() {
        if (side == NONE) return;
        if (anim != null) anim.cancel();
        finishClose();
    }

    private float width() {
        return panel.getWidth() > 0 ? panel.getWidth() : 104 * activity.getResources().getDisplayMetrics().density;
    }

    private void prepare(int s) {
        side = s;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = s == RIGHT ? Gravity.END : Gravity.START;
        panel.setLayoutParams(lp);
        float r = 24 * activity.getResources().getDisplayMetrics().density;
        // Its own background: the theme's, a colour or a gradient, at its own opacity.
        Prefs prefs = host.prefs();
        String k = key(s);
        Accent fill = prefs.sidebarFill(k);
        int opacity = prefs.sidebarOpacity(k);
        GradientDrawable bg;
        if (fill == null) {
            bg = new GradientDrawable();
            bg.setColor(Prefs.background(opacity));
            light = false;
        } else {
            bg = fill.drawable();
            bg.setAlpha(Math.round(opacity * 2.55f));
            light = opacity > 45 && android.graphics.Color.luminance(fill.solid()) > 0.55f;
        }
        // Rounded on the inner side only.
        bg.setCornerRadii(s == RIGHT ? new float[] {r, r, 0, 0, 0, 0, r, r} : new float[] {0, 0, r, r, r, r, 0, 0});
        panel.setBackground(bg);
        populate();
        layer.setVisibility(View.VISIBLE);
        setFraction(0f);
        if (!backRegistered) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
            backRegistered = true;
        }
    }

    private void setFraction(float f) {
        fraction = f;
        float off = (1f - f) * width();
        panel.setTranslationX(side == RIGHT ? off : -off);
        scrim.setAlpha(f);
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(fraction, target);
        anim.setDuration(Math.round(220 * Math.abs(target - fraction)) + 60);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> setFraction((float) a.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && target == 0f) finishClose();
            }
        });
        anim.start();
    }

    private void finishClose() {
        side = NONE;
        fraction = 0f;
        layer.setVisibility(View.GONE);
        if (backRegistered) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
            backRegistered = false;
        }
    }

    /* ---------- entries ---------- */

    private void populate() {
        list.removeAllViews();
        Prefs prefs = host.prefs();
        boolean locked = prefs.lockLayout();
        add.setVisibility(locked ? View.GONE : View.VISIBLE);
        add.setImageTintList(android.content.res.ColorStateList.valueOf(light ? 0xFF14202E : 0xFFE6F0FF));
        List<String> items = prefs.sidebarItems(key(side));
        int iconPx = host.sidebarIconPx();
        for (int i = 0; i < items.size(); i++) {
            String spec = items.get(i);
            String name = Targets.label(activity, host.repo(), prefs, spec);
            if (name == null) name = activity.getString(R.string.sidebar_missing);
            View v = LayoutInflater.from(activity).inflate(R.layout.item_icon, list, false);
            ImageView icon = v.findViewById(R.id.icon);
            icon.getLayoutParams().width = iconPx;
            icon.getLayoutParams().height = iconPx;
            Targets.loadIcon(host.repo(), spec, icon::setImageDrawable);
            TextView label = v.findViewById(R.id.label);
            label.setText(name);
            prefs.styleLabel(label);
            if (light && !prefs.labelAccent()) {
                label.setTextColor(0xFF14202E);
                label.setShadowLayer(0f, 0f, 0f, 0);
            }
            label.setVisibility(prefs.drawerLabels() ? View.VISIBLE : View.GONE);
            v.setContentDescription(name);
            v.setOnClickListener(x -> {
                if (host.launchTarget(spec, x)) x.postDelayed(this::close, 250);
            });
            int index = i;
            v.setOnLongClickListener(x -> {
                if (!host.prefs().lockLayout()) showEntryMenu(index, x);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(4 * activity.getResources().getDisplayMetrics().density);
            list.addView(v, lp);
        }
        if (items.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText(locked ? R.string.sidebar_empty_locked : R.string.sidebar_empty);
            hint.setTextColor(light ? 0x9914202E : 0x99E6F0FF);
            hint.setTextSize(12);
            hint.setGravity(Gravity.CENTER);
            int p = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            hint.setPadding(p, p * 2, p, p);
            list.addView(hint);
        }
    }

    private void showEntryMenu(int index, View anchor) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        List<String> items = host.prefs().sidebarItems(key(side));
        PopupMenu menu = new PopupMenu(activity, anchor);
        if (index > 0) menu.getMenu().add(0, 1, 0, R.string.move_up);
        if (index < items.size() - 1) menu.getMenu().add(0, 2, 1, R.string.move_down);
        menu.getMenu().add(0, 3, 2, R.string.remove);
        menu.setOnMenuItemClickListener(mi -> {
            List<String> now = host.prefs().sidebarItems(key(side));
            if (index >= now.size()) return true;
            switch (mi.getItemId()) {
                case 1: now.add(index - 1, now.remove(index)); break;
                case 2: now.add(index + 1, now.remove(index)); break;
                default: now.remove(index); break;
            }
            host.prefs().setSidebarItems(key(side), now);
            populate();
            host.sidebarChanged();
            return true;
        });
        menu.show();
    }

    private void addEntry() {
        int s = side;
        Targets.pick(activity, host.repo(), host.prefs(), spec -> {
            List<String> items = host.prefs().sidebarItems(key(s));
            if (!items.contains(spec)) items.add(spec);
            host.prefs().setSidebarItems(key(s), items);
            if (side == s) populate();
            host.sidebarChanged();
        });
    }

    /** Re-reads entries (a pinned shortcut finished loading, a rename…). */
    void refresh() {
        if (side != NONE) populate();
    }
}
