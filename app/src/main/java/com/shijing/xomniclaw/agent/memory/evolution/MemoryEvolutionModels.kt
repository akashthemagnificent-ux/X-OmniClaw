package com.shijing.xomniclaw.agent.memory.evolution

/**
 * Agent 任务结束后先写入待处理队列，真正的长期记忆更新由专门定时任务批处理完成。
 */
data class MemoryEvolutionEvent(
    val id: String,
    val sessionId: String,
    val userInput: String,
    val finalContent: String,
    val success: Boolean,
    val errorMessage: String?,
    val toolsUsed: List<String>,
    val triggeredAtMs: Long
)

data class MemoryCandidate(
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val confidence: Double,
    val sourceEventId: String
)

data class MemoryEvolutionReport(
    val processedEvents: Int,
    val acceptedCandidates: Int,
    val skippedCandidates: Int,
    val globalMemoryUpdated: Boolean,
    val profileUpdated: Boolean,
    val pendingEventsRemaining: Int,
    val message: String
)

enum class MemoryCategory(val sectionTitle: String) {
    USER_PREFERENCE("## User preferences and habits"),
    TASK_WORKFLOW("## X-OmniClaw task experience and workflows"),
    FAILURE_LESSON("## Failure lessons and workarounds"),
    PROJECT_CONTEXT("## Long-term project context")
}

data class MemoryEvolutionStatus(
    val lastRunAtMs: Long,
    val processedEvents: Int,
    val acceptedCandidates: Int,
    val globalMemoryChars: Int,
    val userProfileChars: Int,
    val pendingEvents: Int,
    val lastMessage: String
)
