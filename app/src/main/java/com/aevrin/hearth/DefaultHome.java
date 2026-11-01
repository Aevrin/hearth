/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Is Hearth the default home app, and asking Android to make it so. */
final class DefaultHome {

    static final int REQUEST_CODE = 4201;

    private DefaultHome() { }

    static boolean isDefault(Context c) {
        RoleManager rm = c.getSystemService(RoleManager.class);
        return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
    }

    /**
     * Shows Android's "Set Hearth as your default home app?" dialog. If the user has
     * dismissed that before, Android may skip it, so the Default apps screen is the fallback.
     */
    static void request(Activity a) {
        RoleManager rm = a.getSystemService(RoleManager.class);
        try {
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_CODE);
                return;
            }
        } catch (ActivityNotFoundException ignored) {
            // fall through to the settings screen
        }
        openSettings(a);
    }

    static void openSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
