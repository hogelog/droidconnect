package org.hogel.pocketssh

import android.app.Application

/**
 * Release no-op. Sentry is intentionally absent from the release APK so the
 * production app makes no telemetry phone-home. The matching debug
 * implementation in `app/src/debug/java/.../CrashReporting.kt` initializes
 * Sentry for development builds only.
 */
object CrashReporting {
    fun init(@Suppress("UNUSED_PARAMETER") app: Application) = Unit
}
