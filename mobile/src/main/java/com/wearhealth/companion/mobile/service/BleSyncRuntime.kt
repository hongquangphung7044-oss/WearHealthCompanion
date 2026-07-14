package com.wearhealth.companion.mobile.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide single owner for the GATT server used by both the foreground service and UI. */
object BleSyncRuntime {
    @Volatile
    private var instance: BleSyncServer? = null
    private val generation = AtomicInteger(0)

    private fun server(context: Context): BleSyncServer = instance ?: synchronized(this) {
        instance ?: BleSyncServer(context.applicationContext).also { instance = it }
    }

    fun status(context: Context): StateFlow<String> = server(context).status
    fun connected(context: Context): StateFlow<Boolean> = server(context).connected
    fun start(context: Context) {
        generation.incrementAndGet()
        server(context).start()
    }

    fun restart(context: Context) {
        val appContext = context.applicationContext
        val restartGeneration = generation.incrementAndGet()
        server(appContext).stop()
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation.get() == restartGeneration) server(appContext).start()
        }, 300L)
    }

    fun stop(context: Context) {
        generation.incrementAndGet()
        server(context).stop()
    }
}
