"""Core Agent Loop logic — Python-side entry point called from Kotlin via Chaquopy.

The Kotlin AgentLoop.kt acts as a thin bridge: it creates a KotlinBridge object and
passes it here.  All business logic (iteration control, context management, loop
detection, incremental sensing, nudge injection, error tracking) lives in this file.

Public entry:  run_agent(bridge, system_prompt, user_message, context_history_json,
                         reasoning_enabled, max_iterations, context_window_tokens)
Returns: JSON string of {"final_content": str, "tools_used": [str], "iterations": int,
                          "messages": [...]}
"""

import json
import time
import re

from loop_detector import LoopDetector, LoopDetectionResult
from context_manager import enforce_context_budget, HARD_KEEP_RECENT_DIALOG_TURNS
from session_logger import SessionLogger
from utils import (
    is_likely_premature_plan_text,
    is_observe_ui_tool_call,
    is_mutating_ui_tool_call,
    parse_tool_args_json,
    estimate_context_chars,
    TEXT_ONLY_CONTINUATION_NUDGE,
    MAX_TEXT_ONLY_CONTINUATION_NUDGES,
)

MAX_CONSECUTIVE_ERRORS = 3

# A first-turn "Sure, let me do that!" is a promise, not an answer, even though nothing has
# run yet. Long text on turn 1 is treated as a real answer so explanations are not looped.
FIRST_TURN_PROMISE_MAX_CHARS = 240

_REASONING_TAG_RE = re.compile(
    r"<(think|thinking|reasoning|reflection|inner_thoughts)>.*?</\1>",
    re.DOTALL | re.IGNORECASE,
)

# shortcut: on an English UI the Chinese markers below never match, so the
# English equivalents are kept alongside them. Remove only if the UI is
# guaranteed to stay Chinese.
_CHAT_SNAPSHOT_INPUT_MARKERS_RAW = ("输入框", "Input")
_CHAT_SNAPSHOT_INPUT_MARKERS_LOWER = ("editable=true", "edittext", "input")
_CHAT_SNAPSHOT_SEND_MARKERS_RAW = ("发送", "Send")
_CHAT_SNAPSHOT_SEND_MARKERS_LOWER = ("send",)


def _strip_reasoning_tags(text):
    if not text:
        return text
    return _REASONING_TAG_RE.sub("", text).strip()


def _format_reasoning_for_log(text, max_chars=4000):
    """Flatten reasoning into one bounded line so logs stay readable."""
    if not text:
        return ""
    normalized = re.sub(r"\s+", " ", str(text)).strip()
    if len(normalized) <= max_chars:
        return normalized
    # Truncate when overlong, with a marker so the start of the reasoning stays visible.
    return normalized[: max_chars - 1] + "…"


def _safe_get_skill_selection_trace(bridge):
    """Read the skill-selection trace from KotlinBridge; degrade silently on old bridges."""
    try:
        if hasattr(bridge, "get_skill_selection_trace"):
            trace = bridge.get_skill_selection_trace()
            if trace:
                return str(trace).strip()
    except Exception:
        # Old bridge builds may not expose this; never break the main flow over a debug log.
        pass
    return ""


def _is_snapshot_ref_error(err: str) -> bool:
    """Detect typical snapshot/ref-related failures where visual fallback is desired."""
    if not err:
        return False
    msg = err.lower()
    # English markers are what DeviceTool emits; the Chinese ones are kept for
    # older builds and for Chinese-localized AccessibilityManager errors.
    patterns = [
        "expired",
        "not found in the latest",
        "已过期",
        "ref '",
        "ref \"",
        "不存在于最近一次 snapshot",
        "无障碍服务未开启",
        "无障碍服务未启用",
        "获取 ui 树失败",
        "accessibility",
        "dumpviewtree failed",
    ]
    return any(p.lower() in msg for p in patterns)


_SEND_HINTS = ("发送", "发 送", "send")


def _is_send_like_device_tap(fn_name, args):
    if fn_name != "device":
        return False
    if str(args.get("action", "")).lower() != "act":
        return False
    if str(args.get("kind", "")).lower() != "tap":
        return False
    target = str(args.get("target", "")).strip().lower()
    if target and any(h in target for h in _SEND_HINTS):
        return True
    return False


def _detect_chat_snapshot_flags(result_content):
    """
    Detect lightweight chat-page features in a snapshot result.

    Returns:
    - in_chat_context: whether we appear to be inside a chat
    - input_seen: whether an input field was seen
    - send_seen: whether a send affordance was seen
    """
    text = result_content or ""
    lower = text.lower()

    input_seen = any(marker in text for marker in _CHAT_SNAPSHOT_INPUT_MARKERS_RAW) or any(
        marker in lower for marker in _CHAT_SNAPSHOT_INPUT_MARKERS_LOWER
    )
    send_seen = any(marker in text for marker in _CHAT_SNAPSHOT_SEND_MARKERS_RAW) or any(
        marker in lower for marker in _CHAT_SNAPSHOT_SEND_MARKERS_LOWER
    )
    in_chat_context = input_seen or send_seen
    return in_chat_context, input_seen, send_seen


def _update_runtime_state_after_tool(runtime_state, fn_name, args, result_success, result_content, metadata):
    """Update lightweight runtime state from tool results so the agent keeps task context."""
    if not result_success:
        return

    if fn_name != "device":
        return

    action = str(args.get("action", "")).lower()
    if action == "snapshot":
        # Package checks are gone: a successful snapshot counts as the post-send observation.
        if runtime_state.get("pending_send_verify"):
            runtime_state["pending_send_verify"] = False
            runtime_state["last_send_verified_at_iter"] = runtime_state.get("iteration", 0)

        # Detect chat context from UI text so a momentary foreground-package flip does not mislead.
        in_chat_context, input_seen, send_seen = _detect_chat_snapshot_flags(result_content)
        if in_chat_context:
            runtime_state["in_feishu_chat"] = True
            if input_seen:
                runtime_state["feishu_input_seen"] = True
            if send_seen:
                runtime_state["feishu_send_seen"] = True
    elif action == "act":
        kind = str(args.get("kind", "")).lower()
        if kind == "type" and runtime_state.get("in_feishu_chat"):
            runtime_state["typed_in_feishu_chat"] = True
        if kind == "tap" and (
            _is_send_like_device_tap(fn_name, args) or bool(metadata.get("requires_post_send_snapshot_verify"))
        ):
            runtime_state["pending_send_verify"] = True
            runtime_state["last_send_tap_iter"] = runtime_state.get("iteration", 0)


def _build_runtime_state_hint(runtime_state):
    """Build a short state note so the model continues instead of restarting."""
    hints = []

    if runtime_state.get("in_feishu_chat"):
        hints.append("already inside a Feishu conversation; do not restart from \"open Feishu\"")
    if runtime_state.get("typed_in_feishu_chat"):
        hints.append("text was already entered this turn; continue to send and verify instead of retyping")
    if runtime_state.get("pending_send_verify"):
        hints.append("a send action just ran; observe next (snapshot/screenshot) to confirm the message went out")

    if not hints:
        return None
    return "System state memory: " + "; ".join(hints) + "."


def _msg(role, content, tool_calls=None, tool_call_id=None, name=None):
    """Unified message builder: base fields plus optional ones."""
    message = {"role": role, "content": content or ""}
    optional_fields = {
        "tool_calls": tool_calls,
        "tool_call_id": tool_call_id,
        "name": name,
    }
    for key, value in optional_fields.items():
        if value:
            message[key] = value
    return message


def _build_system_prompt_with_self_counting(system_prompt, reasoning_enabled):
    """
    Python no longer injects extra rules; multiple system messages hurt caching.
    Rules live in assets/bootstrap and are loaded by Kotlin when it builds the system prompt.
    """
    return (system_prompt or "").strip()


def _build_initial_messages(system_prompt, reasoning_enabled, context_history_json, user_message, logger):
    """Build the initial session messages, keeping run_agent free of boilerplate."""
    messages = []
    effective_system_prompt = _build_system_prompt_with_self_counting(system_prompt, reasoning_enabled)
    messages.append(_msg("system", effective_system_prompt))
    logger.write(f"✅ System prompt added ({len(effective_system_prompt)} chars)")

    try:
        ctx_history = json.loads(context_history_json) if context_history_json else []
    except Exception:
        ctx_history = []

    if ctx_history:
        # Match Kotlin: thinking is persisted and restored in UI only, never sent to the model
        filtered = [m for m in ctx_history if m.get("role") not in ("system", "thinking")]
        messages.extend(filtered)
        logger.write(f"✅ Context history added: {len(filtered)} messages")

    messages.append(_msg("user", user_message))
    logger.write(f"✅ User message: {user_message}")
    return messages


def _is_stopped_error(err_msg):
    return "__AGENT_STOPPED__" in (err_msg or "")


def _accumulate_llm_usage(totals, usage_obj):
    """
    Accumulate OpenAI-style usage from Kotlin call_llm into this run's totals.
    totals: dict with prompt/completion/total keys, mutated in place.
    """
    if not usage_obj or not isinstance(usage_obj, dict):
        return
    pt = int(usage_obj.get("prompt_tokens") or 0)
    ct = int(usage_obj.get("completion_tokens") or 0)
    tt = usage_obj.get("total_tokens")
    if tt is not None:
        ttn = int(tt)
    else:
        ttn = pt + ct
    totals["prompt"] += pt
    totals["completion"] += ct
    totals["total"] += ttn


# ---------------------------------------------------------------------------
# Main entry — called from Kotlin
# ---------------------------------------------------------------------------

def run_agent(
    bridge,
    system_prompt,
    user_message,
    context_history_json,
    reasoning_enabled,
    max_iterations,
    context_window_tokens,
):
    """Main Python-side agent loop.

    Args:
        bridge: KotlinBridge object with methods call_llm, execute_tool, emit_progress,
                get_tool_definitions, strip_reasoning_tags.
        system_prompt: str
        user_message: str
        context_history_json: JSON string — list of Message dicts
        reasoning_enabled: bool
        max_iterations: int
        context_window_tokens: int

    Returns:
        JSON string with keys: final_content, tools_used, iterations, messages
    """
    logger = SessionLogger()
    logger.init_session(user_message)

    loop_detector = LoopDetector()
    error_tracker = []

    logger.write("========== Agent Loop start (Python) ==========")
    logger.write(f"Max iterations: {max_iterations}")
    logger.write(f"Reasoning: {'enabled' if reasoning_enabled else 'disabled'}")
    skill_trace = _safe_get_skill_selection_trace(bridge)
    if skill_trace:
        logger.write("🧩 Skill Selection Trace (from Kotlin):")
        for line in skill_trace.splitlines():
            logger.write(f"  {line}")

    # --- Build message list ---
    messages = _build_initial_messages(
        system_prompt=system_prompt,
        reasoning_enabled=reasoning_enabled,
        context_history_json=context_history_json,
        user_message=user_message,
        logger=logger,
    )

    # --- Loop state ---
    tools_used = []
    final_content = None
    last_thinking = ""
    text_only_nudges = 0
    runtime_state = {}
    # Token accumulation across LLM rounds (Kotlin bridge attaches usage per round)
    usage_totals = {"prompt": 0, "completion": 0, "total": 0}

    # With max_iterations==0 the loop never assigns iteration; default it to avoid NameError below.
    iteration = 0
    for iteration in range(1, max_iterations + 1):
        iter_start = time.time()
        runtime_state["iteration"] = iteration
        logger.write(f"========== Iteration {iteration} ==========")
        # Reassigned every round: reasoning from an earlier round must never be shown as the
        # final answer when the last round returns nothing.
        last_thinking = ""

        # Check stop signal from Kotlin at the top of each iteration
        if bridge.is_stop_requested():
            logger.write("🛑 Stop signal received from user, leaving loop")
            final_content = "Stopped at the user's request."
            break

        # Emit iteration progress
        bridge.emit_progress("iteration", json.dumps({"number": iteration}))

        # --- Context management ---
        enforce_context_budget(messages, context_window_tokens)

        # Inject the state note into this round only: persisting it would write a user
        # message and produce duplicated "system state" bubbles in the UI.
        state_hint = _build_runtime_state_hint(runtime_state)
        payload_messages = messages
        if state_hint:
            payload_messages = messages + [_msg("user", state_hint)]
            logger.write(f"🧭 Runtime state hint injected (non-persistent): {state_hint}")

        # --- LLM call ---
        bridge.emit_progress("thinking", json.dumps({"iteration": iteration}))
        llm_start = time.time()

        try:
            response_json = bridge.call_llm(json.dumps(payload_messages), reasoning_enabled, iteration)
        except Exception as e:
            err_msg = str(e)
            # User-requested stop: exit cleanly
            if _is_stopped_error(err_msg):
                logger.write("🛑 Stop signal received during LLM call, leaving loop")
                final_content = "Stopped at the user's request."
                break

            logger.write(f"❌ LLM call failed: {err_msg}")

            if _track_error(error_tracker, err_msg):
                final_content = f"Task failed: {MAX_CONSECUTIVE_ERRORS} identical errors in a row - {err_msg}"
                break

            if "timeout" in err_msg.lower():
                messages.append(_msg("user", "System note: the LLM call timed out. Simplify the task or split it into steps."))
                continue

            final_content = f"❌ Execution error\n\n**Error**: {err_msg}"
            break

        llm_duration_ms = int((time.time() - llm_start) * 1000)

        try:
            response = json.loads(response_json)
        except Exception:
            logger.write("❌ Could not parse the LLM response JSON")
            final_content = "❌ Internal error: could not parse the LLM response"
            break

        _accumulate_llm_usage(usage_totals, response.get("usage"))
        logger.write(f"✅ LLM response received [{llm_duration_ms}ms]")

        # Reasoning
        thinking = response.get("thinking_content")
        if thinking:
            last_thinking = thinking
            logger.write(f"🧠 Reasoning ({len(thinking)} chars)")
            logger.write(f"🧠 Reasoning Content: {_format_reasoning_for_log(thinking)}")
            bridge.emit_progress(
                "reasoning",
                json.dumps({"content": thinking[:2000], "llm_duration": llm_duration_ms}),
            )

        tool_calls = response.get("tool_calls") or []
        content = response.get("content")

        # --- Branch: has tool calls ---
        if tool_calls:
            logger.write(f"Function calls: {len(tool_calls)}")

            # Block reply: emit intermediate text
            intermediate_text = (content or "").strip()
            if intermediate_text:
                logger.write(f"📤 Block reply: {intermediate_text[:200]}...")
                bridge.emit_progress(
                    "block_reply",
                    json.dumps({"text": intermediate_text, "iteration": iteration}),
                )

            # Parse tool args once: reused for the assistant message and the real execution.
            parsed_tool_calls = []
            assistant_tool_calls = []
            for tc in tool_calls:
                normalized_args = parse_tool_args_json(tc.get("arguments", "{}"))
                args_json = json.dumps(normalized_args, ensure_ascii=False)
                parsed_tool_calls.append({
                    "id": tc["id"],
                    "name": tc["name"],
                    "args": normalized_args,
                    "args_json": args_json,
                })
                assistant_tool_calls.append({
                    "id": tc["id"],
                    "name": tc["name"],
                    "arguments": args_json,
                })
            messages.append(_msg("assistant", content, tool_calls=assistant_tool_calls))

            saw_observe = False
            saw_mutate = False
            total_exec_ms = 0
            should_stop = False

            def stop_tool_loop(content_to_set, log_message=None):
                """Single place to sync the stop state inside the tool loop."""
                nonlocal should_stop, final_content
                if log_message:
                    logger.write(log_message)
                should_stop = True
                final_content = content_to_set

            for tc in parsed_tool_calls:
                fn_name = tc["name"]
                args = tc.get("args", {})
                args_json = tc.get("args_json", "{}")
                tc_id = tc["id"]

                logger.write(f"🔧 Function: {fn_name}")
                logger.write(f"   Args: {args_json}")

                # --- Loop detection (pre-execution) ---
                det = loop_detector.detect(fn_name, args)
                if det.is_loop:
                    level_icon = "🚨" if det.is_critical else "⚠️"
                    logger.write(f"{level_icon} Loop detected: {det.detector} (count: {det.count})")

                    if det.is_critical:
                        messages.append(_msg("tool", det.message, tool_call_id=tc_id, name=fn_name))
                        bridge.emit_progress(
                            "loop_detected",
                            json.dumps({
                                "detector": det.detector, "count": det.count,
                                "message": det.message, "critical": True,
                            }),
                        )
                        stop_tool_loop(f"Task failed: {det.message}", "🛑 Critical loop, stopping")
                        break

                    # Warning: inject message but skip tool
                    messages.append(_msg("tool", det.message, tool_call_id=tc_id, name=fn_name))
                    bridge.emit_progress(
                        "loop_detected",
                        json.dumps({
                            "detector": det.detector, "count": det.count,
                            "message": det.message, "critical": False,
                        }),
                    )
                    continue

                # Record call
                loop_detector.record_call(fn_name, args, tool_call_id=tc_id)
                tools_used.append(fn_name)

                bridge.emit_progress(
                    "tool_call",
                    json.dumps({"name": fn_name, "arguments": args}),
                )

                # --- Execute tool (callback to Kotlin) ---
                exec_start = time.time()
                try:
                    result_json = bridge.execute_tool(fn_name, args_json)
                except Exception as e:
                    err_str = str(e)
                    if _is_stopped_error(err_str):
                        stop_tool_loop("Stopped at the user's request.", "🛑 Stop signal received during tool execution, leaving loop")
                        break
                    result_json = json.dumps({"success": False, "content": err_str})

                exec_ms = int((time.time() - exec_start) * 1000)
                total_exec_ms += exec_ms

                try:
                    result = json.loads(result_json)
                except Exception:
                    result = {"success": False, "content": result_json}

                result_success = result.get("success", False)
                result_content = result.get("content", str(result))
                result_metadata = result.get("metadata") or {}
                if not isinstance(result_metadata, dict):
                    result_metadata = {}

                logger.write(f"   Result: {result_success}, {result_content[:200]}")
                logger.write(f"   ⏱️ {exec_ms}ms")

                _update_runtime_state_after_tool(
                    runtime_state=runtime_state,
                    fn_name=fn_name,
                    args=args,
                    result_success=result_success,
                    result_content=result_content,
                    metadata=result_metadata,
                )

                # For snapshot/observe tools: print the full accessibility XML tree to the log.
                # screenshot() output is already printed on the Result line and is not a real XML
                # tree, so only genuine accessibility-tree results get this log block.
                if result_success and is_observe_ui_tool_call(fn_name, args):
                    # device(action="screenshot") is now used for visual grounding; do not reprint the whole result.
                    if not (fn_name == "device" and str(args.get("action", "")).lower() == "screenshot"):
                        logger.write("--- [ACCESSIBILITY TREE START] ---")
                        logger.write(result_content)
                        logger.write("--- [ACCESSIBILITY TREE END] ---")

                # Error tracking
                if not result_success:
                    err = f"{fn_name}: {result_content}"
                    if _track_error(error_tracker, err):
                        should_stop = True
                        final_content = f"Task failed: {MAX_CONSECUTIVE_ERRORS} identical errors in a row - {err}"
                        messages.append(_msg("tool", final_content, tool_call_id=tc_id, name=fn_name))
                        break

                    # Heuristic: any snapshot/ref-related failures should recommend
                    # using screenshot+query for visual grounding on the next iteration.
                    if fn_name == "device" and _is_snapshot_ref_error(result_content):
                        logger.write("📸 snapshot/ref error detected; prefer screenshot+query for pixel-level visual checks next round.")
                        hint = (
                            "System hint: the previous step failed because it relied on snapshot/ref."
                            "Next round, refresh refs with device(action=\"snapshot\") first; if that is still insufficient, use device(action=\"screenshot\", query=\"<description of the target control>\") "
                            "for visual grounding, then continue from the fresh snapshot refs."
                        )
                        messages.append(_msg("tool", hint, tool_call_id=tc_id, name=fn_name))
                else:
                    error_tracker.clear()

                if result_success and is_observe_ui_tool_call(fn_name, args):
                    saw_observe = True

                if result_success and is_mutating_ui_tool_call(fn_name, args):
                    saw_mutate = True

                # Record outcome
                loop_detector.record_outcome(
                    fn_name, args, result_content,
                    error=None if result_success else Exception(result_content),
                    tool_call_id=tc_id,
                )

                # Add tool result message
                result_str = result_content if isinstance(result_content, str) else json.dumps(result)
                messages.append(_msg("tool", result_str, tool_call_id=tc_id, name=fn_name))

                bridge.emit_progress(
                    "tool_result",
                    json.dumps({"name": fn_name, "result": result_str[:500], "exec_duration": exec_ms}),
                )

                # Stop tool
                if fn_name == "stop":
                    stopped = result.get("metadata", {}).get("stopped", False)
                    if stopped:
                        stop_tool_loop(result_content)
                        break

            if should_stop:
                break

            iter_ms = int((time.time() - iter_start) * 1000)
            logger.write(f"⏱️ Iteration total: {iter_ms}ms (LLM: {llm_duration_ms}ms, exec: {total_exec_ms}ms)")
            bridge.emit_progress(
                "iteration_complete",
                json.dumps({
                    "number": iteration, "iteration_duration": iter_ms,
                    "llm_duration": llm_duration_ms, "exec_duration": total_exec_ms,
                }),
            )
            continue

        # --- Branch: no tool calls — possible final answer or premature plan ---
        stripped = _strip_reasoning_tags(content) if content else content

        # `tools_used` used to be required, which made a promise on iteration 1 unfixable: the
        # run ended with "Sure! Let me open YouTube for you." and no tool call. A short promise
        # is nudged on its own; a long answer only once real progress proves a multi-step task.
        should_nudge = (
            stripped
            and text_only_nudges < MAX_TEXT_ONLY_CONTINUATION_NUDGES
            and is_likely_premature_plan_text(stripped)
            and (bool(tools_used) or len(stripped) <= FIRST_TURN_PROMISE_MAX_CHARS)
        )

        if should_nudge:
            text_only_nudges += 1
            logger.write(
                f"🔁 no tool_calls but the text still reads like an unfinished task "
                f"(continuation nudge {text_only_nudges}/{MAX_TEXT_ONLY_CONTINUATION_NUDGES})"
            )
            messages.append(_msg("assistant", stripped))
            messages.append(_msg("user", TEXT_ONLY_CONTINUATION_NUDGE))
            if stripped.strip():
                bridge.emit_progress(
                    "block_reply",
                    json.dumps({"text": stripped, "iteration": iteration}),
                )
            iter_ms = int((time.time() - iter_start) * 1000)
            bridge.emit_progress(
                "iteration_complete",
                json.dumps({
                    "number": iteration, "iteration_duration": iter_ms,
                    "llm_duration": llm_duration_ms, "exec_duration": 0,
                }),
            )
            continue

        # Final answer. Keep None when empty so the post-loop reasoning fallback still runs.
        final_content = stripped or None
        messages.append(_msg("assistant", final_content))
        logger.write(f"Final content received")
        logger.write(f"Content: {(final_content or '')[:500]}")
        break

    # --- Post-loop ---
    if final_content is None and iteration >= max_iterations:
        logger.write(f"Max iterations ({max_iterations}) reached")
        final_content = f"Reached the iteration limit ({max_iterations}) before finishing. Split the task into smaller steps."

    # Some free upstream models (e.g. deepseek-v4.1-flash-free via bansos) put the whole
    # reply in `reasoning` and return content="" — the bubble would render blank.
    if not (final_content or "").strip():
        final_content = last_thinking.strip() or "The model returned an empty reply. Try again or pick another model."
    final_content = final_content or ""

    logger.write("========== Agent Loop end (Python) ==========")
    logger.write(f"Iterations: {iteration}")
    logger.write(f"Tools used: {', '.join(tools_used)}")
    logger.write(
        f"Token usage (LLM totals, prompt+completion+total summed per round): "
        f"prompt={usage_totals['prompt']} completion={usage_totals['completion']} total={usage_totals['total']}"
    )

    logger.finalize(iteration, tools_used, len(final_content))

    return json.dumps({
        "final_content": final_content,
        "tools_used": list(set(tools_used)),
        "iterations": iteration,
        "messages": messages,
        "usage": {
            "prompt_tokens": usage_totals["prompt"],
            "completion_tokens": usage_totals["completion"],
            "total_tokens": usage_totals["total"],
        },
    }, ensure_ascii=False)


def _track_error(tracker, error_message):
    """Record error; return True if MAX_CONSECUTIVE_ERRORS identical errors reached."""
    tracker.append(error_message)
    if len(tracker) > MAX_CONSECUTIVE_ERRORS:
        tracker.pop(0)
    if len(tracker) >= MAX_CONSECUTIVE_ERRORS:
        return all(e == error_message for e in tracker)
    return False
