package io.livekit.android.example.voiceassistant.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class IncomingCallInfo(
    val callId: String,
    val fromDeviceId: String,
    val fromDisplayName: String,
    val roomName: String,
)

@Composable
fun IncomingCallOverlay(
    callInfo: IncomingCallInfo,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    // Pulsing animation for the ring indicator
    val pulseScale by rememberInfiniteTransition(label = "ring-pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // consume touches so WebView underneath doesn't steal them
            )
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Ring indicator
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale)
                    .background(Color(0xFF4CAF50).copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .background(Color(0xFF4CAF50).copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📞", fontSize = 32.sp)
                }
            }

            // Caller info
            Text(
                text = "Incoming Call",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = callInfo.fromDisplayName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(48.dp))

            // Accept / Decline buttons — stacked vertically to fit narrow panel
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Accept
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onAccept,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        contentPadding = PaddingValues(20.dp),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Text("📞", fontSize = 28.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Accept", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }

                // Decline
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onDecline,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                        contentPadding = PaddingValues(20.dp),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Text("✕", fontSize = 28.sp, color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Decline", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
        }
    }
}
