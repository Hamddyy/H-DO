package com.hdo.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class HdoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.log").writeText(
                    SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(Date()) + " thread=" + t.name + "\n" +
                        e.javaClass.name + ": " + (e.message ?: "") + "\n" +
                        e.stackTrace.take(30).joinToString("\n") { it.toString() } + "\n")
            } catch (_: Throwable) { }
            prevHandler?.uncaughtException(t, e)
        }
        Store.init(this)
        Notif.channels(this)
        CoroutineScope(Dispatchers.IO).launch {
            // Extracting Python/yt-dlp/ffmpeg can take a few seconds the first time: do it off the main thread.
            Engine.init(this@HdoApp)
            Engine.prewarmYt(this@HdoApp)
            autoUpdateEngine()
        }
        // library refresh every 6 hours in the background (also runs when the app opens, see MainActivity)
        val req = PeriodicWorkRequestBuilder<LibraryWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("hdo-library", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * The extractor is compiled in, so "updates" = a new APK: fetch the remote config at open (hourly at most)
     * and notify when a newer version exists. Small YouTube-side changes can be announced there too.
     */
    private fun autoUpdateEngine() {
        if (Engine.state.value != "ready" || !Store.settings.value.autoUpdateEngine || !Engine.online(this)) return
        val now = System.currentTimeMillis()
        if (now - Store.getLong("cfgFetched") < 3_600_000L) return
        Store.putLong("cfgFetched", now)
        Engine.maybeYtUpdate(this)
        val c = RemoteCfg.refresh(this) ?: return
        if (c.latestVersionCode > BuildConfig.VERSION_CODE) {
            val lang = Store.settings.value.lang
            Notif.channels(this)
            Notif.post(this, 78, Notif.simple(this, "H-DO " + c.latestVersion,
                (if (lang == "ar") c.noticeAr else c.noticeEn).ifBlank { Strings.get("new_version", lang) }))
        }
    }
}
