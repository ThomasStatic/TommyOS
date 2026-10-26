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

**Live and played:** while a game is under way (up to 4 h after kickoff), each poll also reads ESPN's public NFL scoreboard (`site.api.espn.com`, one day's games, about 17 KB gzipped, sent without cookies). Its pre / in / post status moves a player to "played" at the final whistle and keeps one "live" through a long overtime. If the scoreboard can't be read, a player counts as live until 4 h after kickoff. Added 2026-10-25, after 1 pm players were still shown as live at 4:49 pm.

**Broadcast redesign (2026-10-25).** The six-page rotation is replaced by:
- **A pinned board on the left:** your matchup's actual scores (large), projected finals (small, under a PROJ caption, or FINAL), and a triangle pointing at the leader, whose score stays bright while the trailer's dims.
- **A crawl beside it,** in priority order: LIVE (red dot), TO PLAY, PLAYED, ON BYE, then LEAGUE. Each group shows your players under a filled team chip, then your opponent's under an outlined one. Upcoming players show a dimmed PROJ value.
- **An amber status pill on the right:** `AS OF 4:12 PM` once the scores are overdue, prefixed with `ESPN UNAVAILABLE` or `ESPN SIGN-IN EXPIRED`. The overdue threshold now comes from the normal poll cadence, so a backed-off failing poll no longer delays it.
- **Highlights:** a starter gaining or losing 4+ points between consecutive fetches, or the lead changing hands, replaces the crawl for a few seconds (gold for good news, crimson for bad, ▲/▼ with the delta). Only consecutive fetches no more than 5 minutes apart are compared, so nothing replays. Reworked for production below.
- **Sizing and settings:** text is ~20 % larger (crawl 23 dp, scores 29 dp, on a 56 dp bar). The bar sits inside Android TV's safe margins (48 dp sides, 27 dp bottom), and text stays opaque: `opacity` now sets only the background. Height, opacity, speed, fps and edge are kept on the TV (`scripts/sports.sh set …`). `scripts/sports.sh preview highlight|stale|offline` shows each state on the current scores.

The crawl is recorded once per content change; each frame only moves it (a RenderNode translation). Measured over 30 s of live TSN playback (percent of one core):

| | Ticker | SurfaceFlinger | Missed frames |
|---|---|---|---|
| Paged (before) | 3 % | 17 % | n/a |
| Crawl, 60 fps | 25–26 % | 31–33 % | 1–50 % (three runs) |
| Crawl, 30 fps (default) | 15 % | 25 % | 0.1 % |

- Redrawing nothing per frame barely helped at 60 fps. Caching the text in GPU textures (tiles) didn't help either and was removed.
- The cost is per frame. HWC gives TSN's UI layer the hardware plane, so SurfaceFlinger GPU-composites the ticker (`CLIENT`) on every frame it produces, and the GPU is shared with the video.
- 30 fps halves the cost and misses almost no frames. 60 fps (`set fps 60`) misses enough to stutter.
- Memory: 63–74 MB PSS, 9 MB of it graphics.

A non-focusable overlay never sees remote keys, so it can't tell when a player's controls appear. `set edge top` moves it out of their way instead.

## Production readiness (2026-10-25 evening)

Everything below was tested on the TV or against real recorded ESPN refreshes.

**Alerts: detection.** Audited against 147 real refreshes (every 30 s, 18:34–19:49 during the 4:25 games, none failed), replayed through the app's own parser from all 14 teams' sides (`RecordedSeriesTest`; the recording stays outside the repo because it names league members). The first 97 refreshes of one matchup, names replaced, are a committed fixture (`ReplayFixtureTest`).
- Real data had many identical consecutive responses (43 of 146 in your matchup, 602 across all sides), real decreases (38 across all sides, from −0.3 while live to a D/ST −5.0 as points were allowed), single-refresh gains up to +10.2, and no stale or out-of-order responses. The 8 real events became 16 banners (each seen from both teams' sides), all worded as points.
- Fixed: changes are compared in hundredths (doubles made an exact 4.0 fall short); the lead is tracked through ties (12–5, 12–12, 12–16 now reports the change); detection is stateful per service (`ScoreWatch`, seeded from the cache, so a restart replays nothing).
- Wording is points only: `POINTS UP` / `POINTS DOWN` / `LEAD CHANGE`. A delta can't say whether it was one play, several or a stat correction, so nothing says touchdown or play.
- Skipped on purpose: a lineup change (a new starter has no previous points), a week change, another matchup, or refreshes more than 5 min apart.

**Alerts: pacing** (`Alerts`). Banners last `alert` seconds (default 5; `set alert 0` turns them off). A starter's later change merges into his waiting one, and a gain reversed before it is shown cancels out, as does a lead change that reverses. At most 2 banners in a row, then at least 30 s of crawl; anything waiting over a minute is dropped, and at most 4 wait (the biggest kept). The 30 s comes from the real data: a 10 s gap let one matchup show 4 banners in 36 s (19:00:41–19:01:17); now that burst is two runs 40 s apart.

**Readability.** At 2.5 m from a 40-inch 1080p screen (0.46 mm pixels) the board's projections measured 14 arcmin and the PROJ caption 10, below the ~16–20 usually needed; scores (26) and crawl text (21) were fine. Projections are now 20 dp (18 arcmin) and light blue, the caption 14 dp, and projected values in the crawl use the same blue, so white always means points scored. Scores went from 29 to 28 dp to fit. The 68 dp setting still scales everything. Arcminutes are computed, not judged by eye: confirm from the couch.

**Credentials.** No log line in the app contains cookies, SWID or responses, and none appeared in logcat (all buffers) or the activity, intent, service and package dumps. But `am broadcast` extras are kept in plain text in the system's broadcast history (`dumpsys activity broadcasts`, included in bug reports), so every `sports.sh auth` left the cookie there until it rotated out or the TV rebooted. Auth now goes through a DUMP-guarded `ContentProvider.call()` (`AuthProvider`); tested with a fake cookie (delivered, then 401) and the real one: no trace in any dump.

**Disabled and asleep.** Hidden: no window, no service, no ESPN thread, 0.04 s of CPU in 75 s, process cached. Asleep: polling paused (no requests in 87 s, lossless log) but the crawl's frame callbacks still ran, costing ~5 % of a core with nothing drawn; now the crawl pauses too (0 CPU ticks and 0 frames in 30 s asleep, resumes on wake).

**On-demand control.** A global remote shortcut needs key interception: an accessibility service filtering every key system-wide (what button remappers do). That is the fragile interception ruled out, so it isn't done. Fire OS gives third-party apps no quick-settings tile, and launching an activity would take focus from TSN. The TV does accept inbound LAN connections (tested on port 8737), so the proposal is a phone page:
- A small standby listener in the Sports app (no window, no polling: a thread blocked on accept) serving one page with Show / Hide / Top / Bottom and the status.
- Paired once from the PC: `sports.sh phone` generates a random 128-bit token on the TV and prints a URL with it in the fragment (never sent in logs or referrers); the page sends it in a header. Requests without it are refused; wrong tokens are rate-limited.
- LAN only (bound to the Wi-Fi address), no ESPN data or credentials exposed, off unless enabled; a boot receiver for it would be opt-in, keeping "off after reboot" the default.

**Reliability on the TV** (over TSN live playback unless noted):
- Service killed (`am force-stop`) mid-playback: window gone at once, TSN kept focus and kept playing, nothing restarted in 60 s; showing again drew the cached scores and fetched within 2 s.
- App switching, 5× TSN ↔ Home (Lumen): same process throughout, ticker on screen in both, no crashes or ANRs. TSN returns to its browse screen, not the player (TSN's behaviour).
- Stream transitions TSN1 → TSN2 → TSN1: ticker stayed up, TSN focused and playing each time; 15 % / 25 % CPU (ticker / SurfaceFlinger), 0.55 % missed frames.
- Sign-in expiry (fake credentials, ESPN 401): `ESPN SIGN-IN EXPIRED · AS OF 6:46 PM` with the cached scores, one request and no retries; real credentials restored recovery within 2 s.
- Player controls: TSN's control row (y ≈ 920 px) and channel strip lie under a bottom-edge ticker (914–1026 px); at the top edge only TSN's title line is covered. The bottom bar also hides TSN's lowest browse row and its focus highlight.
- Network loss (Wi-Fi off for 250 s from a script on the TV, twice): cached scores stayed up, `ESPN UNAVAILABLE · AS OF 7:16 PM` after the third failure; TSN's own stream failed with its error dialog, which the ticker didn't cover. Recovery took 176 s after Wi-Fi returned, because by then the backoff was 4 min; with a network callback (new `ACCESS_NETWORK_STATE`, a normal permission) the retry came 2 s after Wi-Fi returned, and polling went back to 30 s.
- Asleep (Sleep key, 60–90 s, three times): see above; on wake Fire OS returns to Home and TSN is paused (Fire OS's behaviour), and the ticker resumed with a fetch within 1.5 s.
- Restart (`adb reboot`): after 100 s of uptime the app-op was still `allow`, with no process, window or requests (off until asked). Shown again, it kept its settings, decrypted its credentials and fetched within 1.2 s. Fire OS's "Unable to Detect Your Remote" dialog after boot hides all app overlays (system UI wins), as expected.
- Recommendation from these tests: `set edge top` while watching TSN. The default stays at the bottom, as approved.

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

## Release hardening (2026-10-25/26)

Sports 1.0 (versionCode 2) and Lumen 2.2 (versionCode 7) on the TV: the control layer, Lumen's Sports section, the phone controller and auto mode. Design and residual risks: [SECURITY.md](SECURITY.md); setup and recovery: [SETUP.md](SETUP.md); procedure: [TEST-PLAN.md](TEST-PLAN.md).

**Measured on the TV**
| Test | Result |
|---|---|
| Unit tests (`sports/test.sh`) and page crypto (16 checks, same vector in Kotlin, JS, Python) | pass |
| Audit (`sports_audit.py`), before and after tonight's fixes | 25/25: exported parts guarded, permission held only by the two Lumen apps, listener only on 10.0.0.213:8737, real cookie, SWID and pairing key absent from every log buffer and dump |
| Phone protocol (`phone_test.py`) | 18/18 with 20 show/hide cycles (median 111 ms, max 1.1 s on the first, cold show); 17/17 in 4 later runs |
| Phone control on, ticker off | 0 CPU ticks in 60 s, 12 MB PSS |
| Lumen → Fantasy ticker On | on screen ~1.4 s after OK (including ADB polling), first ESPN answer 0.9 s after the window |
| Disruptions (`chaos.sh`) | 20 toggles (RSS 104 → 118 MB, within limit); TSN → Netflix → Lumen ×2, one process, no crash; 60 s asleep: 0 requests, 0 frames, fetch on wake; force-stop: gone at once, focus kept, nothing restarted in 60 s; Wi-Fi off 60–120 s ×5: scores stayed up, fetch after return, listener rebound (once not, see below); reboot: ticker off, app-op kept, listener back |
| Auto mode over real TSN (TSN1) | hidden on TSN's browse screens, the screensaver and Lumen; shown in TSN's player (`BellDivaTvPlaybackActivity`); hidden again after leaving it |
| Soak, new build, 30 min over the screensaver then asleep | awake: ~30 fps, 0.04 % janky, ticker ~9 % of a core; asleep: 0 frames, ~0.05 %, 1 ESPN fetch in 30 min; no crashes; PSS 51 → 33 MB |
| Soak, previous build, 2.5 h over Sunday Night Football (baseline) | 30 fps, 0.24 % janky, ticker 15 %, SurfaceFlinger 26 %, TSN 47 %, 35 fetches/h, 0 failures |

**Fixed tonight**
- Lumen's status line said "p.m.." and "Waiting for ESPN" until revisited; it now checks again a moment after showing and says when scores were last updated while off.
- Focused settings rows scrolled under the ticker; the list now keeps 112 dp clear at both edges.
- The pairing card was wider than the screen, see-through and partly under the ticker; it now fits, is opaque and sits at the edge away from the ticker.
- Phone listener: a failed rebind is retried (every 5 s, then 30 s). In the first 120 s Wi-Fi outage the listener wasn't back 45 s later; four later outages didn't reproduce it, and the log had rotated.
- The listener closes gently after refusing a request, so the refusal isn't lost to a TCP reset.
- `launcher.sh install` reopens Lumen when the update left the TV on Amazon's home.
- Test fixes: the sleep check counted the screen-off transition; the Wi-Fi check waited for a fetch that wasn't due off-hours and read a log that rotates.

**Not measured yet**
- The iPhone: scanning, Add to Home Screen keeping the pairing, and how fast it feels on the couch.
- A full Sunday of the new build over live games (the TEST-PLAN procedure), including real scoring alerts and auto mode across TSN channel changes.
- Note on numbers: the soak summary counts a sleeping TV as "shown" (the window exists), and per-app network counters are flushed in batches, so short windows mix in earlier traffic; fetch counts come from the ticker's own log.
