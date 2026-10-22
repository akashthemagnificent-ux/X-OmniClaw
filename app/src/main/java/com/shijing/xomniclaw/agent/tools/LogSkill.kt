package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Log Skill
 * Record log information
 */
class LogSkill : Skill {
    companion object {
        private const val TAG = "LogSkill"
    }

    override val name = "log"
    override val description = "Record log information"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "message" to PropertySchema("string", "Log message"),
                        "level" to PropertySchema("string", "Log level: debug, info, warn, error. Defaults to info")
                    ),
                    required = listOf("message")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val message = args["message"] as? String
        val level = args["level"] as? String ?: "info"

        if (message == null) {
            return SkillResult.error("Missing required parameter: message")
        }

        return try {
            when (level.lowercase()) {
                "debug" -> Log.d(TAG, message)
                "info" -> Log.i(TAG, message)
                "warn" -> Log.w(TAG, message)
                "error" -> Log.e(TAG, message)
                else -> Log.i(TAG, message)
            }
            SkillResult.success("Logged: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Log failed", e)
            SkillResult.error("Log failed: ${e.message}")
        }
    }
}
