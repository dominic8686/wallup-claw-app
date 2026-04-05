package com.wallupclaw.app

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
import com.wallupclaw.app.screen.ConnectRoute
import com.wallupclaw.app.screen.ConnectScreen
import com.wallupclaw.app.screen.HermesRoute
import com.wallupclaw.app.screen.HermesScreen
import com.wallupclaw.app.screen.MainDashboardRoute
import com.wallupclaw.app.screen.MainDashboardScreen
import com.wallupclaw.app.screen.PermissionGateRoute
import com.wallupclaw.app.screen.PermissionGateScreen
import com.wallupclaw.app.screen.SettingsRoute
import com.wallupclaw.app.screen.SettingsScreen
import com.wallupclaw.app.screen.VoiceAssistantRoute
import com.wallupclaw.app.screen.VoiceAssistantScreen
import com.wallupclaw.app.settings.AppSettings
import com.wallupclaw.app.settings.AppUpdater
import com.wallupclaw.app.settings.UpdateInterval
import android.util.Log
import com.wallupclaw.app.ui.theme.LiveKitVoiceAssistantExampleTheme
import com.wallupclaw.app.viewmodel.VoiceAssistantViewModel
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
                        NavHost(navController, startDestination = PermissionGateRoute) {
                            composable<PermissionGateRoute> {
                                PermissionGateScreen(
                                    onAllGranted = {
                                        runOnUiThread {
                                            navController.navigate(MainDashboardRoute) {
                                                popUpTo(PermissionGateRoute) { inclusive = true }
                                            }
                                        }
                                    }
                                )
                            }
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
