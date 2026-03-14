package com.pri4l.hub

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Publishes phone IMU data to /phone/imu at ~50Hz.
 *
 * Coordinate frame note: Android uses x-right, y-up, z-out-of-screen.
 * ROS uses x-forward, y-left, z-up (ENU). No transform is applied here —
 * the hub side must handle coordinate conversion when consuming this data.
 */
class ImuSender(
    private val context: Context,
    private val client: RosbridgeClient
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var accel = FloatArray(3)
    private var gyro = FloatArray(3)
    private var running = false

    private var publishThread: HandlerThread? = null
    private var publishHandler: Handler? = null

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            accel = event.values.clone()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            gyro = event.values.clone()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (running) return
        running = true

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        publishThread = HandlerThread("imu-publish").also { it.start() }
        publishHandler = Handler(publishThread!!.looper)
        publishHandler?.post(publishRunnable)
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(gyroListener)
        publishHandler?.removeCallbacksAndMessages(null)
        publishThread?.quitSafely()
        publishThread = null
        publishHandler = null
    }

    private val publishRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            val now = System.currentTimeMillis()
            val sec = now / 1000
            val nanosec = (now % 1000) * 1_000_000

            val msg = JSONObject().apply {
                put("header", JSONObject().apply {
                    put("stamp", JSONObject().apply {
                        put("sec", sec)
                        put("nanosec", nanosec)
                    })
                    put("frame_id", "phone_imu")
                })
                put("linear_acceleration", JSONObject().apply {
                    put("x", accel[0].toDouble())
                    put("y", accel[1].toDouble())
                    put("z", accel[2].toDouble())
                })
                put("angular_velocity", JSONObject().apply {
                    put("x", gyro[0].toDouble())
                    put("y", gyro[1].toDouble())
                    put("z", gyro[2].toDouble())
                })
                put("orientation", JSONObject().apply {
                    put("x", 0.0)
                    put("y", 0.0)
                    put("z", 0.0)
                    put("w", 0.0)
                })
                // orientation_covariance[0] = -1 means orientation not available (ROS convention)
                put("orientation_covariance", JSONArray().apply {
                    put(-1.0); repeat(8) { put(0.0) }
                })
                put("angular_velocity_covariance", JSONArray().apply {
                    repeat(9) { put(0.0) }
                })
                put("linear_acceleration_covariance", JSONArray().apply {
                    repeat(9) { put(0.0) }
                })
            }

            client.publish("/phone/imu", "sensor_msgs/msg/Imu", msg)

            publishHandler?.postDelayed(this, 20) // ~50Hz
        }
    }
}
