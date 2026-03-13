# 003 — Phone over Pi as fallback client

**Status:** decided  
**Date:** 2025-03

## Decision

Track B (fallback client) uses the phone directly. Raspberry Pi dropped from the architecture.

## Options considered

1. **Raspberry Pi + IMU + Pi Camera** — dedicated client device
2. **Raspberry Pi + phone as display** — Pi for sensing, phone for rendering
3. **Phone only** — camera, IMU, WiFi, ARCore all onboard. Chosen.

## Reasoning

The phone has everything Track B needs: camera, IMU, WiFi, and sufficient compute to run an RTSP/WebRTC streaming app and ARCore overlay. Adding a Pi introduces another device, another order, and complexity with no architectural benefit.

The phone-as-client also directly validates the personal bridge component of the architecture rather than treating it as a future concern. A phone receiving hub anchor positions and rendering them via ARCore is a legible, shippable demo.

## Consequences

- Track B requires zero additional hardware orders
- Phone-as-client demo is available before glasses hardware arrives
- Pi remains a candidate only if a fixed secondary sensing node is needed for room-scale coverage (Phase 1 extension), not for client functionality
