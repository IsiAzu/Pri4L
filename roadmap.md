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
## Roadmap Addition — Phase 1.5: Multi-Room Extensibility

### Stress Test: Map Extension Beyond Single Room

**Prerequisite:** Phase 2 (client-to-hub streaming) complete and stable.

**What to test:**
- Walk glasses through 2-3 connected rooms during OOBE; verify hub fuses into single coherent map
- Relocalization reliability in rooms beyond hub FOV -- test cold start (glasses off, back on) in a distant room
- Drift accumulation over distance -- place anchors at max range, measure positional stability across sessions
- Loop closure behavior in geometrically similar spaces (identical hallways, repeated furniture)
- WiFi streaming stability at range (2-3 rooms, multiple walls)

**Expected failure modes to document:** relocalization false positives in similar geometry, anchor drift compounding with distance, no ongoing spatial awareness past hub FOV, streaming degradation through walls.

---

### Extension Anchor Modules

**Concept:** Lightweight fixed sensor nodes deployed in rooms beyond hub FOV. No onboard compute -- raw sensor data streams back to hub over local network. Hub owns all processing and world model fusion.

**What each node needs:**
- Depth sensor (no RGB camera -- see privacy note below)
- WiFi or wired ethernet back to hub
- Power (wall outlet or PoE)
- No compute beyond basic USB/network bridge

**Candidate hardware per node:**

| Component | Option | Approx. Cost |
|---|---|---|
| Depth-only ToF | Intel RealSense L515 (discontinued, used) | $80-150 |
| Depth-only ToF | Orbbec Femto Bolt | $230-280 |
| Depth-only ToF | Microsoft Azure Kinect DK (used) | $150-200 |
| Depth structured light | Luxonis OAK-D (depth only mode) | $150-200 |
| Single-board bridge | Raspberry Pi Zero 2W | $15 |
| PoE hat (if wired) | Standard Pi PoE hat | $20 |
| Enclosure + mount | Off the shelf | $10-20 |

**Rough per-node cost: $175-320 depending on sensor choice.**

**Whole-home cost estimate:**

| Scale | Nodes | Approx. Total |
|---|---|---|
| 2-room apartment | 1 extension node | $175-320 |
| 3-4 room home | 2-3 nodes | $350-960 |
| Large home / office | 4-6 nodes | $700-1,900 |

This is significantly cheaper than a single Livox Mid-360 ($800) per room and architecturally more flexible.

---

### Privacy: Depth-Only Anchor Nodes

**The concern:** A camera-equipped sensor in every room is a fundamentally different privacy proposition than a depth sensor. RGB captures faces, screens, documents, intimate behavior. Depth captures geometry and movement -- substantially lower sensitivity.

**Depth-only mitigations:**
- No RGB means no facial recognition, no screen capture, no readable text
- Depth silhouettes can still identify individuals by gait/body shape at high resolution -- worth noting but significantly harder than RGB identification
- Data never leaves local network; hub processes and stores locally
- Open source stack means the processing pipeline is auditable

**Residual concerns depth-only doesn't solve:**
- Presence and movement tracking is still possible -- the system knows when someone enters a room, how long they stay, movement patterns
- In enterprise/multi-user contexts this becomes a meaningful surveillance capability even without RGB
- Needs explicit disclosure and consent model in the privacy design (Phase 8)

**Recommendation:** Depth-only nodes are the right default for extension anchors. Document RGB as an optional upgrade for specific use cases (object recognition, semantic labeling in extended rooms) with explicit user opt-in. The 360° depth anchor concept is architecturally sound and privacy-conservative relative to camera-based alternatives.

---

**Add to dependencies:** Phase 1.5 stress test gates Phase 4 (spatial anchor layer) design -- anchor reliability assumptions need to be validated against real multi-room drift data before the anchor persistence format is finalized.
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
