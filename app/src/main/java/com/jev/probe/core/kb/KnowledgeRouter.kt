package com.jev.probe.core.kb

/** One bundled reference document (see assets/kb/index.json). */
data class KnowledgeDoc(
    val id: String,
    val title: String,
    val tags: List<String>,
    val text: String
)

/**
 * Picks at most a couple of bundled reference documents for one conversation.
 *
 * Deliberately cheap and explainable: a fixed keyword→topic table, documents
 * pre-tagged at build time, score = matched-topic weight, then the single most
 * on-topic `##` section of the winning documents is excerpted. No embeddings, no
 * network, no per-call cost, and the whole thing is pure Kotlin so it is unit
 * tested on the JVM.
 *
 * The documents come from 狗头军师 (MIT, see assets/kb/LICENSE-GOUTOUJUNSHI).
 */
object KnowledgeRouter {

    /** Never inject more than this many documents. */
    const val MAX_DOCS = 2

    /** Hard cap on one excerpt, in characters. */
    const val MAX_EXCERPT = 700

    private const val MIN_SCORE = 50

    private val TOPICS: Map<String, List<String>> = mapOf(
        "boundary" to listOf("边界", "拒绝", "不想", "不合适", "停止", "收线", "体面", "拉开距离"),
        "conflict" to listOf("冲突", "吵架", "生气", "冷战", "道歉", "误会", "修复", "和好"),
        "comfort" to listOf("安慰", "情绪", "难过", "委屈", "压力", "累", "崩溃", "情绪价值"),
        "invite" to listOf("约", "见面", "约会", "请客", "咖啡", "电影"),
        "reciprocity" to listOf("互惠", "投入", "失衡", "讨好", "主动", "有来有回", "敷衍", "已读不回"),
        "attachment" to listOf("依恋", "焦虑", "回避", "患得患失"),
        "mbti" to listOf("MBTI", "人格", "性格", "I人", "E人"),
        "pua" to listOf("PUA", "操控", "冷读", "推拉", "煤气灯", "打压", "套路"),
        "online" to listOf("网恋", "异地", "网友", "线上"),
        "intimacy" to listOf("亲密", "性", "同意", "身体", "接吻", "上床"),
        "marriage" to listOf("婚姻", "结婚", "家庭", "家务", "育儿", "彩礼", "父母"),
        "law" to listOf("法律", "家暴", "跟踪", "威胁", "诈骗", "报警", "安全"),
        "breakup" to listOf("分手", "复合", "背叛", "出轨", "失恋", "拉黑"),
        "comm" to listOf("怎么回", "接话", "夸", "幽默", "聊天", "话题", "尴尬", "冷场", "表达"),
        "diversity" to listOf("多元关系", "开放关系", "非单偶", "性取向", "出柜")
    )

    private val WEIGHTS: Map<String, Int> = mapOf(
        "boundary" to 120,
        "law" to 120,
        "reciprocity" to 75,
        "pua" to 75,
        "breakup" to 70,
        "conflict" to 70,
        "comm" to 50,
        "comfort" to 45,
        "invite" to 45,
        "intimacy" to 45,
        "marriage" to 45,
        "attachment" to 40,
        "mbti" to 30,
        "online" to 35,
        "diversity" to 30
    )

    /**
     * @param query conversation title + the last messages, already joined.
     * @return documents paired with the excerpt to inject; empty when nothing
     *         in the table matches (then the prompt simply carries no reference,
     *         which is the safe default).
     */
    fun route(query: String, docs: List<KnowledgeDoc>): List<Pair<KnowledgeDoc, String>> {
        if (query.isBlank() || docs.isEmpty()) return emptyList()
        val q = query.lowercase()
        val matched = LinkedHashMap<String, Int>()
        for ((topic, words) in TOPICS) {
            val n = words.count { q.contains(it.lowercase()) }
            if (n > 0) matched[topic] = n
        }
        if (matched.isEmpty()) return emptyList()

        val scored = docs.map { doc ->
            var score = 0
            for (tag in doc.tags) {
                val n = matched[tag] ?: continue
                score += n * (WEIGHTS[tag] ?: 40)
            }
            doc to score
        }.filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<KnowledgeDoc, Int>> { it.second }.thenBy { it.first.id })
            .take(MAX_DOCS)

        return scored.map { (doc, _) -> doc to excerpt(doc.text, matched.keys) }
    }

    /** The `##` section of [text] that hits the most matched topics, capped. */
    internal fun excerpt(text: String, topics: Set<String>): String {
        val words = topics.flatMap { TOPICS[it].orEmpty() }
        val sections = text.split(Regex("(?m)^## "))
        val best = sections.drop(1).maxByOrNull { part ->
            words.sumOf { w -> part.split(w).size - 1 }
        } ?: text
        val body = best.trim()
        return if (body.length <= MAX_EXCERPT) body else body.take(MAX_EXCERPT).trimEnd() + "…"
    }
}
