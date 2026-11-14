package xyz.her3.enco.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatTaskTest {
    @Test
    fun permissionOnlySkipsReviewForVerifiedNavigation() =
        runBlocking {
            val search = VisionAction.Tap(800, 100, "搜索")
            for (permission in PhoneTaskPermission.entries) {
                assertEquals(WeChatTapDecision.EXECUTE, WeChatTask.tapDecision(permission, search) { WeChatTapReview.NAVIGATION })
                assertEquals(
                    WeChatTapDecision.TAKE_OVER,
                    WeChatTask.tapDecision(permission, search) { WeChatTapReview.TAKE_OVER },
                )
                assertEquals(
                    WeChatTapDecision.TAKE_OVER,
                    WeChatTask.tapDecision(permission, search.copy(label = "付款")) { error("付款必须接管") },
                )
            }
            assertEquals(
                WeChatTapDecision.CONFIRM,
                WeChatTask.tapDecision(PhoneTaskPermission.IMPORTANT_ONLY, search) { WeChatTapReview.UNCERTAIN },
            )
            assertEquals(
                WeChatTapDecision.TAKE_OVER,
                WeChatTask.tapDecision(PhoneTaskPermission.FULL, search) { WeChatTapReview.UNCERTAIN },
            )
            assertEquals(WeChatTapReview.TAKE_OVER, WeChatTask.parseTapReview("""{"decision":true}"""))
            assertEquals(WeChatTapReview.NAVIGATION, WeChatTask.parseTapReview("""{"decision":"navigation"}"""))
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
