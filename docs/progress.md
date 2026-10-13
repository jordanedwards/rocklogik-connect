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

Reviewed the same day. Three questions answered by Jordan: the CRWN-specific Cricket code is
rewritten clean-room and only the MIT Bluefruit base is copied; Google sign-in is built from the
start rather than a per-phone token; and the Kelowna install waits for phase 1 rather than going
ahead over SSH, so gate C-1 is on the critical path to 1 November 2026 and the install date moves
if it slips.

Later the same day: twelve screens wireframed on a canvas for review
(https://claude.ai/artifact/KeRAYAgwoHqSPGRPZMVUet) and the whole plan put on the ROCKLOGIK board
as ROCKLOGIK-81 to 106, one ticket per day or less, phase by phase, with each phase gate as the
last ticket of its phase. Two things worth knowing: the wireframes use bracketed placeholders
rather than invented numbers wherever a real value is not yet known, and the Site network
screen's wifi write depends on the station-first rule designed in ROCKLOGIK-80, so ROCKLOGIK-94
implements only the station connection that rule needs and leaves the drain to 80.
