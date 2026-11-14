package xyz.her3.enco.core.api

import kotlinx.serialization.Serializable

@Serializable
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class RawNode(
    val nodePath: List<Int>,
    val bounds: IntRect,
    val packageName: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val canTap: Boolean = false,
    val canLongPress: Boolean = false,
    val password: Boolean = false,
    val canScrollForward: Boolean = false,
    val canScrollBackward: Boolean = false,
    val canEdit: Boolean = false,
    val editPath: List<Int>? = null,
)

@Serializable
data class Screenshot(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/png",
)
