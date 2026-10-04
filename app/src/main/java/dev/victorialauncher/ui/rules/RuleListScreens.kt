// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.victorialauncher.R
import dev.victorialauncher.data.ContextSnapshot
import dev.victorialauncher.data.VisibilityRule
import dev.victorialauncher.data.WifiNameAccess
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A favorite as these screens list it: its key, its name as shown, and its icon. */
class RuleTarget(val key: String, val name: String, val icon: @Composable () -> Unit)

/** Copies one favorite's rule onto others, picked from the rest of the favorites. */
@Composable
fun ApplyRuleScreen(
    sourceKey: String,
    targets: List<RuleTarget>,
    rules: Map<String, VisibilityRule>,
    onApply: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var picked by remember { mutableStateOf(emptySet<String>()) }
    RuleScaffold(
        title = stringResource(R.string.apply_title),
        onBack = onBack,
        actions = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onApply(picked) }) {
                Text(stringResource(R.string.apply_confirm))
            }
        },
    ) {
        Text(
            stringResource(R.string.apply_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        RuleCard {
            targets.filter { it.key != sourceKey }.forEachIndexed { index, target ->
                if (index > 0) CardDivider()
                val checked = target.key in picked
                TargetRow(
                    target = target,
                    detail = if (rules[target.key]?.hasConditions == true) stringResource(R.string.apply_has_rule) else null,
                    onClick = { picked = if (checked) picked - target.key else picked + target.key },
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
}

/**
 * Settings' page for every rule at once: the one switch that sets them all aside, what the
 * phone is doing right now as the rules see it, and the favorites that have one.
 */
@Composable
fun RulesOverviewScreen(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    snapshot: ContextSnapshot,
    wifiAccess: WifiNameAccess,
    targets: List<RuleTarget>,
    rules: Map<String, VisibilityRule>,
    packagesOf: (String) -> Set<String>,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    RuleScaffold(title = stringResource(R.string.rules_title), onBack = onBack) {
        Spacer(Modifier.padding(top = 8.dp))
        RuleCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSetEnabled(!enabled) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rules_enabled), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.rules_enabled_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }
        }

        SectionHeader(stringResource(R.string.rules_now))
        RuleCard {
            NowRow(
                Icons.Filled.Wifi,
                when {
                    !snapshot.wifiConnected -> stringResource(R.string.wifi_not_connected)
                    snapshot.ssid == null -> stringResource(R.string.wifi_name_unavailable)
                    else -> snapshot.ssid
                },
            )
            if (snapshot.wifiConnected && wifiAccess != WifiNameAccess.GRANTED) WarningRow(wifiAccessMessage(wifiAccess))
            CardDivider()
            NowRow(
                Icons.Filled.Headphones,
                snapshot.audioOutputs.map { it.name.ifBlank { null } ?: stringResource(R.string.headset_unnamed) }
                    .distinct().joinToString(", ")
                    .ifEmpty { stringResource(R.string.rules_now_no_headset) },
            )
            CardDivider()
            NowRow(
                Icons.Filled.Schedule,
                snapshot.now.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
            )
        }

        val withRules = targets.filter { rules[it.key]?.hasConditions == true }
        SectionHeader(stringResource(R.string.rules_list))
        RuleCard {
            if (withRules.isEmpty()) InfoRow(stringResource(R.string.rules_none))
            withRules.forEachIndexed { index, target ->
                if (index > 0) CardDivider()
                val rule = rules.getValue(target.key)
                TargetRow(target = target, detail = ruleSummary(rule), onClick = { onEdit(target.key) }) {
                    if (enabled && rule.isActive) {
                        val visible = rule.isVisible(snapshot, packagesOf(target.key))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (visible) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                stringResource(if (visible) R.string.rules_shown else R.string.rules_hidden),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowRow(icon: ImageVector, text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TargetRow(
    target: RuleTarget,
    detail: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        target.icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(target.name, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
