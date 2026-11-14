package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Loads the bundled reference documents (assets/kb) once per process.
 *
 * The index is a plain JSON array written at build time:
 * `[{id,title,file,kind,tags:[…]}, …]`. A document that fails to load is skipped
 * — a missing asset must never take an analysis down with it.
 */
object KnowledgeAssets {

    private const val TAG = "JEVASSIST"
    private const val DIR = "kb"

    @Volatile
    private var cache: List<KnowledgeDoc>? = null

    fun load(context: Context): List<KnowledgeDoc> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = try {
                read(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "kb assets unavailable: ${e.javaClass.simpleName}")
                emptyList()
            }
            cache = loaded
            return loaded
        }
    }

    private fun read(context: Context): List<KnowledgeDoc> {
        val assets = context.assets
        val index = assets.open("$DIR/index.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(index)
        val docs = ArrayList<KnowledgeDoc>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val file = o.optString("file")
            if (file.isEmpty()) continue
            val tags = ArrayList<String>()
            o.optJSONArray("tags")?.let { t ->
                for (j in 0 until t.length()) tags.add(t.getString(j))
            }
            val text = try {
                assets.open("$DIR/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "kb doc missing: $file")
                continue
            }
            docs.add(
                KnowledgeDoc(
                    id = o.optString("id", file.removeSuffix(".md")),
                    title = o.optString("title", file.removeSuffix(".md")),
                    tags = tags,
                    text = text
                )
            )
        }
        Log.i(TAG, "kb loaded docs=${docs.size}")
        return docs
    }
}
