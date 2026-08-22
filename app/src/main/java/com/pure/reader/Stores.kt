package com.pure.reader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 书架与历史的轻量持久化（SharedPreferences + JSON），零外部依赖。 */
object Stores {

    private const val PREFS = "pure_reader_store"
    private const val KEY_SHELF = "shelf"
    private const val KEY_HISTORY = "history"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class ShelfItem(
        val title: String,
        val url: String,
        val source: String,
        val addedAt: Long,
        val chapterIndex: Int = 0,
        val chapterTitle: String = "",
        val progress: Int = 0   // 本章内阅读进度（0-100）
    )

    data class HistoryItem(val title: String, val url: String, val time: Long)

    fun addShelf(ctx: Context, title: String, url: String, source: String) {
        val list = shelves(ctx).toMutableList()
        list.removeAll { it.url == url }
        list.add(0, ShelfItem(title.ifBlank { url }, url, source, System.currentTimeMillis()))
        saveShelf(ctx, list)
    }

    fun updateProgress(ctx: Context, url: String, chapterIndex: Int, chapterTitle: String, progress: Int = 0) {
        val list = shelves(ctx).toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        if (idx < 0) return
        list[idx] = list[idx].copy(chapterIndex = chapterIndex, chapterTitle = chapterTitle, progress = progress.coerceIn(0, 100))
        saveShelf(ctx, list)
    }

    // 最近打开的书移到书架顶部
    fun touchShelf(ctx: Context, url: String) {
        val list = shelves(ctx).toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        if (idx <= 0) return
        val it = list.removeAt(idx)
        list.add(0, it)
        saveShelf(ctx, list)
    }

    fun removeShelf(ctx: Context, url: String) {
        val list = shelves(ctx).toMutableList()
        list.removeAll { it.url == url }
        saveShelf(ctx, list)
    }

    fun shelves(ctx: Context): MutableList<ShelfItem> {
        val raw = prefs(ctx).getString(KEY_SHELF, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<ShelfItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ShelfItem(
                    o.optString("title"),
                    o.optString("url"),
                    o.optString("source"),
                    o.optLong("addedAt"),
                    o.optInt("chapterIndex", 0),
                    o.optString("chapterTitle"),
                    o.optInt("progress", 0)
                )
            )
        }
        return out
    }

    private fun saveShelf(ctx: Context, list: List<ShelfItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("title", it.title)
                .put("url", it.url)
                .put("source", it.source)
                .put("addedAt", it.addedAt)
                .put("chapterIndex", it.chapterIndex)
                .put("chapterTitle", it.chapterTitle)
                .put("progress", it.progress))
        }
        prefs(ctx).edit().putString(KEY_SHELF, arr.toString()).apply()
    }

    fun addHistory(ctx: Context, title: String, url: String) {
        if (url.isBlank()) return
        val list = history(ctx).toMutableList()
        list.removeAll { it.url == url }
        list.add(0, HistoryItem(title.ifBlank { url }, url, System.currentTimeMillis()))
        if (list.size > 200) list.subList(200, list.size).clear()
        saveHistory(ctx, list)
    }

    fun history(ctx: Context): MutableList<HistoryItem> {
        val raw = prefs(ctx).getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<HistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(HistoryItem(o.optString("title"), o.optString("url"), o.optLong("time")))
        }
        return out
    }

    private fun saveHistory(ctx: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time)) }
        prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun removeHistory(ctx: Context, url: String) {
        val list = history(ctx).toMutableList()
        list.removeAll { it.url == url }
        saveHistory(ctx, list)
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HISTORY).apply()
    }
}
