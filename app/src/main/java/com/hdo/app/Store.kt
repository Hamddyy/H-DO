package com.hdo.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** In-memory state (StateFlows) persisted to SharedPreferences as JSON. */
object Store {
    private var sp: SharedPreferences? = null
    val tasks = MutableStateFlow<List<Task>>(emptyList())
    val settings = MutableStateFlow(Settings())
    val history = MutableStateFlow<List<String>>(emptyList())
    val lastSync = MutableStateFlow(0L)
    val syncing = MutableStateFlow(false)

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("hdo", Context.MODE_PRIVATE)
        sp = p
        tasks.value = readTasks(p.getString("tasks", null)).map { if (it.isActive) it.copy(status = St.QUEUED) else it }
        history.value = runCatching {
            val a = JSONArray(p.getString("history", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        p.getString("settings", null)?.let { runCatching { settings.value = Settings.fromJson(JSONObject(it)) } }
        lastSync.value = p.getLong("lastSync", 0L)
    }

    private fun readTasks(s: String?): List<Task> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { runCatching { Task.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    fun task(id: String): Task? = tasks.value.firstOrNull { it.id == id }

    fun addTask(t: Task) { tasks.update { it + t }; saveTasks() }
    fun addTasks(list: List<Task>) { tasks.update { it + list }; saveTasks() }

    fun updateTask(id: String, persist: Boolean = false, f: (Task) -> Task) {
        tasks.update { list -> list.map { if (it.id == id) f(it) else it } }
        if (persist) saveTasks()
    }

    fun removeTask(id: String) { tasks.update { l -> l.filter { it.id != id } }; saveTasks() }

    fun replaceTasks(f: (List<Task>) -> List<Task>) { tasks.update(f); saveTasks() }

    @Synchronized
    fun saveTasks() {
        val keep = tasks.value.let { l -> if (l.size > 800) l.sortedByDescending { it.created }.take(800) else l }
        sp?.edit()?.putString("tasks", JSONArray(keep.map { it.toJson() }).toString())?.apply()
    }

    // ---- settings
    fun setSettings(f: (Settings) -> Settings) {
        settings.update(f)
        sp?.edit()?.putString("settings", settings.value.toJson().toString())?.apply()
    }

    // ---- search history
    fun addHistory(q: String) {
        if (!settings.value.saveHistory || q.isBlank()) return
        history.update { h -> (listOf(q.trim()) + h.filter { !it.equals(q.trim(), ignoreCase = true) }).take(30) }
        saveHistory()
    }
    fun removeHistory(q: String) { history.update { h -> h.filter { it != q } }; saveHistory() }
    fun clearHistory() { history.value = emptyList(); saveHistory() }
    private fun saveHistory() { sp?.edit()?.putString("history", JSONArray(history.value).toString())?.apply() }

    // ---- misc persisted longs
    fun getString(key: String): String? = sp?.getString(key, null)
    fun putString(key: String, v: String) { sp?.edit()?.putString(key, v)?.apply() }
    fun getLong(key: String): Long = sp?.getLong(key, 0L) ?: 0L
    fun putLong(key: String, v: Long) { sp?.edit()?.putLong(key, v)?.apply() }
    fun markSynced(now: Long) { lastSync.value = now; putLong("lastSync", now) }

    // ---- backup / restore (hidden in the UI unless Config.SHOW_HIDDEN_SETTINGS)
    fun exportBackup(): String = JSONObject().put("app", "H-DO").put("version", 4).put("settings", settings.value.toJson()).toString(2)

    fun restoreBackup(json: String) {
        val o = JSONObject(json)
        require(o.str("app") == "H-DO") { "not_hdo_backup" }
        o.optJSONObject("settings")?.let { s -> setSettings { Settings.fromJson(s) } }
    }
}
