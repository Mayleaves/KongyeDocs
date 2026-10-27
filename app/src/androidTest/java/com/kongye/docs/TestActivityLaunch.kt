package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import java.io.FileInputStream

/** Shell launch avoids OEM background-start restrictions and waiting for entry animations. */
internal fun startTestActivity(instrument: Instrumentation,context: Context): MainActivity {
    val monitor=instrument.addMonitor(MainActivity::class.java.name,null,false)
    try {
        instrument.uiAutomation.executeShellCommand("am start -n ${context.packageName}/com.kongye.docs.MainActivity").use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
        return instrument.waitForMonitorWithTimeout(monitor,10_000) as? MainActivity ?: error("Test Activity did not launch")
    } finally { instrument.removeMonitor(monitor) }
}
