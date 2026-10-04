// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * When a favorite is on the home screen: always, only while its conditions hold, or always
 * except while they hold.
 *
 * Kept free of Android types, like [Folder], so the whole decision can be tested on the JVM.
 * What the phone is doing right now arrives as a [ContextSnapshot] built elsewhere.
 */
enum class VisibilityMode { ALWAYS, ONLY_WHEN, HIDE_WHEN }

/**
 * One stretch of the week. Minutes count from midnight.
 *
 * An end before the start runs past midnight and belongs to the day it starts on: Friday
 * 22:00–02:00 is Friday night into Saturday morning, not the two ends of Friday. An end equal
 * to the start is the whole day, which is the only reading of it that is not empty.
 */
data class TimeWindow(
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
) {
    val crossesMidnight: Boolean get() = endMinute < startMinute

    operator fun contains(time: LocalDateTime): Boolean {
        val minute = time.hour * 60 + time.minute
        val day = time.dayOfWeek
        return when {
            startMinute == endMinute -> day in days
            startMinute < endMinute -> day in days && minute >= startMinute && minute < endMinute
            // The early-morning tail belongs to the night before.
            else -> (day in days && minute >= startMinute) || (day.minus(1) in days && minute < endMinute)
        }
    }

    companion object {
        /** What a new window starts as: the working week, nine to six. */
        val DEFAULT = TimeWindow(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            startMinute = 9 * 60,
            endMinute = 18 * 60,
        )
    }
}

/** Which audio outputs count as the headset a rule is waiting for. */
sealed interface HeadsetCondition {
    /** Anything worn: wired, USB or Bluetooth. */
    data object Any : HeadsetCondition

    /** Bluetooth only. A Bluetooth speaker counts too; see [AudioOutput.wireless]. */
    data object Wireless : HeadsetCondition

    /** Particular devices, matched by the name the system reports for them. */
    data class Named(val names: Set<String>) : HeadsetCondition
}

/**
 * Within one kind of condition any match will do — any of the networks, any of the windows —
 * and every kind that is set has to match. That covers the cases people actually describe
 * ("on the office Wi-Fi during working hours") without an expression editor.
 *
 * The conditions are kept when the mode is set back to [VisibilityMode.ALWAYS], so trying that
 * out and changing one's mind does not lose them; [isActive] is what says a rule does anything.
 */
data class VisibilityRule(
    val mode: VisibilityMode = VisibilityMode.ONLY_WHEN,
    /** Connected to any Wi-Fi network at all; when set, [wifi] is not consulted. */
    val anyWifi: Boolean = false,
    /** Network names, compared exactly — an SSID is case-sensitive. */
    val wifi: List<String> = emptyList(),
    val windows: List<TimeWindow> = emptyList(),
    val headset: HeadsetCondition? = null,
    /** Met once today's allowance of sessions in the favorite's apps is used up. */
    val usageLimit: UsageLimit? = null,
) {
    val hasWifi: Boolean get() = anyWifi || wifi.isNotEmpty()

    val hasConditions: Boolean get() = hasWifi || windows.isNotEmpty() || headset != null || usageLimit != null

    val isActive: Boolean get() = mode != VisibilityMode.ALWAYS && hasConditions

    /**
     * Whether the row belongs on the home screen right now.
     *
     * A condition that cannot be answered — the network's name withheld because location is
     * off or not granted, usage access not given — never hides anything. Better an app on
     * screen that was meant to be away than one that vanished for a reason nobody can see.
     *
     * [packages] are the apps a [usageLimit] counts, from [packagesForKey].
     */
    fun isVisible(context: ContextSnapshot, packages: Set<String> = emptySet()): Boolean {
        if (!isActive) return true
        val answers = buildList {
            if (hasWifi) add(wifiMatch(context))
            if (windows.isNotEmpty()) add(if (windows.any { context.now in it }) Match.YES else Match.NO)
            headset?.let { add(headsetMatch(it, context)) }
            usageLimit?.let { add(usageMatch(it, context, packages)) }
        }
        return when (mode) {
            VisibilityMode.ALWAYS -> true
            VisibilityMode.ONLY_WHEN -> answers.none { it == Match.NO }
            VisibilityMode.HIDE_WHEN -> !answers.all { it == Match.YES }
        }
    }

    private fun wifiMatch(context: ContextSnapshot): Match = when {
        !context.wifiConnected -> Match.NO
        anyWifi -> Match.YES
        context.ssid == null -> Match.UNKNOWN
        context.ssid in wifi -> Match.YES
        else -> Match.NO
    }

    private fun headsetMatch(condition: HeadsetCondition, context: ContextSnapshot): Match {
        val hit = when (condition) {
            HeadsetCondition.Any -> context.audioOutputs.isNotEmpty()
            HeadsetCondition.Wireless -> context.audioOutputs.any { it.wireless }
            is HeadsetCondition.Named -> context.audioOutputs.any { it.name in condition.names }
        }
        return if (hit) Match.YES else Match.NO
    }

    private fun usageMatch(limit: UsageLimit, context: ContextSnapshot, packages: Set<String>): Match {
        val usage = context.usage ?: return Match.UNKNOWN
        if (packages.isEmpty()) return Match.UNKNOWN
        return if (usage.countedSessions(packages) >= limit.maxSessions) Match.YES else Match.NO
    }

    private enum class Match { YES, NO, UNKNOWN }
}

/** A headset-like output that is connected right now. Speakers built into the phone are not. */
data class AudioOutput(
    val name: String,
    /**
     * Bluetooth of any flavor. A Bluetooth speaker reports itself the same way headphones do,
     * and telling them apart needs a permission this launcher does not ask for.
     */
    val wireless: Boolean,
)

/** What the phone is doing, as far as any rule can ask. */
data class ContextSnapshot(
    val wifiConnected: Boolean,
    /** The connected network's name, or null when there is none or it is being withheld. */
    val ssid: String?,
    val now: LocalDateTime,
    val audioOutputs: List<AudioOutput>,
    /** Null without usage access, which leaves every usage limit unanswered. */
    val usage: UsageToday? = null,
)

/** The favorites [rules] keep off the home screen in [context]. */
fun hiddenByRules(
    rules: Map<String, VisibilityRule>,
    context: ContextSnapshot,
    packagesOf: (String) -> Set<String> = { emptySet() },
): Set<String> =
    rules.filter { (key, rule) -> !rule.isVisible(context, if (rule.usageLimit != null) packagesOf(key) else emptySet()) }.keys

/** An output seen at some point, so a device can be picked while it is not connected. */
data class SeenAudioDevice(val name: String, val wireless: Boolean, val lastSeenMillis: Long)

/**
 * [seen] with [outputs] marked as seen at [nowMillis], newest first. Returns [seen] itself when
 * nothing new was learned, so the caller can skip a write.
 *
 * Only the last-seen time of a device already known changes, and that only once a day: enough
 * for "seen yesterday" to be true, without a store write every time a headset reconnects.
 */
fun mergeSeenAudioDevices(
    seen: List<SeenAudioDevice>,
    outputs: List<AudioOutput>,
    nowMillis: Long,
    limit: Int = 20,
): List<SeenAudioDevice> {
    val named = outputs.filter { it.name.isNotBlank() }.distinctBy { it.name }
    if (named.isEmpty()) return seen
    val byName = seen.associateBy { it.name }
    var changed = false
    val updated = named.map { output ->
        val previous = byName[output.name]
        if (previous == null || previous.wireless != output.wireless ||
            nowMillis - previous.lastSeenMillis > DAY_MILLIS
        ) {
            changed = true
            SeenAudioDevice(output.name, output.wireless, nowMillis)
        } else {
            previous
        }
    }
    if (!changed) return seen
    val updatedNames = updated.map { it.name }.toSet()
    return (updated + seen.filterNot { it.name in updatedNames })
        .sortedByDescending { it.lastSeenMillis }
        .take(limit)
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

// ---- Storage ----
//
// One JSON object keyed by the same entry key renames and icon overrides use, so a private
// space's rules are stripped from a backup by the same code that strips its renames.

internal fun visibilityRulesToJson(rules: Map<String, VisibilityRule>): String {
    val root = JSONObject()
    rules.forEach { (key, rule) -> root.put(key, ruleToJson(rule)) }
    return root.toString()
}

/** Anything unreadable is left out rather than failing the lot: one bad entry hides nothing. */
internal fun visibilityRulesFromJson(json: String?): Map<String, VisibilityRule> {
    if (json.isNullOrBlank()) return emptyMap()
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
    return buildMap {
        root.keys().forEach { key ->
            val rule = root.optJSONObject(key)?.let { runCatching { ruleFromJson(it) }.getOrNull() }
            if (rule != null) put(key, rule)
        }
    }
}

private fun ruleToJson(rule: VisibilityRule): JSONObject = JSONObject().apply {
    put("mode", rule.mode.name)
    if (rule.anyWifi) put("anyWifi", true)
    if (rule.wifi.isNotEmpty()) put("wifi", JSONArray(rule.wifi))
    if (rule.windows.isNotEmpty()) {
        put("windows", JSONArray().apply {
            rule.windows.forEach { window ->
                put(JSONObject().apply {
                    put("days", JSONArray(window.days.sorted().map { it.value }))
                    put("start", window.startMinute)
                    put("end", window.endMinute)
                })
            }
        })
    }
    when (val headset = rule.headset) {
        null -> Unit
        HeadsetCondition.Any -> put("headset", JSONObject().put("type", "any"))
        HeadsetCondition.Wireless -> put("headset", JSONObject().put("type", "wireless"))
        is HeadsetCondition.Named -> put(
            "headset",
            JSONObject().put("type", "named").put("names", JSONArray(headset.names.sorted())),
        )
    }
    rule.usageLimit?.let { put("usage", JSONObject().put("sessions", it.maxSessions)) }
}

private fun ruleFromJson(obj: JSONObject): VisibilityRule {
    val mode = runCatching { VisibilityMode.valueOf(obj.getString("mode")) }.getOrDefault(VisibilityMode.ONLY_WHEN)
    val wifi = obj.optJSONArray("wifi").strings()
    val windows = obj.optJSONArray("windows")?.let { array ->
        (0 until array.length()).mapNotNull { i ->
            val w = array.optJSONObject(i) ?: return@mapNotNull null
            val days = w.optJSONArray("days")?.let { d ->
                (0 until d.length()).mapNotNull { j -> d.optInt(j).takeIf { it in 1..7 }?.let(DayOfWeek::of) }
            }.orEmpty().toSet()
            val start = w.optInt("start", -1)
            val end = w.optInt("end", -1)
            if (start !in MINUTES_IN_DAY || end !in MINUTES_IN_DAY) null else TimeWindow(days, start, end)
        }
    }.orEmpty()
    val headset = obj.optJSONObject("headset")?.let { h ->
        when (h.optString("type")) {
            "any" -> HeadsetCondition.Any
            "wireless" -> HeadsetCondition.Wireless
            "named" -> HeadsetCondition.Named(h.optJSONArray("names").strings().toSet())
            else -> null
        }
    }
    val usageLimit = obj.optJSONObject("usage")?.let { u ->
        u.optInt("sessions", -1).takeIf { it in UsageLimit.MIN..UsageLimit.MAX }?.let(::UsageLimit)
    }
    return VisibilityRule(mode, obj.optBoolean("anyWifi", false), wifi, windows, headset, usageLimit)
}

private val MINUTES_IN_DAY = 0 until 24 * 60

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

internal fun seenAudioDevicesToJson(devices: List<SeenAudioDevice>): String = JSONArray().apply {
    devices.forEach { put(JSONObject().put("name", it.name).put("wireless", it.wireless).put("seen", it.lastSeenMillis)) }
}.toString()

internal fun seenAudioDevicesFromJson(json: String?): List<SeenAudioDevice> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SeenAudioDevice(name, obj.optBoolean("wireless"), obj.optLong("seen"))
    }
}
