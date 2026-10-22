package com.shijing.xomniclaw.agent.skills

import com.shijing.xomniclaw.accessibility.service.AccessibilityEventDispatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把用户行为记录结果沉淀为工作区 Skill。
 *
 * 这里直接写 `workspace/skills/<slug>/SKILL.md`，这样 SkillsLoader 能立即识别并加载。
 */
object BehaviorSkillWriter {
    private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
    private val readableFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    data class WriteResult(
        val skillName: String,
        val skillDir: File,
        val skillFile: File
    )

    fun writeBehaviorSkill(
        events: List<AccessibilityEventDispatcher.RecordedAccessibilityEvent>
    ): WriteResult {
        val startedAt = events.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        val skillName = "behavior-record-${timestampFormatter.format(Date(startedAt))}"
        val skillDir = File(WORKSPACE_SKILLS_DIR, skillName)
        if (!skillDir.exists()) {
            skillDir.mkdirs()
        }
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText(buildSkillMarkdown(skillName, events))
        return WriteResult(
            skillName = skillName,
            skillDir = skillDir,
            skillFile = skillFile
        )
    }

    private fun buildSkillMarkdown(
        skillName: String,
        events: List<AccessibilityEventDispatcher.RecordedAccessibilityEvent>
    ): String {
        val eventLines = if (events.isEmpty()) {
            listOf("- No usable behavior events were captured this time.")
        } else {
            events.mapIndexed { index, event ->
                val timeLabel = readableFormatter.format(Date(event.timestampMs))
                buildString {
                    append("${index + 1}. Time: $timeLabel")
                    append("\n   - Action: ${event.actionSummary}")
                    append("\n   - Event type: ${event.eventTypeName}")
                    append("\n   - Package: ${event.packageName.ifBlank { "(empty)" }}")
                    append("\n   - Page class: ${event.className.ifBlank { "(empty)" }}")
                    append("\n   - Text: ${event.text.ifBlank { "(empty)" }}")
                    append("\n   - Description: ${event.contentDescription.ifBlank { "(empty)" }}")
                }
            }
        }

        return buildString {
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: Auto-generated from the user behavior log; use it to review real operation steps.")
            appendLine("---")
            appendLine()
            appendLine("# Behavior recording skill")
            appendLine()
            appendLine("## Purpose")
            appendLine()
            appendLine("- This skill is generated automatically by OmniClaw to review the user's real operations.")
            appendLine("- When a later task is similar to this one, read this skill to reference the recorded steps, screen transitions, and input behavior.")
            appendLine()
            appendLine("## Recorded result")
            appendLine()
            eventLines.forEach { line ->
                appendLine(line)
                appendLine()
            }
            appendLine("## Usage notes")
            appendLine()
            appendLine("- First follow the recorded screen changes, taps, input, and scrolling in order.")
            appendLine("- If the UI structure changed a lot, treat this record as a reference, not an absolute script.")
        }
    }
}
