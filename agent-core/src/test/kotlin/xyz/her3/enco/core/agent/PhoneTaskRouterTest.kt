package xyz.her3.enco.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTaskRouterTest {
    @Test
    fun parsesOnlySupportedExplicitRoutesWithBoundedGoals() {
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.SETTINGS, "打开蓝牙设置"),
            PhoneTaskRouter.parse("""{"route":"settings","goal":"打开蓝牙设置"}"""),
        )
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.WECHAT_NAVIGATION, "查看微信发现"),
            PhoneTaskRouter.parse("""{"route":"wechat_navigation","goal":"查看微信发现"}"""),
        )
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.WECHAT_MESSAGE, "给小林发：到了"),
            PhoneTaskRouter.parse("""{"route":"wechat_message","goal":"给小林发：到了"}"""),
        )
        assertNull(PhoneTaskRouter.parse("""{"route":"chat","goal":"打开微信"}"""))
        assertEquals(
            PhoneTaskRequest(PhoneTaskKind.GMAIL_INBOX, "列出最近10封邮件"),
            PhoneTaskRouter.parse("""{"route":"gmail_inbox","goal":"列出最近10封邮件"}"""),
        )
        assertNull(PhoneTaskRouter.parse("""{"route":"shell","goal":"运行命令"}"""))
        assertNull(PhoneTaskRouter.parse("""{"route":"settings","goal":" "}"""))
        assertNull(PhoneTaskRouter.parse("""{"route":"settings","goal":"${"a".repeat(501)}"}"""))
        assertNull(PhoneTaskRouter.parse("直接打开微信"))
    }
}
