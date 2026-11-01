/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Add to home screen?" — shown when another app asks to pin a shortcut, e.g.
 * a website from a browser, a contact, or a destination in a maps app. Accepting
 * pins the shortcut and puts it in the first free cell, starting at the default page.
 */
public class PinShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LauncherApps la = getSystemService(LauncherApps.class);
        LauncherApps.PinItemRequest req = la.getPinItemRequest(getIntent());
        if (req == null || req.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
                || !req.isValid() || req.getShortcutInfo() == null) {
            finish();
            return;
        }
        ShortcutInfo si = req.getShortcutInfo();
        setContentView(R.layout.activity_pin);
        setFinishOnTouchOutside(true);

        CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
        String name = label == null ? si.getId() : label.toString();
        ((TextView) findViewById(R.id.pin_label)).setText(name);
        Drawable icon = null;
        try {
            icon = la.getShortcutBadgedIconDrawable(si, getResources().getDisplayMetrics().densityDpi);
        } catch (SecurityException | IllegalStateException ignored) { }
        ((ImageView) findViewById(R.id.pin_icon)).setImageDrawable(icon);

        findViewById(R.id.pin_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.pin_add).setOnClickListener(v -> {
            boolean accepted;
            try {
                accepted = req.accept(); // pins it for this launcher
            } catch (IllegalStateException e) {
                accepted = false;
            }
            if (accepted) {
                Prefs prefs = new Prefs(this);
                long serial = getSystemService(UserManager.class).getSerialNumberForUser(si.getUserHandle());
                String key = Shortcuts.keyFor(si, serial);
                HomeLayout layout = HomeLayout.load(getFilesDir(), prefs);
                if (layout.contains(key)) {
                    Toast.makeText(this, getString(R.string.already_on_home, name), Toast.LENGTH_SHORT).show();
                } else if (layout.add(key, HomeLayout.HOME, prefs.defaultPage())) {
                    Toast.makeText(this, getString(R.string.added_to_home, name), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.home_full, Toast.LENGTH_LONG).show();
                }
            }
            finish();
        });
    }
}
