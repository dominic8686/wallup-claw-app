package com.wallupclaw.app.ui

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

/**
 * Voice bubble indicator.
 *
 * @param isListening true when wake word engine is active (mic muted, idle)
 * @param isAgentActive true when LiveKit mic is unmuted and agent is listening/responding
 * @param onClick tap to manually toggle mic mute/unmute
 */
@Composable
fun VoiceBubble(
    isListening: Boolean,
    isAgentActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulseTarget = when {
        isAgentActive -> 1.25f   // Bigger pulse when agent is active
        isListening -> 1.15f     // Gentle pulse when listening for wake word
        else -> 1f
    }
    val pulseScale by rememberInfiniteTransition(label = "bubblePulse").animateFloat(
        initialValue = 1f,
        targetValue = pulseTarget,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isAgentActive) 800 else 1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bubblePulseScale"
    )

    // Green when agent active, blue when listening for wake word, grey when idle
    val bubbleColor = when {
        isAgentActive -> Color(0xFF4CAF50)  // Green
        isListening -> Color(0xFF2196F3)    // Blue
        else -> Color(0xFF9E9E9E)           // Grey
    }

    val glowElevation = if (isAgentActive) 16.dp else 8.dp

    Box(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .size(56.dp)
            .scale(pulseScale),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(glowElevation, CircleShape)
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
