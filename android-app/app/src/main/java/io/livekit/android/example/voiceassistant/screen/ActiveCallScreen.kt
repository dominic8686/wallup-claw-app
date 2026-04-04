package io.livekit.android.example.voiceassistant.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay

data class ActiveCallInfo(
    val callId: String,
    val remoteDeviceId: String,
    val remoteDisplayName: String,
    val roomName: String,
)

@Composable
fun ActiveCallScreen(
    callInfo: ActiveCallInfo,
    onHangup: () -> Unit,
    remoteVideoTrack: VideoTrack? = null,
    localVideoTrack: VideoTrack? = null,
) {
    // Call duration timer
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(callInfo.callId) {
        while (true) {
            delay(1_000)
            elapsedSeconds++
        }
    }

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val durationText = "%02d:%02d".format(minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B5E20)),
    ) {
        // Remote video — full-screen background
        if (remoteVideoTrack != null) {
            key(remoteVideoTrack) {
                val track = remoteVideoTrack
                AndroidView(
                    factory = { ctx ->
                        io.livekit.android.renderer.TextureViewRenderer(ctx).apply {
                            init(livekit.org.webrtc.EglBase.create().eglBaseContext, null)
                            track.addRenderer(this)
                        }
                    },
                    onRelease = { renderer ->
                        track.removeRenderer(renderer)
                        renderer.release()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Overlay content on top of video
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (remoteVideoTrack == null) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📞", fontSize = 36.sp)
                }
            }

            Text(
                text = "Connected",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = callInfo.remoteDisplayName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = durationText,
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
            )
        }

        // Hangup button — bottom center
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        ) {
            Button(
                onClick = onHangup,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier.size(72.dp),
            ) {
                Text("✕", fontSize = 28.sp, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text("Hang Up", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }

        // Local video — small PiP in top-right corner
        if (localVideoTrack != null) {
            key(localVideoTrack) {
                val localTrack = localVideoTrack
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .width(160.dp)
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            io.livekit.android.renderer.TextureViewRenderer(ctx).apply {
                                init(livekit.org.webrtc.EglBase.create().eglBaseContext, null)
                                setMirror(true)
                                localTrack.addRenderer(this)
                            }
                        },
                        onRelease = { renderer ->
                            localTrack.removeRenderer(renderer)
                            renderer.release()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
