# Lumen Sports: reliability test plan

How Lumen Sports is shown to survive a full Sunday of football. Results go in [REPORT.md](REPORT.md) (Release hardening).

## Levels

| Level | Runs | Covers |
|---|---|---|
| **Unit** (`sports/test.sh`, about 1 min) | PC, every change | ESPN parsing; ticker text; polling cadence and backoff; cache; alert detection and pacing against 97 real refreshes; settings validation; auto-mode rules; QR encoding (decoded with OpenCV); HTTP limits; request signing (Kotlin, page JavaScript and Python agree), replay, clock skew, rate limits; status without league data |
| **Audit** (`python scripts/sports_audit.py`) | PC + TV, read-only | Exported components and guards, permission holders, overlay app-op, listening sockets, and the real cookie and pairing key absent from every log buffer and system dump |
| **Phone protocol** (`python scripts/phone_test.py`) | PC + TV | Interface binding, refusals (unsigned, replayed, stale, wrong key, wrong Host, oversized, chunked), rate limit and block, show/hide cycles with latency |
| **Disruptions** (`scripts/chaos.sh`) | PC + TV, interrupts playback | App switching (TSN, Netflix, Lumen), sleep and wake, force-stop, 20× toggle, Wi-Fi outage, reboot; each with checked outcomes |
| **Soak** (`scripts/soak.sh run`) | PC + TV, read-only | Every 30 s: ticker / SurfaceFlinger / TSN CPU, RSS (PSS every 5 min), frames and janky frames, window on screen, foreground app, TSN audio; lossless ticker log (fetches, failures, alerts, crashes); app network bytes |
| **Watched** | You, at the TV | What only eyes can judge: the ticker is readable and correct during real scoring, TSN never stutters or loses the stream, the phone feels instant |

## Soak pass criteria (`scripts/soak_summary.py`)

Each comes from earlier measurements on this TV (REPORT.md):
- **No crashes, ANRs or deaths** of the ticker process.
- **Ticker CPU** at most 20 % of a core while shown (measured: 15 % at 30 fps), and at most 1 % while hidden.
- **Memory:** RSS growth under 10 MB per hour after a 30-minute warm-up (leak check).
- **Janky frames** at most 1 % while shown (measured 0.1–0.2 %).
- **ESPN** at most 130 requests per hour (the 30 s cadence plus retries), and never failing for more than 15 minutes while the network is up.

## The Sunday procedure (reproducible)

Before the 1 pm games (about 10 minutes of setup):
1. `bash sports/test.sh` and `python scripts/sports_audit.py`: all pass.
2. On the phone: Show ticker, Position Top, Show it → TSN games (or Always, to stress the drawing).
3. Open TSN and start the early game.
4. On the PC: `bash scripts/soak.sh run 660 30 sunday` (11 hours, 1 pm to midnight). It only reads; the PC can sit idle.

During the day, what you do and what to note (time, what happened):
- Watch normally. Change channels between games (TSN1 to TSN4).
- At least once each: hide and show from the phone mid-play; switch to Netflix and back; press Home and come back; sleep the TV over halftime and wake it.
- **Note anything wrong:** a stutter or a black frame in TSN; the ticker covering something it shouldn't; a stale or wrong score; an alert that seems wrong; the phone being slow (over about 1 s) or failing.

Afterwards: `bash scripts/soak.sh summary backup/soak/sunday-<time>` gives the numbers, and the ticker log lines up with your notes.

**Done when:**
- The summary passes.
- Your notes contain nothing caused by the ticker.
- The phone worked every time.

## Pitfalls when testing on this TV

- **Don't use `uiautomator dump`** while testing Lumen or auto mode: UiAutomation suspends other accessibility services while it runs, so Lumen's Home button service misses app switches (seen as "connected" in its log on every dump). Read focus from `dumpsys window` and look at screenshots instead.
- **logcat rotates within seconds** when Wi-Fi reconnects (the main buffer is 256 KiB), so checks after an outage read the app's status (`lastRefresh`, `phoneListening`), not the log.
- **Outside game windows the ticker polls every 15 min**, so a short outage may contain no fetch at all; `chaos.sh wifi` asks for a refresh afterwards instead of waiting for one.
- **After updating Lumen** the first Home press cold-starts it (about 4 s); key presses sent before it appears land on Amazon's home.

## What only the TV can show (not automatable)

- Protected video can't be screen-captured, so stutter in TSN is judged by eye. The soak does record that TSN's audio track stays started and its process alive.
- Real scoring alerts need live games. The detection is replay-tested; the presentation is checked by eye.
- iPhone behaviour: the camera opening the link, Add to Home Screen keeping the pairing, and how the page feels. Tested by hand on the phone (iOS Safari isn't available on the PC).
