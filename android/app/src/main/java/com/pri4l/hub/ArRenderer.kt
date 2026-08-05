package com.pri4l.hub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
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
    private val getPhoneAnchors: () -> List<FloatArray> = { emptyList() },
    private val getAlignmentMatrix: () -> FloatArray?,
    private val displayRotation: () -> Int,
    private val onArFrame: ((Frame) -> Unit)? = null
) : GLSurfaceView.Renderer {

    private var backgroundRenderer = BackgroundRenderer()
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

    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.create()
        cubeRenderer.create()
        textLabelRenderer.create()
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

        // Draw the camera feed even while tracking is limited — otherwise the screen goes black
        // on every hiccup. Anchors stay hidden until the pose is trustworthy again.
        backgroundRenderer.draw(frame)
        if (camera.trackingState != TrackingState.TRACKING) return

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

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
        // --- Draw cube ---
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, anchorPos[0], anchorPos[1], anchorPos[2])
        Matrix.scaleM(modelMatrix, 0, 0.07f, 0.07f, 0.07f) // 7cm cube

        // Apply hub-to-ARCore alignment
        Matrix.multiplyMM(tempMatrix, 0, alignment, 0, modelMatrix, 0)

        // MVP = projection * view * alignedModel
        Matrix.multiplyMM(modelMatrix, 0, viewMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)

        cubeRenderer.draw(mvpMatrix, r, g, b)

        // --- Draw coordinate label ---
        val label = "x:%.2f y:%.2f z:%.2f".format(anchorPos[0], anchorPos[1], anchorPos[2])
        val texId = textLabelRenderer.getOrCreateTexture(label)

        // Position at anchor + slight vertical offset (ARCore Y-up)
        Matrix.setIdentityM(labelModelMatrix, 0)
        Matrix.translateM(labelModelMatrix, 0, anchorPos[0], anchorPos[1] + 0.08f, anchorPos[2])

        // Apply alignment to get into ARCore space
        Matrix.multiplyMM(tempMatrix2, 0, alignment, 0, labelModelMatrix, 0)

        // Billboard: replace rotation columns with camera-facing orientation
        val lx = tempMatrix2[12]
        val ly = tempMatrix2[13]
        val lz = tempMatrix2[14]

        // Build billboard from inverse view rotation
        Matrix.setIdentityM(labelModelMatrix, 0)
        labelModelMatrix[0] = viewMatrix[0];  labelModelMatrix[1] = viewMatrix[4];  labelModelMatrix[2] = viewMatrix[8]
        labelModelMatrix[4] = viewMatrix[1];  labelModelMatrix[5] = viewMatrix[5];  labelModelMatrix[6] = viewMatrix[9]
        labelModelMatrix[8] = viewMatrix[2];  labelModelMatrix[9] = viewMatrix[6];  labelModelMatrix[10] = viewMatrix[10]
        labelModelMatrix[12] = lx; labelModelMatrix[13] = ly; labelModelMatrix[14] = lz

        Matrix.scaleM(labelModelMatrix, 0, 0.12f, 0.03f, 1f)

        // MVP = projection * view * labelModel
        Matrix.multiplyMM(tempMatrix2, 0, viewMatrix, 0, labelModelMatrix, 0)
        Matrix.multiplyMM(labelMvp, 0, projectionMatrix, 0, tempMatrix2, 0)

        textLabelRenderer.draw(labelMvp, texId)
    }
}

class TextLabelRenderer {
    private var program = 0
    private var mvpUniform = 0
    private var textureUniform = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0

    private val textureCache = HashMap<String, Int>()

    private val vertexShader = """
        uniform mat4 u_MVP;
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
            gl_Position = u_MVP * a_Position;
            v_TexCoord = a_TexCoord;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform sampler2D u_Texture;
        void main() {
            gl_FragColor = texture2D(u_Texture, v_TexCoord);
        }
    """.trimIndent()

    // Unit quad centered at origin
    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f,   1f, -1f, 0f,   1f, 1f, 0f,
        -1f, -1f, 0f,   1f,  1f, 0f,  -1f, 1f, 0f,
    )
    private val quadTexCoords = floatArrayOf(
        0f, 1f,  1f, 1f,  1f, 0f,
        0f, 1f,  1f, 0f,  0f, 0f,
    )

    private var vertexBuffer: java.nio.FloatBuffer? = null
    private var texCoordBuffer: java.nio.FloatBuffer? = null

    fun create() {
        program = createProgram(vertexShader, fragmentShader)
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")

        vertexBuffer = java.nio.ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadVertices); position(0)
            }
        texCoordBuffer = java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadTexCoords); position(0)
            }
    }

    fun getOrCreateTexture(text: String): Int {
        textureCache[text]?.let { return it }
        val texId = generateLabelTexture(text)
        textureCache[text] = texId
        return texId
    }

    private fun generateLabelTexture(text: String): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val textWidth = paint.measureText(text).toInt() + 16
        val textHeight = 48

        val bitmap = Bitmap.createBitmap(textWidth, textHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xCC000000.toInt()) // semi-transparent black background
        canvas.drawText(text, 8f, 34f, paint)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        return textures[0]
    }

    fun draw(mvpMatrix: FloatArray, textureId: Int) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisable(GLES20.GL_BLEND)
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

    fun draw(mvpMatrix: FloatArray, r: Float = 0.2f, g: Float = 0.8f, b: Float = 0.4f) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorUniform, r, g, b, 1f)
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
