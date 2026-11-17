package com.meld.branding

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeldAppIconTest {
    @Test
    fun pickerOffersTheMeldSetInOrder() {
        assertEquals(
            listOf("DEFAULT", "AMBER", "CRIMSON", "MONOCHROME", "RAINBOWS", "VIOLET", "LEGACY"),
            MeldAppIcon.available(easterEggUnlocked = false).map { it.name },
        )
    }

    @Test
    fun easterEggAppearsOnlyOnceUnlocked() {
        assertFalse(MeldAppIcon.EASTER_EGG in MeldAppIcon.available(easterEggUnlocked = false))
        assertEquals(MeldAppIcon.EASTER_EGG, MeldAppIcon.available(easterEggUnlocked = true).last())
        assertEquals(MeldAppIcon.DEFAULT, MeldAppIcon.fromPreference("EASTER_EGG", easterEggUnlocked = false))
        assertEquals(MeldAppIcon.EASTER_EGG, MeldAppIcon.fromPreference("EASTER_EGG", easterEggUnlocked = true))
    }

    @Test
    fun removedUpstreamIconsFallBackToDefault() {
        val removed =
            listOf(
                "ORIGINAL",
                "ARCTIC_HAZE",
                "LAVENDER_LEAP",
                "METROMALLOW",
                "PRIDE_SPECIAL",
                "STATIC",
                "ZENITH_BLACK",
                "ZENITH_WHITE",
                "GRADIENT",
                "",
            )
        for (value in removed) {
            assertEquals(MeldAppIcon.DEFAULT, MeldAppIcon.fromPreference(value, easterEggUnlocked = true), value)
        }
        assertEquals(MeldAppIcon.VIOLET, MeldAppIcon.fromPreference("VIOLET", easterEggUnlocked = false))
    }

    @Test
    fun unlockIsStoredOnTheDeviceAndStaysOutOfBackups() {
        val stored = MapSettings()
        val settings = Settings(stored)
        settings.appIcon = "EASTER_EGG"
        assertEquals(MeldAppIcon.DEFAULT, settings.meldAppIcon)

        settings.meldEasterEggUnlocked = true
        assertEquals(MeldAppIcon.EASTER_EGG, Settings(stored).meldAppIcon)

        val keys = settings.exportBackupSettings().entries.map { it.key }
        assertTrue("appIcon" in keys)
        assertFalse("meldEasterEggUnlocked" in keys)
    }

    @Test
    fun seventhTapCompletesARoundAndTheCountStartsAgain() {
        val taps = MeldVersionTaps()
        repeat(6) { assertFalse(taps.tap()) }
        assertTrue(taps.tap())
        repeat(6) { assertFalse(taps.tap()) }
        assertTrue(taps.tap())
    }
}
