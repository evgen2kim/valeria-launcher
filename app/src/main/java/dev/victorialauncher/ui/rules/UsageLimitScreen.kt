// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.victorialauncher.R
import dev.victorialauncher.data.ContextSnapshot
import dev.victorialauncher.data.SessionTuning
import dev.victorialauncher.data.UsageLimit
import dev.victorialauncher.data.UsageSession
import dev.victorialauncher.data.VisibilityMode
import dev.victorialauncher.data.VisibilityRule
import java.util.Date

/**
 * A daily allowance of sessions, and today's log of them.
 *
 * The log is what makes the counting checkable against the screen-time app it sits beside:
 * every visit is listed, the ones too short to count included and marked, so the two numbers
 * under "Counting" can be set until this screen and that app agree.
 */
@Composable
fun UsageLimitScreen(
    rule: VisibilityRule?,
    snapshot: ContextSnapshot,
    packages: Set<String>,
    appName: (String) -> String,
    hasAccess: Boolean,
    onGrantAccess: () -> Unit,
    tuning: SessionTuning,
    onTuningChange: (SessionTuning) -> Unit,
    onRefresh: () -> Unit,
    onChange: (VisibilityRule) -> Unit,
    onBack: () -> Unit,
) {
    val current = rule ?: VisibilityRule()
    val limit = current.usageLimit

    fun setLimit(value: UsageLimit?) {
        // A limit on its own only makes sense as "hide when it is used up", so that is what a
        // rule with nothing else in it becomes. One that already says something is left alone.
        val onlyCondition = !current.hasWifi && current.windows.isEmpty() && current.headset == null
        val mode = if (value != null && onlyCondition && current.mode == VisibilityMode.ONLY_WHEN) VisibilityMode.HIDE_WHEN else current.mode
        onChange(current.copy(usageLimit = value, mode = mode))
    }

    RuleScaffold(
        title = stringResource(R.string.limit_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.limit_refresh))
            }
        },
    ) {
        if (!hasAccess) {
            Spacer(Modifier.padding(top = 8.dp))
            RuleCard {
                WarningRow(stringResource(R.string.limit_access_needed))
                FilledTonalButton(
                    onClick = onGrantAccess,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) { Text(stringResource(R.string.limit_grant_access)) }
            }
        }

        Spacer(Modifier.padding(top = 8.dp))
        RuleCard {
            RadioRow(stringResource(R.string.rule_not_used), null, limit == null) { setLimit(null) }
            CardDivider()
            RadioRow(stringResource(R.string.limit_sessions_per_day), stringResource(R.string.limit_sessions_detail), limit != null) {
                if (limit == null) setLimit(UsageLimit.DEFAULT)
            }
            if (limit != null) {
                CardDivider()
                StepperRow(
                    title = stringResource(R.string.limit_sessions_label),
                    value = limit.maxSessions.toString(),
                    canDecrease = limit.maxSessions > UsageLimit.MIN,
                    canIncrease = limit.maxSessions < UsageLimit.MAX,
                    onDecrease = { setLimit(UsageLimit(limit.maxSessions - 1)) },
                    onIncrease = { setLimit(UsageLimit(limit.maxSessions + 1)) },
                )
            }
        }

        SectionHeader(stringResource(R.string.limit_counted_apps))
        RuleCard {
            if (packages.isEmpty()) {
                InfoRow(stringResource(R.string.limit_no_apps))
            } else {
                InfoRow(packages.map(appName).sorted().joinToString(", "))
            }
        }

        val usage = snapshot.usage
        val sessions = usage?.sessions(packages).orEmpty()
        val counted = sessions.count { it.counted }
        SectionHeader(
            if (limit != null && usage != null) stringResource(R.string.limit_today_count, counted, limit.maxSessions)
            else stringResource(R.string.limit_today)
        )
        RuleCard {
            when {
                usage == null -> InfoRow(stringResource(R.string.limit_needs_access))
                sessions.isEmpty() -> InfoRow(stringResource(R.string.limit_no_sessions))
                else -> sessions.asReversed().forEachIndexed { index, session ->
                    if (index > 0) CardDivider()
                    SessionRow(session, appName, tuning)
                }
            }
        }

        SectionHeader(stringResource(R.string.limit_counting))
        RuleCard {
            StepperRow(
                title = stringResource(R.string.limit_min_session),
                detail = stringResource(R.string.limit_min_session_detail),
                value = secondsLabel(tuning.minSessionSeconds),
                canDecrease = tuning.minSessionSeconds > SessionTuning.MIN_SECONDS_RANGE.first,
                canIncrease = tuning.minSessionSeconds < SessionTuning.MIN_SECONDS_RANGE.last,
                onDecrease = { onTuningChange(tuning.copy(minSessionSeconds = (tuning.minSessionSeconds - MIN_STEP).coerceIn(SessionTuning.MIN_SECONDS_RANGE))) },
                onIncrease = { onTuningChange(tuning.copy(minSessionSeconds = (tuning.minSessionSeconds + MIN_STEP).coerceIn(SessionTuning.MIN_SECONDS_RANGE))) },
            )
            CardDivider()
            StepperRow(
                title = stringResource(R.string.limit_merge_gap),
                detail = stringResource(R.string.limit_merge_gap_detail),
                value = secondsLabel(tuning.mergeGapSeconds),
                canDecrease = tuning.mergeGapSeconds > SessionTuning.GAP_SECONDS_RANGE.first,
                canIncrease = tuning.mergeGapSeconds < SessionTuning.GAP_SECONDS_RANGE.last,
                onDecrease = { onTuningChange(tuning.copy(mergeGapSeconds = (tuning.mergeGapSeconds - GAP_STEP).coerceIn(SessionTuning.GAP_SECONDS_RANGE))) },
                onIncrease = { onTuningChange(tuning.copy(mergeGapSeconds = (tuning.mergeGapSeconds + GAP_STEP).coerceIn(SessionTuning.GAP_SECONDS_RANGE))) },
            )
        }

        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.limit_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

private const val MIN_STEP = 5
private const val GAP_STEP = 15

@Composable
private fun SessionRow(session: UsageSession, appName: (String) -> String, tuning: SessionTuning) {
    val context = LocalContext.current
    val format = DateFormat.getTimeFormat(context)
    val alpha = if (session.counted) 1f else 0.5f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                format.format(Date(session.startMillis)) + "–" + format.format(Date(session.endMillis)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (session.counted) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                modifier = Modifier.weight(1f),
            )
            Text(
                durationLabel(session.foregroundMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * alpha),
            )
        }
        Text(
            buildString {
                append(session.packages.joinToString(", ") { appName(it) })
                if (!session.counted) {
                    append(" · ")
                    append(context.getString(R.string.limit_session_ignored, tuning.minSessionSeconds))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * alpha),
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        IconButton(onClick = onDecrease, enabled = canDecrease) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.limit_decrease))
        }
        Text(value, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onIncrease, enabled = canIncrease) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.limit_increase))
        }
    }
}

@Composable
private fun secondsLabel(seconds: Int): String = stringResource(R.string.limit_seconds, seconds)

@Composable
private fun durationLabel(millis: Long): String {
    val total = millis / 1000
    return if (total < 60) stringResource(R.string.limit_seconds, total.toInt())
    else stringResource(R.string.limit_minutes_seconds, (total / 60).toInt(), (total % 60).toInt())
}
