package com.pri4l.hub

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix

/**
 * Head tracker using Game Rotation Vector (3DoF).
 * Provides a view matrix suitable for rendering anchors relative to head orientation.
 * No positional tracking — anchors will rotate correctly but no parallax.
 */
class GlassesTracker(context: Context) : SensorEventListener, HeadTracker {

    override val sourceName: String = "Android Game Rotation Vector (WIP/broken axes)"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    @Volatile
    override var isTracking = false
        private set

    init {
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
    }

    override fun start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            isTracking = true
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    private val remappedMatrix = FloatArray(16)

    override fun getViewMatrix(dest: FloatArray) {
        synchronized(rotationMatrix) {
            // WIP / KNOWN-BROKEN: head yaw currently shows up as roll. See decision 011.
            // remapCoordinateSystem can only express axis-aligned 90° permutations and
            // cannot represent the real (non-axis-aligned) IMU-to-eye mounting offset of
            // the INMO chassis, so NO axis pair fixes all three of yaw/pitch/roll.
            // Do not keep permuting these axes — replace this path with INMO's calibrated
            // fusion quaternion (GyroRotation.vQuat) or an empirically-solved correction
            // quaternion C applied as view = C · Rᵀ. This call is a placeholder.
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedMatrix
            )
            Matrix.transposeM(dest, 0, remappedMatrix, 0)
        }
    }

    /**
     * Returns head orientation as quaternion [x, y, z, w].
     */
    fun getOrientation(): FloatArray {
        val quat = FloatArray(4)
        synchronized(rotationMatrix) {
            SensorManager.getQuaternionFromVector(quat, floatArrayOf(
                rotationMatrix[0], rotationMatrix[1], rotationMatrix[2]
            ))
        }
        return quat
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            synchronized(rotationMatrix) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun isAvailable(context: Context): Boolean {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
        }
    }
}
