# AR Spatial Hub

Open source prototype for a hub-and-spoke AR architecture. A fixed room-based hub handles persistent spatial memory and heavy compute. Lightweight glasses act as a thin-client display peripheral.

**Core thesis:** move weight and compute off the face and into the room.

---

## Architecture

| Component | Role |
|-----------|------|
| Hub (room tower) | Wall-powered. SLAM, spatial mapping, LLM inference, persistent world model. No thermal or battery constraints. |
| Glasses (thin client) | Android fork (AOSP Level 2). Two modes: **hub mode** (receives composed frames, local reprojection) and **mobile mode** (standalone HUD, phone-bridged apps). Does not run SLAM. |
| Phone (personal bridge) | Cellular, personal context, authentication. In mobile mode, serves as primary compute bridge for glasses. |

## Why this architecture

Commercial AR puts a compute puck in your pocket with a beefy AI coprocessor, rechargeable batteries, 5G, and active cooling. That is the right answer when you are mobile. At home or in a fixed workspace, you are paying weight and cost penalties for mobility you do not need. Wall power removes every constraint. The glasses become a display peripheral. The room becomes the brain.

Away from the hub, the glasses switch to mobile mode — a lightweight HUD powered by the phone as compute bridge. No spatial AR, no world model, but still useful: notifications, navigation, body-relative UI, and phone app bridging.

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
| Ollama (llama3.2:3b) | Local LLM inference — spatial queries |
| OpenXR / Monado | Glasses-side XR runtime (future) |

---

## Repo structure

```
Pri4L/
├── README.md
├── roadmap.md
├── setup.sh                        # Full install from clean Ubuntu 22.04
├── launch_hub.sh                   # Start hub (RealSense + RTAB-Map + rosbridge)
├── launch_phone_localizer.sh       # Phone relocalization (standalone)
├── launch_spatial_query.sh         # LLM spatial query service
├── publish_test_anchors.sh         # Test anchor publisher for AR overlay
├── spatial_query.py                # Spatial query ROS2 node (Ollama)
├── decisions/                      # Numbered design decision docs (001-007)
├── android/                        # Phone client app (Kotlin, Jetpack Compose)
├── client/
│   └── test.html                   # Browser WebSocket test client
└── hardware/
    └── bom.md
```

---

## How to run

### Hub

```bash
# Install dependencies (first time only, Ubuntu 22.04)
bash setup.sh

# Start the hub (RealSense + RTAB-Map + rosbridge WebSocket)
bash launch_hub.sh              # Resume mapping from existing database
bash launch_hub.sh --new-map    # Start a fresh map
bash launch_hub.sh --localize   # Lock map, localize only
bash launch_hub.sh --with-phone # Also start phone relocalization
bash launch_hub.sh --help       # Show all options
```

### Phone localizer (standalone)

```bash
# If the hub is already running and you want to add phone relocalization
bash launch_phone_localizer.sh
bash launch_phone_localizer.sh --help
```

### Spatial query service

```bash
# Start the LLM spatial query service (requires Ollama + hub running)
bash launch_spatial_query.sh

# Ask a question
ros2 topic pub /hub/query std_msgs/msg/String "{data: 'what do you know about this room?'}" --once

# Listen for responses
ros2 topic echo /hub/response
```

### Phone client app

Open `android/` in Android Studio. Build and run on a phone or emulator.

1. Enter the hub's IP address and port 9090
2. Tap **Connect** — status dot turns green
3. Toggle **IMU** and **Camera** to start streaming sensor data to the hub
4. Pose updates appear when the hub relocalizes the phone's camera

### Browser test client

Open `client/test.html` in a browser. Enter the hub address and click Connect.

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
