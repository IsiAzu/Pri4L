# 007 — Glasses OS: Android fork (Level 2)

**Status:** decided
**Date:** 2026-03

## Decision

The glasses run a stripped-down Android fork (AOSP Level 2), not a minimal Linux or RTOS. The glasses operate in two modes: hub mode (full spatial AR, thin-client rendering) and mobile mode (standalone HUD, phone-bridged apps).

## Options considered

1. **Minimal Linux / custom compositor (Level 3)** — Lowest latency, tightest display pipeline control. No app runtime. Glasses are pure display peripheral.
2. **RTOS / bare metal (Level 4)** — Absolute minimum overhead. No OS services. Display loop only.
3. **Android fork (Level 2)** — Strip AOSP to essentials, add custom spatial services. Retains app runtime and standard sensor APIs. Chosen.

## Reasoning

The glasses need to function independently when away from the hub. Mobile mode requires:

- HUD elements: notifications, time, navigation
- Body-relative UI (head-locked or chest-locked panels, no world anchoring)
- Phone bridge: mirror or extend select phone apps via BLE/WiFi Direct
- Standard Android sensor APIs for IMU, camera, eye tracking

These require a real app runtime. Level 3/4 cannot provide this without rebuilding an entire application framework from scratch, which is more work than stripping Android down.

Level 2 also means:
- Chips that ship with Android BSPs (Snapdragon AR2/XR, Rockchip) work without fighting the platform
- Third-party app compatibility is possible if needed later
- CameraX, SensorManager, BLE APIs work out of the box

The overhead cost of Android is real (higher power draw, GC pauses, compositor latency) but acceptable because:
- Hub mode offloads all heavy compute — Android idles except for reprojection
- Mobile mode is lightweight HUD, not full spatial AR — less latency-sensitive
- Battery budget is larger than a microcontroller design would allow anyway, since the chip must be Android-capable

## Dual-mode architecture

| | Hub mode | Mobile mode |
|---|---|---|
| **Active when** | Hub reachable on local network | No hub connection |
| **Rendering** | Receives composed frames from hub, local timewarp/reprojection | Body-relative HUD, no world anchoring |
| **Compute source** | Hub (heavy), glasses (reprojection only) | Phone (bridge), glasses (HUD rendering) |
| **Spatial awareness** | Full — hub provides world model | None — no SLAM, no map |
| **Phone role** | Authentication, personal context | Primary compute bridge, notification source |

## Consequences

- Glasses chip must be Android-capable — eliminates microcontroller candidates (Nordic nRF, Ambiq, ESP32)
- Snapdragon AR2/XR series or Rockchip become the practical chip options
- Phone bridge scope expands: in mobile mode, phone is the compute source, not just authentication
- Need to define the Android fork surface area — what system services to keep vs strip
- Timewarp/reprojection must bypass Android's standard compositor (SurfaceFlinger) to meet 20ms budget in hub mode
