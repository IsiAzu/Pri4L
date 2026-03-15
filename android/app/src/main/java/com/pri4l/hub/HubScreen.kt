package com.pri4l.hub

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class PoseData(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    connectionState: ConnectionState,
    messageCount: Int,
    pose: PoseData,
    imuActive: Boolean,
    cameraActive: Boolean,
    arActive: Boolean,
    arAvailable: Boolean,
    isAligned: Boolean,
    savedHost: String,
    savedPort: String,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onToggleImu: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleAr: (Boolean) -> Unit,
    onAlign: () -> Unit,
    glSurfaceView: GLSurfaceView?
) {
    var hostInput by remember { mutableStateOf(savedHost) }
    var portInput by remember { mutableStateOf(savedPort) }
    val connected = connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pri4L Hub", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)

        // Connection status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                ConnectionState.CONNECTING -> Color(0xFFFFC107)
                ConnectionState.ERROR -> Color(0xFFF44336)
                ConnectionState.DISCONNECTED -> Color(0xFF757575)
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(connectionState.name, fontSize = 14.sp)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("Host") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = { portInput = it },
                label = { Text("Port") },
                modifier = Modifier.width(80.dp),
                singleLine = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onConnect(hostInput, portInput.toIntOrNull() ?: 9090) },
                enabled = !connected
            ) { Text("Connect") }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = connected
            ) { Text("Disconnect") }
        }

        Divider()

        // Sensor controls
        Text("Sensors", fontSize = 16.sp)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("IMU")
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = imuActive,
                    onCheckedChange = onToggleImu,
                    enabled = connected
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Camera")
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = cameraActive,
                    onCheckedChange = onToggleCamera,
                    enabled = connected && !arActive
                )
            }
        }

        // AR controls
        if (arAvailable) {
            Divider()
            Text("AR", fontSize = 16.sp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ARCore")
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = arActive,
                        onCheckedChange = onToggleAr,
                        enabled = connected
                    )
                }

                Button(
                    onClick = onAlign,
                    enabled = arActive && connected && !isAligned
                ) {
                    Text(if (isAligned) "Aligned" else "Align")
                }

                if (isAligned) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                }
            }

            if (arActive && !isAligned) {
                Text(
                    "Stand at the D435, point phone same direction, tap Align",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider()

        // Hub data
        Text("Hub Data", fontSize = 16.sp)
        Text("Messages: $messageCount", fontSize = 14.sp)
        Text(
            "Pose: x=%.3f  y=%.3f  z=%.3f".format(pose.x, pose.y, pose.z),
            fontSize = 14.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )

        // AR preview
        if (arActive && glSurfaceView != null) {
            Divider()
            AndroidView(
                factory = { glSurfaceView!! },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
