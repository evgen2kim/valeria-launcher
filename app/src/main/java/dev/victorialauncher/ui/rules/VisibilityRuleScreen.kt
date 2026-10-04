// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.victorialauncher.R
import dev.victorialauncher.data.ContextSnapshot
import dev.victorialauncher.data.VisibilityMode
import dev.victorialauncher.data.VisibilityRule
import dev.victorialauncher.data.WifiNameAccess

/**
 * Where one favorite's rule is written: the mode, then one section per kind of condition.
 *
 * Every change is saved as it is made, like the rest of Settings — there is no Save to forget.
 * The sentence at the top and the line under it say what the rule does and what it is doing
 * right now, which is what makes a rule checkable without leaving the screen to look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisibilityRuleScreen(
    name: String,
    icon: @Composable () -> Unit,
    /** What is stored; null for a favorite that has never had a rule. */
    rule: VisibilityRule?,
    snapshot: ContextSnapshot,
    rulesEnabled: Boolean,
    wifiAccess: WifiNameAccess,
    onChange: (VisibilityRule) -> Unit,
    onOpenWifi: () -> Unit,
    onOpenTime: () -> Unit,
    onAddWindow: () -> Unit,
    onOpenHeadset: () -> Unit,
    onApplyToOthers: () -> Unit,
    onBack: () -> Unit,
) {
    // A favorite with no rule is shown as "Only when" with nothing under it: whoever opened
    // "Show when…" has come to add a condition, and the first one added should just work.
    val current = rule ?: VisibilityRule()

    RuleScaffold(title = stringResource(R.string.rule_title), onBack = onBack) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.rule_object_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val modes = listOf(
            VisibilityMode.ALWAYS to R.string.rule_mode_always,
            VisibilityMode.ONLY_WHEN to R.string.rule_mode_only_when,
            VisibilityMode.HIDE_WHEN to R.string.rule_mode_hide_when,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = current.mode == mode,
                    onClick = { onChange(current.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }

        val sentence = ruleSentence(current)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        ) {
            Text(
                sentence ?: stringResource(
                    if (current.mode == VisibilityMode.ALWAYS) R.string.rule_always_hint else R.string.rule_empty_hint
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        RuleStatus(current, snapshot, rulesEnabled)

        // "Always" keeps the conditions but they do nothing, so they are put away rather than
        // left on screen looking as if they still applied.
        if (current.mode != VisibilityMode.ALWAYS) {
            SectionHeader(stringResource(R.string.rule_section_wifi), Icons.Filled.Wifi)
            RuleCard {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (current.anyWifi) {
                        RemovableChip(stringResource(R.string.wifi_any)) { onChange(current.copy(anyWifi = false)) }
                    } else {
                        current.wifi.forEach { ssid ->
                            RemovableChip(ssid) { onChange(current.copy(wifi = current.wifi - ssid)) }
                        }
                    }
                    AssistChip(
                        onClick = onOpenWifi,
                        label = { Text(stringResource(R.string.rule_add_network)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
                // A named network is the one condition a missing permission silently breaks,
                // so it says so right where the network is.
                if (!current.anyWifi && current.wifi.isNotEmpty() && wifiAccess != WifiNameAccess.GRANTED) {
                    WarningRow(wifiAccessMessage(wifiAccess), onClick = onOpenWifi)
                }
            }

            SectionHeader(stringResource(R.string.rule_section_time), Icons.Filled.Schedule)
            RuleCard {
                current.windows.forEachIndexed { index, window ->
                    if (index > 0) CardDivider()
                    NavRow(windowLabel(window), onClick = onOpenTime)
                }
                if (current.windows.isNotEmpty()) CardDivider()
                AddRow(stringResource(R.string.rule_add_window), onClick = onAddWindow)
            }

            SectionHeader(stringResource(R.string.rule_section_headset), Icons.Filled.Headphones)
            RuleCard {
                NavRow(
                    title = headsetLabel(current.headset),
                    detail = if (current.headset == null) stringResource(R.string.rule_tap_to_add) else null,
                    onClick = onOpenHeadset,
                )
            }
        }

        OutlinedButton(
            onClick = onApplyToOthers,
            enabled = current.hasConditions,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rule_apply_to_others))
        }
    }
}

@Composable
private fun RuleStatus(rule: VisibilityRule, snapshot: ContextSnapshot, rulesEnabled: Boolean) {
    val text: String
    val color: Color
    when {
        !rule.isActive -> return
        !rulesEnabled -> {
            text = stringResource(R.string.rule_status_disabled)
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        }
        else -> {
            val visible = rule.isVisible(snapshot)
            text = stringResource(
                when {
                    rule.mode == VisibilityMode.HIDE_WHEN && visible -> R.string.rule_status_shown_by_hide
                    rule.mode == VisibilityMode.HIDE_WHEN -> R.string.rule_status_hidden_by_hide
                    visible -> R.string.rule_status_shown
                    else -> R.string.rule_status_hidden
                }
            )
            color = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
        }
    }
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun wifiAccessMessage(access: WifiNameAccess): String = stringResource(
    when (access) {
        WifiNameAccess.APPROXIMATE_ONLY -> R.string.wifi_perm_banner_approx
        WifiNameAccess.LOCATION_OFF -> R.string.wifi_perm_banner_off
        else -> R.string.wifi_perm_banner_none
    }
)

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.rule_remove_condition),
                modifier = Modifier.size(InputChipDefaults.AvatarSize),
            )
        },
    )
}

// ---- Shared by every rule screen: the Settings look, sized for a column of cards ----

@Composable
internal fun RuleScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Scaffold(
        containerColor = surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp)),
            content = content,
        )
    }
}

@Composable
internal fun SectionHeader(text: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun RuleCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun CardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
internal fun NavRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun AddRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun WarningRow(text: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
