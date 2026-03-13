# Roadmap

Living document. Updated as architecture decisions are made and milestones are reached.

---

## Milestone tracker

| Milestone | What it proves | Status |
|-----------|---------------|--------|
| Live depth feed | Hardware working | — |
| ROS2 topics publishing | Middleware working | — |
| Live map building | Spatial mapping working | — |
| Map survives restart + relocalizes | Persistent spatial memory — core demo | — |
| Client connects to hub map | Hub-to-client communication | — |
| Digital content anchored to real-world position | AR rendering pipeline | — |

---

## Phase 1 — Hub sensing (current)

Establish persistent spatial memory on the hub. All subsequent phases depend on this being solid.

**Build steps:**
1. Ubuntu 22.04 LTS — flash via Balena Etcher, install on GMKtec, update
2. RealSense SDK — depth feed live in `realsense-viewer`
3. ROS2 Humble — `talker` / `listener` handshake works
4. RealSense ROS2 wrapper — topics publishing confirmed
5. RTAB-Map — live 3D map building visible in GUI
6. Persistent map — build map, shut down, relaunch in localization mode, relocalize

**Open problem:** RealSense D435i covers limited FOV. Works for desk-scale POC. Room-scale coverage requires upgrade (Livox Mid-360, Slamtec RPLIDAR A3 — different SDK, different ROS2 driver, different SLAM tuning).

---

## Phase 2 — Client connection — hybrid architecture

Connect a client device to the hub's spatial map. Two parallel tracks. Pursue Track A first; fall back to Track B if blocked.

### Hybrid render split

The 20ms motion-to-photon constraint is non-negotiable. Anything that touches head pose → display must stay local. The hybrid split reflects this:

| | Owns |
|-|------|
| **Hub** | World model, spatial anchors, scene composition, heavy inference, base frame rendering |
| **Glasses** | Head tracking (IMU), timewarp / reprojection, camera feed to hub, display output |
| **Hub → glasses** | Composed frames + anchor metadata |
| **Glasses → hub** | Camera feed (relocalization input), head pose (scene composition input) |

### Track A — INMO Air3 (primary)

1. ADB in — enable developer options
2. Verify IMU accessible via standard Android `SensorManager` (gate condition — if locked, move to Track B)
3. Vanilla Android app — stream camera to hub via RTSP or WebRTC
4. Hub relocalization against saved map — return pose + anchor data
5. Unity SDK — render simple AR overlay at anchored world position
6. Validate timewarp viability via what IMOS 3.0 exposes

**Open questions:**
- Frame transport: MJPEG (easy, lossy) vs H.264/RTP vs WebRTC (adaptive, Android libraries available)
- IMU access: standard Android API or locked behind IMOS 3.0?

### Track B — Phone fallback

1. Android app — stream phone camera + IMU to hub
2. Hub relocalization — return pose + anchor positions
3. ARCore overlay — render hub anchor positions on phone camera feed
4. Headless proof first — confirm data pipeline before display work

**Note:** Phone-as-client directly validates the personal bridge component and produces a legible demo without glasses hardware. Point phone at desk, see hub-placed anchor floating above correct object.

---

## Phase 3 — Glasses-side depth sensing

Hub sees the room. Glasses need near-field understanding: hands, held objects, face-proximate interaction. Hub LIDAR is too far away and at the wrong angle for this.

- Lightweight depth on glasses: ToF (ST VL53 series) or stereo (OAK-D Lite as dev reference)
- Key constraint: low power, low weight — cannot repeat the puck problem
- Fuse glasses-side depth with hub world model: two coordinate frames, real-time transform required

---

## Phase 4 — Spatial anchor layer

RTAB-Map gives the map. An anchor management layer sits on top.

- Anchor management layer on top of RTAB-Map
- Persistence format: storage, versioning, session restart survival
- Multi-user anchor conflict resolution

---

## Phase 5 — Latency management

Hard ceiling: 20ms motion-to-photon. Partition clearly what stays local vs what goes to hub.

- Head tracking + reprojection: must stay on glasses, never offloaded
- Define latency budget per pipeline stage
- WiFi reliability: dedicated WiFi 6/6E AP vs consumer router

---

## Phase 6 — LLM / AI integration

Hub is wall-powered — no inference constraints.

- Local inference stack: Ollama / llama.cpp
- Define interface between spatial context and AI: what does the model know about the room, what can it see, how is it queried

---

## Phase 7 — Glasses hardware design

Custom form factor, open source hardware.

- Fusion 360: handles both organic surfacing and parametric mechanical constraints
- Reference: Brilliant Labs Frame open hardware, OpenGlass
- Weight budget: gram count every component before committing to placement
- Constraints: PCB dimensions, flex cable routing, battery in temples, heat dissipation

---

## Phase 8 — Multi-user / enterprise

- Hub serves multiple glasses simultaneously: shared anchor layer
- Conflict resolution when two users interact with same anchor
- Privacy model: a room that sees everything requires explicit design
