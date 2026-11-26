package com.hdo.app

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainVm(app: Application) : AndroidViewModel(app) {
    var query by mutableStateOf("")
    var source by mutableStateOf(Store.settings.value.searchSource)
    var results by mutableStateOf<List<Entry>>(emptyList())
    var playlist by mutableStateOf<Probe?>(null)          // set when the pasted link is a playlist / channel
    var searching by mutableStateOf(false)
    var error by mutableStateOf("")                         // friendly code, "none" or "x"
    var errorMsg by mutableStateOf("")
    var count by mutableIntStateOf(Store.settings.value.searchCount)
    var canMore by mutableStateOf(false)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var focused by mutableStateOf(false)
    var optionsFor by mutableStateOf<List<Entry>?>(null)
    var previewFor by mutableStateOf<Entry?>(null)
    var optionsFolder by mutableStateOf("")
    var clip by mutableStateOf<String?>(null)
    var requestTab by mutableStateOf<Int?>(null)
    var locked by mutableStateOf(false)
    var skipSplash by mutableStateOf(false)
    private var lastClip = ""
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var prefetchJob: Job? = null

    fun onQuery(text: String) {
        query = text
        suggestJob?.cancel()
        val q = text.trim()
        if (q.length < 2 || Engine.extractUrls(q).isNotEmpty()) { suggestions = emptyList(); return }
        suggestJob = viewModelScope.launch {
            delay(300)
            suggestions = withContext(Dispatchers.IO) { Engine.suggestions(q) }
        }
    }

    fun clear() {
        searchJob?.cancel(); suggestJob?.cancel(); prefetchJob?.cancel()
        query = ""; results = emptyList(); playlist = null; suggestions = emptyList(); error = ""; searching = false; canMore = false
    }

    fun search(more: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) return
        suggestions = emptyList(); suggestJob?.cancel(); searchJob?.cancel()
        error = ""; errorMsg = ""
        val urls = Engine.extractUrls(q)
        prefetchJob?.cancel()
        if (more) count = minOf(count + 10, 50) else { results = emptyList(); playlist = null; count = Store.settings.value.searchCount }
        searchJob = viewModelScope.launch {
            try {
                searching = true
                Engine.awaitReady(getApplication())
                if (urls.isNotEmpty()) {
                    val p = withContext(Dispatchers.IO) { Engine.probe(getApplication(), urls[0], true) }
                    canMore = false
                    if (p.type == "playlist") { playlist = p; results = p.entries } else { playlist = null; results = listOf(Engine.probeToEntry(p)) }
                } else {
                    Store.addHistory(q)
                    val list = withContext(Dispatchers.IO) { Engine.search(getApplication(), q, source, count) }
                    results = list
                    canMore = list.size >= count && count < 50
                    if (list.isEmpty()) error = "none"
                }
                // warm the preview cache for the top results so tapping a thumbnail is usually instant
                val top = results.take(2)
                prefetchJob = viewModelScope.launch {
                    top.forEach { e ->
                        try {
                            withContext(Dispatchers.IO) { Engine.fastStream(e.id.ifBlank { e.url }, e.url, 480) }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val m = e.message ?: ""
                error = Engine.friendlyError(m).ifBlank { "x" }
                errorMsg = m.takeLast(160)
            } catch (e: Throwable) {
                Engine.logEvent(getApplication(), "search-guard", e.javaClass.name + ": " + (e.message ?: ""))
                error = "x"
                errorMsg = (e.message ?: e.javaClass.simpleName).takeLast(160)
            } finally {
                searching = false
            }
        }
    }

    // ---- clipboard helpers
    private fun clipText(ctx: Context): String? {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
        val c = cm.primaryClip ?: return null
        if (c.itemCount == 0) return null
        return c.getItemAt(0).coerceToText(ctx)?.toString()
    }

    fun checkClip(ctx: Context) {
        if (query.isNotEmpty()) return
        val u = Engine.extractUrls(clipText(ctx) ?: return).firstOrNull() ?: return
        if (u != lastClip) { lastClip = u; clip = u }
    }

    fun useClip() { clip?.let { query = it }; clip = null; search() }

    /** Quick-settings tile: download the clipboard link(s) with the default options. */
    fun pasteGo(ctx: Context) {
        val urls = Engine.extractUrls(clipText(ctx) ?: "")
        if (urls.isEmpty()) { toast(ctx, Strings.get("no_clip", Store.settings.value.lang)); return }
        quick(ctx, urls.map { Entry(it, it, it) }, "")
    }

    fun quick(ctx: Context, entries: List<Entry>, folder: String) {
        val s0 = Store.settings.value
        val spec = Spec.defaults(s0).let { it.copy(quality = effDefQuality(it.quality, !Engine.unmetered(ctx), s0.dataSaver)) }
        val n = Enqueue.start(ctx, entries, spec, folder)
        toast(ctx, Strings.get("added", Store.settings.value.lang).replace("{n}", n.toString()))
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var stoppedAt = 0L
    private var started = false
    private var pasteGoPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Store.settings.value.appLock && AppLock.available(this)) vm.locked = true
        handleIntent(intent)
        if (Store.tasks.value.any { it.status == St.QUEUED && it.scheduledAt <= System.currentTimeMillis() }) DownloadService.start(this)
        setContent {
            HdoTheme {
                RootScreen(vm, onUnlock = { unlock() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val s = Store.settings.value
        if (s.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (s.appLock && AppLock.available(this) && (!started || System.currentTimeMillis() - stoppedAt > 30_000)) {
            vm.locked = true
            unlock()
        }
        started = true
        // refresh the downloads list against the files on the device (at most every 10 minutes)
        if (System.currentTimeMillis() - Store.lastSync.value > 10 * 60_000L) lifecycleScope.launch { LibrarySync.run(this@MainActivity) }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    private fun unlock() {
        AppLock.prompt(this, Strings.get("unlock", Store.settings.value.lang), onOk = { vm.locked = false }, onFail = { })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == ACTION_PASTE_GO) { pasteGoPending = true; vm.skipSplash = true; return }
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val txt = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            vm.query = Engine.extractUrls(txt).firstOrNull() ?: txt
            vm.skipSplash = true
            vm.requestTab = 0
            vm.search()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return                       // the clipboard is readable only while the window has focus
        if (pasteGoPending) { pasteGoPending = false; vm.pasteGo(this) } else vm.checkClip(this)
    }

    companion object {
        const val ACTION_PASTE_GO = "com.hdo.app.PASTE_GO"
    }
}

@Composable
fun RootScreen(vm: MainVm, onUnlock: () -> Unit) {
    val tasks by Store.tasks.collectAsState()
    val engine by Engine.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val busy = tasks.count { it.isActive || it.status == St.QUEUED }
    LaunchedEffect(vm.requestTab) { vm.requestTab?.let { tab = it; vm.requestTab = null } }

    if (vm.locked) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("H-DO", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Button(onClick = onUnlock) { Text(t("unlock")) }
            }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val items = listOf("home" to Icons.Filled.Home, "downloads" to Icons.Filled.Download,
                    "settings" to Icons.Filled.Settings, "about" to Icons.Filled.Info)
                items.forEachIndexed { i, (key, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = {
                            if (key == "downloads" && busy > 0) BadgedBox(badge = { Badge { Text("$busy") } }) { Icon(icon, null) }
                            else Icon(icon, null)
                        },
                        label = { Text(t(key), fontSize = 12.sp) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))) {
                    Image(painterResource(R.drawable.ic_logo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text("H-DO", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 10.dp).weight(1f))
            }
            if (engine == "init") Text(t("engine_init"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            if (engine.startsWith("error:")) Text(t("engine_err") + ": " + engine.removePrefix("error:").take(120), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(vm)
                    1 -> DownloadsScreen()
                    2 -> SettingsScreen()
                    else -> AboutScreen()
                }
            }
        }
    }
}
