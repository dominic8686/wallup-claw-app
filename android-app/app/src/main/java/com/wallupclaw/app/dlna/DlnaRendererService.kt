package com.wallupclaw.app.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

private const val TAG = "DlnaRenderer"
private const val HTTP_PORT = 8200
private const val CHANNEL_ID = "dlna_renderer"
private const val NOTIFICATION_ID = 2001

/**
 * Foreground service that runs a UPnP/DLNA MediaRenderer.
 *
 * HA discovers it via SSDP and controls it via SOAP (AVTransport + RenderingControl).
 * Audio playback uses Android's MediaPlayer with proper audio focus management.
 */
class DlnaRendererService : Service() {

    private var httpServer: UpnpHttpServer? = null
    private var ssdpAdvertiser: SsdpAdvertiser? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Transport state
    private var currentUri: String = ""
    private var transportState: String = "NO_MEDIA_PRESENT"  // STOPPED, PLAYING, PAUSED_PLAYBACK, NO_MEDIA_PRESENT
    private var volume: Int = 50  // 0-100
    private var muted: Boolean = false

    // Device identity
    private var deviceUuid: String = ""
    private var friendlyName: String = "Tablet Speaker"

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        friendlyName = intent?.getStringExtra("friendly_name") ?: "Tablet Speaker"
        val deviceId = intent?.getStringExtra("device_id") ?: "tablet-${UUID.randomUUID().toString().take(8)}"
        deviceUuid = "uuid:$deviceId"

        startForeground(NOTIFICATION_ID, buildNotification())

        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val baseUrl = "http://$localIp:$HTTP_PORT"
        val descriptionUrl = "$baseUrl/description.xml"

        // Start UPnP HTTP server with watchdog
        startHttpServer()

        // Start SSDP advertisement
        val usn = "$deviceUuid::urn:schemas-upnp-org:device:MediaRenderer:1"
        ssdpAdvertiser = SsdpAdvertiser(usn, deviceUuid, descriptionUrl).also { it.start() }

        Log.i(TAG, "DLNA renderer started: $friendlyName ($deviceUuid) at $baseUrl")

        // Watchdog: check every 15s with real HTTP connectivity test
        watchdogThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(15_000)
                    // Real connectivity check — try to fetch description.xml
                    val reachable = try {
                        val conn = java.net.URL("http://127.0.0.1:$HTTP_PORT/description.xml")
                            .openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3_000
                        conn.readTimeout = 3_000
                        val code = conn.responseCode
                        conn.disconnect()
                        code == 200
                    } catch (_: Exception) { false }

                    if (!reachable) {
                        Log.w(TAG, "HTTP server unreachable, restarting...")
                        startHttpServer()
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }

        return START_STICKY
    }

    private var watchdogThread: Thread? = null

    private fun startHttpServer() {
        try {
            httpServer?.stop()
        } catch (_: Exception) {}
        try {
            httpServer = UpnpHttpServer(HTTP_PORT).also { it.start() }
            Log.i(TAG, "UPnP HTTP server on :$HTTP_PORT")
        } catch (e: java.net.BindException) {
            Log.w(TAG, "Port $HTTP_PORT in use: ${e.message}")
            httpServer = null
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server start failed: ${e.message}")
            httpServer = null
        }
    }

    override fun onDestroy() {
        watchdogThread?.interrupt()
        ssdpAdvertiser?.stop()
        httpServer?.stop()
        releaseMediaPlayer()
        releaseAudioFocus()
        Log.i(TAG, "DLNA renderer stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Media playback
    // -------------------------------------------------------------------------

    private fun setMediaUri(uri: String) {
        currentUri = uri
        transportState = "STOPPED"
        Log.i(TAG, "SetAVTransportURI: $uri")
    }

    private fun play() {
        if (currentUri.isEmpty()) return
        releaseMediaPlayer()
        requestAudioFocus()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(currentUri)
            setVolume(volume / 100f, volume / 100f)
            setOnPreparedListener {
                it.start()
                transportState = "PLAYING"
                Log.i(TAG, "Playing: $currentUri")
            }
            setOnCompletionListener {
                transportState = "STOPPED"
                releaseAudioFocus()
                Log.i(TAG, "Playback complete")
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                transportState = "STOPPED"
                releaseAudioFocus()
                true
            }
            prepareAsync()
        }
        transportState = "TRANSITIONING"
    }

    private fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
        }
        transportState = "STOPPED"
        releaseAudioFocus()
        Log.i(TAG, "Stopped")
    }

    private fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                transportState = "PAUSED_PLAYBACK"
                Log.i(TAG, "Paused")
            }
        }
    }

    private fun resume() {
        mediaPlayer?.let {
            it.start()
            transportState = "PLAYING"
            Log.i(TAG, "Resumed")
        }
    }

    private fun setVolume(vol: Int) {
        volume = vol.coerceIn(0, 100)
        mediaPlayer?.setVolume(volume / 100f, volume / 100f)
        Log.d(TAG, "Volume: $volume")
    }

    private fun setMute(mute: Boolean) {
        muted = mute
        val v = if (mute) 0f else volume / 100f
        mediaPlayer?.setVolume(v, v)
        Log.d(TAG, "Mute: $muted")
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // -------------------------------------------------------------------------
    // Audio focus
    // -------------------------------------------------------------------------

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focus ->
                    when (focus) {
                        AudioManager.AUDIOFOCUS_LOSS -> stop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            mediaPlayer?.setVolume(0.2f, 0.2f)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mediaPlayer?.setVolume(volume / 100f, volume / 100f)
                        }
                    }
                }
                .build()
            audioManager?.requestAudioFocus(request)
            audioFocusRequest = request
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DLNA Speaker", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DLNA Speaker")
                .setContentText("$friendlyName — available on network")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("DLNA Speaker")
                .setContentText("$friendlyName — available on network")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
    }

    // -------------------------------------------------------------------------
    // UPnP HTTP Server (NanoHTTPD)
    // -------------------------------------------------------------------------

    private inner class UpnpHttpServer(port: Int) : NanoHTTPD(port) {

        override fun useGzipWhenAccepted(r: Response?): Boolean = false

        override fun serve(session: IHTTPSession): Response = try {
            serveInternal(session)
        } catch (e: Exception) {
            Log.e(TAG, "serve() error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal error")
        }

        private fun serveInternal(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            return when {
                // Device description XML
                uri == "/description.xml" -> serveDescription()

                // AVTransport service description
                uri == "/AVTransport.xml" -> serveAvTransportScpd()

                // RenderingControl service description
                uri == "/RenderingControl.xml" -> serveRenderingControlScpd()

                // ConnectionManager service description
                uri == "/ConnectionManager.xml" -> serveConnectionManagerScpd()

                // SOAP control endpoint for AVTransport
                uri == "/AVTransport/control" && method == Method.POST -> {
                    handleAvTransportAction(session)
                }

                // SOAP control endpoint for RenderingControl
                uri == "/RenderingControl/control" && method == Method.POST -> {
                    handleRenderingControlAction(session)
                }

                else -> {
                    Log.d(TAG, "Unhandled: $method $uri")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            }
        }

        // --- Device Description XML ---

        private fun serveDescription(): Response {
            val localIp = getLocalIpAddress() ?: "127.0.0.1"
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>1</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$friendlyName</friendlyName>
    <manufacturer>Hermes</manufacturer>
    <modelName>Tablet Speaker</modelName>
    <modelDescription>Wall-mounted tablet DLNA speaker</modelDescription>
    <UDN>$deviceUuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport.xml</SCPDURL>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl.xml</SCPDURL>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager.xml</SCPDURL>
        <controlURL>/ConnectionManager/control</controlURL>
        <eventSubURL>/ConnectionManager/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml)
        }

        // --- AVTransport SCPD ---

        private fun serveAvTransportScpd(): Response {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Play</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Stop</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Pause</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetTransportInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetPositionInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
        <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
        <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml)
        }

        // --- RenderingControl SCPD ---

        private fun serveRenderingControlScpd(): Response {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>SetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetMute</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>SetMute</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
    <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml)
        }

        // --- ConnectionManager SCPD (minimal) ---

        private fun serveConnectionManagerScpd(): Response {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
            return newFixedLengthResponse(Response.Status.OK, "text/xml", xml)
        }

        // --- SOAP Action Handlers ---

        private fun handleAvTransportAction(session: IHTTPSession): Response {
            val body = readBody(session)
            val action = extractSoapAction(session, body)
            Log.d(TAG, "AVTransport action: $action")

            return when (action) {
                "SetAVTransportURI" -> {
                    val uri = extractXmlValue(body, "CurrentURI") ?: ""
                    setMediaUri(uri)
                    soapOkResponse("SetAVTransportURI")
                }
                "Play" -> {
                    play()
                    soapOkResponse("Play")
                }
                "Stop" -> {
                    stop()
                    soapOkResponse("Stop")
                }
                "Pause" -> {
                    pause()
                    soapOkResponse("Pause")
                }
                "GetTransportInfo" -> {
                    soapResponse("GetTransportInfo", """
                        <CurrentTransportState>$transportState</CurrentTransportState>
                        <CurrentTransportStatus>OK</CurrentTransportStatus>
                        <CurrentSpeed>1</CurrentSpeed>""")
                }
                "GetPositionInfo" -> {
                    val pos = formatDuration(mediaPlayer?.currentPosition ?: 0)
                    val dur = formatDuration(mediaPlayer?.duration ?: 0)
                    soapResponse("GetPositionInfo", """
                        <Track>1</Track>
                        <TrackDuration>$dur</TrackDuration>
                        <TrackMetaData></TrackMetaData>
                        <TrackURI>$currentUri</TrackURI>
                        <RelTime>$pos</RelTime>
                        <AbsTime>$pos</AbsTime>
                        <RelCount>0</RelCount>
                        <AbsCount>0</AbsCount>""")
                }
                else -> {
                    Log.w(TAG, "Unknown AVTransport action: $action")
                    soapOkResponse(action ?: "Unknown")
                }
            }
        }

        private fun handleRenderingControlAction(session: IHTTPSession): Response {
            val body = readBody(session)
            val action = extractSoapAction(session, body)
            Log.d(TAG, "RenderingControl action: $action")

            return when (action) {
                "GetVolume" -> {
                    soapResponse("GetVolume", "<CurrentVolume>$volume</CurrentVolume>", "RenderingControl")
                }
                "SetVolume" -> {
                    val vol = extractXmlValue(body, "DesiredVolume")?.toIntOrNull() ?: volume
                    setVolume(vol)
                    soapOkResponse("SetVolume", "RenderingControl")
                }
                "GetMute" -> {
                    soapResponse("GetMute", "<CurrentMute>${if (muted) "1" else "0"}</CurrentMute>", "RenderingControl")
                }
                "SetMute" -> {
                    val m = extractXmlValue(body, "DesiredMute") == "1"
                    setMute(m)
                    soapOkResponse("SetMute", "RenderingControl")
                }
                else -> {
                    Log.w(TAG, "Unknown RenderingControl action: $action")
                    soapOkResponse(action ?: "Unknown", "RenderingControl")
                }
            }
        }

        // --- Helpers ---

        private fun readBody(session: IHTTPSession): String {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) return ""
            val buf = ByteArray(contentLength)
            session.inputStream.read(buf, 0, contentLength)
            return String(buf)
        }

        private fun extractSoapAction(session: IHTTPSession, body: String): String? {
            // Try SOAPACTION header first
            val header = session.headers["soapaction"]?.trim('"', ' ')
            if (header != null) {
                return header.substringAfterLast("#")
            }
            // Fallback: parse from body
            val match = Regex("<u:(\\w+)").find(body)
            return match?.groupValues?.get(1)
        }

        private fun extractXmlValue(xml: String, tag: String): String? {
            // Handle both namespaced and non-namespaced tags
            val regex = Regex("<(?:\\w+:)?$tag[^>]*>([^<]*)</(?:\\w+:)?$tag>", RegexOption.IGNORE_CASE)
            return regex.find(xml)?.groupValues?.get(1)
        }

        private fun soapOkResponse(action: String, serviceType: String = "AVTransport"): Response {
            return soapResponse(action, "", serviceType)
        }

        private fun soapResponse(action: String, innerXml: String, serviceType: String = "AVTransport"): Response {
            val ns = "urn:schemas-upnp-org:service:$serviceType:1"
            val responseTag = action + "Response"
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$responseTag xmlns:u="$ns">$innerXml</u:$responseTag>
  </s:Body>
</s:Envelope>"""
            return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", xml)
        }

        private fun formatDuration(ms: Int): String {
            val totalSecs = ms / 1000
            val hours = totalSecs / 3600
            val mins = (totalSecs % 3600) / 60
            val secs = totalSecs % 60
            return "%d:%02d:%02d".format(hours, mins, secs)
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun getLocalIpAddress(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
