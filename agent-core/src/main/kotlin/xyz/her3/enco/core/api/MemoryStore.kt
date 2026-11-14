package xyz.her3.enco.core.api

import kotlinx.serialization.Serializable

interface MemoryStore {
    suspend fun searchMemories(query: MemoryQuery): List<MemoryEntry>

    suspend fun saveMemory(memory: MemoryEntry): MemoryEntry

    suspend fun deleteMemory(id: Long): Boolean

    suspend fun searchSkills(
        query: String = "",
        limit: Int = 20,
    ): List<Skill>

    suspend fun saveSkill(skill: Skill): Skill

    suspend fun deleteSkill(id: Long): Boolean
}

enum class MemoryKind {
    PROFILE,
    CONVERSATION,
    EXPERIENCE,
}

@Serializable
data class MemoryQuery(
    val text: String = "",
    val kind: MemoryKind? = null,
    val limit: Int = 20,
)

@Serializable
data class MemoryEntry(
    val id: Long = 0,
    val kind: MemoryKind,
    val content: String,
    val createdAt: Long = 0,
    val updatedAt: Long = createdAt,
)

@Serializable
data class Skill(
    val id: Long = 0,
    val name: String,
    val trigger: String,
    val appPackage: String? = null,
    val steps: List<String> = emptyList(),
    val notes: String = "",
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val averageSteps: Double? = null,
)
