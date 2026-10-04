// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.victorialauncher.R
import dev.victorialauncher.data.ContextSnapshot
import dev.victorialauncher.data.HeadsetCondition
import dev.victorialauncher.data.SeenAudioDevice
import dev.victorialauncher.data.VisibilityRule

/**
 * Which headset a rule waits for. No permission is involved: the system says which audio
 * outputs are connected to anyone who asks, and the launcher remembers the ones it has seen
 * so a device can be picked while it is in a drawer.
 */
@Composable
fun HeadsetPickerScreen(
    rule: VisibilityRule?,
    snapshot: ContextSnapshot,
    seenDevices: List<SeenAudioDevice>,
    onChange: (VisibilityRule) -> Unit,
    onBack: () -> Unit,
) {
    val current = rule ?: VisibilityRule()
    val headset = current.headset
    val picked = (headset as? HeadsetCondition.Named)?.names.orEmpty()
    val connected = snapshot.audioOutputs.filter { it.name.isNotBlank() }.distinctBy { it.name }
    val connectedNames = connected.map { it.name }.toSet()
    val earlier = seenDevices.filter { it.name !in connectedNames }
    // A name picked once and never seen since — typed into another phone's backup, say — still
    // has to be somewhere it can be unticked.
    val strays = picked.filter { it !in connectedNames && earlier.none { d -> d.name == it } }

    fun set(condition: HeadsetCondition?) = onChange(current.copy(headset = condition))
    fun toggle(name: String, on: Boolean) = set(HeadsetCondition.Named(if (on) picked + name else picked - name))

    RuleScaffold(title = stringResource(R.string.headset_title), onBack = onBack) {
        Spacer(Modifier.padding(top = 8.dp))
        RuleCard {
            RadioRow(stringResource(R.string.rule_not_used), null, headset == null) { set(null) }
            CardDivider()
            RadioRow(stringResource(R.string.headset_any), stringResource(R.string.headset_any_detail), headset == HeadsetCondition.Any) {
                set(HeadsetCondition.Any)
            }
            CardDivider()
            RadioRow(
                stringResource(R.string.headset_wireless),
                stringResource(R.string.headset_wireless_detail),
                headset == HeadsetCondition.Wireless,
            ) { set(HeadsetCondition.Wireless) }
            CardDivider()
            RadioRow(stringResource(R.string.headset_named), null, headset is HeadsetCondition.Named) {
                // Starts from whatever is connected, which is usually the device meant.
                if (headset !is HeadsetCondition.Named) set(HeadsetCondition.Named(connectedNames))
            }
        }

        if (headset is HeadsetCondition.Named) {
            SectionHeader(stringResource(R.string.headset_connected_now))
            RuleCard {
                if (connected.isEmpty()) InfoRow(stringResource(R.string.rules_now_no_headset))
                connected.forEachIndexed { index, output ->
                    if (index > 0) CardDivider()
                    CheckRow(
                        title = output.name,
                        detail = kindLabel(output.wireless),
                        tag = stringResource(R.string.wifi_tag_now),
                        checked = output.name in picked,
                        onToggle = { toggle(output.name, it) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.headset_seen_before))
            RuleCard {
                if (earlier.isEmpty() && strays.isEmpty()) InfoRow(stringResource(R.string.headset_none_seen))
                earlier.forEachIndexed { index, device ->
                    if (index > 0) CardDivider()
                    CheckRow(
                        title = device.name,
                        detail = kindLabel(device.wireless) + " · " +
                            DateUtils.getRelativeTimeSpanString(device.lastSeenMillis).toString(),
                        checked = device.name in picked,
                        onToggle = { toggle(device.name, it) },
                    )
                }
                strays.forEach { name ->
                    CardDivider()
                    CheckRow(title = name, checked = true, onToggle = { toggle(name, it) })
                }
            }

            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.headset_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun kindLabel(wireless: Boolean) =
    stringResource(if (wireless) R.string.headset_kind_wireless else R.string.headset_kind_wired)

@Composable
internal fun RadioRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
