package com.pri4l.glasses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix

/**
 * Head tracker using Game Rotation Vector (3DoF). Fallback only.
 *
 * KNOWN-BROKEN on the INMO: head yaw shows up as roll. `remapCoordinateSystem` cannot
 * represent the non-axis-aligned IMU-to-eye mounting offset of the INMO chassis (decision
 * 011), so this never tracks correctly there — it exists only so the app still runs if the
 * air3_core AAR is absent. Prefer [InmoFusionTracker].
 */
class GlassesTracker(context: Context) : SensorEventListener, HeadTracker {

    override val sourceName: String = "Android Game Rotation Vector (WIP/broken axes)"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(16)
    private val remappedMatrix = FloatArray(16)

    @Volatile
    override var isTracking = false
        private set

    init {
        Matrix.setIdentityM(rotationMatrix, 0)
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

    override fun getViewMatrix(dest: FloatArray) {
        synchronized(rotationMatrix) {
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedMatrix
            )
            Matrix.transposeM(dest, 0, remappedMatrix, 0)
        }
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
