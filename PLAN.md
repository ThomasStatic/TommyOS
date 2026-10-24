# Fire TV 2-Series — Phase 1 findings and modification plan

Generated 2026-10-22 from read-only diagnostics. Raw data and backup: `backup/20261022-174126/`.

## Device

| | |
|---|---|
| Model | AFTCU864A2, codename `shinagawa` (Amazon-built, Amlogic SoC) |
| OS | Fire OS 8.1.8.5 (RS8185/3885), Android 11 / API 30 |
| Build | RS8185.3885N.0033018555648, dated 2026-09-26 |
| CPU | 4x Cortex-A55, **32-bit userspace only** (`armeabi-v7a`) |
| RAM | 1.9 GB total, ~320 MB available at idle home screen |
| Storage | /data 8.8 GB, 7.0 GB free |
| Display | 1920x1080, 320 dpi (xhdpi) |
| Inputs | HDMI 1 (`…Hdmi1InputService/HW5`, CEC device present), HDMI 2 (`…/HW6`), antenna tuner (`ADTVInputService/HW16`) |
| Packages | 266 total, 7 user-installed (Netflix, YouTube, Spotify, TSN, plus 3 Amazon overlays), 1 already disabled by Amazon (`com.amazon.comms.starktachyon`) |
| Home | `com.amazon.tv.launcher/.ui.HomeActivity_vNext` (priority 950); fallbacks: `firehomestarter`, `servicemenu`, `settings.v2/.system.FallbackHome` |

Differs from the reference repo's tested device (Hisense AFTOLCBC82 / RS8180). This is a newer build on different hardware, so its package list is a starting point only.

## Feasibility

| Goal | Verdict | Basis |
|---|---|---|
| ADB over Wi-Fi | Works | Connected and authorized |
| Disable packages with `pm disable-user --user 0` | **Likely, but must be tested per package** | Worked on RS8180 for 6 packages there; some Amazon packages refuse with SecurityException. The old system-user exploit is patched on builds ≥ RS8153, so we will not rely on it. |
| Replace Amazon's home screen outright | **No** | Fire OS ignores the default-home choice. Amazon's launcher also hosts Settings, so it must stay enabled. |
| Custom launcher shown on Home press | **Yes, with a brief Amazon-home flash** | Accessibility service that reacts when Amazon's home window appears (reference repo's method). Accessibility services can be enabled; only Amazon's `LoganAccessibilityService` is installed, and it isn't enabled. |
| Direct HDMI shortcuts | **Yes** | Standard TV passthrough URI resolves to `com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias` (verified with a read-only query) |
| Launcher app size/perf | Must be lean | 32-bit, ~320 MB free RAM. Amazon's launcher uses 133 MB; Ambient Experience 94 MB. |
| Eliminate all telemetry | **No** | See below |

### Privacy settings found (current values)

All are at their permissive defaults and **synced to your Amazon account** (`…:syncPending` keys), so change them in the Settings UI, not via `settings put`:

- Interest-based ads: ON (`advertisingIdApp:interestBasedAds=1`, `limit_ad_tracking=0`)
- Device usage data: ON (`settingsApp:deviceUsageData=1`)
- Collect app usage data: ON (`settingsApp:appUsageData=1`)
- Usage metrics upload / marketing: ON (`USAGE_METRICS_UPLOAD_ENABLED=1`, `usage_metrics_marketing_enabled=1`, `pact_usage_collection_enabled=1`)
- Screensaver: Amazon Ambient/screensaver service

### ACR (automatic content recognition)

`com.amazon.tv.acr` is present but is a **6.7 KB stub that has never been updated or run** (`/system_ext/priv-app/com.amazon.tv.acr-stub`, no `/data/app` copy, no process). Its manifest requests HDMI-CEC, key-event and Alexa permissions, not frame capture. Amazon can deliver the full app later through an update. Plan: disable the stub so it can't be activated, and recheck after each Fire OS update.

### Network baseline

A single snapshot of `/proc/net/tcp` at idle showed one established connection: `com.amazon.vizzini` (Alexa voice) → 35.71.160.28:443. One snapshot is not evidence of absence: telemetry is batched and uploaded periodically. See "Verification".

## Component classification

### Advertising / promotion
| Package | Notes | Proposed |
|---|---|---|
| `com.amazon.kso.blackbird` | Sponsored screensaver / lock-screen ads | Disable (low risk) |
| `com.amazon.shoptv.client` | Shopping upsell (updated in /data) | Disable (low risk) |
| `com.amazon.hybridadidservice` | Advertising-ID service (`UpdateMauiJobService`) | Disable (low risk) |
| `com.amazon.tv.democontent.provider` | Retail demo content | Disable (low risk) |
| `com.amazon.sneakpeek` | Launcher preview/promo content (inferred from name) | Disable, test launcher after (medium) |
| `com.amazon.ftvads.deeplinking` | Ad deep links, has secure-video-capture permission | Try; protected on reference device |

### Telemetry / analytics / diagnostics
| Package | Notes | Proposed |
|---|---|---|
| `com.amazon.device.telemetry.emitter` | Telemetry emitter (running) | Disable (low risk) |
| `com.amazon.wirelessmetrics.service` | Wi-Fi metrics | Disable (low risk) |
| `com.amazon.logan` | `AppUsageMetricsReporter` job, frame-buffer + usage-stats permissions, 29 MB | Disable (medium) |
| `com.amazon.diode` | `PeriodicAnalysisAndUploadJob` | Disable (medium) |
| `com.amazon.perfcollection` | Performance metrics + remote config | Disable (medium) |
| `com.amazon.neo.minerva`, `…minerva.logcat` | Metrics pipeline, reads logcat | Try (medium-high; may be protected) |
| `com.amazon.client.metrics.api`, `com.amazon.device.metrics`, `com.amazon.device.crashmanager` | Core metrics/crash | **Leave**: protected on reference device; crash manager also holds OTA-related jobs |
| `com.amazon.device.logmanager`, `com.amazon.tifobserver` | Run as `android.uid.system` | **Leave**: shared system UID |

### Recommendations / content
| Package | Notes | Proposed |
|---|---|---|
| `com.amazon.media.recommendations` | Shares `android.uid.system` | **Leave** (risk to system process) |
| `com.amazon.tv.ftvambient` | Ambient Experience, 94 MB RAM | Turn off in UI first; optional disable after choosing another screensaver |
| `com.amazon.tv.acr` | Stub (see above) | Disable |
| `com.amazon.storm.lightning.tutorial`, `com.amazon.tmm.tutorial` | Tutorials | Disable (low risk) |
| `com.amazon.minitv.android.app`, `com.amazon.tv.earlyaccess` | Stubs | Disable (low risk) |

### Essential: do not touch
Launcher & Settings (`tv.launcher`, `tv.settings.*`, `firehomestarter`, `tv.quicksettings*`), Live TV / HDMI (`tv.livetv`, `droidlogic.*`, `tv.routing`, `tv.inputpreference.service`, `firebat`, `tv.arc`), updates (`device.software.ota*`, `tv.forcedotaupdater.v2`), account/DRM/auth (`dcp*`, `identity.*`, `imp`, `cpl`, `dpcclient`), messaging gateway (`tcomm*`, `device.messaging`), remote/Bluetooth (`fireinputdevices`, `device.bluetooth*`, `autopairservice`), casting (`whisperplay*`, `whisperlink`, `cast.sink`, `dialservice`, AirPlay), `webview.chromium`, `tv.ime`, Appstore (`venezia`: needed for app updates), Alexa (`vizzini`, `alexa*`: optional, kept unless you say otherwise).

## Telemetry that cannot be eliminated (without root/network blocking)

- **Protected metrics/crash packages** (`client.metrics.api`, `device.metrics`, `crashmanager`): refuse disable on comparable builds.
- **System-UID components** (`logmanager`, `tifobserver`, `media.recommendations`): disabling risks system stability.
- **Amazon's launcher itself** stays enabled (hosts Settings) and still runs jobs like `ComradeStateUploaderService` and ad-manager metrics, even when hidden behind the custom launcher.
- **OTA, account auth, device messaging (tcomm), Appstore**: phone home by design; disabling breaks updates/apps.
- **Streaming apps** (Netflix, YouTube, etc.) have their own analytics, outside our control.
- **Fire OS updates may silently re-enable disabled packages** or ship new ones (e.g. a full ACR app). The restore/re-apply scripts must be re-run after updates.
- Opt-outs in Settings are policy controls: Amazon honors them server-side; they can't be verified from the device alone.

## Verification approach

On-device only (no root): repeated `/proc/net/tcp` snapshots mapped to packages before vs. after, plus `dumpsys jobscheduler` to confirm disabled packages' jobs are gone. This shows *which local packages* open connections, not their content.

For real verification, a DNS log is needed: a router with query logging, Pi-hole, or NextDNS. Optional, outside ADB, and the only way to support any claim like "X no longer reaches Amazon".

## Phase 2: proposed order (each step needs your approval)

0. **You, in Settings UI**: Preferences → Privacy Settings → Device Usage Data OFF, Collect App Usage Data OFF, Interest-based Ads OFF. Preferences → Featured Content → autoplay OFF / sponsored content OFF. Display & Sounds → Ambient Experience / Screensaver → turn off sponsored content. I re-read the settings to confirm.
1. **Trial**: disable one low-risk package (`com.amazon.storm.lightning.tutorial`), confirm it reports disabled, re-enable it, confirm restore works on this build.
2. **Low-risk batch**, one package per command, checking the TV after each group: blackbird, shoptv.client, hybridadidservice, democontent.provider, telemetry.emitter, wirelessmetrics.service, tutorials, stubs (acr, minitv, earlyaccess).
3. Reboot, re-enable ADB, run the functional checklist.
4. **Medium-risk, individually**: sneakpeek, logan, diode, perfcollection, then attempts on minerva / ftvads.deeplinking.
4b. **Alexa & antenna (user doesn't use either; added 2026-10-22)**, individually, medium risk:
   `com.amazon.vizzini` (Alexa client, 52 MB RAM, holds the only idle cloud connection), `com.amazon.tv.alexaalerts`, `com.amazon.tv.alexanotifications`, `com.amazon.alexadirectivebrokerservice`, smart-home (`smarthomemapviewapp`, `tv.matter`, `tv.mattercohost`), antenna scan (`tv.channelscan`).
   Keep `tv.livetv` and `droidlogic.*` (HDMI depends on them). Watch for: mic-button error popups, Settings screens that embed Alexa, remote pairing.
5. Reboot + checklist again; network snapshot comparison.

**Status:** steps 4/4b done 2026-10-22. Disabled and survived reboot: sneakpeek, logan, perfcollection, neo.minerva(+logcat), channelscan, smarthomemapviewapp, tv.matter, tv.mattercohost, tv.alexaalerts, tv.alexanotifications, alexadirectivebrokerservice. **Refused (protected):** diode, ftvads.deeplinking. **Re-enabled by Fire OS at boot:** vizzini (explicit enabled=1 within ~30 s of boot; likely Amazon's `AmazonPackageManagerService`, unconfirmed). Network at 100 s after boot (4 snapshots): 18 → 15 distinct packages connecting; only the minerva drop is attributable to a change. Step 2 done 2026-10-22 (11 packages disabled, none refused). Survived reboot; ADB stays enabled across reboots on this TV. Two boot crashes seen (`amazon.speech.davs.davcservice` background-start IllegalStateException; native SIGSEGV in `com.amazon.franktvinput` MAPCommonThread). Neither involves a disabled package; pre-change baseline unknown. Step 0 verified 2026-10-22: 6 privacy keys flipped and synced to the account (snapshot `backup/20261022-175955-after-step0/`); `amz_limit_ad_tracking`, `non_amz_limit_ad_tracking` and `pact_usage_collection_enabled` have no UI toggle. Ambient Experience is still on (`ambient_experience_enabled=1`). Step 1 trial passed 2026-10-22 (disable-user accepted on RS8185; restore.sh re-enabled it).

Every change is `pm disable-user --user 0` (no uninstall), logged to `state/applied.log`; `scripts/restore.sh` re-enables everything logged plus undoes any launcher settings.

## Phase 3: launcher design (summary)

- Kotlin, plain Android Views with hand-tuned focus animations. No Compose, no native code (32-bit device, tight RAM); target < 30 MB RSS.
- **No INTERNET permission**: it can't show promos or phone home, and anyone can verify that from the manifest.
- Dark cinematic theme: blurred wallpaper layer, translucent dock, large clock, scale + glow focus animation, system font (or bundled open-license font).
- Customizable dock (reorder/hide apps), wallpaper picker (bundled + images pushed to `/sdcard/Wallpapers`), Settings tile (`com.amazon.tv.settings.v2`), HDMI 1/2 and Antenna tiles via passthrough URIs.
- Home redirect via accessibility service (expect a brief Amazon-home flash); "Use Amazon Home" escape hatch inside the launcher.
- Build needs JDK 17 + Android SDK command-line tools on this PC (install requires approval).

**Status (2026-10-22):** "Lumen" built at `launcher/` (Kotlin, zero dependencies, 57 KB, **zero permissions**, no native code, minSdk 25 / targetSdk 30). Signing key `launcher/lumen-release.jks` + `keystore.properties`: keep both, updates need the same key. Installed 2026-10-22 (step A). Step B done 2026-10-22: redirect enabled; fixed Settings hijack (Amazon routes Settings through HomeActivity_vNext → redirect now waits 450 ms and cancels if another window appears; Settings tile opens MainSettingsActivity directly). 7/7 redirect tests passed; survives reboot (redirect fired at boot). Boot log captured: timezone service gets CA/UTC−5 from DCAS but logs "TZ update not required" (its cached value is America/Montreal) while system TZ is GMT → user to set manually. Memory 161 → 100 MB PSS after fixes (non-overlapping card alpha, tile-sized banners, 720p built-in wallpapers); Amazon launcher is 103 MB. **Open issue:** TV timezone was America/Montreal in Phase 1 and became GMT ~1 min after the first reboot following step 2 (`kindleautomatictimezone` runs ~25 s after boot; `time_zone_detector` reports device TZ GMT, not initialized). Cause unconfirmed; capture next boot's log live. Install/redirect via `scripts/launcher.sh`; `restore.sh` restores original a11y settings (`enabled_accessibility_services=null`, `accessibility_enabled=0`) and uninstalls.

**Lumen 1.1–1.3 (2026-10-22):** 1.1 tried accessibility key observation to detect Home: on this build the Home key is never delivered to accessibility services (0/6 real-remote presses), so it was removed in 1.3. 1.2 moved the redirect service to its own `:redirect` process (~5 MB PSS) after lmkd killed Lumen during Netflix playback and the service took ~80 s to be restarted; UI now drops wallpaper/artwork on `TRIM_MEMORY_BACKGROUND` (114 → 67 MB). 1.3 adds a wallpaper cover in a `TYPE_ACCESSIBILITY_OVERLAY` during the 0.9 s settle (removed instantly if another window appears, faded when Lumen arrives, failsafe 3 s). Settle raised to 900 ms after measuring cold Settings at 0.74 s. Verified: redirect from Netflix/Spotify/Settings, generic Settings route uncovered 3/3, long-press Home still opens Amazon's quick menu, survives reboot, no lingering overlay. Timezone set manually by user to America/New_York (persists).

**Lumen 2.0 design system (2026-10-22):** `Theme.kt` holds tokens (`Ink` colours, `Space` scale, `TileStyle`, `GlassStyle`) and two themes. Cinema (default): `activity_home_cinema.xml`, centred floating glass dock (`FrostedPanel` is now the container, anti-aliased shader glass), white-shadow focus glow (no ring), app name above dock (apps only), `ChipView` control pills (white-on-focus), opaque All Apps sheet, neutral Obsidian/Silver Mist/Eclipse wallpapers. Classic: unchanged original layout. Switch: Lumen settings → Look → Theme (swaps to the theme's default wallpaper unless a custom one is set). Amazon system shortcuts (profile picker, quick settings, screensaver, SSM) hidden by default; Amazon's launcher never listed. Lumen PSS ~46–90 MB.

## Phase 4: test checklist

D-pad / select / back / home / menu / play-pause / volume / mute / mic button; Netflix, YouTube, Spotify, TSN playback incl. 4K/HDR & DRM; HDMI 1, HDMI 2, antenna, CEC control of HDMI-1 device; Wi-Fi reconnect; sleep/wake via remote and CEC; cold reboot (launcher returns, disabled packages stay disabled); Settings reachable; OTA check screen opens; Appstore installs/updates.

## Lumen 2.1: visual polish (2026-10-24)
- Scenes: Blue Hour (new Cinema default; one-time migration from Obsidian only), Ember Peaks, Still Lake, Moonlit Dunes, Aurora, Silk. Original procedural imagery, about 90 KB each; decoded at 1280x720 (3.7 MB, same as the gradients). Each image already darkens towards the clock and the bottom, so the home-screen shading is lighter.
- Design tokens: `Type` scale (clock 88, title 34, headline 28, body 20, label 17, caption 16, overline 15 sp), `Motion` policy, a two-tone glass style (darkened, lit top edge).
- Dock: tiles 160x90, focus = 1.12 scale + 5 dp rise + white glow + diagonal sheen; resting tiles dim to 76 %. The app name glides above the focused tile, kept within the glass.
- Menus: `MenuRow` (white focus pill, label + value) used by the centred options dialog (with app artwork) and Lumen settings. Classic keeps the right-hand sheet.
- All Apps: 5 centred columns over the frosted, shaded scene; home layers hidden while it is open.
- Memory: about 100 MB total after visiting every screen, including settings previews. Settings frees its thumbnails and previews when it closes (it reached 180 MB before that fix). The low-memory trim handler is unchanged.
- Verified: Cinema and Classic both render; switching themes in both directions works; the dialog's focus and Back work; no crashes; Lumen came back up after an unplanned power cut (unplugged).

