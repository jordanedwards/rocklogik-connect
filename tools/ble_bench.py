#!/usr/bin/env python3
"""BLE latency, drop rate and file throughput against a VISR (ROCKLOGIK-91).

Connects as a central, authenticates, then times N pings and fetches one file with
file_receive_v1, checking it against file_get_crc_v1. Run it once per radio state on the
peripheral and compare. Start the mock with --bench-file 100 for the 100 KB recce report.

    tools/.venv/bin/python tools/ble_bench.py --ble VISR-MOCK --label "wifi off"
    tools/.venv/bin/python tools/ble_bench.py --ble VISR-MOCK --pings 200 --file bench-100k.bin

A ping that gets no reply within --timeout counts as dropped. One result line per run is
appended to --out as JSON, so the four states land in one file.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import statistics
import time
import zlib

from conformance import BleLink
from visr_protocol import MAX_CHUNK, MOCK_KEY


class Bench:
    def __init__(self, link):
        self.link = link
        self.next_id = 1

    async def call(self, method, params=None, timeout=10.0):
        rid = self.next_id
        self.next_id += 1
        req = {"jsonrpc": "2.0", "method": method, "id": rid}
        if params is not None:
            req["params"] = params
        await self.link.send(json.dumps(req).encode() + b"\n")
        while True:
            reply = json.loads(await self.link.line(timeout))
            if reply.get("id") == rid:  # a late reply to a dropped ping is skipped
                if "error" in reply:
                    raise RuntimeError(f"{method}: {reply['error']}")
                return reply["result"]


async def run(args) -> dict:
    t0 = time.monotonic()
    link = BleLink(args.ble)
    await link.open()
    connect_s = time.monotonic() - t0
    b = Bench(link)
    out = {"label": args.label, "device": args.ble, "mtu": link.mtu, "connect_s": round(connect_s, 2)}
    try:
        await b.call("ble_auth_v1", {"key": args.key})
        rtts, dropped = [], 0
        for _ in range(args.pings):
            t = time.monotonic()
            try:
                await b.call("ping_v1", timeout=args.timeout)
                rtts.append((time.monotonic() - t) * 1000)
            except TimeoutError:
                dropped += 1
        rtts.sort()
        out.update({
            "pings": args.pings, "dropped": dropped,
            "rtt_ms_median": round(statistics.median(rtts), 1) if rtts else None,
            "rtt_ms_p95": round(rtts[int(len(rtts) * 0.95) - 1], 1) if rtts else None,
            "rtt_ms_max": round(rtts[-1], 1) if rtts else None,
        })
        crc = await b.call("file_get_crc_v1", {"dir": "recce", "name": args.file})
        data = bytearray()
        t = time.monotonic()
        while len(data) < crc["size"]:
            r = await b.call("file_receive_v1", {"dir": "recce", "name": args.file,
                                                 "offset": len(data), "length": args.chunk})
            chunk = base64.b64decode(r["data"])
            if zlib.crc32(chunk) != r["crc32"]:
                raise RuntimeError(f"chunk CRC mismatch at {len(data)}")
            data.extend(chunk)
        secs = time.monotonic() - t
        out.update({
            "file_bytes": len(data), "file_s": round(secs, 2),
            "file_kb_per_s": round(len(data) / 1024 / secs, 1),
            "file_ok": zlib.crc32(data) == crc["crc32"],
        })
    finally:
        await link.close()
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ble", required=True, metavar="NAME")
    p.add_argument("--key", default=MOCK_KEY)
    p.add_argument("--label", default="", help="the radio state, for the record")
    p.add_argument("--pings", type=int, default=100)
    p.add_argument("--timeout", type=float, default=3.0, help="seconds before a ping counts as dropped")
    p.add_argument("--file", default="bench-100k.bin")
    p.add_argument("--chunk", type=int, default=MAX_CHUNK, help="bytes per file_receive_v1")
    p.add_argument("--out", default="ble_bench.jsonl")
    args = p.parse_args()
    res = asyncio.run(run(args))
    print(json.dumps(res, indent=2))
    with open(args.out, "a") as f:
        f.write(json.dumps(res) + "\n")


if __name__ == "__main__":
    main()
