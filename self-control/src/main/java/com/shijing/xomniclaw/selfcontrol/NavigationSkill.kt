package com.shijing.xomniclaw.selfcontrol

/**
 * Upstream reference (OmniClaw):
 * - ../omniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.content.Intent
import android.util.Log
import com.shijing.xomniclaw.selfcontrol.Skill
import com.shijing.xomniclaw.selfcontrol.SkillResult
import com.shijing.xomniclaw.selfcontrol.FunctionDefinition
import com.shijing.xomniclaw.selfcontrol.ParametersSchema
import com.shijing.xomniclaw.selfcontrol.PropertySchema
import com.shijing.xomniclaw.selfcontrol.ToolDefinition

/**
 * Self-Control Navigation Skill
 *
 * 暴露 PhoneForClaw 自身的页面导航功能，让 AI Agent 能够：
 * - 跳转到各个配置页面
 * - 打开功能设置界面
 * - 访问日志和历史记录
 * - 管理 Channels 和会话
 *
 * 使用场景：
 * - AI 自我开发迭代（修改配置、查看日志）
 * - 远程配置和管理
 * - 自动化测试和调试
 */
class NavigationSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "NavigationSkill"

        // 可用的页面目标
        object Pages {
            const val MAIN = "main"                    // 主界面
            const val CONFIG = "config"                // 配置页面
            const val PERMISSIONS = "permissions"      // 权限管理
            const val CHAT_HISTORY = "chat_history"    // 对话历史
            const val CHAT_LOG = "chat_log"            // 对话日志
            const val FEISHU_CHANNEL = "feishu"        // 飞书通道
            const val CHANNEL_LIST = "channels"        // 通道列表
            const val RESULT = "result"                // 结果页面
        }
    }

    override val name = "navigate_app"

    override val description = """
        Navigate to the pages inside the PhoneForClaw app.

        Available pages:
        - main: main screen
        - config: config page (API, model settings)
        - permissions: permission management
        - chat_history: conversation history
        - chat_log: detailed conversation log
        - feishu: Feishu channel configuration
        - channels: channel list management
        - result: result page

        Use cases:
        - change app configuration
        - check permission status
        - view runtime logs
        - manage channel connections

        Note: the app must be in the foreground or have overlay permission.
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
                        "page" to PropertySchema(
                            type = "string",
                            description = "target page name",
                            enum = listOf(
                                Pages.MAIN,
                                Pages.CONFIG,
                                Pages.PERMISSIONS,
                                Pages.CHAT_HISTORY,
                                Pages.CHAT_LOG,
                                Pages.FEISHU_CHANNEL,
                                Pages.CHANNEL_LIST,
                                Pages.RESULT
                            )
                        ),
                        "extras" to PropertySchema(
                            type = "object",
                            description = "optional Intent extras (JSON object)"
                        )
                    ),
                    required = listOf("page")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val page = args["page"] as? String
            ?: return SkillResult.error("Missing required parameter: page")

        val extras = args["extras"] as? Map<String, Any?>

        return try {
            val intent = createIntentForPage(page)
                ?: return SkillResult.error("Unknown page: $page")

            // 添加额外参数
            extras?.forEach { (key, value) ->
                when (value) {
                    is String -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    is Float -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                }
            }

            // 启动 Activity
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Log.d(TAG, "Successfully navigated to page: $page")

            SkillResult.success(
                "Navigated to page: $page",
                mapOf(
                    "page" to page,
                    "extras" to (extras ?: emptyMap<String, Any>())
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to page: $page", e)
            SkillResult.error("Page navigation failed: ${e.message}")
        }
    }

    private fun createIntentForPage(page: String): Intent? {
        val packageName = context.packageName
        val className = when (page) {
            Pages.MAIN -> "$packageName.ui.activity.MainActivity"
            Pages.CONFIG -> "$packageName.ui.activity.ConfigActivity"
            Pages.PERMISSIONS -> "$packageName.ui.activity.PermissionsActivity"
            Pages.CHAT_HISTORY -> "$packageName.ui.activity.ChatHistoryActivity"
            Pages.CHAT_LOG -> "$packageName.ui.activity.ChatLogActivity"
            Pages.FEISHU_CHANNEL -> "$packageName.ui.activity.FeishuChannelActivity"
            Pages.CHANNEL_LIST -> "$packageName.ui.activity.ChannelListActivity"
            Pages.RESULT -> "$packageName.ui.activity.ResultActivity"
            else -> return null
        }

        return Intent().apply {
            setClassName(packageName, className)
        }
    }
}
