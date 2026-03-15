package com.pri4l.hub

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Base64
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Captures frames from an active ARCore session and publishes them
 * to the hub via rosbridge. Also publishes the phone's pose in hub
 * coordinates (after alignment) so the hub knows where the phone is.
 */
class ArCameraBridge(
    private val client: RosbridgeClient,
    private val frameAlignment: FrameAlignment
) {
    private var lastSendMs = 0L
    private val minIntervalMs = 200L // ~5fps
    private var lastCameraInfoMs = 0L
    private var lastPoseMs = 0L

    /**
     * Called from ArRenderer.onDrawFrame via the onArFrame callback.
     * Runs on the GL thread.
     */
    fun onFrame(frame: Frame) {
        if (client.state.value != ConnectionState.CONNECTED) return
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val now = System.currentTimeMillis()

        // Publish phone pose in hub frame at ~10Hz (independent of image rate)
        if (frameAlignment.isAligned && now - lastPoseMs >= 100) {
            lastPoseMs = now
            publishPoseInHubFrame(frame)
        }

        if (now - lastSendMs < minIntervalMs) return
        lastSendMs = now

        if (now - lastCameraInfoMs >= 2000) {
            publishCameraInfo(frame)
            lastCameraInfoMs = now
        }

        publishFrame(frame)
    }

    /**
     * Returns the current ARCore pose as [x, y, z] and [qx, qy, qz, qw].
     */
    fun getCurrentArPose(frame: Frame): Pair<FloatArray, FloatArray>? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val pose = camera.pose
        return Pair(
            floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
            floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw())
        )
    }

    private fun publishPoseInHubFrame(frame: Frame) {
        val arPose = getCurrentArPose(frame) ?: return
        val hubPose = frameAlignment.arToHub(arPose.first, arPose.second) ?: return

        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "map")
            })
            put("pose", JSONObject().apply {
                put("position", JSONObject().apply {
                    put("x", hubPose.first[0].toDouble())
                    put("y", hubPose.first[1].toDouble())
                    put("z", hubPose.first[2].toDouble())
                })
                put("orientation", JSONObject().apply {
                    put("x", hubPose.second[0].toDouble())
                    put("y", hubPose.second[1].toDouble())
                    put("z", hubPose.second[2].toDouble())
                    put("w", hubPose.second[3].toDouble())
                })
            })
        }

        client.publish("/phone/pose", "geometry_msgs/msg/PoseStamped", msg)
    }

    private fun publishCameraInfo(frame: Frame) {
        val intrinsics = frame.camera.imageIntrinsics
        val focalLength = intrinsics.focalLength
        val principalPoint = intrinsics.principalPoint
        val dims = intrinsics.imageDimensions

        val fx = focalLength[0].toDouble()
        val fy = focalLength[1].toDouble()
        val cx = principalPoint[0].toDouble()
        val cy = principalPoint[1].toDouble()

        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "phone_camera")
            })
            put("width", dims[0])
            put("height", dims[1])
            put("distortion_model", "plumb_bob")
            put("d", org.json.JSONArray(listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
            put("k", org.json.JSONArray(listOf(fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)))
            put("r", org.json.JSONArray(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
            put("p", org.json.JSONArray(listOf(fx, 0.0, cx, 0.0, 0.0, fy, cy, 0.0, 0.0, 0.0, 1.0, 0.0)))
        }

        client.publish("/phone/camera_info", "sensor_msgs/msg/CameraInfo", msg)
    }

    private fun publishFrame(frame: Frame) {
        val image: Image
        try {
            image = frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        }

        try {
            val jpeg = imageToJpeg(image) ?: return
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

            val now = System.currentTimeMillis()
            val msg = JSONObject().apply {
                put("header", JSONObject().apply {
                    put("stamp", JSONObject().apply {
                        put("sec", now / 1000)
                        put("nanosec", (now % 1000) * 1_000_000)
                    })
                    put("frame_id", "phone_camera")
                })
                put("format", "jpeg")
                put("data", b64)
            }

            client.publish("/phone/image/compressed", "sensor_msgs/msg/CompressedImage", msg)
        } finally {
            image.close()
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out)
        return out.toByteArray()
    }
}
