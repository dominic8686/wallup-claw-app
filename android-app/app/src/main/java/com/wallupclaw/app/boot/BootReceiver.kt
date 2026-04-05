package com.wallupclaw.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wallupclaw.app.MainActivity
import com.wallupclaw.app.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Check if auto-start is enabled (blocking read — runs once at boot)
        val settings = AppSettings(context)
        val autoStart = runBlocking { settings.autoStartOnBoot.first() }

        if (autoStart) {
            Log.i(TAG, "Boot completed — auto-starting app")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        } else {
            Log.i(TAG, "Boot completed — auto-start disabled, skipping")
        }
    }
}
