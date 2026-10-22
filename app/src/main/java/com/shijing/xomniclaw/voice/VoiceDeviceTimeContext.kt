package com.shijing.xomniclaw.voice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 将设备本地日期、星期、时刻注入 VLM，避免模型声称「无法获取实时日期」。
 */
internal object VoiceDeviceTimeContext {

    private val WEEKDAYS = arrayOf(
        "",
        "Sunday",
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday",
    )

    /**
     * 短 system 片段，与主提示词并列下发。
     */
    fun buildSystemLine(): String {
        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(cal.time)
        val weekday = WEEKDAYS.getOrElse(cal.get(Calendar.DAY_OF_WEEK)) { "" }
        return "[Device local time] $dateStr $weekday $timeStr. If the user asks what day/date/time it is, answer briefly and conversationally from the information above; never claim you cannot get the current time."
    }
}
