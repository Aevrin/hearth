/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Resize mode for a widget: a frame around it with a handle on each edge it can
 * stretch along. Drag a handle and the frame follows your finger; the grid
 * highlights where it will snap. Let go to apply. Tap outside to finish.
 * Covers the whole screen while it's up, so nothing else reacts to touches.
 */
@SuppressLint("ViewConstructor")
final class ResizeFrame extends View {

    interface Host {
        /** Whether the widget may take this area (cells, top-left x/y plus size). */
        boolean canPlace(int x, int y, int w, int h);
        /** Apply the area; return false if it couldn't be applied. */
        boolean commit(int x, int y, int w, int h);
        /** Show where a drop would snap (ok = it fits). */
        void preview(int x, int y, int w, int h, boolean ok);
        /** Resize mode is over. */
        void done();
    }

    private static final int NONE = 0, LEFT = 1, TOP = 2, RIGHT = 3, BOTTOM = 4;

    private final Host host;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    // Grid geometry in this view's coordinates.
    private float originX, originY, cellW, cellH;
    private int cols, rows;
    // The widget's current area, in cells, and its limits.
    private int x, y, w, h;
    private int minW, minH, maxW, maxH;
    private boolean horizontal, vertical;

    private int dragging = NONE;
    private int snapX, snapY, snapW, snapH;

    ResizeFrame(Context c, Host host) {
        super(c);
        this.host = host;
        density = c.getResources().getDisplayMetrics().density;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2.5f * density);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(2f * density);
        handleRing.setColor(0xFF0F1A2A);
        setClickable(true);
        setHapticFeedbackEnabled(true);
    }

    void setAccent(int color) {
        frame.setColor(color);
        handle.setColor(color);
        invalidate();
    }

    void setGrid(float originX, float originY, float cellW, float cellH, int cols, int rows) {
        this.originX = originX;
        this.originY = originY;
        this.cellW = cellW;
        this.cellH = cellH;
        this.cols = cols;
        this.rows = rows;
        snapToCells();
    }

    void setWidget(int x, int y, int w, int h, int minW, int minH, int maxW, int maxH,
                   boolean horizontal, boolean vertical) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.minW = Math.max(1, Math.min(minW, w));
        this.minH = Math.max(1, Math.min(minH, h));
        this.maxW = Math.max(maxW, w);
        this.maxH = Math.max(maxH, h);
        this.horizontal = horizontal;
        this.vertical = vertical;
        snapToCells();
    }

    private void snapToCells() {
        rect.set(originX + x * cellW, originY + y * cellH, originX + (x + w) * cellW, originY + (y + h) * cellH);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = 2 * density, r = 14 * density;
        c.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, frame);
        float hr = 8 * density;
        if (horizontal) {
            dot(c, rect.left + inset, rect.centerY(), hr);
            dot(c, rect.right - inset, rect.centerY(), hr);
        }
        if (vertical) {
            dot(c, rect.centerX(), rect.top + inset, hr);
            dot(c, rect.centerX(), rect.bottom - inset, hr);
        }
    }

    private void dot(Canvas c, float cx, float cy, float r) {
        c.drawCircle(cx, cy, r, handle);
        c.drawCircle(cx, cy, r, handleRing);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float ex = e.getX(), ey = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = handleAt(ex, ey);
                if (dragging == NONE) {
                    host.done(); // a tap anywhere else ends resize mode
                    return true;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == NONE) return true;
                drag(ex, ey);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging == NONE) return true;
                dragging = NONE;
                if (host.canPlace(snapX, snapY, snapW, snapH) && host.commit(snapX, snapY, snapW, snapH)) {
                    x = snapX;
                    y = snapY;
                    w = snapW;
                    h = snapH;
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                }
                host.preview(-1, -1, 1, 1, false);
                snapToCells();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = NONE;
                host.preview(-1, -1, 1, 1, false);
                snapToCells();
                return true;
            default:
                return true;
        }
    }

    private int handleAt(float px, float py) {
        float reach = 28 * density;
        if (horizontal && near(px, py, rect.left, rect.centerY(), reach)) return LEFT;
        if (horizontal && near(px, py, rect.right, rect.centerY(), reach)) return RIGHT;
        if (vertical && near(px, py, rect.centerX(), rect.top, reach)) return TOP;
        if (vertical && near(px, py, rect.centerX(), rect.bottom, reach)) return BOTTOM;
        return NONE;
    }

    private static boolean near(float px, float py, float hx, float hy, float reach) {
        return Math.hypot(px - hx, py - hy) <= reach;
    }

    // Moves one edge with the finger, within the widget's limits and the grid,
    // then works out which cells that rounds to.
    private void drag(float px, float py) {
        float gridR = originX + cols * cellW, gridB = originY + rows * cellH;
        switch (dragging) {
            case LEFT:
                rect.left = clamp(px, Math.max(originX, rect.right - maxW * cellW), rect.right - minW * cellW);
                break;
            case RIGHT:
                rect.right = clamp(px, rect.left + minW * cellW, Math.min(gridR, rect.left + maxW * cellW));
                break;
            case TOP:
                rect.top = clamp(py, Math.max(originY, rect.bottom - maxH * cellH), rect.bottom - minH * cellH);
                break;
            case BOTTOM:
                rect.bottom = clamp(py, rect.top + minH * cellH, Math.min(gridB, rect.top + maxH * cellH));
                break;
            default:
                return;
        }
        int l = Math.round((rect.left - originX) / cellW), r = Math.round((rect.right - originX) / cellW);
        int t = Math.round((rect.top - originY) / cellH), b = Math.round((rect.bottom - originY) / cellH);
        snapW = clampInt(r - l, minW, maxW);
        snapH = clampInt(b - t, minH, maxH);
        // The edge being dragged moves; the opposite edge stays where it was.
        snapX = dragging == LEFT ? x + w - snapW : x;
        snapY = dragging == TOP ? y + h - snapH : y;
        snapX = clampInt(snapX, 0, cols - snapW);
        snapY = clampInt(snapY, 0, rows - snapH);
        host.preview(snapX, snapY, snapW, snapH, host.canPlace(snapX, snapY, snapW, snapH));
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
