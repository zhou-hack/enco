package xyz.her3.enco.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.her3.enco.core.api.Action

class ActionParserTest {
    @Test
    fun parsesTargetAndCoordinateActions() {
        assertEquals(Action.Tap(1), parse("""{"action":"tap","target":1}"""))
        assertEquals(Action.TapAt(0, 1000), parse("""{"action":"tap","x":0,"y":1000}"""))
        assertEquals(Action.LongPress(2), parse("""{"action":"long_press","target":2}"""))
        assertEquals(Action.LongPressAt(500, 400), parse("""{"action":"long_press","x":500,"y":400}"""))
        assertEquals(Action.Scroll(3, true), parse("""{"action":"scroll","target":3,"direction":"forward"}"""))
        assertEquals(Action.Scroll(3, false), parse("""{"action":"scroll","target":3,"direction":"backward"}"""))
    }

    @Test
    fun parsesTypeWithStrictSubmitFlag() {
        assertEquals(Action.Type(1, "你好"), parse("""{"action":"type","target":1,"text":"你好"}"""))
        assertEquals(
            Action.Type(1, "第一行\n第二行", true),
            parse("""{"action":"type","target":1,"text":"第一行\n第二行","submit":true}"""),
        )
        assertNull(parse("""{"action":"type","target":1,"text":"bad\ttext"}"""))
        assertNull(parse("""{"action":"type","target":1,"text":"bad\rtext"}"""))
        assertNull(parse("""{"action":"type","target":1,"text":""}"""))
        assertNull(parse("""{"action":"type","target":1,"text":"${"a".repeat(501)}"}"""))
        assertNull(parse("""{"action":"type","target":1,"text":"ok","submit":"true"}"""))
    }

    @Test
    fun rejectsInvalidTargetsAndCoordinates() {
        val invalid =
            listOf(
                """{"action":"tap","target":0}""",
                """{"action":"tap","target":-1}""",
                """{"action":"tap","target":"1"}""",
                """{"action":"tap","target":1.0}""",
                """{"action":"tap","x":-1,"y":0}""",
                """{"action":"tap","x":0,"y":1001}""",
                """{"action":"tap","x":0}""",
                """{"action":"tap","target":1,"x":0,"y":0}""",
                """{"action":"long_press","x":"0","y":0}""",
            )
        invalid.forEach { assertNull(it, parse(it)) }
    }

    @Test
    fun parsesAndValidatesSwipes() {
        assertEquals(Action.Swipe("up"), parse("""{"action":"swipe","direction":"up"}"""))
        assertEquals(
            Action.Swipe("left", 1000, 2),
            parse("""{"action":"swipe","direction":"left","distance":1000,"target":2}"""),
        )
        listOf(
            """{"action":"swipe","direction":"forward"}""",
            """{"action":"swipe","direction":"up","distance":0}""",
            """{"action":"swipe","direction":"up","distance":1001}""",
            """{"action":"swipe","direction":"up","distance":"500"}""",
            """{"action":"swipe","direction":"up","target":0}""",
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test
    fun parsesNavigationAndBoundedWait() {
        assertEquals(Action.Back, parse("""{"action":"back"}"""))
        assertEquals(Action.Home, parse("""{"action":"home"}"""))
        assertEquals(Action.Wait(1), parse("""{"action":"wait","ms":1}"""))
        assertEquals(Action.Wait(60_000), parse("""{"action":"wait","ms":60000}"""))
        assertNull(parse("""{"action":"wait","ms":0}"""))
        assertNull(parse("""{"action":"wait","ms":60001}"""))
        assertNull(parse("""{"action":"wait","ms":true}"""))
    }

    @Test
    fun parsesLaunchQuestionAndMemoryWithLengths() {
        assertEquals(Action.Launch("设置"), parse("""{"action":"launch","app":" 设置 "}"""))
        assertEquals(Action.AskUser("发给谁？"), parse("""{"action":"ask_user","question":"发给谁？"}"""))
        assertEquals(Action.Remember("不要香菜"), parse("""{"action":"remember","fact":"不要香菜"}"""))
        assertEquals(Action.Launch("a".repeat(200)), parse("""{"action":"launch","app":"${"a".repeat(200)}"}"""))
        assertNull(parse("""{"action":"launch","app":"${"a".repeat(201)}"}"""))
        assertNull(parse("""{"action":"ask_user","question":"${"a".repeat(501)}"}"""))
        assertNull(parse("""{"action":"remember","fact":""}"""))
    }

    @Test
    fun keepsLegacyTakeoverAndSupportsReasonRequest() {
        assertEquals(Action.TakeOver, parse("""{"action":"take_over"}"""))
        assertEquals(Action.TakeOver, parse("""{"action":"take_over","reason":"需要登录"}"""))
        assertEquals(
            Action.TakeOverRequest("需要登录"),
            parse("""{"action":"take_over_request","reason":"需要登录"}"""),
        )
        assertNull(parse("""{"action":"take_over","reason":1}"""))
        assertNull(parse("""{"action":"take_over_request","reason":""}"""))
        assertNull(parse("""{"action":"take_over_request","reason":"${"a".repeat(241)}"}"""))
    }

    @Test
    fun parsesStrictFinishResult() {
        assertEquals(Action.Finish(true, "完成"), parse("""{"action":"finish","success":true,"summary":"完成"}"""))
        assertNull(parse("""{"action":"finish","success":"true","summary":"完成"}"""))
        assertNull(parse("""{"action":"finish","success":true,"summary":" "}"""))
        assertNull(parse("""{"action":"finish","success":true,"summary":"${"a".repeat(241)}"}"""))
    }

    @Test
    fun acceptsJsonFenceAndRejectsUnknownShellOrMalformedInput() {
        assertEquals(Action.Home, parse("```json\n{\"action\":\"home\"}\n```"))
        listOf(
            """{"action":"shell","command":"anything"}""",
            """{"action":"unknown"}""",
            """{"action":1}""",
            """[]""",
            """not json""",
            """{"action":"home"} trailing""",
        ).forEach { assertNull(it, parse(it)) }
    }

    private fun parse(json: String): Action? = parseAction(json)
}
