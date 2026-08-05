package com.pri4l.hub

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Fullscreen AR view for the phone (Track B): the ARCore camera + anchor overlay fill the
 * screen. A small corner dot toggles a debug HUD; an onboarding prompt asks the wearer to
 * point at the alignment tag until the fiducial locks. Replaces the old control-panel
 * HubScreen as the primary surface.
 */
@Composable
fun ArAppScreen(
    glSurfaceView: GLSurfaceView?,
    connectionState: ConnectionState,
    tracking: ArTrackingUiState,
    isAligned: Boolean,
    messageCount: Int,
    pose: PoseData,
    imuActive: Boolean,
    cameraActive: Boolean,
    hubAnchorCount: Int,
    host: String,
    port: String,
    onReAlign: () -> Unit,
    onClearAlignment: () -> Unit,
    onPlaceAnchor: () -> Unit,
    onToggleImu: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onReconnect: (String, Int) -> Unit,
) {
    var showDebug by remember { mutableStateOf(false) }
    val connected = connectionState == ConnectionState.CONNECTED

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // AR camera + anchors
        if (glSurfaceView != null) {
            AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())
        }

        // Onboarding / status prompt (center), hidden once aligned
        if (!isAligned) {
            val msg = when {
                tracking == ArTrackingUiState.TRACKING -> "Point at the alignment tag"
                tracking == ArTrackingUiState.NOT_TRACKING || tracking == ArTrackingUiState.PAUSED ->
                    "Move your phone slowly to start tracking"
                else -> "Starting AR…"
            }
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 16.sp)
            }
        }

        // Corner tap target → toggle debug HUD
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                .size(28.dp).clip(CircleShape)
                .background(Color(0x66FFFFFF))
                .clickable { showDebug = !showDebug },
            contentAlignment = Alignment.Center
        ) {
            Text("⚙", color = Color.White, fontSize = 14.sp)
        }

        if (showDebug) {
            DebugPanel(
                modifier = Modifier.align(Alignment.TopStart),
                connectionState = connectionState,
                tracking = tracking,
                isAligned = isAligned,
                messageCount = messageCount,
                pose = pose,
                imuActive = imuActive,
                cameraActive = cameraActive,
                hubAnchorCount = hubAnchorCount,
                host = host,
                port = port,
                connected = connected,
                onReAlign = onReAlign,
                onClearAlignment = onClearAlignment,
                onPlaceAnchor = onPlaceAnchor,
                onToggleImu = onToggleImu,
                onToggleCamera = onToggleCamera,
                onReconnect = onReconnect,
            )
        }
    }
}

@Composable
private fun DebugPanel(
    modifier: Modifier,
    connectionState: ConnectionState,
    tracking: ArTrackingUiState,
    isAligned: Boolean,
    messageCount: Int,
    pose: PoseData,
    imuActive: Boolean,
    cameraActive: Boolean,
    hubAnchorCount: Int,
    host: String,
    port: String,
    connected: Boolean,
    onReAlign: () -> Unit,
    onClearAlignment: () -> Unit,
    onPlaceAnchor: () -> Unit,
    onToggleImu: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onReconnect: (String, Int) -> Unit,
) {
    var hostInput by remember(host) { mutableStateOf(host) }
    var portInput by remember(port) { mutableStateOf(port) }

    Surface(
        modifier = modifier.padding(12.dp).widthIn(max = 320.dp),
        color = Color(0xE6101010),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection status
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = when (connectionState) {
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                    ConnectionState.CONNECTING -> Color(0xFFFFC107)
                    ConnectionState.ERROR -> Color(0xFFF44336)
                    ConnectionState.DISCONNECTED -> Color(0xFF757575)
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Text("${connectionState.name}  ·  ${tracking.name}", color = Color.White, fontSize = 12.sp)
            }
            Text(
                "aligned=$isAligned  anchors=$hubAnchorCount  msgs=$messageCount",
                color = Color(0xFFBBBBBB), fontSize = 12.sp
            )
            Text(
                "pose x=%.2f y=%.2f z=%.2f".format(pose.x, pose.y, pose.z),
                color = Color(0xFFBBBBBB), fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            // Host/port + reconnect
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(hostInput, { hostInput = it }, label = { Text("Host") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(portInput, { portInput = it }, label = { Text("Port") },
                    modifier = Modifier.width(78.dp), singleLine = true)
            }
            Button(onClick = { onReconnect(hostInput, portInput.toIntOrNull() ?: 9090) }) {
                Text(if (connected) "Reconnect" else "Connect")
            }

            Divider(color = Color(0x33FFFFFF))

            // IMU streaming toggle. (Camera frames stream automatically via ArCameraBridge
            // once aligned — ARCore owns the camera, so there is no separate CameraX toggle.)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("IMU stream", color = Color.White, fontSize = 13.sp); Spacer(Modifier.width(6.dp))
                Switch(checked = imuActive, onCheckedChange = onToggleImu, enabled = connected)
            }

            // Alignment / anchor actions
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onReAlign) { Text("Re-align") }
                OutlinedButton(onClick = onClearAlignment, enabled = isAligned) { Text("Clear") }
            }
            Button(onClick = onPlaceAnchor, enabled = connected && isAligned,
                modifier = Modifier.fillMaxWidth()) { Text("Place Anchor") }
        }
    }
}
