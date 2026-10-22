package com.shijing.xomniclaw.scheduler

import java.util.Locale

/**
 * 自然语言调度解析器。
 *
 * 目标是把“每周三早上10点”“每个工作日上午10点”“每隔45分钟”这类口语，
 * 转成底层调度系统可直接消费的结构化规则。
 */
object NaturalLanguageScheduleParser {
    data class ParsedSchedule(
        val repeat: String,
        val dailyTime: String? = null,
        val daysOfWeek: List<Int>? = null,
        val intervalMinutes: Int? = null
    )

    private val intervalMinutePattern = Regex("^每隔\\s*(\\d+)\\s*(分钟|分)$")
    private val intervalHourPattern = Regex("^每隔\\s*(\\d+)\\s*(个)?小时$")
    private val workdayPattern = Regex("^(每个?工作日|每逢工作日|工作日)\\s*(.+)$")
    private val dailyPattern = Regex("^(每天|每日)\\s*(.+)$")
    private val weeklyPattern = Regex("^(每周|每星期|周)([一二三四五六日天1-7,，、\\s和及]+)\\s*(.+)$")

    // English phrases are matched too: the agent now emits English schedule_phrase
    // with the English UI. Chinese stays first so existing users are unaffected.
    private val intervalMinutePatternEn = Regex("^every\\s+(\\d+)\\s*(minutes?|mins?)$")
    private val intervalHourPatternEn = Regex("^every\\s+(\\d+)\\s*(hours?|hrs?)$")
    private val workdayPatternEn = Regex("^(?:every\\s+workday|each\\s+workday|weekdays?)\\s*(?:at\\s+)?(.+)$")
    private val dailyPatternEn = Regex("^(?:every\\s*day|each\\s+day|daily)\\s*(?:at\\s+)?(.+)$")
    // Only real weekday names open this branch, so "every day at 10" stays daily.
    // Strictly longest-first: Regex alternation is ordered, so a short form such
    // as "thurs" would otherwise swallow the prefix of "thursday".
    private val weekdayTokenEn =
        "(?:wednesday|thursday|saturday|monday|friday|sunday|tues|weds|thurs|thur|wed|thu|fri|sat|sun|mon|tue)"
    private val weeklyPatternEn = Regex(
        "^(?:every|each|on|weekly\\s+on)\\s+" +
            "($weekdayTokenEn(?:\\s*(?:,|&|/|and)\\s*$weekdayTokenEn)*)\\s*(?:at\\s+)?(.+)$"
    )

    /**
     * Parse a natural-language schedule phrase.
     *
     * Chinese: 每天晚上12点 / 每周三早上10点 / 每周一、三、五早上10点 /
     * 每个工作日上午10点 / 每隔45分钟 / 每隔2小时 / 每隔半小时.
     * English: every day at 10pm / every Wednesday at 10am /
     * every Monday, Wednesday and Friday at 8:30 pm / every workday at 10am /
     * every 45 minutes / every 2 hours / every half hour.
     *
     * A bare time such as "早上10点" or "10am" defaults to daily, as before.
     */
    fun parse(input: String): ParsedSchedule {
        val normalized = normalize(input)
        if (normalized.isBlank()) {
            throw IllegalArgumentException("time_text cannot be blank")
        }

        parseInterval(normalized)?.let { return it }

        weeklyPattern.matchEntire(normalized)?.let { match ->
            val daysOfWeek = parseWeekdayExpression(match.groupValues[2])
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[3])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_WEEKLY,
                dailyTime = dailyTime,
                daysOfWeek = daysOfWeek
            )
        }

        weeklyPatternEn.matchEntire(normalized)?.let { match ->
            val daysOfWeek = parseWeekdayExpression(match.groupValues[1])
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[2])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_WEEKLY,
                dailyTime = dailyTime,
                daysOfWeek = daysOfWeek
            )
        }

        workdayPattern.matchEntire(normalized)?.let { match ->
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[2])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_WORKDAY,
                dailyTime = dailyTime,
                daysOfWeek = listOf(1, 2, 3, 4, 5)
            )
        }

        workdayPatternEn.matchEntire(normalized)?.let { match ->
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[1])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_WORKDAY,
                dailyTime = dailyTime,
                daysOfWeek = listOf(1, 2, 3, 4, 5)
            )
        }

        dailyPattern.matchEntire(normalized)?.let { match ->
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[2])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_DAILY,
                dailyTime = dailyTime
            )
        }

        dailyPatternEn.matchEntire(normalized)?.let { match ->
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[1])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_DAILY,
                dailyTime = dailyTime
            )
        }

        // Legacy entry point: a bare time ("早上10点", "10am") means daily.
        return ParsedSchedule(
            repeat = ScheduledTask.REPEAT_DAILY,
            dailyTime = NaturalLanguageTimeParser.parseToDailyTime(normalized)
        )
    }

    private fun parseInterval(normalized: String): ParsedSchedule? {
        if (normalized == "每隔半小时") {
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = 30
            )
        }

        intervalMinutePattern.matchEntire(normalized)?.let { match ->
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = match.groupValues[1].toInt()
            )
        }

        intervalHourPattern.matchEntire(normalized)?.let { match ->
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = match.groupValues[1].toInt() * 60
            )
        }

        if (normalized == "every half hour" || normalized == "every half an hour") {
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = 30
            )
        }

        intervalMinutePatternEn.matchEntire(normalized)?.let { match ->
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = match.groupValues[1].toInt()
            )
        }

        intervalHourPatternEn.matchEntire(normalized)?.let { match ->
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = match.groupValues[1].toInt() * 60
            )
        }

        return null
    }

    /**
     * Parse weekday expressions such as "周一、三、五", "135", "一三五",
     * "wednesday", or "mon, wed and fri".
     */
    private fun parseWeekdayExpression(expression: String): List<Int> {
        val compact = expression.replace("星期", "").replace("周", "").trim()
        if (compact.isBlank()) {
            throw IllegalArgumentException("Weekly schedule is missing weekday expression")
        }

        val parts = compact.split(Regex("[,，、\\s和及/&]+"))
            .filter { it.isNotBlank() && it.lowercase() != "and" }

        // Single token: "135" / "一三五" / "wednesday" all resolve through the shared table.
        val tokens = if (parts.size > 1) parts else expandCompactWeekdayTokens(compact)

        val days = tokens.map { token ->
            ScheduledTaskWeekdayParser.dayOf(token)
                ?: throw IllegalArgumentException("Unsupported weekday expression: $expression")
        }

        return ScheduledTaskTimeResolver.normalizeDaysOfWeek(days)
    }

    private fun expandCompactWeekdayTokens(compact: String): List<String> {
        if (compact.isBlank()) {
            throw IllegalArgumentException("Weekly schedule is missing weekday expression")
        }

        return when {
            compact.all { it in "1234567" } -> compact.map { it.toString() }
            compact.all { it in "一二三四五六日天" } -> compact.map { it.toString() }
            else -> listOf(compact)
        }
    }

    private fun normalize(input: String): String {
        return input.trim()
            .replace("：", ":")
            .replace("个工作日", "工作日")
            .lowercase(Locale.getDefault())
    }
}
