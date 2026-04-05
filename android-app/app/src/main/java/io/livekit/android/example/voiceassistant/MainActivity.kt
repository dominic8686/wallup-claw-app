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
import io.livekit.android.example.voiceassistant.settings.UpdateInterval
import android.util.Log
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

        // Auto-update check (respects configured interval)
        val appSettings = AppSettings(this)
        lifecycleScope.launch {
            val autoUpdate = appSettings.autoUpdateEnabled.first()
            Log.i("AutoUpdate", "enabled=$autoUpdate, version=${BuildConfig.VERSION_NAME}")
            if (autoUpdate) {
                val lastCheck = appSettings.lastUpdateCheck.first()
                val intervalId = appSettings.updateCheckInterval.first()
                val interval = UpdateInterval.fromId(intervalId)
                val now = System.currentTimeMillis()
                val elapsed = now - lastCheck
                Log.i("AutoUpdate", "interval=${interval.displayName} (${interval.millis}ms), elapsed=${elapsed}ms")
                if (elapsed >= interval.millis) {
                    Log.i("AutoUpdate", "Checking for update...")
                    appSettings.setLastUpdateCheck(now)
                    val info = AppUpdater.checkForUpdate(BuildConfig.VERSION_NAME)
                    if (info != null && info.isNewer) {
                        Log.i("AutoUpdate", "Update found: v${info.latestVersion}, downloading...")
                        AppUpdater.downloadAndInstall(this@MainActivity, info.apkDownloadUrl)
                    } else {
                        Log.i("AutoUpdate", "No update available (latest=${info?.latestVersion})")
                    }
                } else {
                    Log.i("AutoUpdate", "Skipping, next check in ${(interval.millis - elapsed) / 1000}s")
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
