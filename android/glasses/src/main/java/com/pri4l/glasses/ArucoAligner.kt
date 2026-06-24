package com.pri4l.glasses

import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.Objdetect

/**
 * On-device fiducial alignment (decision 010). Detects a single ArUco marker (DICT_4X4_50,
 * id [TARGET_ID]) placed at the hub map origin and solves the glasses' 6DoF pose in the hub
 * frame. Because the tag IS the hub origin, glasses-in-hub = inverse of the detected
 * tag-in-camera pose.
 *
 * 3DoF caveat: this is a one-shot 6DoF fix valid at the detection instant. With no positional
 * tracking afterward, the translation only stays correct while the wearer rotates in place.
 *
 * Feed frames via [process] from the camera analyzer thread; read [hubFromCam] (column-major
 * GL 4x4, world=hub -> ... actually camera-in-hub) and [aligned] from the render thread.
 */
class ArucoAligner {

    private val detector = ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50))

    @Volatile var aligned = false
        private set
    @Volatile var lastSeenMs = 0L
        private set
    @Volatile var rangeM = 0f
        private set
    /** Camera (glasses) position in the hub frame, metres. */
    val camPosHub = FloatArray(3)
    /** Camera-in-hub pose, column-major GL 4x4 (rotation R^T, translation -R^T t). */
    val hubFromCam = FloatArray(16)

    private var lastProcMs = 0L
    private var diagThrottle = 0

    // Reused OpenCV scratch.
    private val objPoints = MatOfPoint3f()
    private val camMatrix = Mat(3, 3, CvType.CV_64F)
    private val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
    private val rvec = Mat()
    private val tvec = Mat()
    private val rMat = Mat()

    /**
     * Detect + solve. [fx320]/[fy320]/[cx320]/[cy320] are intrinsics at the 320x240 stream
     * target; we rescale them to the actual frame resolution ArUco sees.
     * Returns true if the marker was found this frame.
     */
    fun process(image: ImageProxy, fx320: Double, fy320: Double, cx320: Double, cy320: Double): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProcMs < MIN_INTERVAL_MS) return false
        lastProcMs = now

        val w = image.width
        val h = image.height
        val gray = grayFromY(image) ?: return false

        val corners = ArrayList<Mat>()
        val ids = Mat()
        try {
            detector.detectMarkers(gray, corners, ids)
            val n = if (ids.empty()) 0 else ids.rows()
            if (++diagThrottle % 8 == 0) {
                val seen = if (n == 0) "none" else (0 until n).map { ids.get(it, 0)[0].toInt() }.joinToString(",")
                val meanY = org.opencv.core.Core.mean(gray).`val`[0]
                Log.w(TAG, "aruco: frame ${w}x${h} meanY=%.1f markers=%d ids=[%s]".format(meanY, n, seen))
            }
            if (ids.empty()) { aligned = false; return false }

            var found = false
            for (i in 0 until ids.rows()) {
                if (ids.get(i, 0)[0].toInt() != TARGET_ID) continue
                found = solveForCorner(corners[i], w, h, fx320, fy320, cx320, cy320)
                break
            }
            return found
        } catch (e: Throwable) {
            Log.e(TAG, "aruco detect/solve failed", e)
            return false
        } finally {
            gray.release(); ids.release(); corners.forEach { it.release() }
        }
    }

    private fun solveForCorner(
        corner: Mat, w: Int, h: Int,
        fx320: Double, fy320: Double, cx320: Double, cy320: Double
    ): Boolean {
        // Rescale 320x240-based intrinsics to the actual frame size.
        val sx = w / 320.0
        val sy = h / 240.0
        camMatrix.put(0, 0, fx320 * sx, 0.0, w / 2.0, 0.0, fy320 * sy, h / 2.0, 0.0, 0.0, 1.0)

        val L = MARKER_SIZE_M.toDouble()
        objPoints.fromArray(
            Point3(-L / 2, L / 2, 0.0), Point3(L / 2, L / 2, 0.0),
            Point3(L / 2, -L / 2, 0.0), Point3(-L / 2, -L / 2, 0.0)
        )
        val img = MatOfPoint2f()
        val pts = ArrayList<Point>(4)
        for (k in 0 until 4) { val v = corner.get(0, k); pts.add(Point(v[0], v[1])) }
        img.fromList(pts)

        val ok = Calib3d.solvePnP(objPoints, img, camMatrix, distCoeffs, rvec, tvec)
        img.release()
        if (!ok) return false

        // tvec = tag origin in camera coords; R (Rodrigues) = tag->camera rotation.
        Calib3d.Rodrigues(rvec, rMat)
        val r = DoubleArray(9); rMat.get(0, 0, r)            // row-major 3x3
        val t = DoubleArray(3); tvec.get(0, 0, t)

        // Camera-in-hub (tag=hub): R_hub_cam = R^T ; pos = -R^T t.
        val px = -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2])
        val py = -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2])
        val pz = -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2])

        synchronized(hubFromCam) {
            // Column-major GL: columns are R^T rows; translation = pos.
            hubFromCam[0] = r[0].toFloat(); hubFromCam[1] = r[1].toFloat(); hubFromCam[2] = r[2].toFloat(); hubFromCam[3] = 0f
            hubFromCam[4] = r[3].toFloat(); hubFromCam[5] = r[4].toFloat(); hubFromCam[6] = r[5].toFloat(); hubFromCam[7] = 0f
            hubFromCam[8] = r[6].toFloat(); hubFromCam[9] = r[7].toFloat(); hubFromCam[10] = r[8].toFloat(); hubFromCam[11] = 0f
            hubFromCam[12] = px.toFloat(); hubFromCam[13] = py.toFloat(); hubFromCam[14] = pz.toFloat(); hubFromCam[15] = 1f
        }
        camPosHub[0] = px.toFloat(); camPosHub[1] = py.toFloat(); camPosHub[2] = pz.toFloat()
        rangeM = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]).toFloat()
        lastSeenMs = System.currentTimeMillis()
        aligned = true

        Log.w(TAG, "ALIGNED: range=%.2fm camPosHub=[%.2f, %.2f, %.2f]".format(rangeM, px, py, pz))
        return true
    }

    /** Build a single-channel grayscale Mat from the ImageProxy's Y plane. */
    private fun grayFromY(image: ImageProxy): Mat? {
        if (image.planes.isEmpty()) return null
        val y = image.planes[0]
        val buf = y.buffer
        val w = image.width
        val h = image.height
        val rowStride = y.rowStride
        val gray = Mat(h, w, CvType.CV_8UC1)
        if (rowStride == w) {
            val data = ByteArray(w * h)
            buf.get(data)
            gray.put(0, 0, data)
        } else {
            val row = ByteArray(w)
            for (r in 0 until h) {
                buf.position(r * rowStride)
                buf.get(row, 0, w)
                gray.put(r, 0, row)
            }
        }
        return gray
    }

    companion object {
        private const val TAG = "Pri4L"
        private const val TARGET_ID = 0
        private const val MIN_INTERVAL_MS = 150L      // ~6.6 Hz detection cap
        /** Printed black-square width in metres. MUST match the physical print. */
        const val MARKER_SIZE_M = 0.15f
    }
}
