package xyz.her3.enco.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatTaskTest {
    @Test
    fun routesPhoneCommandsWithoutRoutingHowToQuestions() {
        assertTrue(isPhoneTaskRequest("帮我打开蓝牙设置"))
        assertTrue(isPhoneTaskRequest("去微信给张三发个信息"))
        assertFalse(isPhoneTaskRequest("如何打开微信？"))
        assertFalse(isPhoneTaskRequest("给我解释一下微信"))
        assertEquals(PhoneTaskKind.WECHAT_MESSAGE, phoneTaskKind("在微信给张三发：明天见"))
        assertEquals(PhoneTaskKind.WECHAT_NAVIGATION, phoneTaskKind("打开微信发现页"))
    }

    @Test
    fun onlyAcceptsBoundedVisionActions() {
        assertEquals(VisionAction.Tap(800, 100, "搜索"), WeChatTask.parseVision("""{"action":"tap","x":800,"y":100,"label":"搜索"}"""))
        assertNull(WeChatTask.parseVision("""{"action":"tap","x":1001,"y":100,"label":"搜索"}"""))
        assertNull(WeChatTask.parseVision("""{"action":"send","target":1}"""))
        assertNull(WeChatTask.parseVision("""{"action":"type","text":"anything"}"""))
    }

    @Test
    fun requiresBothRecipientAndMessage() {
        assertNull(WeChatTask.parseDraft("""{"recipient":"张三"}"""))
        assertNull(WeChatTask.parseDraft("""{"recipient":"张三","message":""}"""))
        assertEquals(WeChatDraft("张三", "明天见"), WeChatTask.parseDraft("""{"recipient":"张三","message":"明天见"}"""))
    }

    @Test
    fun flagsSensitiveVisionLabelsForManualAction() {
        assertTrue(WeChatTask.needsManualAction("確認傳送"))
        assertTrue(WeChatTask.needsManualAction("输入验证码"))
        assertFalse(WeChatTask.needsManualAction("搜索"))
    }
}
