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
 * Tap Skill
 * Tap at specified screen coordinates
 */
class TapSkill : Skill {
    companion object {
        private const val TAG = "TapSkill"
    }

    override val name = "tap"
    override val description: String
        get() {
            val isAccessibilityEnabled = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
            val statusNote = if (!isAccessibilityEnabled) {
                "\n\n⚠️ **Status: unavailable** - accessibility service not connected"
            } else {
                "\n✅ **Status: available**"
            }
            return "Tap a coordinate on screen. Use it for buttons, input fields, list items and other interactive elements. **Note**: call get_view_tree() for element information before acting; screenshot() is not required.$statusNote"
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
                        "x" to PropertySchema("integer", "X coordinate"),
                        "y" to PropertySchema("integer", "Y coordinate")
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

        if (x == null || y == null) {
            return SkillResult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Tapping at ($x, $y)")
        return try {
            // Add timeout to prevent indefinite blocking on AIDL calls
            val success = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                AccessibilityProxy.tap(x, y)
            }

            if (success == null) {
                Log.e(TAG, "Tap timeout after 3s")
                return SkillResult.error("Tap operation timeout after 3s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return SkillResult.error("Tap operation failed")
            }

            // Wait for UI response (animation, page transitions, etc.)
            kotlinx.coroutines.delay(200)

            SkillResult.success(
                "Tapped at ($x, $y)",
                mapOf("x" to x, "y" to y, "wait_time_ms" to 200)
            )
        } catch (e: IllegalStateException) {
            SkillResult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Tap failed", e)
            SkillResult.error("Tap failed: ${e.message}")
        }
    }
}
