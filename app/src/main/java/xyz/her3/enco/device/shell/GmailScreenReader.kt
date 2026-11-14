package xyz.her3.enco.device.shell

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import xyz.her3.enco.core.agent.GmailAction
import xyz.her3.enco.core.agent.GmailControl
import xyz.her3.enco.core.agent.GmailScreen

@Suppress("DEPRECATION")
internal class GmailScreenReader(
    root: AccessibilityNodeInfo,
    frameId: Long,
) {
    private val controls = mutableListOf<Entry>()
    private val text = linkedSetOf<String>()
    private var bodyRead = false
    private var rowsRead = false
    private var bodyLength = 0
    private var composing = false
    private var visited = 0
    val screen: GmailScreen

    init {
        visit(root, emptyList(), inBody = false, inSort = false, inToolbar = false)
        screen =
            GmailScreen(
                frameId,
                if (composing) {
                    emptyList()
                } else {
                    controls.mapIndexed { index, entry ->
                        GmailControl(index + 1, entry.label, entry.action)
                    }
                },
                text.toList(),
                hasMessageBody = bodyRead,
                hasMailResults = rowsRead,
            )
    }

    fun entry(id: Int): Entry? = if (composing || id <= 0) null else controls.getOrNull(id - 1)

    private fun visit(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        inBody: Boolean,
        inSort: Boolean,
        inToolbar: Boolean,
    ) {
        if (path.size > 40 || visited++ >= 1000 || node.isPassword) return
        val id = node.viewIdResourceName.orEmpty().removePrefix("$PACKAGE:id/")
        val body = inBody || id == "conversation_webview" || node.className == "android.webkit.WebView"
        val sort = inSort || id == "compose_sort_header"
        val toolbar = inToolbar || id in setOf("open_search_view_toolbar", "conversation_toolbar", "toolbar")
        if (id in setOf("compose_body", "compose_area", "send")) composing = true
        if (node.isVisibleToUser) {
            val value =
                node.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val description =
                node.contentDescription
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (id == "viewified_conversation_item_view") {
                val row = listOf("senders", "subject", "date").map { field(node, it) }
                if (row[0].isNotBlank() && row[1].isNotBlank()) {
                    val label = "会话：${redact(row[0])} · ${redact(row[1])} · ${redact(row[2])}"
                    text += label
                    rowsRead = true
                    val parent = node.parent
                    try {
                        if (parent?.isClickable == true && path.isNotEmpty()) {
                            add(parent, path.dropLast(1), label, GmailAction.OPEN_MESSAGE)
                        }
                    } finally {
                        parent?.recycle()
                    }
                }
                return
            }
            when {
                id == "open_search_view_edit_text" && node.isEditable && node.isFocused -> {
                    add(node, path, "搜索邮件（当前查询：${redact(value)}）", GmailAction.SEARCH)
                }

                id == "open_search" && node.isClickable -> {
                    add(node, path, "打开 Gmail 搜索框", GmailAction.TAP)
                }

                id in safeClicks && node.isClickable -> {
                    add(node, path, safeClicks.getValue(id), GmailAction.TAP)
                }

                sort && node.isClickable && !body -> {
                    add(node, path, "排序：${redact(value.ifBlank { description }.ifBlank { descendantsText(node) })}", GmailAction.TAP)
                }

                value in sortOptions && path.isNotEmpty() && !body -> {
                    val parent = node.parent
                    try {
                        if (parent?.isClickable == true && parent.className == "android.view.View") {
                            add(parent, path.dropLast(1), "排序：$value", GmailAction.TAP)
                        }
                    } finally {
                        parent?.recycle()
                    }
                }

                id == "thread_list_view" || id == "conversation_webview" || node.className == "android.webkit.WebView" -> {
                    addScrolling(node, path)
                }

                node.isClickable && node.className == "android.widget.ImageButton" &&
                    (toolbar || path.size < 22) && description in backLabels -> {
                    add(node, path, "返回上一页", GmailAction.TAP)
                }

                !body && node.isClickable && description in drawerLabels -> {
                    add(node, path, "打开导航菜单", GmailAction.TAP)
                }

                id == "hub_drawer_label_title" && value in inboxLabels -> {
                    val parent = node.parent
                    try {
                        if (parent?.isClickable == true && path.isNotEmpty()) add(parent, path.dropLast(1), "打开收件箱", GmailAction.TAP)
                    } finally {
                        parent?.recycle()
                    }
                }
            }
            if (!node.isEditable && text.size < 100 && (value.isNotBlank() || description.isNotBlank())) {
                when {
                    body && value.isNotBlank() && bodyLength < 9000 -> {
                        val line = redact(value).take(1500)
                        text += "邮件正文：$line"
                        bodyRead = true
                        bodyLength += line.length
                    }

                    id in evidenceIds -> {
                        text += "$id：${redact(value.ifBlank { description }).take(350)}"
                    }

                    sort || value in emptyLabels -> {
                        text += redact(value.ifBlank { description }).take(350)
                    }
                }
            }
        }
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try {
                visit(child, path + index, body, sort, toolbar)
            } finally {
                child.recycle()
            }
        }
    }

    private fun addScrolling(
        node: AccessibilityNodeInfo,
        path: List<Int>,
    ) {
        val actions = node.actionList.map { it.id }
        if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions) add(node, path, "向下滚动", GmailAction.SCROLL_FORWARD)
        if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions) add(node, path, "向上滚动", GmailAction.SCROLL_BACKWARD)
    }

    private fun add(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        label: String,
        action: GmailAction,
    ) {
        if (!node.isEnabled || !node.isVisibleToUser || controls.size >= 55) return
        val bounds = Rect().also(node::getBoundsInScreen)
        controls += Entry(path, node.viewIdResourceName, node.className?.toString(), bounds, label, action)
    }

    private fun field(
        node: AccessibilityNodeInfo,
        name: String,
    ): String {
        val nodes = node.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$name")
        return try {
            nodes
                .firstOrNull()
                ?.text
                ?.toString()
                ?.take(300)
                .orEmpty()
        } finally {
            nodes.forEach(AccessibilityNodeInfo::recycle)
        }
    }

    private fun descendantsText(node: AccessibilityNodeInfo): String {
        val children = (0 until node.childCount).mapNotNull(node::getChild)
        return try {
            children.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.joinToString(" ").take(150)
        } finally {
            children.forEach(AccessibilityNodeInfo::recycle)
        }
    }

    data class Entry(
        val path: List<Int>,
        val viewId: String?,
        val className: String?,
        val bounds: Rect,
        val label: String,
        val action: GmailAction,
    )

    companion object {
        const val PACKAGE = "com.google.android.gm"
        private val safeClicks =
            mapOf(
                "open_search_bar_clear_button" to "清除当前搜索",
                "open_search_view_clear_button" to "清除搜索框",
                "recipient_summary_container" to "展开或收起发件人详情",
                "upper_header" to "展开或收起邮件",
            )
        private val backLabels = setOf("Navigate up", "Back", "返回", "向上瀏覽", "向上导航", "向上巡覽")
        private val drawerLabels = setOf("開啟導覽匣", "打开导航抽屉", "Open navigation drawer")
        private val inboxLabels = setOf("Inbox", "收件箱", "收件匣", "Primary", "主要", "主收件箱")
        private val sortOptions = setOf("最新", "Most recent", "Most relevant", "最相關", "最相关")
        private val emptyLabels = setOf("No results", "No results found", "沒有搜尋結果", "找不到搜尋結果", "未找到结果", "无搜索结果")
        private val evidenceIds =
            setOf(
                "open_search_bar_text_view",
                "sender_name",
                "upper_date",
                "subject_and_folder_view",
                "sender_email",
                "details_from",
                "details_date",
                "details_subject",
                "recipient_summary",
                "no_conversations_text",
                "empty_text",
            )
        private val credentials = Regex("(?i)(验证码|驗證碼|verification code|your code|security code|one.time (?:code|password)|passcode|otp)")
        private val numbers = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

        private fun redact(value: String): String = if (credentials.containsMatchIn(value)) value.replace(numbers, "[已隐藏]") else value
    }
}
