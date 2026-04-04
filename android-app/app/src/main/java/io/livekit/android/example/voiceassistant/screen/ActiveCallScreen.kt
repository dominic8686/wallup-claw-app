package io.livekit.android.example.voiceassistant.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Connected indicator
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("📞", fontSize = 36.sp)
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

            Spacer(Modifier.height(48.dp))

            // Hangup button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        }
    }
}
