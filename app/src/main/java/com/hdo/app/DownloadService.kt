package com.hdo.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Notif {
    const val CH_PROGRESS = "hdo_progress"
    const val CH_DONE_SOUND = "hdo_done_sound"
    const val CH_DONE_SILENT = "hdo_done_silent"
    const val ACTION_PAUSE_ALL = "com.hdo.app.PAUSE_ALL"
    const val ACTION_CANCEL_ALL = "com.hdo.app.CANCEL_ALL"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE_SOUND, "Completed", NotificationManager.IMPORTANCE_DEFAULT).apply {
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE_SILENT, "Completed (silent)", NotificationManager.IMPORTANCE_LOW))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun broadcast(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: Exception) { Engine.logEvent(null, "DownloadService:56", e.toString()) }
    }

    /** Opens the finished file when tapped. */
    fun done(ctx: Context, t: Task): Notification {
        val s = Store.settings.value
        val view = try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Storage.shareUri(ctx, t), t.mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, t.id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } catch (e: Exception) { openApp(ctx) }
        return NotificationCompat.Builder(ctx, if (s.notifySound) CH_DONE_SOUND else CH_DONE_SILENT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(Strings.get("done_t", s.lang)).setContentText(t.title)
            .setContentIntent(view).setAutoCancel(true).build()
    }

    fun simple(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_DONE_SOUND).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build()
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        when (intent.action) {
            Notif.ACTION_PAUSE_ALL -> Ops.pauseAll()
            Notif.ACTION_CANCEL_ALL -> Ops.cancelAll(ctx)
        }
    }
}

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val running = ConcurrentHashMap<String, Job>()
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notif.channels(this)
        // When the connection comes back, retry downloads that were waiting for it right away.
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val now = System.currentTimeMillis()
                    Store.tasks.value.filter { it.status == St.QUEUED && it.retries > 0 && it.scheduledAt > now }
                        .forEach { t -> Store.updateTask(t.id) { it.copy(scheduledAt = 0) } }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) { Engine.logEvent(null, "DownloadService:111", e.toString()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, progressNotification(0, 0f, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        try { netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (e: Exception) { Engine.logEvent(null, "DownloadService:121", e.toString()) }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun progressNotification(active: Int, percent: Float, waiting: String?): Notification {
        val lang = Store.settings.value.lang
        val title = if (waiting != null) waiting else if (active > 0) "H-DO • $active" else "H-DO"
        val b = NotificationCompat.Builder(this, Notif.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(Notif.openApp(this)).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, Strings.get("pause", lang), Notif.broadcast(this, Notif.ACTION_PAUSE_ALL, 11))
            .addAction(0, Strings.get("cancel", lang), Notif.broadcast(this, Notif.ACTION_CANCEL_ALL, 12))
        if (active > 0) b.setProgress(100, percent.toInt().coerceIn(0, 100), false).setContentText("${percent.toInt()}%")
        return b.build()
    }

    private suspend fun runLoop() {
        try {
            Engine.awaitReady(this)
        } catch (e: Exception) {
            Store.tasks.value.filter { it.status == St.QUEUED }.forEach { t ->
                Store.updateTask(t.id, true) { it.copy(status = St.ERROR, error = e.message ?: "engine", hint = "") }
            }
            stopSelfSafely(); return
        }
        var idleRounds = 0
        while (true) {
            val s = Store.settings.value
            val now = System.currentTimeMillis()
            val list = Store.tasks.value
            val active = list.filter { it.isActive }
            val due = list.filter { it.status == St.QUEUED && it.scheduledAt <= now }.sortedBy { it.created }
            val retrySoon = list.any { it.status == St.QUEUED && it.retries > 0 && it.scheduledAt > now && it.scheduledAt - now < 11 * 60_000L }
            if (active.isEmpty() && due.isEmpty() && !retrySoon) {
                if (++idleRounds >= 3) break      // short grace period so a task added right now is not missed
                delay(300); continue
            }
            idleRounds = 0
            val avg = if (active.isEmpty()) 0f else active.map { it.percent }.average().toFloat()
            if (s.wifiOnly && !Engine.unmetered(this)) {
                Notif.post(this, NOTIF_ID, progressNotification(active.size, avg, "H-DO • Wi-Fi"))
                delay(3000); continue
            }
            if (active.size < s.maxConcurrent && due.isNotEmpty()) {
                val t = due.first()
                Store.updateTask(t.id) { it.copy(status = St.DOWNLOADING, error = "", hint = "", info = "", sizeTotal = it.expectSize) }
                running[t.id] = scope.launch { try { runTask(t.id) } finally { running.remove(t.id) } }
                continue
            }
            Notif.post(this, NOTIF_ID, progressNotification(active.size, avg, null))
            delay(800)
        }
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runTask(id: String) {
        val t0 = Store.task(id) ?: return
        val dir = Ops.tmpDir(this, id)
        var baseDone = 0L             // bytes of streams that already finished
        var curTotal = 0L             // size of the stream currently downloading
        var total = t0.expectSize     // overall expected bytes (from the options dialog, refined as sizes arrive)
        val lang = Store.settings.value.lang
        try {
            Engine.download(this, t0, dir) { p, eta, line ->
                if (line.startsWith("[download] Destination:")) {
                    baseDone += curTotal
                    curTotal = 0
                    val name = File(line.substringAfter("Destination:").trim()).nameWithoutExtension.replace(Regex("\\.f\\d+$"), "")
                    if (name.isNotBlank()) Store.updateTask(id) { if (it.title == it.url || it.title.isBlank()) it.copy(title = name) else it }
                }
                val post = Engine.isPostLine(line)
                val sz = if (line.startsWith("[download]")) Engine.parseSizeTotal(line) else 0L
                if (sz > 0) { curTotal = sz; total = maxOf(total, baseDone + curTotal) }
                val done = baseDone + if (p > 0f && curTotal > 0) (p / 100f * curTotal).toLong() else 0L
                // percent from real bytes when the total is known; -1 = unknown (indeterminate bar)
                val pct = if (total > 0 && done > 0) (done * 100f / total).coerceAtMost(100f) else -1f
                val speed = Regex("at\\s+(\\S+/s)").find(line)?.groupValues?.get(1)
                Store.updateTask(id) {
                    val np = when {
                        post -> it.percent
                        pct >= 0f -> maxOf(pct, it.percent.coerceAtMost(100f))
                        else -> it.percent
                    }
                    it.copy(
                        status = if (post) St.PROCESSING else St.DOWNLOADING,
                        percent = np,
                        sizeDone = if (done > 0) done else it.sizeDone,
                        sizeTotal = if (total > 0) total else it.sizeTotal,
                        info = when {
                            post -> it.info
                            line.startsWith("[download]") && (speed != null || p >= 0f) ->
                                listOfNotNull(speed, if (eta > 0) fmtEta(eta) else null).joinToString(" • ").ifBlank { it.info }
                            !line.startsWith("[download]") && line.isNotBlank() -> Engine.stageKey(line)?.let { Strings.get(it, lang) } ?: Strings.get("preparing", lang)
                            else -> it.info
                        },
                    )
                }
            }
            // metadata written by yt-dlp (title, channel, site...) decides the final folder and the list entry
            val info = Engine.readInfo(dir)
            if (info != null) Store.updateTask(id) {
                it.copy(
                    title = if (it.title == it.url || it.title.isBlank()) info.str("title").ifBlank { it.title } else it.title,
                    uploader = it.uploader.ifBlank { info.str("uploader").ifBlank { info.str("channel") } },
                    site = it.site.ifBlank { info.str("extractor_key") },
                    thumb = it.thumb.ifBlank { info.str("thumbnail") },
                    duration = if (it.duration > 0) it.duration else info.optLong("duration"),
                )
            }
            val t = Store.task(id) ?: t0
            val f = Engine.pickResult(dir) ?: throw IllegalStateException("no output file produced")
            val size = f.length()
            val uriStr = Storage.publish(this, f, t)
            val mime = Engine.mimeOf(f.extension)
            Store.updateTask(id, true) {
                it.copy(status = St.DONE, percent = 100f, info = "", uri = uriStr, size = size, mime = mime,
                    sizeDone = size, sizeTotal = size, finished = System.currentTimeMillis(), title = if (it.title == it.url) f.nameWithoutExtension else it.title)
            }
            if (t0.infoPath.isNotBlank()) File(t0.infoPath).delete()
            dir.deleteRecursively()
            if (Store.settings.value.notifyDone) Store.task(id)?.let { Notif.post(this, 1000 + (id.hashCode() and 0xFFFF), Notif.done(this, it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            onStopped(id, dir)
        } catch (e: Exception) {
            if (t0.infoPath.isNotBlank()) {
                // fast-path info may be stale (links expire): drop it and retry once with a fresh extraction
                File(t0.infoPath).delete()
                // keep percent/sizeDone: the .part file stays on disk and yt-dlp resumes from it, so progress is not really lost
                Store.updateTask(id, true) {
                    it.copy(infoPath = "", status = St.QUEUED, scheduledAt = 0, info = "", error = "", hint = "")
                }
                return
            }
            onFailed(id, (e.message ?: e.javaClass.simpleName).replace(Regex("\u001B\\[[0-9;]*m"), ""))
        }
    }

    private fun onFailed(id: String, msg: String) {
        val s = Store.settings.value
        val code = Engine.friendlyError(msg)
        val permanent = code in setOf("cookies", "geo", "unavailable", "live", "disk", "unsupported")
        val tries = Store.task(id)?.retries ?: 0
        val now = System.currentTimeMillis()
        if (s.autoRetry && !permanent && tries < s.retries) {
            val wait = listOf(15, 45, 120, 300, 600)[minOf(tries, 4)] * 1000L        // 15s, 45s, 2m, 5m, 10m
            Store.updateTask(id, true) {
                it.copy(status = St.QUEUED, retries = tries + 1, scheduledAt = now + wait, error = msg.takeLast(200), hint = code, info = "")
            }
        } else {
            Store.updateTask(id, true) { it.copy(status = St.ERROR, error = msg.takeLast(400), hint = code, info = "", finished = now) }
        }
    }

    private fun onStopped(id: String, dir: File) {
        if (Ops.pausing.remove(id)) {
            Store.updateTask(id, true) { it.copy(status = St.PAUSED, info = "") }
        } else {
            Store.updateTask(id, true) { it.copy(status = St.CANCELED, info = "", finished = System.currentTimeMillis()) }
            dir.deleteRecursively()
        }
    }

    companion object {
        const val NOTIF_ID = 42

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        /** Safe from the background: Android 12+ may refuse to start a foreground service. */
        fun startOrNotify(ctx: Context, pending: Int) {
            try {
                start(ctx)
            } catch (e: Exception) {
                Notif.channels(ctx)
                Notif.post(ctx, 77, Notif.simple(ctx, "H-DO", "$pending ▶"))
            }
        }
    }
}

fun fmtEta(s: Long): String = when {
    s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
    s >= 60 -> "${s / 60}m ${s % 60}s"
    else -> "${s}s"
}
