package com.trustmarktool

import android.app.Application
import com.trustmarktool.worker.TaskNotifications

class TrustMarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskNotifications.ensureChannel(this)
    }
}
