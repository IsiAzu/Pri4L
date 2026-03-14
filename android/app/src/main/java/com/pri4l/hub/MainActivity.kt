package com.pri4l.hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val rosbridge = RosbridgeClient()
    private lateinit var cameraSender: CameraSender
    private lateinit var imuSender: ImuSender

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraSender.start(this)
            cameraRunning.value = true
        }
    }

    private val imuRunning = mutableStateOf(false)
    private val cameraRunning = mutableStateOf(false)
    private val pose = mutableStateOf(PoseData())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraSender = CameraSender(this, rosbridge)
        imuSender = ImuSender(this, rosbridge)

        setContent {
            MaterialTheme {
                Surface {
                    HubScreen(
                        connectionState = rosbridge.state.value,
                        messageCount = rosbridge.messageCount.value,
                        pose = pose.value,
                        imuActive = imuRunning.value,
                        cameraActive = cameraRunning.value,
                        onConnect = ::handleConnect,
                        onDisconnect = ::handleDisconnect,
                        onToggleImu = ::handleToggleImu,
                        onToggleCamera = ::handleToggleCamera
                    )
                }
            }
        }
    }

    private fun handleConnect(host: String, port: Int) {
        rosbridge.connect(host, port)

        // Subscribe to phone pose from hub relocalization
        rosbridge.subscribe(
            topic = "/phone/pose",
            type = "geometry_msgs/msg/PoseStamped",
            throttleMs = 200
        ) { msg ->
            val p = msg.optJSONObject("pose")?.optJSONObject("position")
            if (p != null) {
                pose.value = PoseData(
                    x = p.optDouble("x", 0.0),
                    y = p.optDouble("y", 0.0),
                    z = p.optDouble("z", 0.0)
                )
            }
        }
    }

    private fun handleDisconnect() {
        if (imuRunning.value) handleToggleImu(false)
        if (cameraRunning.value) handleToggleCamera(false)
        rosbridge.disconnect()
    }

    private fun handleToggleImu(enabled: Boolean) {
        if (enabled) {
            imuSender.start()
        } else {
            imuSender.stop()
        }
        imuRunning.value = enabled
    }

    private fun handleToggleCamera(enabled: Boolean) {
        if (enabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                cameraSender.start(this)
                cameraRunning.value = true
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            cameraSender.stop()
            cameraRunning.value = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraSender.stop()
        imuSender.stop()
        rosbridge.disconnect()
    }
}
