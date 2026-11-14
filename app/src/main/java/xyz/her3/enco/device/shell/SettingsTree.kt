package xyz.her3.enco.device.shell

import android.view.accessibility.AccessibilityNodeInfo
import xyz.her3.enco.core.guard.SettingsGuard

internal const val SETTINGS_PACKAGE = "com.android.settings"

internal class SettingsTree(
    private val root: AccessibilityNodeInfo,
) {
    data class Entry(
        val path: List<Int>,
        val label: String,
        val tap: Boolean,
        val forward: Boolean,
        val backward: Boolean,
    )

    private val items = mutableListOf<Entry>()
    private var visited = 0
    var blocked = false
        private set

    init {
        visit(root, emptyList(), null)
    }

    val entries: List<Entry> get() = items

    @Suppress("DEPRECATION")
    private fun visit(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        clickPath: List<Int>?,
    ) {
        if (!node.isVisibleToUser || ++visited > 450 || path.size > 40) return
        if (node.isPassword || (node.isEditable && SettingsGuard.requiresTakeover(node.hintText?.toString().orEmpty()))) {
            blocked = true
            return
        }
        val ownClick =
            if (node.isCheckable || !node.isEnabled) {
                null
            } else if (node.isClickable) {
                path
            } else {
                clickPath
            }
        val actions = node.actionList
        val forward = node.isScrollable && actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        val backward = node.isScrollable && actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        val label =
            (node.text ?: node.contentDescription)
                ?.toString()
                ?.trim()
                ?.take(100)
                .orEmpty()
        val safeLabel = label.takeIf { !node.isEditable && SettingsGuard.canShare(it) }.orEmpty()
        if (items.size < 72 && (forward || backward || safeLabel.isNotEmpty())) {
            val tap = ownClick != null && SettingsGuard.canTap(label)
            items.add(Entry(if (tap) checkNotNull(ownClick) else path, safeLabel, tap && !forward && !backward, forward, backward))
        }
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try {
                visit(child, path + index, ownClick)
            } finally {
                child.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun perform(
        entry: Entry,
        command: Int,
    ): Boolean {
        if (blocked) return false
        val action =
            when (command) {
                0 -> if (entry.tap) AccessibilityNodeInfo.ACTION_CLICK else return false
                1 -> if (entry.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else return false
                2 -> if (entry.backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else return false
                else -> return false
            }
        var node = root
        try {
            for (index in entry.path) {
                val child = node.getChild(index) ?: return false
                if (node !== root) node.recycle()
                node = child
            }
            if (command == 0 && (node.isCheckable || containsSwitch(node))) return false
            return node.isVisibleToUser && node.isEnabled && node.performAction(action)
        } finally {
            if (node !== root) node.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun containsSwitch(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
    ): Boolean {
        if (node.isCheckable || node.isEditable || depth > 12 || node.childCount > 40) return true
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try {
                if (containsSwitch(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}
