# Hearth

A small, private, open-source Android launcher, in the spirit of classic Nova Launcher.

> **Vibe-coded.** Hearth is written largely by an AI model (Claude, by Anthropic) under
> human direction, review and on-device testing. It is not the work of an experienced
> Android team. Read the code before trusting it, and expect rough edges.

- No ads, no analytics, no accounts, and **no internet permission**. The only permissions are
  asking to uninstall apps (when you choose Uninstall) and expanding the status bar (for the
  swipe-down gesture); both are granted at install with no prompt.
- Free software under the [GNU GPL v3 or later](LICENSE).
- Android 13+.

## Status: 0.6 (gradient accent, settings pages)

| Phase | Scope | State |
|---|---|---|
| 1 | Home screen grid, app drawer with search | Done |
| 2 | Dock, clock, theming, default-home prompt | Done |
| 3 | Configuration: grid, pages, icons, labels, renaming, hidden apps, clock, backup | Done (gestures later) |
| 4 | Generated icon themes, per-app icon styles, folders | Done |
| 0.5 | App shortcuts, drag from drawer, smarter drops, page management, label styling | Done |
| 0.6 | Gradient accent colour, Settings split into pages | **In testing** |
| 4b | Notification dots, icon packs (maybe) | Planned |
| 5 | Widgets | Planned |

### What works now

- **Home screen:** a clock, one or more pages of apps, shortcuts and folders, and a dock, over your wallpaper.
  - Tap to open. Long-press for a menu; keep holding and move your finger to **drag** instead,
    including between the grid, the dock and pages (hold at the left or right edge to flip page).
  - The cell under your finger lights up to show what a drop will do: a plain fill moves it there,
    a ring (dropping on the middle of an icon) makes or joins a **folder**, and a bar (dropping on
    the edge of an icon) **slides the others along** to make room.
  - While dragging, a bar at the top offers **Remove**, **App info** and **Uninstall**.
  - An app's menu lists its own **shortcuts** first (e.g. "New
    message"), then Rename, Change icon, Remove, App info, Uninstall.
  - **Pinned shortcuts:** when another app offers "Add to home screen" (a contact, a site, a
    playlist), Hearth asks and places it on your default page.
  - **Folders:** tap to open as a compact panel above the folder, sized to fit: 2×1 or 2×2 for up
    to four apps, then 3×3, 4×4 and wider where the screen allows; very big folders scroll. Tap
    the name to rename it.
    Long-press inside for Move to home screen, Remove from folder, Rename, Change icon, App info,
    Uninstall. A folder left with one app turns back into that app.
  - Long-press empty space for a menu where you pressed: All apps, Wallpaper, Hearth settings,
    **Pages** (add, delete, move left/right, make default), and **Lock home screen**.
  - Swipe sideways to change page; the wallpaper scrolls with you (optional). Home returns to your
    default page.
  - Tap the clock to open your alarms.
- **Gestures** on the home screen, each configurable (Settings → Gestures): swipe **up** opens the
  drawer, swipe **down** pulls down the notification shade; **left / right** do nothing by default,
  so they just change page. Sideways gestures only run when there's no page that way. Choices:
  nothing, app drawer, search apps (drawer with keyboard), notifications, quick settings, Hearth
  settings, or open any app.
- **Dock:** label-less icons on a translucent bar, with up to three **dock pages** you swipe
  across the dock itself. When the dock is hidden, an arrow at the bottom opens the drawer.
- **App drawer:** swipe up anywhere on the home screen.
  - Alphabetical grid with a search box and an **A–Z strip** down the side. Matches ignore case
    and accents, and names that *start* with your search come first. Go opens the top match.
  - A **Most used** row at the top (optional), learned on the phone only.
  - Long-press an app for its menu (shortcuts, Add to home/dock, Rename, Change icon, Hide,
    App info, Uninstall). Keep holding and move your finger instead to pick it up and drop it on
    the home screen, dock or a folder.
  - Swipe down at the top of the list, Back, or Home closes it.
- **Settings** (long-press empty home space → Hearth settings): a main list of categories, each
  opening its own page. The main page also shows whether Hearth is the default home app.
  - **Appearance:** accent as a **solid colour** (presets or any colour) or a **gradient** (presets,
    or pick start and end, swap them, choose diagonal / horizontal / vertical). The clock, headings,
    accent labels, themed and duotone icons, page dots and drop highlights use the full gradient;
    single-colour things like switches use the midpoint. Drawer and dock background opacity.
  - **Labels:** on/off for home and drawer, size, accent colour, shadow, two lines.
  - **Icons:** generated on the phone from each app's own icon, with a live preview.
    Styles: Original, Themed, Duotone, Grayscale, Letters. Shapes: phone default, circle,
    squircle, rounded square, square, teardrop. Home icon size. Per-app overrides via long-press →
    Change icon.
    Generated icons are cached on disk so they appear instantly after a restart.
  - **Clock:** show/hide, date, seconds, phone / 12-hour / 24-hour.
  - **Home screen:** columns (3–8), rows (3–9), pages (1–7), lock, scroll wallpaper, allow
    rotation. Shrinking never deletes apps.
  - **Dock:** on/off, slots (3–7), pages (1–3), icon size.
  - **App drawer:** columns (3–7), icon size, A–Z strip, Most used, hidden apps and whether
    search finds them.
  - **Backup:** save every setting plus the home layout to a JSON file, and restore it.
- Renamed apps keep their new name everywhere, and search finds both names.
- Work-profile apps are included, with the usual briefcase badge.
- Installs, updates and uninstalls show up straight away.

### Roadmap / ideas

- Notification dots; maybe icon packs.
- Widgets.
- More gestures (double-tap, two-finger swipes).
- **A web page as a home screen:** a page of the home screen (or its own screen) that shows a
  chosen web page, such as a self-hosted dashboard. This needs the internet permission, so it
  would be opt-in, possibly as a separate build.
- Lots of small visual polish.

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

