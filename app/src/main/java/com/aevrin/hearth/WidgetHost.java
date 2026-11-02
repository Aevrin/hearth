/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * Hearth's widget host. Its views notice a long-press anywhere on the widget
 * (even over buttons and lists inside it), so widgets can be moved, resized and
 * removed like icons.
 */
final class WidgetHost extends AppWidgetHost {

    /** Any fixed number; identifies Hearth's widgets to the system. */
    static final int HOST_ID = 0x48454152; // "HEAR"

    WidgetHost(Context context) {
        super(context, HOST_ID);
    }

    @Override
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo widget) {
        return new View(context);
    }

    /** A widget view that turns a held press into a long-click for Hearth. */
    static final class View extends AppWidgetHostView {
        private final int slop;
        private final Runnable longPress = this::fireLongPress;
        private float downX, downY;
        private boolean pending, fired;

        View(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        // Watches the press before the widget's own views see it; once the
        // long-press fires, takes over the gesture so the widget gets a cancel.
        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            return fired;
        }

        // Reached when nothing inside the widget wanted the touch (or after we took it over).
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            return true;
        }

        private void track(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    fired = false;
                    if (!pending) {
                        pending = true;
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (pending && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) cancel();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) fired = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancel(); // two fingers: not a long-press
                    break;
                default:
                    break;
            }
        }

        private void cancel() {
            pending = false;
            removeCallbacks(longPress);
        }

        private void fireLongPress() {
            pending = false;
            if (!isAttachedToWindow()) return;
            fired = true;
            performLongClick();
        }

        @Override
        public void cancelLongPress() {
            super.cancelLongPress();
            cancel();
        }
    }
}
