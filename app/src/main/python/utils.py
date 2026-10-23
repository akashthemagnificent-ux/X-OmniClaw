"""Utility functions for AgentLogic Python modules."""

import json
import time
from datetime import datetime

# Bilingual on purpose: the user may still speak Chinese and Chinese app UI
# produces Chinese output, so both marker sets stay.
DONE_HINTS = [
    "任务已完成", "任务完成", "已经全部完成", "无法继续", "做不到",
    "没有权限", "无法打开", "未能打开", "无法启动", "拒绝访问",
    "搜索结果如下", "以下是搜", "为您找到以下", "找到以下相关", "共为您",
    "以上就是", "总结如下", "希望对您",
    "task is complete", "task complete", "unable to continue",
    "permission denied", "access denied", "unable to open",
    "unable to start", "here are the results", "i found the following",
    "to summarize", "hope this helps"
]

PLAN_HINTS = [
    "接下来", "下一步", "随后", "然后我将", "然后我会", "然后我要",
    "我将要", "我将", "现在将", "需要先", "先要", "稍后", "马上",
    "让我来", "我会继续", "我继续", "现在我来",
    "I will", "I'll ", "Next,", "Then I", "Now I will", "Let me ",
    "next step", "after that", "I'm going to", "I need to first"
]

TEXT_ONLY_CONTINUATION_NUDGE = (
    "System note: your last turn ended without calling any tool, but the user's task "
    "usually needs several UI steps to finish. Observe the current screen again now and "
    "continue with the next step using device / tap / swipe / input_text until the user's "
    "goal is actually achieved. Do not end a turn with a promise such as \"next I will...\"; "
    "every turn that makes real progress must contain the corresponding tool call."
)

MAX_TEXT_ONLY_CONTINUATION_NUDGES = 6


def _normalize_tool_arg_scalar(value):
    """Normalize scalar string values emitted as JSON-string literals with extra whitespace."""
    if not isinstance(value, str):
        return value

    trimmed = value.strip()
    if not trimmed:
        return trimmed

    # Some models emit values like '\n  "act"\n'. Decode the JSON string literal first.
    if trimmed.startswith('"') and trimmed.endswith('"'):
        try:
            decoded = json.loads(trimmed)
            if isinstance(decoded, str):
                return decoded.strip()
        except Exception:
            pass

    # Only trim control-whitespace noise; keep normal user text unchanged.
    if any(ch in value for ch in ("\n", "\r", "\t")):
        return trimmed

    return value


def normalize_tool_args(value):
    """Recursively normalize tool-call arguments parsed from model output."""
    if isinstance(value, dict):
        return {k: normalize_tool_args(v) for k, v in value.items()}
    if isinstance(value, list):
        return [normalize_tool_args(item) for item in value]
    if isinstance(value, str):
        return _normalize_tool_arg_scalar(value)
    return value


def is_likely_premature_plan_text(content):
    """Detect text that looks like an unfinished plan rather than a final answer."""
    c = content.strip()
    if len(c) > 1200:
        return False
    # Case-insensitive: models open with "let me ..." as often as "Let me ...", and a missed
    # match here lets a promise end the turn with no tool call at all.
    lower = c.lower()
    if any(hint.lower() in lower for hint in DONE_HINTS):
        return False
    return any(hint.lower() in lower for hint in PLAN_HINTS)


def parse_tool_args_json(args_json):
    """Parse JSON tool arguments, returning empty dict on failure."""
    if isinstance(args_json, dict):
        return normalize_tool_args(args_json)
    if not isinstance(args_json, str):
        try:
            return normalize_tool_args(json.loads(json.dumps(args_json)))
        except Exception:
            return {}
    try:
        return normalize_tool_args(json.loads(args_json))
    except Exception:
        return {}


def is_observe_ui_tool_call(function_name, args):
    """Check if tool call is an observation (snapshot/screenshot)."""
    if function_name == "device":
        action = args.get("action", "")
        return action in ("snapshot", "screenshot")
    return function_name in ("screenshot", "get_view_tree")


def is_mutating_ui_tool_call(function_name, args):
    """Check if tool call will change the UI."""
    if function_name == "device":
        action = args.get("action", "")
        if action in ("snapshot", "screenshot", "status"):
            return False
        if action == "open":
            return True
        if action == "act":
            kind = args.get("kind", "")
            return kind != "wait"
        return True
    return function_name in (
        "tap", "swipe", "type", "long_press", "home", "back", "open_app",
        "system_settings", "start_activity", "install_app", "send_image",
    )


def compact_text_with_marker(text, max_chars, marker):
    """Truncate long text keeping head and tail with a marker in between."""
    if len(text) <= max_chars:
        return text
    safe_max = max(max_chars, 80)
    marker_line = f"\n[{marker}, {len(text) - safe_max} chars omitted]\n"
    head_chars = max(int(safe_max * 0.55), 24)
    tail_chars = max(safe_max - head_chars - len(marker_line), 20)
    if tail_chars <= 0:
        return text[:safe_max]
    return text[:head_chars] + marker_line + text[-tail_chars:]


def timestamp_str():
    """Return HH:MM:SS.mmm formatted timestamp."""
    now = datetime.now()
    return now.strftime("%H:%M:%S.") + f"{now.microsecond // 1000:03d}"


def estimate_context_chars(messages):
    """Rough character count of all messages."""
    total = 0
    for m in messages:
        total += len(m.get("content", "") or "")
        for tc in m.get("tool_calls", []) or []:
            total += len(tc.get("arguments", "") or "")
    return total
