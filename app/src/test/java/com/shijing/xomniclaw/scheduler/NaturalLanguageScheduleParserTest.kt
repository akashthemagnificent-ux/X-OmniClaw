package com.shijing.xomniclaw.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The agent now emits English `schedule_phrase` values; Chinese must keep working.
 */
class NaturalLanguageScheduleParserTest {

    private fun parse(input: String) = NaturalLanguageScheduleParser.parse(input)

    @Test
    fun englishDaily() {
        val s = parse("every day at 10pm")
        assertEquals(ScheduledTask.REPEAT_DAILY, s.repeat)
        assertEquals("22:00", s.dailyTime)
    }

    @Test
    fun englishDailyIsNotWeekly() {
        val s = parse("everyday at 09:30")
        assertEquals(ScheduledTask.REPEAT_DAILY, s.repeat)
        assertEquals("09:30", s.dailyTime)
    }

    @Test
    fun englishWeeklySingleDay() {
        val s = parse("every Wednesday at 10am")
        assertEquals(ScheduledTask.REPEAT_WEEKLY, s.repeat)
        assertEquals("10:00", s.dailyTime)
        assertEquals(listOf(3), s.daysOfWeek)
    }

    @Test
    fun englishWeeklyMultipleDays() {
        val s = parse("every Monday, Wednesday and Friday at 8:30 pm")
        assertEquals(ScheduledTask.REPEAT_WEEKLY, s.repeat)
        assertEquals("20:30", s.dailyTime)
        assertEquals(listOf(1, 3, 5), s.daysOfWeek)
    }

    @Test
    fun englishWorkday() {
        val s = parse("every workday at 10am")
        assertEquals(ScheduledTask.REPEAT_WORKDAY, s.repeat)
        assertEquals(listOf(1, 2, 3, 4, 5), s.daysOfWeek)
    }

    @Test
    fun englishInterval() {
        assertEquals(45, parse("every 45 minutes").intervalMinutes ?: 0)
        assertEquals(120, parse("every 2 hours").intervalMinutes ?: 0)
        assertEquals(30, parse("every half hour").intervalMinutes ?: 0)
    }

    @Test
    fun chineseStillWorks() {
        val weekly = parse("每周三早上10点")
        assertEquals(ScheduledTask.REPEAT_WEEKLY, weekly.repeat)
        assertEquals("10:00", weekly.dailyTime)
        assertEquals(listOf(3), weekly.daysOfWeek)

        assertEquals("22:00", parse("每天晚上10点").dailyTime)
        assertEquals(45, parse("每隔45分钟").intervalMinutes ?: 0)
        assertEquals(ScheduledTask.REPEAT_WORKDAY, parse("每个工作日上午10点").repeat)
    }
}
