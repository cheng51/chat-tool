package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs

/**
 * Turns "what is on screen right now" into the extra context one analysis gets:
 * which contact this is, their older history, and which knowledge notes apply.
 *
 * Deliberately dumb and cheap (v1.3 revision): exact name/alias matching, plain
 * substring note matching, a hard character budget. No scoring, no embeddings,
 * no auto-created contacts, no network.
 */
object ContextBuilder {

    /** Notes + history together may not exceed this many characters. */
    const val BUDGET_CHARS = 1500

    /** At most this many matched (non-always-on) notes. */
    const val MAX_HIT_NOTES = 5

    /** Only the last few messages are searched for note keywords. */
    private const val MATCH_WINDOW = 6

    /** Identical on-screen copies this short are not worth de-duplicating. */
    private const val DEDUPE_MIN_LEN = 4

    /**
     * @param app package name of the foreground chat app (may be blank).
     * @return context for this snapshot; every field may be empty, which is the
     *         normal state before the user has built a knowledge base.
     */
    fun build(context: Context, snapshot: ChatSnapshot, app: String, prefs: Prefs): ChatContext {
        val store = KbStore.get(context)
        val title = snapshot.title ?: ""

        // 1. Contact — matched only, never created here.
        val contact = store.findContact(title, app)

        // 2. History — recorded and injected only with the user's opt-in.
        val history = if (prefs.contextEnabled && contact != null)
            historyFor(store, contact, snapshot, app, prefs) else emptyList()

        // 3. Notes — always-on ones plus keyword hits.
        val enabled = store.notes().filter { it.enabled }
        val alwaysOn = enabled.filter { it.alwaysOn }
        val hits = matchNotes(enabled.filter { !it.alwaysOn }, snapshot)

        // 4. Budget: always-on notes are exempt; the rest share BUDGET_CHARS,
        //    dropping oldest history first, then whole notes (never half a note).
        val trimmedHistory = ArrayList(history)
        val trimmedHits = ArrayList(hits)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHistory.isNotEmpty())
            trimmedHistory.removeAt(0)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHits.isNotEmpty())
            trimmedHits.removeAt(trimmedHits.size - 1)

        // 5. Bundled reference excerpts (狗头军师, MIT). Kept on their own budget so
        //    a big reference hit can never evict the user's own notes.
        val knowledge = knowledgeNotes(context, snapshot)

        Log.d(TAG, "context: contact=${contact != null} notes=${alwaysOn.size + trimmedHits.size} " +
            "history=${trimmedHistory.size} kb=${knowledge.size}")
        return ChatContext(contact, trimmedHistory, alwaysOn + trimmedHits + knowledge)
    }

    /**
     * Record the visible messages, then read back the recent window minus the
     * copies already on screen (only for lines long enough that an exact repeat
     * is certainly the same message).
     */
    private fun historyFor(
        store: KbStore,
        contact: Contact,
        snapshot: ChatSnapshot,
        app: String,
        prefs: Prefs
    ): List<LogEntry> {
        val now = System.currentTimeMillis()
        store.appendLog(contact.id, snapshot.messages.map { LogEntry(it.side, it.text, now, app) })
        val n = prefs.contextHistoryCount.coerceIn(0, 100)
        if (n == 0) return emptyList()
        val onScreen = snapshot.messages
            .filter { it.text.length >= DEDUPE_MIN_LEN }
            .map { it.side + " " + it.text }
            .toHashSet()
        // Filter the widest window FIRST, then take n. The other order lets the
        // messages currently on screen eat into the quota — ask for 30 lines of
        // history, get 30 minus however many are already visible.
        return store.recentLog(contact.id, KbStore.MAX_LOG)
            .filter { (it.side + " " + it.text) !in onScreen }
            .takeLast(n)
    }

    /**
     * A note matches when any of its tags, or its title, appears in the
     * conversation title or in the last few messages. Newest notes win the
     * [MAX_HIT_NOTES] cap.
     */
    private fun matchNotes(candidates: List<Note>, snapshot: ChatSnapshot): List<Note> {
        if (candidates.isEmpty()) return emptyList()
        val haystack = buildString {
            append(KbStore.normalizeText(snapshot.title))
            snapshot.messages.takeLast(MATCH_WINDOW).forEach {
                append('\n').append(KbStore.normalizeText(it.text))
            }
        }
        if (haystack.isBlank()) return emptyList()
        return candidates
            .filter { note ->
                (note.tags + note.title).any { raw ->
                    val needle = KbStore.normalizeText(raw)
                    needle.isNotEmpty() && haystack.contains(needle)
                }
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_HIT_NOTES)
    }

    /**
     * Reference excerpts from the bundled 狗头军师 knowledge base. The query is the
     * conversation title plus the last few messages; [KnowledgeRouter] decides
     * whether anything is close enough to be worth sending. Empty is normal.
     */
    private fun knowledgeNotes(context: Context, snapshot: ChatSnapshot): List<Note> {
        val docs = KnowledgeAssets.load(context)
        if (docs.isEmpty()) return emptyList()
        val query = buildString {
            snapshot.title?.let { append(it) }
            snapshot.messages.takeLast(KB_MATCH_WINDOW).forEach { append('\n').append(it.text) }
        }
        val out = ArrayList<Note>(2)
        var budget = KB_BUDGET_CHARS
        for ((doc, excerpt) in KnowledgeRouter.route(query, docs)) {
            val cost = excerpt.length + doc.title.length + 8
            if (cost > budget) continue
            out.add(Note(id = "kb:${doc.id}", title = "参考·${doc.title}", content = excerpt))
            budget -= cost
        }
        return out
    }

    private fun cost(notes: List<Note>, history: List<LogEntry>): Int =
        notes.sumOf { it.title.length + it.content.length + 2 } +
            history.sumOf { it.text.length + 3 }

    /** Characters of bundled reference allowed in one prompt. */
    private const val KB_BUDGET_CHARS = 1200

    /** How many recent messages the reference lookup looks at. */
    private const val KB_MATCH_WINDOW = 8

    private const val TAG = "JEVASSIST"
}
