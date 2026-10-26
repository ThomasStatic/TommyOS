# Lumen Sports: setup and everyday use

Your ESPN fantasy matchup over the game, switched on and off from your iPhone or the remote.

## First-time setup (once)

The PC is needed once, to sign in to ESPN. After that, no PC.

1. **On the PC:** put your ESPN cookies in `sports/espn-auth.properties` (see the README), then run `bash scripts/sports.sh auth`.
2. **On the TV:** open Lumen → Settings (the Lumen tile) → scroll to **Sports**.
3. Turn **Phone control** On.
4. Choose **Pair a phone**. A QR code appears.
5. **On the iPhone:** point the Camera at the code and tap the link. Safari opens the controller.
6. In Safari, tap **Share** → **Add to Home Screen** → **Add**. From now on, open *Lumen Sports* from the Home Screen.
7. Tap **Show ticker**. Open TSN on the TV and start a game: the ticker is on screen. Tap **Hide ticker**: it disappears while TSN keeps playing.

If the camera link doesn't open, check that the phone is on the TV's Wi-Fi.

## Everyday use

- **Show / Hide** from the phone, or Lumen → Settings → Sports → Fantasy ticker.
- **Off stays off.** After you hide it, after a reboot, or after Fire TV restarts the app, the ticker stays off until you show it again.
- **Show it: Always or TSN games.**
  - *Always* draws it over every app.
  - *TSN games* shows it only while TSN's player is on screen. It stays off TSN's menus and sign-in screen, other apps, and Fire TV settings and PIN screens.
  - *TSN games* needs Lumen's Home button service (it's on if pressing Home opens Lumen).
- **Position:** Top keeps TSN's player controls and channel strip clear. Bottom is the default.
- **Adjust:** height, background darkness, crawl speed, and how long score alerts stay (Off turns them off).
- **Status line:** "ESPN connected · updated 8:41 PM". *Sign-in expired* means redoing step 1 on the PC, typically after a few months.

The phone page shows only the ticker's state and settings. Scores, teams and your ESPN sign-in never leave the TV.

## If something goes wrong

| Problem | Fix |
|---|---|
| Phone says *Can't reach the TV* | TV on? Phone on the same Wi-Fi? If the TV's address changed (router restart), pair again: Lumen → Sports → Pair a phone, then scan and add to the Home Screen again. A DHCP reservation for the TV on your router avoids this. |
| Phone says *isn't paired any more* | Someone chose Reset pairing. Scan the new code. |
| Ticker covers TSN's controls | Position → Top, or Show it → TSN games. |
| Ticker won't go away | Phone: Hide. Remote: Lumen → Settings → Sports → Fantasy ticker Off. Last resort: Fire TV Settings → Applications → Manage Installed Applications → Lumen Sports → **Force stop**. |
| Lost phone, or someone else has the page | Lumen → Sports → **Reset pairing**. Every old pairing stops working; scan the new code with your phone. |

## Updating (PC)

1. `bash sports/build.sh`, then `bash scripts/sports.sh install`.
2. Install Lumen after Sports if Lumen changed: `bash launcher/build.sh`, then `bash scripts/launcher.sh install`.
3. The ESPN sign-in, settings and phone pairing survive updates. The ticker is off after an update. Phone control comes back by itself.

**A failed update** (the app misbehaves):
- Fire OS refuses to install an older version over a newer one. Instead, rebuild the last good version with a higher version number:
  1. `git checkout <good tag> -- sports`
  2. Raise `versionCode` in `sports/app/build.gradle.kts` above the installed one.
  3. Build and install as above. Settings, sign-in and pairing are kept.
- The archived APK of the version before this phase is `C:\dev\firetv-releases\sports-baseline-v1.apk`. It can only be installed after uninstalling (which deletes the sign-in and pairing).

## Removing Lumen Sports

- **From the PC:** `bash scripts/sports.sh hide`, then `bash scripts/sports.sh revoke`, then `bash scripts/sports.sh uninstall`. Or run `bash scripts/restore.sh` for the full project rollback.
- **Without the PC:** Fire TV Settings → Applications → Manage Installed Applications → Lumen Sports → Uninstall.
- Uninstalling deletes the ESPN cookies, the cached scores and the pairing key. Lumen's Sports section disappears by itself.
- On the iPhone, delete the Home Screen icon.
