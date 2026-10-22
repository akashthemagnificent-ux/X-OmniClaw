---
name: scheduled-automation
description: Schedule app automation tasks such as opening an app and performing actions at a specific time.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "⏰",
      "skillKey": "scheduled-automation",
      "version": "1.0.0",
      "category": "automation"
    }
  }
---

# Scheduled Automation

Use this skill when the user wants the phone to do something **later**, **every day**, **on specific weekdays**, **on workdays**, or **at a fixed minute interval**.

## Extraction Workflow

For app-centric natural-language requests, use this two-stage workflow:

1. LLM extraction:
   - extract `app_name`
   - extract `operation`
   - extract `schedule_phrase`
2. Rule parsing:
   - let `schedule_app_task` parse `schedule_phrase`
   - convert it into `repeat`, `daily_time`, `days_of_week`, or `interval_minutes`
3. Ambiguity check:
   - if `app_name`, `operation`, or `schedule_phrase` is still ambiguous
   - let the tool return clarification questions first instead of creating the wrong task

Important boundary:

- This skill is only for creating, listing, or cancelling future schedules.
- When an existing schedule fires, do **not** call `schedule_app_task` again.
- The saved execution instruction must be immediate and one-shot, e.g. `open Xiaohongshu, then search for news, summarize it, and send it to me`.
- Never keep schedule words (e.g. `every day at 8pm`, `每周三`, `定时`, `later`) inside `operation`; those belong only in `schedule_phrase`.

Example:

- user request: `Every Wednesday at 10:00, open Xiaohongshu, search for news, and summarize it`
- LLM extraction:
  - `app_name = 小红书`
  - `operation = search for news and summarize`
  - `schedule_phrase = every Wednesday at 10am`
- rule parsing result:
  - `repeat = weekly`
  - `days_of_week = [3]`
  - `daily_time = 10:00`

Typical requests:

- "Every day at 12:00, open WeChat and message Zhang San"
- "Every day at noon, open Xiaohongshu, search for AI news, and summarize it"
- "Every Wednesday at 10:00, open Xiaohongshu, search for AI news, and summarize it"
- "Every workday at 10:00, open WeCom and remind me to clock in"
- "Every 45 minutes, open an app and check its status once"
- "Tomorrow at 8:00, automatically open WeCom and remind me to clock in"
- "Help me schedule an app to do something at a fixed time"
- "Every evening, scan the gallery and update the user profile"

## Preferred Tool

### `schedule_app_task`

Use `schedule_app_task` as the **high-level first choice** for natural-language scheduling requests about apps and follow-up operations.

It is better than the lower-level `schedule_task` when the request is naturally phrased as:

- at what time
- open which app
- then do what

### Recommended mapping

If the user says:

- "Every day at 12:00, open WeChat and perform X"

Prefer extracting fields first, then call:

```json
{
  "action": "create",
  "task_name": "daily-wechat-task",
  "app_name": "微信",
  "operation": "perform X",
  "schedule_phrase": "every day at 23:50"
}
```

## Tool Parameters

### `schedule_app_task`

- `action`: `create` | `list` | `cancel`
- `task_name`: task display name
- `app_name`: target app name, e.g. `微信`
- `package_name`: optional package name for higher precision
- `operation`: what to do after opening the app
- `schedule_phrase`: natural-language schedule phrase extracted by the LLM, e.g. `every Wednesday at 10am`. Chinese phrases (`每周三早上10点`) are still accepted.
- `repeat`: `daily` | `once` | `weekly` | `workday` | `interval`
- `time_text`: backward-compatible alias of `schedule_phrase`
- `days_of_week`: used with `repeat=weekly`, e.g. `["mon", "wed"]`, `["monday","wednesday"]` or `["周三"]`
- `interval_minutes`: used with `repeat=interval`, e.g. `30` or `45`
- `run_at`: one-time target time
- `delay_seconds`: one-time delay
- `task_id`: used when cancelling

## When to fall back to `schedule_task`

Use lower-level `schedule_task` when:

- the task is not app-centric
- the user wants to schedule a generic agent instruction
- the execution target is not just "open app then do something"
- the task is a memory maintenance workflow such as gallery syncing or profile rebuilding

### Example: schedule gallery memory sync

If the user says:

- "Every day at 10 PM, scan the gallery and update the user profile"

Prefer using `schedule_task` with a clear instruction that tells the agent to use `gallery_memory`:

```json
{
  "action": "create",
  "task_name": "daily-gallery-memory-sync",
  "instruction": "Use the gallery_memory tool to sync new image memories from the gallery and update the user profile.",
  "repeat": "daily",
  "daily_time": "22:00"
}
```

## Best Practices

1. Prefer `schedule_app_task` for "open an app on a schedule and act" requests.
2. Preserve the user's original business intent inside `operation`.
3. If the target app is ambiguous, ask for clarification or include `package_name`.
4. For first-time setup, remind the user to verify the task once on a real device.
5. When the task is critical, suggest checking exact alarm permission and background restrictions.
6. If the extracted fields still look vague, do not force task creation; let the tool ask follow-up questions first.
7. Creation-time query and execution-time instruction are different: creation uses `schedule_phrase`; execution must contain only the action to perform now.

## Example Commands

### Daily task

```json
{
  "action": "create",
  "task_name": "daily-xhs-news",
  "app_name": "小红书",
  "operation": "Search for AI news and summarize the top three results",
  "schedule_phrase": "every day at 12:00"
}
```

### Weekly task

```json
{
  "action": "create",
  "task_name": "weekly-xhs-news",
  "app_name": "小红书",
  "operation": "Search for AI news and summarize the top three results",
  "schedule_phrase": "every Wednesday at 10am"
}
```

### Workday task

```json
{
  "action": "create",
  "task_name": "workday-checkin",
  "app_name": "企业微信",
  "operation": "Remind me to clock in",
  "schedule_phrase": "every workday at 10am"
}
```

### Fixed interval task

```json
{
  "action": "create",
  "task_name": "interval-status-check",
  "app_name": "小红书",
  "operation": "Check the home feed highlights once and summarize them",
  "schedule_phrase": "every 45 minutes"
}
```

### One-time task

```json
{
  "action": "create",
  "task_name": "wechat-reminder-tonight",
  "app_name": "微信",
  "operation": "Send Zhang San a message: remember to submit your daily report tomorrow",
  "repeat": "once",
  "delay_seconds": 600
}
```

### Cancel task

```json
{
  "action": "cancel",
  "task_id": "your-task-id"
}
```

## Verification Hint

After creating a task, you can use `schedule_app_task(action="list")` to confirm:

- task exists
- next trigger time is correct
- generated instruction matches the user's intent
