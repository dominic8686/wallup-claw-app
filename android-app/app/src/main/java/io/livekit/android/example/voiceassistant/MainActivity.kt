package io.livekit.android.example.voiceassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import io.livekit.android.LiveKit
import io.livekit.android.example.voiceassistant.screen.ConnectRoute
import io.livekit.android.example.voiceassistant.screen.ConnectScreen
import io.livekit.android.example.voiceassistant.screen.HermesRoute
import io.livekit.android.example.voiceassistant.screen.HermesScreen
import io.livekit.android.example.voiceassistant.screen.MainDashboardRoute
import io.livekit.android.example.voiceassistant.screen.MainDashboardScreen
import io.livekit.android.example.voiceassistant.screen.SettingsRoute
import io.livekit.android.example.voiceassistant.screen.SettingsScreen
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantRoute
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantScreen
import io.livekit.android.example.voiceassistant.settings.AppSettings
import io.livekit.android.example.voiceassistant.settings.AppUpdater
import io.livekit.android.example.voiceassistant.ui.theme.LiveKitVoiceAssistantExampleTheme
import io.livekit.android.example.voiceassistant.viewmodel.VoiceAssistantViewModel
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveKit.loggingLevel = LoggingLevel.DEBUG

        // Initialize bundled emoji font so emojis render on all devices
        EmojiCompat.init(BundledEmojiCompatConfig(this))

        // Auto-update check
        val appSettings = AppSettings(this)
        lifecycleScope.launch {
            val autoUpdate = appSettings.autoUpdateEnabled.first()
            if (autoUpdate) {
                val info = AppUpdater.checkForUpdate(BuildConfig.VERSION_NAME)
                if (info != null && info.isNewer) {
                    AppUpdater.downloadAndInstall(this@MainActivity, info.apkDownloadUrl)
                }
            }
        }

        setContent {
            val navController = rememberNavController()
            LiveKitVoiceAssistantExampleTheme(dynamicColor = false) {
                Scaffold { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        // Set up NavHost for the app
                        NavHost(navController, startDestination = MainDashboardRoute) {
                            composable<MainDashboardRoute> {
                                MainDashboardScreen()
                            }
                            composable<HermesRoute> {
                                HermesScreen(
                                    navigateToSettings = {
                                        runOnUiThread { navController.navigate(SettingsRoute) }
                                    }
                                )
                            }
                            composable<ConnectRoute> {
                                ConnectScreen(
                                    navigateToVoiceAssistant = { voiceAssistantRoute ->
                                        runOnUiThread {
                                            navController.navigate(voiceAssistantRoute)
                                        }
                                    },
                                    navigateToSettings = {
                                        runOnUiThread {
                                            navController.navigate(SettingsRoute)
                                        }
                                    }
                                )
                            }

                            composable<SettingsRoute> {
                                SettingsScreen(
                                    onBack = { runOnUiThread { navController.navigateUp() } }
                                )
                            }

                            composable<VoiceAssistantRoute> {
                                val viewModel = viewModel<VoiceAssistantViewModel>()
                                VoiceAssistantScreen(
                                    viewModel = viewModel,
                                    onEndCall = {
                                        runOnUiThread { navController.navigateUp() }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
