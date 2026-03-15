package com.pri4l.hub

import android.opengl.Matrix

/**
 * Computes and maintains the hub-to-ARCore coordinate frame alignment.
 *
 * When the user taps "Align" while standing at the D435, we get two poses:
 *   - hubPose: the D435's known position in the hub (RTAB-Map) frame (typically origin)
 *   - arPose: the phone's current ARCore pose
 *
 * Since the user is at the D435, these represent the same physical location,
 * giving us the rigid transform between the two coordinate frames.
 *
 * The alignment matrix converts hub coordinates to ARCore coordinates:
 *   arPoint = alignmentMatrix * hubPoint
 *
 * The inverse converts ARCore poses to hub coordinates for publishing.
 */
class FrameAlignment {

    // 4x4 column-major matrix: hub frame -> ARCore frame
    var alignmentMatrix: FloatArray? = null
        private set

    // 4x4 column-major matrix: ARCore frame -> hub frame
    var inverseMatrix: FloatArray? = null
        private set

    val isAligned: Boolean
        get() = alignmentMatrix != null

    /**
     * Set alignment from a matched pair of poses (user at D435 position).
     *
     * @param hubPosition  [x, y, z] in hub frame (typically [0,0,0] for D435 at origin)
     * @param hubRotation  [qx, qy, qz, qw] in hub frame (typically identity)
     * @param arPosition   [x, y, z] in ARCore frame
     * @param arRotation   [qx, qy, qz, qw] in ARCore frame
     */
    fun align(
        hubPosition: FloatArray,
        hubRotation: FloatArray,
        arPosition: FloatArray,
        arRotation: FloatArray
    ) {
        // hubMatrix: hub origin -> phone (in hub frame)
        val hubMatrix = FloatArray(16)
        quaternionToMatrix(hubRotation, hubMatrix)
        hubMatrix[12] = hubPosition[0]
        hubMatrix[13] = hubPosition[1]
        hubMatrix[14] = hubPosition[2]

        // arMatrix: ARCore origin -> phone (in ARCore frame)
        val arMatrix = FloatArray(16)
        quaternionToMatrix(arRotation, arMatrix)
        arMatrix[12] = arPosition[0]
        arMatrix[13] = arPosition[1]
        arMatrix[14] = arPosition[2]

        // hubMatrixInv: phone -> hub origin
        val hubMatrixInv = FloatArray(16)
        Matrix.invertM(hubMatrixInv, 0, hubMatrix, 0)

        // alignment = arMatrix * hubMatrixInv
        // Transforms: hub frame point -> phone-relative -> ARCore frame
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, arMatrix, 0, hubMatrixInv, 0)
        alignmentMatrix = result

        // Compute inverse for ARCore -> hub transforms
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, result, 0)
        inverseMatrix = inv
    }

    /**
     * Transform a position from ARCore frame to hub frame.
     * Returns [x, y, z] in hub coordinates, or null if not aligned.
     */
    fun arToHub(arPosition: FloatArray, arRotation: FloatArray): Pair<FloatArray, FloatArray>? {
        val inv = inverseMatrix ?: return null

        // Build ARCore pose matrix
        val arMatrix = FloatArray(16)
        quaternionToMatrix(arRotation, arMatrix)
        arMatrix[12] = arPosition[0]
        arMatrix[13] = arPosition[1]
        arMatrix[14] = arPosition[2]

        // hubPose = inverse * arPose
        val hubMatrix = FloatArray(16)
        Matrix.multiplyMM(hubMatrix, 0, inv, 0, arMatrix, 0)

        val pos = floatArrayOf(hubMatrix[12], hubMatrix[13], hubMatrix[14])
        val rot = matrixToQuaternion(hubMatrix)
        return Pair(pos, rot)
    }

    fun reset() {
        alignmentMatrix = null
        inverseMatrix = null
    }

    private fun quaternionToMatrix(q: FloatArray, m: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        Matrix.setIdentityM(m, 0)
        m[0]  = 1f - 2f * (yy + zz)
        m[1]  = 2f * (xy + wz)
        m[2]  = 2f * (xz - wy)
        m[4]  = 2f * (xy - wz)
        m[5]  = 1f - 2f * (xx + zz)
        m[6]  = 2f * (yz + wx)
        m[8]  = 2f * (xz + wy)
        m[9]  = 2f * (yz - wx)
        m[10] = 1f - 2f * (xx + yy)
    }

    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val trace = m[0] + m[5] + m[10]
        val q = FloatArray(4)
        if (trace > 0) {
            val s = 0.5f / Math.sqrt((trace + 1.0).toDouble()).toFloat()
            q[3] = 0.25f / s
            q[0] = (m[6] - m[9]) * s
            q[1] = (m[8] - m[2]) * s
            q[2] = (m[1] - m[4]) * s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = 2f * Math.sqrt((1.0 + m[0] - m[5] - m[10]).toDouble()).toFloat()
            q[3] = (m[6] - m[9]) / s
            q[0] = 0.25f * s
            q[1] = (m[4] + m[1]) / s
            q[2] = (m[8] + m[2]) / s
        } else if (m[5] > m[10]) {
            val s = 2f * Math.sqrt((1.0 + m[5] - m[0] - m[10]).toDouble()).toFloat()
            q[3] = (m[8] - m[2]) / s
            q[0] = (m[4] + m[1]) / s
            q[1] = 0.25f * s
            q[2] = (m[9] + m[6]) / s
        } else {
            val s = 2f * Math.sqrt((1.0 + m[10] - m[0] - m[5]).toDouble()).toFloat()
            q[3] = (m[1] - m[4]) / s
            q[0] = (m[8] + m[2]) / s
            q[1] = (m[9] + m[6]) / s
            q[2] = 0.25f * s
        }
        return q // [x, y, z, w]
    }
}
