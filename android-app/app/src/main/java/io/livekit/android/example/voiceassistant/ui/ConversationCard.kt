package io.livekit.android.example.voiceassistant.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class ConversationStatus {
    LISTENING,
    THINKING,
    SPEAKING,
}

@Composable
fun ConversationCard(
    messages: List<ChatMessage>,
    status: ConversationStatus,
    avatarVideoTrack: VideoTrack? = null,
    modifier: Modifier = Modifier,
) {
    val statusColor = Color(0xFF4CAF50) // Green
    val statusPulse by rememberInfiniteTransition(label = "statusPulse").animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusPulseAlpha"
    )

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // Status header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(statusPulse)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (status) {
                    ConversationStatus.LISTENING -> "Listening..."
                    ConversationStatus.THINKING -> "Thinking..."
                    ConversationStatus.SPEAKING -> "Speaking..."
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                color = statusColor,
            )
        }

        // Avatar video (if available)
        if (avatarVideoTrack != null) {
            AndroidView(
                factory = { ctx ->
                    TextureViewRenderer(ctx).apply {
                        avatarVideoTrack.addRenderer(this)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (message.isUser) Color(0xFF2196F3) else Color(0xFFE8E8E8)
    val textColor = if (message.isUser) Color.White else Color.Black

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 22.sp,
                lineHeight = 30.sp,
            )
        }
    }
}
