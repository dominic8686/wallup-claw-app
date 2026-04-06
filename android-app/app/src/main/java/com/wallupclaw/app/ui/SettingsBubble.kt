package com.wallupclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings bubble button — opens the settings drawer.
 *
 * Matches the size and bubble treatment used by the other corner controls.
 */
@Composable
fun SettingsBubble(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = Color(0xFF607D8B)
    val glowElevation = 8.dp

    Box(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(glowElevation, CircleShape)
                .background(bubbleColor.copy(alpha = 0.2f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(bubbleColor, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚙",
                fontSize = 18.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
