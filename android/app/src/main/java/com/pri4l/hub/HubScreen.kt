package com.pri4l.hub

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class PoseData(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)

/** ARCore camera tracking; OFF when AR mode is disabled. */
enum class ArTrackingUiState {
    OFF,
    NOT_TRACKING,
    PAUSED,
    TRACKING
}

@Composable
private fun StatusBanner(
    connectionState: ConnectionState,
    arActive: Boolean,
    arTrackingState: ArTrackingUiState,
    isAligned: Boolean
) {
    val text = when (connectionState) {
        ConnectionState.DISCONNECTED -> stringResource(R.string.status_disconnected)
        ConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionState.ERROR -> stringResource(R.string.status_error)
        ConnectionState.CONNECTED -> when {
            !arActive -> stringResource(R.string.status_connected_idle)
            arTrackingState == ArTrackingUiState.OFF -> stringResource(R.string.status_connected_idle)
            arTrackingState == ArTrackingUiState.NOT_TRACKING ->
                stringResource(R.string.status_ar_not_tracking)
            arTrackingState == ArTrackingUiState.PAUSED ->
                stringResource(R.string.status_ar_paused)
            arTrackingState == ArTrackingUiState.TRACKING && !isAligned ->
                stringResource(R.string.status_ar_tracking_align)
            arTrackingState == ArTrackingUiState.TRACKING && isAligned ->
                stringResource(R.string.status_ar_aligned)
            else -> stringResource(R.string.status_connected_idle)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun HubScreen(
    connectionState: ConnectionState,
    messageCount: Int,
    pose: PoseData,
    imuActive: Boolean,
    cameraActive: Boolean,
    arActive: Boolean,
    arAvailable: Boolean,
    arTrackingState: ArTrackingUiState,
    isAligned: Boolean,
    savedHost: String,
    savedPort: String,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onToggleImu: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleAr: (Boolean) -> Unit,
    onAlign: () -> Unit,
    onClearAlignment: () -> Unit,
    glSurfaceView: GLSurfaceView?
) {
    var hostInput by remember { mutableStateOf(savedHost) }
    var portInput by remember { mutableStateOf(savedPort) }
    var showPrivacy by remember { mutableStateOf(false) }
    val connected = connectionState == ConnectionState.CONNECTED

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.privacy_title)) },
            text = {
                Text(
                    stringResource(R.string.privacy_body),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) {
                    Text(stringResource(R.string.privacy_ok))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pri4L Hub", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)

        StatusBanner(
            connectionState = connectionState,
            arActive = arActive,
            arTrackingState = arTrackingState,
            isAligned = isAligned
        )

        // Connection status (compact)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
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
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onAlign,
                    enabled = arActive && connected && !isAligned
                ) {
                    Text("Align")
                }
                OutlinedButton(
                    onClick = onClearAlignment,
                    enabled = arActive && connected && isAligned
                ) {
                    Text(stringResource(R.string.clear_alignment))
                }
            }
        }

        TextButton(onClick = { showPrivacy = true }) {
            Text(stringResource(R.string.privacy_link))
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
                    .heightIn(min = 200.dp)
            )
        }
    }
}
