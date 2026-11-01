/*
 * Hearth — a vibe-coded Android launcher.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.hearth;

import android.content.pm.ShortcutInfo;

/**
 * Keys for app shortcuts ("Navigate home", a pinned website, a contact) in the
 * home layout: "shortcut:<profile serial>:<package>/<shortcut id>".
 *
 * Shortcuts need no permission; Android only lets the default home app read and
 * pin them, which Hearth is once it's set as home.
 */
final class Shortcuts {

    static final String PREFIX = "shortcut:";

    private Shortcuts() { }

    static boolean isShortcutKey(String key) {
        return key.startsWith(PREFIX);
    }

    static String keyFor(ShortcutInfo s, long userSerial) {
        return PREFIX + userSerial + ":" + s.getPackage() + "/" + s.getId();
    }

    /** {serial, package, id}, or null if malformed. */
    static String[] parse(String key) {
        if (!isShortcutKey(key)) return null;
        String rest = key.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        int slash = colon < 0 ? -1 : rest.indexOf('/', colon);
        if (colon < 0 || slash < 0) return null;
        return new String[] {rest.substring(0, colon), rest.substring(colon + 1, slash), rest.substring(slash + 1)};
    }
}
