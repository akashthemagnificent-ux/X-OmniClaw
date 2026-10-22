package com.shijing.xomniclaw.scheduler

import java.util.Locale
import java.util.regex.Pattern

/**
 * Natural-language time parser.
 *
 * Handles both the Chinese colloquial forms the app shipped with
 * ("晚上12点", "中午12点", "早上8点半") and the English forms the agent emits
 * ("10am", "8:30 pm", "noon"), returning a uniform `HH:mm` for the scheduler.
 */
object NaturalLanguageTimeParser {
    private val hhmmPattern = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val chineseTimePattern = Pattern.compile(
        "^(凌晨|早上|早晨|上午|中午|下午|晚上|傍晚|夜里|半夜)?\\s*(\\d{1,2})\\s*点\\s*(半|(\\d{1,2})\\s*分?)?$"
    )

    // English forms the agent emits with the English UI: "10am", "8:30 pm", "noon".
    private val englishTimePattern = Pattern.compile(
        "(?i)^(\\d{1,2})(?::([0-5]\\d))?\\s*([ap])\\.?m\\.?$"
    )
    private val englishNoonPattern = Pattern.compile("(?i)^(noon|midday|midnight)$")

    fun parseToDailyTime(input: String): String {
        val normalized = input.trim().replace("：", ":").lowercase(Locale.getDefault())
        if (normalized.isBlank()) {
            throw IllegalArgumentException("time_text cannot be blank")
        }

        hhmmPattern.matcher(normalized).takeIf { it.matches() }?.let { matcher ->
            val hour = matcher.group(1).toInt()
            val minute = matcher.group(2).toInt()
            return formatHourMinute(hour, minute)
        }

        if (englishNoonPattern.matcher(normalized).matches()) {
            return if (normalized == "midnight") "00:00" else "12:00"
        }

        englishTimePattern.matcher(normalized).takeIf { it.matches() }?.let { matcher ->
            val rawHour = matcher.group(1).toInt()
            val minute = matcher.group(2)?.toInt() ?: 0
            if (rawHour !in 1..12) {
                throw IllegalArgumentException("Hour must be between 1 and 12 with am/pm")
            }
            val isPm = matcher.group(3).equals("p", ignoreCase = true)
            val hour = (rawHour % 12) + if (isPm) 12 else 0
            return formatHourMinute(hour, minute)
        }

        val matcher = chineseTimePattern.matcher(normalized)
        if (!matcher.matches()) {
            throw IllegalArgumentException(
                "Unsupported time_text. Try formats like '00:00', '10:00', '10am', '23:30', " +
                    "'晚上12点', '中午12点', '早上8点半'"
            )
        }

        val period = matcher.group(1)
        val rawHour = matcher.group(2).toInt()
        val halfKeyword = matcher.group(3)
        val explicitMinute = matcher.group(4)?.toInt()

        val minute = when {
            !halfKeyword.isNullOrBlank() && halfKeyword.contains("半") -> 30
            explicitMinute != null -> explicitMinute
            else -> 0
        }

        val hour = normalizeHour(period, rawHour)
        return formatHourMinute(hour, minute)
    }

    private fun normalizeHour(period: String?, rawHour: Int): Int {
        if (rawHour !in 0..23) {
            throw IllegalArgumentException("Hour must be between 0 and 23")
        }

        return when (period) {
            "凌晨", "半夜", "夜里" -> if (rawHour == 12) 0 else rawHour
            "早上", "早晨", "上午" -> if (rawHour == 12) 0 else rawHour
            "中午" -> when (rawHour) {
                in 0..10 -> rawHour + 12
                11, 12 -> 12
                else -> rawHour
            }
            "下午", "晚上", "傍晚" -> when (rawHour) {
                in 1..11 -> rawHour + 12
                12 -> if (period == "下午") 12 else 0
                else -> rawHour
            }
            else -> rawHour
        }
    }

    private fun formatHourMinute(hour: Int, minute: Int): String {
        if (minute !in 0..59) {
            throw IllegalArgumentException("Minute must be between 0 and 59")
        }
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }
}
