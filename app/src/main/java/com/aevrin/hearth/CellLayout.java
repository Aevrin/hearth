/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** A fixed grid: each child occupies exactly one cell, placed by its LayoutParams. */
public class CellLayout extends ViewGroup {

    public static class LayoutParams extends ViewGroup.LayoutParams {
        int cellX, cellY;
        public LayoutParams(int cellX, int cellY) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.cellX = cellX;
            this.cellY = cellY;
        }
    }

    private int columns = 5;
    private int rows = 6;
    private int cellWidth, cellHeight;

    public CellLayout(Context context) { super(context); }
    public CellLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    void setGrid(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        requestLayout();
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

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(0, 0);
    }
}
