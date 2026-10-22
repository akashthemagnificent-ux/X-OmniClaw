package com.shijing.xomniclaw.ui.session

import com.shijing.xomniclaw.core.MainEntryNew
import com.shijing.xomniclaw.providers.LegacyMessage
import com.shijing.xomniclaw.ui.compose.ChatMessage
import com.shijing.xomniclaw.ui.compose.ChatMessageKind
import com.shijing.xomniclaw.ui.compose.MessageStatus

/**
 * 统一把后端消息和执行进度转换成聊天时间线，避免不同入口的映射逻辑漂移。
 */
object ChatTimelineMapper {
    /**
     * @param baseTimeMs Agent 会话创建时间（毫秒），与 [index] 组合成稳定时间戳，避免合并排序时顺序错乱。
     */
    fun fromBackendMessage(
        sessionId: String,
        index: Int,
        message: LegacyMessage,
        baseTimeMs: Long
    ): ChatMessage? {
        val content = normalizeContent(message.content) ?: return null
        // Agent-injected runtime notes must not surface in the main conversation.
        // Both the English and the pre-translation Chinese prefixes are checked so
        // sessions persisted before the switch stay filtered.
        if (message.role == "user" &&
            (content.startsWith("System state memory") || content.startsWith("系统状态记忆"))
        ) {
            return null
        }
        // Pseudo-user nudges from the Python continuation loop, otherwise they show
        // up as an oversized system bubble.
        if (message.role == "user" &&
            (content.startsWith("System note: your last turn ended without calling any tool") ||
                content.startsWith("系统提示：你上一回合没有调用任何工具"))
        ) {
            return null
        }
        // LegacyMessage 无时间字段：用「会话锚点 + 列表下标」生成单调时间戳，多次同步结果一致。
        val stableTs = baseTimeMs + index * 10L
        return when (message.role) {
            // 与 Agent JSONL 中 role=thinking 的落盘一致，供冷启动后恢复思考时间线
            "thinking" -> ChatMessage(
                id = backendMessageId(sessionId, index, message.role, content),
                content = content,
                isUser = false,
                timestamp = stableTs,
                status = MessageStatus.SENT,
                kind = ChatMessageKind.THINKING,
                eventKey = "backend:$sessionId:$index:thinking"
            )

            "user" -> ChatMessage(
                id = backendMessageId(sessionId, index, message.role, content),
                content = content,
                isUser = true,
                timestamp = stableTs,
                status = MessageStatus.SENT,
                kind = ChatMessageKind.USER,
                eventKey = "backend:$sessionId:$index:user"
            )

            "assistant" -> ChatMessage(
                id = backendMessageId(sessionId, index, message.role, content),
                content = content,
                isUser = false,
                timestamp = stableTs,
                status = MessageStatus.SENT,
                kind = ChatMessageKind.ASSISTANT,
                eventKey = "backend:$sessionId:$index:assistant"
            )

            else -> null
        }
    }

    fun fromProgressEvent(event: MainEntryNew.UiProgressEvent): ChatMessage? {
        val rendered = event.content.trim()
        if (rendered.isBlank()) {
            return null
        }
        // 工具调用/结果仅用于内部执行过程，不进入主对话时间线展示。
        if (event.type == "tool_call" || event.type == "tool_result") {
            return null
        }
        val kind = when (event.type) {
            "thinking" -> ChatMessageKind.THINKING
            "reasoning" -> ChatMessageKind.THINKING
            "block_reply" -> ChatMessageKind.BLOCK_REPLY
            "error" -> ChatMessageKind.ERROR
            else -> ChatMessageKind.SYSTEM
        }
        val status = if (kind == ChatMessageKind.ERROR) MessageStatus.ERROR else MessageStatus.SENT
        return ChatMessage(
            id = event.id,
            content = rendered,
            isUser = false,
            timestamp = event.timestamp,
            status = status,
            kind = kind,
            eventKey = event.id
        )
    }

    private fun backendMessageId(sessionId: String, index: Int, role: String, content: String): String {
        return "backend_${sessionId}_${index}_${role}_${content.hashCode()}"
    }

    private fun normalizeContent(content: Any?): String? {
        val text = when (content) {
            is String -> content
            null -> null
            else -> content.toString()
        }?.trim()
        return text?.takeIf { it.isNotBlank() }
    }
}
