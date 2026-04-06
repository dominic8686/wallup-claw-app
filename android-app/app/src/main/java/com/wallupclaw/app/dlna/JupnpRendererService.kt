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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.jupnp.UpnpService as JupnpUpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.binding.annotations.*
import org.jupnp.model.DefaultServiceManager
import org.jupnp.model.meta.*
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDAServiceId
import org.jupnp.model.types.UDAServiceType
import org.jupnp.model.types.UDN
import org.jupnp.model.types.UnsignedIntegerFourBytes
import org.jupnp.model.types.UnsignedIntegerTwoBytes
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

private const val TAG = "DlnaRenderer"
private const val CHANNEL_ID = "dlna_renderer"
private const val NOTIFICATION_ID = 2001

/**
 * Foreground service running a UPnP/DLNA MediaRenderer via jUPnP.
 *
 * jUPnP handles ALL protocol details: SSDP, HTTP server, SOAP actions,
 * UPnP eventing, device description XML, SCPD generation — everything.
 * We only implement the action handlers wired to Android MediaPlayer.
 */
class JupnpRendererService : Service() {

    private var upnpService: JupnpUpnpService? = null
    private var deviceUuid: String = ""
    private var friendlyName: String = "Tablet Speaker"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        friendlyName = intent?.getStringExtra("friendly_name") ?: "Tablet Speaker"
        val deviceId = intent?.getStringExtra("device_id") ?: "tablet-${UUID.randomUUID().toString().take(8)}"
        deviceUuid = deviceId

        startForeground(NOTIFICATION_ID, buildNotification())

        // Initialize jUPnP on a background thread (network operations)
        Thread {
            try {
                startUpnp(deviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start jUPnP: ${e.message}", e)
            }
        }.start()

        return START_STICKY
    }

    private fun startUpnp(deviceId: String) {
        // Create and start the UPnP service
        val service = UpnpServiceImpl(AndroidUpnpServiceConfiguration())
        service.startup()
        upnpService = service

        // Create the MediaRenderer device
        val device = createDevice(deviceId)
        service.registry.addDevice(device)
        Log.i(TAG, "jUPnP MediaRenderer registered: $friendlyName (uuid:$deviceId)")
    }

    @Suppress("unchecked_cast")
    private fun createDevice(deviceId: String): LocalDevice {
        val identity = DeviceIdentity(UDN("$deviceId"))
        val type = UDADeviceType("MediaRenderer", 1)
        val details = DeviceDetails(
            friendlyName,
            ManufacturerDetails("Hermes"),
            ModelDetails("Tablet Speaker", "Wall-mounted tablet DLNA speaker", "1.0")
        )

        // --- AVTransport service ---
        val avTransportService = AnnotationLocalServiceBinder().read(TabletAVTransport::class.java) as LocalService<TabletAVTransport>
        avTransportService.manager = object : DefaultServiceManager<TabletAVTransport>(avTransportService, TabletAVTransport::class.java) {
            override fun createServiceInstance(): TabletAVTransport {
                return TabletAVTransport(this@JupnpRendererService)
            }
        }

        // --- RenderingControl service ---
        val renderingControlService = AnnotationLocalServiceBinder().read(TabletRenderingControl::class.java) as LocalService<TabletRenderingControl>
        renderingControlService.manager = object : DefaultServiceManager<TabletRenderingControl>(renderingControlService, TabletRenderingControl::class.java) {
            override fun createServiceInstance(): TabletRenderingControl {
                return TabletRenderingControl(this@JupnpRendererService)
            }
        }

        // --- ConnectionManager service ---
        val connectionManagerService = AnnotationLocalServiceBinder().read(TabletConnectionManager::class.java) as LocalService<TabletConnectionManager>
        connectionManagerService.manager = DefaultServiceManager(connectionManagerService, TabletConnectionManager::class.java)

        return LocalDevice(identity, type, details, arrayOf(avTransportService, renderingControlService, connectionManagerService))
    }

    override fun onDestroy() {
        try {
            upnpService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "jUPnP shutdown error: ${e.message}")
        }
        upnpService = null
        Log.i(TAG, "DLNA renderer stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Media playback (called from AVTransport actions on main thread)
    // -------------------------------------------------------------------------

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    internal var currentUri: String = ""
    internal var transportState: String = "NO_MEDIA_PRESENT"
    internal var volume: Int = 50
    internal var muted: Boolean = false

    internal fun setMediaUri(uri: String) {
        currentUri = uri
        transportState = "STOPPED"
        Log.i(TAG, "SetAVTransportURI: $uri")
    }

    internal fun play() {
        mainHandler.post {
            if (currentUri.isEmpty()) return@post
            releaseMediaPlayer()
            requestAudioFocus()
            // Force MODE_NORMAL so media plays through loudspeaker at full volume
            // (LiveKit WebRTC sets MODE_IN_COMMUNICATION which ducks media audio)
            if (audioManager == null) audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.mode = AudioManager.MODE_NORMAL
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
                    Log.i(TAG, "Playing: $currentUri (duration=${it.duration}ms)")
                }
                setOnCompletionListener {
                    transportState = "STOPPED"
                    releaseAudioFocus()
                    Log.i(TAG, "Playback complete")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra uri=$currentUri")
                    transportState = "STOPPED"
                    releaseAudioFocus()
                    true
                }
                prepareAsync()
            }
            transportState = "TRANSITIONING"
        }
    }

    internal fun stop() {
        mainHandler.post {
            mediaPlayer?.let { if (it.isPlaying) it.stop() }
            transportState = "STOPPED"
            releaseAudioFocus()
            // Restore communication mode for LiveKit WebRTC
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "Stopped")
        }
    }

    internal fun pause() {
        mainHandler.post {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    transportState = "PAUSED_PLAYBACK"
                    Log.i(TAG, "Paused")
                }
            }
        }
    }

    internal fun setVolumeTo(vol: Int) {
        volume = vol.coerceIn(0, 100)
        mediaPlayer?.setVolume(volume / 100f, volume / 100f)
    }

    internal fun setMuteTo(mute: Boolean) {
        muted = mute
        val v = if (mute) 0f else volume / 100f
        mediaPlayer?.setVolume(v, v)
    }

    internal fun getCurrentPositionMs(): Int = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    internal fun getDurationMs(): Int = try { mediaPlayer?.duration ?: 0 } catch (_: Exception) { 0 }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun requestAudioFocus() {
        if (audioManager == null) audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.setVolume(volume / 100f, volume / 100f)
                }
            }
            .build()
        audioManager?.requestAudioFocus(request)
        audioFocusRequest = request
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "DLNA Speaker", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DLNA Speaker")
            .setContentText("$friendlyName — available on network")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}

// =============================================================================
// UPnP Service: AVTransport
// =============================================================================

@UpnpService(
    serviceId = UpnpServiceId("AVTransport"),
    serviceType = UpnpServiceType("AVTransport", version = 1)
)
@UpnpStateVariables(
    UpnpStateVariable(name = "TransportState", datatype = "string", defaultValue = "NO_MEDIA_PRESENT", sendEvents = false),
    UpnpStateVariable(name = "TransportStatus", datatype = "string", defaultValue = "OK", sendEvents = false),
    UpnpStateVariable(name = "TransportPlaySpeed", datatype = "string", defaultValue = "1", sendEvents = false),
    UpnpStateVariable(name = "AVTransportURI", datatype = "string", defaultValue = "", sendEvents = false),
    UpnpStateVariable(name = "AVTransportURIMetaData", datatype = "string", defaultValue = "", sendEvents = false),
    UpnpStateVariable(name = "CurrentTrack", datatype = "ui4", defaultValue = "0", sendEvents = false),
    UpnpStateVariable(name = "CurrentTrackDuration", datatype = "string", defaultValue = "00:00:00", sendEvents = false),
    UpnpStateVariable(name = "CurrentTrackMetaData", datatype = "string", defaultValue = "", sendEvents = false),
    UpnpStateVariable(name = "CurrentTrackURI", datatype = "string", defaultValue = "", sendEvents = false),
    UpnpStateVariable(name = "RelativeTimePosition", datatype = "string", defaultValue = "00:00:00", sendEvents = false),
    UpnpStateVariable(name = "AbsoluteTimePosition", datatype = "string", defaultValue = "00:00:00", sendEvents = false),
    UpnpStateVariable(name = "RelativeCounterPosition", datatype = "i4", defaultValue = "0", sendEvents = false),
    UpnpStateVariable(name = "AbsoluteCounterPosition", datatype = "i4", defaultValue = "0", sendEvents = false),
    UpnpStateVariable(name = "A_ARG_TYPE_InstanceID", datatype = "ui4", defaultValue = "0", sendEvents = false),
    UpnpStateVariable(name = "A_ARG_TYPE_SeekMode", datatype = "string", defaultValue = "REL_TIME", sendEvents = false, allowedValuesEnum = SeekModeValues::class),
    UpnpStateVariable(name = "A_ARG_TYPE_SeekTarget", datatype = "string", defaultValue = "00:00:00", sendEvents = false)
)
class TabletAVTransport(private val service: JupnpRendererService) {

    // No-arg constructor required by jUPnP reflection
    @Suppress("unused")
    constructor() : this(null as JupnpRendererService)

    private val svc: JupnpRendererService get() = service

    @UpnpAction
    fun setAVTransportURI(
        @UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes,
        @UpnpInputArgument(name = "CurrentURI") currentURI: String?,
        @UpnpInputArgument(name = "CurrentURIMetaData") currentURIMetaData: String?
    ) {
        Log.i("DlnaRenderer", "SetAVTransportURI: $currentURI")
        svc.setMediaUri(currentURI ?: "")
    }

    @UpnpAction
    fun play(
        @UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes,
        @UpnpInputArgument(name = "Speed", stateVariable = "TransportPlaySpeed") speed: String?
    ) {
        Log.i("DlnaRenderer", "Play")
        svc.play()
    }

    @UpnpAction
    fun stop(@UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes) {
        Log.i("DlnaRenderer", "Stop")
        svc.stop()
    }

    @UpnpAction
    fun pause(@UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes) {
        Log.i("DlnaRenderer", "Pause")
        svc.pause()
    }

    @UpnpAction(out = [
        UpnpOutputArgument(name = "CurrentTransportState", stateVariable = "TransportState"),
        UpnpOutputArgument(name = "CurrentTransportStatus", stateVariable = "TransportStatus"),
        UpnpOutputArgument(name = "CurrentSpeed", stateVariable = "TransportPlaySpeed")
    ])
    fun getTransportInfo(@UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes): Map<String, String> {
        return mapOf(
            "CurrentTransportState" to svc.transportState,
            "CurrentTransportStatus" to "OK",
            "CurrentSpeed" to "1"
        )
    }

    @UpnpAction(out = [
        UpnpOutputArgument(name = "Track", stateVariable = "CurrentTrack"),
        UpnpOutputArgument(name = "TrackDuration", stateVariable = "CurrentTrackDuration"),
        UpnpOutputArgument(name = "TrackMetaData", stateVariable = "CurrentTrackMetaData"),
        UpnpOutputArgument(name = "TrackURI", stateVariable = "CurrentTrackURI"),
        UpnpOutputArgument(name = "RelTime", stateVariable = "RelativeTimePosition"),
        UpnpOutputArgument(name = "AbsTime", stateVariable = "AbsoluteTimePosition"),
        UpnpOutputArgument(name = "RelCount", stateVariable = "RelativeCounterPosition"),
        UpnpOutputArgument(name = "AbsCount", stateVariable = "AbsoluteCounterPosition")
    ])
    fun getPositionInfo(@UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes): Map<String, String> {
        val pos = formatDuration(svc.getCurrentPositionMs())
        val dur = formatDuration(svc.getDurationMs())
        return mapOf(
            "Track" to "1",
            "TrackDuration" to dur,
            "TrackMetaData" to "",
            "TrackURI" to svc.currentUri,
            "RelTime" to pos,
            "AbsTime" to pos,
            "RelCount" to "0",
            "AbsCount" to "0"
        )
    }

    private fun formatDuration(ms: Int): String {
        val totalSecs = ms / 1000
        return "%d:%02d:%02d".format(totalSecs / 3600, (totalSecs % 3600) / 60, totalSecs % 60)
    }
}

enum class SeekModeValues { REL_TIME, TRACK_NR }

// =============================================================================
// UPnP Service: RenderingControl
// =============================================================================

@UpnpService(
    serviceId = UpnpServiceId("RenderingControl"),
    serviceType = UpnpServiceType("RenderingControl", version = 1)
)
@UpnpStateVariables(
    UpnpStateVariable(name = "Volume", datatype = "ui2", defaultValue = "50", sendEvents = false,
        allowedValueMinimum = 0, allowedValueMaximum = 100),
    UpnpStateVariable(name = "Mute", datatype = "boolean", defaultValue = "0", sendEvents = false),
    UpnpStateVariable(name = "A_ARG_TYPE_InstanceID", datatype = "ui4", defaultValue = "0", sendEvents = false),
    UpnpStateVariable(name = "A_ARG_TYPE_Channel", datatype = "string", defaultValue = "Master", sendEvents = false)
)
class TabletRenderingControl(private val service: JupnpRendererService) {

    @Suppress("unused")
    constructor() : this(null as JupnpRendererService)

    private val svc: JupnpRendererService get() = service

    @UpnpAction(out = [UpnpOutputArgument(name = "CurrentVolume", stateVariable = "Volume")])
    fun getVolume(
        @UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes,
        @UpnpInputArgument(name = "Channel") channel: String?
    ): UnsignedIntegerTwoBytes = UnsignedIntegerTwoBytes(svc.volume.toLong())

    @UpnpAction
    fun setVolume(
        @UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes,
        @UpnpInputArgument(name = "Channel") channel: String?,
        @UpnpInputArgument(name = "DesiredVolume", stateVariable = "Volume") desiredVolume: UnsignedIntegerTwoBytes
    ) {
        svc.setVolumeTo(desiredVolume.value.toInt())
    }

    @UpnpAction(out = [UpnpOutputArgument(name = "CurrentMute", stateVariable = "Mute")])
    fun getMute(
        @UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes,
        @UpnpInputArgument(name = "Channel") channel: String?
    ): Boolean = svc.muted

    @UpnpAction
    fun setMute(
        @UpnpInputArgument(name = "InstanceID") instanceId: UnsignedIntegerFourBytes,
        @UpnpInputArgument(name = "Channel") channel: String?,
        @UpnpInputArgument(name = "DesiredMute", stateVariable = "Mute") desiredMute: Boolean
    ) {
        svc.setMuteTo(desiredMute)
    }
}

// =============================================================================
// UPnP Service: ConnectionManager
// =============================================================================

@UpnpService(
    serviceId = UpnpServiceId("ConnectionManager"),
    serviceType = UpnpServiceType("ConnectionManager", version = 1)
)
@UpnpStateVariables(
    UpnpStateVariable(name = "SourceProtocolInfo", datatype = "string", defaultValue = "", sendEvents = false),
    UpnpStateVariable(name = "SinkProtocolInfo", datatype = "string", defaultValue = "", sendEvents = false)
)
class TabletConnectionManager {

    @UpnpAction(out = [
        UpnpOutputArgument(name = "Source", stateVariable = "SourceProtocolInfo"),
        UpnpOutputArgument(name = "Sink", stateVariable = "SinkProtocolInfo")
    ])
    fun getProtocolInfo(): Map<String, String> {
        val sink = listOf(
            "http-get:*:audio/mpeg:*",
            "http-get:*:audio/mp3:*",
            "http-get:*:audio/mp4:*",
            "http-get:*:audio/aac:*",
            "http-get:*:audio/ogg:*",
            "http-get:*:audio/flac:*",
            "http-get:*:audio/wav:*",
            "http-get:*:audio/x-wav:*",
            "http-get:*:audio/*:*"
        ).joinToString(",")
        return mapOf("Source" to "", "Sink" to sink)
    }
}
