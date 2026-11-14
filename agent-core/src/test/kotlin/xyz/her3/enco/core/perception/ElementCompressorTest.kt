package xyz.her3.enco.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.her3.enco.core.api.IntRect
import xyz.her3.enco.core.api.RawNode

class ElementCompressorTest {
    @Test
    fun filtersInvisibleEmptyZeroAreaAndOutsideNodes() {
        val nodes =
            listOf(
                node(listOf(0), text = "保留"),
                node(listOf(1), text = "不可见", visible = false),
                node(listOf(2), text = "零面积", bounds = IntRect(20, 20, 20, 40)),
                node(listOf(3), text = "舞台外", bounds = IntRect(1001, 0, 1100, 100)),
                node(listOf(4)),
            )

        val result = ElementCompressor.compress(nodes, 1000, 2000)

        assertEquals(listOf("保留"), result.elements.map { it.label })
    }

    @Test
    fun cleansControlCharactersTruncatesTextAndReadsDescriptionsAndHints() {
        val nodes =
            listOf(
                node(listOf(0), text = "\u0000蓝\n牙\t"),
                node(listOf(1), text = "x".repeat(140)),
                node(listOf(2), contentDescription = "内容描述"),
                node(listOf(3), hintText = "输入提示"),
            )

        val elements = ElementCompressor.compress(nodes, 1000, 2000).elements

        assertEquals("蓝牙", elements[0].label)
        assertEquals("x".repeat(120), elements[1].label)
        assertEquals(listOf("内容描述", "输入提示"), elements.drop(2).map { it.label })
    }

    @Test
    fun usesNearestClickableAncestor() {
        val nodes =
            listOf(
                node(listOf(0), canTap = true, contentDescription = "外层"),
                node(listOf(0, 0), canTap = true, contentDescription = "蓝牙行"),
                node(listOf(0, 0, 0), text = "蓝牙"),
            )

        val bluetooth = ElementCompressor.compress(nodes, 1000, 2000).elements.single { it.label == "蓝牙" }

        assertTrue(bluetooth.canTap)
        assertEquals(listOf(0, 0), bluetooth.nodePath)
    }

    @Test
    fun editableNodeUsesOwnEditPathInsteadOfClickableAncestor() {
        val nodes =
            listOf(
                node(listOf(0), canTap = true, contentDescription = "昵称行"),
                node(listOf(0, 0), hintText = "输入昵称", canEdit = true),
            )

        val editor = ElementCompressor.compress(nodes, 1000, 2000).elements.single { it.label == "输入昵称" }

        assertTrue(editor.canTap)
        assertTrue(editor.canEdit)
        assertEquals(listOf(0), editor.nodePath)
        assertEquals(listOf(0, 0), editor.editPath)
    }

    @Test
    fun passwordRequiresTakeoverAndNeverOutputsItsText() {
        val secret = "s3cret"
        val nodes =
            listOf(
                node(listOf(0), text = secret, password = true, canEdit = true),
                node(listOf(1), text = "登录"),
            )

        val result = ElementCompressor.compress(nodes, 1000, 2000)

        assertTrue(result.requiresTakeover)
        assertEquals(listOf("登录"), result.elements.map { it.label })
        assertFalse(result.elements.any { it.label.contains(secret) })
    }

    @Test
    fun mergesIdenticalTargetLabelCapabilitiesAndBounds() {
        val bounds = IntRect(0, 100, 500, 200)
        val nodes =
            listOf(
                node(listOf(0), text = "蓝牙", bounds = bounds, canTap = true),
                node(listOf(0, 0), text = "蓝牙", bounds = bounds),
            )

        val result = ElementCompressor.compress(nodes, 1000, 2000)

        assertEquals(1, result.elements.size)
        assertEquals(listOf(0), result.elements.single().nodePath)
    }

    @Test
    fun keepsSameLabelAtDifferentPositions() {
        val nodes =
            listOf(
                node(listOf(0), text = "蓝牙", bounds = IntRect(0, 0, 100, 100), canTap = true),
                node(listOf(1), text = "蓝牙", bounds = IntRect(0, 200, 100, 300), canTap = true),
            )

        val result = ElementCompressor.compress(nodes, 1000, 2000)

        assertEquals(2, result.elements.size)
        assertEquals(listOf(1, 2), result.elements.map { it.id })
    }

    @Test
    fun disabledNodeHasNoActionCapabilities() {
        val path = listOf(0)
        val nodes =
            listOf(
                node(
                    path,
                    text = "禁用项",
                    enabled = false,
                    canTap = true,
                    canLongPress = true,
                    canEdit = true,
                    canScrollForward = true,
                    canScrollBackward = true,
                    editPath = path,
                ),
            )

        val element = ElementCompressor.compress(nodes, 1000, 2000).elements.single()

        assertFalse(element.canTap)
        assertFalse(element.canLongPress)
        assertFalse(element.canEdit)
        assertFalse(element.canScrollForward)
        assertFalse(element.canScrollBackward)
        assertNull(element.nodePath)
        assertNull(element.editPath)
    }

    @Test
    fun inheritsLongPressFromNearestAncestor() {
        val nodes =
            listOf(
                node(listOf(0), contentDescription = "更多", canLongPress = true),
                node(listOf(0, 0), text = "项目"),
            )

        val item = ElementCompressor.compress(nodes, 1000, 2000).elements.single { it.label == "项目" }

        assertTrue(item.canLongPress)
        assertFalse(item.canTap)
        assertEquals(listOf(0), item.nodePath)
    }

    @Test
    fun preservesBidirectionalScrolling() {
        val nodes =
            listOf(
                node(
                    listOf(0),
                    contentDescription = "列表",
                    canScrollForward = true,
                    canScrollBackward = true,
                ),
            )

        val element = ElementCompressor.compress(nodes, 1000, 2000).elements.single()

        assertTrue(element.canScrollForward)
        assertTrue(element.canScrollBackward)
        assertEquals(listOf(0), element.nodePath)
    }

    @Test
    fun clipsBoundsToStageAndNormalizesCoordinates() {
        val nodes =
            listOf(
                node(listOf(0), text = "裁切", bounds = IntRect(-50, -100, 100, 200)),
            )

        val element = ElementCompressor.compress(nodes, 200, 400).elements.single()

        assertEquals(IntRect(0, 0, 500, 500), element.bounds)
    }

    @Test
    fun limitsTo120PrioritizesInteractiveElementsAndRestoresPreOrder() {
        val textNodes = (0 until 130).map { index -> node(listOf(index), text = "文本$index") }
        val interactiveNodes =
            listOf(
                node(listOf(130), text = "交互0", canTap = true),
                node(listOf(131), text = "交互1", canEdit = true),
            )

        val elements = ElementCompressor.compress(textNodes + interactiveNodes, 1000, 2000).elements

        assertEquals(120, elements.size)
        assertEquals((1..120).toList(), elements.map { it.id })
        assertEquals(listOf("交互0", "交互1"), elements.takeLast(2).map { it.label })
        assertTrue(elements.any { it.label == "文本117" })
        assertFalse(elements.any { it.label == "文本118" })
    }

    @Test
    fun invisiblePasswordAndExcessiveDepthAreIgnored() {
        val nodes =
            listOf(
                node(listOf(0), text = "隐藏秘密", visible = false, password = true),
                node(List(41) { 0 }, text = "过深节点"),
                node(listOf(1), text = "普通文本"),
            )

        val result = ElementCompressor.compress(nodes, 1000, 2000)

        assertFalse(result.requiresTakeover)
        assertEquals(listOf("普通文本"), result.elements.map { it.label })
    }

    @Test
    fun rawNodeLimitDropsLaterInteractiveElements() {
        val nodes =
            List(600) { index -> node(listOf(index), text = "文本$index") } +
                node(listOf(600), text = "末尾按钮", canTap = true)

        val result = ElementCompressor.compress(nodes, 1000, 2000)

        assertFalse(result.elements.any { it.label == "末尾按钮" })
    }

    @Test
    fun emptyTreeIsValid() {
        val result = ElementCompressor.compress(emptyList(), 1000, 2000)

        assertTrue(result.elements.isEmpty())
        assertFalse(result.requiresTakeover)
    }

    private fun node(
        path: List<Int>,
        bounds: IntRect = IntRect(0, 0, 100, 100),
        text: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        visible: Boolean = true,
        enabled: Boolean = true,
        canTap: Boolean = false,
        canLongPress: Boolean = false,
        canEdit: Boolean = false,
        canScrollForward: Boolean = false,
        canScrollBackward: Boolean = false,
        password: Boolean = false,
        editPath: List<Int>? = null,
    ): RawNode =
        RawNode(
            nodePath = path,
            bounds = bounds,
            text = text,
            contentDescription = contentDescription,
            hintText = hintText,
            visible = visible,
            enabled = enabled,
            canTap = canTap,
            canLongPress = canLongPress,
            password = password,
            canScrollForward = canScrollForward,
            canScrollBackward = canScrollBackward,
            canEdit = canEdit,
            editPath = editPath,
        )
}
