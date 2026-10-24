# Fire TV 2-Series customization (AFTCU864A2 · Fire OS 8.1.8.5)

Privacy debloat + **Lumen**, a minimal tvOS-inspired launcher. Every change is reversible and logged in `state/applied.log`.
Findings, package classification and decisions: [PLAN.md](PLAN.md).

## What is changed on the TV

| Area | Change | Undo |
|---|---|---|
| Privacy settings (you, via Settings UI) | Device/App usage data off, interest-based ads off, autoplay off | Flip back in Settings → Preferences |
| 23 Amazon packages | `pm disable-user --user 0` (never uninstalled) | `scripts/restore.sh` |
| Lumen app (`app.lumen.home`) | Installed; no permissions | `scripts/restore.sh` (uninstalls) |
| Home button | Secure settings `enabled_accessibility_services` (orig `null`) and `accessibility_enabled` (orig `0`) | `scripts/restore.sh` |
| Lumen Sports PoC (`app.lumen.sports`) | Overlay feasibility test 2026-10-24; **currently uninstalled**. When installed: app-op `SYSTEM_ALERT_WINDOW` (orig `default`). See [sports/REPORT.md](sports/REPORT.md) | `scripts/restore.sh` (restores app-op, uninstalls) |

Refused by Fire OS (protected): `com.amazon.diode`, `com.amazon.ftvads.deeplinking`. Re-enabled by Fire OS at boot (left on): `com.amazon.vizzini`.

## Everyday use

- **Remote:** D-pad to move, OK to open, **Menu (☰)** or hold OK on an app for Add to Dock / Move / Remove / Hide / App info.
- **Looks:** **Cinema** (default since Lumen 2.0; 2.1 adds scene wallpapers, a smoked-glass dock, the app name gliding above the focused tile, centred menus) or **Classic** (the original layout). Switch in the **Lumen** control → Look → Theme.
- **Wallpapers:** six cinematic scenes (rendered offline by `launcher/tools/scenes.py`, shipped as 1280x720 JPEGs), the gradients, and your own photos. In Lumen settings, highlighting a wallpaper previews it full screen; OK keeps it.
- **Motion:** follows the system animation scale; with "Remove animations" on, transitions are instant and decorative drift is skipped.
- **Settings:** the gear control opens Fire TV Settings directly. **Apps** (Cinema) opens the full app grid; Back closes it. **Lumen**: theme, wallpaper, 24-hour clock, Home-button on/off, hidden apps, one-off visit to Amazon's home.
- **Own wallpapers:** `adb push photo.jpg /sdcard/Android/data/app.lumen.home/files/wallpapers/` then pick it in the Lumen tile.
- **After a Fire OS update:** run `bash scripts/verify.sh`. Updates can re-enable packages or reset settings; re-apply with `bash scripts/pkg.sh disable <pkg>` for any `MISMATCH`.

## Scripts (Git Bash, from `C:\dev\firetv`)

| Script | Does | Changes TV? |
|---|---|---|
| `scripts/discover.sh` | Full read-only snapshot into `backup/<timestamp>/` | No |
| `scripts/verify.sh [n] [gap]` | Package states vs log, crashes since boot, foreground, memory, n network snapshots | No |
| `scripts/privacy_check.sh` | Privacy-related settings values | No |
| `scripts/screens.sh <label>` | Design-review screenshots into `screens/<label>/` (drives Lumen with remote keys; detects the theme, aborts if anything but Lumen opens). `python scripts/contact_sheet.py <before> <after> [classic]` writes `screens/index.html`, a before/after review page | UI only |
| `scripts/pkg.sh disable\|enable\|status <pkg>` | One package, logged | Yes |
| `scripts/launcher.sh install\|open\|enable-redirect\|disable-redirect\|uninstall\|status` | Lumen management, logged | Yes |
| `scripts/restore.sh [--dry-run]` | **Full rollback** | Yes |
| `launcher/build.sh` | Build signed Lumen APK | No (PC only) |
| `scripts/sports.sh install\|grant\|show [opacity] [scroll]\|opacity <0-1>\|hide\|revoke\|status\|diag\|shot <label>\|uninstall` | Lumen Sports overlay PoC management, logged | Yes |
| `sports/build.sh` | Build the Sports PoC APK (Lumen's signing key) | No (PC only) |

## Complete rollback procedure

1. On the TV, make sure **Settings → My Fire TV → Developer Options → ADB Debugging** is on (it survives reboots on this TV).
2. On the PC: `bash scripts/restore.sh --dry-run` and review the list.
3. `bash scripts/restore.sh` — restores both accessibility settings to their original values, uninstalls Lumen, re-enables every package this project disabled (newest first), and verifies each.
4. Reboot the TV (`adb -s 10.0.0.213:5555 reboot`), then `bash scripts/verify.sh` — every package should report `expected=enabled ok`.
5. Optional, in the TV's Settings UI: re-enable the privacy options you turned off, and turn off ADB Debugging / Developer Options.

**If ADB is unavailable** (e.g. Developer Options reset by an update):
- Home button: in the Lumen tile → "Show Lumen when Home is pressed" → Off (always available). Fire TV's Settings → Accessibility may also list "Lumen Home button" (not verified on this build).
- Lumen: Settings → Applications → Manage Installed Applications → Lumen → Uninstall.
- Disabled packages: Settings → Applications → Manage Installed Applications → *app* → Enable (shown for most; otherwise re-enable ADB and run restore).
- Last resort (erases everything, **only with your decision**): factory reset restores all Amazon packages and settings.

Never used: root, firmware flashing, factory reset, package uninstall of system apps.

## Known limitations

- On Home, Amazon's home screen is visible for **~0.1–0.2 s**, then a cover showing Lumen's wallpaper hides it until Lumen appears (~0.9 s). Fire OS ignores default-home, Home-key presses are not visible to apps on this build (tested), and Amazon routes Settings through its home screen, so Lumen waits 0.9 s (cold Settings measured at 0.74 s) and drops the cover the moment another screen appears.
- If Android kills Lumen's screen for memory (common during 4K playback), Home still works: the redirect service runs in its own ~5 MB process; Lumen just takes slightly longer to draw.
- Amazon's launcher stays installed and running (it hosts Settings and the Home key path) and keeps its own background jobs.
- Telemetry not removable without root: protected metrics/crash packages, system-UID components, OTA/account/messaging services, streaming apps' own analytics. Opt-outs are policy controls Amazon applies server-side. No claim is made that tracking is eliminated; only on-device evidence (which packages run/connect) was checked. A router-level DNS log is the way to verify further.
- `com.amazon.vizzini` (Alexa) is force-re-enabled by Fire OS at boot.
- **Timezone:** became GMT about a minute after the first reboot following debloat step 2 (cause not identified). Amazon's auto-timezone service believed it was already `America/Montreal` and wouldn't correct it. Fixed by setting it manually (now `America/New_York`, persists across reboot); recheck after Fire OS updates.
- Two Amazon components crash once at each boot (`davcservice` background-start, `franktvinput` native crash); both pre-date Lumen and appear unrelated to the disabled packages, but no pre-change baseline exists.
- Lumen's signing key (`launcher/lumen-release.jks` + `keystore.properties`) is needed for updates; keep it.
