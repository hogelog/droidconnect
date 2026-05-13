package org.hogel.pocketssh

import android.app.Application

class PocketSshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporting.init(this)
    }
}
