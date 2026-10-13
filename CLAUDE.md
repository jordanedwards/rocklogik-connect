# rocklogik-connect: working notes for Claude

The Android provisioning app for the RockLogik Scout VISR. **The design record is in
`../rocklogik`**; read its `CLAUDE.md` first, then [docs/Development Plan.md](docs/Development%20Plan.md)
here. This repository holds the app, the device-side protocol contract and the two tracking
files. It does not hold design decisions: a decision made while building the app is recorded in
`../rocklogik/docs/Engineering/POC Decisions.md` or the relevant device document, and pointed
at from here.

## Layout (planned)

- `app/`: the Android application, package `ca.rocklogik.connect`. Kotlin where new, Java where
  carried over from `../cricket-connect`.
- `docs/protocol.md`: **the contract between the app and both VISR tracks**: JSON-RPC 2.0 over
  a Nordic UART Service, one table per method, versioned per method. The Pi rig
  (`../rocklogik-firefly-rpi`, `visr-ble`) and the MCU companion (`../rocklogik-firmware`,
  `visr-ble/`) implement it; if an implementation and this file disagree, one of them is wrong
  and gets fixed in the same commit.
- `docs/install.md`: the technician's procedure, written for someone following it cold.
- `docs/todo.md`, `docs/progress.md`: same convention as `rocklogik`: finish an item, delete it
  from todo and add a dated entry to progress in the same commit; numbers are stable.
- `NOTICE`: every file brought across from another repository, its origin and licence.

## Rules that are not obvious

- **Nothing in the app or the protocol can touch the CAN side.** There is no method for CAN
  mode, filters or the transceiver, and none is added. The tap is listen-only in hardware.
- **Credentials are write-only.** Site wifi passphrases and device keys go to the device and are
  never read back, logged, or sent to the cloud. `config_get_v1` returns SSIDs only.
- **The app is a technician's tool, not a dashboard.** POC Decisions §6: no operator-facing
  output, no alerting, no trend data on a phone. A screen is a step in the install or a
  diagnostic, or it is refused.
- **Every cloud post queues and retries.** The cab has no better signal than the pit; no site
  step blocks on the cloud.
- **Code from `../cricket-connect` is copied only under the paper trail the plan's §10 item 1
  settles**, and every copied file is in `NOTICE` with its licence.
- **Method versions are per method**, `_v1`, `_v2`; a field change is a new version and the
  device serves both until the app stops sending the old one.
- **British spelling in prose, dates in full, prices in CAD**, as in `rocklogik`. No em dashes.

## Tracking

Tickets on the `ROCKLOGIK` project of the `skunkworks-technologies` Plane workspace, as
`rocklogik/CLAUDE.md` describes. Labels: `firmware` for the device side, `business` for the cloud
routes (the precedent of ROCKLOGIK-40), and the app itself under `firmware` until the board has
a better label.
