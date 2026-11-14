package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRouterTest {

    private fun doc(id: String, title: String, tags: List<String>, text: String) =
        KnowledgeDoc(id = id, title = title, tags = tags, text = text)

    private val docs = listOf(
        doc(
            "d-boundary", "边界与拒绝", listOf("boundary"),
            "# 边界与拒绝\n\n## 一、什么时候该停\n对方说不想的时候，停止推进，说清楚你的感受就收线。\n\n" +
                "## 二、怎么表达\n用一句话确认，不追问。"
        ),
        doc(
            "d-mbti", "MBTI 怎么用", listOf("mbti"),
            "# MBTI\n\n## 一、四个字母\nMBTI 是流行框架，不能当诊断。"
        ),
        doc(
            "d-comm", "接话技巧", listOf("comm"),
            "# 接话技巧\n\n## 一、怎么接\n接住对方最后一句里的具体名词。"
        )
    )

    @Test
    fun `picks the on-topic document and its best section`() {
        val hits = KnowledgeRouter.route("她说不想再联系我了，我该怎么回", docs)
        assertTrue(hits.size in 1..KnowledgeRouter.MAX_DOCS)
        assertEquals("d-boundary", hits[0].first.id)
        assertTrue(hits[0].second.contains("停止推进"))
    }

    @Test
    fun `returns nothing when the table has no hit`() {
        assertTrue(KnowledgeRouter.route("今天食堂的饭真难吃", docs).isEmpty())
    }

    @Test
    fun `caps the excerpt length`() {
        val long = doc("d-long", "长文", listOf("conflict"), "# 冲突\n\n## 一、" + "吵".repeat(3000))
        val hits = KnowledgeRouter.route("我们吵架了", listOf(long))
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0].second.length <= KnowledgeRouter.MAX_EXCERPT + 1)
    }

    @Test
    fun `ignores a document that scores below the floor`() {
        // mbti weight is 30, below MIN_SCORE (50): a lone MBTI mention must not
        // pull a whole reference document into the prompt.
        assertTrue(KnowledgeRouter.route("我最近在测 MBTI", docs).isEmpty())
    }
}
