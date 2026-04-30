# Self-Control 双模式使用指南

Self-Control Module 提供了两种独立的控制模式，分别用于不同的场景。

---

## 📋 两种模式对比

| 特性 | Internal Mode（内部模式） | ADB Mode（远程模式） |
|------|-------------------------|---------------------|
| **Skill 名称** | `self_control` | `adb_self_control` |
| **运行位置** | Android 设备上 | 开发电脑上 |
| **调用方式** | Agent 内部调用 | ADB 命令 |
| **使用场景** | AI 自我管理 | 远程控制、CI/CD |
| **需要 ADB** | ❌ 否 | ✅ 是 |
| **实时执行** | ✅ 是 | ❌ 否（生成命令） |
| **典型用户** | AI Agent | 开发者、自动化脚本 |

---

## 🤖 模式 1: Internal Mode（AI 自己调用自己）

### 概述

**Internal Self-Control Skill** (`self_control`) 让 X-OmniClaw 的 AI Agent 能够控制和管理自己。

这是一个**元级别（meta-level）**的 Skill：
- Agent 通过它调用其他 Self-Control Skills
- 实现 "AI 控制 AI" 的自我管理
- 支持链式调用和批量操作

### 使用方式

#### 1. 单个 Skill 调用

```json
{
  "name": "self_control",
  "arguments": {
    "skill": "navigate_app",
    "args": {
      "page": "config"
    }
  }
}
```

#### 2. 链式调用（按顺序执行）

```json
{
  "name": "self_control",
  "arguments": {
    "skills": [
      {
        "skill": "control_service",
        "args": {"operation": "hide_float"}
      },
      {
        "skill": "screenshot",
        "args": {}
      },
      {
        "skill": "control_service",
        "args": {"operation": "show_float", "delay_ms": 500}
      }
    ]
  }
}
```

#### 3. 并行调用（同时执行）

```json
{
  "name": "self_control",
  "arguments": {
    "parallel": "true",
    "skills": [
      {
        "skill": "query_logs",
        "args": {"level": "E", "lines": 50}
      },
      {
        "skill": "control_service",
        "args": {"operation": "check_status"}
      }
    ]
  }
}
```

### 典型场景

#### 场景 1: 截图前后自动处理

```json
// Agent 在执行截图时自动隐藏和显示悬浮窗
{
  "name": "self_control",
  "arguments": {
    "skills": [
      {"skill": "control_service", "args": {"operation": "hide_float"}},
      {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
    ]
  }
}
```

**LLM 思考过程**：
```
1. 用户要求截图
2. 我注意到有悬浮窗可能会干扰
3. 使用 self_control 隐藏悬浮窗
4. 执行截图
5. 使用 self_control 延迟显示悬浮窗
```

#### 场景 2: 自我诊断

```json
// Agent 检测到问题后自动诊断
{
  "name": "self_control",
  "arguments": {
    "skills": [
      {"skill": "query_logs", "args": {"level": "E", "lines": 50}},
      {"skill": "control_service", "args": {"operation": "check_status"}},
      {"skill": "manage_config", "args": {"operation": "list", "category": "feature"}}
    ]
  }
}
```

**LLM 思考过程**：
```
1. 操作失败了
2. 使用 self_control 查询错误日志
3. 分析日志发现 screenshot_delay 太短
4. 使用 self_control 调整配置
5. 重试操作
```

#### 场景 3: 配置优化

```json
// Agent 根据分析结果优化配置
{
  "name": "self_control",
  "arguments": {
    "skills": [
      {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}},
      {"skill": "manage_config", "args": {"operation": "set", "key": "screenshot_delay", "value": "200"}},
      {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}}
    ]
  }
}
```

**LLM 思考过程**：
```
1. 检测到截图经常超时
2. 读取当前 screenshot_delay 配置
3. 计算出更合适的值（200ms）
4. 更新配置
5. 验证配置已生效
```

### 集成示例（在 AgentLoop 中）

```kotlin
// AgentLoop.kt
class AgentLoop(private val skillRegistry: SkillRegistry) {
    suspend fun run(task: String): AgentResult {
        val systemPrompt = buildSystemPrompt()
        val messages = mutableListOf(
            Message("system", systemPrompt),
            Message("user", task)
        )

        while (iteration < maxIterations) {
            val response = llm.chat(messages, tools = skillRegistry.getAllToolDefinitions())

            if (response.toolCalls != null) {
                for (toolCall in response.toolCalls) {
                    // ✅ self_control 会被自动调用
                    val result = skillRegistry.execute(toolCall.name, toolCall.arguments)

                    if (toolCall.name == "self_control") {
                        // Agent 正在自我管理！
                        Log.d(TAG, "Agent is using self-control: ${result.content}")
                    }

                    messages.add(ToolResultMessage(result))
                }
            }
        }
    }
}
```

---

## 💻 模式 2: ADB Mode（开发者远程控制）

### 概述

**ADB Self-Control Skill** (`adb_self_control`) 用于从开发电脑远程控制 Android 设备上的 X-OmniClaw。

这个 Skill **不实际执行操作**，而是**生成 ADB 命令**供开发者使用。

### 使用方式

#### 1. 在 Agent 中调用（生成命令）

```json
{
  "name": "adb_self_control",
  "arguments": {
    "method": "provider",
    "skill": "navigate_app",
    "args": {
      "page": "config"
    }
  }
}
```

**返回**：
```
【ADB Self-Control Command】

Method: provider
Skill: navigate_app
Args: {page=config}

Command:
adb shell content call --uri content://com.shijing.xomniclaw.selfcontrol/execute --method navigate_app --extra page:s:config

📋 Copy and run this command on your development machine
```

#### 2. 直接使用 ADB 命令

##### ContentProvider 方式（推荐）

```bash
adb shell content call \
  --uri content://com.shijing.xomniclaw.selfcontrol/execute \
  --method navigate_app \
  --extra page:s:config
```

##### Broadcast 方式

```bash
adb shell am broadcast \
  -a com.shijing.xomniclaw.SELF_CONTROL \
  --es skill navigate_app \
  --es page config
```

##### Shell 脚本方式（最简单）

```bash
./self-control-adb.sh navigate_app page=config
```

### 典型场景

#### 场景 1: CI/CD 自动化测试

```yaml
# .github/workflows/test.yml
name: Android E2E Test

on: [push]

jobs:
  test:
    runs-on: macos-latest
    steps:
      - name: Start Emulator
        run: # ... 启动模拟器

      - name: Install APK
        run: adb install app-debug.apk

      - name: Configure App
        run: |
          # 设置测试模式
          ./self-control-adb.sh manage_config operation=set key=test_mode value=true

          # 隐藏悬浮窗（测试时不需要）
          ./self-control-adb.sh control_service operation=hide_float

      - name: Run Tests
        run: # ... 执行测试

      - name: Collect Logs
        if: failure()
        run: |
          ./self-control-adb.sh query_logs level=E lines:i=200 > error_logs.txt
```

#### 场景 2: 远程调试

```bash
#!/bin/bash
# debug.sh - 远程调试脚本

echo "=== Health Check ==="
./self-control-adb.sh health

echo -e "\n=== Service Status ==="
./self-control-adb.sh control_service operation=check_status

echo -e "\n=== Recent Errors ==="
./self-control-adb.sh query_logs level=E lines:i=50

echo -e "\n=== Configuration ==="
./self-control-adb.sh manage_config operation=list category=feature
```

#### 场景 3: 批量设备配置

```bash
#!/bin/bash
# configure_devices.sh - 批量配置多个设备

devices=$(adb devices | grep -v "List" | awk '{print $1}')

for device in $devices; do
    echo "Configuring device: $device"

    # 指定设备执行
    adb -s $device shell content call \
      --uri content://com.shijing.xomniclaw.selfcontrol/execute \
      --method manage_config \
      --extra operation:s:set \
      --extra key:s:api_base_url \
      --extra value:s:https://api.example.com
done
```

---

## 🔄 两种模式的配合使用

### 场景：开发者指导 AI 自我优化

```bash
# 1. 开发者通过 ADB 查看当前状态
./self-control-adb.sh manage_config operation=list category=feature

# 2. 开发者调整配置
./self-control-adb.sh manage_config operation=set key=self_optimization value=true

# 3. AI Agent 自动开始优化（使用 self_control）
# Agent 内部：
{
  "name": "self_control",
  "arguments": {
    "skills": [
      {"skill": "query_logs", "args": {"level": "W"}},
      {"skill": "manage_config", "args": {"operation": "set", "key": "optimized_param", "value": "new_value"}}
    ]
  }
}

# 4. 开发者验证结果
./self-control-adb.sh manage_config operation=get key=optimized_param
```

---

## 📊 功能对比表

| 功能 | Internal Mode | ADB Mode |
|------|---------------|----------|
| 页面导航 | ✅ | ✅ |
| 配置管理 | ✅ | ✅ |
| 服务控制 | ✅ | ✅ |
| 日志查询 | ✅ | ✅ |
| 链式调用 | ✅ | ❌ |
| 并行调用 | ✅ | ❌ |
| 实时执行 | ✅ | ❌ |
| 远程执行 | ❌ | ✅ |
| 批量设备 | ❌ | ✅ |
| CI/CD 集成 | ❌ | ✅ |

---

## 🎯 选择指南

### 使用 Internal Mode (`self_control`) 当：

- ✅ AI Agent 需要自我管理
- ✅ 需要链式或并行执行
- ✅ 运行时动态调整
- ✅ 自动化任务执行
- ✅ 自我诊断和优化

### 使用 ADB Mode (`adb_self_control`) 当：

- ✅ 从开发电脑远程控制
- ✅ CI/CD 自动化测试
- ✅ 批量设备管理
- ✅ 远程调试
- ✅ 脚本化操作

---

## 🔐 安全考虑

### Internal Mode

- ✅ 防止递归调用
- ✅ 错误处理和回滚
- ✅ 操作日志记录
- ⚠️ 建议设置白名单

### ADB Mode

- ✅ 仅在调试模式启用
- ✅ 可配置权限保护
- ✅ 操作审计日志
- ⚠️ 生产环境建议禁用

---

## 📚 相关文档

- **ADB_USAGE.md** - ADB 模式详细使用指南
- **self-control-skill.md** - Internal 模式 Skill 文档（供 LLM）
- **INTEGRATION.md** - 集成指南

---

**Self-Control Dual Mode** - AI 自我管理 + 远程控制 🤖💻
