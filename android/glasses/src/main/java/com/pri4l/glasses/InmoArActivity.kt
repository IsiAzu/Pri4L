package com.pri4l.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader

/**
 * Purpose-built INMO Air3 client (Track A). Native landscape/fullscreen at the waveguide's
 * 1920x1080 — no Compose, no phone UI. Renders hub/phone anchors in 3D with INMO native head
 * tracking (decision 011), streams the glasses' camera + IMU to the hub, and shows a minimal
 * HUD. The phone app (:app) stays the Track B / ARCore client; this is its own APK.
 *
 * Touchpad: tap = recenter "forward"; D-pad down = toggle streaming; D-pad up = reconnect.
 */
class InmoArActivity : ComponentActivity() {

    private val TAG = "Pri4L"

    private val client = RosbridgeClient()
    private lateinit var tracker: HeadTracker
    private lateinit var glView: GLSurfaceView
    private lateinit var hud: TextView

    private var imuSender: ImuSender? = null
    private var cameraSender: CameraSender? = null
    private var aligner: ArucoAligner? = null
    private var streaming = false
    private var cameraGranted = false

    @Volatile private var hubAnchors: List<FloatArray> = emptyList()
    @Volatile private var phoneAnchors: List<FloatArray> = emptyList()

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            Log.w(TAG, "camera permission granted=$granted")
            // Streaming may have auto-started (IMU only) before this dialog was answered.
            // If so, bring the camera up now that we're allowed.
            if (granted && streaming) cameraSender?.start(this)
            updateHud()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Head tracking — INMO native fusion if the AAR is present, else the broken fallback.
        tracker = HeadTrackerFactory.create(this)
        tracker.start()
        Log.w(TAG, "head tracker source: ${tracker.sourceName}")

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(AnchorRenderer(tracker, { hubAnchors }, { phoneAnchors }))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(24, 24, 24, 24)
        }

        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(hud, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START))
        })

        imuSender = ImuSender(this, client)
        cameraSender = CameraSender(this, client)

        // On-device fiducial alignment (decision 010). Detect the ArUco tag at the hub origin
        // and tap each camera frame into the aligner.
        if (OpenCVLoader.initLocal()) {
            aligner = ArucoAligner()
            cameraSender?.onFrame = { proxy, fx, fy, cx, cy ->
                aligner?.process(proxy, fx, fy, cx, cy)
                runOnUiThread { updateHud() }
            }
            Log.w(TAG, "OpenCV loaded; ArUco aligner ready (marker ${ArucoAligner.MARKER_SIZE_M}m @ hub origin)")
        } else {
            Log.e(TAG, "OpenCV failed to load; fiducial alignment disabled")
        }

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) requestCamera.launch(Manifest.permission.CAMERA)

        // HUD updates whenever connection state or message count changes.
        client.state.listener = { runOnUiThread { onStateChanged(); updateHud() } }
        client.messageCount.listener = { runOnUiThread { updateHud() } }

        client.subscribe("/hub/anchors/hub", "geometry_msgs/msg/PoseArray", throttleMs = 1000) {
            hubAnchors = parsePoseArray(it); runOnUiThread { updateHud() }
        }
        client.subscribe("/hub/anchors/phone", "geometry_msgs/msg/PoseArray", throttleMs = 1000) {
            phoneAnchors = parsePoseArray(it); runOnUiThread { updateHud() }
        }

        connect()
        updateHud()
    }

    private fun connect() {
        Log.w(TAG, "connecting to ${HubConfig.host(this)}:${HubConfig.port(this)}")
        client.connect(HubConfig.host(this), HubConfig.port(this))
    }

    /** Auto-start streaming once connected (full-client behavior). */
    private fun onStateChanged() {
        if (client.state.value == ConnectionState.CONNECTED && !streaming) {
            startStreaming()
        }
    }

    private fun startStreaming() {
        imuSender?.start()
        if (cameraGranted) cameraSender?.start(this)
        streaming = true
        Log.w(TAG, "streaming started (imu + ${if (cameraGranted) "camera" else "no-camera"})")
        updateHud()
    }

    private fun stopStreaming() {
        imuSender?.stop()
        cameraSender?.stop()
        streaming = false
        Log.w(TAG, "streaming stopped")
        updateHud()
    }

    private fun updateHud() {
        val s = client.state.value
        val cam = if (cameraGranted) "cam" else "cam(no-perm)"
        hud.text = buildString {
            append("Pri4L Glasses\n")
            append("Hub ${HubConfig.host(this@InmoArActivity)}:${HubConfig.port(this@InmoArActivity)}  [$s]\n")
            append("Tracker ${if (tracker.isTracking) "OK" else "--"}: ${tracker.sourceName}\n")
            append("Anchors hub=${hubAnchors.size} phone=${phoneAnchors.size}  msgs=${client.messageCount.value}\n")
            append("Stream ${if (streaming) "ON ($cam+imu)" else "off"}  yawCorr=${tracker.yawCorrectionLabel}\n")
            val a = aligner
            when {
                a == null -> append("Align: opencv off\n")
                a.aligned -> append("Align: tag OK  range=%.2fm pos=[%.2f,%.2f,%.2f]\n"
                    .format(a.rangeM, a.camPosHub[0], a.camPosHub[1], a.camPosHub[2]))
                else -> append("Align: searching for tag (id 0)\n")
            }
            append("tap=recenter down=stream up=reconnect L=yawcorr R=flipsign")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                tracker.recenter()
                Log.w(TAG, "recenter (keyCode=$keyCode)")
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (streaming) stopStreaming() else startStreaming()
            KeyEvent.KEYCODE_DPAD_UP -> connect()
            KeyEvent.KEYCODE_DPAD_LEFT -> { tracker.toggleYawCorrection(); updateHud() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { tracker.flipYawSign(); updateHud() }
            else -> { Log.w(TAG, "unhandled keyCode=$keyCode"); return super.onKeyDown(keyCode, event) }
        }
        return true
    }

    private fun parsePoseArray(msg: org.json.JSONObject): List<FloatArray> {
        val poses = msg.optJSONArray("poses") ?: return emptyList()
        val result = mutableListOf<FloatArray>()
        for (i in 0 until poses.length()) {
            val ap = poses.getJSONObject(i).optJSONObject("position") ?: continue
            result.add(floatArrayOf(
                ap.optDouble("x", 0.0).toFloat(),
                ap.optDouble("y", 0.0).toFloat(),
                ap.optDouble("z", 0.0).toFloat()
            ))
        }
        return result
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        tracker.start()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        if (streaming) stopStreaming()
        tracker.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
    }
}
