---
name: self-control
description: X-OmniClaw self-management and control capabilities
metadata:
  {
    "omniclaw": {
      "always": false,
      "emoji": "🔧",
      "category": "system"
    }
  }
---

# Self-Control Skill

让 X-OmniClaw 的 AI Agent 能够控制和管理自己。

**设计理念**: 实现 "AI 控制 AI" 的自我管理能力，让 Agent 具备自我认知、自我调优和自我诊断的能力。

---

## 🎯 核心能力

Self-Control Skill 是一个**元级别（meta-level）**的 Skill，让 Agent 能够：

1. **自我认知** - 了解自己的运行状态（日志、配置、服务状态）
2. **自我调优** - 动态调整运行参数（配置管理）
3. **自我导航** - 打开自己的配置页面
4. **自我诊断** - 分析错误日志，定位问题

---

## 🛠️ 可用工具

### 1. 页面导航 (navigate_app)

跳转到应用内的指定页面。

**使用场景**：
- 需要修改配置时，打开配置页面
- 需要用户确认时，打开相关页面

**参数**：
```json
{
  "page": "config"  // main/config/permissions/chat_history/chat_log/feishu/channels/result
}
```

**示例**：
```json
{
  "name": "self_control",
  "arguments": {
    "skill": "navigate_app",
    "args": {"page": "config"}
  }
}
```

### 2. 配置管理 (manage_config)

读取和修改应用配置参数。

**使用场景**：
- 读取当前配置状态
- 动态调整运行参数
- 优化性能设置

**参数**：
```json
{
  "operation": "get|set|list|delete",
  "key": "config_key",           // get/set/delete 需要
  "value": "config_value",        // set 需要
  "category": "agent|api|ui|feature|perf"  // list 可选
}
```

**示例**：
```json
// 读取配置
{
  "name": "self_control",
  "arguments": {
    "skill": "manage_config",
    "args": {
      "operation": "get",
      "key": "exploration_mode"
    }
  }
}

// 设置配置
{
  "name": "self_control",
  "arguments": {
    "skill": "manage_config",
    "args": {
      "operation": "set",
      "key": "screenshot_delay",
      "value": "200"
    }
  }
}
```

### 3. 服务控制 (control_service)

控制悬浮窗和后台服务。

**使用场景**：
- 截图前隐藏悬浮窗
- 截图后延迟显示悬浮窗
- 检查服务运行状态

**参数**：
```json
{
  "operation": "show_float|hide_float|start_float|stop_float|check_status",
  "delay_ms": 500  // 仅 show_float 使用
}
```

**示例**：
```json
// 隐藏悬浮窗
{
  "name": "self_control",
  "arguments": {
    "skill": "control_service",
    "args": {"operation": "hide_float"}
  }
}

// 延迟显示悬浮窗
{
  "name": "self_control",
  "arguments": {
    "skill": "control_service",
    "args": {
      "operation": "show_float",
      "delay_ms": 500
    }
  }
}
```

### 4. 日志查询 (query_logs)

查询应用运行日志，用于诊断问题。

**使用场景**：
- 操作失败后查看错误日志
- 分析性能问题
- 自我诊断

**参数**：
```json
{
  "level": "V|D|I|W|E|F",  // 日志级别，默认 I
  "filter": "keyword",      // 过滤关键字
  "lines": 100,             // 行数（1-200）
  "source": "logcat|file"   // 日志来源，默认 logcat
}
```

**示例**：
```json
// 查询错误日志
{
  "name": "self_control",
  "arguments": {
    "skill": "query_logs",
    "args": {
      "level": "E",
      "lines": 50
    }
  }
}

// 搜索特定 TAG
{
  "name": "self_control",
  "arguments": {
    "skill": "query_logs",
    "args": {
      "level": "D",
      "filter": "AgentLoop",
      "lines": 100
    }
  }
}
```

---

## 🔄 典型工作流

### 工作流 1: 截图前后处理（推荐模式）

**场景**：执行截图时，悬浮窗会干扰，需要自动隐藏和显示。

**链式调用**：
```json
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

**思考过程**：
1. 用户要求截图
2. 我注意到有悬浮窗可能会干扰
3. 使用 self_control 隐藏悬浮窗
4. 执行截图（使用 screenshot skill）
5. 使用 self_control 延迟显示悬浮窗（避免立即弹出干扰）

### 工作流 2: 自我诊断

**场景**：操作失败，需要分析原因并自动修复。

**步骤**：
```json
// 1. 查询错误日志
{
  "name": "self_control",
  "arguments": {
    "skill": "query_logs",
    "args": {"level": "E", "lines": 50}
  }
}

// 2. 分析日志（LLM 推理）
// 发现：screenshot timeout 错误

// 3. 读取当前配置
{
  "name": "self_control",
  "arguments": {
    "skill": "manage_config",
    "args": {
      "operation": "get",
      "key": "screenshot_delay"
    }
  }
}

// 4. 调整配置
{
  "name": "self_control",
  "arguments": {
    "skill": "manage_config",
    "args": {
      "operation": "set",
      "key": "screenshot_delay",
      "value": "200"
    }
  }
}

// 5. 验证修改
{
  "name": "self_control",
  "arguments": {
    "skill": "manage_config",
    "args": {
      "operation": "get",
      "key": "screenshot_delay"
    }
  }
}

// 6. 重试失败的操作
```

**思考过程**：
1. 截图操作超时失败
2. 使用 self_control 查询错误日志
3. 分析日志发现 screenshot_delay 设置太短
4. 使用 self_control 读取当前值（100ms）
5. 计算出更合适的值（200ms）
6. 使用 self_control 更新配置
7. 验证配置已生效
8. 重试截图操作（成功）

### 工作流 3: 配置优化

**场景**：根据运行情况动态优化配置。

**批量调用**：
```json
{
  "name": "self_control",
  "arguments": {
    "skills": [
      {"skill": "manage_config", "args": {"operation": "list", "category": "perf"}},
      {"skill": "manage_config", "args": {"operation": "set", "key": "screenshot_delay", "value": "150"}},
      {"skill": "manage_config", "args": {"operation": "set", "key": "ui_tree_enabled", "value": "true"}}
    ]
  }
}
```

**思考过程**：
1. 检测到性能问题（截图太慢）
2. 使用 self_control 列出所有性能配置
3. 分析当前配置不合理之处
4. 批量调整多个配置参数
5. 验证性能改善

---

## 📋 使用原则

### ✅ 应该使用的场景

1. **截图前后** - 隐藏/显示悬浮窗
2. **操作失败后** - 查询日志诊断问题
3. **性能问题** - 动态调整参数
4. **用户要求配置** - 打开配置页面

### ❌ 不应该使用的场景

1. **正常流程中** - 不要频繁查询日志
2. **没有问题时** - 不要盲目调整配置
3. **不确定的修改** - 不要猜测性地修改参数

### 🎯 最佳实践

1. **先读后写** - 修改配置前先读取原值
2. **验证修改** - 修改后立即验证是否生效
3. **记录原值** - 方便回滚
4. **优雅降级** - self_control 失败不影响主任务

---

## ⚠️ 重要注意事项

### 1. 防止递归调用

`self_control` Skill 已内置递归检测，不能调用自己。

❌ **错误示例**：
```json
{
  "name": "self_control",
  "arguments": {
    "skill": "self_control",  // 不能递归调用
    "args": {...}
  }
}
```

### 2. 链式调用错误处理

默认情况下，链式调用中某个 Skill 失败会继续执行后续 Skills。

如果需要遇错即停，使用：
```json
{
  "name": "self_control",
  "arguments": {
    "continue_on_error": "false",
    "skills": [...]
  }
}
```

### 3. 配置修改需谨慎

只修改明确需要改变的配置，不要盲目修改：

✅ **推荐**：
- `exploration_mode`
- `screenshot_delay`
- `ui_tree_enabled`
- `reasoning_enabled`

❌ **不推荐修改**：
- `api_key`（敏感）
- `api_base_url`（安全相关）
- 系统级配置

---

## 🔧 高级用法

### 并行调用

多个独立操作可以并行执行：

```json
{
  "name": "self_control",
  "arguments": {
    "parallel": "true",
    "skills": [
      {"skill": "query_logs", "args": {"level": "E"}},
      {"skill": "control_service", "args": {"operation": "check_status"}},
      {"skill": "manage_config", "args": {"operation": "list", "category": "feature"}}
    ]
  }
}
```

### 条件执行（未来功能）

根据结果决定后续操作：

```json
{
  "name": "self_control",
  "arguments": {
    "skill": "query_logs",
    "args": {"level": "E"},
    "on_success": {
      "skill": "analyze_and_fix"
    }
  }
}
```

---

## 📊 性能考虑

| 操作 | 开销 | 频率建议 |
|------|------|----------|
| navigate_app | 低 | 按需 |
| manage_config (get) | 极低 | 随意 |
| manage_config (set) | 低 | 谨慎 |
| control_service | 低 | 按需 |
| query_logs | 中-高 | 仅在诊断时 |

**优化建议**：
- 批量操作优于多次单独调用
- 避免在循环中查询日志
- 缓存常用配置值

---

## 🎓 学习建议

作为 AI Agent，你应该：

1. **学会观察** - 操作失败后主动查询日志
2. **学会分析** - 根据日志推断问题原因
3. **学会调整** - 动态优化运行参数
4. **学会记录** - 记住有效的配置组合

这些能力让你从"执行指令"进化到"自主管理"。

---

**Self-Control Skill** - 让 AI Agent 具备自我管理能力 🧠🔧

_Inspired by X-OmniClaw Skills System - "Tools provide capabilities, Skills teach how to use them"_
