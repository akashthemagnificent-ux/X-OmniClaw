package com.shijing.xomniclaw.agent.tools

import com.shijing.xomniclaw.scheduler.ScheduledTask
import java.util.Locale

/**
 * `schedule_app_task` 的歧义识别器。
 *
 * 这层不负责真正创建任务，只负责判断当前参数是否足够明确：
 * 1. App 是否明确；
 * 2. 操作是否仍然停留在占位表达；
 * 3. 调度信息是否完整且可继续解析。
 */
object ScheduleAppTaskClarifier {
    data class ClarificationRequest(
        val questions: List<String>,
        val reasonCodes: List<String>
    )

    private val placeholderAppTokens = setOf(
        "app", "某app", "某个app", "某应用", "应用", "软件", "那个app", "这个app",
        "some app", "an app", "the app", "that app", "this app", "some application"
    )

    private val placeholderOperationPatterns = listOf(
        Regex("^x+$", RegexOption.IGNORE_CASE),
        Regex("^x\\s*操作$", RegexOption.IGNORE_CASE),
        Regex("^xx+$", RegexOption.IGNORE_CASE),
        Regex("某操作"),
        Regex("某个操作"),
        Regex("什么操作"),
        Regex("待定"),
        Regex("^some operation$", RegexOption.IGNORE_CASE),
        Regex("^the operation$", RegexOption.IGNORE_CASE),
        Regex("^do (?:something|stuff)$", RegexOption.IGNORE_CASE),
        Regex("^(?:tbd|todo|n/?a)$", RegexOption.IGNORE_CASE)
    )

    private val vagueScheduleTokens = listOf(
        "定时", "稍后", "一会", "过会", "有空", "隔一阵子", "上班前", "下班前", "晚点", "早一点",
        "later", "sometime", "soon", "in a while", "when free", "before work", "after work", "eventually"
    )

    /**
     * 根据当前提取结果给出追问建议。
     */
    fun inspect(
        appName: String?,
        operation: String?,
        schedulePhrase: String?,
        repeat: String?,
        daysOfWeekProvided: Boolean,
        intervalMinutesProvided: Boolean,
        runAtProvided: Boolean
    ): ClarificationRequest? {
        val questions = mutableListOf<String>()
        val reasonCodes = mutableListOf<String>()

        if (appName.isNullOrBlank() || looksLikePlaceholderApp(appName)) {
            questions += "Which app should be opened? Give the app name, e.g. \"Xiaohongshu\" or \"WeCom\"."
            reasonCodes += "ambiguous_app_name"
        }

        if (looksLikePlaceholderOperation(operation)) {
            questions += "What exactly should be done after opening the app? Describe the action, e.g. \"search the news and summarize the top three results\"."
            reasonCodes += "ambiguous_operation"
        }

        val normalizedRepeat = repeat?.trim()?.lowercase(Locale.getDefault())
        val normalizedSchedulePhrase = schedulePhrase?.trim().orEmpty()

        when (normalizedRepeat) {
            null, "" -> {
                if (normalizedSchedulePhrase.isBlank()) {
                    questions += "When should the task run? Give an explicit time, e.g. \"every day at 10pm\" or \"every 45 minutes\"."
                    reasonCodes += "missing_schedule_phrase"
                }
            }

            ScheduledTask.REPEAT_ONCE -> {
                if (!runAtProvided) {
                    questions += "This is a one-time task. Provide the exact run time, or a delay in seconds."
                    reasonCodes += "missing_one_time_trigger"
                }
            }

            ScheduledTask.REPEAT_DAILY,
            ScheduledTask.REPEAT_WORKDAY -> {
                if (normalizedSchedulePhrase.isBlank()) {
                    questions += "Add an explicit time, e.g. \"every day at 10pm\" or \"every workday at 10am\"."
                    reasonCodes += "missing_schedule_phrase"
                }
            }

            ScheduledTask.REPEAT_WEEKLY -> {
                if (normalizedSchedulePhrase.isBlank() && !daysOfWeekProvided) {
                    questions += "Add the weekday and time, e.g. \"every Wednesday at 10am\"."
                    reasonCodes += "missing_weekly_schedule"
                }
            }

            ScheduledTask.REPEAT_INTERVAL -> {
                if (normalizedSchedulePhrase.isBlank() && !intervalMinutesProvided) {
                    questions += "Add the run interval, e.g. \"every 45 minutes\", or pass interval_minutes directly."
                    reasonCodes += "missing_interval_schedule"
                }
            }
        }

        if (normalizedSchedulePhrase.isNotBlank() && looksLikeVagueSchedule(normalizedSchedulePhrase)) {
            questions += "That time expression is not precise enough. Use something actionable, e.g. \"every Wednesday at 10am\" or \"every 45 minutes\"."
            reasonCodes += "ambiguous_schedule_phrase"
        }

        if (questions.isEmpty()) {
            return null
        }

        return ClarificationRequest(
            questions = questions.distinct(),
            reasonCodes = reasonCodes.distinct()
        )
    }

    /**
     * 生成给 LLM 和用户都容易理解的追问文本。
     */
    fun buildResponse(request: ClarificationRequest): String {
        return buildString {
            appendLine("Need clarification before creating the scheduled app task.")
            request.questions.forEachIndexed { index, question ->
                appendLine("${index + 1}. $question")
            }
            append("Confirm the details above and I will create the task.")
        }
    }

    private fun looksLikePlaceholderApp(appName: String): Boolean {
        return appName.trim().lowercase(Locale.getDefault()) in placeholderAppTokens
    }

    private fun looksLikePlaceholderOperation(operation: String?): Boolean {
        val normalized = operation?.trim().orEmpty()
        if (normalized.isBlank()) {
            return false
        }

        return placeholderOperationPatterns.any { it.containsMatchIn(normalized) }
    }

    private fun looksLikeVagueSchedule(schedulePhrase: String): Boolean {
        val normalized = schedulePhrase.trim()
        return vagueScheduleTokens.any { token -> normalized.contains(token) }
    }
}
