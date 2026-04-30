---
name: self-control
description: X-OmniClaw 自我控制和管理技能
metadata:
  {
    "omniclaw": {
      "always": false,
      "emoji": "🔧"
    }
  }
---

# Self-Control Skill

让 AI Agent 能够控制和管理 X-OmniClaw 自身的核心技能。

**设计理念**: 让 AI Agent 具备自我认知、自我调优、自我诊断的能力，实现真正的自主闭环。

---

## 🎯 核心能力

Self-Control Skill 提供以下四大类能力：

### 1. 页面导航 (`navigate_app`)

跳转到应用内各个页面，方便查看和修改配置。

**可用页面**:
- `main`: 主界面
- `config`: 配置页面（API、模型设置）
- `permissions`: 权限管理
- `chat_history`: 对话历史
- `chat_log`: 详细日志
- `feishu`: 飞书通道
- `channels`: 通道列表
- `result`: 结果展示

**示例**:
```json
{
  "name": "navigate_app",
  "arguments": {
    "page": "config"
  }
}
```

### 2. 配置管理 (`manage_config`)

读取和修改应用配置参数。

**操作类型**:
- `get`: 读取配置
- `set`: 设置配置
- `list`: 列出配置
- `delete`: 删除配置

**配置分类**:
- `agent`: Agent 运行参数
- `api`: API 设置
- `ui`: UI 偏好
- `feature`: 功能开关
- `perf`: 性能参数

**示例**:
```json
// 读取配置
{
  "name": "manage_config",
  "arguments": {
    "operation": "get",
    "key": "exploration_mode"
  }
}

// 设置配置
{
  "name": "manage_config",
  "arguments": {
    "operation": "set",
    "key": "exploration_mode",
    "value": "true"
  }
}

// 列出所有功能开关
{
  "name": "manage_config",
  "arguments": {
    "operation": "list",
    "category": "feature"
  }
}
```

### 3. 服务控制 (`control_service`)

控制悬浮窗和后台服务。

**操作类型**:
- `show_float`: 显示悬浮窗
- `hide_float`: 隐藏悬浮窗
- `start_float`: 启动悬浮窗服务
- `stop_float`: 停止悬浮窗服务
- `check_status`: 检查服务状态

**示例**:
```json
// 隐藏悬浮窗（截图前）
{
  "name": "control_service",
  "arguments": {
    "operation": "hide_float"
  }
}

// 延迟显示悬浮窗
{
  "name": "control_service",
  "arguments": {
    "operation": "show_float",
    "delay_ms": 500
  }
}

// 检查服务状态
{
  "name": "control_service",
  "arguments": {
    "operation": "check_status"
  }
}
```

### 4. 日志查询 (`query_logs`)

查询应用运行日志，诊断问题。

**日志级别**:
- `V`: Verbose（详细）
- `D`: Debug（调试）
- `I`: Info（信息）
- `W`: Warning（警告）
- `E`: Error（错误）
- `F`: Fatal（致命）

**示例**:
```json
// 查看最近的错误
{
  "name": "query_logs",
  "arguments": {
    "level": "E",
    "lines": 50
  }
}

// 搜索特定 TAG
{
  "name": "query_logs",
  "arguments": {
    "level": "D",
    "filter": "AgentLoop",
    "lines": 100
  }
}
```

---

## 📋 使用原则

### 何时使用 Self-Control

**适用场景**:
1. **操作失败后诊断** - 查看日志分析原因
2. **截图前后** - 隐藏/显示悬浮窗
3. **性能优化** - 调整延迟、超时参数
4. **功能切换** - 开关特定功能
5. **用户请求配置** - 用户明确要求修改设置

**不适用场景**:
1. **正常任务执行中** - 不要频繁检查日志
2. **没有问题时** - 不要盲目调整配置
3. **不确定的修改** - 不要猜测性地修改参数

### 使用流程

```
观察问题 → 查询日志 → 分析原因 → 调整配置 → 验证结果
```

---

## 🔄 典型工作流

### 工作流 1: 截图操作（推荐模式）

```json
// Step 1: 隐藏悬浮窗
{
  "name": "control_service",
  "arguments": {"operation": "hide_float"}
}

// Step 2: 短暂延迟确保 UI 更新
{
  "name": "wait",
  "arguments": {"duration": 100}
}

// Step 3: 执行截图
{
  "name": "screenshot",
  "arguments": {}
}

// Step 4: 显示悬浮窗（延迟显示避免干扰）
{
  "name": "control_service",
  "arguments": {
    "operation": "show_float",
    "delay_ms": 500
  }
}
```

### 工作流 2: 自我诊断

```json
// Step 1: 检查服务状态
{
  "name": "control_service",
  "arguments": {"operation": "check_status"}
}

// Step 2: 查看错误日志
{
  "name": "query_logs",
  "arguments": {
    "level": "E",
    "lines": 50
  }
}

// Step 3: 如果发现问题，查看详细上下文
{
  "name": "query_logs",
  "arguments": {
    "level": "D",
    "filter": "具体的错误关键字",
    "lines": 100
  }
}
```

### 工作流 3: 性能调优

```json
// Step 1: 读取当前配置
{
  "name": "manage_config",
  "arguments": {
    "operation": "get",
    "key": "screenshot_delay"
  }
}

// Step 2: 根据分析调整参数
{
  "name": "manage_config",
  "arguments": {
    "operation": "set",
    "key": "screenshot_delay",
    "value": "200"
  }
}

// Step 3: 验证修改
{
  "name": "manage_config",
  "arguments": {
    "operation": "get",
    "key": "screenshot_delay"
  }
}
```

### 工作流 4: 用户配置修改

```json
// Step 1: 打开配置页面
{
  "name": "navigate_app",
  "arguments": {"page": "config"}
}

// Step 2: 列出当前配置
{
  "name": "manage_config",
  "arguments": {
    "operation": "list",
    "category": "feature"
  }
}

// Step 3: 根据用户要求修改
{
  "name": "manage_config",
  "arguments": {
    "operation": "set",
    "key": "user_requested_key",
    "value": "user_requested_value"
  }
}
```

---

## ⚠️ 重要注意事项

### 配置修改规则

1. **谨慎修改** - 只修改明确需要改变的配置
2. **验证修改** - 修改后立即读取验证
3. **记录原值** - 修改前记录原始值，必要时回滚
4. **用户确认** - 重要配置修改需要用户确认

### 日志查询优化

1. **按需查询** - 不要在每个步骤都查日志
2. **过滤关键字** - 使用 filter 参数精确查找
3. **合适级别** - 根据问题严重程度选择日志级别
4. **限制行数** - 不要一次查询过多日志

### 服务控制注意

1. **成对操作** - hide_float 后必须 show_float
2. **添加延迟** - 显示悬浮窗时使用 delay_ms 避免干扰
3. **检查权限** - 确保有悬浮窗权限
4. **优雅处理** - 操作失败不影响主流程

---

## 🎓 高级技巧

### 技巧 1: 自适应延迟

根据日志分析动态调整延迟参数：

```python
# 伪代码逻辑
if 日志中出现 "screenshot timeout":
    读取当前 screenshot_delay
    增加 50ms
    设置新的 screenshot_delay
    通知用户已优化
```

### 技巧 2: 批量配置

一次性修改多个相关配置：

```json
// 开启探索模式 + 降低延迟
[
  {
    "name": "manage_config",
    "arguments": {
      "operation": "set",
      "key": "exploration_mode",
      "value": "true"
    }
  },
  {
    "name": "manage_config",
    "arguments": {
      "operation": "set",
      "key": "screenshot_delay",
      "value": "50"
    }
  }
]
```

### 技巧 3: 日志驱动决策

根据日志内容自动决策：

```python
# 伪代码
logs = query_logs(level="E", filter="AccessibilityService")

if "permission denied" in logs:
    navigate_app(page="permissions")
    提示用户："检测到权限问题，已打开权限页面"
elif "service not running" in logs:
    control_service(operation="start_float")
    提示用户："服务已重启"
```

---

## 📊 性能考虑

### 开销评估

| 操作 | 开销 | 频率建议 |
|------|------|----------|
| `navigate_app` | 低 | 按需 |
| `manage_config` (get) | 极低 | 随意 |
| `manage_config` (set) | 低 | 谨慎 |
| `control_service` | 低 | 按需 |
| `query_logs` | 中-高 | 仅在诊断时 |

### 优化建议

1. **批量操作** - 一次性完成多个配置修改
2. **缓存结果** - 避免重复查询同样的配置
3. **延迟日志查询** - 只在真正需要时查日志
4. **异步执行** - 不阻塞主流程

---

## 🔐 安全考虑

### 配置白名单（建议）

只允许修改安全的配置项：
- `exploration_mode`
- `screenshot_delay`
- `ui_tree_enabled`
- `reasoning_enabled`

**不应修改**:
- `api_key` (敏感)
- `api_base_url` (安全)
- 系统级配置

### 操作审计

所有 Self-Control 操作都会记录到日志：

```
SelfControlRegistry: Executing self-control skill: manage_config
ConfigSkill: Config updated: exploration_mode = true
```

---

## 🎯 最佳实践总结

1. **截图前隐藏悬浮窗** - 避免截图中出现悬浮窗
2. **操作失败后查日志** - 诊断根本原因
3. **谨慎修改配置** - 只改必须改的
4. **用户确认重要修改** - 关键配置需要确认
5. **验证每次修改** - 修改后立即验证
6. **记录原始值** - 方便回滚
7. **优雅降级** - Self-Control 失败不影响主任务

---

**Self-Control Skill** - 让 AI Agent 具备自我管理能力 🧠🔧

_Inspired by X-OmniClaw Skills System_
