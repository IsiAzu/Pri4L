package com.unity3d.player;

import android.app.Activity;

/**
 * Minimal stand-in for Unity's UnityPlayer.
 *
 * The INMO {@code air3_core} AAR is compiled against the Unity runtime:
 * {@code GyroRotation.start()} reads {@code UnityPlayer.currentActivity} to obtain an
 * Activity for {@code getSystemService("sensor")}. The real Unity class is NOT bundled
 * in the AAR, so this native app supplies the one static field the AAR's bytecode
 * references via {@code getstatic com/unity3d/player/UnityPlayer.currentActivity}.
 *
 * Set {@link #currentActivity} before starting any INMO tracker (see
 * {@code InmoFusionTracker.start()}). See decision 011.
 */
public class UnityPlayer {
    /** Read by air3_core via {@code getstatic UnityPlayer.currentActivity}. */
    public static Activity currentActivity;
}
