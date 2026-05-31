# 011 — INMO Air3: Track A Glasses Platform

**Status:** In Progress — sensor orientation blocked
**Date:** 2026-05-31
**Context:** Track A requires AR glasses that can connect to the hub as a thin client. INMO Air3 arrived — this doc captures platform capabilities, integration progress, and current blockers.

---

## Hardware Confirmed

| Spec | Value |
|------|-------|
| Model | IMA301 (INMO Air3) |
| OS | Android 14 (full, Google Play Store) |
| SoC | Qualcomm SM6450P (Snapdragon 6 Gen 1), codename "parrot" |
| Build | Air3_DU_V3.11.027_202601230319 |
| Display | 1920x1080, 240dpi, full-color waveguide |
| IMU | ICM4x6xx (TDK-InvenSense) accel + gyro, AK0991x magnetometer |
| Sensors | Game Rotation Vector, Rotation Vector, Gravity, Linear Accel (all QTI fused) |
| Camera | Back-facing (forward on glasses), Camera2 API, intrinsic calibration available |
| Tracking | 6DoF VIO via `libinmoair3.so` + `ArPoseCore`, 3DoF via `GyroRotation` + ATW |
| Connectivity | WiFi, Bluetooth |
| Developer access | ADB works, sideloading works, developer mode documented |

---

## What Works (Confirmed 2026-05-31)

- APK sideloading via ADB
- GLSurfaceView renders fullscreen to waveguide (1920x1080 landscape)
- OpenGL ES 2.0 cubes render correctly (Adreno GPU)
- `GlassesTestActivity` — standalone test app, no Compose, no ARCore
- WebSocket connection to hub (rosbridge on port 9090)
- Anchor manager publishes to glasses correctly
- Standard Android sensor APIs accessible (accel, gyro, game rotation vector)

## Current Blockers

### 1. Sensor Coordinate System Mismatch (Critical)

**Problem:** Android's `Game Rotation Vector` sensor uses a coordinate frame designed for phone form factor. The INMO glasses have a landscape-native orientation worn on the face. Multiple `SensorManager.remapCoordinateSystem()` combinations have been tried — none correctly map all three axes (yaw, pitch, roll) simultaneously.

**What we've established:**
- `AXIS_X, AXIS_Z` → pitch (up/down) correct, cube positions correct after -90°Z view fix, but **yaw maps to roll** (head left/right causes cubes to rotate around forward axis instead of sliding horizontally)
- Other combinations either lose visibility entirely or break pitch
- INMO's own SDK uses **raw gyro/accel/mag fusion** in native code (`libinmoair3.so` → `nativeGyroFusion()`) which outputs a quaternion (`GyroRotation.vQuat`) — this bypasses Android's sensor fusion entirely

**Root cause:** The Game Rotation Vector's reference frame assumes the device's "natural" orientation is portrait (phone). The INMO is landscape-native AND worn perpendicular to a phone's typical orientation. The sensor fusion already accounts for gravity, but the mapping from device axes to GL axes has a rotational ambiguity that `remapCoordinateSystem` can't resolve with a single call.

### 2. VIO/6DoF Not Yet Accessed

**Problem:** The INMO has full VIO (Visual Inertial Odometry) via `com.inmo.air3_core.vio.ArPoseCore` and `com.arglasses.arservice.IServiceInterface`, but this service only starts when their Unity SDK app runs. We haven't tried binding to it from our app.

**Impact:** Without 6DoF, we only get rotation (3DoF) — anchors rotate correctly with head movement but don't show parallax when walking. This is acceptable for initial validation but needed for final product.

---

## INMO SDK Architecture (from decompilation)

```
com.inmo.air3_core/
├── Air3Core                    — Main SDK entry point
├── atw/
│   ├── AtwCore                 — Asynchronous TimeWarp (reprojection)
│   │   └── nativeGyroFusion()  — Fuses accel/mag/gyro → quaternion
│   └── GyroRotation            — Raw sensor listener, outputs vQuat
├── vio/
│   ├── ArPoseCore              — VIO/6DoF tracking
│   ├── ArServiceUtil           — Binds to com.arglasses.arservice
│   └── RGBCameraUtil           — Camera frame access for VIO
└── bluetooth/
    └── RingPoseCore            — INMO Ring input
```

Native library: `libinmoair3.so` (4.2MB, arm64) — contains the actual SLAM/VIO/sensor fusion algorithms.

---

## Plan: Resolve Orientation & Enable 6DoF

### Phase 1: Fix 3DoF Orientation (immediate next session)

**Option A — Use INMO's native fusion (preferred):**
1. Include `air3_core-debug.aar` in our project as a dependency
2. Instantiate `GyroRotation`, call `start()`
3. Read `GyroRotation.vQuat` (static field) each frame
4. Convert quaternion to view matrix — this quaternion already accounts for the glasses' physical orientation
5. Render cubes using this view matrix

**Why this should work:** INMO's fusion is calibrated for their hardware orientation. It outputs a quaternion that their ATW uses for reprojection — it's designed for exactly this use case.

**Option B — Manual calibration approach:**
1. On app launch, render a fixed crosshair and ask user to look straight ahead
2. Capture the raw rotation matrix at that moment as "reference forward"
3. Compute all subsequent frames as relative rotation from reference
4. Apply a manual axis swap matrix determined empirically

**Option C — Unity SDK as reference implementation:**
1. Build minimal Unity project with INMO SDK
2. Log `Camera.main.transform.localRotation` quaternion each frame
3. Simultaneously log raw `Game Rotation Vector` from Android
4. Compare the two — derive the exact correction matrix needed

### Phase 2: Enable 6DoF VIO

1. Bind to `com.arglasses.arservice.IServiceInterface` from our app
2. If service isn't running, check if `ArPoseCore` can start it standalone
3. Alternatively: include their AAR and call `ArPoseCore` directly
4. Extract position + rotation per frame
5. Use as full view matrix (replaces sensor-only path)

### Phase 3: Connect to Hub

Once head tracking works correctly:
1. The WebSocket + anchor subscription code already works on glasses
2. Replace test cubes with hub anchors (already implemented in `GlassesRenderer`)
3. Alignment: use "stand at D435 and align" flow (same as phone), or implement fiducial-based alignment

---

## Decision: Unity vs Native Android

| Factor | Native Android (current) | Unity SDK |
|--------|--------------------------|-----------|
| Iteration speed | Fast (2s builds) | Slow (minutes) |
| Head tracking | Blocked on axis mapping | Works out of box via SDK |
| 6DoF VIO | Need to bind to service | Included in SDK |
| Hub integration | Already done (WebSocket) | Need to rebuild |
| Display rendering | Confirmed working | Confirmed working |
| Long-term fit | Matches phone app architecture | Separate codebase |

**Recommendation:** Try Option A (use INMO's AAR for sensor fusion) first. If that fails within one session, pivot to Unity SDK as reference to extract the correct coordinate mapping, then apply it back to the native app.

---

## References

- [INMO Air3 Unity SDK](https://github.com/INMOXR/air3-unity-sdk)
- [INMO Air3 Documentation](https://support.inmoxr.com/air3/)
- [Developer Mode Setup](https://support.inmoxr.com/air3/development/unlock-developer-mode/)
- [Unofficial Community Wiki](https://github.com/sam1am/inmo_air_3_wiki)
- [INMO Air3 Specs](https://www.inmoxr.com/pages/inmo-air3-specs)
- [Android Sensor Coordinate Remapping](https://developer.android.com/guide/topics/sensors/sensors_position.html)
