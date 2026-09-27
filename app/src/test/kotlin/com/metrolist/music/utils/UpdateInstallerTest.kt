package com.metrolist.music.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdateInstallerTest {
    private fun payload(size: Int) = ByteArray(size) { (it % 256).toByte() }

    @Test
    fun `copyReportingProgress writes every byte of the source`() {
        val source = payload(300_000)
        val output = ByteArrayOutputStream()

        copyReportingProgress(ByteArrayInputStream(source), output, source.size.toLong()) { }

        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun `copyReportingProgress ends at full progress`() {
        val source = payload(300_000)
        val reported = mutableListOf<Float>()

        copyReportingProgress(ByteArrayInputStream(source), ByteArrayOutputStream(), source.size.toLong()) {
            reported += it
        }

        assertEquals(1f, reported.last(), 0f)
    }

    @Test
    fun `copyReportingProgress reports monotonically without repeating a percent`() {
        val source = payload(5_000_000)
        val reported = mutableListOf<Float>()

        copyReportingProgress(ByteArrayInputStream(source), ByteArrayOutputStream(), source.size.toLong()) {
            reported += it
        }

        assertEquals(reported.distinct(), reported)
        assertEquals(reported.sorted(), reported)
        assertTrue(reported.size <= 101)
    }

    @Test
    fun `copyReportingProgress reports nothing when the size is unknown`() {
        val source = payload(300_000)
        val output = ByteArrayOutputStream()
        val reported = mutableListOf<Float>()

        copyReportingProgress(ByteArrayInputStream(source), output, 0L) { reported += it }

        assertTrue(reported.isEmpty())
        assertArrayEquals(source, output.toByteArray())
    }
}
