# Lumen Sports: security and privacy

What the app holds, who can reach it, and what is left to trust. Test evidence is in [REPORT.md](REPORT.md) (Release hardening).

## What there is to protect

| Asset | Where | Protection |
|---|---|---|
| ESPN cookies (`espn_s2`, `SWID`) and league ID | `noBackupFilesDir/espn.bin` | AES-GCM, key in the Android Keystore (non-exportable). Written only from `adb shell` (`AuthProvider`, DUMP). Never logged, never in broadcasts, never sent anywhere but ESPN's league API. Uninstall deletes them. |
| League data (teams, players, scores) | `snapshot.json` (no-backup) | App-private. Lumen sees your team name and league ID (status, for its settings screen). The phone controller sends none of it. |
| Phone pairing key (256 random bits) | `noBackupFilesDir/phone.bin` | AES-GCM as above (its own Keystore key). Leaves the TV only as the QR code / link shown in Lumen or printed by `sports.sh phone pair`. Never logged. |
| Control of the overlay | — | See below. |

## Who can control the ticker

| Caller | How | Allowed |
|---|---|---|
| Lumen (`app.lumen.home`) | `ControlProvider` | Holds `app.lumen.sports.permission.CONTROL`, protection level **signature**: only apps signed with Lumen's key can hold it. |
| `adb shell` (the PC) | `ControlProvider`, `AuthProvider` | Holds `android.permission.DUMP`, which third-party apps cannot get. |
| A paired phone | LAN listener (`PhoneService`, `PhoneApi`) | Signed requests only (below). Show, hide, settings, and a status without league data. No credentials, no pairing, no ESPN. |
| Any other app on the TV | — | Refused: `TickerService`, `PhoneService` and `BootReceiver` are not exported; both providers need DUMP or the signature permission to be opened, and check again in `call()`. Lumen's `SportsFollowReceiver` accepts only senders holding the signature permission. |

Lumen gained one permission, the signature-level control permission. It still has no network access.

## The phone controller

**Off by default.** It's switched on in Lumen → Sports → Phone control, or with `sports.sh phone on`. When on, it runs as a foreground service with one thread blocked in `accept()`, and comes back after a reboot or an app update.

**Network exposure.**
- It binds to the TV's current private IPv4 address on port 8737, never to all interfaces, and follows Wi-Fi changes.
- It accepts connections only from private or link-local addresses.
- It takes at most 4 connections at a time, each with a 5 s deadline.
- Every request has hard limits: 4 KB of headers, 32 header lines, 2 KB of body. Chunked bodies are refused.

**Pairing.** Lumen shows a QR code holding `http://<tv>:8737/#k=<key>`.
- The key travels optically from the TV screen to the phone camera, and sits in the URL fragment, which browsers never send to a server.
- The page keeps it in its own storage. The Home Screen bookmark also keeps it in its URL, because iOS gives Home Screen web apps their own storage.
- **Reset pairing** makes a new key, so every phone paired before is refused from then on.
- Phone control **off** stops the listener. **Unpair** (`sports.sh phone unpair`) deletes the key.

**Requests.** Each API call carries `X-Lumen-Time`, `X-Lumen-Nonce` and `X-Lumen-Sig`:
- The signature is `HMAC-SHA256(key, "LUMEN1\n" + method + "\n" + target + "\n" + time + "\n" + nonce + "\n" + body)`.
- The TV accepts a request only if:
  - the signature matches, compared in constant time;
  - the time is within 90 s of the TV's clock;
  - the nonce hasn't been seen in that window.
- So a captured request can't be replayed or altered, and the key is never transmitted after pairing.
- Requests must also carry the TV's own address in `Host`. That defeats DNS-rebinding pages, and no CORS headers are sent.
- Per address: bursts of 20 requests, refilled at 2 a second. 5 bad signatures within a minute block the address for a minute.

**Why not HTTPS.**
- A certificate for a LAN IP can't come from a public CA. A self-signed one makes iOS warn on every visit, and Home Screen web apps handle that badly.
- Installing a custom root CA on the iPhone would trust that CA for every site, which is worse.
- Without HTTPS, Safari treats the page as insecure (no Web Crypto). So the page carries its own SHA-256/HMAC, checked against FIPS 180-4 and RFC 4231 vectors in its test and at every load.

### Residual risks (accepted, documented)

1. **An active attacker on your Wi-Fi** (someone who has your Wi-Fi password and can ARP-spoof) can rewrite the page as the TV serves it, and steal the key from the phone's storage the next time you open it.
   - With the key they can show, hide or move the ticker. That's all the key allows: no credentials, no league data, no other device access.
   - Mitigation: Reset pairing. To remove the exposure entirely, turn phone control off.
2. **Passive sniffing** reveals that the phone controls the TV, which commands were sent, and the ticker's status: on or off, position, ESPN connection state, last refresh time. It doesn't reveal the key or any league data.
3. **The key on the phone** lives in the Home Screen bookmark's URL and the page's storage. Anyone with your unlocked phone can control the ticker. Reset pairing revokes it.
4. **Denial of service from the LAN.** A device on the network can occupy the 4 connection slots or trigger rate limits, making the page unresponsive while it does. It can't change anything. The remote, Lumen and the PC still work.
5. **Auto mode** gives the Sports app the package and activity name of each screen while it is in auto mode. Lumen's Home button service already sees these; it can't read screen content or keys.

## Logging

- Log tag `LumenSports`:
  - fetch counts and failures;
  - which command came from which LAN address;
  - refusal reasons;
  - pairing shown, phone control on/off, auto-mode hides.
- Never logged: cookies, SWID, keys, signatures, request bodies, URLs with fragments, team or player names.
- `sports.sh phone pair` prints the QR code to the PC terminal. It prints the link (which contains the key) only with `--link`.

## Recovery when control is lost

1. **Remote:** Lumen → Settings → Sports. Fantasy ticker Off, Phone control Off, Reset pairing.
2. **Without Lumen:** Fire TV Settings → Applications → Manage Installed Applications → Lumen Sports.
   - **Force stop** removes the ticker and the listener at once. The ticker stays off.
   - The listener can come back after a later reboot if phone control is still switched on. Opening Lumen's Sports settings un-stops the app, which re-arms its boot start. So also turn Phone control off in Lumen, or uninstall.
   - **Uninstall** also deletes the cookies and the pairing key.
3. **From the PC:**
   - `sports.sh hide`, `sports.sh phone off`, `sports.sh phone unpair`.
   - In an emergency, `adb shell appops set app.lumen.sports SYSTEM_ALERT_WINDOW ignore` hides the overlay whatever the app does.
