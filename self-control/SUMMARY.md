# Self-Control Module - 快速概览

## 🎯 一句话总结

让 X-OmniClaw 的 AI Agent 能够控制和管理自身的独立模块，实现自我认知、自我调优和自我诊断。

---

## 📦 Module 信息

- **名称**: `self-control`
- **类型**: Android Library Module
- **依赖**: `app` module (compileOnly)
- **包名**: `com.shijing.xomniclaw.selfcontrol`

---

## 🔧 提供的 Skills

| Skill | 功能 | 典型用途 |
|-------|------|---------|
| `navigate_app` | 页面导航 | 打开配置、查看历史 |
| `manage_config` | 配置管理 | 读写 MMKV 配置 |
| `control_service` | 服务控制 | 隐藏悬浮窗（截图前） |
| `query_logs` | 日志查询 | 错误诊断 |

---

## 🚀 快速开始

### 1. 添加依赖

```gradle
// settings.gradle
include ':self-control'

// app/build.gradle
implementation project(':self-control')
```

### 2. 集成到 SkillRegistry

```kotlin
class SkillRegistry(context: Context) {
    private val selfControlRegistry = SelfControlRegistry(context)

    fun getAllToolDefinitions(): List<ToolDefinition> {
        return baseTools + selfControlRegistry.getAllToolDefinitions()
    }

    suspend fun execute(name: String, args: Map<String, Any?>): SkillResult {
        return selfControlRegistry.execute(name, args)
            ?: baseSkills[name]?.execute(args)
            ?: SkillResult.error("Unknown skill: $name")
    }
}
```

### 3. 使用示例

```kotlin
// 截图前隐藏悬浮窗
registry.execute("control_service", mapOf("operation" to "hide_float"))

// 查询错误日志
registry.execute("query_logs", mapOf("level" to "E", "lines" to 50))

// 修改配置
registry.execute("manage_config", mapOf(
    "operation" to "set",
    "key" to "exploration_mode",
    "value" to "true"
))

// 打开配置页面
registry.execute("navigate_app", mapOf("page" to "config"))
```

---

## 📚 文档索引

- **README.md** - 详细说明和架构设计
- **INTEGRATION.md** - 完整集成指南
- **self-control-skill.md** - Skill 使用文档（供 LLM 参考）
- **docs/self-control-module.md** - 完整技术文档
- **SelfControlDemoActivity.kt** - 使用示例代码

---

## 🎯 核心价值

1. **自我认知** - Agent 了解自己的运行状态
2. **自我调优** - Agent 动态调整运行参数
3. **自我开发** - Agent 修改自己的配置
4. **自我诊断** - Agent 分析和修复问题

---

## 💡 典型场景

### 场景 1: 截图前后处理
```
hide_float → wait → screenshot → show_float
```

### 场景 2: 自我诊断
```
check_status → query_logs → analyze → adjust_config
```

### 场景 3: 用户配置
```
navigate(config) → list_config → set_config → verify
```

---

## ⚠️ 重要注意

1. **谨慎修改配置** - 只改必须改的
2. **验证每次修改** - 修改后立即验证
3. **记录原始值** - 方便回滚
4. **优雅降级** - Self-Control 失败不影响主任务

---

## 🔗 相关资源

- [X-OmniClaw Skills System](https://github.com/omniclaw/omniclaw)
- [AgentSkills.io](https://agentskills.io)
- [CLAUDE.md](../CLAUDE.md)

---

**Self-Control Module** - 让 AI Agent 具备自我管理能力 🧠🔧

_部分 X-OmniClaw 项目，遵循 X-OmniClaw 架构理念_
