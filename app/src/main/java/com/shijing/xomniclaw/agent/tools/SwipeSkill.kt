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
 * Swipe Skill
 * Swipe on screen
 */
class SwipeSkill : Skill {
    companion object {
        private const val TAG = "SwipeSkill"
    }

    override val name = "swipe"
    override val description: String
        get() {
            val isAccessibilityEnabled = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
            val statusNote = if (!isAccessibilityEnabled) " ⚠️ **Unavailable** - accessibility service not connected" else " ✅"
            return "Swipe on screen. Use it to scroll pages, switch tabs and similar. Supports up, down, left and right. **Note**: call get_view_tree() for element information before acting; screenshot() is not required.$statusNote"
        }

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "start_x" to PropertySchema("integer", "Start X coordinate"),
                        "start_y" to PropertySchema("integer", "Start Y coordinate"),
                        "end_x" to PropertySchema("integer", "End X coordinate"),
                        "end_y" to PropertySchema("integer", "End Y coordinate"),
                        "duration" to PropertySchema("integer", "Swipe duration in ms, default 300")
                    ),
                    required = listOf("start_x", "start_y", "end_x", "end_y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return SkillResult.error("Accessibility service not connected")
        }

        val startX = (args["start_x"] as? Number)?.toInt()
        val startY = (args["start_y"] as? Number)?.toInt()
        val endX = (args["end_x"] as? Number)?.toInt()
        val endY = (args["end_y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 300L

        if (startX == null || startY == null || endX == null || endY == null) {
            return SkillResult.error("Missing required parameters: start_x, start_y, end_x, end_y")
        }

        Log.d(TAG, "Swiping from ($startX, $startY) to ($endX, $endY) in ${duration}ms")
        return try {
            // Add timeout to prevent indefinite blocking
            val success = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                AccessibilityProxy.swipe(startX, startY, endX, endY, duration)
            }

            if (success == null) {
                Log.e(TAG, "Swipe timeout after 5s")
                return SkillResult.error("Swipe operation timeout after 5s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return SkillResult.error("Swipe operation failed")
            }

            // Wait for swipe completion + UI stabilization (swipe animation + inertial scrolling)
            val waitTime = duration + 300
            kotlinx.coroutines.delay(waitTime)

            SkillResult.success(
                "Swiped from ($startX, $startY) to ($endX, $endY)",
                mapOf(
                    "start" to "$startX,$startY",
                    "end" to "$endX,$endY",
                    "duration_ms" to duration,
                    "wait_time_ms" to waitTime
                )
            )
        } catch (e: IllegalStateException) {
            SkillResult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed", e)
            SkillResult.error("Swipe failed: ${e.message}")
        }
    }
}
