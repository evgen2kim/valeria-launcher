// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySettingsImportTest {
    @Test
    fun importsFavoritesWithoutWidgetIdsOwnedByTheOldPackage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs(context)
        val original = prefs.exportJson(stripOtherProfiles = false).json
        try {
            val legacyBackup = """
                {
                  "format": 1,
                  "app": "Victoria Launcher",
                  "values": {
                    "favorites_order": {"type": "string", "value": "org.example.camera/.Main"},
                    "widget_id": {"type": "int", "value": 41},
                    "widget_ids": {"type": "string", "value": "41"}
                  }
                }
            """.trimIndent()
            assertTrue(prefs.importJson(legacyBackup))
            assertEquals(listOf("org.example.camera/.Main"), prefs.favorites.first())
            assertEquals(emptyList<Int>(), prefs.widgetIds.first())
            assertEquals(-1, prefs.widgetId.first())
        } finally {
            prefs.importJson(original)
        }
    }
}
