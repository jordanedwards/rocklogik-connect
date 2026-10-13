# VISR provisioning protocol, version 1

**Written:** 13 October 2026 (ROCKLOGIK-86).
**Status:** the contract. The app (`app/`), the mock (`tools/mock_visr.py`), the Pi rig's
`visr-ble` service (`rocklogik-firefly-rpi`) and the MCU companion (`rocklogik-firmware`,
`visr-ble/` and `visr-mcu/`) implement it. If an implementation and this file disagree, one of
them is wrong and is fixed in the same commit. `tools/conformance.py` checks a device against it.
**Decision record:** POC Decisions §10 in `rocklogik`.

---

## 1. Transport

| | |
|---|---|
| Radio | Bluetooth Low Energy, the VISR as peripheral, the phone as central |
| Service | Nordic UART Service, `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| Phone to VISR | RX characteristic `6E400002-…`, write and write-without-response |
| VISR to phone | TX characteristic `6E400003-…`, notify |
| Advertised name | `VISR-<serial>`, the serial four or more digits (`VISR-0001`); the mock is `VISR-MOCK` |
| MTU | The phone requests 247; both sides work at the default 23 |
| Centrals | One at a time. A second connection is refused or dropped by the VISR |

**Framing.** Each message is one JSON object in UTF-8 followed by `\n` (0x0A). A message never
contains a raw newline; JSON escapes them. A message may span many writes or notifications, and
one write or notification may carry the end of one message and the start of the next. The
receiver buffers to the newline. A line longer than 16,384 bytes is a framing error: the
receiver discards up to the next newline and, if it is the VISR, replies with `bad_params`
(id `null`).

**Messages** are JSON-RPC 2.0. Every request carries an integer `id`; the VISR sends no
notifications in version 1. Replies may arrive out of order; the phone matches them on `id`.

**Time.** Times are Unix seconds (UTC) unless the field name ends in `_s_ago` (seconds before
`status_v1.at`) or `_s` (a duration). Measurement time on the VISR is its own clock; the phone
never sets it.

---

## 2. Authentication and the lid label

Every VISR has a **per-unit key**, generated on the bench by the provisioning script and never
shared across units: 16 random bytes, written as 26 characters of RFC 4648 base32 without
padding, upper case. The key is printed inside the lid as a QR code whose text is:

```
rlk1:visr?s=0001&k=ABCDEFGHIJKLMNOPQRSTUVWXYZ&ap=VISR-0001-svc
```

`s` is the serial, `k` the key, `ap` the service access point's SSID (POC Decisions §6). The
label carries the serial in plain text as well.

**Order on connect.** The first request after connecting is `ble_auth_v1`. Until it succeeds,
every method except `ping_v1` and `ble_auth_v1` returns `not_authenticated`. Authentication lasts
for the connection. Five failed attempts in one connection drop the link.

**What this is and is not.** In version 1 the key travels over an unbonded link and could be
read by a sniffer in the cab. That is accepted for the POC: the key unlocks provisioning of one
box, not the bus, and the bus side has no method at all. LE Secure Connections bonding with the
key as the out-of-band secret is ROCKLOGIK-106.

---

## 3. Errors

Errors use the JSON-RPC `error` object. `message` is the short name below, so a client can
switch on it; `data`, when present, is a human sentence.

| Code | `message` | Meaning |
|---|---|---|
| -32700 | `parse_error` | The line was not JSON |
| -32600 | `invalid_request` | Not a JSON-RPC 2.0 request |
| -32601 | `method_not_found` | No such method, or a version this device does not serve |
| -32602 | `bad_params` | Missing, extra or wrongly typed params; also a framing error |
| -32001 | `not_supported` | The method exists in this protocol but this track cannot do it (MCU `wifi_scan_v1`) |
| -32002 | `not_authenticated` | `ble_auth_v1` has not succeeded on this connection |
| -32003 | `busy` | A job of this kind is running, or a write is in progress; retry later |
| -32004 | `not_found` | A named file, job or puck does not exist |
| -32005 | `failed` | The device tried and failed; `data` says why |

---

## 4. Rules that hold across every method

- **Nothing here touches the CAN side.** There is no method for CAN mode, filters, bit rate
  or the transceiver, and none will be added. The tap is listen-only in hardware.
- **Secrets are write-only.** The fields `key` and `psk` are accepted and never returned,
  never logged by the device or the app, and shown redacted in any terminal. Any field added
  later that holds a secret is added to this list in the same commit.
- **Versions are per method.** A change to a method's params or result is a new method,
  `_v2`, and the device serves both until the app no longer sends `_v1`. `info_v1.protocol`
  lists what the device serves.
- **A device that cannot do something says so** with an error, never silence or a timeout.
- **Unknown fields in a result are ignored** by the app, so a device may add fields without a
  version bump. Removing or retyping a field is a version bump.

---

## 5. Methods

Default reply timeout on the phone is 10 s unless the method says otherwise.

### `ping_v1`

No params, no auth. Result `"pong"`. The phone sends one every 5 s of idle; three missed replies
mean the link is dead.

### `ble_auth_v1`

| Params | Type | |
|---|---|---|
| `key` | string | The 26-character key from the lid label. Secret |

Result `{"status": true}`. Wrong key: `not_authenticated`, with `data` "wrong key".

### `info_v1`

No params. Result:

| Field | Type | |
|---|---|---|
| `serial` | string | `0001` |
| `track` | string | `pi` or `mcu` (`mock` for the mock) |
| `hw_rev` | string | Board or rig revision |
| `sw_version` | string | The service's version, as the heartbeat's `sw_version` |
| `protocol` | object | `{"version": 1, "methods": {"status": [1], …}}`, every method served with its versions |
| `notecard_uid` | string or null | The Notehub device UID |
| `ble_addr` | string | |
| `uptime_s` | integer | |
| `time` | number | The VISR's wall clock |

### `status_v1`

No params. The phone polls it every 5 s while the Status check screen is open. The **device**
computes the checks, so both tracks and every app version agree on what green means. Result:

| Field | Type | |
|---|---|---|
| `at` | number | When this status was taken |
| `uptime_s` | integer | |
| `can` | object | `live` bool, `bitrate` integer or null, `listen_only` bool (always true), `frames_per_s` number, `bus_errors` integer, `last_frame_s_ago` number or null |
| `clock` | object | `rtc_ok` bool, `disciplined` bool, `source` `gnss`, `cell` or null, `last_correction_s_ago` number or null |
| `gnss` | object | `fix` bool, `sats` integer or null |
| `notecard` | object | `present` bool, `transport` `cell`, `wifi`, `ntn` or `none`, `bars` integer or null, `rat` string or null, `last_sync_s_ago` number or null, `queue` integer or null |
| `notehub` | object | `last_ack_s_ago` number or null, the age of the last heartbeat Notehub acknowledged |
| `wifi` | object | `configured` bool, `connected` bool, `ssid` string or null, `backend_ok` bool or null |
| `storage` | object | `free_pct` number, `root_ro` bool |
| `archive` | object | `writing` bool, `segments_pending` integer, `segments_acked` integer |
| `host` | object | `cpu_temp_c` number or null, `supply_v` number or null |
| `checks` | array | Below |

Each check is `{"id", "state", "title", "detail"}`. `state` is `ok`, `wait` (not yet, and
expected to resolve: a first GNSS fix), `fail`, or `off` (does not apply: no site wifi
configured). **All checks `ok` or `off` is green**, and only then does the app unlock Record. The
checks, in this order:

| `id` | `title` | `ok` when |
|---|---|---|
| `can` | CAN bus | `can.live` and `bitrate` latched |
| `frames` | Frames | Frames in the last 10 s and bus errors not rising |
| `clock` | Clock | `clock.rtc_ok` and `disciplined` (`wait` until the first fix or network time) |
| `notecard` | Notecard | `present` and `transport` not `none` |
| `notehub` | Notehub | A heartbeat acknowledged in the last 10 min (`wait` for the first 10 min of uptime) |
| `wifi` | Site wifi | `off` unless configured; then `connected` and `backend_ok` |
| `storage` | Storage | `free_pct` above 15 and `root_ro` |
| `archive` | Archive | `writing` |

`detail` is one short line for the screen, written by the device ("Live · 250 kbit/s latched ·
listen-only").

**One source.** These fields are the heartbeat's fields where the two overlap, read from the same
place on the device: `frames_per_s`, `bus_errors`, `bitrate`, `gnss.fix` (`gnss_fix`),
`host.cpu_temp_c`, `host.supply_v`, `storage.free_pct` (`disk_free_pct`), `uptime_s`, and the
uplink note's `transport`, `segments_pending` and `segments_acked` (`rocklogik-cloud/docs/notes.md`,
`health.qo`).

### `config_get_v1`

No params. Result: the config below, with each wifi entry's `psk` replaced by `"has_secret": true`.

### `config_set_v1`

Params: any of the top-level keys below. A key present replaces that whole block; a key absent
is left alone. Result `{"status": true, "restarted": ["visr-logger"]}`, naming the services the
change restarted. The VISR writes the config to its writable partition before replying.

```json
{
  "record_id": "01JA…",
  "asset": {
    "site": "Kelowna Sand & Gravel",
    "equipment_no": "HT-07",
    "make": "Komatsu", "model": "HM300-5", "serial": "…", "hours": 12345,
    "sap_equipment": null, "sap_floc": null, "maximo_asset": null, "maximo_site": null
  },
  "can": { "pigtail": "komatsu-12pin", "channel": 0 },
  "wifi": [ { "ssid": "…", "security": "wpa2-psk", "psk": "…" } ]
}
```

- `record_id` is the install record's id, a ULID the app mints, so the unit and the cloud name
  the same install.
- `can.pigtail` is one of `komatsu-12pin`, `deutsch-9pin`, `other`. `channel` is 0 or 1.
- `wifi` is the whole list of site networks. `security` is `open`, `wpa2-psk`, `wpa3-sae` or
  `wpa2-enterprise` (the last refused with `not_supported` in version 1). An entry for an SSID the
  unit already holds, sent without `psk`, keeps the stored secret. An empty list removes every
  site network.

### `wifi_scan_v1`

No params; up to 15 s. Result `{"networks": [{"ssid", "rssi_dbm", "security", "captive", "known"}]}`,
strongest first. `captive` is true when the network is known to need a sign-in page (which the
unit cannot use), null when unknown. `known` is true for a configured SSID. MCU track:
`not_supported` (the Notecard joins networks but does not list them).

### `recce_run_v1`

| Params | Type | |
|---|---|---|
| `seconds` | integer | 60 to 3,600; the install uses 900 |

Result `{"job": "<id>"}`. `busy` if a recce is running. MCU track: `not_supported` in the POC.

### `job_status_v1`

| Params | Type | |
|---|---|---|
| `job` | string | |

Result `{"job", "kind", "state", "progress", "eta_s", "result", "error"}`. `state` is `queued`,
`running`, `done` or `failed`; `progress` 0 to 1; `result` on `done` is
`{"dir": "recce", "name": "…json"}`; `error` on `failed` is a sentence. Jobs outlive the
connection; the phone may disconnect and ask again.

### `file_list_v1`

| Params | Type | |
|---|---|---|
| `dir` | string | `recce`, `manifests` or `logs`. Nothing else is reachable |

Result `{"files": [{"name", "size", "mtime"}]}`, newest first.

### `file_receive_v1`

| Params | Type | |
|---|---|---|
| `dir`, `name` | string | |
| `offset` | integer | |
| `length` | integer | At most 4,096 |

Result `{"offset", "length", "total", "data", "crc32"}`: `data` is base64 of the bytes read,
`length` the bytes actually read (0 at end of file), `total` the file size, `crc32` the CRC-32
(IEEE) of this chunk's bytes. The phone checks each chunk and retries a bad one.

### `file_get_crc_v1`

Params `dir`, `name`. Result `{"size", "crc32"}` over the whole file. The phone compares it with
the assembled file.

### `notecard_status_v1`

No params. Result: the `notecard` block of `status_v1`, plus `uid`, `sku`, `firmware`.

### `notecard_signal_v1`

| Params | Type | |
|---|---|---|
| `attach_timeout_s` | integer | Up to 120; the phone's timeout is this plus 10 s |

Result `{"bars", "rssi", "rsrp", "rat", "band", "carrier"}`, nulls where the card does not say.

### `log_tail_v1`

| Params | Type | |
|---|---|---|
| `unit` | string | `visr-logger`, `visr-ble`, `visr-drain`, `visr-can` |
| `lines` | integer | 1 to 200 |

Result `{"text": "…"}`. The device redacts secrets before sending.

### `segments_v1`

No params. Result `{"pending", "acked", "reclaimable", "oldest_pending_started_at"}` from the
segment ledger (ROCKLOGIK-78).

### `reboot_v1`, `shutdown_v1`

No params. Result `{"status": true}`, sent before the device acts, which it does 2 s later.

### `puck_list_v1`, `puck_request_v1`

Reserved for phase 3; fields are defined with ROCKLOGIK-105. Until then `method_not_found`.

---

## 6. Methods by track

| Method | Mock | Pi rig | MCU |
|---|---|---|---|
| `ping_v1`, `ble_auth_v1`, `info_v1`, `status_v1` | yes | yes | yes |
| `config_get_v1`, `config_set_v1` | yes | yes | yes (wifi to the Notecard by `card.wifi`) |
| `wifi_scan_v1` | yes | yes | `not_supported` |
| `recce_run_v1` | yes | yes | `not_supported` |
| `job_status_v1`, `file_*` | yes | yes | yes |
| `notecard_status_v1`, `notecard_signal_v1` | yes | yes | yes |
| `log_tail_v1`, `segments_v1` | yes | yes | yes |
| `reboot_v1`, `shutdown_v1` | yes | yes | yes |
