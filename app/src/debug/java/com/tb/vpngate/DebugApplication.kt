package com.tb.vpngate

import android.app.Application
import android.os.Build
import android.os.StrictMode

/**
 * Debug-only Application that turns StrictMode on.
 *
 * This exists because of a bug that shipped once already: the feed was being parsed on the main
 * thread, which no compiler, lint rule or unit test could see. StrictMode would have flagged it
 * the first time the app was launched on a device.
 *
 * Violations are logged, not fatal. `penaltyDeath` is tempting but third-party initialisation
 * (DataStore in particular) touches disk on the main thread during startup, so a fatal policy
 * would crash on someone else's behaviour and the whole thing would get switched off within a
 * day. Logging keeps the signal available without that.
 *
 * Read them with:
 *     adb logcat | grep -i StrictMode
 */
class DebugApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                // The one that matters most here: a ServiceConnection bound more times than it
                // is unbound shows up as a leaked registration object. That is exactly the
                // failure mode the bind() mutex exists to prevent.
                .detectLeakedRegistrationObjects()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        detectUnsafeIntentLaunch()
                    }
                }
                .penaltyLog()
                .build(),
        )
    }
}
