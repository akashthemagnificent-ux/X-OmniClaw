package com.shijing.xomniclaw.selfcontrol

/**
 * Upstream reference (OmniClaw):
 * - ../omniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log

/**
 * Internal Self-Control Skill
 *
 * 让 PhoneForClaw 的 AI Agent 能够调用自己的 Self-Control 功能。
 *
 * 这是一个元级别（meta-level）的 Skill：
 * - Agent 通过这个 Skill 调用其他 Self-Control Skills
 * - 实现 "AI 控制 AI" 的自我管理能力
 * - 支持链式调用和批量操作
 *
 * 使用场景：
 * 1. **自我诊断**
 *    Agent 检测到问题 → 调用 query_logs → 分析错误 → 调用 manage_config 调整参数
 *
 * 2. **自我调优**
 *    Agent 发现性能问题 → 调用 manage_config 读取当前配置 → 计算最优参数 → 调用 manage_config 更新
 *
 * 3. **自我开发**
 *    Agent 需要修改配置 → 调用 navigate_app 打开配置页面 → 等待用户确认
 *
 * 4. **任务执行优化**
 *    截图前 → 调用 control_service 隐藏悬浮窗 → 执行截图 → 调用 control_service 显示悬浮窗
 *
 * 示例：
 * ```json
 * // 单个调用
 * {
 *   "skill": "navigate_app",
 *   "args": {"page": "config"}
 * }
 *
 * // 链式调用（按顺序执行）
 * {
 *   "skills": [
 *     {"skill": "control_service", "args": {"operation": "hide_float"}},
 *     {"skill": "wait", "args": {"duration": 100}},
 *     {"skill": "screenshot", "args": {}},
 *     {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
 *   ]
 * }
 *
 * // 条件调用（根据结果决定）
 * {
 *   "skill": "query_logs",
 *   "args": {"level": "E", "lines": 50},
 *   "on_success": {
 *     "skill": "analyze_and_fix"
 *   }
 * }
 * ```
 */
class InternalSelfControlSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "InternalSelfControl"
    }

    // 内部 Registry 实例（延迟初始化）
    private val registry by lazy { SelfControlRegistry(context) }

    override val name = "self_control"

    override val description = """
        Let the AI Agent call its own Self-Control capabilities.

        A meta-level skill that lets the Agent control and manage itself:
        - call other Self-Control skills
        - supports single and chained calls
        - supports batch operations

        Available skills:
        - navigate_app: page navigation
        - manage_config: config management (get/set/list/delete)
        - control_service: service control (hide/show/check_status)
        - query_logs: log query

        Usage:

        1. Single call
        {
          "skill": "navigate_app",
          "args": {"page": "config"}
        }

        2. Chained call (run several skills in order)
        {
          "skills": [
            {"skill": "control_service", "args": {"operation": "hide_float"}},
            {"skill": "screenshot", "args": {}},
            {"skill": "control_service", "args": {"operation": "show_float"}}
          ]
        }

        3. Batch call (run in parallel)
        {
          "parallel": true,
          "skills": [
            {"skill": "query_logs", "args": {"level": "E"}},
            {"skill": "control_service", "args": {"operation": "check_status"}}
          ]
        }

        Typical scenarios:

        **场景 1: 截图前后处理**
        ```
        {
          "skills": [
            {"skill": "control_service", "args": {"operation": "hide_float"}},
            {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
          ]
        }
        ```

        **场景 2: 自我诊断**
        ```
        {
          "skills": [
            {"skill": "query_logs", "args": {"level": "E", "lines": 50}},
            {"skill": "control_service", "args": {"operation": "check_status"}},
            {"skill": "manage_config", "args": {"operation": "list", "category": "feature"}}
          ]
        }
        ```

        **场景 3: 配置调优**
        ```
        {
          "skills": [
            {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}},
            {"skill": "manage_config", "args": {"operation": "set", "key": "screenshot_delay", "value": "200"}},
            {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}}
          ]
        }
        ```

        Notes:
        - chained calls run in order
        - if one skill fails you may continue or stop
        - nested calls are supported (use carefully to avoid infinite recursion)
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
                        "skill" to PropertySchema(
                            type = "string",
                            description = "single skill name"
                        ),
                        "args" to PropertySchema(
                            type = "object",
                            description = "skill arguments"
                        ),
                        "skills" to PropertySchema(
                            type = "array",
                            description = "multiple skills (chained or parallel)"
                        ),
                        "parallel" to PropertySchema(
                            type = "string",
                            description = "run in parallel (only for skills)"
                        ),
                        "continue_on_error" to PropertySchema(
                            type = "string",
                            description = "continue on error (only for skills)"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        return try {
            when {
                // 单个 Skill 调用
                args.containsKey("skill") -> {
                    executeSingleSkill(args)
                }

                // 多个 Skills 调用
                args.containsKey("skills") -> {
                    executeMultipleSkills(args)
                }

                else -> {
                    SkillResult.error("Missing 'skill' or 'skills' parameter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Self-control execution failed", e)
            SkillResult.error("Self-control failed: ${e.message}")
        }
    }

    /**
     * 执行单个 Skill
     */
    private suspend fun executeSingleSkill(args: Map<String, Any?>): SkillResult {
        val skillName = args["skill"] as? String
            ?: return SkillResult.error("Missing 'skill' parameter")

        val skillArgs = (args["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap()

        Log.d(TAG, "Executing single skill: $skillName with args: $skillArgs")

        // 防止递归调用自己
        if (skillName == name) {
            return SkillResult.error("Cannot recursively call self_control skill")
        }

        // 调用目标 Skill
        val result = registry.execute(skillName, skillArgs)
            ?: return SkillResult.error("Unknown skill: $skillName")

        return if (result.success) {
            SkillResult.success(
                buildString {
                    appendLine("[Self-Control] Successfully ran $skillName")
                    appendLine()
                    appendLine(result.content)
                },
                mapOf(
                    "skill" to skillName,
                    "result" to result.metadata
                )
            )
        } else {
            SkillResult.error("Skill $skillName failed: ${result.content}")
        }
    }

    /**
     * 执行多个 Skills（链式或并行）
     */
    private suspend fun executeMultipleSkills(args: Map<String, Any?>): SkillResult {
        val skillsList = args["skills"] as? List<*>
            ?: return SkillResult.error("Invalid 'skills' parameter")

        val parallel = (args["parallel"] as? String)?.toBoolean() ?: false
        val continueOnError = (args["continue_on_error"] as? String)?.toBoolean() ?: true

        Log.d(TAG, "Executing ${skillsList.size} skills (parallel=$parallel, continueOnError=$continueOnError)")

        val results = mutableListOf<Pair<String, SkillResult?>>()

        if (parallel) {
            // 并行执行（简化实现，实际可以用 coroutines）
            skillsList.forEach { skillItem ->
                val skillMap = skillItem as? Map<*, *> ?: return@forEach
                val skillName = skillMap["skill"] as? String ?: return@forEach
                val skillArgs = (skillMap["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?: emptyMap()

                val result = registry.execute(skillName, skillArgs)
                results.add(skillName to result)
            }
        } else {
            // 链式执行（按顺序）
            for (skillItem in skillsList) {
                val skillMap = skillItem as? Map<*, *> ?: continue
                val skillName = skillMap["skill"] as? String ?: continue
                val skillArgs = (skillMap["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?: emptyMap()

                Log.d(TAG, "Executing skill ${results.size + 1}/${skillsList.size}: $skillName")

                val result = registry.execute(skillName, skillArgs)
                results.add(skillName to result)

                // 检查是否失败
                if (result?.success == false && !continueOnError) {
                    Log.w(TAG, "Skill $skillName failed, stopping chain")
                    break
                }
            }
        }

        // 汇总结果
        val successCount = results.count { it.second?.success == true }
        val failedCount = results.count { it.second?.success == false }

        val summary = buildString {
            appendLine("[Self-Control] Ran ${results.size} skills")
            appendLine()
            appendLine("Succeeded: $successCount")
            appendLine("Failed: $failedCount")
            appendLine()
            appendLine("Details:")
            appendLine()

            results.forEachIndexed { index, (skillName, result) ->
                val status = if (result?.success == true) "✅" else "❌"
                appendLine("${index + 1}. $status $skillName")
                if (result != null) {
                    appendLine("   ${result.content.lines().firstOrNull() ?: ""}")
                }
                appendLine()
            }
        }

        return if (failedCount == 0) {
            SkillResult.success(
                summary,
                mapOf(
                    "total" to results.size,
                    "success" to successCount,
                    "failed" to failedCount,
                    "results" to results.map { it.first to it.second?.metadata }
                )
            )
        } else {
            SkillResult(
                success = successCount > 0,
                content = summary,
                metadata = mapOf(
                    "total" to results.size,
                    "success" to successCount,
                    "failed" to failedCount,
                    "results" to results.map { it.first to it.second?.metadata }
                )
            )
        }
    }
}
