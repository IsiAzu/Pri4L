package com.pri4l.hub

/**
 * Common interface for 3DoF head-orientation sources.
 *
 * Two implementations exist:
 *  - [GlassesTracker]      — Android Game Rotation Vector. KNOWN-BROKEN axis mapping
 *                            on the INMO (see decision 011); kept as fallback.
 *  - [InmoFusionTracker]   — INMO's native sensor fusion (`GyroRotation.vQuat`), which
 *                            bakes in the true IMU-to-eye mounting offset. Preferred.
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
     * Re-defines "forward" as the current head orientation. Anchors placed straight
     * ahead will appear centered after this call. Optional; no-op if unsupported.
     */
    fun recenter() {}

    /** Short human-readable name of the active source, for logging/diagnostics. */
    val sourceName: String
}
