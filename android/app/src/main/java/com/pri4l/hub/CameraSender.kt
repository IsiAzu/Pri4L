package com.pri4l.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Captures camera frames via CameraX ImageAnalysis and publishes
 * compressed JPEG to /phone/image/compressed at ~5fps.
 *
 * Uses 640x480 resolution, JPEG quality 60.
 */
class CameraSender(
    private val lifecycleOwner: LifecycleOwner,
    private val client: RosbridgeClient
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastSendMs = 0L
    private val minIntervalMs = 200L // ~5fps
    private var lastCameraInfoMs = 0L

    fun start(context: Context) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindAnalysis()
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun bindAnalysis() {
        val provider = cameraProvider ?: return

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            val now = System.currentTimeMillis()
            if (now - lastSendMs >= minIntervalMs && client.state.value == ConnectionState.CONNECTED) {
                lastSendMs = now
                // Republish camera_info every 2s so RTAB-Map picks it up
                if (now - lastCameraInfoMs >= 2000) {
                    publishCameraInfo(imageProxy)
                    lastCameraInfoMs = now
                }
                publishFrame(imageProxy)
            }
            imageProxy.close()
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysis
        )
    }

    /**
     * Publishes camera intrinsics as sensor_msgs/CameraInfo.
     * RTAB-Map needs this to do relocalization. We publish once on start
     * and again periodically (every ~2s) since RTAB-Map may start after the phone.
     *
     * Uses approximate intrinsics from the 640x480 target resolution.
     * Real device calibration will differ — good enough for prototype.
     */
    private fun publishCameraInfo(imageProxy: ImageProxy) {
        val w = imageProxy.width.toDouble()
        val h = imageProxy.height.toDouble()
        // Approximate focal length for a typical phone camera at 640x480
        // ~60° horizontal FOV → fx ≈ w / (2 * tan(30°)) ≈ 554
        val fx = 554.0
        val fy = 554.0
        val cx = w / 2.0
        val cy = h / 2.0

        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "phone_camera")
            })
            put("width", imageProxy.width)
            put("height", imageProxy.height)
            put("distortion_model", "plumb_bob")
            put("d", org.json.JSONArray(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0).toList()))
            // K: 3x3 intrinsic matrix, row-major
            put("k", org.json.JSONArray(listOf(fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)))
            // R: identity rotation
            put("r", org.json.JSONArray(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
            // P: 3x4 projection matrix
            put("p", org.json.JSONArray(listOf(fx, 0.0, cx, 0.0, 0.0, fy, cy, 0.0, 0.0, 0.0, 1.0, 0.0)))
        }

        client.publish("/phone/camera_info", "sensor_msgs/msg/CameraInfo", msg)
    }

    private fun publishFrame(imageProxy: ImageProxy) {
        val jpeg = imageProxyToJpeg(imageProxy) ?: return
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val now = System.currentTimeMillis()
        val sec = now / 1000
        val nanosec = (now % 1000) * 1_000_000

        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", sec)
                    put("nanosec", nanosec)
                })
                put("frame_id", "phone_camera")
            })
            put("format", "jpeg")
            put("data", b64)
        }

        client.publish("/phone/image/compressed", "sensor_msgs/msg/CompressedImage", msg)
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 format: Y + VU interleaved
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 60, out)
        return out.toByteArray()
    }
}
