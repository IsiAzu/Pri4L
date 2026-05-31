package com.pri4l.hub

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderer for glasses mode (no ARCore).
 * Uses sensor-based head rotation (3DoF) to render anchors.
 * No camera background — renders on transparent/black background.
 */
class GlassesRenderer(
    private val tracker: GlassesTracker,
    private val getHubAnchors: () -> List<FloatArray>,
    private val getPhoneAnchors: () -> List<FloatArray>,
    private val getAlignmentMatrix: () -> FloatArray?
) : GLSurfaceView.Renderer {

    private var cubeRenderer = CubeRenderer()
    private var textLabelRenderer = TextLabelRenderer()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val labelModelMatrix = FloatArray(16)
    private val labelMvp = FloatArray(16)
    private val tempMatrix2 = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0.0f)
        cubeRenderer.create()
        textLabelRenderer.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // INMO Air3 waveguide: ~45° diagonal, ~38° vertical
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 38f, aspect, 0.05f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (!tracker.isTracking) return

        tracker.getViewMatrix(viewMatrix)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val alignment = getAlignmentMatrix()
        if (alignment != null) {
            // Hub anchors = blue
            for (anchorPos in getHubAnchors()) {
                drawAnchorCube(anchorPos, alignment, 0.2f, 0.4f, 1.0f)
            }
            // Phone anchors = green
            for (anchorPos in getPhoneAnchors()) {
                drawAnchorCube(anchorPos, alignment, 0.2f, 1.0f, 0.4f)
            }
        }
    }

    private fun drawAnchorCube(anchorPos: FloatArray, alignment: FloatArray, r: Float, g: Float, b: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, anchorPos[0], anchorPos[1], anchorPos[2])
        Matrix.scaleM(modelMatrix, 0, 0.07f, 0.07f, 0.07f)

        // Apply hub-to-glasses alignment
        Matrix.multiplyMM(tempMatrix, 0, alignment, 0, modelMatrix, 0)

        // MVP = projection * view * alignedModel
        Matrix.multiplyMM(modelMatrix, 0, viewMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)

        cubeRenderer.draw(mvpMatrix, r, g, b)

        // Label
        val label = "x:%.2f y:%.2f z:%.2f".format(anchorPos[0], anchorPos[1], anchorPos[2])
        val texId = textLabelRenderer.getOrCreateTexture(label)

        Matrix.setIdentityM(labelModelMatrix, 0)
        Matrix.translateM(labelModelMatrix, 0, anchorPos[0], anchorPos[1] + 0.08f, anchorPos[2])
        Matrix.multiplyMM(tempMatrix2, 0, alignment, 0, labelModelMatrix, 0)

        val lx = tempMatrix2[12]
        val ly = tempMatrix2[13]
        val lz = tempMatrix2[14]

        // Billboard facing camera
        Matrix.setIdentityM(labelModelMatrix, 0)
        labelModelMatrix[0] = viewMatrix[0]; labelModelMatrix[1] = viewMatrix[4]; labelModelMatrix[2] = viewMatrix[8]
        labelModelMatrix[4] = viewMatrix[1]; labelModelMatrix[5] = viewMatrix[5]; labelModelMatrix[6] = viewMatrix[9]
        labelModelMatrix[8] = viewMatrix[2]; labelModelMatrix[9] = viewMatrix[6]; labelModelMatrix[10] = viewMatrix[10]
        labelModelMatrix[12] = lx; labelModelMatrix[13] = ly; labelModelMatrix[14] = lz

        Matrix.scaleM(labelModelMatrix, 0, 0.12f, 0.03f, 1f)

        Matrix.multiplyMM(tempMatrix2, 0, viewMatrix, 0, labelModelMatrix, 0)
        Matrix.multiplyMM(labelMvp, 0, projectionMatrix, 0, tempMatrix2, 0)

        textLabelRenderer.draw(labelMvp, texId)
    }
}
