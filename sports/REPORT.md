# Lumen Sports: overlay feasibility (Phase 1–3)

Tested 2026-10-24 on the AFTCU864A2, Fire OS 8.1.8.5 (RS8185/3885, Android 11 / API 30), over TSN 3.1.5 (`ca.tsn.mobile.android`) live CFL playback.
PoC source: `sports/` (package `app.lumen.sports`, 24 KB APK). Control: `scripts/sports.sh`. Screenshots: `screens/sports-poc/` (not committed).

**Verdict: feasible.** A third-party `TYPE_APPLICATION_OVERLAY` window can be granted, draws above TSN (including DRM playback using the secure decoder), and leaves playback and remote input untouched. **Confirmed by eye** by the user, who watched the ticker over the live CFL broadcast during the test. The screenshots still show black where the game is, because protected video can't be screen-captured.

## Phase 1: findings

**Lumen today.** `app.lumen.home`, versionCode 6 / 2.1, **zero permissions**, no INTERNET. Home redirect = `HomeRedirectService`, an accessibility service in its own `:redirect` process that only receives `typeWindowStateChanged` (`canRetrieveWindowContent=false`). It already draws a `TYPE_ACCESSIBILITY_OVERLAY` cover during the 0.9 s Home settle, which proves overlays can render on this device, but only as an accessibility window.

**Overlay permission on this build.**
- `android.settings.action.MANAGE_OVERLAY_PERMISSION` resolves to Amazon's `CTSDummyIntentHandler`, a stub. There's no UI to grant it.
- `appops set <pkg> SYSTEM_ALERT_WINDOW allow` over ADB works, **provided the APK declares `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`**. That matches the community reports ([dumbtv #62](https://github.com/humblemuzzu/dumbtv/pull/62), [#8](https://github.com/humblemuzzu/dumbtv/issues/8)). [dumbtv #31](https://github.com/humblemuzzu/dumbtv/pull/31) says Fire OS ignores the grant, but it tested without the manifest declaration.
- Amazon's stated position is that SAW is "not supported on Fire TV devices" ([Amazon forum, 2024](https://community.amazondeveloper.com/t/firetv-overlay-permissions/6053)). No source showed a third-party overlay actually drawing on Fire OS 8; this test is the first evidence for this device.
- No third-party app on the TV had the op set beforehand (Spotify declares SAW but has the op at `default`).

**Minimum reversible change.** It took one separate APK plus one app-op. Lumen stays at zero permissions, and no secure or global settings change. Uninstalling the APK removes the app-op too.

## Phase 2: proof of concept results

| Test | Result |
|---|---|
| Show **without** grant (negative control) | Refused: `BadTokenException … permission denied for window type 2038`; service cleans up and stops |
| Grant (`appops set … allow`) | Reads back `allow`; `canDrawOverlays=true` |
| Over Ambient screensaver, Lumen, TSN browse UI | Renders flush with the bottom edge; ~60 fps |
| Over **TSN full-screen live playback** | Window `isVisible=true`, composited above TSN's `SurfaceView`; TSN holds `OMX.amlogic.avc.decoder.awesome2.secure` (Widevine secure path); playback `state=3` throughout. **Confirmed visually on the TV by the user** |
| Remote input | Focus never moves to the overlay (`FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCHABLE`). D-pad moved Lumen's focus; play/pause toggled TSN (3→2→3) with the ticker up |
| Opacity 0.2 / 0.5 / 0.9 / 1.0 | Live change via `WindowManager.LayoutParams.alpha`, no re-add |
| Amazon Settings, Parental Controls PIN screen | Ticker stays visible (Amazon does not hide overlays there) |
| Long-press-Home quick menu | Amazon's panel draws **above** the ticker (system UI is never blocked) |
| App switching (TSN → Home/Lumen → Netflix → TSN) | Overlay persists across all; Lumen's Home redirect still works |
| Sleep → wake | Overlay survives; TSN resumed playing |
| `am force-stop` (simulated crash/LMK kill) | Window disappears instantly; TSN unaffected; stays off (`START_NOT_STICKY`) |
| Reboot | Grant persists (`allow`, not reset by Fire OS ~70 s after boot); ticker stays **off** until asked; shows normally afterwards |
| 3-min soak during playback | ~60 fps heartbeats; process at `adj=200` (perceptible), so lmkd killed cached apps, not the ticker; 24 MB PSS |
| Hide / show mid-game | Instant; TSN `state=3`, focus unchanged |
| Revoke to `default` while showing | **Window stays visible** (AOSP treats `default` as visible for existing windows); new windows are refused |
| `appops set … ignore` while showing | Hides instantly (`mAppOpVisibility=false`); `allow` brings it back |
| Remove during playback (hide → revoke → uninstall) | TSN kept playing; package and app-op gone |

**Performance (during TSN playback):**

| | Ticker CPU | SurfaceFlinger CPU | Ticker frames >16 ms |
|---|---|---|---|
| Continuous scroll (60 dp/s) | ~23 % of a core | ~34 % | ~34 % |
| Static | 0 % | ~16 % | n/a |

TSN itself uses ~40 % of a core and 310–330 MB RSS. The device signals `TRIM_MEMORY_RUNNING_CRITICAL` when playback starts.

**Permission state:**
- Before: package absent, app-op `default`.
- After: package absent, app-op absent. Restored and logged in `state/applied.log`.
- Lumen's accessibility settings were untouched.
- One unlogged step: the `ignore` kill-switch test, set and reverted within seconds.

## Phase 3: report

- **Can the permission be granted?** Yes, over ADB only, and only with the manifest declaration. It survives reboot.
- **Does it render above TSN video?** Yes at the compositor level, including during secure-decoder playback. Amlogic puts video on a hardware plane beneath the UI layer, the same way Amazon's own banners appear over Netflix. Confirmed by eye by the user.
- **Do playback and remote keep working?** Yes. Input never reaches the overlay, and adding, hiding, killing or uninstalling it doesn't disturb TSN.

**Limitations:**
- Unsupported by Amazon. A Fire OS update could block it, so `scripts/verify.sh` should gain an overlay check.
- Continuous scrolling is expensive on this SoC. Use paged items with a short crossfade (static between changes).
- The bar covers the bottom ~88 px of every app: TSN's lowest browse row and Lumen's control chips. It sits below TSN's playback controls.
- It shows everywhere, including Settings and the PIN screen, unless it is told when to hide.
- Not tested: HDMI 1/2 passthrough, 4K/HDR streams, Netflix or YouTube playback, multi-hour soak.
- Theoretical: the ticker *appearing* during Lumen's 0.9 s Home settle would cancel that one redirect, because Lumen counts any non-system window event. The fix is to add `app.lumen.sports` to `TRANSIENT_WINDOWS`.

**Recommended architecture.**
- A separate app, `app.lumen.sports`, with a foreground service hosting one `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`.
- `START_NOT_STICKY` and no boot receiver.
- Signed with Lumen's key, so Lumen can control it through a signature-level permission. The PoC's exported service must not ship.
- Not the accessibility-overlay route. It works, but it would tie the ticker to Lumen's Home service and to accessibility.

**Safest on/off strategy:**
1. Leave the app-op at `allow`. It's inert unless the service runs.
2. *On:* start the service (Lumen toggle, or `scripts/sports.sh show`).
3. *Off:* the `HIDE` action removes the window and stops the service.
4. *Emergency, from the PC:* `appops set app.lumen.sports SYSTEM_ALERT_WINDOW ignore` hides it immediately whatever the app does.
5. *Emergency, without the PC:* Settings → Applications → Lumen Sports → Force stop.
6. *Remove:* hide, then revoke to `default`, then uninstall. `scripts/restore.sh` restores the logged app-op and uninstalls; uninstalling removes the window too.

Never rely on revoking to `default` alone to hide it.

## Stage 2: ESPN Fantasy Football

Implemented in `sports/` (see the code's KDoc); unit-tested at four seams (`EspnParser`, `TickerItems`, `PollPolicy`, `SnapshotCache`; synthetic fixtures).

**Verified against the live API on 2026-10-24** (league 2061761840, week 7, 14 teams):
- `lm-api-reads.fantasy.espn.com` with the `espn_s2` + `SWID` cookies returns 200; without them, `401 AUTH_LEAGUE_NOT_VISIBLE`.
- The `x-fantasy-filter` header limits the schedule to the current week (7 matchups, 94 KB).
- Your team is found from `SWID` in `teams[].owners`.
- Starters' `appliedStatTotal` (every slot except 20 bench and 21 IR) sums exactly to each team's `totalPointsLive`.
- NFL kickoffs: `proGamesByScoringPeriod` covers 33 teams across 18 weeks.
- On the TV, the first fetch took under a second, and the ticker showed all 7 matchups on one page (emoji team abbreviations render).

**Deferred:**
- The Lumen toggle. It needs a signature permission on the service, which is DUMP-guarded for now.
- Edge choice and auto mode.

**Matchup detail (2026-10-25).** The ticker now pages through, in order:
1. Your matchup, with both full team names (`teams[].name`) and projections.
2. Both teams' starters, grouped as live, played, still to play, then on a bye (yours before your opponent's). Each group starts a new page, and long groups split 3 players at a time.
   - If the NFL schedule can't be read, game state is unknown: each team's starters are listed together with their points.
   - Players still to play show ESPN's weekly projection: the `stats` entry with `statSourceId` 1, `statSplitTypeId` 1 and the current scoring period.
3. The rest of the league, abbreviated, starting a new page.

Checked on the TV during the 1 pm games: 7 pages, about 42 s per cycle, nothing cut off.

**Known limitation:** a player stays "live" until 4 h after kickoff, then moves to "played". Each NFL game has a `statsOfficial` flag (true for Thursday's finished game, false for Sunday's), which could end that early. Its lag after the final whistle is unknown, and using it would mean re-reading the schedule during games, so it's not used yet.

The original proposal follows.

### Proposal

**Data source.** ESPN's undocumented league API, which community clients reach as `https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/{year}/segments/0/leagues/{leagueId}?view=mMatchupScore&view=mScoreboard&view=mTeam&scoringPeriodId={week}`. Re-verify the host and views when stage 2 starts. Public leagues need no credentials. Private leagues need the `espn_s2` and `SWID` cookies from a logged-in browser. Treat the API as breakable: one parser module with tolerant JSON handling and a visible "data unavailable" state.

**Authentication: no password ever touches the app.**
- You copy `espn_s2` and `SWID` once from a desktop browser into a local file on the PC (git-ignored), and `scripts/sports.sh auth` sends them over ADB.
- They go to a receiver protected by a permission only the shell holds (e.g. `android.permission.DUMP`), so no other app can write credentials.
- The app encrypts them with an Android Keystore AES-GCM key (non-exportable) in no-backup storage (`allowBackup=false`) and never logs them.
- A 401/403 shows "ESPN sign-in expired" on the ticker. `espn_s2` lasts months, not forever.
- Adds the `INTERNET` permission to the Sports app only; Lumen stays offline.

**Live scoring with a cache.**
- A fetcher on a background thread, using `HttpsURLConnection` + `org.json` with zero dependencies like Lumen. It runs **only while the ticker is enabled and the screen is on**.
- Polling: 30–60 s during NFL game windows (Thu/Sun/Mon evenings, Sun afternoon), backing off to 15 min otherwise and on errors (exponential, capped).
- One request per poll, with conditional/compressed responses where ESPN allows.
- Every good response is normalised to a small immutable `Snapshot` (your matchup, league matchups, your in-progress players, projections, fetched-at time) and written atomically to the cache file.
- On show, the ticker renders the cached snapshot instantly, then refreshes. Network failures never blank it; older data is labelled "as of 1:42 pm".

**Independent rendering.**
- `TickerView` only knows `List<TickerItem>` (text + emphasis); it has no ESPN types and no network.
- Items page every ~6 s with a 250 ms crossfade, which is static between changes per the measurements above. The view doesn't redraw when nothing changed.
- Fetch failures, parse errors and the API changing shape can't stop rendering. If isolation is ever needed, the fetcher moves to its own `:fetch` process.

**Control and placement.**
- A Lumen settings toggle via the signature permission: on/off, opacity, top/bottom edge.
- Optional *auto mode:* show only while TSN (or a chosen app list) is in front. Lumen's existing window-change service would send that signal: it reads package names only, not content and not input.
- Default after reboot: off.
