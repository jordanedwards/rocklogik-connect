# rocklogik-connect

The Android app a technician uses in the cab to provision, check and record a RockLogik Scout
VISR install over Bluetooth Low Energy, and later to do the same for the Scout Sensors through
the VISR.

Nothing is built yet. Start with [docs/Development Plan.md](docs/Development%20Plan.md), which
is under review. The design record for the devices is in `../rocklogik`; this repository holds
the app, its device-side protocol contract (`docs/protocol.md`, once written) and the two
tracking files, and no design decisions.

The app derives from `../cricket-connect` (a Bluefruit LE Connect descendant with a JSON-RPC
client over a BLE UART service). What is carried across, and under what licence, is listed in
`NOTICE` as it happens; see the plan's §2 decision 2 and §10 item 1.
