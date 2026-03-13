# 002 — Hybrid render split

**Status:** decided  
**Date:** 2025-03

## Decision

Adopted a hybrid rendering architecture. Hub handles scene composition and base frame rendering. Glasses handle head tracking, timewarp, and final display output.

## Options considered

1. **Stream world model only** — glasses render locally. Low bandwidth, higher glasses compute. Glasses need a full render pipeline.
2. **Stream rendered frames** — glasses are a pure display. Requires sub-5ms round trip. Not reliably achievable on local WiFi.
3. **Hybrid split** — hub composes scene, glasses do timewarp and reprojection. Chosen.

## Reasoning

The 20ms motion-to-photon constraint is non-negotiable. Any latency in the head pose → display path causes nausea. This rules out streaming fully rendered frames from the hub.

Streaming the world model only requires the glasses to run a full render pipeline, which adds weight and compute — contrary to the core thesis.

The hybrid split respects the latency constraint while keeping the glasses thin. Hub renders at ~30-60fps. Glasses apply timewarp at the last millisecond using fresh IMU data to correct for head movement since the last frame was composed.

The INMO Air3 (Snapdragon XR, Android 14) has sufficient onboard compute to run timewarp properly, making this viable for Phase 2.

## Consequences

- Head tracking and IMU must stay on glasses — never offloaded
- Frame transport protocol decision deferred to Phase 2 build (MJPEG vs H.264/RTP vs WebRTC)
- Need to verify INMO Air3 exposes IMU via standard Android SensorManager
