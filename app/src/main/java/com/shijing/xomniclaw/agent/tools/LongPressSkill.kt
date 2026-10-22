package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.util.Log
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Long Press Skill
 * Long press at specified screen coordinates
 */
class LongPressSkill : Skill {
    companion object {
        private const val TAG = "LongPressSkill"
    }

    override val name = "long_press"
    override val description = "Long-press a coordinate on screen. Use it for long-press menus, deleting items, and similar. **Note**: call get_view_tree() for element information before acting; screenshot() is not required."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "x" to PropertySchema("integer", "X coordinate"),
                        "y" to PropertySchema("integer", "Y coordinate"),
                        "duration" to PropertySchema("integer", "Long-press duration in ms, default 1000")
                    ),
                    required = listOf("x", "y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return SkillResult.error("Accessibility service not connected")
        }

        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 1000L

        if (x == null || y == null) {
            return SkillResult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Long pressing at ($x, $y)")
        return try {
            // Add timeout to prevent indefinite blocking
            val success = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                AccessibilityProxy.longPress(x, y)
            }

            if (success == null) {
                Log.e(TAG, "Long press timeout after 3s")
                return SkillResult.error("Long press operation timeout after 3s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return SkillResult.error("Long press operation failed")
            }

            // Wait for menu popup or response after long press
            kotlinx.coroutines.delay(200)

            SkillResult.success(
                "Long pressed at ($x, $y)",
                mapOf(
                    "x" to x,
                    "y" to y,
                    "wait_time_ms" to 200
                )
            )
        } catch (e: IllegalStateException) {
            SkillResult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Long press failed", e)
            SkillResult.error("Long press failed: ${e.message}")
        }
    }
}
