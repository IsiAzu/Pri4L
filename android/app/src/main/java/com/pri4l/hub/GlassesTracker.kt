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
class GlassesTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    @Volatile
    var isTracking = false
        private set

    init {
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
    }

    fun start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            isTracking = true
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    private val remappedMatrix = FloatArray(16)

    fun getViewMatrix(dest: FloatArray) {
        synchronized(rotationMatrix) {
            // Glasses are landscape with screen facing outward.
            // Use AXIS_Y, AXIS_MINUS_X: equivalent to 90° CW rotation of device,
            // which maps landscape-native sensor data to GL correctly.
            // head yaw → GL yaw, head pitch → GL pitch, head roll → GL roll
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
