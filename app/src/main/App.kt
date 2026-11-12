package com.recuperafiles

import android.app.Application
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val log = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            log.writeText(e.stackTraceToString())
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
