/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in cards for wide sidebars: a quick note and a checklist. Both save
 * as you type (half a second after you stop) to Hearth's private storage and
 * never leave the phone, apart from backups you make yourself.
 */
final class NoteCards {

    private static final long SAVE_DELAY_MS = 500;

    private final Activity activity;
    private final Prefs prefs;
    private final boolean light;
    private final Handler handler = new Handler(Looper.getMainLooper());

    NoteCards(Activity activity, Prefs prefs, boolean light) {
        this.activity = activity;
        this.prefs = prefs;
        this.light = light;
    }

    private int text() { return light ? 0xFF14202E : 0xFFE6F0FF; }
    private int hint() { return light ? 0x8014202E : 0x80E6F0FF; }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /* ---------- note ---------- */

    View note(int id) {
        EditText e = new EditText(activity);
        e.setBackground(null);
        e.setText(prefs.noteText(id));
        e.setHint(R.string.note_hint);
        e.setTextColor(text());
        e.setHintTextColor(hint());
        e.setTextSize(15);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        e.setPadding(dp(4), dp(4), dp(28), dp(4)); // room for the menu button
        e.setVerticalScrollBarEnabled(true);
        keepScrollInside(e);
        Runnable save = () -> prefs.setNoteText(id, e.getText().toString());
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(save);
                handler.postDelayed(save, SAVE_DELAY_MS);
            }
        });
        // Save straight away if focus leaves (sidebar closing, another card).
        e.setOnFocusChangeListener((v, has) -> { if (!has) { handler.removeCallbacks(save); save.run(); } });
        return e;
    }

    /* ---------- checklist ---------- */

    private static final class Item {
        String text;
        boolean done;
        Item(String text, boolean done) { this.text = text; this.done = done; }
    }

    private List<Item> load(int id) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.checklist(id));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(new Item(o.optString("t"), o.optBoolean("d")));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    private void store(int id, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) a.put(new JSONObject().put("t", it.text).put("d", it.done));
        } catch (JSONException ignored) { }
        prefs.setChecklist(id, a.toString());
    }

    /** Removes the ticked items; returns how many went. */
    int clearDone(int id) {
        List<Item> items = load(id);
        int before = items.size();
        items.removeIf(it -> it.done);
        store(id, items);
        return before - items.size();
    }

    View checklist(int id) {
        ScrollView scroll = new ScrollView(activity);
        keepScrollInside(scroll);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, 0, dp(24), 0); // room for the menu button
        scroll.addView(rows);
        List<Item> items = load(id);
        fill(id, rows, items, -1);
        return scroll;
    }

    // Rebuilds the rows; `focus` is the row whose text box should take the cursor.
    private void fill(int id, LinearLayout rows, List<Item> items, int focus) {
        rows.removeAllViews();
        Runnable save = () -> store(id, items);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int index = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox box = new CheckBox(activity);
            box.setChecked(it.done);
            box.setButtonTintList(ColorStateList.valueOf(prefs.accent()));
            row.addView(box);

            EditText t = new EditText(activity);
            t.setBackground(null);
            t.setText(it.text);
            t.setHint(R.string.checklist_item_hint);
            t.setTextColor(text());
            t.setHintTextColor(hint());
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            styleDone(t, it.done);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            ImageView del = new ImageView(activity);
            del.setImageResource(R.drawable.ic_close_small);
            del.setImageTintList(ColorStateList.valueOf(hint()));
            del.setContentDescription(activity.getString(R.string.checklist_delete));
            del.setPadding(dp(6), dp(6), dp(6), dp(6));
            row.addView(del);

            box.setOnCheckedChangeListener((b, on) -> {
                it.done = on;
                styleDone(t, on);
                save.run();
            });
            t.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    it.text = s.toString();
                    handler.removeCallbacks(save);
                    handler.postDelayed(save, SAVE_DELAY_MS);
                }
            });
            // Next on the keyboard: a new item right below this one.
            t.setOnEditorActionListener((v, action, ev) -> {
                if (action != EditorInfo.IME_ACTION_NEXT && action != EditorInfo.IME_ACTION_DONE) return false;
                handler.removeCallbacks(save);
                items.add(index + 1, new Item("", false));
                save.run();
                fill(id, rows, items, index + 1);
                return true;
            });
            del.setOnClickListener(v -> {
                handler.removeCallbacks(save);
                items.remove(index);
                save.run();
                fill(id, rows, items, -1);
            });
            rows.addView(row);
            if (i == focus) t.post(() -> {
                t.requestFocus();
                activity.getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .showSoftInput(t, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        }
        TextView addRow = new TextView(activity);
        addRow.setText(R.string.checklist_add);
        addRow.setTextColor(Accent.withAlpha(prefs.accent(), 230));
        addRow.setTextSize(15);
        addRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        addRow.setOnClickListener(v -> {
            handler.removeCallbacks(save);
            items.add(new Item("", false));
            save.run();
            fill(id, rows, items, items.size() - 1);
        });
        rows.addView(addRow);
    }

    private void styleDone(TextView t, boolean done) {
        t.setPaintFlags(done ? t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : t.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        t.setAlpha(done ? 0.55f : 1f);
    }

    // The sidebar itself scrolls; a card that can scroll keeps its own drags.
    @SuppressLint("ClickableViewAccessibility")
    private static void keepScrollInside(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (view.canScrollVertically(1) || view.canScrollVertically(-1))) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }
}
