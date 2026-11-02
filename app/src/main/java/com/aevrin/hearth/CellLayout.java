/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * A fixed grid: each child occupies exactly one cell, placed by its LayoutParams.
 * While dragging, it can highlight the cell a drop would land in.
 */
public class CellLayout extends ViewGroup {

    public static class LayoutParams extends ViewGroup.LayoutParams {
        int cellX, cellY;
        int spanX = 1, spanY = 1;
        public LayoutParams(int cellX, int cellY) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.cellX = cellX;
            this.cellY = cellY;
        }
        public LayoutParams(int cellX, int cellY, int spanX, int spanY) {
            this(cellX, cellY);
            this.spanX = Math.max(1, spanX);
            this.spanY = Math.max(1, spanY);
        }
    }

    /** What a drop on the highlighted cell would do. */
    static final int HL_NONE = 0, HL_MOVE = 1, HL_FOLDER = 2, HL_INSERT = 3;

    private int columns = 5;
    private int rows = 6;
    private int cellWidth, cellHeight;

    private int hlX = -1, hlY = -1, hlW = 1, hlH = 1, hlMode = HL_NONE;
    private final Paint hlFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hlRect = new RectF();
    // The highlight glides between cells and fades in and out rather than jumping.
    private final RectF hlFrom = new RectF(), hlTo = new RectF();
    private float hlAlpha, hlAlphaFrom, hlAlphaTo;
    private int drawMode = HL_NONE;
    private ValueAnimator hlAnim;
    private Accent accent = Accent.solid(0xFF06B6D4);

    public CellLayout(Context context) { super(context); init(); }
    public CellLayout(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        hlStroke.setStyle(Paint.Style.STROKE);
        hlStroke.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        setAccent(accent);
    }

    void setGrid(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        requestLayout();
    }

    // A gradient spans the whole grid, so each highlighted cell shows its own slice of it.
    void setAccent(Accent a) {
        accent = a;
        int c = a.solid();
        hlFill.setColor(Color.argb(56, Color.red(c), Color.green(c), Color.blue(c)));
        hlStroke.setColor(c);
        int w = getWidth(), h = getHeight();
        hlFill.setShader(w > 0 ? a.shader(0, 0, w, h, 56) : null);
        hlStroke.setShader(w > 0 ? a.shader(0, 0, w, h, 255) : null);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        setAccent(accent);
    }

    int cellWidth() { return cellWidth; }
    int cellHeight() { return cellHeight; }

    void setHighlight(int x, int y, int mode) {
        setHighlight(x, y, 1, 1, mode);
    }

    /** Highlights a w×h area (a widget's footprint). */
    void setHighlight(int x, int y, int w, int h, int mode) {
        if (x == hlX && y == hlY && w == hlW && h == hlH && mode == hlMode) return;
        hlX = x;
        hlY = y;
        hlW = w;
        hlH = h;
        hlMode = mode;
        boolean show = mode != HL_NONE && x >= 0 && cellWidth > 0;
        if (show) {
            float d = getResources().getDisplayMetrics().density;
            float left = getPaddingLeft() + x * cellWidth, top = getPaddingTop() + y * cellHeight;
            hlTo.set(left + 4 * d, top + 4 * d, left + cellWidth * w - 4 * d, top + cellHeight * h - 4 * d);
            if (drawMode == HL_NONE || hlAlpha <= 0.01f) hlRect.set(hlTo); // appearing: start in place
            drawMode = mode;
        }
        hlFrom.set(hlRect);
        if (!show) hlTo.set(hlRect); // disappearing: fade where it is
        hlAlphaFrom = hlAlpha;
        hlAlphaTo = show ? 1f : 0f;
        if (hlAnim != null) hlAnim.cancel();
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(show ? 110 : 140);
        hlAnim.setInterpolator(new DecelerateInterpolator());
        hlAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            hlRect.set(hlFrom.left + (hlTo.left - hlFrom.left) * t, hlFrom.top + (hlTo.top - hlFrom.top) * t,
                    hlFrom.right + (hlTo.right - hlFrom.right) * t, hlFrom.bottom + (hlTo.bottom - hlFrom.bottom) * t);
            hlAlpha = hlAlphaFrom + (hlAlphaTo - hlAlphaFrom) * t;
            if (hlAlpha <= 0.01f && hlAlphaTo == 0f) drawMode = HL_NONE;
            invalidate();
        });
        hlAnim.start();
    }

    void clearHighlight() {
        setHighlight(-1, -1, HL_NONE);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        cellWidth = (w - getPaddingLeft() - getPaddingRight()) / columns;
        cellHeight = (h - getPaddingTop() - getPaddingBottom()) / rows;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(cellWidth * lp.spanX, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellHeight * lp.spanY, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int left = getPaddingLeft() + lp.cellX * cellWidth;
            int top = getPaddingTop() + lp.cellY * cellHeight;
            child.layout(left, top, left + cellWidth * lp.spanX, top + cellHeight * lp.spanY);
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (drawMode != HL_NONE && hlAlpha > 0.01f && cellWidth > 0) {
            float d = getResources().getDisplayMetrics().density;
            float r = 16 * d;
            hlFill.setAlpha(Math.round((hlFill.getShader() != null ? 255 : 56) * hlAlpha));
            hlStroke.setAlpha(Math.round(255 * hlAlpha));
            switch (drawMode) {
                case HL_FOLDER: // ring: "drop to make a folder / join it"
                    c.drawRoundRect(hlRect, r, r, hlFill);
                    c.drawRoundRect(hlRect, r, r, hlStroke);
                    break;
                case HL_INSERT: // bar on the leading edge: "slide the others along"
                    c.drawRoundRect(hlRect.left - 2 * d, hlRect.top + 8 * d,
                            hlRect.left + 2 * d, hlRect.bottom - 8 * d, 2 * d, 2 * d, hlStroke);
                    break;
                default: // plain fill: "goes here"
                    c.drawRoundRect(hlRect, r, r, hlFill);
                    break;
            }
        }
        super.dispatchDraw(c);
    }

    /** The cell under a point in this view's coordinates, clamped to the grid. */
    int[] cellAt(float x, float y) {
        if (cellWidth == 0 || cellHeight == 0) return new int[] {0, 0};
        int cx = (int) ((x - getPaddingLeft()) / cellWidth);
        int cy = (int) ((y - getPaddingTop()) / cellHeight);
        return new int[] {
                Math.max(0, Math.min(columns - 1, cx)),
                Math.max(0, Math.min(rows - 1, cy))
        };
    }

    /** True when the point is in the middle of its cell (a folder drop rather than a reorder). */
    boolean nearCellCentre(float x, float y) {
        if (cellWidth == 0 || cellHeight == 0) return true;
        float fx = ((x - getPaddingLeft()) % cellWidth) / cellWidth;
        float fy = ((y - getPaddingTop()) % cellHeight) / cellHeight;
        return fx > 0.22f && fx < 0.78f && fy > 0.12f && fy < 0.88f;
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(0, 0);
    }
}
