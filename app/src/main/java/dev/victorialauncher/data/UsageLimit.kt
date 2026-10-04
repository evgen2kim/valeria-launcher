// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.data

/**
 * A daily allowance of sessions in a favorite's apps. Met — "the limit is used up" — once
 * today has seen [maxSessions] of them.
 *
 * Meant to sit beside a screen-time app rather than replace it: that app enforces the limit,
 * and this takes the icon away once there is nothing left to open it for. Neither can read the
 * other, so the numbers are typed in twice and the counting here is tuned by hand to agree
 * ([SessionTuning]).
 *
 * Which apps count is not stored: it is the favorite's own app, or whatever its folder holds
 * at the moment ([packagesForKey]), so moving an app into the folder starts counting it.
 */
data class UsageLimit(val maxSessions: Int) {
    companion object {
        const val MIN = 1
        const val MAX = 20
        val DEFAULT = UsageLimit(2)
    }
}

/**
 * How stretches in the foreground are turned into sessions.
 *
 * A screen-time app's own pause screen puts the app on screen for an instant before covering
 * it, so a visit shorter than [minSessionSeconds] is not a session — walking away at the pause
 * screen should not use one up. Visits closer together than [mergeGapSeconds] are one session:
 * the pause screen in front of the real visit, a reply in another app halfway through, or
 * hopping between two of the apps on one allowance.
 */
data class SessionTuning(
    val minSessionSeconds: Int = DEFAULT_MIN_SECONDS,
    val mergeGapSeconds: Int = DEFAULT_GAP_SECONDS,
) {
    companion object {
        const val DEFAULT_MIN_SECONDS = 10
        const val DEFAULT_GAP_SECONDS = 60
        val MIN_SECONDS_RANGE = 0..300
        val GAP_SECONDS_RANGE = 0..900
    }
}

/** One app on screen without a break, in epoch milliseconds. */
data class ForegroundInterval(val packageName: String, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/** What the phone has had on screen since midnight, as far as usage access shows it. */
data class UsageToday(
    val dayStartMillis: Long,
    val intervals: List<ForegroundInterval>,
    val tuning: SessionTuning = SessionTuning(),
) {
    /** Every candidate session in [packages] today, the ones too short to count included. */
    fun sessions(packages: Set<String>): List<UsageSession> = groupSessions(intervals, packages, tuning)

    fun countedSessions(packages: Set<String>): Int = sessions(packages).count { it.counted }
}

/** Visits [mergeGapSeconds][SessionTuning.mergeGapSeconds] or less apart, taken together. */
data class UsageSession(
    val startMillis: Long,
    val endMillis: Long,
    /** Time actually on screen: the gaps that joined its visits are not part of it. */
    val foregroundMillis: Long,
    /** In the order they were first opened. */
    val packages: List<String>,
    /** False for one under [minSessionSeconds][SessionTuning.minSessionSeconds]. */
    val counted: Boolean,
)

internal fun groupSessions(
    intervals: List<ForegroundInterval>,
    packages: Set<String>,
    tuning: SessionTuning,
): List<UsageSession> {
    val mine = intervals.filter { it.packageName in packages && it.endMillis > it.startMillis }.sortedBy { it.startMillis }
    if (mine.isEmpty()) return emptyList()
    val gap = tuning.mergeGapSeconds * 1000L
    val min = tuning.minSessionSeconds * 1000L
    val sessions = mutableListOf<UsageSession>()
    var group = mutableListOf(mine.first())
    var groupEnd = mine.first().endMillis
    fun close() {
        val foreground = group.sumOf { it.durationMillis }
        sessions += UsageSession(
            startMillis = group.first().startMillis,
            endMillis = groupEnd,
            foregroundMillis = foreground,
            packages = group.map { it.packageName }.distinct(),
            counted = foreground >= min,
        )
    }
    mine.drop(1).forEach { interval ->
        if (interval.startMillis - groupEnd <= gap) {
            group += interval
            groupEnd = maxOf(groupEnd, interval.endMillis)
        } else {
            close()
            group = mutableListOf(interval)
            groupEnd = interval.endMillis
        }
    }
    close()
    return sessions
}

/** The usage events [foregroundIntervals] reads, with the system's numbers for them. */
enum class UsageEventKind(val code: Int) {
    RESUMED(1),
    PAUSED(2),
    SCREEN_OFF(16),
    KEYGUARD_SHOWN(17),
    SHUTDOWN(26),
}

data class UsageEventRecord(val timeMillis: Long, val kind: UsageEventKind, val packageName: String)

/**
 * Who was on screen when, from the system's stream of usage events, clipped to
 * [fromMillis]..[nowMillis].
 *
 * One app holds the screen from its first activity resumed until another app's is, or the
 * screen goes off. A pause is only provisional: moving between two activities of one app
 * pauses one and resumes the other, and that is no break at all. A pause that is followed by
 * another app, or by the screen going off, ends the stretch at the moment of the pause.
 */
fun foregroundIntervals(events: List<UsageEventRecord>, fromMillis: Long, nowMillis: Long): List<ForegroundInterval> {
    val out = mutableListOf<ForegroundInterval>()
    var current: String? = null
    var since = 0L
    var pausedAt: Long? = null

    fun close(at: Long) {
        val pkg = current ?: return
        val start = maxOf(since, fromMillis)
        val end = minOf(at, nowMillis)
        if (end > start) out += ForegroundInterval(pkg, start, end)
        current = null
        pausedAt = null
    }

    events.sortedBy { it.timeMillis }.forEach { event ->
        when (event.kind) {
            UsageEventKind.RESUMED -> {
                if (event.packageName == current) {
                    pausedAt = null
                } else {
                    close(pausedAt ?: event.timeMillis)
                    current = event.packageName
                    since = event.timeMillis
                }
            }
            UsageEventKind.PAUSED -> if (event.packageName == current && pausedAt == null) pausedAt = event.timeMillis
            UsageEventKind.SCREEN_OFF, UsageEventKind.KEYGUARD_SHOWN, UsageEventKind.SHUTDOWN ->
                close(pausedAt ?: event.timeMillis)
        }
    }
    close(pausedAt ?: nowMillis)
    return out
}

/**
 * The packages whose sessions a favorite's limit counts: an app's own, a shortcut's
 * publisher, or every app in a folder. Empty for anything else, which leaves the limit
 * unanswerable rather than met.
 */
fun packagesForKey(key: String, foldersById: Map<String, Folder>): Set<String> {
    folderIdFromToken(key)?.let { id ->
        return foldersById[id]?.apps.orEmpty().flatMap { packagesForKey(it, emptyMap()) }.toSet()
    }
    // Another profile's apps do not show up in this profile's usage events at all.
    if (EntryKeys.userSerial(key) != 0L) return emptySet()
    if (EntryKeys.isShortcut(key)) return EntryKeys.parseShortcut(key)?.let { setOf(it.packageName) }.orEmpty()
    val slash = key.indexOf('/')
    return if (slash > 0) setOf(key.substring(0, slash)) else emptySet()
}
