// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.victorialauncher.R
import dev.victorialauncher.data.ContextMonitor
import dev.victorialauncher.data.ContextSnapshot
import dev.victorialauncher.data.VisibilityRule
import dev.victorialauncher.data.WifiNameAccess

/**
 * Picks the networks a rule waits for.
 *
 * Android stopped telling apps which networks are saved, so the choices are the one connected
 * now, the ones in range, and any name typed in by hand. All three need precise location —
 * Android treats a network's name as a clue to where the phone is — which is asked for here,
 * with the reason given first, and nowhere else.
 */
@Composable
fun WifiPickerScreen(
    rule: VisibilityRule?,
    snapshot: ContextSnapshot,
    readAccess: () -> WifiNameAccess,
    /** Location was just granted or switched on: the network's name may be readable now. */
    onAccessChanged: () -> Unit,
    onChange: (VisibilityRule) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val current = rule ?: VisibilityRule()
    var access by remember { mutableStateOf(readAccess()) }
    // Asked once per visit; after that the banner is where it lives.
    var showRationale by rememberSaveable { mutableStateOf(access == WifiNameAccess.NO_PERMISSION || access == WifiNameAccess.APPROXIMATE_ONLY) }
    // Android stops showing its own dialog after two refusals, so the way forward becomes Settings.
    var deniedForGood by rememberSaveable { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var scanTick by remember { mutableIntStateOf(0) }
    var nearby by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = readAccess()
        onAccessChanged()
        if (access == WifiNameAccess.NO_PERMISSION || access == WifiNameAccess.APPROXIMATE_ONLY) {
            val activity = context as? Activity
            deniedForGood = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        scanTick++
    }
    fun requestPermission() = permissionLauncher.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )

    // Location can be granted or switched on from Settings while this screen is behind it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val fresh = readAccess()
                if (fresh != access) {
                    access = fresh
                    onAccessChanged()
                    scanTick++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val granted = access == WifiNameAccess.GRANTED
    DisposableEffect(granted, scanTick) {
        if (!granted) return@DisposableEffect onDispose {}
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        fun read() {
            nearby = scanResults(wifi)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            // A protected system broadcast reaches an unexported receiver too.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Whatever the system already has comes up at once; a fresh scan may follow, or may be
        // refused — Android allows only a few a minute — in which case these are what there is.
        read()
        @Suppress("DEPRECATION")
        runCatching { wifi.startScan() }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    fun toggle(ssid: String, on: Boolean) {
        val list = if (on) (current.wifi + ssid).distinct() else current.wifi - ssid
        onChange(current.copy(anyWifi = false, wifi = list))
    }

    val connected = snapshot.ssid
    val nearbyNames = nearby.map { it.first }.filter { it != connected }
    val others = current.wifi.filter { it != connected && it !in nearbyNames }

    RuleScaffold(
        title = stringResource(R.string.wifi_title),
        onBack = onBack,
        actions = {
            if (granted) {
                IconButton(onClick = { scanTick++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.wifi_scan_again))
                }
            }
        },
    ) {
        if (!granted) {
            AccessBanner(
                access = access,
                deniedForGood = deniedForGood,
                onGrant = ::requestPermission,
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onOpenLocationSettings = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }

        Spacer(Modifier.padding(top = 8.dp))
        RuleCard {
            CheckRow(
                title = stringResource(R.string.wifi_any),
                detail = stringResource(R.string.wifi_any_detail),
                checked = current.anyWifi,
                onToggle = { onChange(current.copy(anyWifi = it)) },
            )
        }

        if (!current.anyWifi) {
            SectionHeader(stringResource(R.string.wifi_connected_now))
            RuleCard {
                when {
                    !snapshot.wifiConnected -> InfoRow(stringResource(R.string.wifi_not_connected))
                    connected == null -> InfoRow(stringResource(R.string.wifi_name_unavailable))
                    else -> CheckRow(
                        title = connected,
                        tag = stringResource(R.string.wifi_tag_now),
                        checked = connected in current.wifi,
                        onToggle = { toggle(connected, it) },
                    )
                }
            }

            if (granted) {
                SectionHeader(stringResource(R.string.wifi_nearby))
                RuleCard {
                    if (nearbyNames.isEmpty()) InfoRow(stringResource(R.string.wifi_none_nearby))
                    nearbyNames.forEachIndexed { index, ssid ->
                        if (index > 0) CardDivider()
                        CheckRow(title = ssid, checked = ssid in current.wifi, onToggle = { toggle(ssid, it) })
                    }
                }
            }

            SectionHeader(stringResource(R.string.wifi_other))
            RuleCard {
                others.forEach { ssid ->
                    CheckRow(
                        title = ssid,
                        detail = stringResource(R.string.wifi_manual_detail),
                        checked = true,
                        onToggle = { toggle(ssid, it) },
                    )
                    CardDivider()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showManualEntry = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.wifi_enter_name), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.wifi_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
            title = { Text(stringResource(R.string.wifi_perm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.wifi_perm_body))
                    Spacer(Modifier.padding(top = 10.dp))
                    Text(
                        stringResource(R.string.wifi_perm_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRationale = false; requestPermission() }) {
                    Text(stringResource(R.string.wifi_perm_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text(stringResource(R.string.wifi_perm_not_now)) }
            },
        )
    }

    if (showManualEntry) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualEntry = false },
            title = { Text(stringResource(R.string.wifi_enter_name)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.wifi_name_hint)) },
                )
            },
            confirmButton = {
                // Not trimmed: a space is a legal part of a network's name, and this is
                // compared exactly.
                TextButton(enabled = text.isNotEmpty(), onClick = { toggle(text, true); showManualEntry = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualEntry = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/**
 * Every network name in range once, strongest first. Hidden networks have no name to list.
 *
 * Only called once location is granted, and a permission revoked in between is the
 * SecurityException the runCatching turns into an empty list.
 */
@SuppressLint("MissingPermission")
private fun scanResults(wifi: WifiManager): List<Pair<String, Int>> = runCatching {
    @Suppress("DEPRECATION")
    wifi.scanResults
        .mapNotNull { result -> ContextMonitor.cleanSsid(result.SSID)?.let { it to result.level } }
        .groupBy({ it.first }, { it.second })
        .map { (ssid, levels) -> ssid to levels.max() }
        .sortedByDescending { it.second }
}.getOrDefault(emptyList())

@Composable
private fun AccessBanner(
    access: WifiNameAccess,
    deniedForGood: Boolean,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text(
                wifiAccessMessage(access),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(Modifier.align(Alignment.End)) {
                when {
                    access == WifiNameAccess.LOCATION_OFF ->
                        TextButton(onClick = onOpenLocationSettings) { Text(stringResource(R.string.wifi_location_turn_on)) }
                    deniedForGood ->
                        TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.wifi_perm_open_settings)) }
                    else ->
                        TextButton(onClick = onGrant) { Text(stringResource(R.string.wifi_perm_grant)) }
                }
            }
        }
    }
}

@Composable
internal fun CheckRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    detail: String? = null,
    tag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row owns the tap; see SwitchRow in Settings for why the box does not too.
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        if (tag != null) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.SignalWifi4Bar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun InfoRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
