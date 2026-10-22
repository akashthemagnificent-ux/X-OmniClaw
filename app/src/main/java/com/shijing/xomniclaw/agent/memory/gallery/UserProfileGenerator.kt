package com.shijing.xomniclaw.agent.memory.gallery

import com.shijing.xomniclaw.agent.memory.MemoryManager
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 用户画像生成器。
 *
 * v2 版本继续保持“规则聚合、可解释”的路线，但把画像拆成：
 * 1. 稳定画像：身份、偏好、节奏、风险；
 * 2. 近期摘要：最近 7 天主题与最近同步概况；
 * 3. 近期细节：仅保存在文件中，不建议默认全部注入上下文。
 */
class UserProfileGenerator(
    private val memoryManager: MemoryManager,
    private val repository: GalleryMemoryRepository
) {
    companion object {
        private val PROFILE_TIME_FORMAT =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val MEMORY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val topicMapping = mapOf(
            "work" to "Work",
            "learning" to "Learning",
            "shopping" to "Shopping",
            "travel" to "Travel",
            "social" to "Social",
            "screenshot" to "Screenshots",
            "camera" to "Photos",
            "uncategorized" to "Everyday content"
        )

        private val recurringNeedMapping = mapOf(
            "work" to "Summarize information",
            "learning" to "Organize knowledge",
            "shopping" to "Compare products",
            "travel" to "Plan trips",
            "social" to "Organize messages",
            "screenshot" to "Archive screenshots"
        )

        private val roleMapping = mapOf(
            "work" to "Knowledge worker",
            "shopping" to "Content consumer",
            "travel" to "Trip planner",
            "learning" to "Continuous learner",
            "social" to "Social communicator"
        )
    }

    suspend fun buildUserProfile(): String {
        val imageMemories = repository.readImageMemories()
        // 显式用户偏好与交互约定以 AGENTS.md（执行手册）中「全局交互准则」等为参考
        val userContext = memoryManager.readWorkspaceMarkdownFile("AGENTS.md")
        val longTermMemory = memoryManager.readMemory()
        val recentLogs = loadRecentLogs(limit = 3)
        val parsedEntries = parseImageMemories(imageMemories)
        val taskMemorySignals = parseTaskMemorySignals(longTermMemory, recentLogs)

        val topTags = parsedEntries.flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val topBuckets = parsedEntries.mapNotNull { it.bucketName }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val profile = UserProfileV2(
            lastUpdated = PROFILE_TIME_FORMAT.format(Date()),
            profileVersion = "v2",
            confidenceNote = buildConfidenceNote(parsedEntries.size, recentLogs.size),
            identitySnapshot = IdentitySnapshot(
                preferredName = extractPreferredName(userContext, longTermMemory),
                likelyRoles = deriveLikelyRoles(topTags),
                primaryLanguage = extractPrimaryLanguage(userContext),
                timezone = TimeZone.getDefault().id
            ),
            stablePreferences = StablePreferences(
                responseStyle = deriveResponseStyle(userContext, taskMemorySignals),
                preferredTopics = derivePreferredTopics(topTags, taskMemorySignals),
                contentPreferences = deriveContentPreferences(parsedEntries, topBuckets, taskMemorySignals),
                recurringNeeds = deriveRecurringNeeds(topTags, taskMemorySignals)
            ),
            lifestyleAndRoutine = LifestyleAndRoutine(
                activeTimePattern = deriveActiveTimePattern(parsedEntries),
                likelyWorkLifeMode = deriveWorkLifeMode(topTags, topBuckets),
                planningStyle = derivePlanningStyle(parsedEntries, topTags)
            ),
            interestDistribution = InterestDistribution(
                topInterestTags = topTags.map { it.key to it.value },
                topGallerySources = topBuckets.map { it.key to it.value }
            ),
            importantContextHints = deriveImportantContextHints(parsedEntries, topTags, topBuckets, taskMemorySignals),
            privacyAndRiskNotes = derivePrivacyAndRiskNotes(parsedEntries, taskMemorySignals),
            recentSignalsSummary = RecentSignalsSummary(
                last7dTopics = deriveRecentTopics(parsedEntries),
                lastSyncSummary = buildLastSyncSummary(parsedEntries)
            ),
            provenance = Provenance(
                imageMemoriesCount = parsedEntries.size,
                longTermMemoryAvailable = longTermMemory.isNotBlank(),
                recentLogsLoaded = recentLogs.size,
                generatedFrom = listOf(
                    "memory/IMAGE-MEMORY.md",
                    "MEMORY.md",
                    "memory/YYYY-MM-DD.md"
                )
            ),
            recentSignalsDetails = RecentSignalsDetails(
                recentFocuses = deriveRecentFocuses(parsedEntries, recentLogs, taskMemorySignals),
                latestMemoryHighlights = (
                    parsedEntries.takeLast(5).map { it.summary }.filter { it.isNotBlank() } +
                        taskMemorySignals.recentTaskHighlights
                    ).distinct().take(8)
            )
        )

        return UserProfileMarkdownFormatter.toMarkdown(profile)
    }

    private suspend fun loadRecentLogs(limit: Int): List<String> {
        return memoryManager.listLogs()
            .take(limit)
            .mapNotNull { date ->
                memoryManager.getLogByDate(date).takeIf { it.isNotBlank() }
            }
    }

    private fun parseImageMemories(markdown: String): List<ParsedImageMemory> {
        if (markdown.isBlank()) {
            return emptyList()
        }

        val chunks = markdown.split(Regex("(?m)^## "))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("# Image Memories") }

        return chunks.mapNotNull { chunk ->
            val lines = chunk.lines()
            if (lines.isEmpty()) {
                return@mapNotNull null
            }
            val displayName = lines.first().removePrefix("## ").trim()
            val timestampText = extractField(lines, "time")
                ?: extractField(lines, "timestamp")
                .orEmpty()
            val summary = extractField(lines, "summary").orEmpty()
            val albumOrBucket = extractField(lines, "album")
                ?: extractField(lines, "bucket")
            val tags = inferCompactTags(displayName, summary, albumOrBucket)
            ParsedImageMemory(
                displayName = displayName,
                bucketName = albumOrBucket?.takeIf { it.isNotBlank() },
                tags = tags,
                summary = summary,
                sensitivity = "low",
                extractedText = "",
                timestamp = parseTimestamp(timestampText)
            )
        }
    }

    private fun extractField(lines: List<String>, field: String): String? {
        val prefix = "- $field:"
        return lines.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
    }

    private fun parseTimestamp(value: String): LocalDateTime? {
        if (value.isBlank()) {
            return null
        }
        return runCatching {
            LocalDateTime.parse(value, MEMORY_TIME_FORMATTER)
        }.getOrNull()
    }

    /**
     * image-memories 极简条目不保存独立 tags 字段。
     * 从文件名、summary 以及 `album`（图片来源目录）做 best effort 推断，让 user-profile 继续可用。
     */
    private fun inferCompactTags(
        displayName: String,
        summary: String,
        albumOrBucket: String?
    ): List<String> {
        val normalized = "$displayName $summary ${albumOrBucket.orEmpty()}".lowercase(Locale.getDefault())
        val tags = linkedSetOf<String>()
        if (normalized.containsAny("截图", "screen", "screenshot")) {
            tags += "screenshot"
        }
        if (normalized.containsAny("工作", "文档", "表格", "电脑", "work", "document", "spreadsheet", "laptop")) {
            tags += "work"
        }
        if (normalized.containsAny("学习", "课程", "笔记", "study", "course", "note")) {
            tags += "learning"
        }
        if (normalized.containsAny("购物", "小票", "发票", "订单", "shopping", "receipt", "invoice", "order")) {
            tags += "shopping"
        }
        if (normalized.containsAny("聊天", "消息", "社交", "chat", "message", "social")) {
            tags += "social"
        }
        if (normalized.containsAny("出行", "旅行", "酒店", "机票", "travel", "hotel", "flight")) {
            tags += "travel"
        }
        if (normalized.containsAny("相机", "拍摄", "照片", "camera", "photo")) {
            tags += "camera"
        }
        if (tags.isEmpty()) {
            tags += "uncategorized"
        }
        return tags.toList()
    }

    private fun buildConfidenceNote(imageMemoryCount: Int, recentLogsCount: Int): String {
        val confidence = when {
            imageMemoryCount >= 30 || recentLogsCount >= 5 -> "high"
            imageMemoryCount >= 10 || recentLogsCount >= 2 -> "medium"
            else -> "low"
        }
        return "This profile is inferred from gallery memories, long-term memory, and recent logs. Evidence confidence: $confidence."
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private fun extractPreferredName(userContext: String, longTermMemory: String): String {
        val candidateTexts = listOf(userContext, longTermMemory)
        val patterns = listOf(
            Regex("(?im)^\\s*preferred_name\\s*[:：]\\s*(.+)$"),
            Regex("(?im)^\\s*name\\s*[:：]\\s*(.+)$"),
            Regex("(?im)^\\s*称呼\\s*[:：]\\s*(.+)$"),
            Regex("(?im)^\\s*昵称\\s*[:：]\\s*(.+)$")
        )
        patterns.forEach { pattern ->
            candidateTexts.forEach { text ->
                val match = pattern.find(text)
                if (match != null) {
                    return match.groupValues[1].trim()
                }
            }
        }
        return "unknown"
    }

    private fun extractPrimaryLanguage(userContext: String): String {
        val normalized = userContext.lowercase(Locale.getDefault())
        return when {
            normalized.contains("use chinese") || normalized.contains("中文") -> "zh-CN"
            normalized.contains("english") -> "en-US"
            else -> Locale.getDefault().toLanguageTag()
        }
    }

    private fun deriveResponseStyle(userContext: String, taskSignals: TaskMemorySignals): String {
        val normalized = userContext.lowercase(Locale.getDefault())
        val traits = mutableListOf<String>()
        if (normalized.contains("brief")) traits += "Concise"
        if (normalized.contains("clear")) traits += "Structured"
        if (normalized.contains("success confirmations")) traits += "Execution-oriented"
        traits += taskSignals.responseStyleHints
        return traits.distinct().joinToString(" / ").ifBlank { "Structured / execution-oriented" }
    }

    private fun deriveLikelyRoles(topTags: List<Map.Entry<String, Int>>): List<String> {
        val roles = topTags.mapNotNull { roleMapping[it.key] }.distinct().toMutableList()
        if (roles.isEmpty()) {
            roles += "Personal device user"
        }
        return roles.take(4)
    }

    private fun derivePreferredTopics(
        topTags: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val topics = topTags.map { topicMapping[it.key] ?: it.key }
            .filter { it.isNotBlank() }
            .plus(taskSignals.preferredTaskTopics)
            .distinct()
        return if (topics.isEmpty()) listOf("Everyday content") else topics.take(5)
    }

    private fun deriveContentPreferences(
        entries: List<ParsedImageMemory>,
        topBuckets: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val preferences = mutableListOf<String>()
        if (entries.isNotEmpty()) {
            val screenshotRatio = entries.count { it.tags.contains("screenshot") } / entries.size.toDouble()
            val extractedTextRatio = entries.count { it.extractedText.isNotBlank() } / entries.size.toDouble()
            val cameraBucketRatio = entries.count { (it.bucketName ?: "").contains("camera", ignoreCase = true) } / entries.size.toDouble()

            if (screenshotRatio >= 0.35 || topBuckets.firstOrNull()?.key?.contains("screenshot", ignoreCase = true) == true) {
                preferences += "Screenshot-heavy information"
            }
            if (extractedTextRatio >= 0.30) {
                preferences += "Prefers text content"
            }
            if (cameraBucketRatio >= 0.25) {
                preferences += "Prefers photo records"
            }
            if (entries.any { it.summary.contains("新闻") || it.summary.contains("热点") || it.summary.contains("资讯") }) {
                preferences += "Prefers news-type content"
            }
        }
        preferences += taskSignals.contentPreferences

        return preferences.distinct().ifEmpty { listOf("Varied content preferences") }.take(4)
    }

    private fun deriveRecurringNeeds(
        topTags: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val needs = topTags.mapNotNull { recurringNeedMapping[it.key] }.distinct().toMutableList()
        needs += taskSignals.recurringNeeds
        if (needs.isEmpty()) {
            needs += "Add context"
        }
        if ("Summarize information" in needs && "Archive screenshots" !in needs) {
            needs += "Next-step suggestions"
        }
        return needs.take(5)
    }

    private fun deriveActiveTimePattern(entries: List<ParsedImageMemory>): String {
        val timestamps = entries.mapNotNull { it.timestamp }
        if (timestamps.isEmpty()) {
            return "unknown"
        }

        val averageHour = timestamps.map { it.hour }.average()
        val workdayRatio = timestamps.count { it.dayOfWeek in listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        ) } / timestamps.size.toDouble()

        val period = when {
            averageHour >= 19 || averageHour < 6 -> "Active in the evening"
            averageHour in 6.0..11.9 -> "Active in the morning"
            else -> "Active during the day"
        }
        val cadence = if (workdayRatio >= 0.65) "Frequent on weekdays" else "Balanced across the week"
        return "$period / $cadence"
    }

    private fun deriveWorkLifeMode(
        topTags: List<Map.Entry<String, Int>>,
        topBuckets: List<Map.Entry<String, Int>>
    ): String {
        val tagKeys = topTags.map { it.key }
        return when {
            "work" in tagKeys && "screenshot" in tagKeys -> "Many work and utility screenshots"
            "travel" in tagKeys || "camera" in tagKeys -> "Much life-log and travel content"
            topBuckets.any { it.key.contains("screenshot", ignoreCase = true) } -> "Screenshot-driven information capture"
            else -> "Mixed work and personal use"
        }
    }

    private fun derivePlanningStyle(
        entries: List<ParsedImageMemory>,
        topTags: List<Map.Entry<String, Int>>
    ): String {
        val tagKeys = topTags.map { it.key }
        val hasTaskSignals = entries.any {
            it.summary.contains("安排") || it.summary.contains("待办") || it.summary.contains("提醒")
        }
        return when {
            "work" in tagKeys || hasTaskSignals -> "Task-driven"
            "travel" in tagKeys || "camera" in tagKeys -> "Prefers life logging"
            else -> "Mostly ad-hoc notes"
        }
    }

    private fun deriveImportantContextHints(
        entries: List<ParsedImageMemory>,
        topTags: List<Map.Entry<String, Int>>,
        topBuckets: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val hints = mutableListOf<String>()
        val tagKeys = topTags.map { it.key }

        if ("work" in tagKeys || "screenshot" in tagKeys) {
            hints += "Often saves work screenshots and pending items; during task execution, provide summaries and next-step suggestions."
        }
        if ("shopping" in tagKeys) {
            hints += "For shopping or price-comparison tasks, use recent image memories to infer interests first."
        }
        if ("social" in tagKeys) {
            hints += "When recent social or chat content exists, retrieve related image memories before executing the task."
        }
        if (hints.isEmpty() && entries.isNotEmpty()) {
            val dominantBucket = topBuckets.firstOrNull()?.key ?: "album"
            hints += "This profile is derived mainly from $dominantBucket content; for image-related tasks, combine it with IMAGE-MEMORY.md."
        }
        hints += taskSignals.importantHints
        return hints.distinct().take(6)
    }

    private fun derivePrivacyAndRiskNotes(
        entries: List<ParsedImageMemory>,
        taskSignals: TaskMemorySignals
    ): PrivacyAndRiskNotes {
        val avoidTopics = (listOf("证件号", "支付细节", "验证码") + taskSignals.riskNotes).distinct().take(8)
        if (entries.isEmpty()) {
            return PrivacyAndRiskNotes(
                highSensitivityRatio = "unknown",
                avoidTopics = avoidTopics,
                profileGenerationMode = "filtered_evidence_based_v2"
            )
        }

        val mediumOrHighCount = entries.count { it.sensitivity == "medium" || it.sensitivity == "high" }
        val ratio = mediumOrHighCount / entries.size.toDouble()
        val label = when {
            ratio >= 0.45 -> "high"
            ratio >= 0.20 -> "medium"
            else -> "low"
        }
        return PrivacyAndRiskNotes(
            highSensitivityRatio = label,
            avoidTopics = avoidTopics,
            profileGenerationMode = "filtered_evidence_based_v2"
        )
    }

    private fun deriveRecentTopics(entries: List<ParsedImageMemory>): List<String> {
        val latestTimestamp = entries.mapNotNull { it.timestamp }.maxOrNull() ?: return listOf("unknown")
        val recentEntries = entries.filter { entry ->
            entry.timestamp != null && entry.timestamp.isAfter(latestTimestamp.minusDays(7))
        }
        if (recentEntries.isEmpty()) {
            return listOf("unknown")
        }
        val topicCounts = recentEntries.flatMap { it.tags }
            .groupingBy { topicMapping[it] ?: it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .map { it.key }
        return if (topicCounts.isEmpty()) listOf("unknown") else topicCounts
    }

    private fun buildLastSyncSummary(entries: List<ParsedImageMemory>): String {
        if (entries.isEmpty()) {
            return "No image-memory sync results yet."
        }
        val latestEntry = entries.maxByOrNull { it.timestamp ?: LocalDateTime.MIN }
        val latestTimeText = latestEntry?.timestamp?.format(MEMORY_TIME_FORMATTER) ?: "unknown"
        return "Since the last sync, ${entries.size} image memories were included; newest image time is $latestTimeText."
    }

    private fun deriveRecentFocuses(
        entries: List<ParsedImageMemory>,
        recentLogs: List<String>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val focuses = entries.takeLast(3).map { it.summary }.filter { it.isNotBlank() }.toMutableList()
        if (recentLogs.isNotEmpty()) {
            focuses += "${recentLogs.size} recent log updates exist; consult the daily log for finer context when needed."
        }
        focuses += taskSignals.recentTaskHighlights
        return focuses.distinct().take(4)
    }

    private fun parseTaskMemorySignals(
        longTermMemory: String,
        recentLogs: List<String>
    ): TaskMemorySignals {
        val combined = (longTermMemory + "\n" + recentLogs.joinToString("\n")).lowercase(Locale.getDefault())
        val preferredTopics = mutableListOf<String>()
        val recurringNeeds = mutableListOf<String>()
        val contentPreferences = mutableListOf<String>()
        val responseStyleHints = mutableListOf<String>()
        val importantHints = mutableListOf<String>()
        val riskNotes = mutableListOf<String>()
        val highlights = longTermMemory.lines()
            .filter { it.startsWith("- ") && !it.contains("No stable memory yet") }
            .takeLast(6)
            .map { it.removePrefix("- ").take(180) }

        if (combined.contains("小红书") || combined.contains("新闻") || combined.contains("资讯")) {
            preferredTopics += "News summarization"
            recurringNeeds += "In-app search and summarization"
            contentPreferences += "Prefers news-type content"
        }
        if (combined.contains("定时") || combined.contains("全局记忆进化")) {
            recurringNeeds += "Scheduled automation"
            importantHints += "The user runs long-term automation via scheduled tasks; keep task instructions repeatable and diagnosable."
        }
        if (combined.contains("相册") || combined.contains("image-memories")) {
            recurringNeeds += "Album memory maintenance"
        }
        if (combined.contains("简洁") || combined.contains("中文")) {
            responseStyleHints += "Chinese / concise"
        }
        if (combined.contains("coloros") || combined.contains("息屏") || combined.contains("权限")) {
            riskNotes += "Android permissions, screen-off, and ROM background limits"
            importantHints += "For background, screen-off, or cross-app automation, check permissions and system limits first."
        }

        return TaskMemorySignals(
            preferredTaskTopics = preferredTopics.distinct(),
            recurringNeeds = recurringNeeds.distinct(),
            contentPreferences = contentPreferences.distinct(),
            responseStyleHints = responseStyleHints.distinct(),
            importantHints = importantHints.distinct(),
            riskNotes = riskNotes.distinct(),
            recentTaskHighlights = highlights
        )
    }

    private data class ParsedImageMemory(
        val displayName: String,
        val bucketName: String?,
        val tags: List<String>,
        val summary: String,
        val sensitivity: String,
        val extractedText: String,
        val timestamp: LocalDateTime?
    )

    private data class TaskMemorySignals(
        val preferredTaskTopics: List<String>,
        val recurringNeeds: List<String>,
        val contentPreferences: List<String>,
        val responseStyleHints: List<String>,
        val importantHints: List<String>,
        val riskNotes: List<String>,
        val recentTaskHighlights: List<String>
    )
}
