package com.jev.probe.core.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural rules tested on a hand-built tree — no device, no Android classes. */
class WechatRulesTest {

    private class Fake(
        override val className: String? = null,
        override val viewId: String? = null,
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val isEditable: Boolean = false,
        override val isScrollable: Boolean = false,
        override val bounds: IntArray = intArrayOf(0, 0, 0, 0),
        private val children: List<WxNode> = emptyList()
    ) : WxNode {
        override val childCount: Int get() = children.size
        override fun child(i: Int): WxNode? = children.getOrNull(i)
    }

    private fun leaf(text: String, l: Int, t: Int, r: Int, b: Int) =
        Fake(text = text, bounds = intArrayOf(l, t, r, b))

    private fun described(desc: String, l: Int, t: Int, r: Int, b: Int) =
        Fake(className = "android.view.View", contentDescription = desc, bounds = intArrayOf(l, t, r, b))

    private val width = 1080
    private val height = 2400

    private fun chatScene(composerTop: Int = 2150): WxNode {
        val list = Fake(
            className = "androidx.recyclerview.widget.RecyclerView",
            isScrollable = true,
            bounds = intArrayOf(0, 200, 1080, 2100),
            children = listOf(
                leaf("20:15", 440, 220, 640, 260),                    // timestamp → dropped
                leaf("在吗", 130, 300, 400, 380),                     // other
                leaf("在的", 700, 420, 950, 500),                     // me
                leaf("你撤回了一条消息", 300, 540, 780, 580),          // system → dropped
                leaf("3″", 130, 620, 200, 660)                        // voice duration → dropped
            )
        )
        val title = leaf("张三", 460, 90, 620, 140)
        val composer = Fake(
            className = "android.widget.EditText",
            isEditable = true,
            bounds = intArrayOf(100, composerTop, 900, 2300)
        )
        return Fake(
            className = "android.widget.FrameLayout",
            bounds = intArrayOf(0, 0, 1080, 2400),
            children = listOf(title, list, composer)
        )
    }

    @Test
    fun `reads title sides and drops chrome`() {
        val snap = WechatRules.read(chatScene(), width, height)
        assertEquals("张三", snap?.title)
        assertEquals(2, snap?.messages?.size)
        assertEquals("other", snap!!.messages[0].side)
        assertEquals("在吗", snap.messages[0].text)
        assertEquals("me", snap.messages[1].side)
        assertEquals("在的", snap.messages[1].text)
    }

    @Test
    fun `no composer means not a chat window`() {
        val root = Fake(
            bounds = intArrayOf(0, 0, 1080, 2400),
            children = listOf(leaf("张三", 460, 90, 620, 140), leaf("在吗", 130, 300, 400, 380))
        )
        assertNull(WechatRules.read(root, width, height))
    }

    @Test
    fun `composer without bodies returns empty snapshot for OCR fallback`() {
        val root = Fake(
            bounds = intArrayOf(0, 0, 1080, 2400),
            children = listOf(
                Fake(isScrollable = true, bounds = intArrayOf(0, 200, 1080, 2100)),
                Fake(isEditable = true, bounds = intArrayOf(100, 2150, 900, 2300))
            )
        )
        val snap = WechatRules.read(root, width, height)
        assertTrue(snap != null && snap.messages.isEmpty())
    }

    @Test
    fun `body exposed only as contentDescription is still read`() {
        val list = Fake(
            isScrollable = true,
            bounds = intArrayOf(0, 200, 1080, 2100),
            children = listOf(described("在吗？", 130, 300, 400, 380))
        )
        val root = Fake(
            bounds = intArrayOf(0, 0, 1080, 2400),
            children = listOf(list, Fake(isEditable = true, bounds = intArrayOf(100, 2150, 900, 2300)))
        )
        val snap = WechatRules.read(root, width, height)
        assertEquals("在吗？", snap?.messages?.first()?.text)
    }

    @Test
    fun `long incoming message still counts as other`() {
        val list = Fake(
            isScrollable = true,
            bounds = intArrayOf(0, 200, 1080, 2100),
            children = listOf(leaf("这是一条很长很长的消息".repeat(4), 130, 300, 1000, 500))
        )
        val root = Fake(
            bounds = intArrayOf(0, 0, 1080, 2400),
            children = listOf(list, Fake(isEditable = true, bounds = intArrayOf(100, 2150, 900, 2300)))
        )
        val snap = WechatRules.read(root, width, height)
        assertEquals("other", snap?.messages?.first()?.side)
    }
}
