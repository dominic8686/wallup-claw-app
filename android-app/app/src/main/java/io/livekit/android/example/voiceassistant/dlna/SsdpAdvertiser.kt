package io.livekit.android.example.voiceassistant.dlna

import android.util.Log
import kotlinx.coroutines.*
import java.net.*

private const val TAG = "SsdpAdvertiser"
private const val SSDP_ADDR = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val NOTIFY_INTERVAL_MS = 30_000L  // re-advertise every 30s
private const val MAX_AGE = 1800  // cache-control max-age in seconds

/**
 * Advertises a UPnP MediaRenderer via SSDP so Home Assistant can auto-discover it.
 *
 * Sends NOTIFY ssdp:alive on startup and periodically, responds to M-SEARCH queries,
 * and sends NOTIFY ssdp:byebye on shutdown.
 */
class SsdpAdvertiser(
    private val usn: String,           // e.g., "uuid:tablet-kitchen::urn:schemas-upnp-org:device:MediaRenderer:1"
    private val deviceUuid: String,    // e.g., "uuid:tablet-kitchen"
    private val descriptionUrl: String // e.g., "http://192.168.211.50:8200/description.xml"
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: MulticastSocket? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch { listenLoop() }
        scope.launch { notifyLoop() }
        Log.i(TAG, "SSDP advertiser started (usn=$usn)")
    }

    fun stop() {
        running = false
        // Send byebye before shutdown
        try {
            sendNotify("ssdp:byebye")
        } catch (_: Exception) {}
        socket?.close()
        scope.cancel()
        Log.i(TAG, "SSDP advertiser stopped")
    }

    /** Periodically send NOTIFY ssdp:alive */
    private suspend fun notifyLoop() {
        // Initial burst: send 3 times with 1s gap (UPnP spec recommendation)
        repeat(3) {
            sendNotify("ssdp:alive")
            delay(1_000)
        }
        while (running) {
            delay(NOTIFY_INTERVAL_MS)
            sendNotify("ssdp:alive")
        }
    }

    /** Listen for M-SEARCH queries and respond */
    private suspend fun listenLoop() {
        try {
            val group = InetAddress.getByName(SSDP_ADDR)
            socket = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                joinGroup(InetSocketAddress(group, SSDP_PORT), NetworkInterface.getByIndex(0))
                soTimeout = 5000
            }
            val buf = ByteArray(2048)

            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length)

                    if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                        val searchTarget = extractHeader(msg, "ST")
                        if (shouldRespond(searchTarget)) {
                            sendSearchResponse(packet.address, packet.port, searchTarget ?: "ssdp:all")
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal — just loop
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "SSDP listen error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP listen setup failed: ${e.message}")
        }
    }

    private fun shouldRespond(st: String?): Boolean {
        if (st == null) return false
        return st == "ssdp:all" ||
               st == "upnp:rootdevice" ||
               st == "urn:schemas-upnp-org:device:MediaRenderer:1" ||
               st == "urn:schemas-upnp-org:service:AVTransport:1" ||
               st == "urn:schemas-upnp-org:service:RenderingControl:1" ||
               st == deviceUuid
    }

    private fun sendNotify(nts: String) {
        val nt = "urn:schemas-upnp-org:device:MediaRenderer:1"
        val msg = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
            append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
            append("LOCATION: $descriptionUrl\r\n")
            append("NT: $nt\r\n")
            append("NTS: $nts\r\n")
            append("SERVER: Android/1.0 UPnP/1.1 HermesRenderer/1.0\r\n")
            append("USN: $usn\r\n")
            append("\r\n")
        }
        sendMulticast(msg)
    }

    private fun sendSearchResponse(addr: InetAddress, port: Int, st: String) {
        val msg = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
            append("LOCATION: $descriptionUrl\r\n")
            append("ST: $st\r\n")
            append("USN: $usn\r\n")
            append("SERVER: Android/1.0 UPnP/1.1 HermesRenderer/1.0\r\n")
            append("EXT:\r\n")
            append("\r\n")
        }
        try {
            val data = msg.toByteArray()
            val packet = DatagramPacket(data, data.size, addr, port)
            DatagramSocket().use { it.send(packet) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send M-SEARCH response: ${e.message}")
        }
    }

    private fun sendMulticast(msg: String) {
        try {
            val data = msg.toByteArray()
            val group = InetAddress.getByName(SSDP_ADDR)
            val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
            DatagramSocket().use { it.send(packet) }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP multicast send failed: ${e.message}")
        }
    }

    private fun extractHeader(msg: String, header: String): String? {
        val regex = Regex("(?i)^$header:\\s*(.+?)\\s*$", RegexOption.MULTILINE)
        return regex.find(msg)?.groupValues?.get(1)
    }
}
