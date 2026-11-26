package com.hdo.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.text.SimpleDateFormat
import java.util.Date

/** One selectable video quality of a video: height in pixels and approximate size (video + best audio), 0 = unknown. */
data class VOpt(val height: Int, val bytes: Long)

data class Probe(
    val type: String,               // video | playlist
    val title: String,
    val uploader: String,
    val thumb: String,
    val duration: Long,
    val site: String,
    val videoOptions: List<VOpt>,
    val audioBytes: Long,
    val entries: List<Entry>,
    val isLive: Boolean,
    val url: String,
    val views: Long = 0,
    val id: String = "",
    val raw: String = "",        // kept for shape compatibility; always "" with the NewPipe engine
)

object Engine {
    /** init | ready | error:<message> */
    val state = MutableStateFlow("init")
    private val lock = Any()

    @Volatile private var appCtx: Context? = null
    @Volatile private var ffmpegBin: File? = null

    // ------------------------------------------------------------ diagnostics
    private const val DIAG_MAX = 120 * 1024

    /** Appends a timestamped line to filesDir/diag.log (best effort, capped size). */
    fun logEvent(ctx: Context?, tag: String, msg: String) {
        try {
            val c = (ctx ?: appCtx)?.applicationContext ?: return
            val f = File(c.filesDir, "diag.log")
            if (f.length() > DIAG_MAX) f.writeText(f.readText().takeLast(DIAG_MAX / 2))
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts [$tag] ${msg.take(400)}\n")
        } catch (_: Throwable) { }
    }

    /** Text the user can copy and send: versions, engine states, last crash, recent events. */
    fun diagText(ctx: Context): String {
        val c = ctx.applicationContext
        val sb = StringBuilder()
        sb.append("H-DO ").append(BuildConfig.VERSION_NAME).append(" vc").append(BuildConfig.VERSION_CODE)
            .append(" engine=").append(state.value).append(" yt=").append(ytState).append("\n")
        try { sb.append("--- crash ---\n").append(File(c.filesDir, "crash.log").readText().takeLast(3000)).append("\n") } catch (_: Throwable) { }
        try { sb.append("--- events ---\n").append(File(c.filesDir, "diag.log").readText().takeLast(4000)) } catch (_: Throwable) { }
        return sb.toString()
    }

    fun init(ctx: Context) {
        synchronized(lock) {
            if (state.value == "ready") return
            try {
                val app = ctx.applicationContext
                appCtx = app
                if (!YT_DLP_ONLY) Np.init(app)
                try {
                    FFmpeg.getInstance().init(app)
                    ffmpegBin = findFfmpeg(app)
                } catch (_: Throwable) { }
                state.value = "ready"
                logEvent(app, "engine", "init ready")
            } catch (e: Throwable) {
                state.value = "error:" + (e.message ?: e.javaClass.simpleName)
                logEvent(ctx, "engine", "init FAILED: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }
    }

    /** Suspends until the engine is ready; initializes it if nobody has yet. */
    suspend fun awaitReady(ctx: Context) {
        if (state.value == "init") init(ctx)
        val s = state.first { it != "init" }
        if (s.startsWith("error:")) throw IllegalStateException(s.removePrefix("error:"))
    }

    // ------------------------------------------------------------ URL helpers
    private val TRACKING = setOf(
        "fbclid", "igshid", "igsh", "si", "feature", "pp", "gclid", "mc_cid", "mc_eid", "ref", "ref_src", "ref_url", "s",
        "t_src", "share", "wtsid", "__tn__", "mibextid", "sfnsn", "app", "ab_channel",
    )
    private val URL_RE = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun cleanUrl(u: String): String {
        val url = u.trim().substringBefore('#')
        val q = url.indexOf('?')
        if (q < 0) return url
        val kept = url.substring(q + 1).split('&').filter { it.isNotEmpty() }.filter {
            val k = it.substringBefore('=').lowercase()
            k !in TRACKING && !k.startsWith("utm_")
        }
        return if (kept.isEmpty()) url.substring(0, q) else url.substring(0, q) + "?" + kept.joinToString("&")
    }

    fun extractUrls(text: String): List<String> =
        URL_RE.findAll(text).map { cleanUrl(it.value.trimEnd('.', ',', ';', ')', ']', '}')) }.distinct().toList()

    fun ytId(u: String): String =
        Regex("(?:watch\\?v=|youtu\\.be/|shorts/|live/)([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1) ?: ""

    /** "90", "1:30", "01:02:03" -> seconds, or null when invalid. */
    fun parseTime(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty()) return null
        var v = 0.0
        for (p in t.split(":")) v = v * 60 + (p.toDoubleOrNull() ?: return null)
        return v
    }

    // ------------------------------------------------------- HTTP primitives
    /** Connection with the app proxy, the Android YouTube UA (proven fastest on his route) and the user's cookies. */
    fun openConn(u: String, range: String? = null): HttpURLConnection {
        val url = URL(u)
        val p = Np.proxy()
        val c = (if (p != null) url.openConnection(p) else url.openConnection()) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 13) gzip")
        val ck = cookieHeader(u)
        if (ck.isNotBlank()) c.setRequestProperty("Cookie", ck)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    // --------------------------------------------------------------- cookies
    private var cookieCache: Pair<Long, List<Array<String>>>? = null

    fun cookiesFile(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    fun importCookies(ctx: Context, uri: Uri): Boolean {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return false
        if (!text.contains('\t')) return false
        cookiesFile(ctx).writeText(text)
        cookieCache = null
        return true
    }

    private fun cookieRows(ctx: Context): List<Array<String>> {
        val f = cookiesFile(ctx)
        if (!f.exists()) return emptyList()
        val m = f.lastModified()
        cookieCache?.let { if (it.first == m) return it.second }
        val rows = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull {
            val p = it.split("\t")
            if (p.size >= 7) p.take(7).toTypedArray() else null
        }
        cookieCache = m to rows
        return rows
    }

    /** "k=v; k2=v2" for the cookies whose domain matches this URL's host (Netscape cookies.txt). */
    fun cookieHeader(url: String): String {
        val ctx = appCtx ?: return ""
        val host = try { URL(url).host } catch (e: Exception) { return "" }
        val now = System.currentTimeMillis() / 1000
        return cookieRows(ctx).filter { r ->
            val d = r[0]
            val bare = d.removePrefix(".")
            (host == bare || host.endsWith(".$bare")) && (r[4].toLongOrNull()?.let { it == 0L || it > now } ?: true)
        }.joinToString("; ") { it[5] + "=" + it[6] }
    }

    // --------------------------------------------------------- preview streams
    /** A resolved preview stream: progressive when audio is empty, otherwise separate video + audio URLs the player merges. */
    data class StreamSet(val video: String, val audio: String = "")

    private data class CachedStream(val set: StreamSet, val at: Long)
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    fun cachedStream(key: String): StreamSet? =
        streamCache[key]?.let { if (System.currentTimeMillis() - it.at < 30 * 60_000L) it.set else null }

    fun cacheStream(key: String, set: StreamSet) {
        if (key.isNotBlank() && set.video.isNotBlank()) streamCache[key] = CachedStream(set, System.currentTimeMillis())
    }

    /** One 2-byte range GET proving a direct stream URL is usable from THIS network before it reaches the player. */
    fun probeUrl(u: String): Boolean = try {
        val c = openConn(u, "bytes=0-1")
        c.responseCode in 200..399
    } catch (e: Exception) { false }

    /** Cache + verified extractor path; null when unavailable - the caller falls back to a fresh resolve. */
    fun fastStream(key: String, pageUrl: String, height: Int): StreamSet? {
        if (YT_DLP_ONLY) return null   // NewPipe path disabled; preview resolves via resolveStreamSlow
        val k = "$key|$height"
        cachedStream(k)?.let { return it }
        val fs = try { Np.streamSet(Np.info(appCtx, pageUrl), height) } catch (e: Throwable) { return null }
        return if (fs.video.isNotBlank() && probeUrl(fs.video)) fs.also { cacheStream(k, it) } else null
    }

    /** Cache-aware preview resolution: instant on a hit, one verified extractor call otherwise. */
    fun resolveStream(ctx: Context, key: String, pageUrl: String, height: Int = 480): StreamSet =
        fastStream(key, pageUrl, height) ?: resolveStreamSlow(ctx, key, pageUrl, height)

    /** Last-resort path: re-resolve completely fresh URLs (the cached ones failed on this network). */
    fun resolveStreamSlow(ctx: Context, key: String, pageUrl: String, height: Int = 480): StreamSet {
        val k = "$key|$height"
        cachedStream(k)?.let { return it }
        val fs = wrap {
            if (!isNpSite(pageUrl)) {
                val cached = ytInfoCache[pageUrl]?.takeIf { System.currentTimeMillis() - it.at < INFO_TTL }
                val local = cached?.let { streamSetFromInfo(it.json, height) } ?: StreamSet("")
                if (local.video.isNotBlank()) local else ytStreamSet(ctx, pageUrl, height)
            } else {
                val np = try { Np.streamSet(Np.info(appCtx ?: ctx, pageUrl, fresh = true), height) } catch (e: Throwable) { StreamSet("") }
                if (np.video.isNotBlank()) np else ytStreamSet(ctx, pageUrl, height)
            }
        }
        if (fs.video.isNotBlank()) cacheStream(k, fs)
        return fs
    }

    // ---- SponsorBlock: community-cut segments for the preview player. Cached per video id.
    private val sbCache = ConcurrentHashMap<String, List<FloatArray>>()

    /** [start, end) seconds of skippable segments for a YouTube video id; empty when none or unreachable. */
    fun sponsorSegments(id: String): List<FloatArray> {
        if (id.isBlank()) return emptyList()
        sbCache[id]?.let { return it }
        val cats = Store.settings.value.sponsorCats.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\",\"")
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=" + id +
            "&categories=" + URLEncoder.encode("[\"" + cats + "\"]", "UTF-8")
        val out = try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "H-DO/1.5")
            }
            if (c.responseCode != 200) {
                emptyList()
            } else {
                val arr = JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                (0 until arr.length()).mapNotNull { i ->
                    val seg = arr.optJSONObject(i)?.optJSONArray("segment") ?: return@mapNotNull null
                    floatArrayOf(seg.optDouble(0, -1.0).toFloat(), seg.optDouble(1, -1.0).toFloat())
                        .takeIf { it[0] >= 0f && it[1] > it[0] }
                }.sortedBy { it[0] }
            }
        } catch (e: Exception) { emptyList() }
        sbCache[id] = out
        return out
    }

    // --------------------------------------------------------- error mapping
    private fun <T> wrap(block: () -> T): T {
        try { return block() } catch (e: Exception) { throw mapErr(e) }
    }

    private fun mapErr(e: Exception): Exception {
        if (e is RangeDl.Canceled || e is InterruptedException) return e
        val n = e.javaClass.simpleName
        val msg = when {
            e is java.io.IOException -> "network error: ${e.message}"
            n == "ReCaptchaException" || n == "SignInConfirmNotBotException" -> "Sign in to confirm you're not a bot"
            n == "AgeRestrictedContentException" -> "age-restricted video: login required"
            n == "GeographicRestrictionException" || n == "UnsupportedContentInCountryException" -> "not available in your country"
            n == "PrivateContentException" -> "private video"
            n == "YoutubeMusicPremiumContentException" -> "members-only content: login required"
            n == "PaidContentException" -> "paid content: login required"
            n == "ContentNotAvailableException" || n == "AccountTerminatedException" -> "video unavailable"
            else -> e.message ?: n
        }
        return Exception(msg, e)
    }

    fun friendlyError(msg: String): String {
        val m = msg.lowercase()
        fun any(vararg k: String) = k.any { m.contains(it) }
        return when {
            any("sign in to confirm", "not a bot") -> "botcheck"
            any("login required", "log in", "private video", "members-only", "this video is private",
                "cookies", "restricted video", "age-restricted", "confirm your age", "paid content") -> "cookies"
            any("not available in your country", "geo restricted", "geo-restricted", "blocked it in your country") -> "geo"
            any("video unavailable", "has been removed", "no longer available", "does not exist", "http error 404", "deleted") -> "unavailable"
            any("live event will begin", "is live", "premieres in", "live stream") -> "live"
            any("http error 429", "too many requests", "rate-limit", "rate limit") -> "ratelimit"
            any("no space left", "disk full") -> "disk"
            any("unsupported url", "no video stream", "no audio stream", "no supported") -> "unsupported"
            any("unable to download", "urlopen error", "timed out", "connection reset", "name or service not known",
                "temporary failure", "network is unreachable", "getaddrinfo", "network error") -> "network"
            else -> ""
        }
    }

    fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ----------------------------------------------------------------- probe
    /**
     * @param flat ignored (NewPipe playlist pages are always flat); kept for call-site compatibility.
     */
    fun probe(ctx: Context, url: String, playlist: Boolean, limit: Int = 200, flat: Boolean = true): Probe = wrap {
        if (!isNpSite(url)) return@wrap ytProbe(ctx, url, playlist, limit)
        try {
            if (url.contains("list=")) Np.playlist(ctx, url, limit) else videoProbe(ctx, url)
        } catch (e: Throwable) {
            // NewPipe broke (YouTube change, network, ...): try the self-updating yt-dlp before giving up
            logEvent(ctx, "probe", "NewPipe probe failed (" + e.javaClass.simpleName + ") -> yt-dlp fallback")
            ytProbe(ctx, url, playlist, limit)
        }
    }

    private fun videoProbe(ctx: Context, url: String): Probe {
        val np = Np.info(ctx, url)
        val audioBytes = (np.audios.maxByOrNull { it.bitrate }?.bitrate ?: 0).toLong() * np.duration / 8
        val opts = np.videos.filter { it.height > 0 }.groupBy { it.height }.map { (h, l) ->
            val sz = l.maxOf { it.bitrate.toLong() * np.duration / 8 }
            VOpt(h, if (sz > 0) sz + audioBytes else 0L)
        }.sortedByDescending { it.height }
        return Probe("video", np.title, np.uploader, np.thumb, np.duration, np.service, opts, audioBytes,
            emptyList(), np.live, np.url, np.views, np.id, "")
    }

    /** Search by name. source: youtube | ytmusic | soundcloud. */
    fun search(ctx: Context, query: String, source: String, count: Int): List<Entry> = wrap {
        // Fast path: NewPipe (pure Kotlin, ~1-3s) for the sites it covers; yt-dlp otherwise and on any failure.
        if (source != "soundcloud") {
            try {
                val r = Np.search(ctx, query, source, count)
                if (r.isNotEmpty()) return@wrap r
            } catch (e: Throwable) {
                logEvent(ctx, "search", "NewPipe search failed (" + e.javaClass.simpleName + ") -> yt-dlp fallback")
            }
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (ytState != "ready") {
            if (System.currentTimeMillis() > deadline || ytState.startsWith("error:"))
                throw IllegalStateException("search engine not ready / محرك البحث مش جاهز، جرب تاني")
            Thread.sleep(250)
        }
        ytSearch(ctx, query, source, count)
    }

    /** Live search suggestions from Google/YouTube (empty list on any problem). */
    fun suggestions(q: String): List<String> = try {
        val u = URL("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + URLEncoder.encode(q, "UTF-8"))
        val c = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000 }
        val txt = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(txt).getJSONArray(1)
        (0 until minOf(arr.length(), 8)).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun probeToEntry(p: Probe): Entry = Entry(p.id.ifBlank { p.url }, p.title, p.url, p.duration, p.thumb, p.uploader, p.views)

    // ------------------------------------------------ yt-dlp fallback engine
    // NewPipe covers YouTube/SoundCloud; everything else (Facebook, Instagram, TikTok...) goes through
    // yt-dlp, which is initialized lazily so app start and YouTube flows never pay for Python.
    @Volatile private var ytState = "off"      // off | init | ready | error:<msg>
    private val ytLock = Any()
    // init/update take the write lock; every yt-dlp execute takes the read lock, so the daily
    // self-update can never replace the engine files while a probe/download is mid-flight.
    private val ytRw = ReentrantReadWriteLock()
    private fun <T> ytRead(f: () -> T): T { ytRw.readLock().lock(); try { return f() } finally { ytRw.readLock().unlock() } }

    // User decision (1.5.4): yt-dlp only. NewPipe Extractor v0.27.1 needs Android 13+ (NoSuchMethodError
    // URLDecoder.decode(String,Charset) on his Android 10), so nothing may touch it.
    // Hybrid (1.5.5): NewPipe v0.26.5 (the version YTDLnis ships, works on Android 10) is the fast
    // path for YouTube search/info/preview/download; yt-dlp covers everything else and is the
    // per-operation fallback. Every NewPipe call site catches Throwable and degrades to yt-dlp.
    private val YT_DLP_ONLY = false
    private val NP_HOST = Regex("""(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|soundcloud\.com|on\.soundcloud\.com)$""", RegexOption.IGNORE_CASE)
    fun isNpSite(url: String): Boolean {
        if (YT_DLP_ONLY) return false
        val h = try { URL(url).host.lowercase() } catch (e: Exception) { return false }
        return NP_HOST.containsMatchIn(h)
    }

    private fun ensureYt(ctx: Context) {
        if (ytState == "ready") return
        ytRw.writeLock().lock()
        try {
        synchronized(ytLock) {
            if (ytState == "ready") return
            ytState = "init"
            try {
                val app = ctx.applicationContext
                // drop an outdated extracted engine once so upgrades re-extract the bundled copy
                val p = app.getSharedPreferences("hdo_engine", Context.MODE_PRIVATE)
                if (p.getLong("bundledEngine", 0L) < 20261123L) {
                    File(app.noBackupFilesDir, "youtubedl-android/yt-dlp").delete()
                    p.edit().putLong("bundledEngine", 20261123L).apply()
                }
                YoutubeDL.getInstance().init(app)
                ytState = "ready"
                // daily engine refresh (the fallback engine ages fast); off when the user disabled update checks
                val now = System.currentTimeMillis()
                if (Store.settings.value.autoUpdateEngine && online(app) && now - Store.getLong("engineUpdated") > 24 * 3_600_000L) {
                    Thread {
                        try { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE); Store.putLong("engineUpdated", now) } catch (e: Exception) { Engine.logEvent(null, "Engine:425", e.toString()) }
                    }.start()
                }
            } catch (e: Throwable) {
                ytState = "error:" + (e.message ?: e.javaClass.simpleName)
                logEvent(ctx, "yt", "init FAILED: " + ytState)
                throw IllegalStateException(ytState.removePrefix("error:"))
            }
        }
        } finally { ytRw.writeLock().unlock() }
    }

    /** Pre-extract the yt-dlp engine a few seconds after app open so the first non-NewPipe use starts instantly.
     *  Only file extraction happens here (one-time, then cheap checks); no Python process is spawned. */
    fun prewarmYt(ctx: Context) {
        Thread {
            try {
                Thread.sleep(10_000)
                ensureYt(ctx.applicationContext)
                logEvent(ctx, "yt", "prewarm done (state=$ytState)")
            } catch (e: Throwable) { logEvent(ctx, "yt", "prewarm failed: " + e.javaClass.simpleName) }
        }.start()
    }

    /** Fresh probe results (15 min) let a download reuse the extraction instead of paying for it twice. */
    private data class CachedInfo(val json: String, val at: Long)
    private val ytInfoCache = ConcurrentHashMap<String, CachedInfo>()
    private val INFO_TTL = 15 * 60_000L

    /** Daily yt-dlp engine refresh, callable at app open: background thread, no-op when fresh/offline/disabled. */
    fun maybeYtUpdate(ctx: Context) {
        if (!Store.settings.value.autoUpdateEngine || !online(ctx)) return
        val now = System.currentTimeMillis()
        if (now - Store.getLong("engineUpdated") < 24 * 3_600_000L) return
        Store.putLong("engineUpdated", now)
        Thread {
            try {
                Thread.sleep(60_000) // let the user settle first; never fight an in-flight yt-dlp call
                if (!ytRw.writeLock().tryLock()) { logEvent(ctx, "yt", "update skipped: engine busy"); return@Thread }
                try {
                    ensureYt(ctx)
                    YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                    logEvent(ctx, "yt", "engine self-update done")
                } finally { ytRw.writeLock().unlock() }
            } catch (e: Throwable) { logEvent(ctx, "yt", "self-update failed: " + e.javaClass.simpleName) }
        }.start()
    }

    private fun ytCommon(r: YoutubeDLRequest, ctx: Context) {
        val s = Store.settings.value
        if (s.proxy.isNotBlank()) r.addOption("--proxy", s.proxy.trim())
        val ck = cookiesFile(ctx)
        if (ck.exists()) r.addOption("--cookies", ck.absolutePath)
        r.addOption("--socket-timeout", 20)
    }

    private fun ytProbe(ctx: Context, url: String, playlist: Boolean, limit: Int): Probe {
        ensureYt(ctx)
        val r = YoutubeDLRequest(url).addOption("--dump-single-json").addOption("--no-warnings")
        r.addOption("--flat-playlist")
        if (!playlist) r.addOption("--no-playlist")
        if (limit < 200) r.addOption("--playlist-end", limit)
        ytCommon(r, ctx)
        probeStage.value = "stage_boot"
        try {
            val out = ytRead { YoutubeDL.getInstance().execute(r, null) { _, _, l -> stageKey(l)?.let { probeStage.value = it } }.out }
            val p = parseProbe(out, url, limit)
            if (p.type == "video" && p.raw.isNotBlank()) {
                ytInfoCache[url] = CachedInfo(p.raw, System.currentTimeMillis())
                if (p.url != url) ytInfoCache[p.url] = CachedInfo(p.raw, System.currentTimeMillis())
            }
            return p
        } finally {
            probeStage.value = ""
        }
    }

    private fun parseProbe(out: String, url: String, limit: Int): Probe {
        val line = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: throw IllegalStateException("empty response")
        val o = JSONObject(line)
        val site = o.str("extractor_key")
        val uploader = o.str("uploader").ifBlank { o.str("channel") }
        if (o.str("_type") == "playlist" || o.has("entries")) {
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val entries = ArrayList<Entry>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val nested = e.optJSONArray("entries")
                if (nested != null) {
                    for (j in 0 until nested.length()) nested.optJSONObject(j)?.let { entryOf(it) }?.let { entries.add(it) }
                } else entryOf(e)?.let { entries.add(it) }
                if (entries.size >= limit) break
            }
            return Probe("playlist", o.str("title").ifBlank { url }, uploader, o.str("thumbnail").ifBlank { entries.firstOrNull()?.thumb ?: "" },
                0, site, emptyList(), 0, entries, false, url)
        }
        val bestByHeight = HashMap<Int, Long>()
        var audioBytes = 0L
        o.optJSONArray("formats")?.let { f ->
            for (i in 0 until f.length()) {
                val fo = f.optJSONObject(i) ?: continue
                val size = maxOf(fo.optLong("filesize"), fo.optLong("filesize_approx"))
                val h = fo.optInt("height", 0)
                if (h > 0 && fo.str("vcodec") != "none") bestByHeight[h] = maxOf(bestByHeight[h] ?: 0L, size)
                else if (fo.str("vcodec") == "none" && fo.str("acodec") != "none") audioBytes = maxOf(audioBytes, size)
            }
        }
        val opts = bestByHeight.entries.sortedByDescending { it.key }.map { VOpt(it.key, if (it.value > 0) it.value + audioBytes else 0L) }
        return Probe("video", o.str("title").ifBlank { url }, uploader, o.str("thumbnail"), o.optLong("duration"), site, opts, audioBytes,
            emptyList(), o.optBoolean("is_live"), o.str("webpage_url").ifBlank { url }, o.optLong("view_count"), o.str("id"), line)
    }

    private fun entryOf(e: JSONObject): Entry? {
        val id = e.str("id")
        var u = e.str("url").ifBlank { e.str("webpage_url") }
        if (!u.startsWith("http") && id.isNotBlank() && e.str("ie_key").lowercase().contains("youtube")) u = "https://www.youtube.com/watch?v=$id"
        if (!u.startsWith("http")) return null
        var th = e.str("thumbnail")
        if (th.isBlank()) e.optJSONArray("thumbnails")?.let { a -> if (a.length() > 0) th = a.optJSONObject(a.length() - 1)?.str("url") ?: "" }
        return Entry(id.ifBlank { u }, e.str("title").ifBlank { u }, u, e.optLong("duration"), th,
            e.str("uploader").ifBlank { e.str("channel") }, e.optLong("view_count"))
    }

    /** Search fallback via yt-dlp when NewPipe breaks: flat ytsearch/scsearch entries (no views, sparse metadata). */
    private fun ytSearch(ctx: Context, query: String, source: String, count: Int): List<Entry> {
        ensureYt(ctx)
        val prefix = if (source == "soundcloud") "scsearch" else "ytsearch"
        val r = YoutubeDLRequest("$prefix$count:$query")
            .addOption("--dump-json").addOption("--flat-playlist").addOption("--lazy-playlist").addOption("--no-warnings")
        ytCommon(r, ctx)
        val out = ytRead { YoutubeDL.getInstance().execute(r, null, null).out }
        return out.lineSequence().mapNotNull { l ->
            val t = l.trim()
            if (t.startsWith("{")) try { entryOf(JSONObject(t)) } catch (e: Exception) { null } else null
        }.distinctBy { it.url }.take(count).toList()
    }

    /** Pick preview URLs from a cached probe extraction: best h264 progressive <= height, else video + best audio. */
    private fun streamSetFromInfo(json: String, height: Int): StreamSet {
        val o = try { JSONObject(json) } catch (e: Exception) { return StreamSet("") }
        val fmts = o.optJSONArray("formats") ?: return StreamSet("")
        class F(val url: String, val h: Int, val v: String, val a: String)
        val list = ArrayList<F>()
        for (i in 0 until fmts.length()) {
            val f = fmts.optJSONObject(i) ?: continue
            val u = f.str("url")
            if (!u.startsWith("http")) continue
            val proto = f.str("protocol")
            if (proto.isNotBlank() && !proto.startsWith("http")) continue   // skip hls/dash manifests
            list.add(F(u, f.optInt("height", 0), f.str("vcodec"), f.str("acodec")))
        }
        if (list.isEmpty()) return StreamSet("")
        fun avc(v: String) = v.startsWith("avc1") || v.startsWith("avc3") || v == "h264"
        val prog = list.filter { it.v != "none" && it.a != "none" && it.h in 1..height }
        val bestProg = prog.filter { avc(it.v) }.maxByOrNull { it.h } ?: prog.maxByOrNull { it.h }
        if (bestProg != null) return StreamSet(bestProg.url)
        val vids = list.filter { it.v != "none" && it.h in 1..height }
        val bv = vids.filter { avc(it.v) }.maxByOrNull { it.h } ?: vids.maxByOrNull { it.h } ?: return StreamSet("")
        val auds = list.filter { it.v == "none" && it.a != "none" }
        val ba = auds.filter { it.a.startsWith("mp4a") || it.a == "aac" }.lastOrNull() ?: auds.lastOrNull()
        return StreamSet(bv.url, ba?.url ?: "")
    }

    /** Direct stream URL(s) for preview via yt-dlp (non-NewPipe sites only). */
    private fun ytStreamSet(ctx: Context, url: String, height: Int): StreamSet {
        ensureYt(ctx)
        val r = YoutubeDLRequest(url).addOption("--get-url").addOption("--no-playlist")
            .addOption("-f", "bv*[height<=$height][vcodec^=avc1]+ba[acodec^=mp4a]/b[height<=$height][vcodec^=avc1][acodec!=none]/bv*[height<=$height]+ba/b[height<=$height]/bv*+ba/b")
        ytCommon(r, ctx)
        val lines = ytRead { YoutubeDL.getInstance().execute(r, null, null).out }
            .lineSequence().map { it.trim() }.filter { it.startsWith("http") }.toList()
        return if (lines.isEmpty()) StreamSet("") else StreamSet(lines[0], lines.getOrElse(1) { "" })
    }

    private fun ytNameTemplate(): String = when (Store.settings.value.nameTemplate) {
        "title_uploader" -> "%(title).110B - %(uploader).35B.%(ext)s"
        "date_title" -> "%(upload_date)s - %(title).130B.%(ext)s"
        else -> "%(title).150B.%(ext)s"
    }

    private fun downloadYt(ctx: Context, t: Task, dir: File, onLine: (Float, Long, String) -> Unit) {
        ensureYt(ctx)
        dir.mkdirs()
        val cached = ytInfoCache[t.url]?.takeIf { System.currentTimeMillis() - it.at < INFO_TTL }
        try {
            downloadYtOnce(ctx, t, dir, onLine, cached?.json)
        } catch (e: RangeDl.Canceled) {
            throw e
        } catch (e: Throwable) {
            if (cached == null) throw e
            // the reused extraction failed (stale URLs etc.): retry once with a fresh extraction
            ytInfoCache.remove(t.url)
            downloadYtOnce(ctx, t, dir, onLine, null)
        }
    }

    private fun downloadYtOnce(ctx: Context, t: Task, dir: File, onLine: (Float, Long, String) -> Unit, infoJson: String?) {
        val s = Store.settings.value
        val r = YoutubeDLRequest(t.url)
        var infoFile: File? = null
        if (infoJson != null) {
            infoFile = File(dir, ".hdo-probe-" + t.id + ".info.json")
            infoFile.writeText(infoJson)
            r.addOption("--load-info-json", infoFile.absolutePath)
        }
        r.addOption("-o", File(dir, ytNameTemplate()).absolutePath)
        r.addOption("--no-playlist").addOption("--newline").addOption("--windows-filenames").addOption("--no-mtime")
        r.addOption("--write-info-json")
        r.addOption("-N", s.fragConcurrency.toString()).addOption("--retries", 10).addOption("--fragment-retries", 10)
        r.addOption("--http-chunk-size", "10M")
        if (t.kind == "audio") {
            r.addOption("-x").addOption("--audio-format", t.audioFormat)
            val lossy = t.audioFormat == "mp3" || t.audioFormat == "m4a" || t.audioFormat == "opus"
            r.addOption("--audio-quality", if (lossy && t.audioQuality != "best") t.audioQuality + "K" else "0")
        } else {
            val h = if (t.quality == "best") "" else "[height<=${t.quality}]"
            r.addOption("-f", t.format.ifBlank { "bv*[vcodec^=avc1]$h+ba[acodec^=mp4a]/bv*[vcodec^=avc1]$h+ba/bv*$h+ba/b$h/b" })
            r.addOption("--merge-output-format", t.container)
        }
        if (t.embed) r.addOption("--embed-metadata").addOption("--embed-chapters").addOption("--embed-thumbnail").addOption("--convert-thumbnails", "jpg")
        if (t.subtitles) {
            r.addOption("--write-subs").addOption("--write-auto-subs").addOption("--sub-langs", s.subLangs.replace(" ", ""))
            if (t.kind == "video" && (t.container == "mp4" || t.container == "mkv")) r.addOption("--embed-subs")
        }
        if (t.trimmed) r.addOption("--download-sections", "*${t.trimStart}-${t.trimEnd}").addOption("--force-keyframes-at-cuts")
        if (s.rateLimit.isNotBlank()) r.addOption("-r", s.rateLimit.trim())
        ytCommon(r, ctx)
        try {
            ytRead { YoutubeDL.getInstance().execute(r, t.id) { p, e, l -> onLine(p, e, l) } }
        } catch (e: YoutubeDL.CanceledException) {
            throw RangeDl.Canceled()
        } finally {
            infoFile?.delete()
        }
    }

    // -------------------------------------------------------------- download
    private fun nameTemplate(np: NpVideo): String = when (Store.settings.value.nameTemplate) {
        "title_uploader" -> sanitize(np.title, 110) + " - " + sanitize(np.uploader, 35)
        "date_title" -> np.uploadDate.ifBlank { "00000000" } + " - " + sanitize(np.title, 130)
        else -> sanitize(np.title, 150)
    }

    private fun capOf(t: Task): Int {
        Regex("height<=(\\d+)").find(t.format)?.let { return it.groupValues[1].toInt() }
        return if (t.quality == "best") Int.MAX_VALUE else t.quality.toIntOrNull() ?: Int.MAX_VALUE
    }

    /** "1M", "500K", "2000000" -> bytes/s (1024-based like yt-dlp); 0 = unlimited. */
    private fun parseRateLimit(s: String): Long {
        val t = s.trim().lowercase()
        if (t.isEmpty()) return 0
        val m = Regex("([\\d.]+)\\s*([kmgt]?)b?/?s?").find(t) ?: return 0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2]) { "k" -> 1024.0; "m" -> 1048576.0; "g" -> 1073741824.0; "t" -> 1099511627776.0; else -> 1.0 }
        return (v * mult).toLong()
    }

    private fun fmtB(v: Long): String = when {
        v >= 1L shl 30 -> String.format(Locale.US, "%.2fGiB", v / 1073741824.0)
        v >= 1L shl 20 -> String.format(Locale.US, "%.2fMiB", v / 1048576.0)
        v >= 1024 -> String.format(Locale.US, "%.1fKiB", v / 1024.0)
        else -> "${v}B"
    }

    private fun fmtClock(s: Long): String = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)

    fun download(ctx: Context, t: Task, dir: File, onLine: (Float, Long, String) -> Unit) {
        if (!isNpSite(t.url)) { downloadYt(ctx, t, dir, onLine); return }
        try {
            downloadNp(ctx, t, dir, onLine)
        } catch (e: RangeDl.Canceled) {
            throw e
        } catch (e: Throwable) {
            // NewPipe path failed mid-setup or mid-download: retry this task through yt-dlp (resumes what it can)
            logEvent(ctx, "download", "NewPipe download failed (" + e.javaClass.simpleName + ": " + (e.message ?: "").take(120) + ") -> yt-dlp fallback")
            try { downloadYt(ctx, t, dir, onLine) } catch (e2: Throwable) {
                if (e2 !is RangeDl.Canceled) logEvent(ctx, "download", "yt-dlp also failed: " + (e2.message ?: e2.javaClass.simpleName).replace("\n", " ").take(400))
                throw e2
            }
        }
    }

    private fun downloadNp(ctx: Context, t: Task, dir: File, onLine: (Float, Long, String) -> Unit) {
        dir.mkdirs()
        var np = try { Np.info(ctx, t.url) } catch (e: Exception) { throw mapErr(e) }
        if (np.live) throw IllegalStateException("live stream: download after it ends")
        val s = Store.settings.value
        val rate = parseRateLimit(s.rateLimit)
        val conns = s.fragConcurrency.coerceIn(1, 16)
        val base = nameTemplate(np)
        File(dir, "$base.info.json").writeText(JSONObject()
            .put("id", np.id).put("title", np.title).put("uploader", np.uploader).put("channel", np.uploader)
            .put("extractor_key", np.service).put("thumbnail", np.thumb).put("duration", np.duration)
            .put("view_count", np.views).put("webpage_url", np.url).put("upload_date", np.uploadDate).toString())

        /** Download one stream with resume; on a 403/expired URL re-resolve fresh ones and continue (partial data stays). */
        fun dl(url: String, out: File, resolve: (NpVideo) -> String?) {
            onLine(-1f, 0, "[download] Destination: ${out.absolutePath}")
            var u = url
            var attempt = 0
            while (true) {
                try {
                    RangeDl.download(t.id, u, out, conns, rate) { done, total, speed, eta ->
                        val pct = if (total > 0) (done * 100f / total).coerceIn(0f, 100f) else -1f
                        val line = if (total > 0)
                            "[download] " + String.format(Locale.US, "%.1f", pct) + "% of ~" + fmtB(total) +
                                " at " + fmtB(speed) + "/s ETA " + fmtClock(eta)
                        else "[download] " + fmtB(done) + " at " + fmtB(speed) + "/s"
                        onLine(pct, eta, line)
                    }
                    return
                } catch (e: RangeDl.Expired) {
                    if (++attempt > 2) throw Exception("url expired")
                    np = try { Np.info(ctx, t.url, fresh = true) } catch (x: Exception) { throw mapErr(x) }
                    u = resolve(np) ?: u
                }
            }
        }

        if (t.kind == "audio") {
            val a = Np.pickAudio(np, t.audioFormat)
            val rawExt = if (a.mp4) "m4a" else "webm"
            val raw = File(dir, "$base.raw.$rawExt.part")
            dl(a.url, raw) { Np.pickAudio(it, t.audioFormat).url }
            finishAudio(np, t, raw, File(dir, "$base.${t.audioFormat}"), a.mp4, onLine)
            raw.delete()
        } else {
            val cap = capOf(t)
            val (vs, au) = Np.pickVideo(np, cap, t.container == "webm")
            val vF = File(dir, "$base.fv.part")
            dl(vs.url, vF) { Np.pickVideo(it, cap, t.container == "webm").first.url }
            if (au == null) {
                val out = File(dir, "$base.${if (vs.mp4) "mp4" else "webm"}")
                vF.renameTo(out)
                if (t.trimmed) trim(out, t, onLine)
            } else {
                val aF = File(dir, "$base.fa.part")
                dl(au.url, aF) { Np.pickVideo(it, cap, t.container == "webm").second?.url }
                val ext = if (vs.mp4 && au.mp4) (if (t.container == "mkv") "mkv" else "mp4") else "webm"
                val out = File(dir, "$base.$ext")
                merge(np, t, vF, aF, out, dir, onLine)
                vF.delete(); aF.delete()
                if (t.trimmed) trim(out, t, onLine)
            }
        }
        if (t.subtitles) saveSubs(np, dir, base)
    }

    /** Mux video + audio with the bundled ffmpeg (stream copy: seconds even on slow phones). */
    private fun merge(np: NpVideo, t: Task, vF: File, aF: File, out: File, dir: File, onLine: (Float, Long, String) -> Unit) {
        onLine(0f, 0, "[Merger] Merging formats into \"${out.absolutePath}\"")
        val ext = out.extension.ifBlank { "mp4" }
        // always merge into a plain ASCII temp name, then rename: titles with Arabic/spaces/odd chars never reach ffmpeg's output path
        val tmp = File(dir, "merge.tmp.$ext")
        val muxer = when (ext) { "mkv" -> "matroska"; "webm" -> "webm"; else -> "mp4" }
        val cover = if (t.embed && np.thumb.isNotBlank() && ext == "mp4") fetchThumb(np.thumb, dir) else null
        fun attempt(withCover: Boolean, withMeta: Boolean, faststart: Boolean): Pair<Int, String> {
            tmp.delete()
            val a = ArrayList<String>()
            a += listOf("-y", "-i", vF.absolutePath, "-i", aF.absolutePath)
            if (withCover && cover != null) a += listOf("-i", cover.absolutePath, "-map", "0:v", "-map", "0:a", "-map", "2:v", "-disposition:v:1", "attached_pic")
            else a += listOf("-map", "0:v", "-map", "0:a")
            a += listOf("-c", "copy")
            if (faststart && ext == "mp4") a += listOf("-movflags", "+faststart")
            if (withMeta && t.embed) {
                a += listOf("-metadata", "title=${np.title.replace('\n', ' ')}", "-metadata", "artist=${np.uploader.replace('\n', ' ')}")
                if (np.uploadDate.isNotBlank()) a += listOf("-metadata", "date=${np.uploadDate}")
            }
            a += listOf("-f", muxer, tmp.absolutePath)
            return runFfmpeg(a)
        }
        // most complete first, then simpler variants; the full ffmpeg tail goes to the diagnostics log
        val steps = listOf(Triple(true, true, true), Triple(false, true, true), Triple(false, false, true), Triple(false, false, false))
        var lastCode = -1; var lastLog = ""
        for ((c, m, f) in steps) {
            if (c && cover == null) continue
            val (code, log) = attempt(c, m, f)
            lastCode = code; lastLog = log
            if (code == 0 && tmp.isFile && tmp.length() > 0L) {
                out.delete()
                if (tmp.renameTo(out)) { cover?.delete(); return }
            }
            logEvent(null, "merge", "attempt cover=$c meta=$m faststart=$f failed ($code): " + log.takeLast(300).replace("\n", " "))
        }
        cover?.delete(); tmp.delete()
        throw IllegalStateException("ffmpeg merge failed ($lastCode): ${lastLog.takeLast(200)}")
    }

    /** Convert the raw audio stream to the requested format; plain rename when no conversion is needed. */
    private fun finishAudio(np: NpVideo, t: Task, raw: File, out: File, srcMp4: Boolean, onLine: (Float, Long, String) -> Unit) {
        val noWork = (t.audioFormat == "m4a" && srcMp4 && !t.embed && !t.trimmed)
        if (noWork) { raw.renameTo(out); return }
        if ((t.audioFormat == "m4a" && srcMp4 && !t.embed).not()) onLine(0f, 0, "[ExtractAudio] Destination: ${out.absolutePath}")
        val dir = raw.parentFile!!
        val cover = if (t.embed && np.thumb.isNotBlank() && (t.audioFormat == "mp3" || t.audioFormat == "m4a")) fetchThumb(np.thumb, dir) else null
        val args = ArrayList<String>()
        args += listOf("-y", "-i", raw.absolutePath)
        if (cover != null) args += listOf("-i", cover.absolutePath)
        if (cover != null) args += listOf("-map", "0:a", "-map", "1:v", "-c:v", "copy", "-disposition:v:0", "attached_pic")
        else args += listOf("-map", "0:a")
        when (t.audioFormat) {
            "mp3" -> {
                args += listOf("-c:a", "libmp3lame")
                args += if (t.audioQuality == "best") listOf("-q:a", "0") else listOf("-b:a", "${t.audioQuality}k")
                if (cover != null) args += listOf("-id3v2_version", "3")
            }
            "m4a" -> args += listOf("-c:a", "copy")
            "opus" -> args += if (srcMp4) listOf("-c:a", "libopus", "-b:a", (if (t.audioQuality == "best") "160" else t.audioQuality) + "k")
                              else listOf("-c:a", "copy")
            "flac" -> args += listOf("-c:a", "flac")
            "wav" -> args += listOf("-c:a", "pcm_s16le")
            else -> args += listOf("-c:a", "copy")
        }
        if (t.embed) args += listOf("-metadata", "title=${np.title}", "-metadata", "artist=${np.uploader}")
        args += out.absolutePath
        val (code, _) = runFfmpeg(args)
        cover?.delete()
        if (code != 0 || !out.isFile || out.length() == 0L) {
            // ffmpeg unavailable/failed: deliver the raw stream under its real extension instead of failing the task
            val fb = File(dir, out.nameWithoutExtension + "." + (if (srcMp4) "m4a" else "webm"))
            fb.delete(); raw.renameTo(fb)
            return
        }
        if (t.trimmed) trim(out, t, onLine)
    }

    private fun trim(f: File, t: Task, onLine: (Float, Long, String) -> Unit) {
        onLine(0f, 0, "[ffmpeg] Trimming")
        val tmp = File(f.parentFile, f.nameWithoutExtension + ".cut.part")
        val muxer = when (f.extension.lowercase()) { "mp4", "m4a" -> "mp4"; "mkv" -> "matroska"; "webm" -> "webm"; "mp3" -> "mp3"; "opus" -> "ogg"; else -> f.extension }
        val (code, _) = runFfmpeg(listOf(
            "-y", "-ss", String.format(Locale.US, "%.3f", t.trimStart), "-to", String.format(Locale.US, "%.3f", t.trimEnd),
            "-i", f.absolutePath, "-c", "copy", "-f", muxer, tmp.absolutePath,
        ))
        if (code == 0 && tmp.isFile && tmp.length() > 0) { f.delete(); tmp.renameTo(f) } else tmp.delete()
    }

    private fun saveSubs(np: NpVideo, dir: File, base: String) {
        val want = Store.settings.value.subLangs.split(",").map { it.trim().take(2) }.filter { it.isNotEmpty() }
        np.subs.filter { !it.auto && (want.isEmpty() || it.lang.take(2) in want) }.take(4).forEach { sub ->
            try {
                val c = openConn(sub.url)
                if (c.responseCode == 200) {
                    val txt = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (txt.isNotBlank()) File(dir, "$base.${sub.lang}.${sub.ext}").writeText(txt)
                }
            } catch (e: Exception) { Engine.logEvent(null, "Engine:856", e.toString()) }
        }
    }

    private fun fetchThumb(url: String, dir: File): File? = try {
        val f = File(dir, "cover.img")
        val c = openConn(url)
        if (c.responseCode == 200) { c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }; f } else null
    } catch (e: Exception) { null }

    // ----------------------------------------------------------------- ffmpeg
    /** The ffmpeg AAR extracts its binary under noBackupFilesDir; find it wherever it landed. */
    private fun findFfmpeg(ctx: Context): File? {
        val nl = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (nl.exists()) return nl
        val q = ArrayDeque<File>(); q.add(ctx.noBackupFilesDir)
        while (q.isNotEmpty()) {
            val d = q.removeFirst()
            d.listFiles()?.forEach { f -> if (f.isDirectory) q.add(f) else if (f.name.contains("ffmpeg") && f.canExecute()) return f }
        }
        return null
    }

    private fun ffmpegLibPath(ctx: Context): String {
        val root = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val dirs = LinkedHashSet<String>()
        root.walkTopDown().maxDepth(4).filter { it.isDirectory && it.name == "lib" }.forEach { dirs.add(it.absolutePath) }
        dirs.add(ctx.applicationInfo.nativeLibraryDir)
        return dirs.joinToString(":")
    }

    fun runFfmpeg(args: List<String>): Pair<Int, String> {
        val app = appCtx
        if (ffmpegBin == null && app != null) {
            try { FFmpeg.getInstance().init(app) } catch (e: Throwable) { Engine.logEvent(null, "Engine:890", e.toString()) }
            ffmpegBin = findFfmpeg(app)
        }
        val bin = ffmpegBin ?: return -1 to "ffmpeg binary not found"
        return try {
            bin.setExecutable(true)
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = if (app != null) ffmpegLibPath(app) else bin.parentFile!!.absolutePath
            val p = pb.start()
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            p.waitFor() to out
        } catch (e: Exception) { -1 to (e.message ?: "exec failed") }
    }

    fun kill(id: String): Boolean {
        RangeDl.cancel(id)
        return try { if (ytState == "ready") YoutubeDL.getInstance().destroyProcessById(id) else true } catch (e: Exception) { true }
    }

    fun isPostLine(line: String): Boolean = POST.any { line.startsWith(it) }

    /** Live stage while the quality-list probe runs; a Strings key, "" when idle. */
    val probeStage = MutableStateFlow("")

    /** Maps a raw yt-dlp output line to a Strings stage key, or null when it is not a stage line. */
    fun stageKey(line: String): String? {
        val m = line.trim()
        return when {
            m.contains("Downloading player") -> "stage_player"
            m.contains("Downloading") && !m.startsWith("[download]") -> "stage_connect"
            m.startsWith("[info]") -> "stage_info"
            m.startsWith("[Merger]") || m.contains("Merging formats") -> "stage_merge"
            m.startsWith("[ExtractAudio]") -> "stage_audio"
            m.startsWith("[VideoConvertor]") || m.startsWith("[VideoRemuxer]") || m.startsWith("[Fixup") || m.startsWith("[ffmpeg]") -> "stage_convert"
            m.startsWith("[ThumbnailsConvertor]") || m.startsWith("[EmbedThumbnail]") -> "stage_thumb"
            m.startsWith("[Metadata]") || m.startsWith("[EmbedMetadata]") || m.startsWith("[EmbedSubtitle]") || m.startsWith("[ModifyChapters]") -> "stage_meta"
            else -> null
        }
    }
    private val POST = listOf("[Merger]", "[ExtractAudio]", "[VideoRemuxer]", "[EmbedThumbnail]", "[Metadata]", "[ThumbnailsConvertor]",
        "[SponsorBlock]", "[ModifyChapters]", "[EmbedSubtitle]", "[FixupM3u8]", "[FixupM4a]", "[ffmpeg]")

    /** "of ~52.31MiB" in a [download] progress line -> total bytes of the current stream, 0 when absent. */
    private val SIZE_RE = Regex("""of\s+~?\s*([\d.,]+)\s*([KMGT]?i?B)\b""")
    fun parseSizeTotal(line: String): Long {
        val m = SIZE_RE.find(line) ?: return 0
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2]) {
            "KiB" -> 1024.0; "MiB" -> 1024.0 * 1024; "GiB" -> 1024.0 * 1024 * 1024; "TiB" -> 1024.0 * 1024 * 1024 * 1024
            "KB" -> 1000.0; "MB" -> 1000.0 * 1000; "GB" -> 1000.0 * 1000 * 1000; "TB" -> 1000.0 * 1000 * 1000 * 1000
            else -> 1.0
        }
        return (v * mult).toLong()
    }

    private val MEDIA = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg", "flac", "wav", "aac", "mov")
    fun pickResult(dir: File): File? = dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in MEDIA && !Regex("\\.f\\d+$").containsMatchIn(it.nameWithoutExtension) }
        ?.maxByOrNull { it.length() }

    /** Metadata written next to the downloaded file (title, uploader, site, thumbnail...). */
    fun readInfo(dir: File): JSONObject? =
        dir.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "mp4", "mov" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus", "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"; "aac" -> "audio/aac"; else -> "application/octet-stream"
    }

    fun sanitize(name: String, limit: Int = 100): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim(' ', '.').take(limit).ifBlank { "file" }

    // ------------------------------------------------------- app update check
    data class Release(val tag: String, val pageUrl: String, val apkUrl: String)

    /** Latest GitHub release of Config.UPDATE_REPO, or null when none is published. */
    fun latestRelease(): Release? {
        try { return latestFromApi() } catch (e: Exception) { logEvent(null, "update-check", "api failed: " + e.javaClass.simpleName) }
        return latestFromRedirect()   // no API: the /releases/latest page redirects to the tag
    }

    private fun latestFromApi(): Release? {
        val c = (URL("https://api.github.com/repos/${Config.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000; setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (c.responseCode == 404) return null
        val o = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        var apk = ""
        o.optJSONArray("assets")?.let { a ->
            for (i in 0 until a.length()) {
                val n = a.getJSONObject(i).str("name")
                if (n.endsWith(".apk") && (apk.isEmpty() || n.contains("arm64"))) apk = a.getJSONObject(i).str("browser_download_url")
            }
        }
        return Release(o.str("tag_name"), o.str("html_url"), apk)
    }

    private fun latestFromRedirect(): Release? {
        val c = (URL("https://github.com/${Config.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000; instanceFollowRedirects = false
        }
        val loc = c.getHeaderField("Location") ?: return null
        val tag = loc.substringAfterLast("/tag/", "").ifBlank { return null }
        // asset name follows the release workflow: H-DO-arm64-<tag>.apk
        return Release(tag, loc, "https://github.com/${Config.UPDATE_REPO}/releases/download/$tag/H-DO-arm64-$tag.apk")
    }

    fun isNewer(tag: String, current: String): Boolean {
        fun nums(s: String) = s.trim().trimStart('v', 'V').split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = nums(tag); val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** User actions on queue items (pause / resume / cancel / retry / remove). */
object Ops {
    val pausing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun tmpDir(ctx: Context, id: String) = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "tmp/$id")

    fun pause(id: String) {
        val t = Store.task(id) ?: return
        when {
            t.isActive -> { pausing.add(id); Engine.kill(id) }
            t.status == St.QUEUED -> Store.updateTask(id, true) { it.copy(status = St.PAUSED) }
        }
    }

    fun cancel(ctx: Context, id: String) {
        val t = Store.task(id) ?: return
        if (t.isActive) { pausing.remove(id); Engine.kill(id) }
        else {
            Store.updateTask(id, true) { it.copy(status = St.CANCELED, finished = System.currentTimeMillis()) }
            tmpDir(ctx, id).deleteRecursively()
        }
    }

    fun resume(ctx: Context, id: String) {
        Store.updateTask(id, true) { it.copy(status = St.QUEUED, error = "", hint = "", scheduledAt = 0) }
        DownloadService.start(ctx)
    }

    fun retry(ctx: Context, id: String) {
        Store.updateTask(id, true) { it.copy(status = St.QUEUED, error = "", hint = "", scheduledAt = 0, retries = 0, percent = 0f) }
        DownloadService.start(ctx)
    }

    fun remove(ctx: Context, id: String, deleteFile: Boolean) {
        val t = Store.task(id) ?: return
        if (t.isActive) return
        if (deleteFile && t.uri.isNotBlank()) Storage.delete(ctx, t)
        tmpDir(ctx, id).deleteRecursively()
        Store.removeTask(id)
    }

    fun pauseAll() = Store.tasks.value.filter { it.isActive || it.status == St.QUEUED }.forEach { pause(it.id) }
    fun cancelAll(ctx: Context) = Store.tasks.value.filter { it.isActive || it.status == St.QUEUED }.forEach { cancel(ctx, it.id) }
}

/** Creates tasks from entries + a spec and starts the service. */
object Enqueue {
    fun tasks(entries: List<Entry>, spec: Spec, folder: String = "", expectSize: Long = 0): List<Task> = entries.map { e ->
        Task(
            id = newId(), url = e.url, title = e.title.ifBlank { e.url }, thumb = e.thumb, uploader = e.uploader, duration = e.duration,
            kind = spec.kind, quality = spec.quality, container = spec.container, audioFormat = spec.audioFormat, audioQuality = spec.audioQuality,
            format = spec.format, trimStart = spec.trimStart, trimEnd = spec.trimEnd, subtitles = spec.subtitles, embed = spec.embed,
            sponsor = spec.sponsor, scheduledAt = spec.scheduledAt, folder = folder, expectSize = expectSize,
        )
    }

    fun start(ctx: Context, entries: List<Entry>, spec: Spec, folder: String = "", expectSize: Long = 0, infoJson: String = ""): Int {
        val list = tasks(entries, spec, folder, expectSize)
        Store.addTasks(list)
        DownloadService.start(ctx)
        return list.size
    }
}
