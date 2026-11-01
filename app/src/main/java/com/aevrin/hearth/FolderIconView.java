/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;

/** A folder's icon: a translucent plate in the theme's shape with up to four mini icons. */
final class FolderIconView extends View {

    private final Drawable[] minis = new Drawable[4];
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private IconTheme theme;
    private Path path;
    private int pathSize = -1;

    FolderIconView(Context c) {
        super(c);
        plate.setColor(0xB3192534);
    }

    void setTheme(IconTheme t) {
        theme = t;
        pathSize = -1;
        invalidate();
    }

    void setMini(int index, Drawable d) {
        if (index < 0 || index >= minis.length) return;
        minis[index] = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int size = Math.min(getWidth(), getHeight());
        if (size <= 0 || theme == null) return;
        if (size != pathSize) {
            path = theme.shapePath(size);
            pathSize = size;
        }
        c.save();
        c.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        c.drawPath(path, plate);
        // 2×2 grid inside the plate.
        float pad = size * 0.16f;
        float gap = size * 0.06f;
        float cell = (size - 2 * pad - gap) / 2f;
        for (int i = 0; i < minis.length; i++) {
            Drawable d = minis[i];
            if (d == null) continue;
            int col = i % 2, row = i / 2;
            int left = Math.round(pad + col * (cell + gap));
            int top = Math.round(pad + row * (cell + gap));
            d.setBounds(left, top, left + Math.round(cell), top + Math.round(cell));
            d.draw(c);
        }
        c.restore();
    }
}
