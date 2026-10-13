# Progress

Completed work, newest first. Items arrive here from [todo.md](todo.md) when they are finished.

## 2026-10-13 — Repository created; development plan drafted

The decision to build the provisioning app from the outset, rather than after the proof of
concept, was taken on 13 October 2026 so that the production VISR inherits a provisioning
process that has been used. [Development Plan.md](Development%20Plan.md) is the draft for
review: BLE as the transport because it serves both VISR tracks, JSON-RPC over a Nordic UART
Service as Cricket Connect already speaks, one protocol contract shared by the Pi rig and the
MCU companion, the bench-and-site split of provisioning, three phases aimed at the Kelowna
install with SSH as the fallback, and the Cricket code ownership question flagged before any
file is copied.
