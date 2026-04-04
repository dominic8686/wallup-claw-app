package io.livekit.android.example.voiceassistant.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConversationCard(
    messages: List<ChatMessage>,
    status: ConversationStatus,
    anamApiKey: String = "",
    anamAvatarId: String = "",
    anamEnabled: Boolean = false,
    onClose: () -> Unit = {},
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
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 40) { // Swipe right to close
                        onClose()
                    }
                }
            }
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

        // Avatar WebView (Anam JS SDK)
        if (anamEnabled && anamApiKey.isNotEmpty() && anamAvatarId.isNotEmpty()) {
            val avatarHtml = remember(anamApiKey, anamAvatarId) {
                """
                <!DOCTYPE html>
                <html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                    * { margin:0; padding:0; }
                    body { background:#000; overflow:hidden; }
                    video, #anamVideo { width:100%; height:100%; object-fit:cover; }
                    audio { display:none; }
                </style>
                </head><body>
                <video id="anamVideo" autoplay playsinline muted></video>
                <audio id="anamAudio" autoplay></audio>
                <script src="https://unpkg.com/@anam-ai/js-sdk@4.12.0/dist/umd/anam.js"></script>
                <script>
                    const { createClient } = window.anam;
                    // Get session token with no brain (visual-only avatar)
                    async function startAvatar() {
                        try {
                            const resp = await fetch('https://api.anam.ai/v1/auth/session-token', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                    'Authorization': 'Bearer ${anamApiKey}',
                                },
                                body: JSON.stringify({
                                    personaConfig: {
                                        name: 'Hermes',
                                        avatarId: '${anamAvatarId}',
                                        voiceId: '6bfbe25a-979d-40f3-a92b-5394170af54b',
                                        llmId: 'CUSTOMER_CLIENT_V1',
                                        systemPrompt: 'You are a visual avatar. Do not respond to user input.',
                                    },
                                }),
                            });
                            const data = await resp.json();
                            const client = createClient(data.sessionToken, { disableClientAudio: true });
                            await client.streamToVideoAndAudioElements('anamVideo', 'anamAudio');
                            window.anamClient = client;
                        } catch(e) {
                            console.error('Avatar start failed:', e);
                        }
                    }
                    startAvatar();
                    // Expose talk() for Android to call
                    window.anamTalk = function(text) {
                        try { if(window.anamClient) window.anamClient.talk(text); } catch(e) { console.error('anamTalk error:', e); }
                    };
                </script>
                </body></html>
                """.trimIndent()
            }

            val webViewRef = remember { mutableStateOf<WebView?>(null) }

            // When agent speaks, send text to avatar
            val lastAgentMessage = messages.lastOrNull { !it.isUser }?.text
            LaunchedEffect(lastAgentMessage) {
                if (lastAgentMessage != null) {
                    webViewRef.value?.evaluateJavascript(
                        "if(window.anamTalk) window.anamTalk('" +
                            lastAgentMessage.replace("'", "\\'").replace("\n", " ") +
                            "');", null
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadDataWithBaseURL(
                            "https://api.anam.ai",
                            avatarHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                        webViewRef.value = this
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
