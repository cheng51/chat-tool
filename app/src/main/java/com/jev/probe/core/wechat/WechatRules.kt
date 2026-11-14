package com.jev.probe.core.wechat

/**
 * WeChat (com.tencent.mm) reading rules — pure Kotlin, no Android types, so the
 * whole thing is unit-tested on the JVM with hand-built node trees.
 *
 * WeChat is the hardest of the supported apps: every resource id is obfuscated
 * and changes between releases, so unlike QQ nothing here may key on view ids.
 * What IS stable across versions:
 *
 * - the message list is one scrollable container;
 * - each message body is a leaf node carrying `text` (TextView, sometimes a
 *   Compose-ish View with text);
 * - the composer at the bottom is the only *editable* node;
 * - incoming bubbles hug the left edge of the list, mine hug the right edge.
 *
 * So the rules are purely structural: find the composer (proves "we are in a
 * chat"), find the list, collect leaf texts inside it, drop chrome (timestamps,
 * centered system notices, pure durations), and decide the side by which edge
 * the text hugs.
 *
 * Known limitations, deliberately accepted for v0.1:
 * - A group member's nickname is a separate short text node above the bubble and
 *   cannot be told apart from a very short message without text styling, so it
 *   may show up as an extra "other" line.
 * - A quoted reply puts the quoted text in its own node, which will be read as a
 *   separate message.
 * Both only over-supply context; they never invent a sender.
 */
internal interface WxNode {
    val className: String?
    val viewId: String?
    val text: String?
    val contentDescription: String?
    val isEditable: Boolean
    val isScrollable: Boolean
    /** left, top, right, bottom in screen coordinates. */
    val bounds: IntArray
    val childCount: Int
    fun child(i: Int): WxNode?
}

internal data class WxMessage(val side: String, val text: String)
internal data class WxSnapshot(val title: String?, val messages: List<WxMessage>)

internal object WechatRules {

    const val PACKAGE = "com.tencent.mm"

    /** "20:15", "昨天", "星期三", "2026年9月1日", "9月1日 下午3:20" … */
    private val TIMESTAMP = Regex(
        """^(\d{1,2}[:：]\d{2}|昨天|前天|今天|星期[一二三四五六日天]|周[一二三四五六日天]|""" +
            """\d{4}年\d{1,2}月\d{1,2}日.*|\d{1,2}月\d{1,2}日.*|[上下]午\s*\d{1,2}[:：]\d{2})$"""
    )

    /** Short service lines WeChat prints inside the list. */
    private val SYSTEM_LINES = setOf(
        "以下为新消息", "查看更多消息", "回到顶部", "消息已发出", "对方正在输入",
        "该消息已过期", "你已添加了对方为朋友", "你已添加了对方，现在可以开始聊天了"
    )

    /** Fragments that mean the node is a "recalled / notice" row, never a message. */
    private val SYSTEM_FRAGMENTS = listOf("撤回了一条消息", "已读", "对方开启了朋友验证")

    /** Pure durations come from the voice-message bubble ("3″"). */
    private val DURATION = Regex("""^\d{1,3}(″|''|秒)?$""")

    /**
     * @return null when this window is not a WeChat chat (list, contacts,
     *         settings…). A snapshot with no messages means "chat window, but the
     *         tree carried no readable body" — the caller's cue for the OCR
     *         fallback.
     */
    /** Some WeChat builds expose a bubble body as text, others as contentDescription. */
    private fun bodyOf(node: WxNode): String? {
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t
        val d = node.contentDescription?.toString()?.trim()
        return if (!d.isNullOrEmpty()) d else null
    }

    /** True when no child carries its own body (i.e. this node is the message itself). */
    private fun hasNoTextChild(node: WxNode): Boolean {
        for (i in 0 until node.childCount) {
            val c = node.child(i) ?: continue
            if (!bodyOf(c).isNullOrEmpty()) return false
        }
        return true
    }

    fun read(root: WxNode, width: Int, height: Int): WxSnapshot? {
        if (width <= 0 || height <= 0) return null

        val nodes = ArrayList<WxNode>(1024)
        val stack = ArrayDeque<WxNode>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 8000) {
            guard++
            val node = stack.removeLast()
            nodes.add(node)
            for (i in node.childCount - 1 downTo 0) node.child(i)?.let { stack.addLast(it) }
        }

        // 1. Composer: the only editable node, and it must sit in the lower half.
        //    Its absence is what "not a chat window" means for WeChat.
        val composer = nodes.firstOrNull {
            it.isEditable && it.bounds[1] > (height * 0.5).toInt()
        } ?: return null

        // 2. Message list: the scrollable container with the largest area above
        //    the composer. Falls back to a band between the action bar and the
        //    composer when a build does not expose one.
        val listTop = (height * 0.08).toInt()
        val listBottom = composer.bounds[1]
        val list = nodes
            .filter { it.isScrollable }
            .map { it.bounds }
            .filter { it[1] >= listTop && it[3] <= listBottom + 8 && it[3] - it[1] > (height * 0.2).toInt() }
            .maxByOrNull { (it[2] - it[0]).toLong() * (it[3] - it[1]) }

        val bandTop = list?.get(1) ?: listTop
        val bandBottom = list?.get(3) ?: listBottom
        val bandLeft = list?.get(0) ?: 0
        val bandRight = list?.get(2) ?: width

        // 3. Title: topmost short, roughly centered text above the list. WeChat's
        //    title is the only centered text up there; timestamps are filtered.
        val title = nodes
            .asSequence()
            .filter { it.childCount == 0 }
            .mapNotNull { w ->
                val t = bodyOf(w)
                if (t.isNullOrEmpty() || t.length > 24 || TIMESTAMP.matches(t)) return@mapNotNull null
                val b = w.bounds
                val cx = (b[0] + b[2]) / 2
                if (b[3] > bandTop || b[1] < 0) return@mapNotNull null
                if (cx < (width * 0.25).toInt() || cx > (width * 0.75).toInt()) return@mapNotNull null
                t to b[1]
            }
            .minByOrNull { it.second }
            ?.first

        // 4. Bodies. Leaf text nodes inside the list band only.
        val rows = ArrayList<Row>(64)
        for (node in nodes) {
            if (node.isEditable) continue
            val cls = node.className.orEmpty()
            if (cls.endsWith("EditText") || cls.endsWith("Button") || cls.endsWith("ImageView")) continue
            if (!hasNoTextChild(node)) continue
            val raw = bodyOf(node) ?: continue
            if (TIMESTAMP.matches(raw) || DURATION.matches(raw) ||
                raw in SYSTEM_LINES || SYSTEM_FRAGMENTS.any { raw.contains(it) }
            ) continue
            val b = node.bounds
            if (b[3] <= bandTop || b[1] >= bandBottom) continue
            if (b[2] <= b[0] || b[3] <= b[1]) continue
            // A centered short line inside the list is a system notice, not a bubble.
            val cx = (b[0] + b[2]) / 2
            if (raw.length <= 30 && kotlin.math.abs(cx - width / 2) < (width * 0.06).toInt()) continue
            rows.add(Row(b[0], b[1], b[2], b[3], raw))
        }
        if (rows.isEmpty()) return WxSnapshot(title, emptyList())

        // 5. Side: compare which list edge the text hugs. Same avatar-column idea
        //    as QQ, expressed against the list's own edges.
        val avatarInset = ((bandRight - bandLeft) * 0.13).toInt()
        val leftEdge = bandLeft + avatarInset
        val rightEdge = bandRight - avatarInset
        val messages = rows
            .sortedWith(compareBy({ it.top }, { it.left }))
            .map { r ->
                val dl = kotlin.math.abs(r.left - leftEdge)
                val dr = kotlin.math.abs(rightEdge - r.right)
                WxMessage(if (dr < dl) "me" else "other", r.text)
            }
            // Same text at nearly the same y is one node seen twice (container +
            // child), not two messages.
            .fold(ArrayList<WxMessage>()) { acc, m ->
                val last = acc.lastOrNull()
                if (last != null && last.text == m.text && last.side == m.side) acc else { acc.add(m); acc }
            }

        return WxSnapshot(title, messages)
    }

    private data class Row(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String)
}
