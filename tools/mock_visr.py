#!/usr/bin/env python3
"""A mock VISR: the provisioning protocol (docs/protocol.md) behind a Nordic UART Service.

Runs on any Linux machine with BlueZ and a Bluetooth adapter, using bless. Advertises as
VISR-MOCK (or VISR-<serial>), accepts the mock key, and serves simulated state, so the app can be
built and tested before the Pi rig's visr-ble service exists.

    tools/.venv/bin/python tools/mock_visr.py                 # defaults
    tools/.venv/bin/python tools/mock_visr.py --track mcu     # MCU behaviour: no wifi scan, no recce
    tools/.venv/bin/python tools/mock_visr.py --chunk 240     # bigger notifications once the MTU is 247
    tools/.venv/bin/python tools/mock_visr.py --print-label   # the lid label text, for a QR code
    tools/.venv/bin/python tools/mock_visr.py --bench-file 100  # recce/bench-100k.bin, for ble_bench.py

Limits of the mock, not of the protocol: bless reports neither the negotiated MTU nor connection
events. Notifications are therefore cut to --chunk bytes (default 20, which works at any MTU),
and a new session (unauthenticated, empty buffer) starts whenever a central subscribes to TX.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time

from bless import (BlessServer, GATTAttributePermissions, GATTCharacteristicProperties)

from visr_protocol import MOCK_KEY, MockVisr, Session, lid_label

NUS = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  # phone writes here
TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  # we notify here

log = logging.getLogger("mock_visr")


class Mock:
    def __init__(self, args):
        self.args = args
        self.device = MockVisr(serial=args.serial, key=args.key, track=args.track,
                               time_scale=args.time_scale)
        self.session = self.new_session()
        self.server: BlessServer | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self.out: asyncio.Queue[bytes] = asyncio.Queue()

    def new_session(self) -> Session:
        def trace(direction, obj):
            arrow = "→" if direction == "in" else "←"
            log.info("%s %s", arrow, json.dumps(obj, separators=(",", ":"))[:400])
        return Session(self.device, log=trace)

    # bless callbacks run on the event loop's thread
    def on_read(self, characteristic, **kwargs):
        return characteristic.value

    def on_write(self, characteristic, value, **kwargs):
        if str(characteristic.uuid).upper() != RX:
            return
        for line in self.session.receive(bytes(value)):
            self.out.put_nowait(line)
        if self.session.closed:
            log.warning("five failed authentications: dropping the session")
            self.session = self.new_session()

    def on_subscribe(self, *_):
        log.info("central subscribed: new session")
        self.session = self.new_session()

    async def sender(self):
        while True:
            line = await self.out.get()
            if self.args.slow:
                await asyncio.sleep(self.args.slow)
            if self.args.drop_after and len(line) > self.args.drop_after:
                log.warning("simulating a dropped link mid-reply")
                line = line[: self.args.drop_after]
            for i in range(0, len(line), self.args.chunk):
                ch = self.server.get_characteristic(TX)
                ch.value = bytearray(line[i:i + self.args.chunk])
                self.server.update_value(NUS, TX)
                await asyncio.sleep(0.005)

    async def run(self):
        self.loop = asyncio.get_running_loop()
        name = f"VISR-{self.args.serial}"
        self.server = BlessServer(name=name, loop=self.loop)
        self.server.read_request_func = self.on_read
        self.server.write_request_func = self.on_write
        await self.server.add_new_service(NUS)
        await self.server.add_new_characteristic(
            NUS, RX,
            GATTCharacteristicProperties.write | GATTCharacteristicProperties.write_without_response,
            None, GATTAttributePermissions.writeable)
        await self.server.add_new_characteristic(
            NUS, TX, GATTCharacteristicProperties.notify, None, GATTAttributePermissions.readable)
        # bless has no subscription hook; wrap the BlueZ app's StartNotify.
        app = self.server.app
        original = app.StartNotify
        def start_notify(arg, _orig=original):
            self.on_subscribe()
            if _orig:
                _orig(arg)
        app.StartNotify = start_notify
        await self.server.start()
        log.info("advertising as %s, track %s, key %s", name, self.args.track,
                 "<from --key>" if self.args.key != MOCK_KEY else MOCK_KEY)
        log.info("lid label: %s", lid_label(self.args.serial, self.args.key))
        log.info("workdir %s", self.device.workdir)
        if self.args.bench_file:
            path = os.path.join(self.device.workdir, "recce", f"bench-{self.args.bench_file}k.bin")
            with open(path, "wb") as f:
                f.write(os.urandom(self.args.bench_file * 1024))
            log.info("bench file recce/%s", os.path.basename(path))
        await self.sender()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--serial", default="MOCK")
    p.add_argument("--key", default=MOCK_KEY)
    p.add_argument("--track", choices=["mock", "pi", "mcu"], default="mock")
    p.add_argument("--chunk", type=int, default=20, help="bytes per notification (MTU minus 3)")
    p.add_argument("--slow", type=float, default=0.0, help="seconds to hold every reply")
    p.add_argument("--drop-after", type=int, default=0, help="truncate replies longer than N bytes")
    p.add_argument("--time-scale", type=float, default=1.0,
                   help="simulated seconds per real second (60 makes a 15-minute recce take 15 s)")
    p.add_argument("--print-label", action="store_true")
    p.add_argument("--bench-file", type=int, default=0, metavar="KB",
                   help="write KB random bytes to recce/bench-<KB>k.bin, for ble_bench.py")
    args = p.parse_args()
    if args.print_label:
        print(lid_label(args.serial, args.key))
        return
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    try:
        asyncio.run(Mock(args).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
