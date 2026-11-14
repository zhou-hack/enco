package xyz.her3.enco.device.shell

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

@Suppress("DEPRECATION")
internal class GmailInboxReader(
    private val automation: UiAutomation,
) {
    fun read(count: Int): Array<String>? {
        require(count in 1..20)
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        val opened =
            withGmailRoot { root ->
                root.findAccessibilityNodeInfosByViewId(id("toolbar_wrapped_around_search_bar")).useNodes { bars ->
                    bars.singleOrNull()?.clickMenu() == true
                }
            } ?: false
        if (!opened) return null
        SystemClock.sleep(400)
        val inboxSelected =
            withGmailRoot { root ->
                root.findAccessibilityNodeInfosByViewId(id("hub_drawer_label_title")).useNodes { labels ->
                    val inbox = labels.firstOrNull { it.text?.toString() in inboxLabels } ?: return@useNodes false
                    val parent = inbox.parent ?: return@useNodes false
                    try {
                        parent.isVisibleToUser && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        parent.recycle()
                    }
                }
            } ?: false
        if (!inboxSelected) return null
        SystemClock.sleep(550)
        val rows = LinkedHashSet<List<String>>()
        repeat(4) {
            val before = rows.size
            val more =
                withGmailRoot { root ->
                    root.findAccessibilityNodeInfosByViewId(id("thread_list_view")).useNodes { lists ->
                        val list = lists.singleOrNull() ?: return@useNodes null
                        list.findAccessibilityNodeInfosByViewId(id("viewified_conversation_item_view")).useNodes { items ->
                            for (row in items) {
                                val sender = row.field("senders")
                                val subject = row.field("subject")
                                val date = row.field("date")
                                if (sender.isNotBlank() && date.isNotBlank()) rows.add(listOf(sender, subject, date))
                                if (rows.size == count) break
                            }
                        }
                        rows.size < count && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                } ?: return null
            if (!more || (it > 0 && rows.size == before)) return rows.flatten().toTypedArray()
            SystemClock.sleep(400)
        }
        return rows.flatten().toTypedArray()
    }

    private fun <T> withGmailRoot(action: (AccessibilityNodeInfo) -> T): T? {
        val deadline = SystemClock.uptimeMillis() + 650
        var root = automation.rootInActiveWindow
        while (root == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(30)
            root = automation.rootInActiveWindow
        }
        if (root == null) return null
        return try {
            if (root.packageName?.toString() == PACKAGE) action(root) else null
        } finally {
            root.recycle()
        }
    }

    private fun AccessibilityNodeInfo.clickMenu(): Boolean {
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            try {
                if (child.isVisibleToUser && child.isClickable && child.className?.toString() == "android.widget.ImageButton") {
                    return child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun AccessibilityNodeInfo.field(name: String): String =
        findAccessibilityNodeInfosByViewId(id(name)).useNodes { nodes ->
            nodes
                .firstOrNull()
                ?.text
                ?.toString()
                .orEmpty()
                .replace(Regex("[\\r\\n]+"), " ")
                .take(200)
        }

    private inline fun <T> List<AccessibilityNodeInfo>.useNodes(action: (List<AccessibilityNodeInfo>) -> T): T =
        try {
            action(this)
        } finally {
            forEach(AccessibilityNodeInfo::recycle)
        }

    private fun id(name: String) = "$PACKAGE:id/$name"

    companion object {
        const val PACKAGE = "com.google.android.gm"
        private val inboxLabels = setOf("Inbox", "收件箱", "收件匣", "Primary", "主要", "主收件箱")
    }
}
