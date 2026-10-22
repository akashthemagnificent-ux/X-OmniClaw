package com.shijing.xomniclaw.selfcontrol

/**
 * Upstream reference (OmniClaw):
 * - ../omniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.selfcontrol.Skill
import com.shijing.xomniclaw.selfcontrol.SkillResult
import com.shijing.xomniclaw.selfcontrol.FunctionDefinition
import com.shijing.xomniclaw.selfcontrol.ParametersSchema
import com.shijing.xomniclaw.selfcontrol.PropertySchema
import com.shijing.xomniclaw.selfcontrol.ToolDefinition
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Self-Control Log Query Skill
 *
 * 查询 PhoneForClaw 的运行日志，让 AI Agent 能够：
 * - 读取应用日志（logcat）
 * - 查询错误和异常
 * - 分析运行状态
 * - 自我诊断问题
 *
 * 使用场景：
 * - 调试失败的操作
 * - 分析崩溃原因
 * - 性能监控
 * - 自动化问题诊断
 */
class LogQuerySkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "LogQuerySkill"
        private const val MAX_LOG_LINES = 200

        object LogLevel {
            const val VERBOSE = "V"
            const val DEBUG = "D"
            const val INFO = "I"
            const val WARN = "W"
            const val ERROR = "E"
            const val FATAL = "F"
        }
    }

    override val name = "query_logs"

    override val description = """
        Query PhoneForClaw runtime logs.

        Parameters:
        - level: log level (V/D/I/W/E/F), default I (Info and above)
        - filter: keyword filter (TAG or message content)
        - lines: number of lines, default 100 (max 200)
        - source: log source (logcat/file), default logcat

        Log levels:
        - V: Verbose
        - D: Debug
        - I: Info
        - W: Warning
        - E: Error
        - F: Fatal

        Examples:
        - recent errors: {"level": "E", "lines": 50}
        - search a TAG: {"filter": "AgentLoop", "lines": 100}
        - all logs: {"level": "V", "lines": 200}

        Note: requires READ_LOGS (System UID).
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "level" to PropertySchema(
                            type = "string",
                            description = "log level",
                            enum = listOf(
                                LogLevel.VERBOSE,
                                LogLevel.DEBUG,
                                LogLevel.INFO,
                                LogLevel.WARN,
                                LogLevel.ERROR,
                                LogLevel.FATAL
                            )
                        ),
                        "filter" to PropertySchema(
                            type = "string",
                            description = "keyword filter (TAG or message content)"
                        ),
                        "lines" to PropertySchema(
                            type = "integer",
                            description = "number of lines (1-200)"
                        ),
                        "source" to PropertySchema(
                            type = "string",
                            description = "log source",
                            enum = listOf("logcat", "file")
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val level = args["level"] as? String ?: LogLevel.INFO
        val filter = args["filter"] as? String
        val lines = ((args["lines"] as? Number)?.toInt() ?: 100).coerceIn(1, MAX_LOG_LINES)
        val source = args["source"] as? String ?: "logcat"

        return try {
            when (source) {
                "logcat" -> queryLogcat(level, filter, lines)
                "file" -> queryLogFile(filter, lines)
                else -> SkillResult.error("Unknown log source: $source")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query logs", e)
            SkillResult.error("Log query failed: ${e.message}")
        }
    }

    private fun queryLogcat(level: String, filter: String?, lines: Int): SkillResult {
        return try {
            val packageName = context.packageName
            val levelFilter = when (level) {
                LogLevel.VERBOSE -> "*:V"
                LogLevel.DEBUG -> "*:D"
                LogLevel.INFO -> "*:I"
                LogLevel.WARN -> "*:W"
                LogLevel.ERROR -> "*:E"
                LogLevel.FATAL -> "*:F"
                else -> "*:I"
            }

            // 构建 logcat 命令
            val command = mutableListOf(
                "logcat",
                "-d",                           // dump 模式
                "-t", lines.toString(),         // 最后 N 行
                levelFilter,                    // 日志级别
                "--pid=${android.os.Process.myPid()}"  // 仅本进程
            )

            // 执行命令
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val logLines = mutableListOf<String>()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // 应用过滤器
                if (filter != null && !currentLine.contains(filter, ignoreCase = true)) {
                    continue
                }

                logLines.add(currentLine)
            }

            reader.close()
            process.waitFor()

            if (logLines.isEmpty()) {
                return SkillResult.success(
                    "No matching logs found${if (filter != null) " (filter: $filter)" else ""}",
                    mapOf("count" to 0)
                )
            }

            val summary = buildString {
                appendLine("[Log query result]")
                appendLine("Level: $level")
                if (filter != null) {
                    appendLine("Filter: $filter")
                }
                appendLine("Lines: ${logLines.size}")
                appendLine()
                appendLine("--- Log content ---")
                logLines.takeLast(lines).forEach { appendLine(it) }
            }

            SkillResult.success(
                summary,
                mapOf(
                    "level" to level,
                    "filter" to (filter ?: "none"),
                    "count" to logLines.size,
                    "lines" to logLines.takeLast(lines)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query logcat", e)
            SkillResult.error("Logcat query failed: ${e.message}")
        }
    }

    private fun queryLogFile(filter: String?, lines: Int): SkillResult {
        return try {
            // 尝试读取应用日志文件（如果存在）
            val logDir = File(context.getExternalFilesDir(null), "logs")

            if (!logDir.exists() || !logDir.isDirectory) {
                return SkillResult.error("Log directory does not exist: ${logDir.absolutePath}")
            }

            val logFiles = logDir.listFiles { file ->
                file.isFile && file.extension == "log"
            }?.sortedByDescending { it.lastModified() }

            if (logFiles.isNullOrEmpty()) {
                return SkillResult.error("No log files found")
            }

            // 读取最新的日志文件
            val latestLog = logFiles.first()
            val logLines = mutableListOf<String>()

            latestLog.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (filter == null || line.contains(filter, ignoreCase = true)) {
                        logLines.add(line)
                    }
                }
            }

            if (logLines.isEmpty()) {
                return SkillResult.success(
                    "No matching content in the log file${if (filter != null) " (filter: $filter)" else ""}",
                    mapOf("file" to latestLog.name, "count" to 0)
                )
            }

            val summary = buildString {
                appendLine("[Log file query result]")
                appendLine("File: ${latestLog.name}")
                appendLine("Size: ${latestLog.length() / 1024} KB")
                appendLine("Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(latestLog.lastModified())}")
                if (filter != null) {
                    appendLine("Filter: $filter")
                }
                appendLine("Matching lines: ${logLines.size}")
                appendLine()
                appendLine("--- Log content (last $lines lines) ---")
                logLines.takeLast(lines).forEach { appendLine(it) }
            }

            SkillResult.success(
                summary,
                mapOf(
                    "file" to latestLog.name,
                    "filter" to (filter ?: "none"),
                    "count" to logLines.size,
                    "lines" to logLines.takeLast(lines)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query log file", e)
            SkillResult.error("Log file query failed: ${e.message}")
        }
    }
}
