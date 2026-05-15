package org.hogel.pocketssh.debug

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import org.hogel.pocketssh.R

/**
 * Debug-only menu add-on. MainActivity calls [inflate] from
 * `onCreateOptionsMenu` and [handle] from `onOptionsItemSelected`; in
 * release builds the matching no-op stub ships instead, so the menu items
 * and the activities they launch never appear in production APKs.
 */
object DebugMenu {

    fun inflate(activity: Activity, menu: Menu) {
        activity.menuInflater.inflate(R.menu.menu_main_debug, menu)
    }

    fun handle(activity: Activity, item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_diag_dump -> {
            activity.startActivity(Intent(activity, DiagDumpActivity::class.java))
            true
        }
        R.id.action_logcat -> {
            activity.startActivity(Intent(activity, LogcatActivity::class.java))
            true
        }
        else -> false
    }
}
