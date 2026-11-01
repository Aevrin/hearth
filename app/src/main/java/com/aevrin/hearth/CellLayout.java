/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * A fixed grid: each child occupies exactly one cell, placed by its LayoutParams.
 * While dragging, it can highlight the cell a drop would land in.
 */
public class CellLayout extends ViewGroup {

    public static class LayoutParams extends ViewGroup.LayoutParams {
        int cellX, cellY;
        public LayoutParams(int cellX, int cellY) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.cellX = cellX;
            this.cellY = cellY;
        }
    }

    /** What a drop on the highlighted cell would do. */
    static final int HL_NONE = 0, HL_MOVE = 1, HL_FOLDER = 2, HL_INSERT = 3;

    private int columns = 5;
    private int rows = 6;
    private int cellWidth, cellHeight;

    private int hlX = -1, hlY = -1, hlMode = HL_NONE;
    private final Paint hlFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hlRect = new RectF();

    public CellLayout(Context context) { super(context); init(); }
    public CellLayout(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        hlStroke.setStyle(Paint.Style.STROKE);
        hlStroke.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        setAccent(0xFF06B6D4);
    }

    void setGrid(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        requestLayout();
    }

    void setAccent(int color) {
        hlFill.setColor(Color.argb(56, Color.red(color), Color.green(color), Color.blue(color)));
        hlStroke.setColor(color);
        invalidate();
    }

    int cellWidth() { return cellWidth; }
    int cellHeight() { return cellHeight; }

    void setHighlight(int x, int y, int mode) {
        if (x == hlX && y == hlY && mode == hlMode) return;
        hlX = x;
        hlY = y;
        hlMode = mode;
        invalidate();
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
        int cw = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cw, ch);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int left = getPaddingLeft() + lp.cellX * cellWidth;
            int top = getPaddingTop() + lp.cellY * cellHeight;
            child.layout(left, top, left + cellWidth, top + cellHeight);
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (hlMode != HL_NONE && hlX >= 0 && cellWidth > 0) {
            float d = getResources().getDisplayMetrics().density;
            float left = getPaddingLeft() + hlX * cellWidth, top = getPaddingTop() + hlY * cellHeight;
            hlRect.set(left + 4 * d, top + 4 * d, left + cellWidth - 4 * d, top + cellHeight - 4 * d);
            float r = 16 * d;
            switch (hlMode) {
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
