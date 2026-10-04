// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.victorialauncher.R
import dev.victorialauncher.data.TimeWindow
import dev.victorialauncher.data.VisibilityRule
import java.time.DayOfWeek

/**
 * Every window of one rule, edited in place: the days as a row of chips, the two ends as
 * times. Any one window being current is enough, so they are listed rather than merged.
 */
@Composable
fun TimeWindowsScreen(
    rule: VisibilityRule?,
    onChange: (VisibilityRule) -> Unit,
    onBack: () -> Unit,
) {
    val current = rule ?: VisibilityRule()
    val windows = current.windows
    // Which end of which window the time dialog is open for.
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    fun update(index: Int, window: TimeWindow) =
        onChange(current.copy(windows = windows.toMutableList().also { it[index] = window }))

    RuleScaffold(title = stringResource(R.string.rule_time_title), onBack = onBack) {
        windows.forEachIndexed { index, window ->
            WindowCard(
                number = index + 1,
                window = window,
                onToggleDay = { day ->
                    update(index, window.copy(days = if (day in window.days) window.days - day else window.days + day))
                },
                onEditStart = { editing = index to true },
                onEditEnd = { editing = index to false },
                onDelete = { onChange(current.copy(windows = windows.filterIndexed { i, _ -> i != index })) },
            )
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(
            onClick = { onChange(current.copy(windows = windows + TimeWindow.DEFAULT)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.rule_add_window))
        }
    }

    editing?.let { (index, isStart) ->
        val window = windows.getOrNull(index)
        if (window == null) {
            editing = null
            return@let
        }
        TimeDialog(
            minute = if (isStart) window.startMinute else window.endMinute,
            onConfirm = { minute ->
                update(index, if (isStart) window.copy(startMinute = minute) else window.copy(endMinute = minute))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun WindowCard(
    number: Int,
    window: TimeWindow,
    onToggleDay: (DayOfWeek) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    RuleCard {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.rule_window_title, number).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rule_window_delete))
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 12.dp),
            ) {
                orderedWeek().forEach { day ->
                    DayChip(dayName(day), selected = day in window.days) { onToggleDay(day) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                TimeBox(stringResource(R.string.rule_window_from), timeLabel(window.startMinute), null, Modifier.weight(1f), onEditStart)
                Text("—", modifier = Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                TimeBox(
                    stringResource(R.string.rule_window_to),
                    timeLabel(window.endMinute),
                    if (window.crossesMidnight) stringResource(R.string.rule_window_next_day) else null,
                    Modifier.weight(1f),
                    onEditEnd,
                )
            }
        }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.size(38.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                // Two letters is what fits a circle in every language this ships in.
                label.take(2),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TimeBox(label: String, value: String, note: String?, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (note != null) {
                    Text(
                        " · $note",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun TimeDialog(minute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = minute / 60,
        initialMinute = minute % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
