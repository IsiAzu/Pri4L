package com.pri4l.hub

import android.app.Activity
import android.content.Context
import android.util.Log

/**
 * Selects the best available [HeadTracker].
 *
 * Preference order:
 *   1. [InmoFusionTracker]  — INMO native fusion (correct axes; see decision 011).
 *   2. [GlassesTracker]     — Android Game Rotation Vector (WIP/broken axes) fallback.
 *
 * The INMO path only wins once the `air3_core` AAR is present (see
 * `android/app/libs/README.md`); otherwise we transparently fall back.
 */
object HeadTrackerFactory {
    private const val TAG = "Pri4L"

    fun create(activity: Activity): HeadTracker {
        if (InmoFusionTracker.isAvailable()) {
            Log.w(TAG, "HeadTracker: using INMO native fusion")
            return InmoFusionTracker(activity)
        }
        Log.w(TAG, "HeadTracker: INMO AAR absent, falling back to Game Rotation Vector")
        return GlassesTracker(activity)
    }

    /** True if any head-tracking source is usable on this device. */
    fun isAvailable(context: Context): Boolean =
        InmoFusionTracker.isAvailable() || GlassesTracker.isAvailable(context)
}
