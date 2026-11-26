package com.hdo.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.DateFormat
import java.util.Date

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b >= 1_000_000_000 -> String.format("%.2f GB", b / 1e9)
    b >= 1_000_000 -> String.format("%.1f MB", b / 1e6)
    else -> String.format("%d KB", b / 1000)
}

fun fmtDur(s: Long): String {
    if (s <= 0) return ""
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%d:%02d", m, x)
}

fun fmtDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
fun hintText(code: String): String? = if (code.isBlank()) null else Strings.get("h_$code", LocalLang.current).takeIf { it != "h_$code" }

@Composable
fun Ring(percent: Float, modifier: Modifier = Modifier.size(54.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { 1f }, modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 5.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round,
        )
        CircularProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(),
            strokeWidth = 5.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round,
        )
        Text("${percent.toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun EmptyState(emoji: String, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 48.sp)
        Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 13.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
fun HdoDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 680.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

/** Media3 player surface with a fullscreen button; shared by the search-preview and the downloads player. */
@Composable
fun PlayerBox(player: androidx.media3.exoplayer.ExoPlayer, modifier: Modifier = Modifier) {
    var full by remember { mutableStateOf(false) }
    Box(modifier) {
        AndroidView(
            factory = { c ->
                androidx.media3.ui.PlayerView(c).apply {
                    useController = true
                    controllerShowTimeoutMs = 3000
                    this.player = player
                }
            },
            update = { it.player = if (full) null else player },
            modifier = Modifier.fillMaxSize(),
        )
        if (!full) {
            IconButton(
                onClick = { full = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(34.dp).background(Color(0x88000000), RoundedCornerShape(8.dp)),
            ) {
                Icon(Icons.Filled.Fullscreen, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (full) FullscreenPlayer(player) { full = false }
}

private fun findActivity(c: Context): android.app.Activity? {
    var ctx = c
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun FullscreenPlayer(player: androidx.media3.exoplayer.ExoPlayer, onClose: () -> Unit) {
    val act = findActivity(LocalContext.current)
    // 0 = rotate freely with the sensor, 1 = force landscape, 2 = force portrait
    var rotateMode by remember { mutableStateOf(0) }
    fun applyRotation(mode: Int) {
        act?.requestedOrientation = when (mode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
    DisposableEffect(Unit) {
        val prev = act?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyRotation(rotateMode)
        act?.window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            act?.requestedOrientation = prev
            act?.window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { c ->
                    androidx.media3.ui.PlayerView(c).apply {
                        useController = true
                        controllerShowTimeoutMs = 3000
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(34.dp).background(Color(0x88000000), RoundedCornerShape(8.dp)),
            ) {
                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = { rotateMode = (rotateMode + 1) % 3; applyRotation(rotateMode) },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).background(Color(0x88000000), RoundedCornerShape(8.dp)),
            ) {
                Icon(
                    when (rotateMode) {
                        1 -> Icons.Filled.ScreenLockLandscape
                        2 -> Icons.Filled.ScreenLockPortrait
                        else -> Icons.Filled.ScreenRotation
                    },
                    null, tint = Color.White, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- thumbnails
private val thumbCache = LruCache<String, ImageBitmap>(80)

fun loadRemote(url: String): ImageBitmap? = try {
    if (!url.startsWith("http")) null else {
        val c = URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
        c.getInputStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }
} catch (e: Exception) { null }

fun loadThumb(ctx: Context, t: Task): ImageBitmap? {
    Storage.thumbnail(ctx, t)?.let { return it.asImageBitmap() }
    return loadRemote(t.thumb)
}

@Composable
private fun ThumbBox(key: String, emoji: String, modifier: Modifier, load: () -> ImageBitmap?) {
    var bmp by remember(key) { mutableStateOf(thumbCache.get(key)) }
    LaunchedEffect(key) {
        if (bmp == null) {
            val b = withContext(Dispatchers.IO) { load() }
            if (b != null) { thumbCache.put(key, b); bmp = b }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(emoji, fontSize = 28.sp)
    }
}

@Composable
fun Thumb(task: Task, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    ThumbBox(task.uri.ifBlank { task.thumb }.ifBlank { task.id }, if (task.kind == "audio") "🎵" else "🎬", modifier) { loadThumb(ctx, task) }
}

@Composable
fun RemoteThumb(url: String, modifier: Modifier = Modifier) {
    ThumbBox(url.ifBlank { "none" }, "🎬", modifier) { loadRemote(url) }
}

/** Filled button that also reacts to a long press (Material Button has no long-press callback). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionButton(
    text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Download,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

/** The animated intro (res/raw/splash.gif) shown briefly when the app opens. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color(0xFF01000D))) {
        AndroidView(
            factory = { c ->
                android.widget.ImageView(c).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    try {
                        val d = android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(c.resources, R.raw.splash))
                        setImageDrawable(d)
                        if (d is android.graphics.drawable.AnimatedImageDrawable) {
                            d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                            d.start()
                        }
                    } catch (e: Exception) {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        setImageResource(R.drawable.ic_logo)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
