# To do

Queued work for rocklogik-connect. When an item is finished, delete it here and add a dated
entry to [progress.md](progress.md) in the same commit. Numbers are stable; a gap means a
finished item.

1. **Google Cloud OAuth client ids** for `rocklogik-cloud`'s sign-in route (plan §10 item 3).
   Founder; needed by the end of phase 0. ROCKLOGIK-82.
2. **Phase 0, bootstrap**, in progress. Done: wireframe review (81), skeleton (84), protocol.md
   (86). Built and passing locally, waiting on a push or a radio: CI (85, needs the first push to
   GitHub), the mock over BLE (87, needs LE on this machine or the Pi), the client against the
   mock from the tablet (88, the same), sign-in on the cloud (89, needs 82 and a deployment).
   Gate C-0 is 90 and needs 82.
3. **Phase 1, provision the rig**, before the Kelowna install, which waits for it: coexistence
   measured, `visr-ble` on the Pi, the screens, the bench script and QR label, the engineer
   terminal, `docs/install.md` and the bench rehearsal. Gate C-1. ROCKLOGIK-91 to 98.
4. **Phase 2, record the install**: recce job and screen, photos, the cloud provisioning routes,
   the record and its offline queue. Gate C-2. ROCKLOGIK-99 to 103.
5. **Phase 3, pucks and the MCU track**: the nRF52840 peripheral, the puck methods and screen,
   bonding and release signing. Gate C-3. ROCKLOGIK-104 to 106.

The decision record entry and the VISR document amendments for the app are ROCKLOGIK-83, in
`rocklogik`.

Wireframes for review: https://claude.ai/artifact/KeRAYAgwoHqSPGRPZMVUet (twelve screens in
three rows; stickies on the right list the protocol calls each screen makes).
