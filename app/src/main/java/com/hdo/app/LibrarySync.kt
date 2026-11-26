package com.hdo.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the downloads list in step with the files on the device. Runs in the background
 * when the app opens (at most every 10 minutes) and every 6 hours via WorkManager.
 *  - removes entries whose file was deleted outside the app
 *  - adds files of this app that are missing from the list
 *  - deletes finished downloads older than N days when that option is enabled
 */
object LibrarySync {
    suspend fun run(ctx: Context) {
        if (Store.syncing.value) return
        Store.syncing.value = true
        try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val days = Store.settings.value.autoDeleteDays
                val done = Store.tasks.value.filter { it.status == St.DONE && it.uri.isNotBlank() }
                val gone = HashSet<String>()
                for (t in done) {
                    if (!Storage.exists(ctx, t)) { gone.add(t.id); continue }
                    if (days > 0 && t.finished > 0 && now - t.finished > days * 86_400_000L) { Storage.delete(ctx, t); gone.add(t.id) }
                }
                val known = done.filter { it.id !in gone }.map { it.uri }.toSet()
                val found = Storage.scanUntracked(ctx, known)
                if (gone.isNotEmpty() || found.isNotEmpty()) Store.replaceTasks { l -> l.filter { it.id !in gone } + found }
                Store.markSynced(now)
            }
        } finally {
            Store.syncing.value = false
        }
    }
}

class LibraryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        LibrarySync.run(applicationContext)
        return Result.success()
    }
}
