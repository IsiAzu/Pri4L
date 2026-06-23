# Local AAR drop-in — INMO Air3 native fusion

This directory holds local `.aar` dependencies that are **not** committed to git
(see `.gitignore`). The build picks up any `*.aar` here automatically via the
`fileTree(... "libs" ...)` dependency in `app/build.gradle.kts`.

## What to drop here

`air3_core-debug.aar` (or `air3_core-release.aar`) from the INMO Air3 SDK. This is
the library that provides `com.inmo.air3_core.atw.GyroRotation`, whose `vQuat` field
is the pre-calibrated head-orientation quaternion we consume in
[`InmoFusionTracker.kt`](../src/main/java/com/pri4l/hub/InmoFusionTracker.kt).

### How to obtain it

1. INMO Air3 Unity SDK: <https://github.com/INMOXR/air3-unity-sdk>
   The `air3_core` AAR ships inside the Unity package
   (`Assets/Plugins/Android/` or the `.unitypackage`). Extract it.
2. Alternatively pull it off-device from an installed INMO app:
   `adb shell pm path <inmo.package>` → `adb pull` the APK → unzip and look for the
   bundled native libs / classes. (The AAR repackages `libinmoair3.so` + the
   `com.inmo.air3_core.*` classes.)
3. Community wiki for pointers: <https://github.com/sam1am/inmo_air_3_wiki>

## After dropping it in

- Rebuild. `InmoFusionTracker.isAvailable()` flips to `true` and
  `HeadTrackerFactory` selects the INMO path automatically.
- Launch `GlassesTestActivity` (standalone cubes) and check logcat for:
  `head tracker source: INMO GyroRotation.vQuat (native fusion)`.
- Verify on-device and adjust the assumptions flagged in `InmoFusionTracker.kt`:
  - vQuat component order ([x,y,z,w] vs [w,x,y,z]) — `copyQuat()`
  - field type (float[] vs Quaternion object) — `readQuat()`
  - lifecycle (getInstance / constructor / start signature) — `tryGetInstance` / `tryInvokeStart`
  - eye-axis handedness — `axisFix`

If the glasses still spin wrong after the quaternion reads correctly, fix `axisFix`
(a constant eye-frame rotation) rather than re-introducing `remapCoordinateSystem`
on the raw sensor path — that path cannot represent the mounting offset (decision 011).
