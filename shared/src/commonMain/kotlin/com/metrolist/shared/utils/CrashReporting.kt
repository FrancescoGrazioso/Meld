package com.metrolist.shared.utils

import com.metrolist.shared.BuildConfig

// Meld: nothing is offered either while the build has no Sentry project to report to.
fun isCrashReportingAvailable(
    appVersion: String = BuildConfig.APP_VERSION,
    dsn: String = BuildConfig.SENTRY_DSN,
): Boolean = '+' !in appVersion && dsn.isNotBlank()
