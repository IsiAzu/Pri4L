package com.pri4l.hub

import android.media.Image
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
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
 * Detects an ArUco marker (DICT_4X4_50, id [TARGET_ID]) in the ARCore camera image and returns
 * the marker's pose in the ARCore world frame. Used to align ARCore to the hub map frame: the
 * tag is placed at the hub origin, so feeding the tag's AR-world pose to
 * [FrameAlignment.align] (with hub pose = origin) links the two frames automatically — no need
 * to physically stand the phone at the D435 (decision 009's manual flow).
 *
 * Coordinate handling: solvePnP yields the tag pose in the OpenCV camera frame (x-right, y-down,
 * z-forward). ARCore's camera frame is x-right, y-up, z-backward, so we flip Y and Z
 * (C = diag(1,-1,-1)) before composing with the ARCore camera pose.
 */
class FiducialAligner {

    private val detector = ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50))

    private val camMatrix = Mat(3, 3, CvType.CV_64F)
    private val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
    private val objPoints = MatOfPoint3f()
    private val rvec = Mat()
    private val tvec = Mat()
    private val rMat = Mat()

    private val tArcamTag = FloatArray(16)
    private val camPose = FloatArray(16)
    private val tagWorld = FloatArray(16)

    @Volatile var lastRangeM = 0f
        private set

    /**
     * @return the tag pose in ARCore world as ([x,y,z], [qx,qy,qz,qw]), or null if no marker /
     *         not tracking / image unavailable.
     */
    fun detectTagInArWorld(frame: Frame): Pair<FloatArray, FloatArray>? {
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return null

        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return null
        }
        try {
            val gray = grayFromY(image) ?: return null

            val corners = ArrayList<Mat>()
            val ids = Mat()
            try {
                detector.detectMarkers(gray, corners, ids)
                if (ids.empty()) return null

                var idx = -1
                for (i in 0 until ids.rows()) {
                    if (ids.get(i, 0)[0].toInt() == TARGET_ID) { idx = i; break }
                }
                if (idx < 0) return null

                // Intrinsics from ARCore, matching the sensor-native acquired image.
                val intr = cam.imageIntrinsics
                val fl = intr.focalLength
                val pp = intr.principalPoint
                camMatrix.put(0, 0, fl[0].toDouble(), 0.0, pp[0].toDouble(),
                                    0.0, fl[1].toDouble(), pp[1].toDouble(),
                                    0.0, 0.0, 1.0)

                val L = MARKER_SIZE_M.toDouble()
                objPoints.fromArray(
                    Point3(-L / 2, L / 2, 0.0), Point3(L / 2, L / 2, 0.0),
                    Point3(L / 2, -L / 2, 0.0), Point3(-L / 2, -L / 2, 0.0)
                )
                val img = MatOfPoint2f()
                val pts = ArrayList<Point>(4)
                val c = corners[idx]
                for (k in 0 until 4) { val v = c.get(0, k); pts.add(Point(v[0], v[1])) }
                img.fromList(pts)

                val ok = Calib3d.solvePnP(objPoints, img, camMatrix, distCoeffs, rvec, tvec)
                img.release()
                if (!ok) return null

                Calib3d.Rodrigues(rvec, rMat)
                val r = DoubleArray(9); rMat.get(0, 0, r)   // row-major tag->cv-cam
                val t = DoubleArray(3); tvec.get(0, 0, t)
                lastRangeM = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]).toFloat()

                // C = diag(1,-1,-1): OpenCV cam -> ARCore cam (negate rows 1,2 of R and t1,t2).
                // T_arcam_tag column-major.
                tArcamTag[0] = r[0].toFloat(); tArcamTag[1] = (-r[3]).toFloat(); tArcamTag[2] = (-r[6]).toFloat(); tArcamTag[3] = 0f
                tArcamTag[4] = r[1].toFloat(); tArcamTag[5] = (-r[4]).toFloat(); tArcamTag[6] = (-r[7]).toFloat(); tArcamTag[7] = 0f
                tArcamTag[8] = r[2].toFloat(); tArcamTag[9] = (-r[5]).toFloat(); tArcamTag[10] = (-r[8]).toFloat(); tArcamTag[11] = 0f
                tArcamTag[12] = t[0].toFloat(); tArcamTag[13] = (-t[1]).toFloat(); tArcamTag[14] = (-t[2]).toFloat(); tArcamTag[15] = 1f

                // tagWorld = cameraPose(world<-arcam) * T_arcam_tag
                cam.pose.toMatrix(camPose, 0)
                Matrix.multiplyMM(tagWorld, 0, camPose, 0, tArcamTag, 0)

                val pos = floatArrayOf(tagWorld[12], tagWorld[13], tagWorld[14])
                val rot = matrixToQuaternion(tagWorld)
                Log.w("Pri4L", "FIDUCIAL: range=%.2fm tagWorld=[%.2f, %.2f, %.2f]"
                    .format(lastRangeM, pos[0], pos[1], pos[2]))
                return Pair(pos, rot)
            } finally {
                gray.release(); ids.release(); corners.forEach { it.release() }
            }
        } catch (e: Throwable) {
            Log.e("Pri4L", "fiducial detect failed", e)
            return null
        } finally {
            image.close()
        }
    }

    private fun grayFromY(image: Image): Mat? {
        if (image.planes.isEmpty()) return null
        val y = image.planes[0]
        val buf = y.buffer
        val w = image.width
        val h = image.height
        val gray = Mat(h, w, CvType.CV_8UC1)
        val stride = y.rowStride
        if (stride == w) {
            val data = ByteArray(w * h); buf.get(data); gray.put(0, 0, data)
        } else {
            val row = ByteArray(w)
            for (r in 0 until h) { buf.position(r * stride); buf.get(row, 0, w); gray.put(r, 0, row) }
        }
        return gray
    }

    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val trace = m[0] + m[5] + m[10]
        val q = FloatArray(4)
        if (trace > 0) {
            val s = 0.5f / Math.sqrt((trace + 1.0).toDouble()).toFloat()
            q[3] = 0.25f / s; q[0] = (m[6] - m[9]) * s; q[1] = (m[8] - m[2]) * s; q[2] = (m[1] - m[4]) * s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = 2f * Math.sqrt((1.0 + m[0] - m[5] - m[10]).toDouble()).toFloat()
            q[3] = (m[6] - m[9]) / s; q[0] = 0.25f * s; q[1] = (m[4] + m[1]) / s; q[2] = (m[8] + m[2]) / s
        } else if (m[5] > m[10]) {
            val s = 2f * Math.sqrt((1.0 + m[5] - m[0] - m[10]).toDouble()).toFloat()
            q[3] = (m[8] - m[2]) / s; q[0] = (m[4] + m[1]) / s; q[1] = 0.25f * s; q[2] = (m[9] + m[6]) / s
        } else {
            val s = 2f * Math.sqrt((1.0 + m[10] - m[0] - m[5]).toDouble()).toFloat()
            q[3] = (m[1] - m[4]) / s; q[0] = (m[8] + m[2]) / s; q[1] = (m[9] + m[6]) / s; q[2] = 0.25f * s
        }
        return q
    }

    companion object {
        private const val TARGET_ID = 0
        /** Printed black-square width in metres. MUST match the physical print. */
        const val MARKER_SIZE_M = 0.156f
    }
}
