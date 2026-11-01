# Hearth

A small, private, open-source Android launcher, in the spirit of classic Nova Launcher.

> **Vibe-coded.** Hearth is written largely by an AI model (Claude, by Anthropic) under
> human direction, review and on-device testing. It is not the work of an experienced
> Android team. Read the code before trusting it, and expect rough edges.

- No ads, no analytics, no accounts, and **no internet permission**.
- Free software under the [GNU GPL v3 or later](LICENSE).
- Android 13+.

## Status: Phase 4 (icons and folders)

| Phase | Scope | State |
|---|---|---|
| 1 | Home screen grid, app drawer with search | Done |
| 2 | Dock, clock, theming, default-home prompt | Done |
| 3 | Configuration: grid, pages, icons, labels, renaming, hidden apps, clock, backup | Done (gestures later) |
| 4 | Generated icon themes, per-app icon styles, folders | **In progress** |
| 4b | Notification dots, icon packs (maybe) | Planned |
| 5 | Widgets | Planned |

### What works now

- **Home screen:** a clock, one or more pages of pinned apps, and a dock, over your wallpaper.
  - Tap to open. Long-press and **drag** to move, including between the grid and the dock;
    dropping on an empty cell moves it. Hold a dragged icon at the left or right edge to
    carry it to the next page.
  - Swipe sideways to change page (dots show where you are). Home returns to page one.
  - **Folders:** drop an app onto another app to make a folder, or onto a folder to add it.
    Tap a folder to open it; tap its name to rename it. Long-press an app inside for Move to
    home screen, Remove from folder, Rename, Change icon, App info, Uninstall. Long-press a
    folder without moving for Open, Rename, Ungroup, Remove. A folder left with one app turns
    back into that app.
  - Drag onto **Remove** at the top to unpin. Long-press an app without moving for a menu
    (Rename, Change icon, Remove, App info, Uninstall).
  - Long-press empty space: All apps, Wallpaper, Hearth settings.
  - Tap the clock to open your alarms.
- **Dock:** a row of label-less icons on a translucent bar. When the dock is hidden, an
  arrow at the bottom opens the drawer instead.
- **App drawer:** swipe up anywhere on the home screen.
  - Alphabetical grid with a search box. Matches ignore case and accents, and names
    that *start* with your search come first. The keyboard's Go key opens the top match.
  - Long-press an app: Add to home screen, Add to dock, Rename, Change icon, Hide, App info,
    Uninstall.
  - Swipe down at the top of the list, Back, or Home closes it.
- **Settings** (long-press empty home space → Hearth settings):
  - **Home app:** whether Hearth is the default, with a button to make it so. Hearth also
    asks once, the first time it's opened while it isn't.
  - **Appearance:** accent colour, drawer and dock background opacity, icon size,
    labels on/off for the home screen and the drawer.
  - **Icons:** generated on the phone from each app's own icon, with a live preview.
    Styles: Original, Themed (the app's glyph in your accent colour on a dark plate, using its
    monochrome icon when it has one and an approximate silhouette when not), Duotone,
    Grayscale, Letters. Shapes: phone default, circle, squircle, rounded square, square,
    teardrop. Any app can have its own style via long-press → Change icon.
  - **Clock:** show/hide, date on/off, seconds on/off, phone / 12-hour / 24-hour.
  - **Layout:** home columns (3–8), rows (3–9), pages (1–7), dock on/off and slots (3–7),
    drawer columns (3–7). Shrinking never deletes apps: ones that don't fit move to a free
    spot, or wait out of sight until there's room.
  - **Apps:** pick hidden apps; choose whether they still show up in search.
  - **Backup:** save every setting plus the home layout to a JSON file, and restore it.
- Renamed apps keep their new name everywhere, and search finds both names.
- Work-profile apps are included, with the usual briefcase badge.
- Installs, updates and uninstalls show up straight away.

The home, dock and folder layout is saved as readable JSON in the app's private storage (`files/home.json`).

## Try it

Install it, then make it your home app: **Settings → Apps → Default apps → Home app → Hearth**.
Switching back is the same setting.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 36), Gradle 9.5+.

```bash
gradle :app:assembleDebug
```

Release builds need an upload key: copy `signing.properties.example` to
`signing.properties` (gitignored), or set the `SIGNING_*` environment variables.

The GitHub Actions workflow builds a signed bundle on every push to `main` and publishes it
to a Google Play internal testing track (maintainer only). Forks without the secrets will
fail at the signing step; that's expected.

## Contributing

Issues and pull requests are welcome. Since the project is AI-assisted, please say in your
PR whether your change was too; either is fine, it just helps review.

## License

Copyright (C) 2026 Aevrin

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version. See [LICENSE](LICENSE).

