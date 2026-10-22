package com.shijing.xomniclaw.selfcontrol

/**
 * Upstream reference (OmniClaw):
 * - ../omniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import com.shijing.xomniclaw.selfcontrol.Skill
import com.shijing.xomniclaw.selfcontrol.SkillResult
import com.shijing.xomniclaw.selfcontrol.FunctionDefinition
import com.shijing.xomniclaw.selfcontrol.ParametersSchema
import com.shijing.xomniclaw.selfcontrol.PropertySchema
import com.shijing.xomniclaw.selfcontrol.ToolDefinition

/**
 * Self-Control Config Skill
 *
 * 读取和修改 PhoneForClaw 的配置参数，让 AI Agent 能够：
 * - 查看当前配置
 * - 修改运行时参数
 * - 切换功能开关
 * - 调整性能参数
 *
 * 使用场景：
 * - AI 自我调优（调整超时、重试次数等）
 * - 功能开关管理
 * - 远程配置更新
 * - A/B 测试
 */
class ConfigSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ConfigSkill"

        // 操作类型
        object Operations {
            const val GET = "get"              // 读取配置
            const val SET = "set"              // 设置配置
            const val LIST = "list"            // 列出所有配置
            const val DELETE = "delete"        // 删除配置
        }

        // 配置分类
        object Categories {
            const val AGENT = "agent"          // Agent 配置
            const val API = "api"              // API 配置
            const val UI = "ui"                // UI 配置
            const val FEATURE = "feature"      // 功能开关
            const val PERFORMANCE = "perf"     // 性能参数
        }
    }

    override val name = "manage_config"

    override val description = """
        Manage PhoneForClaw application configuration.

        Supported operations:
        - get: read one config item
        - set: set a config value
        - list: list all config items in a category
        - delete: delete a config item

        Categories:
        - agent: Agent runtime parameters (max_iterations, timeout, etc.)
        - api: API settings (base_url, api_key, model, etc.)
        - ui: UI preferences (theme, language, etc.)
        - feature: feature toggles (exploration_mode, reasoning_enabled, etc.)
        - perf: performance parameters (screenshot_delay, ui_tree_enabled, etc.)

        Examples:
        - read: {"operation": "get", "key": "exploration_mode"}
        - set: {"operation": "set", "key": "exploration_mode", "value": true}
        - list: {"operation": "list", "category": "feature"}

        Note: some config changes may require an app restart to take effect.
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
                        "operation" to PropertySchema(
                            type = "string",
                            description = "operation type",
                            enum = listOf(
                                Operations.GET,
                                Operations.SET,
                                Operations.LIST,
                                Operations.DELETE
                            )
                        ),
                        "key" to PropertySchema(
                            type = "string",
                            description = "config key (required for get/set/delete)"
                        ),
                        "value" to PropertySchema(
                            type = "string",
                            description = "config value (required for set; string/number/boolean)"
                        ),
                        "category" to PropertySchema(
                            type = "string",
                            description = "config category (optional for list)",
                            enum = listOf(
                                Categories.AGENT,
                                Categories.API,
                                Categories.UI,
                                Categories.FEATURE,
                                Categories.PERFORMANCE
                            )
                        )
                    ),
                    required = listOf("operation")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val operation = args["operation"] as? String
            ?: return SkillResult.error("Missing required parameter: operation")

        val mmkv = MMKV.defaultMMKV()

        return try {
            when (operation) {
                Operations.GET -> handleGet(mmkv, args)
                Operations.SET -> handleSet(mmkv, args)
                Operations.LIST -> handleList(mmkv, args)
                Operations.DELETE -> handleDelete(mmkv, args)
                else -> SkillResult.error("Unknown operation: $operation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config operation failed: $operation", e)
            SkillResult.error("Config operation failed: ${e.message}")
        }
    }

    private fun handleGet(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val key = args["key"] as? String
            ?: return SkillResult.error("Missing parameter: key")

        if (!mmkv.contains(key)) {
            return SkillResult.error("Config item does not exist: $key")
        }

        // 尝试不同类型
        val value: Any? = when {
            mmkv.decodeInt(key, Int.MIN_VALUE) != Int.MIN_VALUE -> mmkv.decodeInt(key)
            mmkv.decodeLong(key, Long.MIN_VALUE) != Long.MIN_VALUE -> mmkv.decodeLong(key)
            mmkv.decodeFloat(key, Float.MIN_VALUE) != Float.MIN_VALUE -> mmkv.decodeFloat(key)
            mmkv.decodeDouble(key, Double.MIN_VALUE) != Double.MIN_VALUE -> mmkv.decodeDouble(key)
            else -> {
                val str = mmkv.decodeString(key, null)
                if (str != null) str
                else mmkv.decodeBool(key, false)
            }
        }

        return SkillResult.success(
            "Config item '$key' = $value",
            mapOf(
                "key" to key,
                "value" to value,
                "type" to value?.javaClass?.simpleName
            )
        )
    }

    private fun handleSet(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val key = args["key"] as? String
            ?: return SkillResult.error("Missing parameter: key")

        val valueStr = args["value"] as? String
            ?: return SkillResult.error("Missing parameter: value")

        // 智能类型转换
        val success = when {
            valueStr.equals("true", ignoreCase = true) -> {
                mmkv.encode(key, true)
            }
            valueStr.equals("false", ignoreCase = true) -> {
                mmkv.encode(key, false)
            }
            valueStr.toIntOrNull() != null -> {
                mmkv.encode(key, valueStr.toInt())
            }
            valueStr.toLongOrNull() != null -> {
                mmkv.encode(key, valueStr.toLong())
            }
            valueStr.toFloatOrNull() != null -> {
                mmkv.encode(key, valueStr.toFloat())
            }
            valueStr.toDoubleOrNull() != null -> {
                mmkv.encode(key, valueStr.toDouble())
            }
            else -> {
                mmkv.encode(key, valueStr)
            }
        }

        return if (success) {
            SkillResult.success(
                "Config updated: $key = $valueStr",
                mapOf("key" to key, "value" to valueStr)
            )
        } else {
            SkillResult.error("Failed to update config")
        }
    }

    private fun handleList(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val category = args["category"] as? String

        val allKeys = mmkv.allKeys() ?: emptyArray()

        // 根据分类过滤（简单前缀匹配）
        val filteredKeys = if (category != null) {
            allKeys.filter { it.startsWith(category, ignoreCase = true) }
        } else {
            allKeys.toList()
        }

        val configList = filteredKeys.sorted().joinToString("\n") { key ->
            val value = mmkv.decodeString(key, "[unknown]")
            "  - $key = $value"
        }

        val summary = if (category != null) {
            "[$category config] (${filteredKeys.size} items)\n$configList"
        } else {
            "[All config] (${filteredKeys.size} items)\n$configList"
        }

        return SkillResult.success(
            summary,
            mapOf(
                "category" to (category ?: "all"),
                "count" to filteredKeys.size,
                "keys" to filteredKeys
            )
        )
    }

    private fun handleDelete(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val key = args["key"] as? String
            ?: return SkillResult.error("Missing parameter: key")

        if (!mmkv.contains(key)) {
            return SkillResult.error("Config item does not exist: $key")
        }

        mmkv.remove(key)

        return SkillResult.success(
            "Config item deleted: $key",
            mapOf("key" to key)
        )
    }
}
