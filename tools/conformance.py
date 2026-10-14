#!/usr/bin/env python3
"""Check a VISR against docs/protocol.md.

    tools/.venv/bin/python tools/conformance.py --direct                    # the mock, in-process
    tools/.venv/bin/python tools/conformance.py --direct --track mcu
    tools/.venv/bin/python tools/conformance.py --ble VISR-0001 --key <key> # a real unit, over BLE

Over BLE this machine is the central, so it cannot test a mock running on its own adapter: run
the mock on another machine, or test a Pi rig from here. Exit status 0 means every check passed.
Every check is listed with its result; a failure says what was expected and what came back.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import sys
import time
import zlib

from visr_protocol import CHECK_ORDER, ERRORS, MOCK_KEY, MockVisr, Session

NUS_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
NUS_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
TEST_PSK = "conformance-psk-7Q2"

NUM = (int, float)
OPT = type(None)
SHAPES = {
    "info_v1": {"serial": str, "track": str, "hw_rev": str, "sw_version": str, "protocol": dict,
                "notecard_uid": (str, OPT), "ble_addr": str, "uptime_s": int, "time": NUM},
    "status_v1": {"at": NUM, "uptime_s": int, "can": dict, "clock": dict, "gnss": dict,
                  "notecard": dict, "notehub": dict, "wifi": dict, "storage": dict,
                  "archive": dict, "host": dict, "checks": list},
    "status_v1.can": {"live": bool, "bitrate": (int, OPT), "listen_only": bool,
                      "frames_per_s": NUM, "bus_errors": int, "last_frame_s_ago": (*NUM, OPT)},
    "status_v1.clock": {"rtc_ok": bool, "disciplined": bool, "source": (str, OPT),
                        "last_correction_s_ago": (*NUM, OPT)},
    "status_v1.gnss": {"fix": bool, "sats": (int, OPT)},
    "status_v1.notecard": {"present": bool, "transport": str, "bars": (int, OPT), "rat": (str, OPT),
                           "last_sync_s_ago": (*NUM, OPT), "queue": (int, OPT)},
    "status_v1.notehub": {"last_ack_s_ago": (*NUM, OPT)},
    "status_v1.wifi": {"configured": bool, "connected": bool, "ssid": (str, OPT),
                       "backend_ok": (bool, OPT)},
    "status_v1.storage": {"free_pct": NUM, "root_ro": bool},
    "status_v1.archive": {"writing": bool, "segments_pending": int, "segments_acked": int},
    "status_v1.host": {"cpu_temp_c": (*NUM, OPT), "supply_v": (*NUM, OPT)},
    "check": {"id": str, "state": str, "title": str, "detail": str},
    "job_status_v1": {"job": str, "kind": str, "state": str, "progress": NUM, "eta_s": int,
                      "result": (dict, OPT), "error": (str, OPT)},
    "file_receive_v1": {"offset": int, "length": int, "total": int, "data": str, "crc32": int},
    "file_get_crc_v1": {"size": int, "crc32": int},
    "notecard_signal_v1": {"bars": (int, OPT), "rssi": (int, OPT), "rsrp": (int, OPT),
                           "rat": (str, OPT), "band": (str, OPT), "carrier": (str, OPT)},
    "segments_v1": {"pending": int, "acked": int, "reclaimable": int,
                    "oldest_pending_started_at": (int, OPT)},
}


class DirectLink:
    def __init__(self, device: MockVisr):
        self.session = Session(device)
        self.rx = bytearray()

    async def send(self, data: bytes):
        for line in self.session.receive(data):
            self.rx.extend(line)

    async def line(self, timeout: float) -> bytes:
        nl = self.rx.find(b"\n")
        if nl < 0:
            raise TimeoutError
        out = bytes(self.rx[:nl])
        del self.rx[:nl + 1]
        return out

    async def close(self):
        pass


class BleLink:
    def __init__(self, name: str):
        self.name = name
        self.rx = bytearray()
        self.event = asyncio.Event()
        self.client = None

    async def open(self):
        from bleak import BleakClient, BleakScanner
        dev = await BleakScanner.find_device_by_name(self.name, timeout=15)
        if dev is None:
            raise SystemExit(f"{self.name} not found")
        self.client = BleakClient(dev)
        await self.client.connect()
        await self.client.start_notify(NUS_TX, self._notify)
        # BlueZ negotiates the MTU on connect, but bleak reports 23 until asked for it.
        acquire = getattr(self.client._backend, "_acquire_mtu", None)
        if acquire:
            await acquire()
        self.mtu = self.client.mtu_size

    def _notify(self, _, data: bytearray):
        self.rx.extend(data)
        self.event.set()

    async def send(self, data: bytes):
        step = max(self.mtu - 3, 20)
        for i in range(0, len(data), step):
            await self.client.write_gatt_char(NUS_RX, data[i:i + step], response=False)

    async def line(self, timeout: float) -> bytes:
        deadline = time.monotonic() + timeout
        while True:
            nl = self.rx.find(b"\n")
            if nl >= 0:
                out = bytes(self.rx[:nl])
                del self.rx[:nl + 1]
                return out
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError
            self.event.clear()
            try:
                await asyncio.wait_for(self.event.wait(), left)
            except asyncio.TimeoutError:
                pass

    async def close(self):
        if self.client:
            await self.client.disconnect()


class Checker:
    def __init__(self, link, key: str, recce_wait: float):
        self.link = link
        self.key = key
        self.recce_wait = recce_wait
        self.next_id = 1
        self.passed = 0
        self.failed = 0
        self.seen = bytearray()  # every byte received, for the secret-leak check

    def ok(self, name: str, cond: bool, detail: str = ""):
        if cond:
            self.passed += 1
            print(f"  ok    {name}")
        else:
            self.failed += 1
            print(f"  FAIL  {name}  {detail}")

    async def raw(self, payload: bytes, timeout=10.0) -> dict:
        await self.link.send(payload)
        line = await self.link.line(timeout)
        self.seen.extend(line)
        return json.loads(line)

    async def call(self, method, params=None, timeout=10.0) -> dict:
        rid = self.next_id
        self.next_id += 1
        req = {"jsonrpc": "2.0", "method": method, "id": rid}
        if params is not None:
            req["params"] = params
        reply = await self.raw(json.dumps(req).encode() + b"\n", timeout)
        if reply.get("id") != rid:
            self.ok(f"{method}: reply id", False, f"sent {rid}, got {reply.get('id')}")
        return reply

    def shape(self, name: str, obj, key: str):
        spec = SHAPES[key]
        if not isinstance(obj, dict):
            self.ok(name, False, f"expected an object, got {obj!r:.80}")
            return
        bad = [f"{k}: {type(obj.get(k)).__name__}" for k, t in spec.items()
               if k not in obj or not isinstance(obj[k], t) or (t is int and isinstance(obj[k], bool))]
        self.ok(name, not bad, "; ".join(bad))

    def is_error(self, name, reply, err):
        got = reply.get("error", {})
        self.ok(name, got.get("message") == err and got.get("code") == ERRORS[err],
                f"expected {err}, got {json.dumps(reply)[:160]}")

    async def run(self, track: str):
        print("framing and authentication")
        r = await self.call("ping_v1")
        self.ok("ping_v1 before auth", r.get("result") == "pong", str(r))
        self.is_error("info_v1 before auth", await self.call("info_v1"), "not_authenticated")
        self.is_error("wrong key", await self.call("ble_auth_v1", {"key": "A" * 26}), "not_authenticated")
        self.is_error("parse error", await self.raw(b"{not json\n"), "parse_error")
        self.is_error("invalid request", await self.raw(b'{"jsonrpc":"2.0","id":99}\n'), "invalid_request")
        self.is_error("unknown method", await self.call("no_such_v1"), "method_not_found")
        r = await self.call("ble_auth_v1", {"key": self.key})
        self.ok("ble_auth_v1", r.get("result") == {"status": True}, str(r))
        # one request split across two writes, and two requests in one write
        await self.link.send(b'{"jsonrpc":"2.0","method":"pi')
        r = await self.raw(b'ng_v1","id":500}\n')
        self.ok("a request split across writes", r.get("id") == 500 and r.get("result") == "pong", str(r))
        await self.link.send(b'{"jsonrpc":"2.0","method":"ping_v1","id":501}\n'
                             b'{"jsonrpc":"2.0","method":"ping_v1","id":502}\n')
        a = json.loads(await self.link.line(10))
        b = json.loads(await self.link.line(10))
        self.ok("two requests in one write", {a.get("id"), b.get("id")} == {501, 502})

        print("info and status")
        info = (await self.call("info_v1")).get("result")
        self.shape("info_v1 fields", info, "info_v1")
        if isinstance(info, dict):
            self.ok("info_v1 protocol version 1", info.get("protocol", {}).get("version") == 1)
            self.ok("info_v1 track", info.get("track") in ("mock", "pi", "mcu"), info.get("track"))
        st = (await self.call("status_v1")).get("result")
        self.shape("status_v1 fields", st, "status_v1")
        if isinstance(st, dict):
            for block in ("can", "clock", "gnss", "notecard", "notehub", "wifi", "storage", "archive", "host"):
                self.shape(f"status_v1.{block}", st.get(block), f"status_v1.{block}")
            self.ok("can.listen_only is true", st.get("can", {}).get("listen_only") is True)
            checks = st.get("checks", [])
            for c in checks:
                self.shape(f"check {c.get('id')}", c, "check")
            self.ok("checks in contract order", [c.get("id") for c in checks] == CHECK_ORDER,
                    str([c.get("id") for c in checks]))
            self.ok("check states valid", all(c.get("state") in ("ok", "wait", "fail", "off") for c in checks))
        self.is_error("status_v1 rejects params", await self.call("status_v1", {"x": 1}), "bad_params")

        print("config")
        asset = {"site": "Conformance", "equipment_no": "CT-01", "make": "Komatsu", "model": "HM300-5",
                 "serial": "T-1", "hours": 1234}
        r = await self.call("config_set_v1", {
            "record_id": "01JCONFORMANCE0000000000000", "asset": asset,
            "can": {"pigtail": "komatsu-12pin", "channel": 0},
            "wifi": [{"ssid": "CONF-SITE", "security": "wpa2-psk", "psk": TEST_PSK}]})
        self.ok("config_set_v1", r.get("result", {}).get("status") is True, str(r)[:200])
        cfg = (await self.call("config_get_v1")).get("result", {})
        self.ok("config round-trips the asset", cfg.get("asset") == asset, str(cfg.get("asset")))
        wifi = cfg.get("wifi", [{}])
        self.ok("config_get_v1 hides the passphrase",
                "psk" not in wifi[0] and wifi[0].get("has_secret") is True, str(wifi))
        r = await self.call("config_set_v1", {"wifi": [{"ssid": "CONF-SITE", "security": "wpa2-psk"}]})
        self.ok("wifi entry without psk keeps the stored one", r.get("result", {}).get("status") is True, str(r))
        self.is_error("config_set_v1 rejects an unknown block",
                      await self.call("config_set_v1", {"bus": {"bitrate": 500000}}), "bad_params")
        self.is_error("config_set_v1 refuses WPA2-Enterprise",
                      await self.call("config_set_v1", {"wifi": [{"ssid": "E", "security": "wpa2-enterprise"}]}),
                      "not_supported")
        r = await self.call("wifi_scan_v1", timeout=20)
        if track == "mcu":
            self.is_error("wifi_scan_v1 on the MCU track", r, "not_supported")
        else:
            nets = r.get("result", {}).get("networks")
            self.ok("wifi_scan_v1 lists networks", isinstance(nets, list), str(r)[:160])

        print("files")
        files = (await self.call("file_list_v1", {"dir": "manifests"})).get("result", {}).get("files", [])
        self.ok("file_list_v1 manifests", isinstance(files, list) and len(files) > 0, str(files)[:160])
        if files:
            await self.fetch("manifests", files[0]["name"])
        self.is_error("file_receive_v1 refuses a path", await self.call(
            "file_receive_v1", {"dir": "manifests", "name": "../x", "offset": 0, "length": 10}), "bad_params")
        self.is_error("file_receive_v1 refuses a big chunk", await self.call(
            "file_receive_v1", {"dir": "manifests", "name": "x", "offset": 0, "length": 5000}), "bad_params")
        self.is_error("file_list_v1 refuses other dirs",
                      await self.call("file_list_v1", {"dir": "/etc"}), "bad_params")

        print("recce job")
        r = await self.call("recce_run_v1", {"seconds": 60})
        if track == "mcu":
            self.is_error("recce_run_v1 on the MCU track", r, "not_supported")
        else:
            job = r.get("result", {}).get("job")
            self.ok("recce_run_v1 returns a job", isinstance(job, str), str(r))
            self.is_error("a second recce is busy", await self.call("recce_run_v1", {"seconds": 60}), "busy")
            deadline = time.monotonic() + self.recce_wait
            js = {}
            while time.monotonic() < deadline:
                js = (await self.call("job_status_v1", {"job": job})).get("result", {})
                if js.get("state") in ("done", "failed"):
                    break
                await asyncio.sleep(1)
            self.shape("job_status_v1 fields", js, "job_status_v1")
            self.ok("recce finishes", js.get("state") == "done", str(js))
            if js.get("state") == "done":
                await self.fetch(js["result"]["dir"], js["result"]["name"])
        self.is_error("job_status_v1 unknown job",
                      await self.call("job_status_v1", {"job": "nope"}), "not_found")

        print("notecard, logs, segments")
        r = (await self.call("notecard_status_v1")).get("result", {})
        self.shape("notecard_status_v1 fields", r, "status_v1.notecard")
        r = (await self.call("notecard_signal_v1", {"attach_timeout_s": 0}, timeout=20)).get("result")
        self.shape("notecard_signal_v1 fields", r, "notecard_signal_v1")
        r = (await self.call("log_tail_v1", {"unit": "visr-ble", "lines": 5})).get("result", {})
        self.ok("log_tail_v1", isinstance(r.get("text"), str), str(r)[:120])
        self.is_error("log_tail_v1 refuses other units",
                      await self.call("log_tail_v1", {"unit": "sshd", "lines": 5}), "bad_params")
        self.shape("segments_v1 fields", (await self.call("segments_v1")).get("result"), "segments_v1")

        print("secrets")
        self.ok("the passphrase never came back", TEST_PSK.encode() not in self.seen)
        self.ok("the key never came back", self.key.encode() not in self.seen)

    async def fetch(self, d, name):
        crc = (await self.call("file_get_crc_v1", {"dir": d, "name": name})).get("result")
        self.shape(f"file_get_crc_v1 {d}/{name}", crc, "file_get_crc_v1")
        data = bytearray()
        while True:
            r = (await self.call("file_receive_v1",
                                 {"dir": d, "name": name, "offset": len(data), "length": 4096})).get("result")
            self.shape("file_receive_v1 fields", r, "file_receive_v1") if not data else None
            chunk = base64.b64decode(r["data"])
            if zlib.crc32(chunk) != r["crc32"]:
                self.ok("chunk CRC", False, f"at {len(data)}")
                return
            if not chunk:
                break
            data.extend(chunk)
            if len(data) >= r["total"]:
                break
        self.ok(f"{d}/{name} assembles to the whole-file CRC",
                len(data) == crc["size"] and zlib.crc32(data) == crc["crc32"])


async def amain(args) -> int:
    if args.ble:
        link = BleLink(args.ble)
        await link.open()
        print(f"connected to {args.ble}, MTU {link.mtu}")
        track = args.track
    else:
        link = DirectLink(MockVisr(track=args.track, time_scale=600))
        track = args.track
    checker = Checker(link, args.key, args.recce_wait)
    try:
        await checker.run(track)
    finally:
        await link.close()
    print(f"\n{checker.passed} passed, {checker.failed} failed")
    return 1 if checker.failed else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--direct", action="store_true", help="the mock, in-process")
    g.add_argument("--ble", metavar="NAME", help="a device advertising NAME")
    p.add_argument("--key", default=MOCK_KEY)
    p.add_argument("--track", choices=["mock", "pi", "mcu"], default="mock",
                   help="which methods to expect not_supported from")
    p.add_argument("--recce-wait", type=float, default=30.0,
                   help="seconds to wait for a 60 s recce (the mock's --time-scale shortens it)")
    return asyncio.run(amain(p.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
