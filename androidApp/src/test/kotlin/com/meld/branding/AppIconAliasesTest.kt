package com.meld.branding

import com.metrolist.android.utils.LEGACY_ALIAS_NAMES
import com.metrolist.android.utils.aliasName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppIconAliasesTest {
    private val aliases: Map<String, Element> =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("activity-alias")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .associateBy { it.getAttribute("android:name").removePrefix(".") }

    @Test
    fun everyAliasBelongsToAnIconOrIsARetiredStub() {
        assertEquals(MeldAppIcon.entries.map { it.aliasName }.toSet() + LEGACY_ALIAS_NAMES, aliases.keys)
    }

    @Test
    fun onlyTheDefaultIconIsEnabledOutOfTheBox() {
        val enabled = aliases.filterValues { it.getAttribute("android:enabled") == "true" }.keys
        assertEquals(setOf(MeldAppIcon.DEFAULT.aliasName), enabled)
        assertEquals("MainActivityOriginal", MeldAppIcon.DEFAULT.aliasName)
    }

    @Test
    fun everyAliasIsALauncherEntryWithAnIconThatExists() {
        for ((name, alias) in aliases) {
            val categories = alias.getElementsByTagName("category")
            val names = (0 until categories.length).map { (categories.item(it) as Element).getAttribute("android:name") }
            assertTrue(name, "android.intent.category.LAUNCHER" in names)
            val icon = alias.getAttribute("android:icon").removePrefix("@mipmap/")
            assertTrue("$name: adaptive $icon", File("src/main/res/mipmap-anydpi-v26/$icon.xml").isFile)
            assertTrue("$name: legacy $icon", File("src/main/res/mipmap-mdpi/$icon.png").isFile)
        }
    }

    @Test
    fun retiredStubsShowTheDefaultIcon() {
        for (name in LEGACY_ALIAS_NAMES) assertEquals(name, "@mipmap/ic_launcher", aliases.getValue(name).getAttribute("android:icon"))
    }
}
