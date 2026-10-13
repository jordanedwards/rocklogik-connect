"""The VISR provisioning protocol, version 1, as a transport-free device.

`docs/protocol.md` is the contract; this module is a mock that serves it, with simulated state,
so the app can be built and tested before the Pi rig's `visr-ble` exists. `Session` does the
newline framing and the per-connection authentication; `MockVisr` holds the device state and
answers requests. Neither knows about Bluetooth: `mock_visr.py` puts them behind a Nordic UART
Service, and `conformance.py --direct` drives them in-process.
"""

from __future__ import annotations

import base64
import json
import os
import tempfile
import threading
import time
import uuid
import zlib
from dataclasses import dataclass, field

MAX_LINE = 16_384
MAX_CHUNK = 4_096
SECRET_FIELDS = ("key", "psk")
MOCK_KEY = "MOCKKEYMOCKKEYMOCKKEYMOCKK"  # 26 base32 characters; the mock's lid label

ERRORS = {
    "parse_error": -32700,
    "invalid_request": -32600,
    "method_not_found": -32601,
    "bad_params": -32602,
    "not_supported": -32001,
    "not_authenticated": -32002,
    "busy": -32003,
    "not_found": -32004,
    "failed": -32005,
}

CHECK_ORDER = ["can", "frames", "clock", "notecard", "notehub", "wifi", "storage", "archive"]
FILE_DIRS = ("recce", "manifests", "logs")
LOG_UNITS = ("visr-logger", "visr-ble", "visr-drain", "visr-can")


class RpcError(Exception):
    def __init__(self, name: str, data: str | None = None):
        super().__init__(name)
        self.name = name
        self.data = data


def redact(obj):
    """A copy of obj with every secret field replaced, for logs and terminals."""
    if isinstance(obj, dict):
        return {k: ("<redacted>" if k in SECRET_FIELDS else redact(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [redact(v) for v in obj]
    return obj


def lid_label(serial: str, key: str) -> str:
    return f"rlk1:visr?s={serial}&k={key}&ap=VISR-{serial}-svc"


@dataclass
class Job:
    id: str
    kind: str
    seconds: float
    started: float
    result_name: str
    state: str = "running"
    error: str | None = None


@dataclass
class MockVisr:
    serial: str = "MOCK"
    key: str = MOCK_KEY
    track: str = "mock"
    time_scale: float = 1.0          # simulated seconds per real second, for jobs and warm-up
    workdir: str = field(default_factory=lambda: tempfile.mkdtemp(prefix="visr-mock-"))
    started: float = field(default_factory=time.time)
    config: dict = field(default_factory=lambda: {
        "record_id": None,
        "asset": None,
        "can": {"pigtail": "komatsu-12pin", "channel": 0},
        "wifi": [],
    })
    jobs: dict = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def __post_init__(self):
        for d in FILE_DIRS:
            os.makedirs(os.path.join(self.workdir, d), exist_ok=True)
        manifest = os.path.join(self.workdir, "manifests", "can-0001.manifest.json")
        if not os.path.exists(manifest):
            with open(manifest, "w") as f:
                json.dump({"segment_id": "can-0001", "kind": "can", "bytes": 1_048_576,
                           "sha256": "0" * 64, "frames": 600_000}, f)

    # -- served methods ------------------------------------------------------------------

    def methods(self) -> dict:
        unsupported = {"wifi_scan_v1", "recce_run_v1"} if self.track == "mcu" else set()
        table = {
            "ping_v1": self.ping_v1,
            "ble_auth_v1": None,  # handled by Session
            "info_v1": self.info_v1,
            "status_v1": self.status_v1,
            "config_get_v1": self.config_get_v1,
            "config_set_v1": self.config_set_v1,
            "wifi_scan_v1": self.wifi_scan_v1,
            "recce_run_v1": self.recce_run_v1,
            "job_status_v1": self.job_status_v1,
            "file_list_v1": self.file_list_v1,
            "file_receive_v1": self.file_receive_v1,
            "file_get_crc_v1": self.file_get_crc_v1,
            "notecard_status_v1": self.notecard_status_v1,
            "notecard_signal_v1": self.notecard_signal_v1,
            "log_tail_v1": self.log_tail_v1,
            "segments_v1": self.segments_v1,
            "reboot_v1": self.reboot_v1,
            "shutdown_v1": self.shutdown_v1,
        }
        for m in unsupported:
            table[m] = self._unsupported
        return table

    def _unsupported(self, params):
        raise RpcError("not_supported", f"the {self.track} track does not serve this method")

    def _uptime(self) -> float:
        return (time.time() - self.started) * self.time_scale

    def ping_v1(self, params):
        _no_params(params)
        return "pong"

    def info_v1(self, params):
        _no_params(params)
        served = {}
        for name in self.methods():
            base, _, ver = name.rpartition("_v")
            served.setdefault(base, []).append(int(ver))
        return {
            "serial": self.serial,
            "track": self.track,
            "hw_rev": "mock",
            "sw_version": "mock-0.1.0",
            "protocol": {"version": 1, "methods": served},
            "notecard_uid": "dev:000000000000000",
            "ble_addr": "00:00:00:00:00:00",
            "uptime_s": int(self._uptime()),
            "time": time.time(),
        }

    def status_v1(self, params):
        _no_params(params)
        up = self._uptime()
        disciplined = up > 30
        acked = up > 20
        wifi_on = bool(self.config["wifi"])
        s = {
            "at": time.time(),
            "uptime_s": int(up),
            "can": {"live": True, "bitrate": 250_000, "listen_only": True,
                    "frames_per_s": 612.0, "bus_errors": 0, "last_frame_s_ago": 0.0},
            "clock": {"rtc_ok": True, "disciplined": disciplined,
                      "source": "gnss" if disciplined else None,
                      "last_correction_s_ago": (up - 30) if disciplined else None},
            "gnss": {"fix": disciplined, "sats": 9 if disciplined else 0},
            "notecard": {"present": True, "transport": "cell", "bars": 3, "rat": "lte-m",
                         "last_sync_s_ago": 12.0, "queue": 0},
            "notehub": {"last_ack_s_ago": 40.0 if acked else None},
            "wifi": {"configured": wifi_on, "connected": wifi_on,
                     "ssid": self.config["wifi"][0]["ssid"] if wifi_on else None,
                     "backend_ok": True if wifi_on else None},
            "storage": {"free_pct": 94.0, "root_ro": True},
            "archive": {"writing": True, "segments_pending": 1, "segments_acked": 0},
            "host": {"cpu_temp_c": 48.5, "supply_v": 5.12},
        }
        s["checks"] = _checks(s, up)
        return s

    def config_get_v1(self, params):
        _no_params(params)
        with self.lock:
            cfg = json.loads(json.dumps(self.config))
        for w in cfg["wifi"]:
            if w.pop("psk", None) is not None:
                w["has_secret"] = True
        return cfg

    def config_set_v1(self, params):
        if not isinstance(params, dict) or not params:
            raise RpcError("bad_params", "config_set_v1 takes an object with at least one block")
        allowed = {"record_id", "asset", "can", "wifi"}
        extra = set(params) - allowed
        if extra:
            raise RpcError("bad_params", f"unknown blocks: {sorted(extra)}")
        restarted = []
        with self.lock:
            if "record_id" in params:
                _type(params["record_id"], (str, type(None)), "record_id")
                self.config["record_id"] = params["record_id"]
            if "asset" in params:
                _validate_asset(params["asset"])
                self.config["asset"] = params["asset"]
            if "can" in params:
                can = params["can"]
                _type(can, dict, "can")
                if can.get("pigtail") not in ("komatsu-12pin", "deutsch-9pin", "other"):
                    raise RpcError("bad_params", "can.pigtail")
                if can.get("channel") not in (0, 1):
                    raise RpcError("bad_params", "can.channel")
                self.config["can"] = {"pigtail": can["pigtail"], "channel": can["channel"]}
                restarted.append("visr-logger")
            if "wifi" in params:
                self.config["wifi"] = _merge_wifi(self.config["wifi"], params["wifi"])
                restarted.append("NetworkManager")
        return {"status": True, "restarted": restarted}

    def wifi_scan_v1(self, params):
        _no_params(params)
        known = {w["ssid"] for w in self.config["wifi"]}
        nets = [
            {"ssid": "MOCK-SITE", "rssi_dbm": -52, "security": "wpa2-psk", "captive": False},
            {"ssid": "MOCK-GUEST", "rssi_dbm": -67, "security": "open", "captive": True},
            {"ssid": "MOCK-FAR", "rssi_dbm": -84, "security": "wpa3-sae", "captive": None},
        ]
        for n in nets:
            n["known"] = n["ssid"] in known
        return {"networks": nets}

    def recce_run_v1(self, params):
        if not isinstance(params, dict) or set(params) != {"seconds"}:
            raise RpcError("bad_params", "recce_run_v1 takes {seconds}")
        seconds = params["seconds"]
        if not isinstance(seconds, int) or isinstance(seconds, bool) or not 60 <= seconds <= 3600:
            raise RpcError("bad_params", "seconds must be an integer from 60 to 3600")
        with self.lock:
            for j in self.jobs.values():
                if j.kind == "recce" and self._job_state(j) == "running":
                    raise RpcError("busy", "a recce is already running")
            jid = uuid.uuid4().hex[:12]
            name = f"recce-{time.strftime('%Y%m%dT%H%M%S')}-{jid[:4]}.json"
            self.jobs[jid] = Job(jid, "recce", seconds, time.time(), name)
        return {"job": jid}

    def _job_state(self, j: Job) -> str:
        if j.state == "running" and (time.time() - j.started) * self.time_scale >= j.seconds:
            path = os.path.join(self.workdir, "recce", j.result_name)
            with open(path, "w") as f:
                json.dump(_recce_report(self.serial, j), f, indent=1)
            j.state = "done"
        return j.state

    def job_status_v1(self, params):
        if not isinstance(params, dict) or set(params) != {"job"}:
            raise RpcError("bad_params", "job_status_v1 takes {job}")
        with self.lock:
            j = self.jobs.get(params["job"])
            if j is None:
                raise RpcError("not_found", "no such job")
            state = self._job_state(j)
            elapsed = (time.time() - j.started) * self.time_scale
        progress = 1.0 if state == "done" else min(elapsed / j.seconds, 0.99)
        return {
            "job": j.id, "kind": j.kind, "state": state, "progress": round(progress, 3),
            "eta_s": 0 if state == "done" else int(max(j.seconds - elapsed, 0)),
            "result": {"dir": "recce", "name": j.result_name} if state == "done" else None,
            "error": j.error,
        }

    def _path(self, params) -> str:
        d, name = params.get("dir"), params.get("name")
        if d not in FILE_DIRS or not isinstance(name, str) or "/" in name or name.startswith("."):
            raise RpcError("bad_params", "dir must be recce, manifests or logs; name a plain file name")
        path = os.path.join(self.workdir, d, name)
        if not os.path.isfile(path):
            raise RpcError("not_found", f"{d}/{name}")
        return path

    def file_list_v1(self, params):
        if not isinstance(params, dict) or params.get("dir") not in FILE_DIRS or len(params) != 1:
            raise RpcError("bad_params", "file_list_v1 takes {dir}")
        root = os.path.join(self.workdir, params["dir"])
        files = []
        for n in os.listdir(root):
            st = os.stat(os.path.join(root, n))
            files.append({"name": n, "size": st.st_size, "mtime": int(st.st_mtime)})
        files.sort(key=lambda f: f["mtime"], reverse=True)
        return {"files": files}

    def file_receive_v1(self, params):
        if not isinstance(params, dict) or set(params) != {"dir", "name", "offset", "length"}:
            raise RpcError("bad_params", "file_receive_v1 takes {dir, name, offset, length}")
        off, length = params["offset"], params["length"]
        if not (isinstance(off, int) and isinstance(length, int)) or off < 0 or not 0 < length <= MAX_CHUNK:
            raise RpcError("bad_params", f"offset >= 0 and 0 < length <= {MAX_CHUNK}")
        path = self._path(params)
        total = os.path.getsize(path)
        with open(path, "rb") as f:
            f.seek(off)
            data = f.read(length)
        return {"offset": off, "length": len(data), "total": total,
                "data": base64.b64encode(data).decode(), "crc32": zlib.crc32(data)}

    def file_get_crc_v1(self, params):
        if not isinstance(params, dict) or set(params) != {"dir", "name"}:
            raise RpcError("bad_params", "file_get_crc_v1 takes {dir, name}")
        path = self._path(params)
        with open(path, "rb") as f:
            data = f.read()
        return {"size": len(data), "crc32": zlib.crc32(data)}

    def notecard_status_v1(self, params):
        _no_params(params)
        nc = dict(self.status_v1(None)["notecard"])
        nc.update({"uid": "dev:000000000000000", "sku": "NOTE-MOCK", "firmware": "mock"})
        return nc

    def notecard_signal_v1(self, params):
        if not isinstance(params, dict) or set(params) != {"attach_timeout_s"}:
            raise RpcError("bad_params", "notecard_signal_v1 takes {attach_timeout_s}")
        t = params["attach_timeout_s"]
        if not isinstance(t, int) or not 0 <= t <= 120:
            raise RpcError("bad_params", "attach_timeout_s 0 to 120")
        return {"bars": 3, "rssi": -71, "rsrp": -98, "rat": "lte-m", "band": "LTE BAND 4",
                "carrier": "mock"}

    def log_tail_v1(self, params):
        if not isinstance(params, dict) or set(params) != {"unit", "lines"}:
            raise RpcError("bad_params", "log_tail_v1 takes {unit, lines}")
        if params["unit"] not in LOG_UNITS:
            raise RpcError("bad_params", f"unit is one of {LOG_UNITS}")
        n = params["lines"]
        if not isinstance(n, int) or not 1 <= n <= 200:
            raise RpcError("bad_params", "lines 1 to 200")
        lines = [f"mock {params['unit']}: line {i}" for i in range(n)]
        return {"text": "\n".join(lines)}

    def segments_v1(self, params):
        _no_params(params)
        return {"pending": 1, "acked": 0, "reclaimable": 0,
                "oldest_pending_started_at": int(self.started)}

    def reboot_v1(self, params):
        _no_params(params)
        return {"status": True}

    def shutdown_v1(self, params):
        _no_params(params)
        return {"status": True}


class Session:
    """One BLE connection: newline framing, authentication, dispatch."""

    def __init__(self, device: MockVisr, log=None):
        self.device = device
        self.buf = bytearray()
        self.authed = False
        self.failures = 0
        self.closed = False
        self.log = log or (lambda direction, obj: None)

    def receive(self, data: bytes) -> list[bytes]:
        """Bytes in; complete reply lines out, each ending in a newline."""
        out = []
        self.buf.extend(data)
        while True:
            nl = self.buf.find(b"\n")
            if nl < 0:
                if len(self.buf) > MAX_LINE:
                    self.buf.clear()
                    out.append(_line(_error(None, "bad_params", "line too long")))
                break
            raw = bytes(self.buf[:nl])
            del self.buf[:nl + 1]
            if len(raw) > MAX_LINE:
                out.append(_line(_error(None, "bad_params", "line too long")))
                continue
            if not raw.strip():
                continue
            reply = self.handle_line(raw)
            if reply is not None:
                out.append(_line(reply))
        return out

    def handle_line(self, raw: bytes) -> dict:
        try:
            req = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return _error(None, "parse_error")
        self.log("in", redact(req))
        reply = self.handle(req)
        self.log("out", redact(reply))
        return reply

    def handle(self, req) -> dict:
        if (not isinstance(req, dict) or req.get("jsonrpc") != "2.0"
                or not isinstance(req.get("method"), str)
                or not isinstance(req.get("id"), int) or isinstance(req.get("id"), bool)):
            rid = req.get("id") if isinstance(req, dict) and isinstance(req.get("id"), int) else None
            return _error(rid, "invalid_request")
        rid, method, params = req["id"], req["method"], req.get("params")
        table = self.device.methods()
        if method not in table:
            return _error(rid, "method_not_found")
        try:
            if method == "ble_auth_v1":
                return {"jsonrpc": "2.0", "result": self._auth(params), "id": rid}
            if method != "ping_v1" and not self.authed:
                raise RpcError("not_authenticated")
            return {"jsonrpc": "2.0", "result": table[method](params), "id": rid}
        except RpcError as e:
            return _error(rid, e.name, e.data)
        except Exception as e:  # a bug in the mock is still an answer, never silence
            return _error(rid, "failed", f"{type(e).__name__}: {e}")

    def _auth(self, params):
        if not isinstance(params, dict) or set(params) != {"key"} or not isinstance(params["key"], str):
            raise RpcError("bad_params", "ble_auth_v1 takes {key}")
        if params["key"].strip().upper() == self.device.key:
            self.authed = True
            self.failures = 0
            return {"status": True}
        self.failures += 1
        if self.failures >= 5:
            self.closed = True
        raise RpcError("not_authenticated", "wrong key")


# -- helpers ---------------------------------------------------------------------------------

def _line(obj) -> bytes:
    return json.dumps(obj, separators=(",", ":")).encode() + b"\n"


def _error(rid, name, data=None) -> dict:
    err = {"code": ERRORS[name], "message": name}
    if data:
        err["data"] = data
    return {"jsonrpc": "2.0", "error": err, "id": rid}


def _no_params(params):
    if params not in (None, {}, []):
        raise RpcError("bad_params", "this method takes no params")


def _type(v, t, name):
    if not isinstance(v, t):
        raise RpcError("bad_params", name)


def _validate_asset(a):
    _type(a, dict, "asset")
    fields = {"site", "equipment_no", "make", "model", "serial", "hours", "sap_equipment",
              "sap_floc", "maximo_asset", "maximo_site"}
    extra = set(a) - fields
    if extra:
        raise RpcError("bad_params", f"asset: unknown fields {sorted(extra)}")
    for k in fields - {"hours"}:
        if k in a:
            _type(a[k], (str, type(None)), f"asset.{k}")
    if "hours" in a and a["hours"] is not None and not isinstance(a["hours"], (int, float)):
        raise RpcError("bad_params", "asset.hours")


def _merge_wifi(current: list, new) -> list:
    _type(new, list, "wifi")
    stored = {w["ssid"]: w for w in current}
    out = []
    for w in new:
        _type(w, dict, "wifi entry")
        if not isinstance(w.get("ssid"), str) or not w["ssid"]:
            raise RpcError("bad_params", "wifi.ssid")
        sec = w.get("security")
        if sec == "wpa2-enterprise":
            raise RpcError("not_supported", "WPA2-Enterprise is not supported in version 1")
        if sec not in ("open", "wpa2-psk", "wpa3-sae"):
            raise RpcError("bad_params", "wifi.security")
        entry = {"ssid": w["ssid"], "security": sec}
        if sec != "open":
            psk = w.get("psk", stored.get(w["ssid"], {}).get("psk"))
            if not isinstance(psk, str) or not 8 <= len(psk) <= 63:
                raise RpcError("bad_params", f"wifi {w['ssid']}: a passphrase of 8 to 63 characters")
            entry["psk"] = psk
        out.append(entry)
    return out


def _checks(s: dict, up: float) -> list:
    c = {}
    can = s["can"]
    c["can"] = ("ok" if can["live"] and can["bitrate"] else "fail",
                f"Live · {can['bitrate'] // 1000} kbit/s latched · listen-only" if can["live"]
                else "No frames on the bus")
    c["frames"] = ("ok", f"{can['frames_per_s']:.0f} per second · {can['bus_errors']} bus errors")
    clk = s["clock"]
    c["clock"] = (("ok", f"RTC disciplined from {clk['source'].upper()}") if clk["disciplined"]
                  else ("wait", "RTC running · waiting for first GPS fix"))
    nc = s["notecard"]
    c["notecard"] = ("ok" if nc["present"] and nc["transport"] != "none" else "fail",
                     f"{'Cellular' if nc['transport'] == 'cell' else nc['transport']} · "
                     f"{nc['rat'].upper()} · {nc['bars']} bars")
    ack = s["notehub"]["last_ack_s_ago"]
    c["notehub"] = (("ok", f"Heartbeat acknowledged {int(ack // 60)} min ago") if ack is not None
                    else ("wait" if up < 600 else "fail", "Waiting for the first heartbeat"))
    w = s["wifi"]
    c["wifi"] = (("off", "Not configured") if not w["configured"]
                 else ("ok", f"Connected to {w['ssid']} · backend reached") if w["connected"] and w["backend_ok"]
                 else ("fail", f"Not connected to {w['ssid']}"))
    st = s["storage"]
    c["storage"] = ("ok" if st["free_pct"] > 15 and st["root_ro"] else "fail",
                    f"{st['free_pct']:.0f} % free · read-only root")
    ar = s["archive"]
    c["archive"] = ("ok" if ar["writing"] else "fail",
                    f"Writing · {ar['segments_pending']} segments pending upload")
    titles = {"can": "CAN bus", "frames": "Frames", "clock": "Clock", "notecard": "Notecard",
              "notehub": "Notehub", "wifi": "Site wifi", "storage": "Storage", "archive": "Archive"}
    return [{"id": k, "state": c[k][0], "title": titles[k], "detail": c[k][1]} for k in CHECK_ORDER]


def _recce_report(serial: str, j: Job) -> dict:
    return {
        "serial": serial, "seconds": j.seconds, "finished_at": time.time(),
        "ecus": [{"sa": 0, "name": "Engine #1"}, {"sa": 3, "name": "Transmission #1"},
                 {"sa": 11, "name": "Brakes"}, {"sa": 23, "name": "Instrument cluster"}],
        "pgn_count": 48, "decode_coverage_pct": 71.0,
        "signals": {"engine_speed": "live", "output_shaft_speed": "live", "gear": "live",
                    "ground_speed": "static"},
        "note": "Mock report: every value here is invented for testing.",
    }
