package xyz.her3.enco.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTaskToolTest {
    @Test
    fun parsesOnlySupportedToolArgumentsWithBoundedGoals() {
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.SETTINGS, "打开蓝牙设置"),
            parse("""{"kind":"settings","goal":"打开蓝牙设置"}"""),
        )
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.WECHAT_NAVIGATION, "查看微信发现"),
            parse("""{"kind":"wechat_navigation","goal":"查看微信发现"}"""),
        )
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.WECHAT_MESSAGE, "给小林发：到了"),
            parse("""{"kind":"wechat_message","goal":"给小林发：到了"}"""),
        )
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.GMAIL_INBOX, "列出最近10封邮件"),
            parse("""{"kind":"gmail_inbox","goal":"列出最近10封邮件"}"""),
        )
        assertNull(PhoneTaskTool.parse("shell", """{"kind":"settings","goal":"打开蓝牙设置"}"""))
        assertNull(parse("""{"kind":"shell","goal":"运行命令"}"""))
        assertNull(parse("""{"kind":"settings","goal":"打开蓝牙设置","permission":"full"}"""))
        assertNull(parse("""{"kind":"settings","goal":" "}"""))
        assertNull(parse("""{"kind":"settings","goal":true}"""))
        assertNull(parse("""{"kind":"settings","goal":"${"a".repeat(501)}"}"""))
        assertNull(parse("直接打开微信"))
    }

    private fun parse(arguments: String) = PhoneTaskTool.parse(PhoneTaskTool.NAME, arguments)
}
