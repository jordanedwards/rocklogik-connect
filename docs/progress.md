# Progress

Completed work, newest first. Items arrive here from [todo.md](todo.md) when they are finished.

## 2026-10-13 — Sign-in works against the deployed cloud (ROCKLOGIK-89)

A debug build pointed at https://cloud.rocklogik.ca signed in on the tablet as
jordan@orchardcity.ca, after Jordan added that account to the droplet's users table with tinker.
Debug builds still default to the local cloud; `CLOUD=... ./build.sh apk` (Gradle property
`cloudBaseUrl`) points one elsewhere. Release builds already pointed at the droplet.

The first attempt failed with "RockLogik cannot be reached", and it was not the network. The
app now logs the exception behind that message, which was `ProtocolException: Too many
follow-up requests: 21`, a redirect loop. `cloud.rocklogik.ca` had been created proxied in
Cloudflare, whose Flexible SSL mode fetches the origin over plain HTTP, which nginx redirects to
HTTPS, which Cloudflare fetches over HTTP again. The workstation never saw it because its resolver
still held an older answer pointing straight at the droplet, so every check from here passed.
Jordan set the record to DNS only. It should stay that way: the droplet terminates TLS with its
own certificate, and the hostname serves the app and Notehub, not browsers, so Cloudflare's
proxy adds a firewall that reads request bodies and nothing this host needs. The Android 7.1.1
tablet trusts the droplet's Let's Encrypt chain (it carries ISRG Root X1), which was checked on
the way.

## 2026-10-13 — Phase 0 done: gate C-0 passed (todo items 1 and 2)

The CI-built APK, signed with the project debug key, installed on the tablet, signed in as
jordan@orchardcity.ca through Google to a local rocklogik-cloud, found VISR-MOCK, unlocked and
read `info_v1` (ROCKLOGIK-90). The OAuth clients exist (ROCKLOGIK-82, Jordan). One check from
phase 0 stays open on the board: exchanging a token on the droplet (ROCKLOGIK-89), which waits on
rocklogik-cloud being deployed at all (its todo item 1). Phase 1 is next and the Kelowna install
waits on its gate.

## 2026-10-13 — Google sign-in works end to end, against a local cloud (ROCKLOGIK-90)

The app signs in with Google through Credential Manager, exchanges the ID token with
rocklogik-cloud for its own token, and keeps that encrypted with an Android Keystore key. Signing
out (the back arrow on Nearby units) revokes the token on the cloud. Proven on the tablet as
jordan@orchardcity.ca against rocklogik-cloud running on the workstation, reached through
`adb reverse tcp:8000 tcp:8000`, which is what debug builds point at; release builds point at
https://cloud.rocklogik.ca, which does not exist yet.

Four things went wrong first, and each one looked like the same silent cancel:

- **One-tap refused** with 16 [28433], "Cannot find a matching credential". The app now uses
  Google's full account chooser (GetSignInWithGoogleOption), which suits a button anyway.
- **The OAuth consent screen was Internal,** so only accounts in the owning Workspace could sign
  in. Now External, in Testing, with test users.
- **Only an Android OAuth client existed,** and its id had been put where the *web* client id
  belongs. Google needs both, in one project: the Android client (package and SHA-1) authorises
  the app; the web client's id is the audience the ID token is issued for and the cloud checks.
  No redirect URIs or origins on the web client, and its secret is used nowhere.
- **Credential Manager reports all of these as "cancelled by the user".** A debug-only
  `SignInDiagnosticActivity` runs Google's legacy sign-in, which gives the numeric status (10 is
  a client mismatch); it found the third problem in seconds. Debug builds also show the
  exception text on the Sign in screen.

The cloud's variable is `GOOGLE_WEB_CLIENT_ID`; it had been added as `GOOGLE_CLIENT_ID` and was
renamed. Locally the cloud runs in the `sail-8.5/app` image under PHP's built-in server against
`database/local.sqlite` (`artisan serve` hands its workers the Postgres settings from `.env`).

## 2026-10-13 — One debug key for every build (ROCKLOGIK-85, 82)

Google sign-in only works for an APK whose certificate SHA-1 is registered on the Android OAuth
client, and each machine's own debug keystore has a different one. The project now signs every
debug build with one key: the founder's existing `~/.android/debug.keystore`, kept so the SHA-1
already given for ROCKLOGIK-82 stays valid. The keystore lives at `keystore/debug.keystore` and
is not committed, because the repository is public; CI writes it from the secret
`DEBUG_KEYSTORE_BASE64` (set 13 October 2026); Docker builds see it through the mounted checkout.
`keystore/debug.sha1` is committed, and the build checks the certificate against it and stops
when the key is missing or different. Both failures were provoked and both stopped the build.
The client secret Google issues with the web client is not used anywhere: the cloud verifies ID
tokens with the client id and Google's public keys.

## 2026-10-13 — First end-to-end run: tablet to mock over BLE (ROCKLOGIK-87)

With LE switched on in this workstation's BlueZ (`ControllerMode = dual`, bluetoothd restarted by
Jordan), the mock advertised as VISR-MOCK and the Galaxy Tab A found it at -38 dBm, connected,
negotiated an MTU of 247, unlocked with the typed key, and read `info_v1` and a live `status_v1`
(all eight checks green against the mock). The engineer terminal showed the traffic with the key
redacted, and the idle pings every 5 s. The first two connection attempts failed with GATT error
0x85 (133), the usual Android 7 first-connect failure; the link's two retries covered it, which
is the reason they are there. Still open in phase 0: CI on GitHub (85, needs a push), sign-in
(82, 89, 90).

## 2026-10-13 — Phase 0 built: protocol, mock, client, skeleton (ROCKLOGIK-84 to 89)

- **`docs/protocol.md` v1** (86): transport, the lid label and key, errors, the cross-method rules
  and every phase 0 to 2 method with its fields. The device computes the status checks, so both
  tracks and every app version agree on green; the status fields are the heartbeat's where they
  overlap.
- **Mock and conformance** (87): `tools/visr_protocol.py` serves the contract with simulated state,
  `tools/mock_visr.py` puts it behind a Nordic UART Service with bless, `tools/conformance.py`
  checks any device. Both tracks pass in-process; leaking the passphrase or reordering the checks
  in the mock fails the checker. Over BLE it has not run: this machine's BlueZ has LE switched off
  (`ControllerMode = bredr`), so it exposes no GATT server or advertising.
- **App skeleton and client** (84, 88): Kotlin and Compose, package `ca.rocklogik.connect`,
  `minSdk` 25 for the Android 7.1.1 tablet, `compileSdk` 37 because current AndroidX requires it.
  Nothing copied: Nordic's BLE Library replaces the Bluefruit classes the plan named. The RPC client
  was written from protocol.md; 11 unit tests pass, and disabling redaction fails one. Every
  wireframe is a destination; Find, Unlock (typed key for now), Unit, Status check and the engineer
  terminal work, the rest show their ticket. Installed on the tablet, it scans; nothing advertises
  as a VISR yet.
- **CI** (85): `build.sh` and the Dockerfile pass lint, tests and the APK in Docker; the GitHub
  workflow has not run because nothing has been pushed.

## 2026-10-13 — Repository created; development plan drafted

The decision to build the provisioning app from the outset, rather than after the proof of
concept, was taken on 13 October 2026 so that the production VISR inherits a provisioning
process that has been used. [Development Plan.md](Development%20Plan.md) is the draft for
review: BLE as the transport because it serves both VISR tracks, JSON-RPC over a Nordic UART
Service as Cricket Connect already speaks, one protocol contract shared by the Pi rig and the
MCU companion, the bench-and-site split of provisioning, three phases aimed at the Kelowna
install with SSH as the fallback, and the Cricket code ownership question flagged before any
file is copied.

Reviewed the same day. Three questions answered by Jordan: the CRWN-specific Cricket code is
rewritten clean-room and only the MIT Bluefruit base is copied; Google sign-in is built from the
start rather than a per-phone token; and the Kelowna install waits for phase 1 rather than going
ahead over SSH, so gate C-1 is on the critical path to 1 November 2026 and the install date moves
if it slips.

Later the same day: twelve screens wireframed on a canvas for review
(https://claude.ai/artifact/KeRAYAgwoHqSPGRPZMVUet) and the whole plan put on the ROCKLOGIK board
as ROCKLOGIK-81 to 106, one ticket per day or less, phase by phase, with each phase gate as the
last ticket of its phase. Two things worth knowing: the wireframes use bracketed placeholders
rather than invented numbers wherever a real value is not yet known, and the Site network
screen's wifi write depends on the station-first rule designed in ROCKLOGIK-80, so ROCKLOGIK-94
implements only the station connection that rule needs and leaves the drain to 80.
