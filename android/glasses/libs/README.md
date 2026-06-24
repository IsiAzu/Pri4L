# Local AAR drop-in — INMO Air3 native fusion (glasses module)

Holds the local `air3_core` `.aar` (not committed; see `.gitignore`). The build picks
up any `*.aar` here via the `fileTree(... "libs" ...)` dependency in `build.gradle.kts`.

## What to drop here

`air3_core-debug.aar` from the INMO Air3 Unity SDK
(<https://github.com/INMOXR/air3-unity-sdk>, `Assets/Plugins/Android/`). It provides
`com.inmo.air3_core.atw.GyroRotation` (whose static `vQuat` is the pre-calibrated
head-orientation quaternion) and bundles `libinmoair3.so`.

## Integration notes (validated 2026-06-23 on IMA301, Android 14)

The AAR is compiled against the Unity runtime. Running it in this native app needs:

1. A `com.unity3d.player.UnityPlayer` stub (in `src/main/java/com/unity3d/player/`) —
   `GyroRotation.start()` reads `UnityPlayer.currentActivity` for
   `getSystemService("sensor")`. `InmoFusionTracker` sets it before `start()`.
2. `System.loadLibrary("inmoair3")` before `start()` — the native fusion symbol
   (`AtwCore.nativeGyroFusion`) lives in `libinmoair3.so`, normally loaded by
   `Air3Core`'s static initializer (which we bypass). `InmoFusionTracker` does this.

`vQuat` order `[x,y,z,w]` is correct as-is. See `decisions/011-inmo-air3-track-a.md`.
