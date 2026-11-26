package com.hdo.app

import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Multi-connection HTTP range downloader: N parallel connections pull 10 MB chunks into one
 * file (the same technique yt-dlp uses with -N + --http-chunk-size, what made YTDLnis fast).
 * Chunk progress persists to a sidecar file, so a retry resumes where it stopped.
 */
object RangeDl {
    class Canceled : InterruptedException("canceled")

    /** The stream URL stopped working mid-download (403/401/410): the caller re-resolves and retries; partial data stays. */
    class Expired : Exception("url expired")

    private val flags = ConcurrentHashMap<String, AtomicBoolean>()
    fun cancel(id: String) { flags.getOrPut(id) { AtomicBoolean(false) }.set(true) }
    private fun flag(id: String) = flags.getOrPut(id) { AtomicBoolean(false) }

    private const val CHUNK = 10L * 1024 * 1024

    /** Total size via a 1-byte range probe; -1 = server did not say. */
    private fun sizeOf(url: String): Long {
        return try {
            val c = Engine.openConn(url, "bytes=0-0")
            val code = c.responseCode
            val cr = c.getHeaderField("Content-Range")
            val cl = c.getHeaderField("Content-Length")
            c.disconnect()
            when {
                code == 403 || code == 401 || code == 410 -> throw Expired()
                cr != null && cr.contains("/") -> cr.substringAfter("/").toLongOrNull() ?: -1
                code == 200 && cl != null -> cl.toLongOrNull() ?: -1
                else -> -1
            }
        } catch (e: Expired) { throw e } catch (e: Exception) { -1 }
    }

    fun download(id: String, url: String, out: File, connections: Int, rateBps: Long,
                 onProgress: (done: Long, total: Long, speedBps: Long, etaSec: Long) -> Unit): Long {
        flag(id).set(false)
        val total = sizeOf(url)
        if (total <= 0 || connections <= 1 || total < CHUNK) return single(id, url, out, total, rateBps, onProgress)
        val nChunks = ((total + CHUNK - 1) / CHUNK).toInt()
        out.parentFile?.mkdirs()
        val stateFile = File(out.parentFile, out.name + ".st")
        val doneArr = LongArray(nChunks)
        if (stateFile.isFile && out.isFile && out.length() == total) {
            try {
                val a = JSONArray(stateFile.readText())
                for (i in 0 until min(a.length(), nChunks)) doneArr[i] = a.optLong(i)
            } catch (_: Exception) { doneArr.fill(0) }
        }
        val next = AtomicInteger(0)
        val err = AtomicReference<Throwable>(null)
        val doneTotal = AtomicLong(doneArr.sum())
        val speedWin = AtomicLong(0)
        val winStart = AtomicLong(System.nanoTime())
        val lastSpeed = AtomicLong(0)
        val lastCb = AtomicLong(0)
        val arrLock = Any()

        fun bump(n: Int) {
            val totalDone = doneTotal.addAndGet(n.toLong())
            val now = System.nanoTime()
            val wb = speedWin.addAndGet(n.toLong())
            val ws = winStart.get()
            if (now - ws >= 1_000_000_000L && winStart.compareAndSet(ws, now)) {
                lastSpeed.set((wb * 1e9 / (now - ws)).toLong()); speedWin.set(0)
            }
            val t = System.currentTimeMillis()
            if (t - lastCb.get() > 300) {
                lastCb.set(t)
                val sp = lastSpeed.get()
                onProgress(totalDone, total, sp, if (sp > 0) maxOf(0, (total - totalDone) / sp) else 0)
            }
        }

        val workers = (0 until connections).map {
            thread(name = "hdo-dl-$it") {
                val raf = RandomAccessFile(out, "rw")
                try {
                    raf.setLength(total)
                    while (true) {
                        if (flag(id).get()) throw Canceled()
                        if (err.get() != null) return@thread
                        val ci = next.getAndIncrement()
                        if (ci >= nChunks) return@thread
                        val start = ci * CHUNK
                        val end = min(total, start + CHUNK) - 1
                        var attempt = 0
                        while (true) {
                            if (flag(id).get()) throw Canceled()
                            val off = start + synchronized(arrLock) { doneArr[ci] }
                            if (off > end) break
                            try {
                                fetchRange(id, url, raf, off, end, rateBps / connections) { n ->
                                    synchronized(arrLock) { doneArr[ci] += n }
                                    bump(n)
                                }
                                synchronized(arrLock) { saveState(stateFile, doneArr) }
                            } catch (e: Expired) { throw e } catch (e: Canceled) { throw e } catch (e: Exception) {
                                if (++attempt > 6) throw e
                                Thread.sleep(min(1000L * attempt, 5000L))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    err.compareAndSet(null, t)
                } finally {
                    try { raf.close() } catch (_: Exception) { }
                }
            }
        }
        workers.forEach { it.join() }
        err.get()?.let { throw it }
        stateFile.delete()
        onProgress(doneTotal.get(), total, lastSpeed.get(), 0)
        return total
    }

    private fun saveState(f: File, arr: LongArray) {
        try { f.writeText(JSONArray(arr.toList()).toString()) } catch (e: Exception) { Engine.logEvent(null, "RangeDl:132", e.toString()) }
    }

    private fun fetchRange(id: String, url: String, raf: RandomAccessFile, off: Long, end: Long, rateBps: Long, onBytes: (Int) -> Unit) {
        val c = Engine.openConn(url, "bytes=$off-$end")
        val code = c.responseCode
        if (code == 403 || code == 401 || code == 410) { c.disconnect(); throw Expired() }
        if (code !in 200..299) { c.disconnect(); throw java.io.IOException("HTTP $code") }
        val inp = c.inputStream
        val buf = ByteArray(64 * 1024)
        var pos = off
        val started = System.nanoTime()
        var bytesThis = 0L
        try {
            while (pos <= end) {
                if (flag(id).get()) throw Canceled()
                val n = inp.read(buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
                if (n <= 0) break
                raf.seek(pos); raf.write(buf, 0, n)
                pos += n; bytesThis += n
                onBytes(n)
                if (rateBps > 0) {
                    val expected = (bytesThis * 1e9 / rateBps).toLong()
                    val actual = System.nanoTime() - started
                    if (expected > actual) Thread.sleep((expected - actual) / 1_000_000L)
                }
            }
        } finally {
            try { inp.close() } catch (_: Exception) { }
            c.disconnect()
        }
    }

    /** Single-connection fallback (unknown size or tiny file). Resumes from the file length when the server accepts ranges. */
    private fun single(id: String, url: String, out: File, total: Long, rateBps: Long,
                       onProgress: (done: Long, total: Long, speedBps: Long, etaSec: Long) -> Unit): Long {
        out.parentFile?.mkdirs()
        var done = if (total > 0 && out.isFile) out.length().coerceAtMost(total) else 0L
        if (total > 0 && done >= total) { onProgress(total, total, 0, 0); return total }
        var attempt = 0
        while (true) {
            val c = Engine.openConn(url, if (done > 0) "bytes=$done-" else null)
            val code = c.responseCode
            if (code == 403 || code == 401 || code == 410) { c.disconnect(); out.delete(); throw Expired() }
            if (code !in 200..299) {
                c.disconnect()
                if (++attempt > 6) throw java.io.IOException("HTTP $code")
                Thread.sleep(min(1000L * attempt, 5000L)); continue
            }
            if (code == 200 && done > 0) done = 0    // range ignored: restart the file
            val len = if (total > 0) total else c.contentLengthLong.let { if (it > 0) it + done else -1L }
            val raf = RandomAccessFile(out, "rw")
            try {
                if (len > 0) raf.setLength(len)
                raf.seek(done)
                val inp = c.inputStream
                val buf = ByteArray(64 * 1024)
                var window = 0L; var winStart = System.nanoTime()
                var lastCb = 0L
                val started = System.nanoTime(); var bytesThis = 0L
                while (true) {
                    if (flag(id).get()) throw Canceled()
                    val n = inp.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    done += n; window += n; bytesThis += n
                    val now = System.nanoTime()
                    if (now - winStart >= 1_000_000_000L) {
                        val sp = (window * 1e9 / (now - winStart)).toLong()
                        window = 0; winStart = now
                        val t = System.currentTimeMillis()
                        if (t - lastCb > 300) {
                            lastCb = t
                            onProgress(done, len, sp, if (sp > 0 && len > 0) maxOf(0, (len - done) / sp) else 0)
                        }
                    }
                    if (rateBps > 0) {
                        val expected = (bytesThis * 1e9 / rateBps).toLong()
                        val actual = System.nanoTime() - started
                        if (expected > actual) Thread.sleep((expected - actual) / 1_000_000L)
                    }
                }
            } catch (e: Canceled) { throw e } catch (e: Exception) {
                try { raf.close() } catch (_: Exception) { }
                c.disconnect()
                if (++attempt > 6) throw e
                Thread.sleep(min(1000L * attempt, 5000L)); continue
            }
            try { raf.close() } catch (_: Exception) { }
            c.disconnect()
            onProgress(done, len, 0, 0)
            return done
        }
    }
}
