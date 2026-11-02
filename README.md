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

## Status: 0.7 (widgets)

| Phase | Scope | State |
|---|---|---|
| 1 | Home screen grid, app drawer with search | Done |
| 2 | Dock, clock, theming, default-home prompt | Done |
| 3 | Configuration: grid, pages, icons, labels, renaming, hidden apps, clock, backup | Done (gestures later) |
| 4 | Generated icon themes, per-app icon styles, folders | Done |
| 0.5 | App shortcuts, drag from drawer, smarter drops, page management, label styling | Done |
| 0.6 | Gradient accent colour, Settings split into pages, gestures | Done |
| 4b | Notification dots, icon packs (maybe) | Planned |
| 5 | Widgets | **In testing** |

### What works now

- **Home screen:** a clock, one or more pages of apps, shortcuts and folders, and a dock, over your wallpaper.
  - Tap to open. Long-press for a menu; keep holding and move your finger to **drag** instead,
    including between the grid, the dock and pages (hold at the left or right edge to flip page).
  - Dragging is animated: the icon lifts slightly under your finger, the highlight glides from
    cell to cell, icons slide aside *while you hover* to show where they'd go, and everything
    settles into place (the dropped icon from where you let go) instead of jumping.
  - The cell under your finger lights up to show what a drop will do: a plain fill moves it there,
    a ring (dropping on the middle of an icon) makes or joins a **folder**, and a bar (dropping on
    the edge of an icon) **slides the others along** to make room.
  - While dragging, icons at the top offer **Remove** (trash can), **App info** and **Uninstall**;
    the one under your finger grows and lights up.
  - An app's menu lists its own **shortcuts** first (e.g. "New
    message"), then Rename, Change icon, Remove, App info, Uninstall.
  - **Pinned shortcuts:** when another app offers "Add to home screen" (a contact, a site, a
    playlist), Hearth asks and places it on your default page.
  - **Folders:** tap to open as a compact panel above the folder, sized to fit: 2×1 or 2×2 for up
    to four apps, then 3×3, 4×4 and wider where the screen allows; very big folders scroll. Tap
    the name to rename it.
    Long-press a folder for Open, **Change icon** (show the apps inside, use any app's icon, a
    letter or emoji in the accent, or a picture from your phone, all cut to the icon shape),
    Ungroup and Remove.
    Long-press inside for Move to home screen, Remove from folder, Rename, Change icon, App info,
    Uninstall; keep holding and move to **reorder** (the others shift aside as you drag), or drag
    outside the panel to take the app out and drop it anywhere. A folder left with one app turns
    back into that app.
  - Long-press empty space for a menu where you pressed: All apps, Wallpaper, Hearth settings,
    **Pages** (add, delete, move left/right, make default), and **Lock home screen**.
  - Swipe sideways to change page; the wallpaper scrolls with you (optional). Home returns to your
    default page.
  - Tap the clock to open your alarms.
- **Gestures** on the home screen, each configurable (Settings → Gestures): swipe **up** opens the
  drawer, swipe **down** pulls down the notification shade; **left / right** do nothing by default,
  so they just change page (they only run when there's no page that way); **double-tap** on empty
  space and **two-finger swipe up / down** are off by default. Choices: nothing, app drawer, search
  apps, notifications, quick settings, Hearth settings, open the left or right sidebar, or open
  **any app, any activity inside an app** (a particular settings screen, say) **or any app
  shortcut**, Nova-style. Gestures only look at touches already on the home screen, so they cost
  no battery.
- **Sidebars** (Settings → Sidebars), left, right or both: swipe in from the edge where the faint
  handle is and a column of apps, activities and shortcuts follows your finger out. Add with +,
  long-press to reorder or remove; tap outside, swipe it back or press Back to close. The handle
  zone is up to 200dp of the edge (top, middle or bottom), the most Android lets an app take from
  the Back gesture; the rest of the edge is still Back. Each sidebar has its own background (theme, any colour,
  or a gradient) and opacity down to fully see-through; labels turn dark on light backgrounds.
- **Widgets:** long-press empty space → **Widgets** for every installed widget, grouped by app with
  previews, sizes and a search box. Hearth places it in the first free space big enough (on the
  current page first), running the widget's own setup screen if it has one. The first time you add
  a widget from an app, Android asks whether Hearth may create widgets.
  - Long-press a widget to pick it up: move it to drag it somewhere else (it only drops where all
    its cells are free; the highlight shows its whole footprint), or onto the trash can at the top
    to remove it. Let go **without moving** and it goes straight into **resize**: a frame with
    handles; drag an edge and the grid shows where it will snap. Widgets with their own settings
    get a **Widget settings** button under the frame. Putting a widget down after dragging it
    also leaves it in resize mode, and in resize mode you can drag the widget itself to move it
    again. Tap outside or Back to finish.
  - Icons slide around widgets, never through them. Widgets live on home pages only (not the dock
    or folders). A widget whose app was uninstalled, or one restored from a backup on another
    phone, shows as "Widget unavailable" until you remove it.
- **Dock:** label-less icons on a translucent bar, with up to three **dock pages** you swipe
  across the dock itself. When the dock is hidden, an arrow at the bottom opens the drawer.
- **App drawer:** swipe up anywhere on the home screen.
  - Alphabetical grid with a search box and an **A–Z strip** down the side. Matches ignore case
    and accents, and names that *start* with your search come first. Go opens the top match.
  - A **Most used** row at the top (optional), learned on the phone only.
  - Optional **pull-to-search**: the drawer opens without its search bar; pull down at the top
    of the list to reveal it (pull again to close the drawer).
  - Long-press an app for its menu (shortcuts, Add to home/dock, Rename, Change icon, Hide,
    App info, Uninstall). Keep holding and move your finger instead to pick it up and drop it on
    the home screen, dock or a folder.
  - Swipe down at the top of the list, Back, or Home closes it.
- **Settings** (long-press empty home space → Hearth settings): a main list of categories, each
  opening its own page. The main page also shows whether Hearth is the default home app.
  - **Appearance:** accent as a **solid colour** (presets or any colour) or a **gradient** (presets,
    or pick start and end, swap them, choose diagonal / horizontal / vertical). The clock, headings,
    accent labels, themed and duotone icons, page dots and drop highlights use the full gradient;
    single-colour things like switches use the midpoint. **System bar icons:** the status bar (clock,
    battery, signal) and navigation bar (back, home, recents) icons can each be Auto, Light or
    Dark. Android doesn't let apps colour them; Auto follows the wallpaper and goes light over a
    dark drawer. Drawer and dock background opacity.
  - **Labels:** on/off for home and drawer, size, shadow, two lines, and **text in accent colour**
    (labels everywhere, folder names, the search box and hints).
  - **Icons:** generated on the phone from each app's own icon, with a live preview.
    Styles: Original, Themed, Duotone, Grayscale, Letters. Shapes: phone default, circle,
    squircle, rounded square, square, teardrop. Home icon size. Per-app overrides via long-press →
    Change icon.
    Generated icons are cached on disk so they appear instantly after a restart.
  - **Clock:** show/hide, date, seconds, phone / 12-hour / 24-hour, and optional **next alarm** and
    **battery level** under the date (both read from the system with no permission).
  - **Home screen:** columns (3–8), rows (3–9), pages (1–7), **subgrid positioning**, lock, scroll
    wallpaper, allow rotation. Shrinking never deletes apps.
    **Folder spacing** slider with a live preview of an open folder, and separate backgrounds for
    the open folder and for the folder icon's plate (theme, colour or gradient, own opacity).
    - *Subgrid* splits every home cell in four, so icons, folders and widgets can sit on half
      cells (icons centre under your finger as you drag) and widgets resize in half steps. Icons
      no longer slide aside on the subgrid: drop on free space to move, or on the middle of an icon
      to make a folder. Turning it on or off converts your layout in place.
  - **Dock:** on/off, slots (3–7), pages (1–3), icon size.
  - **App drawer:** columns (3–7), icon size, **sort** (A–Z, most used, recently installed), an
    optional **dot on newly installed apps** until you open them, A–Z strip, Most used, hidden
    apps and whether search finds them.
  - **Backup:** save every setting plus the home layout to a JSON file, and restore it.
- Renamed apps keep their new name everywhere, and search finds both names.
- Work-profile apps are included, with the usual briefcase badge.
- Installs, updates and uninstalls show up straight away.

### Roadmap / ideas

- Notification dots; maybe icon packs.
- **A web page as a home screen:** a page of the home screen (or its own screen) that shows a
  chosen web page, such as a self-hosted dashboard. This needs the internet permission, so it
  would be opt-in, possibly as a separate build.
- Lots of small visual polish.

The home, dock and folder layout is saved as readable JSON in the app's private storage (`files/home.json`).

## Privacy

Hearth collects nothing and has no internet permission. See the [privacy policy](PRIVACY.md).
To report a security problem, see [SECURITY.md](SECURITY.md).

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

## Tests

The layout engine (placing, sliding, widgets, folders, pages, the subgrid, and never losing
anything when the grid shrinks) has unit tests in `app/src/test`. CI runs them before every
release build: `gradle :app:testDebugUnitTest`.

## Contributing

Issues and pull requests are welcome. Since the project is AI-assisted, please say in your
PR whether your change was too; either is fine, it just helps review.

## License

Copyright (C) 2026 Aevrin

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version. See [LICENSE](LICENSE).

