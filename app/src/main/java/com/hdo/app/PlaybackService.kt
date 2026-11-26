package com.hdo.app

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback: keeps a preview stream playing (screen off / app in background)
 * with media-notification and lock-screen controls via Media3.
 * Start with action "play" + extras: video, audio ("" when progressive), pos (ms), title. Action "stop" ends it.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "play" -> {
                val video = intent.getStringExtra("video") ?: return START_NOT_STICKY
                val audio = intent.getStringExtra("audio") ?: ""
                val pos = intent.getLongExtra("pos", 0L)
                val title = intent.getStringExtra("title") ?: "H-DO"
                val p = session?.player as? ExoPlayer
                    ?: ExoPlayer.Builder(this).build().also { session = MediaSession.Builder(this, it).build() }
                val ds = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                val f = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds)
                val meta = androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist("H-DO").build()
                val vs = f.createMediaSource(androidx.media3.common.MediaItem.Builder().setUri(video).setMediaMetadata(meta).build())
                p.setMediaSource(
                    if (audio.isBlank()) vs
                    else androidx.media3.exoplayer.source.MergingMediaSource(
                        vs, f.createMediaSource(androidx.media3.common.MediaItem.fromUri(audio)),
                    ),
                )
                p.prepare()
                if (pos > 0) p.seekTo(pos)
                p.play()
            }
            "stop" -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.player?.release()
        session?.release()
        session = null
        super.onDestroy()
    }
}
