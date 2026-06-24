package com.pri4l.glasses

/**
 * Common interface for 3DoF head-orientation sources.
 *
 *  - [GlassesTracker]    — Android Game Rotation Vector. KNOWN-BROKEN axis mapping on
 *                          the INMO (decision 011); kept only as a fallback.
 *  - [InmoFusionTracker] — INMO's native sensor fusion (`GyroRotation.vQuat`), which
 *                          bakes in the true IMU-to-eye mounting offset. Preferred.
 *
 * Use [HeadTrackerFactory.create] to obtain the best available source.
 */
interface HeadTracker {
    /** True once a valid orientation stream is being received. */
    val isTracking: Boolean

    /** Begin listening to the underlying sensor/fusion source. */
    fun start()

    /** Stop listening and release resources. */
    fun stop()

    /**
     * Writes a column-major 4x4 GL view matrix (world -> eye) into [dest].
     * Caller must pass a FloatArray of length 16.
     */
    fun getViewMatrix(dest: FloatArray)

    /**
     * Re-defines "forward" as the current head orientation. Content placed straight
     * ahead appears centered after this call. Optional; no-op if unsupported.
     */
    fun recenter() {}

    /** Short human-readable name of the active source, for logging/diagnostics. */
    val sourceName: String

    /** Toggle experimental magnetometer yaw-drift correction. Returns new on/off state. */
    fun toggleYawCorrection(): Boolean = false

    /** Flip the sign of the yaw-drift correction (for on-device tuning). */
    fun flipYawSign() {}

    /** HUD label for the yaw-correction state ("off", "on(+)", "on(-)", or "n/a"). */
    val yawCorrectionLabel: String get() = "n/a"
}
