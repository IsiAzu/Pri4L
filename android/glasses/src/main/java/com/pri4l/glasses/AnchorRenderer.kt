package com.pri4l.glasses

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders hub/phone anchors as labeled cubes, oriented by the 3DoF head tracker.
 *
 * The INMO is orientation-only (no position), so anchors cannot be placed at their true
 * room coordinates — instead the hub map origin is pinned a fixed distance in front of the
 * wearer and anchors are laid out relative to it, body-stabilized: as you look around they
 * stay put in space (rotating opposite head motion), which reads as "anchored in the room"
 * for a head-locked viewer. Use the tracker's recenter to align "forward" with the hub.
 *
 * Coordinate mapping ROS map frame (x fwd, y left, z up) -> GL eye (x right, y up, z back):
 *   GL.x = -ROS.y ;  GL.y = ROS.z ;  GL.z = -ROS.x
 * plus [originForward] metres pushed ahead so anchors near the hub origin are visible.
 */
class AnchorRenderer(
    private val tracker: HeadTracker,
    private val getHubAnchors: () -> List<FloatArray>,
    private val getPhoneAnchors: () -> List<FloatArray>
) : GLSurfaceView.Renderer {

    private val cube = CubeRenderer()
    private val label = TextLabelRenderer()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val labelModel = FloatArray(16)
    private val labelMvp = FloatArray(16)

    /** Metres the hub map origin sits ahead of the wearer (so origin-adjacent anchors show). */
    private val originForward = 2.0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f) // transparent — waveguide shows the world through
        cube.create()
        label.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        // INMO Air3 waveguide: ~38° vertical FOV.
        Matrix.perspectiveM(projectionMatrix, 0, 38f, aspect, 0.05f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!tracker.isTracking) return

        tracker.getViewMatrix(viewMatrix)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        for (a in getHubAnchors()) drawAnchor(a, 0.2f, 0.4f, 1.0f)    // hub = blue
        for (a in getPhoneAnchors()) drawAnchor(a, 0.2f, 1.0f, 0.4f) // phone = green
    }

    private fun drawAnchor(rosPos: FloatArray, r: Float, g: Float, b: Float) {
        val gx = -rosPos[1]
        val gy = rosPos[2]
        val gz = -rosPos[0] - originForward

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, gx, gy, gz)
        Matrix.scaleM(modelMatrix, 0, 0.07f, 0.07f, 0.07f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)
        cube.draw(mvpMatrix, r, g, b)

        // Billboarded coordinate label above the cube.
        val text = "x:%.2f y:%.2f z:%.2f".format(rosPos[0], rosPos[1], rosPos[2])
        val texId = label.getOrCreateTexture(text)

        Matrix.setIdentityM(labelModel, 0)
        // Billboard: copy the view rotation transpose into the model's upper 3x3.
        labelModel[0] = viewMatrix[0]; labelModel[1] = viewMatrix[4]; labelModel[2] = viewMatrix[8]
        labelModel[4] = viewMatrix[1]; labelModel[5] = viewMatrix[5]; labelModel[6] = viewMatrix[9]
        labelModel[8] = viewMatrix[2]; labelModel[9] = viewMatrix[6]; labelModel[10] = viewMatrix[10]
        labelModel[12] = gx; labelModel[13] = gy + 0.08f; labelModel[14] = gz
        Matrix.scaleM(labelModel, 0, 0.12f, 0.03f, 1f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, labelModel, 0)
        Matrix.multiplyMM(labelMvp, 0, projectionMatrix, 0, tempMatrix, 0)
        label.draw(labelMvp, texId)
    }
}
