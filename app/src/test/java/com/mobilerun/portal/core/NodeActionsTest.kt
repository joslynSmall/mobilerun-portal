package com.mobilerun.portal.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeActionsTest {

    @Test
    fun namesRoundTripForStandardActions() {
        assertEquals("SCROLL_BACKWARD", NodeActions.nameOf(0x2000))
        assertEquals(0x2000, NodeActions.idOf("SCROLL_BACKWARD"))
        assertEquals(0x10, NodeActions.idOf("click"))
        assertEquals(16908346, NodeActions.idOf("SCROLL_DOWN"))
        assertEquals("CUSTOM_123456", NodeActions.nameOf(123456))
        assertEquals(123456, NodeActions.idOf("CUSTOM_123456"))
        assertNull(NodeActions.idOf("NOT_AN_ACTION"))
    }

    @Test
    fun targetParsing() {
        val params = JSONObject()
            .put("window", 1)
            .put("path", JSONArray(listOf(0, 3, 2)))
            .put("bounds", JSONArray(listOf(1, 2, 3, 4)))
            .put("className", "android.widget.ScrollView")
        val t = NodeActions.Target.from(params)!!
        assertEquals(1, t.windowIndex)
        assertEquals(listOf(0, 3, 2), t.path)
        assertEquals("android.widget.ScrollView", t.className)
        assertNull(NodeActions.Target.from(JSONObject()))
        assertNull(NodeActions.Target.from(JSONObject().put("path", JSONArray(listOf(-1)))))
    }

    @Test
    fun performWalksPathChecksGuardsAndRecycles() {
        val target = node("android.widget.ScrollView", listOf(0x1000), performed = true)
        val middle = node("android.widget.FrameLayout", emptyList(), children = listOf(mockk(relaxed = true), target))
        val root = node("android.widget.FrameLayout", emptyList(), children = listOf(middle))

        val out = NodeActions.perform(
            root,
            NodeActions.Target(0, listOf(0, 1), listOf(0, 0, 100, 100), "android.widget.ScrollView"),
            0x1000,
            null,
        )

        assertEquals(NodeActions.Outcome.Done(true), out)
        verify { target.performAction(0x1000, null) }
        verify { root.recycle(); middle.recycle(); target.recycle() }
    }

    @Test
    fun performRefusesActionTheNodeDoesNotOffer() {
        val target = node("android.widget.ScrollView", listOf(0x1000), performed = true)
        val root = node("android.widget.FrameLayout", emptyList(), children = listOf(target))

        val out = NodeActions.perform(root, NodeActions.Target(0, listOf(0), null, null), 0x2000, null)

        assertTrue(out is NodeActions.Outcome.Failed)
        assertTrue((out as NodeActions.Outcome.Failed).reason.contains("SCROLL_FORWARD"))
        verify(exactly = 0) { target.performAction(any<Int>(), any()) }
    }

    @Test
    fun performDetectsChangedScreen() {
        val target = node("android.widget.Button", listOf(0x10), performed = true)
        val root = node("android.widget.FrameLayout", emptyList(), children = listOf(target))

        val wrongClass = NodeActions.perform(root, NodeActions.Target(0, listOf(0), null, "android.widget.ScrollView"), 0x10, null)
        val missing = NodeActions.perform(root, NodeActions.Target(0, listOf(5), null, null), 0x10, null)
        val moved = NodeActions.perform(root, NodeActions.Target(0, listOf(0), listOf(9, 9, 99, 99), null), 0x10, null)

        assertTrue(wrongClass is NodeActions.Outcome.Failed)
        assertTrue(missing is NodeActions.Outcome.Failed)
        assertTrue(moved is NodeActions.Outcome.Failed)
    }

    private fun node(
        cls: String,
        actions: List<Int>,
        performed: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.className } returns cls
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        every { n.getBoundsInScreen(any()) } answers {
            val r = firstArg<Rect>()
            r.left = 0; r.top = 0; r.right = 100; r.bottom = 100
        }
        every { n.actionList } returns actions.map { id ->
            mockk<AccessibilityNodeInfo.AccessibilityAction>().also { every { it.id } returns id }
        }
        every { n.performAction(any<Int>(), any()) } returns performed
        every { n.recycle() } just Runs
        return n
    }
}
