package com.pri4l.hub

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the AR camera background and hub-placed anchors.
 *
 * Anchor positions arrive in hub (RTAB-Map) coordinates.
 * The hub-to-ARCore alignment transform converts them to ARCore world space.
 */
class ArRenderer(
    private val sessionProvider: () -> Session?,
    private val getHubAnchors: () -> List<FloatArray>,
    private val getAlignmentMatrix: () -> FloatArray?,
    private val displayRotation: () -> Int,
    private val onArFrame: ((Frame) -> Unit)? = null
) : GLSurfaceView.Renderer {

    private var backgroundRenderer = BackgroundRenderer()
    private var cubeRenderer = CubeRenderer()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.create()
        cubeRenderer.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = sessionProvider() ?: return
        val frame: Frame
        try {
            session.setCameraTextureName(backgroundRenderer.textureId)
            // Tell ARCore about the display orientation so UV coords are correct
            session.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            frame = session.update()
        } catch (e: Exception) {
            return
        }

        onArFrame?.invoke(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        backgroundRenderer.draw(frame)

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val alignment = getAlignmentMatrix()
        val anchors = getHubAnchors()
        if (alignment != null) {
            for (anchorPos in anchors) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, anchorPos[0], anchorPos[1], anchorPos[2])
                Matrix.scaleM(modelMatrix, 0, 0.05f, 0.05f, 0.05f) // 5cm cube

                // Apply hub-to-ARCore alignment
                Matrix.multiplyMM(tempMatrix, 0, alignment, 0, modelMatrix, 0)

                // MVP = projection * view * alignedModel
                Matrix.multiplyMM(modelMatrix, 0, viewMatrix, 0, tempMatrix, 0)
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)

                cubeRenderer.draw(mvpMatrix)
            }
        }
    }
}

class BackgroundRenderer {
    var textureId: Int = 0
        private set

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0

    private val vertexShader = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
            gl_Position = a_Position;
            v_TexCoord = a_TexCoord;
        }
    """.trimIndent()

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES u_Texture;
        void main() {
            gl_FragColor = texture2D(u_Texture, v_TexCoord);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f,  -1f, +1f, 0f,  +1f, -1f, 0f,  +1f, +1f, 0f
    )
    private val quadTexCoords = floatArrayOf(
        0f, 1f,  0f, 0f,  1f, 1f,  1f, 0f
    )

    fun create() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = createProgram(vertexShader, fragmentShader)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformDisplayUvCoords(
                java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadTexCoords); position(0) },
                java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().also { transformedBuf = it }
            )
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        val vertBuf = java.nio.ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadVertices); position(0) }

        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0,
            transformedBuf ?: java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadTexCoords); position(0) })
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDepthMask(true)
    }

    private var transformedBuf: java.nio.FloatBuffer? = null
}

class CubeRenderer {
    private var program = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var positionAttrib = 0

    private val vertexShader = """
        uniform mat4 u_MVP;
        attribute vec4 a_Position;
        void main() {
            gl_Position = u_MVP * a_Position;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform vec4 u_Color;
        void main() {
            gl_FragColor = u_Color;
        }
    """.trimIndent()

    private val cubeVertices = floatArrayOf(
        -1f, -1f,  1f,   1f, -1f,  1f,   1f,  1f,  1f,
        -1f, -1f,  1f,   1f,  1f,  1f,  -1f,  1f,  1f,
        -1f, -1f, -1f,  -1f,  1f, -1f,   1f,  1f, -1f,
        -1f, -1f, -1f,   1f,  1f, -1f,   1f, -1f, -1f,
        -1f, -1f, -1f,  -1f, -1f,  1f,  -1f,  1f,  1f,
        -1f, -1f, -1f,  -1f,  1f,  1f,  -1f,  1f, -1f,
         1f, -1f, -1f,   1f,  1f, -1f,   1f,  1f,  1f,
         1f, -1f, -1f,   1f,  1f,  1f,   1f, -1f,  1f,
        -1f,  1f, -1f,  -1f,  1f,  1f,   1f,  1f,  1f,
        -1f,  1f, -1f,   1f,  1f,  1f,   1f,  1f, -1f,
        -1f, -1f, -1f,   1f, -1f, -1f,   1f, -1f,  1f,
        -1f, -1f, -1f,   1f, -1f,  1f,  -1f, -1f,  1f,
    )

    private var vertexBuffer: java.nio.FloatBuffer? = null

    fun create() {
        program = createProgram(vertexShader, fragmentShader)
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")

        vertexBuffer = java.nio.ByteBuffer.allocateDirect(cubeVertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(cubeVertices)
                position(0)
            }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorUniform, 0.2f, 0.8f, 0.4f, 1f)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }
}

fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    return program
}

fun loadShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    return shader
}
