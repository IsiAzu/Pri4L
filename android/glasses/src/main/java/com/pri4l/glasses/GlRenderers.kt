package com.pri4l.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils

/** Solid-color cube (unit half-extent). Ported from the phone app's ArRenderer. */
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

/** Renders text as a textured quad (billboard). Ported from the phone app's ArRenderer. */
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
            typeface = Typeface.MONOSPACE
        }
        val textWidth = paint.measureText(text).toInt() + 16
        val textHeight = 48

        val bitmap = Bitmap.createBitmap(textWidth, textHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xCC000000.toInt())
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
