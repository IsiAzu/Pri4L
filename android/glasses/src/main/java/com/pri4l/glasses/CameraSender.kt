package com.pri4l.glasses

import android.content.Context
import android.graphics.ImageFormat
import android.util.Base64
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Captures the glasses' world camera via CameraX ImageAnalysis and publishes raw RGB to
 * /glasses/image at ~1.3fps, plus camera_info (real intrinsics) and dummy odom for RTAB-Map
 * sync. 320x240. Ported from the phone app's CameraSender.
 *
 * NOTE: uses DEFAULT_BACK_CAMERA — on the INMO the world-facing camera should bind here;
 * verify on-device and switch the selector if it grabs the wrong one.
 */
class CameraSender(
    private val lifecycleOwner: LifecycleOwner,
    private val client: RosbridgeClient
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastSendMs = 0L
    private val minIntervalMs = 750L // ~1.3fps
    private var lastCameraInfoMs = 0L

    private val targetW = 320
    private val targetH = 240

    private var fxPx = 0.0
    private var fyPx = 0.0
    private var cxPx = 0.0
    private var cyPx = 0.0
    private var intrinsicsReady = false

    /**
     * Optional per-frame tap (with current intrinsics) for on-device processing such as
     * fiducial detection. Runs on the analyzer thread, before the frame is closed, on every
     * analyzed frame (not gated by the ~1.3fps send throttle). Keep it fast.
     */
    var onFrame: ((ImageProxy, Double, Double, Double, Double) -> Unit)? = null

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
        camera = null
        intrinsicsReady = false
    }

    private fun bindAnalysis() {
        val provider = cameraProvider ?: return

        // 640x480 analyzer frames so the ArUco detector has enough marker pixels; streaming
        // still downscales to 320x240 in imageProxyToRgbScaled (the aligner scales intrinsics).
        // 640x480 analyzer frames so ArUco has enough marker pixels; default auto-exposure.
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            if (!intrinsicsReady) computeIntrinsics(imageProxy)

            // Per-frame tap (fiducial detection) — runs every frame, independent of streaming.
            if (intrinsicsReady) onFrame?.invoke(imageProxy, fxPx, fyPx, cxPx, cyPx)

            val now = System.currentTimeMillis()
            if (now - lastSendMs >= minIntervalMs && client.state.value == ConnectionState.CONNECTED && intrinsicsReady) {
                lastSendMs = now
                if (now - lastCameraInfoMs >= 2000) {
                    publishCameraInfo(imageProxy)
                    lastCameraInfoMs = now
                }
                publishFrame(imageProxy)
                publishDummyOdom()
            }
            imageProxy.close()
        }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysis
        )
    }

    private fun computeIntrinsics(imageProxy: ImageProxy) {
        try {
            computeIntrinsicsCamera2(imageProxy)
        } catch (e: Exception) {
            android.util.Log.e("Pri4L", "Camera2 intrinsics failed: ${e.message}", e)
            computeIntrinsicsFallback(imageProxy)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun computeIntrinsicsCamera2(imageProxy: ImageProxy) {
        val cam = camera ?: throw IllegalStateException("Camera not bound yet")
        val cameraInfo = cam.cameraInfo
        val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
        val focalLengths = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )
        val sensorSize = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        )

        if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
            val focalMm = focalLengths[0]
            val srcW = imageProxy.width.toDouble()
            val srcH = imageProxy.height.toDouble()

            val srcFx = focalMm * srcW / sensorSize.width
            val srcFy = focalMm * srcH / sensorSize.height

            val scaleX = targetW.toDouble() / srcW
            val scaleY = targetH.toDouble() / srcH
            fxPx = srcFx * scaleX
            fyPx = srcFy * scaleY
            cxPx = targetW / 2.0
            cyPx = targetH / 2.0
            intrinsicsReady = true

            android.util.Log.w("Pri4L",
                "INTRINSICS OK: focal=${focalMm}mm, sensor=${sensorSize.width}x${sensorSize.height}mm, " +
                "src=${srcW.toInt()}x${srcH.toInt()}, target=${targetW}x${targetH}, fx=$fxPx, fy=$fyPx")
        } else {
            throw IllegalStateException("Focal lengths or sensor size not available")
        }
    }

    private fun computeIntrinsicsFallback(imageProxy: ImageProxy) {
        // Generic ~70° HFOV guess, refined on-device once Camera2 intrinsics are confirmed.
        val srcW = imageProxy.width.toDouble()
        val srcH = imageProxy.height.toDouble()
        val approxFx = srcW * 0.8
        val scaleX = targetW.toDouble() / srcW
        val scaleY = targetH.toDouble() / srcH
        fxPx = approxFx * scaleX
        fyPx = approxFx * scaleY
        cxPx = targetW / 2.0
        cyPx = targetH / 2.0
        intrinsicsReady = true
        android.util.Log.w("Pri4L", "INTRINSICS FALLBACK (approx): fx=$fxPx, fy=$fyPx")
    }

    private fun publishCameraInfo(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "glasses_camera")
            })
            put("width", targetW)
            put("height", targetH)
            put("distortion_model", "plumb_bob")
            put("d", org.json.JSONArray(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0).toList()))
            put("k", org.json.JSONArray(listOf(fxPx, 0.0, cxPx, 0.0, fyPx, cyPx, 0.0, 0.0, 1.0)))
            put("r", org.json.JSONArray(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
            put("p", org.json.JSONArray(listOf(fxPx, 0.0, cxPx, 0.0, 0.0, fyPx, cyPx, 0.0, 0.0, 0.0, 1.0, 0.0)))
        }
        client.publish("/glasses/camera_info", "sensor_msgs/msg/CameraInfo", msg)
    }

    private fun publishFrame(imageProxy: ImageProxy) {
        val rgb = imageProxyToRgbScaled(imageProxy) ?: return
        val b64 = Base64.encodeToString(rgb, Base64.NO_WRAP)

        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "glasses_camera")
            })
            put("height", targetH)
            put("width", targetW)
            put("encoding", "rgb8")
            put("is_bigendian", 0)
            put("step", targetW * 3)
            put("data", b64)
        }
        client.publish("/glasses/image", "sensor_msgs/msg/Image", msg)
    }

    private fun publishDummyOdom() {
        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "odom")
            })
            put("child_frame_id", "glasses_camera")
            put("pose", JSONObject().apply {
                put("pose", JSONObject().apply {
                    put("position", JSONObject().apply {
                        put("x", 0.0); put("y", 0.0); put("z", 0.0)
                    })
                    put("orientation", JSONObject().apply {
                        put("x", 0.0); put("y", 0.0); put("z", 0.0); put("w", 1.0)
                    })
                })
            })
        }
        client.publish("/glasses/odom", "nav_msgs/msg/Odometry", msg)
    }

    private fun imageProxyToRgbScaled(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null

        val srcW = imageProxy.width
        val srcH = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val rgb = ByteArray(targetW * targetH * 3)
        var idx = 0
        for (dstRow in 0 until targetH) {
            val srcRow = dstRow * srcH / targetH
            for (dstCol in 0 until targetW) {
                val srcCol = dstCol * srcW / targetW
                val y = (yBuf.get(srcRow * yRowStride + srcCol).toInt() and 0xFF)
                val uvRow = srcRow / 2
                val uvCol = srcCol / 2
                val u = (uBuf.get(uvRow * uvRowStride + uvCol * uvPixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvRow * uvRowStride + uvCol * uvPixelStride).toInt() and 0xFF) - 128

                rgb[idx++] = (y + 1.370705 * v).toInt().coerceIn(0, 255).toByte()
                rgb[idx++] = (y - 0.337633 * u - 0.698001 * v).toInt().coerceIn(0, 255).toByte()
                rgb[idx++] = (y + 1.732446 * u).toInt().coerceIn(0, 255).toByte()
            }
        }
        return rgb
    }
}
