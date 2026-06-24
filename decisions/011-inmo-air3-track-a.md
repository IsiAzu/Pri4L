# 011 — INMO Air3: Track A Glasses Platform

**Status:** 3DoF orientation RESOLVED on-device 2026-06-23 (IMA301, Android 14) via INMO native fusion (`GyroRotation.vQuat`). Required: a `com.unity3d.player.UnityPlayer` stub (the AAR reads `currentActivity`), `System.loadLibrary("inmoair3")` (the SDK normally loads it in `Air3Core`'s static init, which we bypass), and `tools:overrideLibrary` for the AAR's minSdk 34. `vQuat` order `[x,y,z,w]` correct as-is; tap-to-recenter handles content centering. Next: full `MainActivity` + hub test; VIO/6DoF still future. (Earlier: blocked on `remapCoordinateSystem` — structurally insufficient, see below.)
**Date:** 2026-05-31 (updated 2026-06-23)
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

#### Investigation 2026-06-01 — refined diagnosis

Re-read `GlassesTracker.getViewMatrix()` (`android/.../GlassesTracker.kt:46-60`). Two findings sharpen the root cause:

1. **The remaps are phone-derived and double-rotate.** `AXIS_X, AXIS_Z` (the earlier attempt) is the *standard "phone held vertically, back camera pointing forward"* remap — it rotates the frame ~90° about pitch so a vertically-held phone's screen-up becomes forward. **Glasses are already worn in the looking-forward orientation**, so the IMU frame is already ~aligned with the eye frame. Applying the phone-camera remap adds a spurious ~90° pitch rotation, which swaps the yaw and roll axes — exactly the observed "yaw → roll" symptom. The current `AXIS_Y, AXIS_MINUS_X` is a different guess at the same dead end.

2. **`remapCoordinateSystem` is structurally too weak.** It can only express the 24 axis-aligned 90° permutations (proper + improper). The true IMU→eye transform depends on how the IMU is physically soldered inside the INMO chassis — almost certainly **not** a clean 90° multiple. No single `remapCoordinateSystem` call can represent a non-axis-aligned mounting offset. This is the structural reason every axis combination fails on at least one axis, and it means the fix is **not** "find the right remap pair."

3. **No recentering / Earth-referenced frame.** `getRotationMatrixFromVector` yields a device→world matrix in Android ENU (X=East, Y=North, Z=Up). With no captured reference orientation, "forward" is magnetic north, not where the wearer is looking. Even with axes fixed, a "look straight ahead" reference capture is required for cubes to sit in front of the user. (Game Rotation Vector drops the magnetometer, so absolute yaw is unavailable anyway — relative tracking from a captured reference is the only correct model.)

**Implication:** Continuing to permute `remapCoordinateSystem` axes cannot succeed. The correct fix is either (a) consume INMO's pre-calibrated fusion quaternion (`GyroRotation.vQuat`), which already bakes in the true mounting offset, or (b) solve for a single constant correction quaternion `C` empirically (`view = C · Rᵀ`, with `C` from a Unity reference capture or a guided look-forward calibration). See refined plan below.

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

> **Priority after 2026-06-01 investigation:** Do **not** spend more time permuting `remapCoordinateSystem` axes — it cannot represent the true (non-axis-aligned) IMU mounting. Pursue Option A first; if the AAR can't be integrated quickly, Option B (empirical correction quaternion via a guided look-forward capture) is the fastest self-contained fallback. Option C (Unity) is the reference-of-last-resort for deriving the exact `C`.

**Option A — Use INMO's native fusion (preferred):**
1. Include `air3_core-debug.aar` in our project as a dependency
2. Instantiate `GyroRotation`, call `start()`
3. Read `GyroRotation.vQuat` (static field) each frame
4. Convert quaternion to view matrix — this quaternion already accounts for the glasses' physical orientation
5. Render cubes using this view matrix

**Why this should work:** INMO's fusion is calibrated for their hardware orientation. It outputs a quaternion that their ATW uses for reprojection — it's designed for exactly this use case.

**Scaffolding landed 2026-06-01 (compiles, AAR not yet present):**
- `HeadTracker` interface abstracts the orientation source; `GlassesTracker` (Game Rotation Vector, fallback) and `InmoFusionTracker` (INMO fusion) both implement it.
- `InmoFusionTracker` binds to `com.inmo.air3_core.atw.GyroRotation` **reflectively** so the project builds before the AAR is in-hand and the INMO path activates automatically once it is. Includes quaternion→view conversion + a recenter ("look forward") reference and an `axisFix` hook for eye-axis handedness.
- `HeadTrackerFactory.create()` selects INMO fusion when `air3_core` is on the classpath, else falls back. Wired into `MainActivity.startGlasses()` and `GlassesTestActivity`.
- AAR integration: drop `air3_core-*.aar` into `android/app/libs/` (gitignored); picked up via `fileTree` in `app/build.gradle.kts`. See `android/app/libs/README.md` for how to obtain it.
- **Remaining (needs hardware + the AAR):** obtain the AAR; confirm on-device the assumptions flagged in `InmoFusionTracker.kt` — vQuat component order, field type, `GyroRotation` lifecycle, and `axisFix`. Validate via `GlassesTestActivity` (logcat should report `head tracker source: INMO ...`).
- Build note: AGP requires JDK 17+; the only JDK on the bench machine is 11. Use Android Studio's bundled JBR — `JAVA_HOME=~/android-studio/jbr ./gradlew ...`.

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
