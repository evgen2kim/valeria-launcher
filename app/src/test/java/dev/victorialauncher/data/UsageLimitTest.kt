// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.data

import dev.victorialauncher.data.UsageEventKind.KEYGUARD_SHOWN
import dev.victorialauncher.data.UsageEventKind.PAUSED
import dev.victorialauncher.data.UsageEventKind.RESUMED
import dev.victorialauncher.data.UsageEventKind.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class UsageLimitTest {

    private val insta = "com.instagram.android"
    private val threads = "com.instagram.barcelona"
    private val zen = "com.screenzen"
    private val home = "dev.victorialauncher"
    private val scroll = setOf(insta, threads)

    private fun s(seconds: Int) = seconds * 1000L
    private fun ev(seconds: Int, kind: UsageEventKind, pkg: String) = UsageEventRecord(s(seconds), kind, pkg)

    private fun visit(pkg: String, from: Int, to: Int) = ForegroundInterval(pkg, s(from), s(to))

    private fun ctx(intervals: List<ForegroundInterval>?) = ContextSnapshot(
        wifiConnected = false,
        ssid = null,
        now = LocalDateTime.of(2026, 10, 4, 12, 0),
        audioOutputs = emptyList(),
        usage = intervals?.let { UsageToday(0, it) },
    )

    // ---- Intervals from events ----

    @Test
    fun `an app holds the screen until another one is resumed`() {
        val events = listOf(
            ev(10, RESUMED, insta),
            ev(70, PAUSED, insta),
            ev(70, RESUMED, home),
        )
        assertEquals(
            listOf(visit(insta, 10, 70), visit(home, 70, 100)),
            foregroundIntervals(events, fromMillis = 0, nowMillis = s(100)),
        )
    }

    @Test
    fun `moving between two activities of one app is no break`() {
        val events = listOf(
            ev(10, RESUMED, insta),
            ev(20, PAUSED, insta),
            ev(21, RESUMED, insta),
            ev(50, PAUSED, insta),
            ev(51, RESUMED, home),
        )
        assertEquals(visit(insta, 10, 50), foregroundIntervals(events, 0, s(60)).first())
    }

    @Test
    fun `the screen going off ends a stretch at its pause`() {
        val events = listOf(
            ev(10, RESUMED, insta),
            ev(40, PAUSED, insta),
            ev(41, SCREEN_OFF, ""),
            ev(500, KEYGUARD_SHOWN, ""),
        )
        assertEquals(listOf(visit(insta, 10, 40)), foregroundIntervals(events, 0, s(600)))
    }

    @Test
    fun `what was open across midnight is clipped to the day`() {
        val events = listOf(ev(10, RESUMED, insta), ev(100, PAUSED, insta), ev(100, RESUMED, home))
        assertEquals(visit(insta, 50, 100), foregroundIntervals(events, fromMillis = s(50), nowMillis = s(200)).first())
    }

    // ---- Sessions ----

    @Test
    fun `backing out at the pause screen is not a session`() {
        // Instagram for an instant, the pause screen over it, then home.
        val intervals = listOf(visit(insta, 0, 1), visit(zen, 1, 6), visit(home, 6, 60))
        val sessions = groupSessions(intervals, scroll, SessionTuning())
        assertEquals(1, sessions.size)
        assertFalse(sessions.single().counted)
    }

    @Test
    fun `the pause screen in front of a real visit joins it`() {
        val intervals = listOf(visit(insta, 0, 1), visit(zen, 1, 6), visit(insta, 6, 126))
        val session = groupSessions(intervals, scroll, SessionTuning()).single()
        assertTrue(session.counted)
        assertEquals(s(121), session.foregroundMillis)
        assertEquals(listOf(insta), session.packages)
    }

    @Test
    fun `hopping between two apps of the group is one session, a later visit another`() {
        val intervals = listOf(
            visit(insta, 0, 60),
            visit(threads, 70, 120),
            visit(insta, 1000, 1100),
        )
        val sessions = groupSessions(intervals, scroll, SessionTuning(mergeGapSeconds = 60))
        assertEquals(2, sessions.size)
        assertEquals(listOf(insta, threads), sessions.first().packages)
    }

    @Test
    fun `apps outside the group are not counted`() {
        assertTrue(groupSessions(listOf(visit(home, 0, 600)), scroll, SessionTuning()).isEmpty())
    }

    // ---- The condition ----

    @Test
    fun `hide when the limit is used up`() {
        val rule = VisibilityRule(mode = VisibilityMode.HIDE_WHEN, usageLimit = UsageLimit(2))
        val one = listOf(visit(insta, 0, 120))
        val two = one + visit(threads, 5000, 5120)
        assertTrue(rule.isVisible(ctx(one), scroll))
        assertFalse(rule.isVisible(ctx(two), scroll))
    }

    @Test
    fun `without usage access or apps to count a limit hides nothing`() {
        val rule = VisibilityRule(mode = VisibilityMode.HIDE_WHEN, usageLimit = UsageLimit(1))
        val used = listOf(visit(insta, 0, 120))
        assertTrue(rule.isVisible(ctx(null), scroll))
        assertTrue(rule.isVisible(ctx(used), emptySet()))
    }

    @Test
    fun `hiddenByRules asks for the packages of a limited favorite`() {
        val rules = mapOf("folder:scroll" to VisibilityRule(mode = VisibilityMode.HIDE_WHEN, usageLimit = UsageLimit(1)))
        val used = ctx(listOf(visit(insta, 0, 120)))
        assertEquals(setOf("folder:scroll"), hiddenByRules(rules, used) { scroll })
        assertTrue(hiddenByRules(rules, used).isEmpty())
    }

    @Test
    fun `a limit survives a JSON round-trip and a bad one is dropped`() {
        val rules = mapOf("a/A" to VisibilityRule(mode = VisibilityMode.HIDE_WHEN, usageLimit = UsageLimit(3)))
        assertEquals(rules, visibilityRulesFromJson(visibilityRulesToJson(rules)))
        val bad = visibilityRulesFromJson("""{"a/A":{"mode":"HIDE_WHEN","usage":{"sessions":0}}}""")
        assertEquals(null, bad.getValue("a/A").usageLimit)
    }

    // ---- Which apps a favorite counts ----

    @Test
    fun `packages for apps, shortcuts and folders`() {
        val folder = Folder("scroll", "Scroll", listOf("$insta/.Main", "shortcut:$threads/feed", "x/Y|u10"))
        val byId = mapOf("scroll" to folder)
        assertEquals(setOf(insta), packagesForKey("$insta/.Main", byId))
        assertEquals(setOf(threads), packagesForKey("shortcut:$threads/feed", byId))
        assertEquals(setOf(insta, threads), packagesForKey("folder:scroll", byId))
        assertTrue(packagesForKey("x/Y|u10", byId).isEmpty())
        assertTrue(packagesForKey("folder:missing", byId).isEmpty())
        assertTrue(packagesForKey(EntryKeys.SEARCH, byId).isEmpty())
    }
}
