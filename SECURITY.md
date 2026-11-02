# Security policy

Hearth is a small, open-source Android launcher maintained by one person in
their spare time. Security reports are welcome and taken seriously.

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report privately through GitHub instead: go to the
[Security tab](https://github.com/Aevrin/hearth/security) and choose
**Report a vulnerability**. Only the maintainer can see the report.

Helpful to include:

- What the problem is and what an attacker could do with it.
- Steps to reproduce, or a proof of concept.
- The Hearth version (Settings → About) and your Android version and device.

## What to expect

- An acknowledgement within about a week.
- An honest assessment of whether it's a real issue and how serious it is.
- For confirmed issues, a fix in the next release, published through Google
  Play and noted in the commit history. You'll be credited unless you'd rather
  not be.

This is a volunteer project, so there is no bug bounty.

## Supported versions

Only the latest release receives fixes. Updates ship through Google Play, so
the current version there is the supported one.

## Scope

Hearth deliberately has a small attack surface:

- It does **not** request the internet permission, so it cannot send or
  receive anything over the network.
- It collects no data (see the [privacy policy](PRIVACY.md)).
- Its only other permissions are asking to uninstall apps (Android shows its
  own confirmation) and expanding the status bar.

Things that are in scope include, for example: another app being able to
make Hearth launch something, change its layout or settings, or read its
data without the user's action; crashes or hangs triggered by another app's
shortcuts, widgets or icons; and problems in the backup/restore file handling.

Out of scope: problems in Android itself, in other apps' widgets or
shortcuts (report those to their developers), and anything that needs a
rooted or already-compromised device.
