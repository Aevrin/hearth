/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;

/** One launchable app activity, for one user profile (personal or work). */
final class AppEntry {

    final LauncherActivityInfo info;
    final ComponentName component;
    final UserHandle user;
    final String label;
    /** Stable across restarts: component plus the profile's serial number. */
    final String key;

    AppEntry(LauncherActivityInfo info, long userSerial) {
        this.info = info;
        this.component = info.getComponentName();
        this.user = info.getUser();
        CharSequence l = info.getLabel();
        this.label = l == null ? component.getPackageName() : l.toString();
        this.key = keyFor(component, userSerial);
    }

    static String keyFor(ComponentName component, long userSerial) {
        return component.flattenToShortString() + "#" + userSerial;
    }

    String packageName() {
        return component.getPackageName();
    }
}
