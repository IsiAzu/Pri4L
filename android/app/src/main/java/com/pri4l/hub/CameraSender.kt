package com.pri4l.hub

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
 * Captures camera frames via CameraX ImageAnalysis and publishes
 * raw RGB to /phone/image at ~2fps. Also publishes camera_info
 * (with real intrinsics from CameraX) and dummy odometry for RTAB-Map sync.
 *
 * Uses 320x240 resolution. Non-AR fallback only.
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

    // Target output resolution (scaled down from whatever CameraX gives us)
    private val targetW = 320
    private val targetH = 240

    // Real intrinsics from CameraX, scaled to target resolution
    private var fxPx = 0.0
    private var fyPx = 0.0
    private var cxPx = 0.0
    private var cyPx = 0.0
    private var intrinsicsReady = false

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

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            // Compute intrinsics once from the actual camera + image resolution
            if (!intrinsicsReady) {
                computeIntrinsics(imageProxy)
            }

            val now = System.currentTimeMillis()
            if (now - lastSendMs >= minIntervalMs && client.state.value == ConnectionState.CONNECTED && intrinsicsReady) {
                lastSendMs = now
                // Republish camera_info every 2s so RTAB-Map picks it up
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

    /**
     * Computes focal length in pixels from CameraX camera info.
     *
     * CameraX provides focal length in mm and sensor size in mm.
     * fx_pixels = focal_mm * image_width / sensor_width_mm
     * fy_pixels = focal_mm * image_height / sensor_height_mm
     */
    private fun computeIntrinsics(imageProxy: ImageProxy) {
        try {
            computeIntrinsicsCamera2(imageProxy)
        } catch (e: Exception) {
            android.util.Log.e("CameraSender", "Camera2 intrinsics failed: ${e.message}", e)
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

            // Compute intrinsics at source resolution
            val srcFx = focalMm * srcW / sensorSize.width
            val srcFy = focalMm * srcH / sensorSize.height

            // Scale to target output resolution
            val scaleX = targetW.toDouble() / srcW
            val scaleY = targetH.toDouble() / srcH
            fxPx = srcFx * scaleX
            fyPx = srcFy * scaleY
            cxPx = targetW / 2.0
            cyPx = targetH / 2.0
            intrinsicsReady = true

            android.util.Log.w("Pri4L",
                "INTRINSICS OK: focal=${focalMm}mm, sensor=${sensorSize.width}x${sensorSize.height}mm, " +
                "src=${srcW.toInt()}x${srcH.toInt()}, target=${targetW}x${targetH}, " +
                "fx=$fxPx, fy=$fyPx, cx=$cxPx, cy=$cyPx")
        } else {
            throw IllegalStateException("Focal lengths or sensor size not available")
        }
    }

    private fun computeIntrinsicsFallback(imageProxy: ImageProxy) {
        // Pixel 9a main camera: 4.53mm focal, 6.4x4.8mm sensor (from Camera2)
        val focalMm = 4.53
        val sensorWidthMm = 6.4
        val sensorHeightMm = 4.8
        val srcW = imageProxy.width.toDouble()
        val srcH = imageProxy.height.toDouble()

        val scaleX = targetW.toDouble() / srcW
        val scaleY = targetH.toDouble() / srcH
        fxPx = (focalMm * srcW / sensorWidthMm) * scaleX
        fyPx = (focalMm * srcH / sensorHeightMm) * scaleY
        cxPx = targetW / 2.0
        cyPx = targetH / 2.0
        intrinsicsReady = true

        android.util.Log.w("Pri4L",
            "INTRINSICS FALLBACK: fx=$fxPx, fy=$fyPx (Pixel 9a hardcoded)")
    }

    /**
     * Publishes camera intrinsics as sensor_msgs/CameraInfo using real
     * focal length from the device camera.
     */
    private fun publishCameraInfo(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("stamp", JSONObject().apply {
                    put("sec", now / 1000)
                    put("nanosec", (now % 1000) * 1_000_000)
                })
                put("frame_id", "phone_camera")
            })
            put("width", targetW)
            put("height", targetH)
            put("distortion_model", "plumb_bob")
            put("d", org.json.JSONArray(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0).toList()))
            // K: 3x3 intrinsic matrix, row-major
            put("k", org.json.JSONArray(listOf(fxPx, 0.0, cxPx, 0.0, fyPx, cyPx, 0.0, 0.0, 1.0)))
            // R: identity rotation
            put("r", org.json.JSONArray(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
            // P: 3x4 projection matrix
            put("p", org.json.JSONArray(listOf(fxPx, 0.0, cxPx, 0.0, 0.0, fyPx, cyPx, 0.0, 0.0, 0.0, 1.0, 0.0)))
        }

        client.publish("/phone/camera_info", "sensor_msgs/msg/CameraInfo", msg)
    }

    private fun publishFrame(imageProxy: ImageProxy) {
        val rgb = imageProxyToRgbScaled(imageProxy) ?: return
        val b64 = Base64.encodeToString(rgb, Base64.NO_WRAP)

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
            put("height", targetH)
            put("width", targetW)
            put("encoding", "rgb8")
            put("is_bigendian", 0)
            put("step", targetW * 3)
            put("data", b64)
        }

        client.publish("/phone/image", "sensor_msgs/msg/Image", msg)
    }

    /**
     * Publishes a dummy odometry message with the same timestamp as the camera frame.
     * RTAB-Map requires an odom input for sync even in localization-only mode.
     * The pose is identity — relocalization overwrites it from image matching.
     */
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
            put("child_frame_id", "phone_camera")
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
        client.publish("/phone/odom", "nav_msgs/msg/Odometry", msg)
    }

    /**
     * Converts YUV_420_888 to RGB and scales to targetW x targetH using
     * nearest-neighbor sampling. This avoids sending full-res frames over websocket.
     */
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
