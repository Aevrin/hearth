# Hearth

A small, private, open-source Android launcher, in the spirit of classic Nova Launcher.

> **Vibe-coded.** Hearth is written largely by an AI model (Claude, by Anthropic) under
> human direction, review and on-device testing. It is not the work of an experienced
> Android team. Read the code before trusting it, and expect rough edges.

- No ads, no analytics, no accounts, and **no internet permission**.
- Free software under the [GNU GPL v3 or later](LICENSE).
- Android 13+.

## Status: Phase 2 (dock + theming)

| Phase | Scope | State |
|---|---|---|
| 1 | Home screen grid, app drawer with search | Done |
| 2 | Dock, clock, theming, default-home prompt | **In progress** |
| 3 | Configuration (grid size, pages, labels, gestures, hidden apps, settings backup…) | Planned |
| 4 | Notification dots, icon packs (maybe) | Planned |
| 5 | Widgets | Planned |

### What works now

- **Home screen:** a clock, a 5 × 6 grid of pinned apps and a dock, over your wallpaper.
  - Tap to open. Long-press and **drag** to move, including between the grid and the dock;
    dropping on another app swaps them.
  - Drag onto **Remove** at the top to unpin. Long-press without moving for a menu
    (Remove, App info, Uninstall).
  - Long-press empty space: All apps, Wallpaper, Hearth settings.
  - Tap the clock to open your alarms.
- **Dock:** five slots on a translucent bar, icons without labels.
- **App drawer:** swipe up anywhere on the home screen, or tap the arrow at the bottom.
  - Alphabetical grid with a search box. Matches ignore case and accents, and names
    that *start* with your search come first. The keyboard's Go key opens the top match.
  - Long-press an app: Add to home screen, Add to dock, App info, Uninstall.
  - Swipe down at the top of the list, Back, or Home closes it.
- **Settings** (long-press empty home space → Hearth settings):
  - Default home app: shows whether Hearth is the default, with a button to make it so.
    Hearth also asks once, the first time it's opened while it isn't.
  - Accent colour (eight choices), app drawer background opacity, dock background
    opacity, show/hide clock, show/hide dock.
- Work-profile apps are included, with the usual briefcase badge.
- Installs, updates and uninstalls show up straight away.

The home and dock layout is saved as readable JSON in the app's private storage (`files/home.json`).

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

