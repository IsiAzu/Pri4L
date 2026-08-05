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
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
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
    // Re-lock: throttle fiducial detection while already aligned.
    @Volatile private var lastRelockMs = 0L

    /**
     * ARCore anchor seated at the detected tag pose. ARCore drift-corrects anchor poses as it
     * refines its world estimate, so re-deriving the hub->AR transform from this anchor every
     * frame keeps the cubes physically fixed. A cached transform built from raw world coordinates
     * does not — it silently goes stale as you walk, then jumps when ARCore relocalizes.
     */
    private var alignAnchor: Anchor? = null

    // Scratch, reused on the GL thread to keep the render loop allocation-free.
    private val hubOriginPos = floatArrayOf(0f, 0f, 0f)
    private val hubOriginRot = floatArrayOf(0f, 0f, 0f, 1f)
    private val anchorPos = FloatArray(3)
    private val anchorRot = FloatArray(4)

    // --- Tracking-continuity watchdog -------------------------------------------------------
    // Blocking the camera can make ARCore give up and redefine its world frame. When that
    // happens the align anchor's pose is meaningless in the new frame, so trusting it renders
    // the cubes confidently in the wrong place. Detect the two signatures of a reset and force
    // a fresh fiducial lock instead.
    @Volatile private var wasTracking = false
    private var lostTrackingAtMs = 0L
    /** Alignment survived a tracking gap but has not been re-verified against the tag yet. */
    @Volatile private var alignmentSuspect = false
    /** When the align anchor stopped tracking, so we can give up if ARCore never relocalizes. */
    private var relocWaitStartedMs = 0L
    private val lastCamPos = FloatArray(3)
    private var lastCamPosMs = 0L
    private var haveLastCamPos = false
    private val lastAnchorPos = FloatArray(3)
    private var haveLastAnchorPos = false

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
        alignAnchor?.detach()
        alignAnchor = null
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
            android.util.Log.w("Pri4L", "startAr: NEW ARCore session created — world frame reset " +
                "to this pose, any previous alignment is void")

            arCameraBridge = ArCameraBridge(rosbridge, frameAlignment) {
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }

            val renderer = ArRenderer(
                sessionProvider = { arSession },
                getHubAnchors = { hubAnchors },
                getPhoneAnchors = { phoneAnchors },
                getAlignmentMatrix = { frameAlignment.alignmentMatrix },
                isAlignmentTrusted = ::isAlignmentTrusted,
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

        watchTrackingContinuity(cam)

        // Auto-align: while UNALIGNED, continuously detect the ArUco tag and lock on the first
        // solid detection (no tap). Throttled. While unaligned, the fiducial owns the camera
        // image; streaming runs only once aligned (below) to avoid a double acquireCameraImage.
        if (!frameAlignment.isAligned) {
            if (cam.trackingState == TrackingState.TRACKING) {
                val now = System.currentTimeMillis()
                if (now - lastFiducialMs >= 250) {
                    lastFiducialMs = now
                    val tag = fiducialAligner?.detectTagInArWorld(frame)
                    if (tag != null) {
                        lockAlignment(tag.first, tag.second)
                        lastRelockMs = now
                        runOnUiThread { aligned.value = true }
                        android.util.Log.w("Pri4L",
                            "AUTO-ALIGNED via FIDUCIAL: tagWorld=[%.3f, %.3f, %.3f]"
                                .format(tag.first[0], tag.first[1], tag.first[2]))
                    }
                }
            }
            return  // don't stream/acquire again this frame
        }

        // Aligned. Re-derive the transform from the anchor's CURRENT pose every frame: ARCore
        // moves the anchor to compensate for its own drift and relocalization jumps, so this is
        // what keeps the cubes nailed to the physical world once you walk away from the tag.
        refreshAlignmentFromAnchor()

        if (cam.trackingState != TrackingState.TRACKING) return

        val now = System.currentTimeMillis()

        // The align anchor going non-TRACKING means ARCore lost its place: the old anchor is
        // orphaned and the cubes must be hidden. It does NOT mean detections are unusable —
        // tagWorld is built from the CURRENT camera pose in ARCore's CURRENT frame, so a fresh
        // sighting re-locks correctly right away. Waiting for ARCore to self-relocalize instead
        // stalled recovery for 30s+ in a dim room (measured 2026-08-05), so hunt hard instead.
        val trusted = isAlignmentTrusted()
        if (!trusted) {
            if (relocWaitStartedMs == 0L) {
                relocWaitStartedMs = now
                android.util.Log.w("Pri4L",
                    "anchor=${alignAnchor?.trackingState} — cubes hidden; hunting for the tag " +
                    "(a fresh detection re-locks immediately)")
            }
            alignmentSuspect = true
        } else {
            relocWaitStartedMs = 0L
        }
        if (aligned.value != trusted) runOnUiThread { aligned.value = trusted }

        // Re-lock: whenever the tag is back in view, re-seat the anchor on a fresh close-range
        // detection. Costs one camera image, so this frame skips streaming (one
        // acquireCameraImage per frame). A suspect alignment hunts harder and accepts any
        // detection; a healthy one only re-seats on a meaningfully different reading.
        val suspect = alignmentSuspect
        val interval = if (suspect) SUSPECT_RELOCK_INTERVAL_MS else RELOCK_INTERVAL_MS
        val maxRange = if (suspect) SUSPECT_RELOCK_MAX_RANGE_M else RELOCK_MAX_RANGE_M

        if (now - lastRelockMs >= interval) {
            lastRelockMs = now
            val tag = fiducialAligner?.detectTagInArWorld(frame)
            val range = fiducialAligner?.lastRangeM ?: Float.MAX_VALUE
            if (tag != null && range <= maxRange &&
                (suspect || shouldRelock(tag.first, tag.second))) {
                if (suspect) logRecoveryDelta(tag.first, tag.second)
                lockAlignment(tag.first, tag.second)
                alignmentSuspect = false
                android.util.Log.w("Pri4L",
                    "RE-LOCKED on fiducial%s: range=%.2fm tagWorld=[%.3f, %.3f, %.3f]"
                        .format(if (suspect) " (was SUSPECT)" else "", range,
                                tag.first[0], tag.first[1], tag.first[2]))
            }
            return
        }

        // Stream frames + publish pose in the hub frame — but never publish a pose derived from
        // an alignment we don't believe; the hub would record the phone in the wrong place.
        if (!trusted) return
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

    /**
     * Watch for the two signatures of ARCore abandoning its world frame (e.g. after the camera
     * is covered): a gap in tracking, and a discontinuous camera-pose jump. Either one means the
     * align anchor can no longer be trusted to sit on the physical tag.
     */
    private fun watchTrackingContinuity(cam: com.google.ar.core.Camera) {
        val now = System.currentTimeMillis()
        val tracking = cam.trackingState == TrackingState.TRACKING

        if (!tracking) {
            if (wasTracking) {
                lostTrackingAtMs = now
                android.util.Log.w("Pri4L",
                    "TRACKING LOST: state=${cam.trackingState} reason=${cam.trackingFailureReason}")
            }
            wasTracking = false
            haveLastCamPos = false   // don't measure a jump across the gap; it's ambiguous
            return
        }

        if (!wasTracking) {
            val downMs = if (lostTrackingAtMs == 0L) 0L else now - lostTrackingAtMs
            wasTracking = true
            if (frameAlignment.isAligned && lostTrackingAtMs != 0L) {
                // Keep the anchor across the gap deliberately: the next tag sighting measures
                // how far it moved, which is the only direct evidence of whether ARCore's world
                // frame survived. See logRecoveryDelta().
                alignmentSuspect = true
                android.util.Log.w("Pri4L",
                    "TRACKING REGAINED after ${downMs}ms — alignment SUSPECT, re-verifying " +
                    "against the tag (anchor=${alignAnchor?.trackingState})")
            }
            return
        }

        // Still tracking: a large single-frame camera translation is ARCore either relocalizing
        // (correcting a drifted pose — harmless, anchors absorb it) or redefining its world
        // frame (fatal for the alignment). The two are told apart by whether the ANCHOR moved
        // with the camera: a relocalization leaves the anchor pinned to the physical tag, so its
        // world pose barely changes while the camera teleports.
        val p = cam.pose
        val anchor = alignAnchor
        val anchorTracking = anchor != null && anchor.trackingState == TrackingState.TRACKING

        if (haveLastCamPos && now - lastCamPosMs <= JUMP_WINDOW_MS) {
            val dx = p.tx() - lastCamPos[0]
            val dy = p.ty() - lastCamPos[1]
            val dz = p.tz() - lastCamPos[2]
            val camMoved = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            if (camMoved > WORLD_JUMP_M) {
                if (anchorTracking && haveLastAnchorPos) {
                    val ax = anchor!!.pose.tx() - lastAnchorPos[0]
                    val ay = anchor.pose.ty() - lastAnchorPos[1]
                    val az = anchor.pose.tz() - lastAnchorPos[2]
                    val anchorMoved = Math.sqrt((ax * ax + ay * ay + az * az).toDouble())
                    android.util.Log.e("Pri4L",
                        ("POSE JUMP: camera %.2fm / anchor %.2fm in %dms -> %s")
                            .format(camMoved, anchorMoved, now - lastCamPosMs,
                                if (anchorMoved < ANCHOR_FOLLOW_M)
                                    "relocalization, anchor held (alignment OK)"
                                else "anchor moved too — world frame redefined"))
                } else {
                    android.util.Log.e("Pri4L",
                        "POSE JUMP: camera %.2fm in %dms (no tracking anchor to compare)"
                            .format(camMoved, now - lastCamPosMs))
                }
                // Deliberately does NOT drop the alignment: measurements so far show these are
                // relocalization corrections, which the anchor already absorbs. The SUSPECT
                // re-verify path re-checks against the tag anyway.
                alignmentSuspect = true
            }
        }
        lastCamPos[0] = p.tx(); lastCamPos[1] = p.ty(); lastCamPos[2] = p.tz()
        lastCamPosMs = now
        haveLastCamPos = true

        if (anchorTracking) {
            lastAnchorPos[0] = anchor!!.pose.tx()
            lastAnchorPos[1] = anchor.pose.ty()
            lastAnchorPos[2] = anchor.pose.tz()
            haveLastAnchorPos = true
        } else {
            haveLastAnchorPos = false
        }
    }

    /**
     * After a tracking gap, measure how far the align anchor has moved relative to a fresh
     * sighting of the physical tag. A few cm means ARCore relocalized and its world frame
     * survived; a large delta means the frame was redefined and everything drawn during the gap
     * was wrong. This is the measurement that decides the recovery policy.
     */
    private fun logRecoveryDelta(tagPos: FloatArray, tagRot: FloatArray) {
        val anchor = alignAnchor ?: return
        val p = anchor.pose
        val dx = tagPos[0] - p.tx(); val dy = tagPos[1] - p.ty(); val dz = tagPos[2] - p.tz()
        val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())

        var dot = Math.abs(tagRot[0] * p.qx() + tagRot[1] * p.qy() +
                           tagRot[2] * p.qz() + tagRot[3] * p.qw()).toDouble()
        if (dot > 1.0) dot = 1.0
        val angDeg = Math.toDegrees(2.0 * Math.acos(dot))

        android.util.Log.e("Pri4L",
            "RECOVERY DELTA: anchor vs fresh tag = %.3fm / %.1fdeg  (anchor=%s) -> %s"
                .format(dist, angDeg, anchor.trackingState,
                    if (dist < 0.10) "world SURVIVED the gap" else "world was RESET"))
    }

    /**
     * True if a fresh detection disagrees with the current anchor enough to be worth re-seating.
     * Without this, solvePnP noise would twitch the cubes every [RELOCK_INTERVAL_MS] while the
     * tag is in view.
     */
    private fun shouldRelock(tagPos: FloatArray, tagRot: FloatArray): Boolean {
        val anchor = alignAnchor ?: return true
        if (anchor.trackingState != TrackingState.TRACKING) return true
        val p = anchor.pose

        val dx = tagPos[0] - p.tx(); val dy = tagPos[1] - p.ty(); val dz = tagPos[2] - p.tz()
        if (dx * dx + dy * dy + dz * dz > RELOCK_MIN_SHIFT_M * RELOCK_MIN_SHIFT_M) return true

        // |dot| = cos(halfAngle) between the two orientations; sign is irrelevant (q ~ -q).
        val dot = Math.abs(tagRot[0] * p.qx() + tagRot[1] * p.qy() +
                           tagRot[2] * p.qz() + tagRot[3] * p.qw())
        return dot < RELOCK_MIN_DOT
    }

    /** Seat an ARCore anchor at the detected tag pose and align the hub frame to it. */
    private fun lockAlignment(tagPos: FloatArray, tagRot: FloatArray) {
        normalize(tagRot)  // ARCore's Pose assumes a unit quaternion
        try {
            arSession?.createAnchor(Pose(tagPos, tagRot))?.let { fresh ->
                alignAnchor?.detach()
                alignAnchor = fresh
            }
        } catch (e: Exception) {
            android.util.Log.e("Pri4L", "createAnchor failed; alignment will not drift-correct", e)
        }
        frameAlignment.align(hubOriginPos, hubOriginRot, tagPos, tagRot)
    }

    /**
     * Whether the hub->AR transform can currently be believed. The camera regaining tracking is
     * NOT enough — ARCore relocalizes seconds later, and until it does the transform points
     * roughly 1m away from the truth. The anchor tracking again is the signal that it has.
     */
    private fun isAlignmentTrusted(): Boolean {
        val anchor = alignAnchor ?: return frameAlignment.isAligned  // no anchor: legacy path
        return anchor.trackingState == TrackingState.TRACKING
    }

    /** Rebuild the hub->AR transform from the anchor's drift-corrected pose. GL thread. */
    private fun refreshAlignmentFromAnchor() {
        val anchor = alignAnchor ?: return
        if (anchor.trackingState != TrackingState.TRACKING) return
        val p = anchor.pose
        anchorPos[0] = p.tx(); anchorPos[1] = p.ty(); anchorPos[2] = p.tz()
        anchorRot[0] = p.qx(); anchorRot[1] = p.qy(); anchorRot[2] = p.qz(); anchorRot[3] = p.qw()
        frameAlignment.align(hubOriginPos, hubOriginRot, anchorPos, anchorRot)
    }

    private fun stopAr() {
        arRunning.value = false
        aligned.value = false
        arTrackingUiState.value = ArTrackingUiState.OFF
        frameAlignment.reset()
        alignAnchor?.detach()
        alignAnchor = null
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
        android.util.Log.w("Pri4L", "onResume (session=${if (arSession == null) "none" else "existing"})")
        ensureArStarted()   // start AR (perm-gated) on first run, resume otherwise
        connectToHub()      // reconnect on foreground (was disconnected on background)
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.w("Pri4L", "onPause — ARCore session pausing")
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

    /** Normalize a [qx, qy, qz, qw] quaternion in place. */
    private fun normalize(q: FloatArray) {
        val n = Math.sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat()
        if (n > 1e-6f) { q[0] /= n; q[1] /= n; q[2] /= n; q[3] /= n }
    }

    companion object {
        /** How often to re-check for the tag once aligned, to re-seat the anchor. */
        private const val RELOCK_INTERVAL_MS = 2000L
        /** Ignore re-lock detections beyond this range — far solvePnP poses are noisy. */
        private const val RELOCK_MAX_RANGE_M = 2.5f
        /** Positional disagreement that justifies re-seating the anchor. */
        private const val RELOCK_MIN_SHIFT_M = 0.02f
        /** Orientation disagreement that justifies re-seating: |dot| below cos(1deg). */
        private const val RELOCK_MIN_DOT = 0.99985f

        /** Hunt for the tag this often while the alignment is unverified after a tracking gap. */
        private const val SUSPECT_RELOCK_INTERVAL_MS = 250L
        /** Accept a longer-range fix when suspect — roughly right beats confidently wrong. */
        private const val SUSPECT_RELOCK_MAX_RANGE_M = 4.0f
        /** Single-frame camera translation too large to be real motion. */
        private const val WORLD_JUMP_M = 0.35f
        /** Anchor movement below this during a camera jump means it stayed pinned to the tag. */
        private const val ANCHOR_FOLLOW_M = 0.10f
        /** Only test for a jump between frames this close together. */
        private const val JUMP_WINDOW_MS = 250L
    }
}
