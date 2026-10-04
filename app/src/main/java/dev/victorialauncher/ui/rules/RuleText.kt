// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.rules

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.victorialauncher.R
import dev.victorialauncher.data.HeadsetCondition
import dev.victorialauncher.data.TimeWindow
import dev.victorialauncher.data.VisibilityMode
import dev.victorialauncher.data.VisibilityRule
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// How a rule reads back to the person who wrote it: the sentence at the top of the editor, the
// line under "Show when…" in a favorite's menu, and the badges on a row in edit mode.

@Composable
@ReadOnlyComposable
private fun locale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** The week in the order this locale starts it, for day chips and for "Mon–Fri". */
@Composable
@ReadOnlyComposable
internal fun orderedWeek(): List<DayOfWeek> {
    val first = WeekFields.of(locale()).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

@Composable
@ReadOnlyComposable
internal fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale())

/** "Every day", "Mon–Fri", or "Mon, Wed, Fri" — a run of three or more reads as a range. */
@Composable
@ReadOnlyComposable
internal fun daysLabel(days: Set<DayOfWeek>): String {
    if (days.isEmpty()) return stringResource(R.string.rule_days_none)
    if (days.size == 7) return stringResource(R.string.rule_days_every)
    val week = orderedWeek()
    val picked = week.filter { it in days }
    val first = week.indexOf(picked.first())
    val contiguous = picked.size >= 3 && picked.indices.all { week.indexOf(picked[it]) == first + it }
    return if (contiguous) {
        stringResource(R.string.rule_days_range, dayName(picked.first()), dayName(picked.last()))
    } else {
        picked.map { dayName(it) }.joinToString(", ")
    }
}

/** A minute of the day in the phone's own 12- or 24-hour style. */
@Composable
@ReadOnlyComposable
internal fun timeLabel(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    return if (DateFormat.is24HourFormat(LocalContext.current)) {
        "%d:%02d".format(h, m)
    } else {
        val hour12 = if (h % 12 == 0) 12 else h % 12
        "%d:%02d %s".format(hour12, m, if (h < 12) "AM" else "PM")
    }
}

@Composable
@ReadOnlyComposable
internal fun windowLabel(window: TimeWindow): String =
    if (window.startMinute == window.endMinute) {
        stringResource(R.string.rule_window_all_day, daysLabel(window.days))
    } else {
        stringResource(
            R.string.rule_window_time,
            daysLabel(window.days),
            timeLabel(window.startMinute),
            timeLabel(window.endMinute),
        )
    }

/** "A, B or C". */
@Composable
@ReadOnlyComposable
private fun orList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> stringResource(R.string.rule_join_or, items.dropLast(1).joinToString(", "), items.last())
}

/** The whole rule as one sentence, or null when it has nothing to say yet. */
@Composable
@ReadOnlyComposable
internal fun ruleSentence(rule: VisibilityRule): String? {
    if (!rule.isActive) return null
    val parts = buildList {
        when {
            rule.anyWifi -> add(stringResource(R.string.rule_part_any_wifi))
            rule.wifi.isNotEmpty() -> add(stringResource(R.string.rule_part_wifi, orList(rule.wifi)))
        }
        if (rule.windows.isNotEmpty()) add(orList(rule.windows.map { windowLabel(it) }))
        when (val headset = rule.headset) {
            null -> Unit
            HeadsetCondition.Any -> add(stringResource(R.string.rule_part_headset_any))
            HeadsetCondition.Wireless -> add(stringResource(R.string.rule_part_headset_wireless))
            is HeadsetCondition.Named -> add(stringResource(R.string.rule_part_headset_named, orList(headset.names.sorted())))
        }
    }
    var joined = parts.first()
    parts.drop(1).forEach { joined = stringResource(R.string.rule_join_and, joined, it) }
    return stringResource(
        if (rule.mode == VisibilityMode.HIDE_WHEN) R.string.rule_sentence_hide else R.string.rule_sentence_only,
        joined,
    )
}

/** The short line under "Show when…": "Always", or "Only when: Wi-Fi · Time". */
@Composable
@ReadOnlyComposable
internal fun ruleSummary(rule: VisibilityRule?): String {
    if (rule == null || !rule.isActive) return stringResource(R.string.rule_mode_always)
    val kinds = buildList {
        if (rule.hasWifi) add(stringResource(R.string.rule_kind_wifi))
        if (rule.windows.isNotEmpty()) add(stringResource(R.string.rule_kind_time))
        if (rule.headset != null) add(stringResource(R.string.rule_kind_headset))
    }.joinToString(" · ")
    return stringResource(
        if (rule.mode == VisibilityMode.HIDE_WHEN) R.string.rule_summary_hide else R.string.rule_summary_only,
        kinds,
    )
}

@Composable
@ReadOnlyComposable
internal fun headsetLabel(condition: HeadsetCondition?): String = when (condition) {
    null -> stringResource(R.string.rule_not_used)
    HeadsetCondition.Any -> stringResource(R.string.headset_any)
    HeadsetCondition.Wireless -> stringResource(R.string.headset_wireless)
    is HeadsetCondition.Named -> condition.names.sorted().joinToString(", ")
}

/**
 * The rule in miniature, under a row's name in edit mode. A crossed-out eye leads when the
 * rule hides rather than shows, since otherwise the two read the same.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleBadges(rule: VisibilityRule, contentColor: Color, modifier: Modifier = Modifier) {
    if (!rule.isActive) return
    FlowRow(
        modifier = modifier.padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (rule.mode == VisibilityMode.HIDE_WHEN) Badge(Icons.Filled.VisibilityOff, null, contentColor)
        if (rule.hasWifi) {
            val text = if (rule.anyWifi) stringResource(R.string.rule_badge_any_wifi) else withMore(rule.wifi)
            Badge(Icons.Filled.Wifi, text, contentColor)
        }
        if (rule.windows.isNotEmpty()) {
            val first = rule.windows.first()
            val text = if (first.startMinute == first.endMinute) daysLabel(first.days)
                else timeLabel(first.startMinute) + "–" + timeLabel(first.endMinute)
            Badge(Icons.Filled.Schedule, text + more(rule.windows.size - 1), contentColor)
        }
        rule.headset?.let { headset ->
            val text = when (headset) {
                HeadsetCondition.Any -> stringResource(R.string.rule_badge_headset)
                HeadsetCondition.Wireless -> stringResource(R.string.rule_badge_wireless)
                is HeadsetCondition.Named -> withMore(headset.names.sorted())
            }
            Badge(Icons.Filled.Headphones, text, contentColor)
        }
    }
}

private fun withMore(items: List<String>) = items.firstOrNull().orEmpty() + more(items.size - 1)

private fun more(extra: Int) = if (extra > 0) " +$extra" else ""

@Composable
private fun Badge(icon: ImageVector, text: String?, contentColor: Color) {
    Row(
        modifier = Modifier
            .background(contentColor.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(start = 5.dp, end = if (text == null) 5.dp else 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
        if (text != null) {
            Spacer(Modifier.width(3.dp))
            Text(text, color = contentColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
