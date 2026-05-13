package org.hogel.pocketssh

import android.app.Application
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Debug-only crash reporting hook. Release builds use the matching no-op in
 * `app/src/release/java/.../CrashReporting.kt` and ship without the Sentry SDK,
 * so the production app sends no telemetry of any kind.
 *
 * The privacy policy promises that Sentry events do not include SSH
 * credentials, hostnames, usernames, or terminal contents. The configuration
 * here is what backs that promise:
 *
 * - `beforeSend` redacts every event message and exception value, leaving
 *   only the type and stack trace. SSH-layer exceptions occasionally carry
 *   the hostname in their message (e.g. `UnknownHostException`), so a class
 *   name + stack frame survives but no user-controlled string does.
 * - View hierarchy and screenshot attachments are forced off so the
 *   connection screen's `EditText`s and the terminal output cannot ride
 *   along with an error.
 * - Session replay sample rates are pinned to zero. The replay artifact is
 *   on the runtime classpath via the `sentry-android` umbrella but the SDK's
 *   defaults are 0; pin them explicitly so a future SDK upgrade cannot flip
 *   replay on without us noticing.
 * - User-interaction breadcrumbs are disabled so taps on the connection
 *   form's `EditText`s do not surface their resource IDs as breadcrumbs.
 *
 * Auto-init is disabled in the debug manifest so this manual init is the
 * single source of truth for what data may leave the device.
 */
object CrashReporting {

    fun init(app: Application) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isEmpty()) return

        SentryAndroid.init(app) { options ->
            options.dsn = dsn
            options.release = BuildConfig.SENTRY_RELEASE
            options.dist = BuildConfig.SENTRY_DIST

            options.isAttachViewHierarchy = false
            options.isAttachScreenshot = false
            options.isEnableUserInteractionBreadcrumbs = false

            options.sessionReplay.sessionSampleRate = 0.0
            options.sessionReplay.onErrorSampleRate = 0.0

            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                event.message?.let { msg ->
                    msg.formatted = REDACTED
                    msg.message = REDACTED
                    msg.params = null
                }
                event.exceptions?.forEach { it.value = REDACTED }
                event
            }
        }
    }

    private const val REDACTED = "<redacted>"
}
