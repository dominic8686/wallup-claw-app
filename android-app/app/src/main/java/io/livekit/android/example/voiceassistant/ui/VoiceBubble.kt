package io.livekit.android.example.voiceassistant.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun VoiceBubble(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulseScale by rememberInfiniteTransition(label = "bubblePulse").animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bubblePulseScale"
    )

    val bubbleColor = Color(0xFF2196F3) // Blue

    Box(
        modifier = modifier
            .padding(24.dp)
            .size(56.dp)
            .scale(pulseScale),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape)
                .background(bubbleColor.copy(alpha = 0.2f), CircleShape)
        )
        // Inner solid circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(bubbleColor, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        )
        // Center dot
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(Color.White.copy(alpha = 0.8f), CircleShape)
        )
    }
}
