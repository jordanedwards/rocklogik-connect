# Progress

Completed work, newest first. Items arrive here from [todo.md](todo.md) when they are finished.

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
