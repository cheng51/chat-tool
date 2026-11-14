package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.wechat.WechatRules
import com.jev.probe.core.wechat.WxNode

/**
 * WeChat (com.tencent.mm). Resource ids are obfuscated and change every release,
 * so all the reading logic lives in [WechatRules] as structural rules over plain
 * node data — this class is only the thin Android bridge.
 *
 * The adapter reports exactly what the tree holds. WeChat normally exposes the
 * message bodies as text, so the OCR fallback (empty snapshot) should be rare;
 * it is still wired through the shared path for builds that draw their bubbles.
 */
class WeChatAdapter : ChatAppAdapter {

    override val pkg = WechatRules.PACKAGE

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val m = res.displayMetrics
        val snap = WechatRules.read(A11yWxNode(root), m.widthPixels, m.heightPixels) ?: return null
        return ChatSnapshot(snap.title, snap.messages.map { Msg(it.side, it.text) })
    }

    /** Live accessibility node behind the [WxNode] view the rules read. */
    private class A11yWxNode(private val node: AccessibilityNodeInfo) : WxNode {
        override val className: String? get() = node.className?.toString()
        override val viewId: String? get() = node.viewIdResourceName
        override val text: String? get() = node.text?.toString()
        override val isEditable: Boolean get() = node.isEditable
        override val isScrollable: Boolean get() = node.isScrollable
        override val bounds: IntArray
            get() = Rect().also { node.getBoundsInScreen(it) }
                .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        override val childCount: Int get() = node.childCount
        override fun child(i: Int): WxNode? = node.getChild(i)?.let { A11yWxNode(it) }
    }
}
