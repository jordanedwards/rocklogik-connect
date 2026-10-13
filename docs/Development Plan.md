# RockLogik Connect: development plan

**Written:** 13 October 2026
**Status:** reviewed 13 October 2026; the three questions put to the founder that day are
answered in §2 (decisions 2 and 5) and §9. Nothing is built; the repository holds this plan.
**What it is:** the Android app a technician uses in the cab to provision, check and record a
RockLogik Scout VISR install, over Bluetooth Low Energy, and later to do the same for the Scout
Sensors. It is built from the outset so that the production unit inherits a provisioning process
that has been used, not one designed on paper after the proof of concept.
**Design record:** everything about the devices lives in `../rocklogik`. Start with its
`CLAUDE.md`, then [POC Decisions](../../rocklogik/docs/Engineering/POC%20Decisions.md) §6 and §9,
[Scout VISR POC Hardware and Firmware](../../rocklogik/hw/Devices/Scout%20VISR/Scout%20VISR%20POC%20Hardware%20and%20Firmware.md)
§6.1, §6.3 and §8, the
[Scout VISR Uplink and Consistency Note](../../rocklogik/hw/Devices/Scout%20VISR/Scout%20VISR%20Uplink%20and%20Consistency%20Note.md)
§5.1 and §8, and the
[Scout VISR MCU Development Plan](../../rocklogik/hw/Devices/Scout%20VISR/Scout%20VISR%20MCU%20Development%20Plan.md)
§3.3 and §5.
**Starting point:** `../cricket-connect`, the CRWN Cricket diagnostic app: a Bluefruit LE Connect
derivative with a JSON-RPC 2.0 client over a BLE UART service, device authentication, CRC-checked
file transfer, Notecard diagnostics, Google sign-in against a Laravel backend and a standalone
deployment-details page. The shape of this app is that one with the nouns changed, and §2 says
what is taken and what is not.

---

## 1. Why an app, and why now

The install today is an engineer with a laptop, the VISR's own wifi access point and an SSH
session. That is fine for one truck and one person. It fails at the second truck, at the first
install done by anyone else, and at the production unit, whose provisioning process will
otherwise be invented under time pressure after the proof of concept. Building the app against
the POC rig means the production VISR arrives with a provisioning process that has been through
installs, and with the device-side contract already written and versioned.

The app also closes a data gap. The mounting survey (POC plan F1) and the asset record the
Integration Design Note needs (customer equipment number, make, model, serial, functional
location) are today a form typed up after the visit. With the app they are entered in the cab,
with photos, at the moment the truck is identified, and they go to `rocklogik-cloud` as the
asset and device record.

What the app is **not**: a dashboard, an operator display, an alerting surface or a way to read
data off a truck in service. POC Decisions §6 forbids all four in the POC, and the app does not
reopen them. It is a technician's tool, used standing next to a truck with the key on.

---

## 2. Decisions this plan takes

| # | Decision | Why |
|---|---|---|
| 1 | **Transport is BLE: JSON-RPC 2.0, newline-delimited, over a Nordic UART Service.** The same protocol Cricket Connect speaks. | It is the only transport that serves both VISR tracks. The Pi 4B has Bluetooth under BlueZ; the MCU companion's nRF52840 is a BLE radio by definition and runs a peripheral service beside its central role for the pucks. A wifi-and-HTTP variant would be easier on the Pi and impossible on the companion. |
| 2 | **Fresh repository, new package `ca.rocklogik.connect`. Only the MIT Bluefruit LE Connect base is copied, file by file into `NOTICE`. Everything CRWN-specific in Cricket Connect (the JSON-RPC client, the file-transfer client, the auth and deployment code) is rewritten clean-room from its documented behaviour, not copied.** Decided 13 October 2026. | Bluefruit LE Connect is MIT and travels freely. The CRWN-specific code is CRWN's and this is a RockLogik Technologies repository; a rewrite of the few hundred lines that matter is about a day's work and leaves no title question beside the IP assignment already open in `rocklogik` todo item 1. A fresh package also drops the Cricket modules that have no VISR equivalent (battery, solar, LoRa, microphone, RF sensor, MQTT, S3 sync, DFU). |
| 3 | **The device-side contract is one file, `docs/protocol.md`, versioned per method, shared by the Pi rig and the MCU firmware.** | The same rule as the notefile contract in `rocklogik-cloud/docs/notes.md`: one place the field names live, and both device tracks implement it. A method the MCU track cannot serve returns a `not_supported` error rather than being absent. |
| 4 | **Device authentication is a per-unit key, printed as a QR code on the label inside the lid, sent as the first request on every connection.** BLE bonding is a later hardening step, not the POC's gate. | Cricket's `ble_auth_v1` pattern. The cab is a public place; a shared fleet key would be as public as the lid. The QR code means the technician scans rather than types. |
| 5 | **Backend is `rocklogik-cloud`, Google sign-in exchanged for a Sanctum token, following the cortex pattern from the start.** Decided 13 October 2026 over a per-phone API token. | The pattern is known to work from a phone and the allow-list is the users table, so the second technician costs a row and not a feature. The app side is written fresh (decision 2); the cloud side needs a Google Cloud OAuth client that only the account holder can create (§10). `rocklogik-cloud` gains three routes (§7). |
| 6 | **Android only for the POC, sideloaded, built in Docker and on CI; no Play Console, ever.** | The tablet on site is the founder's. Cricket's `build.sh` and `Dockerfile` already build without Android Studio. |
| 7 | **The SSH path stays, as the engineer's path and the lockout fallback, not as an install path.** The wifi access point of POC Decisions §6 and SSH bound to it remain; Ethernet behind the bench panel is the last resort. **Every install, the first included, goes through the app** (decided 13 October 2026). | A device you cannot log into is a device that comes home, so the engineer's way in stays. But a procedure exercised from day one is the point of building the app now, so no install is done by hand to save the schedule. |
| 8 | **Secrets never leave the device by BLE.** Site wifi credentials are write-only; `config_get_v1` returns the SSID and never the passphrase; nothing logs them. | Uplink note §5.1: credentials live on the box, not in Notehub, and not in a phone's log either. |
| 9 | **The Notecard stays on USB on the Pi rig.** | The UART fallback disables the Pi's Bluetooth to free the PL011 (VISR POC §5.6, amendment of 10 October 2026). This plan is one more reason to make USB work; if UART wins, the Pi rig needs a USB Bluetooth adapter and the plan says so in §8. |

---

## 3. What the technician does with it

The session is bench-and-site, as the uplink discussion settled: everything that is not a fact
about the truck is done on the bench, and the site session enters only what the bench cannot
know.

**On the bench, before the visit** (a script in `rocklogik-firefly-rpi`, not the app): the
image, SSH keys, the Notecard claimed to Notehub, the per-unit BLE key and the per-device upload
token generated and registered in `rocklogik-cloud`, the service access point with its per-unit
passphrase, the lid label printed with serial and QR code. Then one app session against the
bench unit to prove the whole path.

**In the cab, key on**, in this order, one screen each:

1. **Find.** Scan; the box advertises as `VISR-<serial>`. Scan the lid QR code; the app
   authenticates. `info_v1` shows serial, track, software version, Notecard id.
2. **Identify the truck.** Customer equipment number, make, model, serial, hours, and the site.
   Prefilled from the cloud if the asset exists (entered in the office beforehand), typed if not.
   CAN channel and pigtail from a short list. Photos of the decal, the diagnostic connector, the
   mounted box and the cable run.
3. **Site network**, if the site granted one: pick the SSID from `wifi_scan_v1`, enter the
   passphrase, write it. Never read back.
4. **Write and check.** `config_set_v1`, then the status page from `status_v1`: bus alive, bit
   rate latched, frames per second, bus errors, RTC disciplined, Notecard transport and signal,
   last heartbeat acknowledged at Notehub, disk free, segments pending and acknowledged. Every
   line green or the technician does not close the lid.
5. **Recce.** `recce_run_v1` for fifteen minutes; the report comes back by file transfer and is
   shown as a summary (ECU inventory, PGN count, decode coverage, the four live signals).
6. **Record.** The identification, the status page, the recce report and the photos go to
   `rocklogik-cloud` as the asset, monitoring point and device record. If the phone has no
   signal in the cab, the record queues and posts when it does.

Under fifteen minutes from key-on to record is the target, and it is the gate in §9.

**Later, for the pucks** (phase 3): the same app, connecting to the VISR, lists the pucks it
has seen with link state, time-sync state, cell state and archive free, and can ask the VISR to
pass a configuration or a burst request through. The pucks are never connected to directly
(POC Decisions §8: nothing else connects a puck to anything).

---

## 4. The protocol, version 1

Carried over from Cricket: JSON-RPC 2.0 request and response objects, integer ids, method names
suffixed `_v1`, newline-delimited UTF-8 over the Nordic UART Service (write to RX, notify on
TX), MTU negotiated to 247 so a notification carries 244 bytes, base64 chunks with a CRC for
file transfer, a `ping` to detect a dead link. The full field tables are written into
`docs/protocol.md` as the first deliverable of phase 0; this is the method list and what each
is for.

| Method | Params | Returns | Pi rig | MCU track |
|---|---|---|---|---|
| `ble_auth_v1` | `key` | status | yes | yes |
| `info_v1` | | serial, track (`pi`, `mcu`), sw_version, protocol version, notecard_id, ble_id, uptime_s | yes | yes |
| `status_v1` | | the status page of §3 step 4, one object | yes | yes |
| `config_get_v1` | | asset, can, site, wifi SSIDs (no passphrases) | yes | yes |
| `config_set_v1` | asset, can, site, wifi (write-only) | status | yes | yes; wifi goes to the Notecard Cell+WiFi through `card.wifi` |
| `wifi_scan_v1` | | SSIDs with signal | yes | `not_supported` |
| `recce_run_v1` | `seconds` | a job id | yes | `not_supported` in the POC; the recce is offline tooling |
| `job_status_v1` | `job` | state, progress, result file | yes | yes |
| `file_list_v1` | `dir` | names, sizes | yes | yes |
| `file_receive_v1` | `n`, `off`, `csz` | base64 chunk, CRC | yes | yes |
| `file_get_crc_v1` | `n` | CRC | yes | yes |
| `notecard_status_v1` | | transport, signal, last sync, queue depth | yes | yes |
| `notecard_signal_v1` | `attach_timeout_s` | bars, RAT, band | yes | yes |
| `log_tail_v1` | `unit`, `lines` | text | yes | last N records |
| `segments_v1` | | ledger summary: pending, acked, reclaimable, oldest pending | yes | yes |
| `reboot_v1`, `shutdown_v1` | | status | yes | yes |
| `puck_list_v1` | | pucks seen, link, time-sync, cell state, archive free | phase 3 | phase 3 |
| `puck_request_v1` | `puck`, `kind`, `burst` | status | phase 3 | phase 3 |

Rules that hold across every method:

- **A device that cannot do something says so** with a JSON-RPC error, code and name
  (`not_supported`, `not_authenticated`, `busy`, `bad_params`), never silence.
- **Nothing in the protocol changes the CAN side.** There is no method that touches CAN mode,
  filters or the transceiver, and there will not be one; the tap is listen-only in hardware.
- **Version per method**, as Cricket does. A field change is a `_v2`, and the device serves both
  until the app no longer sends `_v1`. `info_v1.protocol` says which versions the device has.
- **Credentials are write-only** (decision 8).

---

## 5. Device side, per track

### 5.1 The Pi rig (`rocklogik-firefly-rpi`)

A `visr-ble` service: a BlueZ GATT server exposing the Nordic UART Service, advertising
`VISR-<serial>`, dispatching JSON-RPC to handlers that read the same status the heartbeat
reads, write the config file on the archive partition, drive NetworkManager for the wifi
items, start the recce as a job, and serve files from a short allow-list of directories (the
recce reports, the manifests, the service logs). Python, under systemd, restart on failure,
like the logger. One connection at a time; the service refuses a second central.

Three things to prove early, because each can sink the transport:

- **Coexistence.** The Pi 4's wifi and Bluetooth share one chip and one antenna. The service
  access point, the site station and BLE all want it. First measurement on the bench is BLE
  throughput and drop rate with the access point up, then with the station associated.
- **MTU and throughput under BlueZ.** Cricket learned its lessons on an nRF52 bridge at about
  560 KB a minute; the Pi will differ. A 100 KB recce report must arrive in under a minute.
- **The Notecard on USB** (decision 9).

### 5.2 The MCU track (`rocklogik-firmware`)

The nRF52840 companion runs the Nordic UART Service as a peripheral alongside its central role,
which upstream Zephyr supports for a handful of links. It frames the JSON-RPC lines to the host
over the existing nRF link protocol (MCU plan §4.7) as one more record type, and the host answers
from the same status the Notecard module reports. File transfer serves from the microSD. This is
step 7 of the MCU plan's order of work and lands in phase 3 here; nothing in phases 0 to 2 waits
on it, and the contract is written so it needs no change when it comes.

---

## 6. The app

Kotlin where new, Java where carried over; `compileSdk` 35, `minSdk` 26, the Android 12 and
later Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, with `neverForLocation`),
camera for the QR code and photos, no location permission unless scanning requires it on the
oldest supported release.

**Copied from Cricket, under MIT, into `NOTICE`:** the Bluefruit BLE scanner and connection
stack, and the Docker build and lint scripts (Jordan's own, written for Cricket; confirm they
carry no CRWN licence header before copying).

**Rewritten clean-room from Cricket's behaviour, not its code:** the JSON-RPC client with auth
retry and ping, the file-transfer client with chunk retry and CRC, Google sign-in and the token
store, the deployment-details page. Cricket's `docs/` describe each well enough to rewrite from;
the rewrite is done without the Cricket source open, and the plan's author notes that in the
commit.

**Replaced:** every device module (battery, solar, LoRa, microphone, RF, TMD, cycle control,
health LED) by the five VISR screens in §3. The Cricket "Config Settings" page becomes the
identify and site-network screens.

**Dropped:** MQTT, S3 sync, DFU, the colour picker and chart libraries, Play Services
location and vision beyond what the barcode scan needs. The dependency list shrinks by half and
the APK with it.

**Kept, behind an engineer toggle:** the raw UART terminal. On a bad day it is the only way to
see what the device actually said.

**Offline behaviour:** every cloud post is queued on the phone and retried; the cab is as
likely to lack signal as the pit. The app never blocks a site step on the cloud.

---

## 7. The cloud side (`rocklogik-cloud`)

Three routes, mirrored from cortex, under `auth:sanctum` except the first:

- `POST /api/auth/google`: ID token in, Sanctum token out, with the allow-list rule (a
  `users` row with `active` set, no self-registration since there is no company domain claim
  worth trusting yet).
- `GET /api/provisioning/visr?serial=`: the asset, monitoring points and device record for a
  VISR serial if known, `404 no_such_unit` if not, `409` if two live rows claim it.
- `PUT /api/provisioning/visr`: creates or updates the asset and device from the identify
  screen, attaches the status page and recce report as JSON, and photos as bucket objects.

A record created from the serial before the box's first note, or the other way round, must
match up; the device row is keyed on the VISR serial the bench script registered, and the
Notehub device UID is adopted on first contact, which is the rule cortex already uses for the
Cricket enclosure number.

---

## 8. Hardware consequences

- The Pi rig needs nothing new if the Notecard is on USB. If the UART fallback wins, add a USB
  Bluetooth adapter (about C$15) and disable the on-board radio's Bluetooth half; the enclosure's
  USB-A ports face the bench panel with 1 mm to spare, so the adapter would need the USB-C
  port instead, which the Notecard would then be using. Make USB work.
- The lid label gains a QR code: serial, BLE key, service access point name. Printed on the bench
  from the provisioning script. `mech/visr-enclosure` already engraves the lid; the label is a
  sticker inside it, not an engraving, because the key is per unit.
- Nothing on the MCU companion changes: the nRF52840 and its antenna are already there.

---

## 9. Phases, gates and order of work

The first deployment is Kelowna Sand & Gravel, 1 November 2026 to January 2027. **Phase 1 is
a precondition of that install: the install goes through the app or it waits** (decided
13 October 2026). So gate C-1 is on the critical path to 1 November, and if it slips the
install date moves rather than the procedure being skipped. Phase 2 follows during the run;
phase 3 waits for the pucks and the MCU companion.

| Phase | Deliverables | Gate |
|---|---|---|
| **0. Bootstrap** (one week) | Repository skeleton on the MIT Bluefruit base with `NOTICE`; package `ca.rocklogik.connect`; Docker build and a GitHub Actions job producing a debug APK; `docs/protocol.md` v1 with every field named; the clean-room JSON-RPC client; sign-in against a `rocklogik-cloud` route stub | **C-0:** the APK builds on CI, installs, scans, and authenticates against a Python mock that speaks `protocol.md` |
| **1. Provision the rig** (two weeks, **before Kelowna**) | `visr-ble` on the Pi: auth, info, status, config, wifi scan; the find, identify, site-network and status screens; bench provisioning script with the QR label; coexistence and throughput measured and recorded; `docs/install.md` first draft | **C-1:** the bench rig is provisioned from the phone, the status page is green, the site passphrase is on the box and not in any log, BLE holds through the access point coming up, and the Kelowna install is rehearsed on the bench from the written procedure |
| **2. Record the install** (two weeks, during the run) | Recce as a job with file transfer; photos; the three cloud routes; the offline queue; the deployment record end to end | **C-2:** a full dry-run install on the bench, key-on to record in the cloud, in under fifteen minutes, following the written procedure in `docs/install.md` |
| **3. Pucks and the MCU track** (after the pucks exist) | `puck_list_v1` and `puck_request_v1`; the NUS peripheral on the nRF52840 and the host-side handlers; BLE bonding; release signing | **C-3:** the same APK provisions a Pi rig and an MCU rig, and shows three pucks through either |

Order of work inside phases 0 and 1, each a ticket of a day or less: skeleton, `NOTICE` and
build; `protocol.md`; the mock device; the clean-room JSON-RPC client; scanner and auth;
`visr-ble` with info and status; the status screen; config and wifi on the device; the identify
and site-network screens; the bench script and label; coexistence measurement; `install.md`
and the Kelowna rehearsal. That is roughly thirteen working days against the eighteen between
this plan and 1 November, alongside the rig's own Notecard bring-up (ROCKLOGIK-53) which the
status page depends on. Tight, and the decision above says what gives if it does not fit.

---

**Tickets**, created 13 October 2026 on the ROCKLOGIK board in this order: ROCKLOGIK-81
(wireframe review), 82 (OAuth client ids), 83 (decision record), phase 0 84 to 90, phase 1 91 to
98, phase 2 99 to 103, phase 3 104 to 106. The board appends new tickets, so phases 0 and 1 sit
after ROCKLOGIK-78 to 80 (the uplink work); since Kelowna waits for C-1, drag 81 to 98 above
79 and 80 in the board's manual order.

**Wireframes**: https://claude.ai/artifact/KeRAYAgwoHqSPGRPZMVUet, twelve phone screens, under
review (ROCKLOGIK-81).

---

## 10. Open items for the founder

1. ~~Ownership of the Cricket Connect code.~~ Decided 13 October 2026: clean-room rewrite of
   everything CRWN-specific; only the MIT Bluefruit base is copied (§2 decision 2).
2. **App name.** "RockLogik Connect" is the working name; it is not a mark and need not be.
3. **Google Cloud project and OAuth client ids** for `rocklogik-cloud`, which only the account
   holder can create. Needed by the end of phase 0.
4. ~~Whether the first Kelowna install waits for phase 1.~~ Decided 13 October 2026: it waits
   (§2 decision 7, §9).

---

## 11. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| BLE on the Pi 4 is flaky beside the wifi access point and station | Medium | Measured first in phase 1; USB Bluetooth adapter as the fallback, which needs the USB-C port and so needs the Notecard on USB-C's partner, a hub, or the UART after all. Decide on the measurement, not in advance |
| The Notecard ends up on UART and takes the Pi's Bluetooth with it | Medium | Decision 9; the measurement above is the tie-breaker |
| The app grows into a dashboard | High if unwatched | §1's last paragraph; every screen is a step in the install or a diagnostic, and a screen that shows trend data is refused |
| CRWN code finds its way into the rewrite | Low | The rewrite is done from Cricket's `docs/` with the source closed, and the commit says so; the Bluefruit files in `NOTICE` are the only copies |
| Phase 1 slips and takes the Kelowna date with it | Medium | Decided and accepted: the install moves. Phase 0 starts the day this plan is approved, and the Notecard bring-up runs in parallel |
| Android permission and BLE API churn between releases | Low | `minSdk` 26, the modern permission set, one tested phone for the POC |
| Protocol drift between the two device tracks | Medium | One contract file, a mock that implements it, and the firmware's rule that the overlay and the document are fixed in the same commit applied here to `protocol.md` and the handlers |
| The fifteen-minute gate fails on people rather than code | Medium | `docs/install.md` is written for the technician, rehearsed on the bench by someone following it cold |

---

## 12. What this changes elsewhere

- `rocklogik/CLAUDE.md` lists this repository under related repositories (done with this plan).
- POC Decisions gains an entry for the app and its transport once this plan is approved; VISR
  POC §8 gains the bench-and-site provisioning split; the uplink note §5.1 gains the Bluetooth
  consequence of the Notecard link choice.
- `rocklogik-firefly-rpi` gains the `visr-ble` service and the bench provisioning script as todo
  items; `rocklogik-firmware` gains the NUS peripheral at step 7; `rocklogik-cloud` gains the
  three routes.
- The ROCKLOGIK board gets the phase 0 and 1 tickets, after the Notecard bring-up (ROCKLOGIK-53)
  and before the archive drain (ROCKLOGIK-80).
