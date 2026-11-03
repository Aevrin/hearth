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
 * A sidebar on the left or right, dragged in from the screen edge (it follows
 * the finger) or opened by a gesture. Two styles: a narrow column of apps,
 * activities and shortcuts, or a wide panel of widget cards (calendar, weather,
 * music…). Tap outside or press Back to close; "+" adds entries.
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
        /** A widget's view (kept between openings), or a placeholder if it's gone. */
        View sidebarWidget(int id);
        /** Pick and add a widget to this sidebar's panel. */
        void addSidebarWidget(String side);
        /** Lets go of a widget removed from a panel. */
        void removeSidebarWidget(int id);
        boolean widgetHasSettings(int id);
        void openWidgetSettings(int id);
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
    private boolean panelMode; // wide panel of widget cards instead of a shortcut column
    private int panelWidth;
    private View header;       // grab strip at the top of a panel: swipe it toward the edge to close
    private int resizingId = -1;

    Sidebar(Activity activity, Host host, View layer, View scrim, LinearLayout panel, LinearLayout list, ImageView add) {
        this.activity = activity;
        this.host = host;
        this.layer = layer;
        this.scrim = scrim;
        this.panel = panel;
        this.list = list;
        this.add = add;
        scrim.setOnClickListener(v -> close());
        add.setOnClickListener(v -> {
            if (panelMode) host.addSidebarWidget(key(side));
            else addEntry();
        });
        header = new View(activity);
        header.setContentDescription(activity.getString(R.string.sidebar_close));
        panel.addView(header, 0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        header.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: startX = e.getRawX(); return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float back = side == LEFT ? startX - e.getRawX() : e.getRawX() - startX;
                        if (back > dp(40)) close();
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (Math.abs(e.getRawX() - startX) < dp(8)) close(); // a tap on the strip closes too
                        return true;
                    default: return true;
                }
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /** Wide panel (widgets) rather than the narrow shortcut column. */
    boolean isPanel() { return side != NONE && panelMode; }

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
        return panelWidth > 0 ? panelWidth : dp(104);
    }

    private void prepare(int s) {
        side = s;
        panelMode = host.prefs().sidebarStyle(key(s)) == Prefs.SB_STYLE_PANEL;
        int screenW = activity.getWindow().getDecorView().getWidth();
        if (screenW <= 0) screenW = activity.getResources().getDisplayMetrics().widthPixels;
        panelWidth = panelMode ? Math.min(Math.round(screenW * 0.85f), dp(380)) : dp(104);
        resizingId = -1;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = s == RIGHT ? Gravity.END : Gravity.START;
        lp.width = panelWidth;
        panel.setLayoutParams(lp);
        float r = 24 * activity.getResources().getDisplayMetrics().density;
        // Its own background: the theme's, a colour or a gradient, at its own opacity.
        Prefs prefs = host.prefs();
        String k = key(s);
        Accent fill = prefs.bgFill(k);
        int opacity = prefs.bgOpacity(k);
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
        header.setVisibility(panelMode ? View.VISIBLE : View.GONE);
        header.setBackground(grabBar());
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
        // Widgets are kept and reused by the host, so detach them before clearing.
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof FrameLayout) ((FrameLayout) c).removeAllViews();
        }
        list.removeAllViews();
        if (panelMode) {
            populatePanel();
            return;
        }
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        Prefs prefs = host.prefs();
        boolean locked = prefs.lockLayout();
        add.setVisibility(locked ? View.GONE : View.VISIBLE);
        add.setImageTintList(android.content.res.ColorStateList.valueOf(light ? 0xFF14202E : 0xFFE6F0FF));
        add.setContentDescription(activity.getString(R.string.sidebar_add));
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

    /* ---------- panel of widget cards ---------- */

    private android.graphics.drawable.Drawable grabBar() {
        int c = light ? 0x6614202E : 0x66E6F0FF;
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(c);
        bar.setCornerRadius(dp(2));
        android.graphics.drawable.InsetDrawable d = new android.graphics.drawable.InsetDrawable(bar,
                0, dp(11), 0, dp(13));
        // Centred, 36dp wide.
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {d}) {
            @Override protected void onBoundsChange(android.graphics.Rect b) {
                int w = dp(36);
                getDrawable(0).setBounds(b.centerX() - w / 2, b.top, b.centerX() + w / 2, b.bottom);
            }
        };
    }

    private void populatePanel() {
        Prefs prefs = host.prefs();
        boolean locked = prefs.lockLayout();
        add.setVisibility(locked ? View.GONE : View.VISIBLE);
        add.setImageTintList(android.content.res.ColorStateList.valueOf(light ? 0xFF14202E : 0xFFE6F0FF));
        add.setContentDescription(activity.getString(R.string.sidebar_add_widget));
        list.setGravity(Gravity.NO_GRAVITY);
        List<int[]> cards = prefs.sidebarCards(key(side));
        for (int i = 0; i < cards.size(); i++) {
            int id = cards.get(i)[0];
            int index = i;
            FrameLayout card = new FrameLayout(activity);
            View w = host.sidebarWidget(id);
            card.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            w.setOnLongClickListener(v -> {
                showCardMenu(index, id, card);
                return true;
            });
            if (id == resizingId) card.addView(resizeHandle(card, index), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(32), Gravity.BOTTOM));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(cards.get(i)[1]));
            lp.setMargins(dp(10), dp(6), dp(10), dp(6));
            list.addView(card, lp);
        }
        if (cards.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText(locked ? R.string.sidebar_empty_locked : R.string.sidebar_panel_empty);
            hint.setTextColor(light ? 0x9914202E : 0x99E6F0FF);
            hint.setTextSize(14);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(16), dp(32), dp(16), dp(16));
            list.addView(hint);
        }
    }

    private void showCardMenu(int index, int id, View anchor) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        boolean locked = host.prefs().lockLayout();
        List<int[]> cards = host.prefs().sidebarCards(key(side));
        PopupMenu menu = new PopupMenu(activity, anchor);
        if (!locked) {
            menu.getMenu().add(0, 1, 0, R.string.card_resize);
            if (index > 0) menu.getMenu().add(0, 2, 1, R.string.move_up);
            if (index < cards.size() - 1) menu.getMenu().add(0, 3, 2, R.string.move_down);
        }
        if (host.widgetHasSettings(id)) menu.getMenu().add(0, 4, 3, R.string.widget_settings);
        if (!locked) menu.getMenu().add(0, 5, 4, R.string.remove);
        if (menu.getMenu().size() == 0) return;
        menu.setOnMenuItemClickListener(mi -> {
            List<int[]> now = host.prefs().sidebarCards(key(side));
            if (index >= now.size()) return true;
            switch (mi.getItemId()) {
                case 1: resizingId = id; populate(); return true;
                case 2: now.add(index - 1, now.remove(index)); break;
                case 3: now.add(index + 1, now.remove(index)); break;
                case 4: host.openWidgetSettings(id); return true;
                default:
                    now.remove(index);
                    host.removeSidebarWidget(id);
                    break;
            }
            host.prefs().setSidebarCards(key(side), now);
            populate();
            return true;
        });
        menu.show();
    }

    // A strip along the card's bottom edge: drag it to make the card taller or
    // shorter. Saved when you let go; tap it to finish.
    private View resizeHandle(FrameLayout card, int index) {
        View h = new View(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Accent.withAlpha(host.prefs().accent(), 200));
        bg.setCornerRadius(dp(12));
        h.setBackground(bg);
        h.setContentDescription(activity.getString(R.string.card_resize));
        h.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private int startH;
            private boolean moved;
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        startH = lp.height;
                        moved = false;
                        v.getParent().requestDisallowInterceptTouchEvent(true); // not a scroll
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.8f);
                        int nh = Math.max(dp(64), Math.min(max, startH + Math.round(e.getRawY() - startY)));
                        if (Math.abs(e.getRawY() - startY) > dp(4)) moved = true;
                        lp.height = nh;
                        card.setLayoutParams(lp);
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        List<int[]> now = host.prefs().sidebarCards(key(side));
                        if (index < now.size()) {
                            float d = activity.getResources().getDisplayMetrics().density;
                            now.get(index)[1] = Math.round(lp.height / d);
                            host.prefs().setSidebarCards(key(side), now);
                        }
                        if (!moved) {
                            resizingId = -1; // tapped: done
                            populate();
                        }
                        return true;
                    }
                    default: return true;
                }
            }
        });
        return h;
    }

    /** Re-reads entries (a pinned shortcut finished loading, a rename…). */
    void refresh() {
        if (side != NONE) populate();
    }
}
