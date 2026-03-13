# AR Spatial Hub

Open source proof-of-concept for a hub-and-spoke AR architecture. A fixed room-based hub handles persistent spatial memory and heavy compute. Lightweight glasses act as a thin-client display peripheral.

**Core thesis:** move weight and compute off the face and into the room.

---

## Architecture

| Component | Role |
|-----------|------|
| Hub (room tower) | Wall-powered. SLAM, spatial mapping, LLM inference, persistent world model. No thermal or battery constraints. |
| Glasses (thin client) | Display, eye tracking, head tracking, camera, microphones, WiFi. Receives world model from hub. Does not run SLAM. |
| Phone (personal bridge) | Cellular, personal context, authentication. Anything that needs to leave the local network. |

## Why this architecture

Commercial AR puts a compute puck in your pocket with a beefy AI coprocessor, rechargeable batteries, 5G, and active cooling. That is the right answer when you are mobile. At home or in a fixed workspace, you are paying weight and cost penalties for mobility you do not need. Wall power removes every constraint. The glasses become a display peripheral. The room becomes the brain.

---

## Current hardware

| Device | Role |
|--------|------|
| GMKtec M6 Ultra (Ryzen 7640HS, 16GB RAM, 1TB) | Hub |
| Intel RealSense D435i (depth + IMU) | Hub sensor |
| Ubuntu 22.04 LTS | Hub OS |
| INMO Air3 (Android 14, Snapdragon XR, 1080p RGB waveguide, 36° FOV) | Glasses client — Track A |

## Software stack

| Tool | Role |
|------|------|
| RealSense SDK | Hardware interface for D435i |
| ROS2 Humble | Middleware — connects sensor data to processing |
| RTAB-Map | Spatial mapping, loop closure, persistent storage |
| OpenXR / Monado | Glasses-side XR runtime (future) |

---

## Repo structure

```
Pri4L/
├── README.md
├── roadmap.md
├── decisions/
│   ├── 001-no-companion-puck.md
│   ├── 002-hybrid-render-split.md
│   └── 003-phone-over-pi-fallback.md
├── hardware/
│   └── bom.md
└── src/
```

---

## Key concepts

| Term | Definition |
|------|------------|
| SLAM | Simultaneous Localization and Mapping. Builds a map while tracking position within it. |
| Relocalization | How a client re-establishes its position in a saved map after reconnecting. |
| Loop closure | SLAM recognizes a previously visited location and corrects accumulated drift. |
| Reprojection / timewarp | Last-millisecond frame correction to match actual head position. Must stay on glasses — never offloaded. |
| ROS2 | Robotics middleware. Nervous system connecting sensor data to processing to output. |
| RTAB-Map | Spatial mapping brain. Map building, loop closure, persistent storage. |
| OpenXR | Khronos Group standard API layer for XR. Foundation for glasses-side software. |
| Monado | Open source OpenXR runtime. |
| ToF | Time of Flight. Depth sensing method relevant for glasses-side near-field sensing. |
