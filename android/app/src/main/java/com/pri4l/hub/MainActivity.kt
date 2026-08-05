package com.pri4l.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import android.view.WindowManager

class MainActivity : ComponentActivity() {

    private val rosbridge = RosbridgeClient()
    private lateinit var cameraSender: CameraSender
    private lateinit var imuSender: ImuSender

    private val frameAlignment = FrameAlignment()
    private var arCameraBridge: ArCameraBridge? = null
    private var fiducialAligner: FiducialAligner? = null
    private var arSession: Session? = null
    private val glSurfaceViewState = mutableStateOf<GLSurfaceView?>(null)

    private val imuRunning = mutableStateOf(false)
    private val cameraRunning = mutableStateOf(false)
    private val arRunning = mutableStateOf(false)
    private val arAvailable = mutableStateOf(false)
    private val glassesAvailable = mutableStateOf(false)
    private val glassesRunning = mutableStateOf(false)
    private val arTrackingUiState = mutableStateOf(ArTrackingUiState.OFF)
    private val aligned = mutableStateOf(false)
    private val pose = mutableStateOf(PoseData())
    private val hubAnchorCountState = mutableStateOf(0)

    private var glassesTracker: HeadTracker? = null

    // Auto-align: throttle fiducial detection on the GL thread while unaligned.
    @Volatile private var lastFiducialMs = 0L

    private var hubAnchors: List<FloatArray> = emptyList()
    private var phoneAnchors: List<FloatArray> = emptyList()

    private val defaultHost = "192.168.68.129"

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingArStart) {
            pendingArStart = false
            startAr()
        } else if (granted) {
            cameraSender.start(this)
            cameraRunning.value = true
        }
    }
    private var pendingArStart = false

    private val prefs by lazy {
        getSharedPreferences("pri4l_hub", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraSender = CameraSender(this, rosbridge)
        imuSender = ImuSender(this, rosbridge)
        checkArAvailability()

        // Fiducial alignment: detect an ArUco tag at the hub origin to align ARCore <-> hub.
        if (org.opencv.android.OpenCVLoader.initLocal()) {
            fiducialAligner = FiducialAligner()
            android.util.Log.w("Pri4L", "OpenCV loaded; fiducial aligner ready (point at the tag, then Align)")
        } else {
            android.util.Log.e("Pri4L", "OpenCV failed to load; fiducial align unavailable (manual align still works)")
        }

        setContent {
            MaterialTheme {
                Surface {
                    ArAppScreen(
                        glSurfaceView = glSurfaceViewState.value,
                        connectionState = rosbridge.state.value,
                        tracking = arTrackingUiState.value,
                        isAligned = aligned.value,
                        messageCount = rosbridge.messageCount.value,
                        pose = pose.value,
                        imuActive = imuRunning.value,
                        cameraActive = cameraRunning.value,
                        hubAnchorCount = hubAnchorCountState.value,
                        host = prefs.getString("host", defaultHost) ?: defaultHost,
                        port = prefs.getString("port", "9090") ?: "9090",
                        onReAlign = ::handleClearAlignment,   // clear -> auto-align re-detects
                        onClearAlignment = ::handleClearAlignment,
                        onPlaceAnchor = ::handlePlaceAnchor,
                        onToggleImu = ::handleToggleImu,
                        onToggleCamera = ::handleToggleCamera,
                        onReconnect = { h, p ->
                            prefs.edit().putString("host", h).putString("port", p.toString()).apply()
                            connectToHub()
                        },
                    )
                }
            }
        }
        // AR-first: start AR (camera-permission gated) and connect happen in onResume.
    }

    private fun checkArAvailability() {
        var arSupported = false
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            arSupported = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
            // Double-check: try creating a session — if it fails, ARCore isn't usable
            if (arSupported) {
                val testSession = Session(this)
                testSession.close()
            }
        } catch (_: Exception) {
            arSupported = false
        }
        arAvailable.value = arSupported
        // If no ARCore, check if we have sensors for glasses mode
        if (!arAvailable.value) {
            glassesAvailable.value = HeadTrackerFactory.isAvailable(this)
        }
        android.util.Log.w("Pri4L", "arAvailable=$arSupported glassesAvailable=${glassesAvailable.value}")
    }

    /** Connect to the hub and (re)subscribe. Idempotent — safe to call on every foreground. */
    private fun connectToHub() {
        val host = prefs.getString("host", defaultHost) ?: defaultHost
        val port = prefs.getString("port", "9090")?.toIntOrNull() ?: 9090
        rosbridge.connect(host, port)

        rosbridge.subscribe("/hub/anchors/hub", "geometry_msgs/msg/PoseArray", throttleMs = 1000) { msg ->
            hubAnchors = parsePoseArray(msg)
            runOnUiThread { hubAnchorCountState.value = hubAnchors.size }
        }
        rosbridge.subscribe("/hub/anchors/phone", "geometry_msgs/msg/PoseArray", throttleMs = 1000) { msg ->
            phoneAnchors = parsePoseArray(msg)
        }
    }

    /** Start ARCore once (camera-permission gated), or resume an existing session. */
    private fun ensureArStarted() {
        if (arSession != null) {
            arSession?.resume()
            glSurfaceViewState.value?.onResume()
            return
        }
        if (!arAvailable.value) return
        if (hasCameraPermission()) {
            startAr()
        } else if (!pendingArStart) {
            pendingArStart = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handleToggleImu(enabled: Boolean) {
        if (enabled) imuSender.start() else imuSender.stop()
        imuRunning.value = enabled
    }

    private fun handleToggleCamera(enabled: Boolean) {
        if (enabled) {
            if (hasCameraPermission()) {
                cameraSender.start(this)
                cameraRunning.value = true
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            cameraSender.stop()
            cameraRunning.value = false
        }
    }

    private fun handleToggleAr(enabled: Boolean) {
        if (enabled) {
            if (cameraRunning.value) {
                cameraSender.stop()
                cameraRunning.value = false
            }
            if (hasCameraPermission()) {
                startAr()
            } else {
                pendingArStart = true
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            stopAr()
        }
    }

    private fun handleClearAlignment() {
        frameAlignment.reset()
        aligned.value = false
    }

    private fun handlePlaceAnchor() {
        // Publish current phone position in hub frame as a new anchor
        val currentPose = pose.value
        val now = System.currentTimeMillis()
        val msg = org.json.JSONObject().apply {
            put("header", org.json.JSONObject().apply {
                put("stamp", org.json.JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "map")
            })
            put("pose", org.json.JSONObject().apply {
                put("position", org.json.JSONObject().apply {
                    put("x", currentPose.x)
                    put("y", currentPose.y)
                    put("z", currentPose.z)
                })
                put("orientation", org.json.JSONObject().apply {
                    put("x", 0.0)
                    put("y", 0.0)
                    put("z", 0.0)
                    put("w", 1.0)
                })
            })
        }
        rosbridge.publish("/phone/anchors/create", "geometry_msgs/msg/PoseStamped", msg)
    }

    private fun startAr() {
        try {
            val session = Session(this)
            val config = Config(session)
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            session.configure(config)
            session.resume()
            arSession = session

            arCameraBridge = ArCameraBridge(rosbridge, frameAlignment) {
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }

            val renderer = ArRenderer(
                sessionProvider = { arSession },
                getHubAnchors = { hubAnchors },
                getPhoneAnchors = { phoneAnchors },
                getAlignmentMatrix = { frameAlignment.alignmentMatrix },
                displayRotation = {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                },
                onArFrame = { frame -> onArFrame(frame) }
            )

            val surfaceView = GLSurfaceView(this).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            glSurfaceViewState.value = surfaceView
            arRunning.value = true
            arTrackingUiState.value = ArTrackingUiState.NOT_TRACKING
        } catch (e: Exception) {
            arAvailable.value = false
            arTrackingUiState.value = ArTrackingUiState.OFF
        }
    }

    private fun onArFrame(frame: Frame) {
        val cam = frame.camera
        val uiTrack = when (cam.trackingState) {
            TrackingState.TRACKING -> ArTrackingUiState.TRACKING
            TrackingState.PAUSED -> ArTrackingUiState.PAUSED
            TrackingState.STOPPED -> ArTrackingUiState.NOT_TRACKING
        }
        runOnUiThread { arTrackingUiState.value = uiTrack }

        // Auto-align: while UNALIGNED, continuously detect the ArUco tag and lock on the first
        // solid detection (no tap). Throttled. While unaligned, the fiducial owns the camera
        // image; streaming runs only once aligned (below) to avoid a double acquireCameraImage.
        if (!frameAlignment.isAligned && cam.trackingState == TrackingState.TRACKING) {
            val now = System.currentTimeMillis()
            if (now - lastFiducialMs >= 250) {
                lastFiducialMs = now
                val tag = fiducialAligner?.detectTagInArWorld(frame)
                if (tag != null) {
                    frameAlignment.align(
                        floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
                        tag.first, tag.second
                    )
                    runOnUiThread { aligned.value = true }
                    android.util.Log.w("Pri4L",
                        "AUTO-ALIGNED via FIDUCIAL: tagWorld=[%.3f, %.3f, %.3f]"
                            .format(tag.first[0], tag.first[1], tag.first[2]))
                }
            }
            return  // don't stream/acquire again this frame
        }

        // Aligned: stream frames + publish pose in the hub frame.
        if (frameAlignment.isAligned) {
            arCameraBridge?.onFrame(frame)
            val arPose = arCameraBridge?.getCurrentArPose(frame)
            if (arPose != null) {
                val hubPose = frameAlignment.arToHub(arPose.first, arPose.second)
                if (hubPose != null) {
                    runOnUiThread {
                        pose.value = PoseData(
                            x = hubPose.first[0].toDouble(),
                            y = hubPose.first[1].toDouble(),
                            z = hubPose.first[2].toDouble()
                        )
                    }
                }
            }
        }
    }

    private fun stopAr() {
        arRunning.value = false
        aligned.value = false
        arTrackingUiState.value = ArTrackingUiState.OFF
        frameAlignment.reset()
        glSurfaceViewState.value = null
        arCameraBridge = null
        arSession?.pause()
        arSession?.close()
        arSession = null
    }

    private fun handleToggleGlasses(enabled: Boolean) {
        if (enabled) startGlasses() else stopGlasses()
    }

    private fun startGlasses() {
        android.util.Log.w("Pri4L", "startGlasses() called")
        val tracker = HeadTrackerFactory.create(this)
        tracker.start()
        glassesTracker = tracker
        android.util.Log.w("Pri4L", "head tracker source: ${tracker.sourceName}")

        val renderer = GlassesRenderer(
            tracker = tracker,
            getHubAnchors = { hubAnchors },
            getPhoneAnchors = { phoneAnchors },
            getAlignmentMatrix = { frameAlignment.alignmentMatrix }
        )

        val surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glSurfaceViewState.value = surfaceView
        glassesRunning.value = true
        android.util.Log.w("Pri4L", "glassesRunning=true, glSurfaceView set")

        // In glasses mode, replace the entire content view with the GL surface
        setContentView(surfaceView)

        // Auto-align: place glasses 1.5m back from hub origin so anchors are visible
        if (!frameAlignment.isAligned) {
            frameAlignment.align(
                floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),  // hub origin
                floatArrayOf(0f, 0f, 1.5f), floatArrayOf(0f, 0f, 0f, 1f) // glasses 1.5m back
            )
            aligned.value = true
        }
    }

    private fun stopGlasses() {
        glassesRunning.value = false
        glassesTracker?.stop()
        glassesTracker = null
        glSurfaceViewState.value = null
        frameAlignment.reset()
        aligned.value = false
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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        ensureArStarted()   // start AR (perm-gated) on first run, resume otherwise
        connectToHub()      // reconnect on foreground (was disconnected on background)
    }

    override fun onPause() {
        super.onPause()
        if (arSession != null) {
            glSurfaceViewState.value?.onPause()
            arSession?.pause()
        }
        // Full disconnect in the background — zero background socket churn.
        rosbridge.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAr()
        cameraSender.stop()
        imuSender.stop()
        rosbridge.disconnect()
    }
}
