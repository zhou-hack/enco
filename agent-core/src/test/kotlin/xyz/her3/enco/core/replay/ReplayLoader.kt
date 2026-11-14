package xyz.her3.enco.core.replay

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import xyz.her3.enco.core.api.Observation
import xyz.her3.enco.core.api.RawNode
import xyz.her3.enco.core.api.Screenshot
import xyz.her3.enco.core.perception.ElementCompressor
import xyz.her3.enco.core.protocol.encoJson
import java.io.File

@Serializable
data class ReplayFrame(
    val frameId: Long,
    val stageWidth: Int,
    val stageHeight: Int,
    val packageName: String? = null,
    val rawNodes: List<RawNode> = emptyList(),
    val screenshot: Screenshot? = null,
)

class ReplayLoader {
    fun load(scene: String): List<Observation> {
        val resource =
            ReplayLoader::class.java.classLoader.getResource("replays/$scene")
                ?: error("找不到回放场景：$scene")
        return File(resource.toURI())
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .sortedBy { it.name }
            .map { file ->
                val frame = encoJson.decodeFromString<ReplayFrame>(file.readText())
                frame.toObservation()
            }
    }

    private fun ReplayFrame.toObservation(): Observation {
        val compressed = ElementCompressor.compress(rawNodes, stageWidth, stageHeight)
        return Observation(
            id = frameId,
            elements = compressed.elements,
            requiresTakeover = compressed.requiresTakeover,
            rawNodes = rawNodes,
            screenshot = screenshot,
            packageName = packageName,
        )
    }
}
