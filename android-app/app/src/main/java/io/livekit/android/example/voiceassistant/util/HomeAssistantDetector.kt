package io.livekit.android.example.voiceassistant.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

object HomeAssistantDetector {

    private const val TAG = "HADetector"
    private const val HA_PORT = 8123
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000

    data class DetectionResult(
        val found: Boolean,
        val url: String? = null,
    )

    /**
     * Try to find a Home Assistant instance on the local network.
     * Checks common URLs in order of likelihood.
     */
    suspend fun detect(): DetectionResult = withContext(Dispatchers.IO) {
        val candidates = mutableListOf(
            "http://homeassistant.local:$HA_PORT",
            "http://homeassistant:$HA_PORT",
        )

        // Add gateway-based guesses from device's current subnet
        try {
            val localIp = getLocalIpAddress()
            if (localIp != null) {
                val subnet = localIp.substringBeforeLast(".")
                candidates.add("http://$subnet.1:$HA_PORT")
                candidates.add("http://$subnet.2:$HA_PORT")
                candidates.add("http://$subnet.100:$HA_PORT")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to determine local subnet: ${e.message}")
        }

        for (candidate in candidates) {
            Log.d(TAG, "Trying $candidate...")
            val reachable = withTimeoutOrNull(5000L) { testHaUrl(candidate) } ?: false
            if (reachable) {
                Log.i(TAG, "Found Home Assistant at $candidate")
                return@withContext DetectionResult(found = true, url = candidate)
            }
        }

        Log.i(TAG, "No Home Assistant instance found")
        DetectionResult(found = false)
    }

    /**
     * Test if a given URL points to a valid Home Assistant instance.
     */
    suspend fun testHaUrl(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${baseUrl.trimEnd('/')}/api/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            conn.disconnect()

            // HA returns 401 (unauthorized) for /api/ without a token, which still confirms it exists.
            // 200 means it's behind a proxy or has auth disabled.
            code in listOf(200, 401, 403)
        } catch (e: Exception) {
            Log.d(TAG, "Not reachable: $baseUrl (${e.message})")
            false
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
