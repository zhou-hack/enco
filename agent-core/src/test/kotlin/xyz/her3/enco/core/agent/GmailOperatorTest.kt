package xyz.her3.enco.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.her3.enco.core.protocol.encoJson

class GmailOperatorTest {
    @Test
    fun summaryRequiresActualSearchAndOpenedBody() =
        runBlocking {
            val row = GmailControl(1, "会话：示例发件人", GmailAction.OPEN_MESSAGE)
            val search = GmailControl(1, "搜索邮件", GmailAction.SEARCH)
            val inbox = GmailScreen(1, listOf(row), listOf("会话摘要"), hasMailResults = true)
            val screens =
                ArrayDeque(
                    listOf(
                        inbox,
                        GmailScreen(2, listOf(search), emptyList()),
                        inbox.copy(id = 3),
                        inbox.copy(id = 4),
                        GmailScreen(5, emptyList(), listOf("邮件正文：会议改到周五"), hasMessageBody = true),
                    ),
                )
            val answers =
                ArrayDeque(
                    listOf(
                        """{"action":"finish","success":true,"summary":"已完成"}""",
                        """{"action":"type","target":1,"text":"from:example.org"}""",
                        """{"action":"finish","success":true,"summary":"用标题应付"}""",
                        """{"action":"tap","target":1}""",
                        """{"action":"finish","success":true,"summary":"会议改到周五"}""",
                    ),
                )
            val actions = mutableListOf<Triple<Long, Int, String?>>()
            val result =
                GmailOperator().run(
                    goal = "找一下 example.org 发来的邮件，总结正文",
                    observe = { encoJson.encodeToString(screens.removeFirst()) },
                    execute = { id, target, query -> actions.add(Triple(id, target, query)) },
                    decide = { _, input ->
                        if (answers.size == 1) assertTrue(input.contains("会议改到周五"))
                        answers.removeFirst()
                    },
                    settle = {},
                )
            assertEquals(listOf(Triple(2L, 1, "from:example.org"), Triple(4L, 1, null)), actions)
            assertEquals(TaskResult.Finished(true, "会议改到周五"), result)
        }
}
