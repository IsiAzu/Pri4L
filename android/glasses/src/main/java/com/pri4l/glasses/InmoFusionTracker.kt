package com.pri4l.glasses

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.util.Log
import com.unity3d.player.UnityPlayer

/**
 * Head tracker backed by INMO's native sensor fusion (`com.inmo.air3_core.atw.GyroRotation`).
 *
 * The vendor quaternion (`GyroRotation.vQuat`) already accounts for the glasses' physical
 * IMU mounting — the offset `remapCoordinateSystem` could not represent (decision 011).
 * Validated 2026-06-23 on IMA301 (Android 14): yaw/pitch/roll all track correctly,
 * vQuat order [x,y,z,w], axisFix identity.
 *
 * Bound reflectively so the module still builds if the AAR is absent.
 *
 * REQUIRES AN ACTIVITY: `GyroRotation.start()` reads `UnityPlayer.currentActivity` (not any
 * passed-in Context) for `getSystemService("sensor")`. We set it from [activity] in [start]
 * via our [UnityPlayer] stub, and `System.loadLibrary("inmoair3")` so the native fusion
 * symbol resolves (normally loaded by Air3Core's static init, which we bypass).
 */
class InmoFusionTracker(private val activity: Activity) : HeadTracker {

    override val sourceName: String = "INMO GyroRotation.vQuat (native fusion)"

    @Volatile
    override var isTracking = false
        private set

    private var gyroInstance: Any? = null
    private var quatField: java.lang.reflect.Field? = null

    private val quat = FloatArray(4)            // working [x, y, z, w]
    private val rot = FloatArray(16)            // head -> world
    private val refRot = FloatArray(16)         // head -> world at recenter
    private val tmp = FloatArray(16)
    @Volatile private var haveRef = false
    @Volatile private var recenterRequested = true   // auto-recenter on first frame

    /** Eye-frame correction `view = axisFix * viewRaw`. Identity (validated correct). */
    private val axisFix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // --- magnetometer yaw-drift correction -------------------------------------------
    // INMO's GyroRotation is a 6-axis fusion (accel+gyro, no magnetometer — confirmed via
    // sensorservice), so yaw drifts ~0.4°/s with no heading reference. We read the
    // magnetometer ourselves and slowly pull the rendered yaw toward magnetic heading.
    // Pitch/roll are gravity-stabilized by the fusion and left untouched.
    //
    // Tunables (expect on-device iteration): [DRIFT_ALPHA] slow-LP rate on the fusion-vs-mag
    // yaw difference; [CORRECTION_SIGN] flip if drift is corrected the wrong way; [UP_AXIS]
    // the world up-axis to rotate about (assume Z-up/ENU; flip to Y if wrong).
    private val sensorManager =
        activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelData = FloatArray(3)
    private val magData = FloatArray(3)
    @Volatile private var haveAccel = false
    @Volatile private var haveMag = false
    private val rMag = FloatArray(9)
    private val orient = FloatArray(3)
    @Volatile private var magYaw = 0f
    @Volatile private var haveMagYaw = false

    private val r9 = FloatArray(9)
    private var driftLP = 0f
    private var haveDrift = false
    private var driftRef = 0f
    private val rCorr = FloatArray(16)
    private val refCorrected = FloatArray(16)
    private var logThrottle = 0

    // Yaw correction is OFF by default — on-device it needs the right sign/up-axis, and the
    // desk is magnetically noisy. Toggle/flip at runtime to tune (see toggleYawCorrection /
    // flipYawSign). When off, behavior is the validated 6-axis fusion (slow drift + recenter).
    @Volatile var yawCorrectionOn = false
        private set
    @Volatile private var corrSign = 1f

    private val magListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { System.arraycopy(e.values, 0, accelData, 0, 3); haveAccel = true }
                Sensor.TYPE_MAGNETIC_FIELD -> { System.arraycopy(e.values, 0, magData, 0, 3); haveMag = true }
            }
            if (haveAccel && haveMag && SensorManager.getRotationMatrix(rMag, null, accelData, magData)) {
                SensorManager.getOrientation(rMag, orient)
                magYaw = orient[0]   // azimuth (radians), absolute magnetic heading
                haveMagYaw = true
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun start() {
        try {
            // GyroRotation.start() reads UnityPlayer.currentActivity for getSystemService("sensor").
            UnityPlayer.currentActivity = activity

            // Native fusion symbol (AtwCore.nativeGyroFusion) lives in libinmoair3.so,
            // normally loaded by Air3Core's static init which we bypass. Load it or the
            // first sensor callback aborts with UnsatisfiedLinkError. Idempotent.
            try {
                System.loadLibrary(NATIVE_LIB)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load lib$NATIVE_LIB.so; INMO native fusion unavailable", e)
                isTracking = false
                return
            }

            val cls = Class.forName(GYRO_CLASS)
            gyroInstance = tryGetInstance(cls)

            quatField = cls.declaredFields.firstOrNull { it.name == "vQuat" }
                ?.apply { isAccessible = true }
                ?: cls.fields.firstOrNull { it.name == "vQuat" }?.apply { isAccessible = true }

            tryInvokeStart(cls, gyroInstance)

            if (quatField == null) {
                Log.e(TAG, "GyroRotation found but no vQuat field; cannot track")
                isTracking = false
                return
            }
            // Our own magnetometer + accel feed for yaw-drift correction (INMO's fusion omits mag).
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(magListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(magListener, it, SensorManager.SENSOR_DELAY_UI)
            }

            isTracking = true
            Log.w(TAG, "INMO fusion tracker started ($sourceName)")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "INMO air3_core AAR not present; INMO fusion unavailable")
            isTracking = false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start INMO fusion tracker", e)
            isTracking = false
        }
    }

    override fun stop() {
        isTracking = false
        sensorManager.unregisterListener(magListener)
        val inst = gyroInstance ?: return
        try {
            inst.javaClass.methods.firstOrNull { it.name == "stop" && it.parameterTypes.isEmpty() }
                ?.invoke(inst)
        } catch (e: Throwable) {
            Log.w(TAG, "stop() on GyroRotation failed (non-fatal)", e)
        }
    }

    override fun recenter() {
        recenterRequested = true
    }

    override fun toggleYawCorrection(): Boolean {
        yawCorrectionOn = !yawCorrectionOn
        Log.w(TAG, "yaw correction ${if (yawCorrectionOn) "ON sign=$corrSign" else "OFF"}")
        return yawCorrectionOn
    }

    override fun flipYawSign() {
        corrSign = -corrSign
        Log.w(TAG, "yaw correction sign flipped to $corrSign")
    }

    override val yawCorrectionLabel: String
        get() = if (!yawCorrectionOn) "off" else "on(${if (corrSign > 0) "+" else "-"})"

    override fun getViewMatrix(dest: FloatArray) {
        if (!isTracking || !readQuat()) {
            Matrix.setIdentityM(dest, 0)
            return
        }

        quatToMatrix(quat, rot)   // rot = head -> world

        // Fusion azimuth from rot (column-major head->world) via the same getOrientation
        // convention the magnetometer uses, so their difference isolates the yaw drift.
        r9[0] = rot[0]; r9[1] = rot[4]; r9[2] = rot[8]
        r9[3] = rot[1]; r9[4] = rot[5]; r9[5] = rot[9]
        r9[6] = rot[2]; r9[7] = rot[6]; r9[8] = rot[10]
        SensorManager.getOrientation(r9, orient)
        val fusYaw = orient[0]

        if (haveMagYaw) {
            val d = wrap(fusYaw - magYaw)               // offset + accumulated drift
            if (!haveDrift) { driftLP = d; haveDrift = true }
            else driftLP += DRIFT_ALPHA * wrap(d - driftLP)
        }

        if (recenterRequested || !haveRef) {
            System.arraycopy(rot, 0, refRot, 0, 16)
            driftRef = driftLP                          // freeze the constant offset at recenter
            haveRef = true
            recenterRequested = false
        }

        // Pre-rotate the reference about world-up by the drift accrued since recenter, so a
        // stationary head holds a fixed magnetic heading instead of drifting. OFF by default.
        val corrDeg = if (yawCorrectionOn && haveMagYaw)
            Math.toDegrees((corrSign * wrap(driftLP - driftRef)).toDouble()).toFloat() else 0f
        Matrix.setRotateM(rCorr, 0, corrDeg, 0f, 0f, 1f) // Z = world up (ENU); flip to (0,1,0) if wrong
        Matrix.multiplyMM(refCorrected, 0, rCorr, 0, refRot, 0)

        // view = axisFix * (rot^T * refCorrected) — relative to the recenter orientation.
        Matrix.transposeM(tmp, 0, rot, 0)
        Matrix.multiplyMM(dest, 0, tmp, 0, refCorrected, 0)
        Matrix.multiplyMM(tmp, 0, axisFix, 0, dest, 0)
        System.arraycopy(tmp, 0, dest, 0, 16)

        if (++logThrottle % 120 == 0 && haveMagYaw) {
            Log.w(TAG, "yaw fus=%.0f mag=%.0f driftLP=%.0f corr=%.0f".format(
                Math.toDegrees(fusYaw.toDouble()), Math.toDegrees(magYaw.toDouble()),
                Math.toDegrees(driftLP.toDouble()), corrDeg))
        }
    }

    /** Wrap an angle (radians) to (-pi, pi]. */
    private fun wrap(a: Float): Float {
        var x = a
        val twoPi = (2.0 * Math.PI).toFloat()
        while (x > Math.PI) x -= twoPi
        while (x < -Math.PI) x += twoPi
        return x
    }

    // --- reflection helpers --------------------------------------------------

    private fun tryGetInstance(cls: Class<*>): Any? {
        cls.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }
            ?.let { return it.invoke(null) }
        runCatching { cls.getConstructor(Context::class.java).newInstance(activity) }
            .getOrNull()?.let { return it }
        runCatching { cls.getConstructor().newInstance() }.getOrNull()?.let { return it }
        return null
    }

    private fun tryInvokeStart(cls: Class<*>, instance: Any?) {
        cls.methods.firstOrNull { it.name == "start" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Context::class.java }
            ?.let { it.invoke(instance, activity); return }
        cls.methods.firstOrNull { it.name == "start" && it.parameterTypes.isEmpty() }
            ?.let { it.invoke(instance); return }
        Log.w(TAG, "No start() method on GyroRotation; assuming auto-started")
    }

    /** Reads vQuat into [quat] as [x,y,z,w]. Returns false if unreadable. */
    private fun readQuat(): Boolean {
        val f = quatField ?: return false
        return try {
            when (val v = f.get(gyroInstance)) {
                is FloatArray -> if (v.size >= 4) { copyQuat(v[0], v[1], v[2], v[3]); true } else false
                is DoubleArray -> if (v.size >= 4) {
                    copyQuat(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat()); true
                } else false
                null -> false
                else -> readQuatObject(v)
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun readQuatObject(obj: Any): Boolean {
        fun comp(name: String): Float? {
            obj.javaClass.fields.firstOrNull { it.name == name }?.let {
                return (it.get(obj) as? Number)?.toFloat()
            }
            val getter = "get" + name.uppercase()
            obj.javaClass.methods.firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
                ?.let { return (it.invoke(obj) as? Number)?.toFloat() }
            return null
        }
        val x = comp("x") ?: return false
        val y = comp("y") ?: return false
        val z = comp("z") ?: return false
        val w = comp("w") ?: return false
        copyQuat(x, y, z, w)
        return true
    }

    /** Vendor order is [x,y,z,w] (validated). */
    private fun copyQuat(a: Float, b: Float, c: Float, d: Float) {
        quat[0] = a; quat[1] = b; quat[2] = c; quat[3] = d
    }

    /** Column-major head->world rotation matrix from a unit quaternion [x,y,z,w]. */
    private fun quatToMatrix(q: FloatArray, m: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val n = x * x + y * y + z * z + w * w
        val s = if (n > 0f) 2f / n else 0f
        val xs = x * s; val ys = y * s; val zs = z * s
        val wx = w * xs; val wy = w * ys; val wz = w * zs
        val xx = x * xs; val xy = x * ys; val xz = x * zs
        val yy = y * ys; val yz = y * zs; val zz = z * zs

        m[0] = 1f - (yy + zz); m[1] = xy + wz;        m[2] = xz - wy;        m[3] = 0f
        m[4] = xy - wz;        m[5] = 1f - (xx + zz); m[6] = yz + wx;        m[7] = 0f
        m[8] = xz + wy;        m[9] = yz - wx;        m[10] = 1f - (xx + yy); m[11] = 0f
        m[12] = 0f;            m[13] = 0f;            m[14] = 0f;            m[15] = 1f
    }

    companion object {
        private const val TAG = "Pri4L"
        private const val GYRO_CLASS = "com.inmo.air3_core.atw.GyroRotation"
        private const val NATIVE_LIB = "inmoair3"   // libinmoair3.so — provides nativeGyroFusion

        // Yaw-drift correction tunables (getViewMatrix runs at GL frame rate ~60Hz).
        // DRIFT_ALPHA ~0.005 → ~3s time constant: rejects magnetometer jitter while still
        // removing the slow gyro drift. Lower = smoother but slower correction.
        private const val DRIFT_ALPHA = 0.005f

        /** True if the INMO fusion classes are on the classpath (AAR present). */
        fun isAvailable(): Boolean = try {
            Class.forName(GYRO_CLASS); true
        } catch (e: Throwable) {
            false
        }
    }
}
