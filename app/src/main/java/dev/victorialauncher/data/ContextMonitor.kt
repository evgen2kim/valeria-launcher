// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Why the connected network's name can or cannot be read. */
enum class WifiNameAccess {
    GRANTED,
    /** No location permission at all. */
    NO_PERMISSION,
    /** Android 12+ lets someone grant only approximate location, which is not enough. */
    APPROXIMATE_ONLY,
    /** Granted, but location is switched off for the whole phone, which withholds it anyway. */
    LOCATION_OFF,
}

/**
 * Keeps a [ContextSnapshot] current for the visibility rules: which Wi-Fi network, what time
 * it is, which headsets are connected.
 *
 * Only while the launcher is on screen. Nothing here runs in the background — a rule is only
 * ever looked at by a home screen that is showing, and coming back to it is the moment the
 * answer is read again ([start] re-reads everything rather than trusting what it last saw).
 *
 * Every source is a callback rather than a poll: a network callback, an audio device callback,
 * and the system's own once-a-minute tick for the clock.
 */
class ContextMonitor(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _snapshot = MutableStateFlow(
        ContextSnapshot(wifiConnected = false, ssid = null, now = LocalDateTime.now(), audioOutputs = emptyList()),
    )
    val snapshot: StateFlow<ContextSnapshot> = _snapshot.asStateFlow()

    private var started = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Every Wi-Fi network currently up, and the name each one reported, if any. */
    private val wifiNetworks = HashMap<Network, String?>()

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            _snapshot.update { it.copy(now = LocalDateTime.now()) }
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshAudio()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshAudio()
    }

    /** Counted, so two screens asking for it do not stop it for each other. Main thread only. */
    fun start() {
        if (started++ > 0) return
        _snapshot.update { it.copy(now = LocalDateTime.now()) }
        ContextCompat.registerReceiver(
            context,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registerNetworkCallback()
        // Fires once at registration with every device already connected.
        runCatching { audio.registerAudioDeviceCallback(audioCallback, mainHandler) }
        refreshAudio()
    }

    fun stop() {
        if (started == 0 || --started > 0) return
        runCatching { context.unregisterReceiver(timeReceiver) }
        unregisterNetworkCallback()
        runCatching { audio.unregisterAudioDeviceCallback(audioCallback) }
    }

    /**
     * Reads the network again from scratch. For when the answer may have changed without the
     * network doing anything: location was just granted or switched on, so a name that was
     * withheld a moment ago is readable now.
     */
    fun refreshWifi() {
        if (started == 0) return
        unregisterNetworkCallback()
        registerNetworkCallback()
    }

    fun wifiNameAccess(): WifiNameAccess {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            !fine && coarse -> WifiNameAccess.APPROXIMATE_ONLY
            !fine -> WifiNameAccess.NO_PERMISSION
            !locationEnabled() -> WifiNameAccess.LOCATION_OFF
            else -> WifiNameAccess.GRANTED
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun locationEnabled(): Boolean = runCatching {
        val manager = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
    }.getOrDefault(true)

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // From Android 12 a network callback is handed the SSID only when it asks for location
        // information outright, and only with the permission behind it.
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(network, caps)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(network, caps)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        }
        wifiNetworks.clear()
        publishWifi()
        runCatching { connectivity.registerNetworkCallback(request, callback, mainHandler) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun onWifi(network: Network, caps: NetworkCapabilities) {
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps.transportInfo as? WifiInfo)?.ssid
        } else {
            @Suppress("DEPRECATION")
            runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
        }
        wifiNetworks[network] = cleanSsid(ssid)
        publishWifi()
    }

    private fun onWifiLost(network: Network) {
        wifiNetworks.remove(network)
        publishWifi()
    }

    private fun publishWifi() {
        val connected = wifiNetworks.isNotEmpty()
        val ssid = wifiNetworks.values.firstOrNull { it != null }
        _snapshot.update { it.copy(wifiConnected = connected, ssid = ssid) }
    }

    private fun refreshAudio() {
        val outputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
            .mapNotNull { it.toHeadset() }
            .distinctBy { it.name to it.wireless }
        _snapshot.update { it.copy(audioOutputs = outputs) }
        if (outputs.isNotEmpty()) {
            scope.launch { prefs.recordAudioOutputs(outputs, System.currentTimeMillis()) }
        }
    }

    companion object {
        /** Absent from older SDK levels, so spelled out; values are from AudioDeviceInfo. */
        private const val TYPE_HEARING_AID = 23
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27
        private const val TYPE_BLE_BROADCAST = 30

        private val WIRELESS_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            TYPE_HEARING_AID,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER,
            TYPE_BLE_BROADCAST,
        )
        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        private fun AudioDeviceInfo.toHeadset(): AudioOutput? {
            val wireless = type in WIRELESS_TYPES
            if (!wireless && type !in WIRED_TYPES) return null
            return AudioOutput(name = productName?.toString()?.trim().orEmpty(), wireless = wireless)
        }

        /** The system wraps a readable SSID in quotes and reports an unreadable one as a placeholder. */
        fun cleanSsid(raw: String?): String? {
            if (raw.isNullOrBlank() || raw == WifiManager.UNKNOWN_SSID) return null
            return raw.removeSurrounding("\"").takeIf { it.isNotEmpty() }
        }
    }
}
