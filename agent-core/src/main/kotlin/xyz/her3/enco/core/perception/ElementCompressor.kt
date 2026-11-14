package xyz.her3.enco.core.perception

import xyz.her3.enco.core.api.IntRect
import xyz.her3.enco.core.api.RawNode
import xyz.her3.enco.core.api.ScreenElement

private const val MAX_ELEMENTS = 120
private const val MAX_LABEL_LENGTH = 120
private const val MAX_RAW_NODES = 600
private const val MAX_DEPTH = 40

data class ElementCompressionResult(
    val elements: List<ScreenElement>,
    val requiresTakeover: Boolean,
)

object ElementCompressor {
    fun compress(
        rawNodes: List<RawNode>,
        stageWidth: Int,
        stageHeight: Int,
    ): ElementCompressionResult {
        val nodes = rawNodes.filter { it.nodePath.size <= MAX_DEPTH }.take(MAX_RAW_NODES)
        val requiresTakeover = nodes.any { it.visible && it.password }
        if (stageWidth <= 0 || stageHeight <= 0) {
            return ElementCompressionResult(emptyList(), requiresTakeover)
        }

        val nodesByPath = nodes.associateBy { it.nodePath }
        val uniqueElements = LinkedHashMap<ScreenElement, OrderedElement>()
        nodes.forEachIndexed { index, node ->
            if (node.password || !node.visible) return@forEachIndexed
            val bounds = normalizeBounds(node.bounds, stageWidth, stageHeight) ?: return@forEachIndexed
            val tapTarget =
                if (node.enabled) {
                    nearestAncestor(node, nodesByPath) { it.canTap }
                } else {
                    null
                }
            val longPressTarget =
                if (node.enabled) {
                    nearestAncestor(node, nodesByPath) { it.canLongPress }
                } else {
                    null
                }
            val canTap = tapTarget != null
            val canLongPress = longPressTarget != null
            val canEdit = node.enabled && node.canEdit
            val canScrollForward = node.enabled && node.canScrollForward
            val canScrollBackward = node.enabled && node.canScrollBackward
            val hasInteraction =
                node.canTap || node.canLongPress || node.canEdit ||
                    node.canScrollForward || node.canScrollBackward ||
                    tapTarget != null || longPressTarget != null
            val label =
                firstLabel(node)
                    ?: if (hasInteraction) {
                        cleanLabel(node.className?.substringAfterLast('.')) ?: "未命名控件"
                    } else {
                        return@forEachIndexed
                    }
            val nodePath =
                when {
                    !node.enabled -> null
                    tapTarget != null -> tapTarget.nodePath
                    longPressTarget != null -> longPressTarget.nodePath
                    canScrollForward || canScrollBackward -> node.nodePath
                    else -> null
                }
            val element =
                ScreenElement(
                    id = 0,
                    label = label,
                    canTap = canTap,
                    canScrollForward = canScrollForward,
                    canScrollBackward = canScrollBackward,
                    bounds = bounds,
                    nodePath = nodePath,
                    editPath = if (canEdit) node.editPath ?: node.nodePath else null,
                    canEdit = canEdit,
                    canLongPress = canLongPress,
                )
            uniqueElements.putIfAbsent(element, OrderedElement(index, element))
        }

        val elements =
            uniqueElements.values
                .sortedWith(
                    compareBy<OrderedElement> { if (it.element.isInteractive()) 0 else 1 }
                        .thenBy { it.order },
                ).take(MAX_ELEMENTS)
                .sortedBy { it.order }
                .mapIndexed { index, ordered -> ordered.element.copy(id = index + 1) }
        return ElementCompressionResult(elements, requiresTakeover)
    }

    private fun nearestAncestor(
        node: RawNode,
        nodesByPath: Map<List<Int>, RawNode>,
        predicate: (RawNode) -> Boolean,
    ): RawNode? {
        for (size in node.nodePath.size downTo 0) {
            val candidate = nodesByPath[node.nodePath.subList(0, size)]
            if (candidate != null && candidate.enabled && predicate(candidate)) return candidate
        }
        return null
    }

    private fun firstLabel(node: RawNode): String? =
        listOf(node.text, node.contentDescription, node.hintText).firstNotNullOfOrNull(::cleanLabel)

    private fun cleanLabel(value: String?): String? =
        value
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(MAX_LABEL_LENGTH)
            ?.takeIf { it.isNotEmpty() }

    private fun normalizeBounds(
        bounds: IntRect,
        stageWidth: Int,
        stageHeight: Int,
    ): IntRect? {
        val clipped =
            IntRect(
                left = bounds.left.coerceIn(0, stageWidth),
                top = bounds.top.coerceIn(0, stageHeight),
                right = bounds.right.coerceIn(0, stageWidth),
                bottom = bounds.bottom.coerceIn(0, stageHeight),
            )
        if (clipped.right <= clipped.left || clipped.bottom <= clipped.top) return null
        return IntRect(
            left = scale(clipped.left, stageWidth),
            top = scale(clipped.top, stageHeight),
            right = scale(clipped.right, stageWidth),
            bottom = scale(clipped.bottom, stageHeight),
        )
    }

    private fun scale(
        value: Int,
        dimension: Int,
    ): Int = (value.toLong() * 1000L / dimension).toInt()

    private fun ScreenElement.isInteractive(): Boolean = canTap || canLongPress || canEdit || canScrollForward || canScrollBackward

    private data class OrderedElement(
        val order: Int,
        val element: ScreenElement,
    )
}
