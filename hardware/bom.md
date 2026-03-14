# Bill of Materials — Pri4L AR Spatial Hub

_Prices sourced March 2026. All USD._

## Confirmed Hardware (Ordered / In Hand)

| Component | Model | Role | Unit Price | Qty | Total | Source |
|-----------|-------|------|-----------|-----|-------|--------|
| Hub compute | GMKtec NucBox M6 Ultra (Ryzen 7640HS, 16GB DDR5, 1TB NVMe) | Hub brain | $329 | 1 | $329 | Amazon |
| Hub depth sensor | Intel RealSense D435i (depth + RGB + IMU) | Hub SLAM input | $334 | 1 | $334 | Intel RealSense Store |
| Glasses (thin client) | INMO Air3 (Snapdragon XR, 1080p Sony Micro-OLED, Android 14) | Display peripheral | $1,099 | 1 | $1,099 | inmoxr.com / Alibaba |

**Confirmed subtotal: $1,762**

---

## INMO Air3 Specifications

| Spec | Value |
|------|-------|
| OS | Android 14 (IMOS 3.0) |
| Processor | Qualcomm Snapdragon XR 8-core |
| RAM / Storage | 8GB / 128GB |
| Display | Sony Micro-OLED 1080p per eye, full-color waveguide |
| Brightness | 600 nits |
| FOV | 36° |
| Camera | 16MP, 120° ultra-wide, 1080p 30fps |
| Battery | 660mAh |
| Connectivity | WiFi, Bluetooth |
| SDK | Unity SDK (INMOXR/air3-unity-sdk) + standard Android APIs |

## Under Consideration — Phase 6 (Hub GPU Upgrade)

| Component | Model | Role | Est. Price | Notes |
|-----------|-------|------|-----------|-------|
| GPU | NVIDIA RTX 3060 12GB (used) | LLM inference, YOLO object detection | ~$180 | 12GB VRAM, runs 8B-13B models. Recommended pick |
| GPU (alt) | NVIDIA RTX 3090 24GB (used) | Future-proof: 30B+ models | ~$700 | 350W TDP, needs serious cooling. Overkill for prototype |
| GPU (alt) | NVIDIA RTX 4060 8GB | Efficient, newer arch | ~$300 | Only 8GB VRAM — limits model size |
| GPU (alt) | NVIDIA RTX 4070 12GB | Best perf/VRAM balance | ~$550 | If buying new |

**Note:** GMKtec M6 Ultra lacks Thunderbolt/USB4 output — eGPU not viable. GPU upgrade requires tower build (see below).

### Prototype Tower Build (replaces GMKtec as hub)

| Component | Model | Est. Price | Notes |
|-----------|-------|-----------|-------|
| GPU | RTX 3060 12GB (used) | ~$180 | See above |
| CPU | AMD Ryzen 5 5600 or 7600 | ~$100-150 | 6C/12T, plenty for ROS2 + RTAB-Map |
| Motherboard | B550 or B650 mATX | ~$80-120 | Standard PCIe x16 for GPU |
| RAM | 32GB DDR4 or DDR5 | ~$60-80 | Double current — fits all workloads simultaneously |
| PSU | 550W 80+ Bronze | ~$50 | Headroom for GPU + all peripherals |
| Case | Compact mATX tower | ~$40-60 | "Room tower" form factor |
| Storage | 1TB NVMe | ~$0-60 | Reuse from GMKtec or buy new |

**Estimated total: $510-700.** GMKtec repurposed as dev/test hub or extension anchor node controller.

---

## Under Consideration — Phase 1 Extension

| Component | Model | Role | Est. Price | Notes |
|-----------|-------|------|-----------|-------|
| 360 LiDAR | Livox Mid-360 | Room-scale SLAM coverage | ~$800 | Preferred — better ROS2 support |
| 360 LiDAR (alt) | Slamtec RPLIDAR A3 | Room-scale SLAM coverage | ~$380 | Budget option, 2D scan plane only |

---

## Under Consideration — Phase 3 (Glasses-Side Depth)

| Component | Model | Role | Est. Price | Notes |
|-----------|-------|------|-----------|-------|
| ToF sensor | ST VL53L5CX (8x8 multizone) | Near-field depth on glasses | ~$10-20 | Low power, low weight |
| Dev reference | Luxonis OAK-D Lite | Stereo depth dev reference | ~$149 | Not for final glasses; bench eval only |

---

## Under Consideration — Phase 5 (Networking)

| Component | Model | Role | Est. Price | Notes |
|-----------|-------|------|-----------|-------|
| WiFi 6E AP | TP-Link Deco XE75 or equivalent | Dedicated local network | ~$150-200 | Only if consumer router proves unreliable |

---

## Software Stack (No Cost)

| Package | Version | Role |
|---------|---------|------|
| Ubuntu | 22.04 LTS | Hub OS |
| Intel RealSense SDK | 2.0 | Sensor interface |
| ROS2 | Humble | Middleware |
| RTAB-Map | Latest | SLAM + persistent mapping |
| Monado | Latest | OpenXR runtime reference |
| Ollama (llama3.2:3b) | Latest | Local LLM inference |
| YOLO v8 | Latest | Object detection (planned) |

---

## Total Committed Spend

| Category | Amount |
|----------|--------|
| Confirmed hardware | $1,762 |
| Software | $0 |
| **Total** | **$1,762** |
