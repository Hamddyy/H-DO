package com.hdo.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resolved video info + stream URLs from the NewPipe Extractor (in-process, no Python). */
class VStream(val url: String, val height: Int, val fps: Int, val bitrate: Int, val videoOnly: Boolean, val mp4: Boolean, val codec: String, val itag: Int)
class AStream(val url: String, val bitrate: Int, val mp4: Boolean, val codec: String)
class SubStream(val url: String, val lang: String, val ext: String, val auto: Boolean)

class NpVideo(
    val id: String, val url: String, val title: String, val uploader: String, val thumb: String,
    val duration: Long, val views: Long, val uploadDate: String, val live: Boolean, val service: String,
    val videos: List<VStream>, val audios: List<AStream>, val subs: List<SubStream>, val hls: String,
)

object Np {
    const val VERSION = "v0.27.1"

    @Volatile private var inited = false
    @Volatile private var ctx: Context? = null

    // ---- proxy (shared by the extractor's OkHttp client and our range downloader)
    fun proxy(): Proxy? {
        var s = Store.settings.value.proxy.trim()
        if (s.isEmpty()) return null
        var type = Proxy.Type.HTTP
        if (s.startsWith("socks://")) { type = Proxy.Type.SOCKS; s = s.removePrefix("socks://") }
        else s = s.removePrefix("http://").removePrefix("https://")
        s = s.substringAfter('@').trimEnd('/')
        val port = s.substringAfter(':', "").toIntOrNull() ?: return null
        return try { Proxy(type, InetSocketAddress(s.substringBefore(':'), port)) } catch (e: Exception) { null }
    }

    private var clientCache: Pair<String, OkHttpClient>? = null

    @Synchronized
    private fun httpClient(): OkHttpClient {
        val key = Store.settings.value.proxy
        clientCache?.let { if (it.first == key) return it.second }
        val b = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
        proxy()?.let { b.proxy(it) }
        val c = b.build()
        clientCache = key to c
        return c
    }

    /** NewPipe -> OkHttp bridge; also injects the user's cookies.txt as a Cookie header for YouTube. */
    private class OkDl(private val client: OkHttpClient, private val cookies: (String) -> String) : Downloader() {
        override fun execute(request: Request): Response {
            val url = request.url()
            val b = okhttp3.Request.Builder().url(url)
            var hasCookie = false
            request.headers()?.forEach { (k, vs) ->
                if (k.equals("Content-Length", true) || k.equals("Content-Type", true)) return@forEach
                if (k.equals("Cookie", true)) hasCookie = true
                vs.forEach { b.addHeader(k, it) }
            }
            if (!hasCookie) {
                val ck = cookies(url)
                if (ck.isNotBlank()) b.header("Cookie", ck)
            }
            val ct = request.headers()?.entries?.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            val data = request.dataToSend()
            when (request.httpMethod().uppercase()) {
                "GET" -> b.get()
                "HEAD" -> b.head()
                else -> b.method(request.httpMethod().uppercase(), (data ?: ByteArray(0)).toRequestBody(ct?.toMediaTypeOrNull()))
            }
            client.newCall(b.build()).execute().use { res ->
                val body = res.body?.string()
                val headers = HashMap<String, List<String>>()
                res.headers.names().forEach { n -> headers[n] = res.headers(n) }
                if (res.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", url)
                return Response(res.code, res.message, headers, body, res.request.url.toString())
            }
        }
    }

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        ctx = c.applicationContext
        NewPipe.init(OkDl(httpClient()) { url -> Engine.cookieHeader(url) }, Localization("en"))
        inited = true
    }

    private fun ready() = inited

    // ------------------------------------------------------------- video info
    private val cache = ConcurrentHashMap<String, Pair<Long, NpVideo>>()
    private const val TTL = 30 * 60_000L

    fun bestThumb(list: List<Image>?): String =
        list?.maxByOrNull { if (it.height > 0) it.height else 0 }?.url ?: ""

    private fun vid(s: VideoStream): VStream = VStream(
        s.content, s.height, s.fps, s.bitrate, s.isVideoOnly,
        s.format == MediaFormat.MPEG_4, s.codec ?: "", try { s.itag } catch (e: Exception) { 0 },
    )

    /** Full video info (30 min cache; fresh=true forces a re-resolve, e.g. after URLs expire). */
    fun info(c: Context?, url: String, fresh: Boolean = false): NpVideo {
        val cc = c ?: ctx ?: throw IllegalStateException("engine not ready")
        init(cc)
        val key = Engine.ytId(url).ifBlank { url }
        if (!fresh) cache[key]?.let { if (System.currentTimeMillis() - it.first < TTL) return it.second }
        val si = StreamInfo.getInfo(url)
        val live = si.streamType == StreamType.LIVE_STREAM || si.streamType == StreamType.AUDIO_LIVE_STREAM
        val vids = si.videoOnlyStreams.map { vid(it) } + si.videoStreams.map { vid(it) }
        val auds = si.audioStreams.map { AStream(it.content, if (it.averageBitrate > 0) it.averageBitrate else it.bitrate, it.format == MediaFormat.M4A, it.codec ?: "") }
        val subs = try {
            si.subtitles.map { SubStream(it.content, it.languageTag ?: "", it.extension ?: "srt", it.isAutoGenerated) }
        } catch (e: Exception) { emptyList() }
        val ud = try { si.uploadDate?.instant?.toString()?.take(10)?.replace("-", "") ?: "" } catch (e: Exception) { "" }
        val v = NpVideo(
            id = Engine.ytId(url).ifBlank { si.id ?: url }, url = url, title = si.name ?: url,
            uploader = si.uploaderName ?: "", thumb = bestThumb(si.thumbnails),
            duration = maxOf(si.duration, 0), views = maxOf(si.viewCount, 0), uploadDate = ud, live = live,
            service = si.service?.serviceInfo?.name ?: "YouTube", videos = vids, audios = auds, subs = subs,
            hls = try { si.hlsUrl ?: "" } catch (e: Exception) { "" },
        )
        cache[key] = System.currentTimeMillis() to v
        return v
    }

    /** Preview pair: best mp4 video-only <= height + best m4a audio; falls back to a progressive stream. */
    fun streamSet(v: NpVideo, height: Int): Engine.StreamSet {
        fun bestV(l: List<VStream>) = l.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
        val vo = v.videos.filter { it.videoOnly }
        val vBest = bestV(vo.filter { it.mp4 && it.height in 1..height })
            ?: bestV(vo.filter { it.height in 1..height })
        val aBest = v.audios.filter { it.mp4 }.maxByOrNull { it.bitrate } ?: v.audios.maxByOrNull { it.bitrate }
        if (vBest != null && aBest != null) return Engine.StreamSet(vBest.url, aBest.url)
        val prog = bestV(v.videos.filter { !it.videoOnly && it.height in 1..height })
            ?: bestV(v.videos.filter { !it.videoOnly })
        return Engine.StreamSet(prog?.url ?: "")
    }

    /** Download pick: video-only (or progressive when that is all there is) + audio. */
    fun pickVideo(v: NpVideo, cap: Int, preferWebm: Boolean): Pair<VStream, AStream?> {
        fun best(l: List<VStream>) = l.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
        val pool = v.videos.filter { it.videoOnly }
        val ordered = if (preferWebm) pool.sortedByDescending { !it.mp4 } else pool.sortedByDescending { it.mp4 }
        val vBest = best(ordered.filter { it.height in 1..cap }) ?: best(ordered)
        val aBest = (if (preferWebm) v.audios.sortedByDescending { !it.mp4 } else v.audios.sortedByDescending { it.mp4 }).maxByOrNull { it.bitrate }
        if (vBest != null && aBest != null) return vBest to aBest
        val prog = best(v.videos.filter { !it.videoOnly && it.height in 1..cap }) ?: best(v.videos.filter { !it.videoOnly })
        return (prog ?: throw IllegalStateException("no video stream")) to null
    }

    fun pickAudio(v: NpVideo, fmt: String): AStream {
        val pref = if (fmt == "opus") v.audios.filter { !it.mp4 } else v.audios.filter { it.mp4 }
        return (pref.ifEmpty { v.audios }).maxByOrNull { it.bitrate } ?: throw IllegalStateException("no audio stream")
    }

    // ---------------------------------------------------------------- search
    fun search(c: Context, query: String, source: String, count: Int): List<Entry> {
        init(c)
        val (service, filters) = when (source) {
            "ytmusic" -> ServiceList.YouTube to listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
            "soundcloud" -> ServiceList.SoundCloud to emptyList()
            else -> ServiceList.YouTube to listOf(YoutubeSearchQueryHandlerFactory.VIDEOS)
        }
        val ex = service.getSearchExtractor(query, filters, "")
        ex.fetchPage()
        val out = ArrayList<Entry>()
        var page = ex.initialPage
        while (true) {
            for (it in page.items) {
                if (it !is StreamInfoItem) continue
                out += Entry(
                    id = Engine.ytId(it.url).ifBlank { it.url },
                    title = it.name ?: it.url, url = it.url,
                    duration = maxOf(it.duration, 0), thumb = bestThumb(it.thumbnails),
                    uploader = it.uploaderName ?: "", views = maxOf(it.viewCount, 0),
                )
                if (out.size >= count) break
            }
            if (out.size >= count || !page.hasNextPage()) break
            try { page = ex.getPage(page.nextPage) } catch (e: Exception) { break }
        }
        return out.distinctBy { it.url }.take(count)
    }

    // -------------------------------------------------------------- playlist
    fun playlist(c: Context, url: String, limit: Int): Probe {
        init(c)
        val pi = PlaylistInfo.getInfo(url)
        val items = pi.relatedItems.take(limit).map {
            Entry(
                id = Engine.ytId(it.url).ifBlank { it.url },
                title = it.name ?: it.url, url = it.url,
                duration = maxOf(it.duration, 0), thumb = bestThumb(it.thumbnails),
                uploader = it.uploaderName ?: "", views = maxOf(it.viewCount, 0),
            )
        }
        return Probe(
            "playlist", pi.name ?: url, pi.uploaderName ?: "",
            bestThumb(pi.thumbnails).ifBlank { items.firstOrNull()?.thumb ?: "" },
            0, pi.service?.serviceInfo?.name ?: "YouTube", emptyList(), 0, items, false, url,
        )
    }
}
