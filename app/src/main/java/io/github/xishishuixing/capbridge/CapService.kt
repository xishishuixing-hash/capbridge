package io.github.xishishuixing.capbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only channel that needs no ADB and no root: an accessibility service.
 * It can read the current window tree and inject taps, which is the part of
 * "root-like" behaviour a normal app can legitimately reach.
 */
class CapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CapService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Snapshot the active window as JSON, bounded by depth and node count. */
    fun screen(maxDepth: Int = 6, maxNodes: Int = 400): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "no active window")
        val counter = intArrayOf(0)
        val tree = walk(root, 0, maxDepth, maxNodes, counter)
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString() ?: "")
            .put("nodes", counter[0])
            .put("tree", tree)
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int, maxNodes: Int, counter: IntArray): JSONObject {
        val out = JSONObject()
        if (node == null) return out
        counter[0] += 1
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        out.put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
        out.put("text", node.text?.toString() ?: "")
        out.put("desc", node.contentDescription?.toString() ?: "")
        out.put("id", node.viewIdResourceName ?: "")
        out.put("b", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        out.put("clickable", node.isClickable)
        out.put("editable", node.isEditable)
        out.put("scrollable", node.isScrollable)
        if (depth < maxDepth && counter[0] < maxNodes) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (counter[0] >= maxNodes) break
                children.put(walk(node.getChild(i), depth + 1, maxDepth, maxNodes, counter))
            }
            if (children.length() > 0) out.put("children", children)
        }
        return out
    }

    /** Tap at screen coordinates. Works on any window the service can see. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 3000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Put text into whatever field currently holds input focus. */
    fun typeText(text: String): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "no active window")
        val target = findEditable(root)
            ?: return JSONObject().put("ok", false).put("error", "no editable node has input focus")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return JSONObject().put("ok", ok)
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val found = findEditable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }
}
