package com.shijing.xomniclaw.agent.memory.gallery

/**
 * 图片记忆隐私过滤器。
 *
 * 目标不是做绝对准确的 DLP，而是先提供一层保守过滤，避免高敏感内容直接进入长期记忆。
 */
object ImageMemoryPrivacyFilter {
    private val idCardPattern = Regex("\\b\\d{17}[\\dXx]\\b")
    private val phonePattern = Regex("\\b1\\d{10}\\b")
    private val bankCardPattern = Regex("\\b\\d{15,19}\\b")
    private val emailPattern = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")

    // Chinese entries stay: the phone/ID patterns below are CN formats, so CN
    // summaries still arrive. English entries cover summaries written in English.
    private val highRiskKeywords = listOf(
        "身份证", "银行卡", "支付", "转账", "验证码", "密码", "账单", "收款",
        "订单号", "住址", "发票", "护照", "社保", "公积金", "聊天记录",
        "id card", "bank card", "payment", "transfer", "verification code", "password",
        "bill", "invoice", "passport", "order number", "address", "chat history"
    )

    data class FilterResult(
        val sanitizedText: String,
        val sensitivity: String,
        val redacted: Boolean
    )

    fun sanitize(text: String): FilterResult {
        if (text.isBlank()) {
            return FilterResult(
                sanitizedText = "",
                sensitivity = "low",
                redacted = false
            )
        }

        var sanitized = text
        var redacted = false

        listOf(idCardPattern, phonePattern, bankCardPattern, emailPattern).forEach { pattern ->
            if (pattern.containsMatchIn(sanitized)) {
                sanitized = sanitized.replace(pattern, "[REDACTED]")
                redacted = true
            }
        }

        val normalized = sanitized.lowercase()
        val hasHighRiskKeywords = highRiskKeywords.any { normalized.contains(it.lowercase()) }
        val sensitivity = when {
            hasHighRiskKeywords -> "high"
            redacted -> "medium"
            else -> "low"
        }

        return FilterResult(
            sanitizedText = sanitized.trim(),
            sensitivity = sensitivity,
            redacted = redacted
        )
    }

    fun deriveTags(record: AlbumImageRecord, summary: String): List<String> {
        val tags = linkedSetOf<String>()
        val bucket = record.bucketName.orEmpty().lowercase()
        val text = summary.lowercase()

        if (bucket.contains("screenshot") || bucket.contains("截图")) tags += "screenshot"
        if (bucket.contains("camera") || bucket.contains("相机")) tags += "camera"
        if (text.containsAny("工作", "会议", "文档", "work", "meeting", "document")) tags += "work"
        if (text.containsAny("学习", "课程", "知识", "study", "course", "learning")) tags += "learning"
        if (text.containsAny("购物", "商品", "订单", "shopping", "purchase", "order")) tags += "shopping"
        if (text.containsAny("旅行", "酒店", "机票", "travel", "hotel", "flight")) tags += "travel"
        if (text.containsAny("聊天", "消息", "社交", "chat", "message", "social")) tags += "social"

        if (tags.isEmpty()) {
            tags += "uncategorized"
        }

        return tags.toList()
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }
}
