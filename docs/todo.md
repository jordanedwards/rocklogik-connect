# To do

Queued work for rocklogik-connect. When an item is finished, delete it here and add a dated
entry to [progress.md](progress.md) in the same commit. Numbers are stable; a gap means a
finished item.

1. **Google Cloud OAuth client ids** for `rocklogik-cloud`'s sign-in route (plan §10 item 3).
   Founder; needed by the end of phase 0. ROCKLOGIK-82.
2. **Phase 0, bootstrap**: wireframe review, skeleton on the MIT Bluefruit base with `NOTICE`,
   Docker build and CI, `docs/protocol.md` v1, the mock device, the clean-room JSON-RPC client,
   sign-in. Gate C-0. ROCKLOGIK-81, 84 to 90.
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
