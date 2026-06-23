package com.pri4l.hub

import android.app.Activity
import android.content.Context
import android.opengl.Matrix
import android.util.Log
import com.unity3d.player.UnityPlayer

/**
 * Head tracker backed by INMO's native sensor fusion (`com.inmo.air3_core.atw.GyroRotation`).
 *
 * WHY REFLECTION: the `air3_core` AAR is integrated as a local file dependency (see
 * `android/app/libs/README.md`). Until that .aar is actually dropped in, the INMO classes
 * are absent at compile time, so we bind to them reflectively. This keeps the project
 * building today and lets the INMO path activate automatically once the AAR is present.
 * Once the API is confirmed on-device, this can be swapped for direct typed calls.
 *
 * The vendor quaternion (`GyroRotation.vQuat`) already accounts for the glasses' physical
 * IMU mounting — that is exactly the offset `remapCoordinateSystem` could not represent
 * (see decision 011). So this path should not exhibit the "yaw -> roll" bug.
 *
 * UNVERIFIED ON HARDWARE (confirm and adjust when the AAR lands):
 *   - vQuat component order (assumed [x, y, z, w]; INMO may use [w, x, y, z]).
 *   - vQuat field type (assumed float[4]; may be a Quaternion object).
 *   - GyroRotation lifecycle (getInstance vs constructor; start() arg shape).
 *   - eye-axis handedness vs GL (see [axisFix]).
 *
 * REQUIRES AN ACTIVITY: `GyroRotation.start()` reads `UnityPlayer.currentActivity`
 * (not any passed-in Context) to get the Activity it calls `getSystemService("sensor")`
 * on — the AAR is compiled against Unity. We set that field from [activity] in [start]
 * via our local [UnityPlayer] stub (decision 011). Without it, `start()` throws
 * NoClassDefFoundError / leaves the SensorManager null and no `vQuat` ever arrives.
 */
class InmoFusionTracker(private val activity: Activity) : HeadTracker {

    override val sourceName: String = "INMO GyroRotation.vQuat (native fusion)"

    @Volatile
    override var isTracking = false
        private set

    // Reflection handles, resolved in start().
    private var gyroInstance: Any? = null
    private var quatField: java.lang.reflect.Field? = null

    private val quat = FloatArray(4)            // working [x, y, z, w]
    private val rot = FloatArray(16)            // head -> world
    private val refRot = FloatArray(16)         // head -> world at recenter
    private val tmp = FloatArray(16)
    @Volatile private var haveRef = false
    @Volatile private var recenterRequested = true   // auto-recenter on first frame

    /**
     * Eye-frame correction applied as `view = axisFix * viewRaw`. Identity by default.
     * If, on hardware, forward/up/right come out swapped or mirrored even after the
     * quaternion is read correctly, set this to the appropriate constant 90deg rotation
     * instead of touching the quaternion math. Recentering cancels world-frame offsets
     * but NOT a body/eye-axis mismatch — that is what this matrix is for.
     */
    private val axisFix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    override fun start() {
        try {
            // The AAR's GyroRotation.start() reads UnityPlayer.currentActivity (not the
            // Context we hold) to obtain the Activity for getSystemService("sensor").
            // Populate our stub before invoking it, or no sensors register.
            UnityPlayer.currentActivity = activity

            // The native fusion symbol (AtwCore.nativeGyroFusion) lives in libinmoair3.so,
            // normally loaded by Air3Core's static initializer during Unity/Air3Core init —
            // which we bypass. Load it ourselves, or the first sensor callback aborts with
            // UnsatisfiedLinkError. Idempotent; transitively pulls in the bundled deps.
            try {
                System.loadLibrary(NATIVE_LIB)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load lib$NATIVE_LIB.so; INMO native fusion unavailable", e)
                isTracking = false
                return
            }

            val cls = Class.forName(GYRO_CLASS)

            // Lifecycle: try singleton accessor, then Context constructor, then no-arg.
            gyroInstance = tryGetInstance(cls)

            // Locate the vQuat field (instance or static).
            quatField = cls.declaredFields.firstOrNull { it.name == "vQuat" }
                ?.apply { isAccessible = true }
                ?: cls.fields.firstOrNull { it.name == "vQuat" }?.apply { isAccessible = true }

            // Kick the fusion loop. Tolerate either start() or start(Context).
            tryInvokeStart(cls, gyroInstance)

            if (quatField == null) {
                Log.e(TAG, "GyroRotation found but no vQuat field; cannot track")
                isTracking = false
                return
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

    override fun getViewMatrix(dest: FloatArray) {
        if (!isTracking || !readQuat()) {
            Matrix.setIdentityM(dest, 0)
            return
        }

        quatToMatrix(quat, rot)   // rot = head -> world

        if (recenterRequested || !haveRef) {
            System.arraycopy(rot, 0, refRot, 0, 16)
            haveRef = true
            recenterRequested = false
        }

        // view = axisFix * (rot^T * refRot)   — relative to the recenter orientation.
        Matrix.transposeM(tmp, 0, rot, 0)
        Matrix.multiplyMM(dest, 0, tmp, 0, refRot, 0)
        Matrix.multiplyMM(tmp, 0, axisFix, 0, dest, 0)
        System.arraycopy(tmp, 0, dest, 0, 16)
    }

    // --- reflection helpers --------------------------------------------------

    private fun tryGetInstance(cls: Class<*>): Any? {
        // 1) static getInstance()
        cls.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }
            ?.let { return it.invoke(null) }
        // 2) constructor(Context)
        runCatching { cls.getConstructor(Context::class.java).newInstance(activity) }
            .getOrNull()?.let { return it }
        // 3) no-arg constructor
        runCatching { cls.getConstructor().newInstance() }.getOrNull()?.let { return it }
        // 4) fully static API — no instance needed
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
                else -> readQuatObject(v)   // custom Quaternion type
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** Best-effort read of an object exposing x/y/z/w as fields or getters. */
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

    /** ASSUMPTION: vendor order is [x,y,z,w]. If glasses spin wrong, try [w,x,y,z] here. */
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

        /** True if the INMO fusion classes are on the classpath (AAR present). */
        fun isAvailable(): Boolean = try {
            Class.forName(GYRO_CLASS); true
        } catch (e: Throwable) {
            false
        }
    }
}
