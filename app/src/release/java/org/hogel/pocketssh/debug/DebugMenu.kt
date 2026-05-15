package org.hogel.pocketssh.debug

import android.app.Activity
import android.view.Menu
import android.view.MenuItem

/**
 * Release no-op. The matching debug implementation in
 * `app/src/debug/java/.../debug/DebugMenu.kt` adds diagnostic menu entries
 * and routes them to debug-only activities. Release APKs never reference
 * those activities, so they (and their resources) are absent from the
 * production binary.
 */
object DebugMenu {
    fun inflate(@Suppress("UNUSED_PARAMETER") activity: Activity, @Suppress("UNUSED_PARAMETER") menu: Menu) = Unit
    fun handle(@Suppress("UNUSED_PARAMETER") activity: Activity, @Suppress("UNUSED_PARAMETER") item: MenuItem): Boolean = false
}
