package com.wallupclaw.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Intercom bubble button — toggles the contacts/intercom panel.
 *
 * @param isOpen true when the contacts panel is currently visible
 * @param isCallActive true when a call is ringing or in progress
 * @param onClick tap to toggle the contacts panel
 */
@Composable
fun IntercomBubble(
    isOpen: Boolean,
    isCallActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulseTarget = when {
        isCallActive -> 1.20f   // Pulse when call is active
        isOpen -> 1f            // No pulse when panel is just open
        else -> 1f
    }
    val pulseScale by rememberInfiniteTransition(label = "intercomPulse").animateFloat(
        initialValue = 1f,
        targetValue = pulseTarget,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "intercomPulseScale"
    )

    // Orange when call active, green when panel open, blue when idle
    val bubbleColor = when {
        isCallActive -> Color(0xFFFF9800)  // Orange
        isOpen -> Color(0xFF4CAF50)        // Green
        else -> Color(0xFF2196F3)          // Blue
    }

    val glowElevation = if (isCallActive) 12.dp else 6.dp

    Box(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .size(56.dp)
            .scale(if (isCallActive) pulseScale else 1f),
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
        // Center icon
        Text(
            text = "📞",
            fontSize = 16.sp,
        )
    }
}
