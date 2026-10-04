// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.LocalDateTime

class VisibilityRulesTest {

    // 2026-10-02 is a Friday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    private fun ctx(
        ssid: String? = null,
        wifi: Boolean = ssid != null,
        now: LocalDateTime = at(2, 12),
        audio: List<AudioOutput> = emptyList(),
    ) = ContextSnapshot(wifiConnected = wifi, ssid = ssid, now = now, audioOutputs = audio)

    private val office = VisibilityRule(wifi = listOf("Office", "Office-5G"))

    @Test
    fun `a rule with no conditions never hides anything`() {
        assertTrue(VisibilityRule(mode = VisibilityMode.ONLY_WHEN).isVisible(ctx()))
        assertTrue(VisibilityRule(mode = VisibilityMode.HIDE_WHEN).isVisible(ctx()))
    }

    @Test
    fun `always ignores whatever conditions are kept`() {
        assertTrue(office.copy(mode = VisibilityMode.ALWAYS).isVisible(ctx(ssid = "Home")))
        assertFalse(office.copy(mode = VisibilityMode.ALWAYS).isActive)
    }

    @Test
    fun `only when matches any of the listed networks`() {
        assertTrue(office.isVisible(ctx(ssid = "Office-5G")))
        assertFalse(office.isVisible(ctx(ssid = "Home")))
        assertFalse(office.isVisible(ctx()))
    }

    @Test
    fun `network names are case-sensitive`() {
        assertFalse(office.isVisible(ctx(ssid = "office")))
    }

    @Test
    fun `a withheld network name never hides`() {
        val unknown = ctx(ssid = null, wifi = true)
        assertTrue(office.isVisible(unknown))
        assertTrue(office.copy(mode = VisibilityMode.HIDE_WHEN).isVisible(unknown))
    }

    @Test
    fun `any wifi needs only a connection, named or not`() {
        val any = VisibilityRule(anyWifi = true)
        assertTrue(any.isVisible(ctx(ssid = null, wifi = true)))
        assertFalse(any.isVisible(ctx(wifi = false)))
        // Hide-when-on-any-Wi-Fi is how "only on mobile data" is said.
        assertFalse(any.copy(mode = VisibilityMode.HIDE_WHEN).isVisible(ctx(ssid = "Home")))
    }

    @Test
    fun `kinds of condition must all hold, and hide when needs all of them too`() {
        val rule = office.copy(windows = listOf(TimeWindow.DEFAULT))
        assertTrue(rule.isVisible(ctx(ssid = "Office", now = at(2, 10))))
        assertFalse(rule.isVisible(ctx(ssid = "Office", now = at(3, 10)))) // Saturday
        assertFalse(rule.isVisible(ctx(ssid = "Home", now = at(2, 10))))

        val hide = rule.copy(mode = VisibilityMode.HIDE_WHEN)
        assertFalse(hide.isVisible(ctx(ssid = "Office", now = at(2, 10))))
        assertTrue(hide.isVisible(ctx(ssid = "Office", now = at(3, 10))))
    }

    @Test
    fun `a window includes its start and excludes its end`() {
        val w = TimeWindow.DEFAULT
        assertTrue(at(2, 9) in w)
        assertTrue(at(2, 17, 59) in w)
        assertFalse(at(2, 18) in w)
        assertFalse(at(2, 8, 59) in w)
    }

    @Test
    fun `a window past midnight belongs to the day it starts on`() {
        val friNight = TimeWindow(setOf(FRIDAY), 22 * 60, 2 * 60)
        assertTrue(at(2, 23) in friNight) // Friday 23:00
        assertTrue(at(3, 1, 30) in friNight) // Saturday 01:30
        assertFalse(at(3, 23) in friNight) // Saturday 23:00
        assertFalse(at(2, 1, 30) in friNight) // Friday 01:30 belongs to Thursday night
    }

    @Test
    fun `a window that crosses the week boundary wraps from Sunday to Monday`() {
        val sunNight = TimeWindow(setOf(DayOfWeek.SUNDAY), 23 * 60, 60)
        assertTrue(at(5, 0, 30) in sunNight) // Monday 00:30
    }

    @Test
    fun `equal start and end is the whole day`() {
        val sat = TimeWindow(setOf(SATURDAY), 0, 0)
        assertTrue(at(3, 0) in sat)
        assertTrue(at(3, 23, 59) in sat)
        assertFalse(at(2, 12) in sat)
    }

    @Test
    fun `headset conditions`() {
        val buds = AudioOutput("Galaxy Buds2", wireless = true)
        val wired = AudioOutput("USB-C Headset", wireless = false)
        assertTrue(VisibilityRule(headset = HeadsetCondition.Any).isVisible(ctx(audio = listOf(wired))))
        assertFalse(VisibilityRule(headset = HeadsetCondition.Any).isVisible(ctx()))
        assertFalse(VisibilityRule(headset = HeadsetCondition.Wireless).isVisible(ctx(audio = listOf(wired))))
        assertTrue(VisibilityRule(headset = HeadsetCondition.Wireless).isVisible(ctx(audio = listOf(buds))))
        val named = VisibilityRule(headset = HeadsetCondition.Named(setOf("WH-1000XM4")))
        assertFalse(named.isVisible(ctx(audio = listOf(buds))))
        assertTrue(named.isVisible(ctx(audio = listOf(buds, AudioOutput("WH-1000XM4", true)))))
    }

    @Test
    fun `hiddenByRules returns only the keys that are off screen`() {
        val rules = mapOf("a/A" to office, "b/B" to VisibilityRule(anyWifi = true), "folder:1" to office)
        assertEquals(setOf("a/A", "folder:1"), hiddenByRules(rules, ctx(ssid = "Home")))
    }

    @Test
    fun `rules survive a JSON round-trip`() {
        val rules = mapOf(
            "a/A" to VisibilityRule(
                mode = VisibilityMode.HIDE_WHEN,
                wifi = listOf("Office", "Café \"Bean\""),
                windows = listOf(TimeWindow.DEFAULT, TimeWindow(setOf(FRIDAY, SATURDAY), 22 * 60, 120)),
                headset = HeadsetCondition.Named(setOf("WH-1000XM4", "Buds")),
            ),
            "folder:x" to VisibilityRule(anyWifi = true, headset = HeadsetCondition.Wireless),
            "shortcut:p/id|u10" to VisibilityRule(mode = VisibilityMode.ALWAYS, headset = HeadsetCondition.Any),
        )
        assertEquals(rules, visibilityRulesFromJson(visibilityRulesToJson(rules)))
    }

    @Test
    fun `unreadable rules are dropped rather than failing the rest`() {
        assertTrue(visibilityRulesFromJson("{nope").isEmpty())
        assertTrue(visibilityRulesFromJson(null).isEmpty())
        val mixed = """{"a/A":{"mode":"ONLY_WHEN","wifi":["X"]},"b/B":"junk",""" +
            """"c/C":{"mode":"ONLY_WHEN","windows":[{"days":[1],"start":9999,"end":5}]}}"""
        val parsed = visibilityRulesFromJson(mixed)
        assertEquals(setOf("a/A", "c/C"), parsed.keys)
        assertTrue(parsed.getValue("c/C").windows.isEmpty())
    }

    @Test
    fun `seen devices learn new names and keep the list newest first`() {
        val seen = listOf(SeenAudioDevice("Old", true, 1_000))
        val merged = mergeSeenAudioDevices(seen, listOf(AudioOutput("New", true)), nowMillis = 5_000)
        assertEquals(listOf("New", "Old"), merged.map { it.name })
    }

    @Test
    fun `seeing a known device again the same day changes nothing`() {
        val seen = listOf(SeenAudioDevice("Buds", true, 1_000))
        assertSame(seen, mergeSeenAudioDevices(seen, listOf(AudioOutput("Buds", true)), nowMillis = 2_000))
        assertSame(seen, mergeSeenAudioDevices(seen, listOf(AudioOutput("", true)), nowMillis = 2_000))
    }

    @Test
    fun `seen devices round-trip`() {
        val seen = listOf(SeenAudioDevice("Buds", true, 42), SeenAudioDevice("USB", false, 7))
        assertEquals(seen, seenAudioDevicesFromJson(seenAudioDevicesToJson(seen)))
    }

    @Test
    fun `the default window is the working week`() {
        assertEquals(5, TimeWindow.DEFAULT.days.size)
        assertTrue(MONDAY in TimeWindow.DEFAULT.days)
    }
}
