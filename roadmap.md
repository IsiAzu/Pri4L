# Roadmap

Living document. Updated as architecture decisions are made and milestones are reached.

---

## Milestone tracker

| Milestone | What it proves | Status |
|-----------|---------------|--------|
| Live depth feed | Hardware working | Done |
| ROS2 topics publishing | Middleware working | Done |
| Live map building | Spatial mapping working | Done |
| Map survives restart + relocalizes | Persistent spatial memory — core demo | Done |
| Client connects to hub map | Hub-to-client communication | Done |
| Phone app streams camera + IMU to hub | Phone-to-hub data pipeline | Done |
| Hub relocalizes phone against saved map | Cross-device localization | Done |
| LLM answers spatial queries | AI integration baseline | Done |
| Digital content anchored to real-world position | AR rendering pipeline | In progress (arcore-overlay branch) |

---

## Phase 1 — Hub sensing (complete)

Establish persistent spatial memory on the hub. All subsequent phases depend on this being solid.

**Build steps:**
1. Ubuntu 22.04 LTS — flash via Balena Etcher, install on GMKtec, update
2. RealSense SDK — depth feed live in `realsense-viewer`
3. ROS2 Humble — `talker` / `listener` handshake works
4. RealSense ROS2 wrapper — topics publishing confirmed
5. RTAB-Map — live 3D map building visible in GUI
6. Persistent map — build map, shut down, relaunch in localization mode, relocalize

**Open problem:** RealSense D435i covers limited FOV. Works for desk-scale prototype. Room-scale coverage requires upgrade (Livox Mid-360, Slamtec RPLIDAR A3 — different SDK, different ROS2 driver, different SLAM tuning).

---

## Phase 2 — Client connection — hybrid architecture (current)

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

1. ~~Android app — stream phone camera + IMU to hub~~ (done — `android/`)
2. ~~Hub relocalization — return pose + anchor positions~~ (done — `launch_phone_localizer.sh`)
3. ARCore overlay — render hub anchor positions on phone camera feed (in progress — `arcore-overlay` branch)
4. Headless proof first — confirm data pipeline before display work

**Note:** Phone-as-client directly validates the personal bridge component and produces a legible demo without glasses hardware. Point phone at desk, see hub-placed anchor floating above correct object. The phone bridge is also critical for mobile mode (decision 007) — this app is a stepping stone toward that.

---

## Phase 3 — Spatial coverage expansion

Two independent tracks: extending the hub's awareness beyond desk scale, and adding near-field depth to the glasses.

### Track A — Multi-room stress test

**Prerequisite:** Phase 2 complete and stable.

**What to test:**
- Walk glasses/phone through 2-3 connected rooms during OOBE; verify hub fuses into single coherent map
- Relocalization reliability in rooms beyond hub FOV — test cold start in a distant room
- Drift accumulation over distance — place anchors at max range, measure positional stability across sessions
- Loop closure behavior in geometrically similar spaces (identical hallways, repeated furniture)
- WiFi streaming stability at range (2-3 rooms, multiple walls)

**Expected failure modes to document:** relocalization false positives in similar geometry, anchor drift compounding with distance, no ongoing spatial awareness past hub FOV, streaming degradation through walls.

Results from this track gate Phase 4 (spatial anchor layer) — anchor reliability assumptions need real multi-room drift data before the persistence format is finalized.

### Track B — Glasses-side depth sensing

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
- **Prerequisite:** Phase 3 Track A stress test results — validates anchor stability assumptions across rooms

---

## Phase 5 — Latency & infrastructure hardening

Hard ceiling: 20ms motion-to-photon. Partition clearly what stays local vs what goes to hub.

- Head tracking + reprojection: must stay on glasses, never offloaded
- Define latency budget per pipeline stage
- WiFi reliability: dedicated WiFi 6/6E AP vs consumer router

### Extension anchor modules

Deploy if Phase 3 Track A stress test reveals that passive relocalization fails beyond hub FOV. Lightweight fixed sensor nodes in rooms beyond hub range — no onboard compute, raw sensor data streams back to hub.

**What each node needs:**
- Depth sensor (no RGB — see privacy note)
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

**Per-node cost: $175-320. Whole-home (2-6 nodes): $350-1,900.**

### Privacy: depth-only anchor nodes

Depth-only is the right default. No RGB means no facial recognition, no screen capture, no readable text. Depth silhouettes can still identify individuals by gait/body shape — substantially harder than RGB but worth noting. Presence/movement tracking is still possible. Needs explicit consent model in Phase 8 privacy design.

RGB is an optional upgrade for specific use cases (object recognition in extended rooms) with explicit user opt-in.

---

## Phase 6 — LLM / AI integration (started)

Hub is wall-powered — no thermal or power constraints, but current hardware (16GB RAM, no discrete GPU) limits model size to ~8B parameters on CPU.

- ~~Local inference stack: Ollama~~ (done — llama3.2:3b installed, ~5s response on CPU)
- ~~Interface between spatial context and AI~~ (done — `spatial_query.py`, queries via `/hub/query` topic)
- **Next: Object/surface detection** — YOLO v8 on hub RGB frames → labeled objects with 3D positions fed into LLM context
- **Next: Upgrade to 8B** — swap to llama3.1:8b for better reasoning once object detection enriches the context
- **Future: Hub GPU upgrade** — current GMKtec M6 Ultra (Ryzen 7640HS, 16GB, no discrete GPU) handles prototype workloads but CPU inference caps at ~8B models and ~5-15s response times. YOLO runs at ~100-200ms/frame on CPU. Upgrade path: eGPU enclosure + RTX 3060 12GB (if USB4/TB available) or replace with mini-ITX build with discrete GPU. This unlocks 10x inference speed, real-time YOLO, and larger models (13B+). Gate: needed when object detection and conversational-speed LLM responses are required simultaneously

---

## Phase 7 — Glasses hardware design

Custom form factor, open source hardware. Android fork (AOSP Level 2) — see decision 007.

- **OS:** Stripped AOSP fork. Two modes — hub mode (thin-client reprojection) and mobile mode (standalone HUD, phone bridge)
- **Chip constraint:** Must be Android-capable. Snapdragon AR2/XR series or Rockchip — microcontroller candidates eliminated
- Fusion 360: handles both organic surfacing and parametric mechanical constraints
- Reference: Brilliant Labs Frame open hardware, OpenGlass
- Weight budget: gram count every component before committing to placement
- Constraints: PCB dimensions, flex cable routing, battery in temples, heat dissipation
- Timewarp/reprojection must bypass SurfaceFlinger in hub mode to meet 20ms budget

---

## Phase 8 — Multi-user / enterprise

- Hub serves multiple glasses simultaneously: shared anchor layer
- Conflict resolution when two users interact with same anchor
- Privacy model: a room that sees everything requires explicit design
