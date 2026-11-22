package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DSN = "https://key@sentry.example/1"

class CrashReportingTest {
    @Test
    fun plainReleaseVersionsAllowReporting() {
        assertTrue(isCrashReportingAvailable("14.0.0", DSN))
        assertTrue(isCrashReportingAvailable("14.1.2", DSN))
    }

    @Test
    fun buildMetadataDisablesReporting() {
        assertFalse(isCrashReportingAvailable("14.0.0+24897bc", DSN))
        assertFalse(isCrashReportingAvailable("14.1.2+build.123", DSN))
    }

    @Test
    fun aBuildWithoutASentryProjectOffersNoCrashReports() {
        assertFalse(isCrashReportingAvailable("14.0.0", dsn = ""))
    }
}
