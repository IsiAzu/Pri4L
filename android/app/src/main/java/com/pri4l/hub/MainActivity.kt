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
    private var arSession: Session? = null
    private val glSurfaceViewState = mutableStateOf<GLSurfaceView?>(null)

    private val imuRunning = mutableStateOf(false)
    private val cameraRunning = mutableStateOf(false)
    private val arRunning = mutableStateOf(false)
    private val arAvailable = mutableStateOf(false)
    private val arTrackingUiState = mutableStateOf(ArTrackingUiState.OFF)
    private val aligned = mutableStateOf(false)
    private val pose = mutableStateOf(PoseData())

    // Flag set by UI thread, consumed by GL thread
    @Volatile private var alignRequested = false

    private var hubAnchors: List<FloatArray> = emptyList()

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

        setContent {
            MaterialTheme {
                Surface {
                    HubScreen(
                        connectionState = rosbridge.state.value,
                        messageCount = rosbridge.messageCount.value,
                        pose = pose.value,
                        imuActive = imuRunning.value,
                        cameraActive = cameraRunning.value,
                        arActive = arRunning.value,
                        arAvailable = arAvailable.value,
                        arTrackingState = arTrackingUiState.value,
                        isAligned = aligned.value,
                        savedHost = prefs.getString("host", "192.168.68.143") ?: "192.168.68.143",
                        savedPort = prefs.getString("port", "9090") ?: "9090",
                        onConnect = ::handleConnect,
                        onDisconnect = ::handleDisconnect,
                        onToggleImu = ::handleToggleImu,
                        onToggleCamera = ::handleToggleCamera,
                        onToggleAr = ::handleToggleAr,
                        onAlign = ::handleAlign,
                        onClearAlignment = ::handleClearAlignment,
                        glSurfaceView = glSurfaceViewState.value
                    )
                }
            }
        }
    }

    private fun checkArAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        arAvailable.value = availability.isSupported
    }

    private fun handleConnect(host: String, port: Int) {
        prefs.edit().putString("host", host).putString("port", port.toString()).apply()
        rosbridge.connect(host, port)

        // Subscribe to hub anchors for AR rendering
        rosbridge.subscribe(
            topic = "/hub/anchors",
            type = "geometry_msgs/msg/PoseArray",
            throttleMs = 1000
        ) { msg ->
            val poses = msg.optJSONArray("poses")
            if (poses != null) {
                val anchors = mutableListOf<FloatArray>()
                for (i in 0 until poses.length()) {
                    val ap = poses.getJSONObject(i).optJSONObject("position")
                    if (ap != null) {
                        anchors.add(floatArrayOf(
                            ap.optDouble("x", 0.0).toFloat(),
                            ap.optDouble("y", 0.0).toFloat(),
                            ap.optDouble("z", 0.0).toFloat()
                        ))
                    }
                }
                hubAnchors = anchors
            }
        }
    }

    private fun handleDisconnect() {
        if (arRunning.value) handleToggleAr(false)
        if (imuRunning.value) handleToggleImu(false)
        if (cameraRunning.value) handleToggleCamera(false)
        rosbridge.disconnect()
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

    private fun handleAlign() {
        // Set flag — actual alignment happens on the GL thread in onArFrame
        alignRequested = true
    }

    private fun handleClearAlignment() {
        frameAlignment.reset()
        aligned.value = false
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

        // Handle alignment request from UI thread
        if (alignRequested) {
            alignRequested = false
            if (cam.trackingState == TrackingState.TRACKING) {
                val arPose = cam.pose
                val arPos = floatArrayOf(arPose.tx(), arPose.ty(), arPose.tz())
                val arRot = floatArrayOf(arPose.qx(), arPose.qy(), arPose.qz(), arPose.qw())

                // D435 is at RTAB-Map origin
                val hubPos = floatArrayOf(0f, 0f, 0f)
                val hubRot = floatArrayOf(0f, 0f, 0f, 1f)

                frameAlignment.align(hubPos, hubRot, arPos, arRot)
                runOnUiThread { aligned.value = true }

                android.util.Log.w("Pri4L",
                    "ALIGNED: arPos=[%.3f, %.3f, %.3f]".format(arPos[0], arPos[1], arPos[2]))
            }
        }

        // Stream frames and publish pose
        arCameraBridge?.onFrame(frame)

        // Update displayed pose from ARCore (in hub frame if aligned)
        if (frameAlignment.isAligned) {
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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (arRunning.value) {
            arSession?.resume()
            glSurfaceViewState.value?.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (arRunning.value) {
            glSurfaceViewState.value?.onPause()
            arSession?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAr()
        cameraSender.stop()
        imuSender.stop()
        rosbridge.disconnect()
    }
}
