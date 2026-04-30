# Self-Control Module 集成指南

本文档详细说明如何将 Self-Control Module 集成到 X-OmniClaw 主应用中。

---

## 📦 步骤 1: Module 依赖配置

### 1.1 添加到 `settings.gradle`

```gradle
rootProject.name = "X-OmniClaw"
include ':app'
include ':quickjs-executor'
include ':feishu-channel'
include ':self-control'  // ✅ 添加这行
```

### 1.2 在 `app/build.gradle` 中添加依赖

```gradle
dependencies {
    // ... 现有依赖

    // ========== Self-Control Module ==========
    implementation project(':self-control')  // ✅ 添加这行
}
```

---

## 🔧 步骤 2: 集成到 SkillRegistry

### 2.1 修改 `SkillRegistry.kt`

**文件位置**: `app/src/main/java/com/xiaoming/omniclaw/agent/tools/SkillRegistry.kt`

```kotlin
package com.shijing.xomniclaw.agent.tools

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.selfcontrol.SelfControlRegistry  // ✅ 导入

class SkillRegistry(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkillRegistry"
    }

    // 基础 Skills
    private val skills = mutableMapOf<String, Skill>()

    // ✅ Self-Control Registry
    private val selfControlRegistry = SelfControlRegistry(context)

    init {
        // 注册现有的基础 Skills
        register(ScreenshotSkill(context))
        register(GetViewTreeSkill(context))
        register(TapSkill())
        register(SwipeSkill())
        register(TypeSkill())
        register(LongPressSkill())
        register(HomeSkill())
        register(BackSkill())
        register(OpenAppSkill(context))
        register(WaitSkill())
        register(StopSkill())
        register(NotificationSkill(context))
        register(LogSkill())

        // ✅ 打印 Self-Control Skills 加载信息
        Log.d(TAG, "Registered ${skills.size} base skills")
        Log.d(TAG, "Self-Control skills: ${selfControlRegistry.getAllSkillNames()}")
    }

    private fun register(skill: Skill) {
        skills[skill.name] = skill
        Log.d(TAG, "Registered skill: ${skill.name}")
    }

    /**
     * 获取所有工具定义（包括 Self-Control）
     */
    fun getAllToolDefinitions(): List<ToolDefinition> {
        val baseTools = skills.values.map { it.getToolDefinition() }
        val selfControlTools = selfControlRegistry.getAllToolDefinitions()  // ✅

        Log.d(TAG, "Total tools: ${baseTools.size + selfControlTools.size} " +
                "(${baseTools.size} base + ${selfControlTools.size} self-control)")

        return baseTools + selfControlTools  // ✅ 合并工具列表
    }

    /**
     * 执行 Skill
     * 优先级：Self-Control Skills > Base Skills
     */
    suspend fun execute(name: String, args: Map<String, Any?>): SkillResult {
        Log.d(TAG, "Executing skill: $name with args: $args")

        // ✅ 1. 优先检查 Self-Control Skills
        if (selfControlRegistry.contains(name)) {
            Log.d(TAG, "Executing self-control skill: $name")
            val result = selfControlRegistry.execute(name, args)
            if (result != null) {
                return result
            }
        }

        // 2. 回退到基础 Skills
        val skill = skills[name]
            ?: return SkillResult.error("Unknown skill: $name")

        return try {
            skill.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "Skill execution failed: $name", e)
            SkillResult.error("Skill execution failed: ${e.message}")
        }
    }

    /**
     * 检查 Skill 是否存在
     */
    fun contains(name: String): Boolean {
        return skills.containsKey(name) || selfControlRegistry.contains(name)  // ✅
    }

    /**
     * ✅ 获取 Self-Control 功能摘要（可选）
     * 用于添加到 system prompt 或日志
     */
    fun getSelfControlSummary(): String {
        return selfControlRegistry.getSummary()
    }
}
```

---

## 🎨 步骤 3: (可选) 添加到 System Prompt

如果希望在 system prompt 中向 LLM 说明 Self-Control 能力，可以修改 `ContextBuilder.kt`。

**文件位置**: `app/src/main/java/com/xiaoming/omniclaw/agent/context/ContextBuilder.kt`

```kotlin
class ContextBuilder(
    private val context: Context,
    private val skillRegistry: SkillRegistry
) {
    fun buildSystemPrompt(
        taskDescription: String = "",
        additionalContext: String = ""
    ): String {
        return buildString {
            // ... 现有的 system prompt 内容

            appendLine()
            appendLine("## 📱 Device Information")
            appendLine("Screen: ${DeviceInfoUtils.getScreenSize(context)}")
            appendLine("Android: ${DeviceInfoUtils.getAndroidVersion()}")

            // ✅ 添加 Self-Control 能力说明（可选）
            if (shouldIncludeSelfControl()) {
                appendLine()
                appendLine(skillRegistry.getSelfControlSummary())
            }

            appendLine()
            appendLine("## 🎯 Current Task")
            appendLine(taskDescription.ifBlank { "Waiting for instructions..." })

            if (additionalContext.isNotBlank()) {
                appendLine()
                appendLine("## 📝 Additional Context")
                appendLine(additionalContext)
            }
        }
    }

    private fun shouldIncludeSelfControl(): Boolean {
        // 根据配置决定是否在 system prompt 中包含 Self-Control 说明
        // 可以从 MMKV 读取配置
        val mmkv = MMKV.defaultMMKV()
        return mmkv.decodeBool("self_control_enabled", true)
    }
}
```

---

## ✅ 步骤 4: 构建和测试

### 4.1 构建项目

```bash
# 清理构建
./gradlew clean

# 构建 debug 版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

### 4.2 验证集成

启动应用后，检查日志：

```bash
adb logcat | grep -E "(SkillRegistry|SelfControl)"
```

**期望输出**:

```
SkillRegistry: Registered 13 base skills
SkillRegistry: Self-Control skills: [navigate_app, manage_config, control_service, query_logs]
SkillRegistry: Total tools: 17 (13 base + 4 self-control)
```

### 4.3 测试 Self-Control Skills

通过 AgentLoop 测试：

```kotlin
// 测试页面导航
val result = skillRegistry.execute(
    "navigate_app",
    mapOf("page" to "config")
)
Log.d(TAG, "Navigate result: ${result.content}")

// 测试配置读取
val configResult = skillRegistry.execute(
    "manage_config",
    mapOf(
        "operation" to "get",
        "key" to "exploration_mode"
    )
)
Log.d(TAG, "Config result: ${configResult.content}")

// 测试服务控制
val serviceResult = skillRegistry.execute(
    "control_service",
    mapOf("operation" to "check_status")
)
Log.d(TAG, "Service status: ${serviceResult.content}")

// 测试日志查询
val logResult = skillRegistry.execute(
    "query_logs",
    mapOf(
        "level" to "E",
        "lines" to 50
    )
)
Log.d(TAG, "Logs: ${logResult.content}")
```

---

## 🎯 步骤 5: 实际使用场景

### 场景 1: 截图前自动隐藏悬浮窗

**在 ScreenshotSkill 中使用**:

```kotlin
class ScreenshotSkill(private val context: Context) : Skill {
    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        // ✅ 截图前隐藏悬浮窗
        val hideResult = skillRegistry.execute(
            "control_service",
            mapOf("operation" to "hide_float")
        )

        if (!hideResult.success) {
            Log.w(TAG, "Failed to hide floating window: ${hideResult.content}")
        }

        // 延迟确保 UI 更新
        delay(100)

        // 执行截图
        val screenshot = DeviceController.getScreenshot(context)

        // ✅ 截图后显示悬浮窗
        skillRegistry.execute(
            "control_service",
            mapOf("operation" to "show_float", "delay_ms" to 500)
        )

        return SkillResult.success(...)
    }
}
```

### 场景 2: Agent 自我诊断

**在 AgentLoop 中添加错误处理**:

```kotlin
class AgentLoop(...) {
    suspend fun run(...): AgentResult {
        try {
            // ... 执行任务
        } catch (e: Exception) {
            Log.e(TAG, "Task failed", e)

            // ✅ 自动查询错误日志
            val logResult = skillRegistry.execute(
                "query_logs",
                mapOf(
                    "level" to "E",
                    "filter" to "AgentLoop",
                    "lines" to 50
                )
            )

            // 将日志包含在错误响应中
            return AgentResult(
                success = false,
                error = "Task failed: ${e.message}\n\nRecent logs:\n${logResult.content}"
            )
        }
    }
}
```

### 场景 3: 动态调整参数

**LLM 决策调整配置**:

```markdown
# Agent 决策示例

User: 截图速度太慢