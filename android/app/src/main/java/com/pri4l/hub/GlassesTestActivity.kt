package com.pri4l.hub

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.WindowManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Minimal test: fullscreen spinning cube on INMO glasses.
 * No Compose, no WebSocket, no ARCore. Just GL + sensors.
 */
class GlassesTestActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var tracker: GlassesTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tracker = GlassesTracker(this)
        tracker.start()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(HeadTrackedCubeRenderer(tracker))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContentView(glView)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        tracker.start()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        tracker.stop()
    }
}

class HeadTrackedCubeRenderer(private val tracker: GlassesTracker) : GLSurfaceView.Renderer {

    private var cubeRenderer = CubeRenderer()
    private val mvpMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.3f, 0.0f, 0.0f, 1.0f) // Dark red — obvious
        cubeRenderer.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.1f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Head tracking view matrix
        tracker.getViewMatrix(viewMatrix)

        // Cube 3m forward (negative Z = forward in OpenGL)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -3f)
        Matrix.scaleM(modelMatrix, 0, 0.5f, 0.5f, 0.5f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        cubeRenderer.draw(mvpMatrix, 0.2f, 0.8f, 1.0f) // Cyan

        // Cube to the right
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 2f, 0f, -3f)
        Matrix.scaleM(modelMatrix, 0, 0.3f, 0.3f, 0.3f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        cubeRenderer.draw(mvpMatrix, 1.0f, 0.4f, 0.2f) // Orange

        // Cube above
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 2f, -3f)
        Matrix.scaleM(modelMatrix, 0, 0.3f, 0.3f, 0.3f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        cubeRenderer.draw(mvpMatrix, 0.4f, 1.0f, 0.4f) // Green
    }
}
