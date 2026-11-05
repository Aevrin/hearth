/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The A–Z strip beside the app drawer: touch or slide along it to jump to a letter. */
public class AlphaScroller extends View {

    private final List<String> letters = new ArrayList<>();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Accent accent = Accent.solid(0xFF06B6D4);
    private int active = -1;
    private Consumer<String> listener;

    public AlphaScroller(Context c) { super(c); init(); }
    public AlphaScroller(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    }

    void setLetters(List<String> l) {
        letters.clear();
        letters.addAll(l);
        active = -1;
        invalidate();
    }

    void setAccent(Accent a) {
        accent = a;
        invalidate();
    }

    void setOnLetter(Consumer<String> l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (letters.isEmpty()) return;
        float slot = (float) (getHeight() - getPaddingTop() - getPaddingBottom()) / letters.size();
        // Letters smaller than the slot would overlap, so skip some on very short screens.
        int every = Math.max(1, (int) Math.ceil(text.getTextSize() * 1.1f / Math.max(1f, slot)));
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < letters.size(); i++) {
            if (i % every != 0 && i != active) continue;
            float t = letters.size() > 1 ? (float) i / (letters.size() - 1) : 0f;
            text.setColor(i == active ? accent.at(t) : Accent.withAlpha(Prefs.textColor(), 0xB3));
            text.setFakeBoldText(i == active);
            float y = getPaddingTop() + slot * i + slot / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(letters.get(i), getWidth() / 2f, y, text);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (letters.isEmpty()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float slot = (float) (getHeight() - getPaddingTop() - getPaddingBottom()) / letters.size();
                int i = (int) ((e.getY() - getPaddingTop()) / slot);
                i = Math.max(0, Math.min(letters.size() - 1, i));
                if (i != active) {
                    active = i;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.accept(letters.get(i));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
