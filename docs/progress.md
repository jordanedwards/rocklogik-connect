# Progress

Completed work, newest first. Items arrive here from [todo.md](todo.md) when they are finished.

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
