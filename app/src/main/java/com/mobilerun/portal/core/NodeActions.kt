package com.mobilerun.portal.core

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Native accessibility actions by name, and locating a node again to perform one.
 *
 * Names follow the AccessibilityNodeInfo / AccessibilityAction constants (CLICK,
 * SCROLL_BACKWARD, SET_TEXT, ...) so an agent can use the node's own `actionList`
 * from `state` directly. IDs are the framework values (android.R.id for the
 * AccessibilityAction ones), fixed since their API level.
 */
object NodeActions {
    /** Framework action id -> canonical name. */
    val NAMES: Map<Int, String> = linkedMapOf(
        0x00000001 to "FOCUS",
        0x00000002 to "CLEAR_FOCUS",
        0x00000004 to "SELECT",
        0x00000008 to "CLEAR_SELECTION",
        0x00000010 to "CLICK",
        0x00000020 to "LONG_CLICK",
        0x00000040 to "ACCESSIBILITY_FOCUS",
        0x00000080 to "CLEAR_ACCESSIBILITY_FOCUS",
        0x00000100 to "NEXT_AT_MOVEMENT_GRANULARITY",
        0x00000200 to "PREVIOUS_AT_MOVEMENT_GRANULARITY",
        0x00000400 to "NEXT_HTML_ELEMENT",
        0x00000800 to "PREVIOUS_HTML_ELEMENT",
        0x00001000 to "SCROLL_FORWARD",
        0x00002000 to "SCROLL_BACKWARD",
        0x00004000 to "COPY",
        0x00008000 to "PASTE",
        0x00010000 to "CUT",
        0x00020000 to "SET_SELECTION",
        0x00040000 to "EXPAND",
        0x00080000 to "COLLAPSE",
        0x00100000 to "DISMISS",
        0x00200000 to "SET_TEXT",
        16908342 to "SHOW_ON_SCREEN",
        16908343 to "SCROLL_TO_POSITION",
        16908344 to "SCROLL_UP",
        16908345 to "SCROLL_LEFT",
        16908346 to "SCROLL_DOWN",
        16908347 to "SCROLL_RIGHT",
        16908348 to "CONTEXT_CLICK",
        16908349 to "SET_PROGRESS",
        16908354 to "MOVE_WINDOW",
        16908356 to "SHOW_TOOLTIP",
        16908357 to "HIDE_TOOLTIP",
        16908358 to "PAGE_UP",
        16908359 to "PAGE_DOWN",
        16908360 to "PAGE_LEFT",
        16908361 to "PAGE_RIGHT",
        16908362 to "PRESS_AND_HOLD",
        16908372 to "IME_ENTER",
        16908373 to "DRAG_START",
        16908374 to "DRAG_DROP",
        16908375 to "DRAG_CANCEL",
        16908376 to "SHOW_TEXT_SUGGESTIONS",
        16908382 to "SCROLL_IN_DIRECTION",
    )

    private val IDS: Map<String, Int> = NAMES.entries.associate { (id, name) -> name to id }

    fun nameOf(id: Int): String = NAMES[id] ?: "CUSTOM_$id"

    /** Accepts a canonical name, CUSTOM_<id>, or a decimal id. */
    fun idOf(name: String): Int? {
        IDS[name.uppercase()]?.let { return it }
        val raw = name.removePrefix("CUSTOM_").removePrefix("custom_")
        return raw.toIntOrNull()
    }

    /** Builds the argument bundle for actions that take one (SET_TEXT, SET_PROGRESS, ...). */
    fun arguments(actionId: Int, args: JSONObject?): Bundle? {
        if (args == null) return null
        return when (actionId) {
            0x00200000 -> Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    args.optString("text", ""),
                )
            }
            0x00020000 -> Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, args.optInt("start", 0))
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, args.optInt("end", 0))
            }
            16908349 -> Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, args.optDouble("value", 0.0).toFloat())
            }
            16908343 -> Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, args.optInt("row", 0))
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, args.optInt("column", 0))
            }
            else -> null
        }
    }

    /**
     * Identifies the node an agent picked from a `state` tree.
     *
     * The tree is rebuilt for every `state` call, so there is no stable node handle.
     * `path` is the child-index path from the window root as returned by `state`
     * (indices into the raw child list, so it survives Portal's visibility filter);
     * `bounds` and `className` guard against the screen having changed in between.
     */
    data class Target(
        val windowIndex: Int,
        val path: List<Int>,
        val bounds: List<Int>?,  // [left, top, right, bottom]
        val className: String?,
    ) {
        companion object {
            fun from(params: JSONObject): Target? {
                val pathArr = params.optJSONArray("path") ?: return null
                val path = (0 until pathArr.length()).map { pathArr.optInt(it, -1) }
                if (path.any { it < 0 }) return null
                val b = params.optJSONArray("bounds")
                val bounds = if (b != null && b.length() == 4) {
                    listOf(b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3))
                } else {
                    null
                }
                val cls = params.optString("className", "").ifEmpty { null }
                return Target(params.optInt("window", 0), path, bounds, cls)
            }
        }
    }

    sealed class Outcome {
        data class Done(val performed: Boolean) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * Walk `target.path` from `root`, check it is still the same node and that it
     * offers `actionId`, then perform it. `root` and every node obtained here are
     * recycled before returning.
     */
    fun perform(
        root: AccessibilityNodeInfo,
        target: Target,
        actionId: Int,
        arguments: Bundle?,
    ): Outcome {
        val obtained = mutableListOf(root)
        try {
            var node = root
            for (index in target.path) {
                val count = try { node.childCount } catch (_: RuntimeException) { 0 }
                if (index >= count) return Outcome.Failed("node not found: the screen changed, observe again")
                val child = try { node.getChild(index) } catch (_: RuntimeException) { null }
                    ?: return Outcome.Failed("node not found: the screen changed, observe again")
                obtained += child
                node = child
            }
            target.className?.let { expected ->
                val actual = node.className?.toString().orEmpty()
                if (actual != expected) {
                    return Outcome.Failed("node changed ($actual, expected $expected): observe again")
                }
            }
            target.bounds?.let { expected ->
                val r = Rect()
                node.getBoundsInScreen(r)
                val actual = listOf(r.left, r.top, r.right, r.bottom)
                if (actual != expected) {
                    return Outcome.Failed("node moved (now $actual): observe again")
                }
            }
            val offered = node.actionList?.any { it.id == actionId } ?: false
            if (!offered) {
                val available = node.actionList?.joinToString(",") { nameOf(it.id) }.orEmpty()
                return Outcome.Failed("node does not offer ${nameOf(actionId)} (available: $available)")
            }
            return Outcome.Done(node.performAction(actionId, arguments))
        } finally {
            obtained.asReversed().forEach {
                try { it.recycle() } catch (_: RuntimeException) { }
            }
        }
    }
}
