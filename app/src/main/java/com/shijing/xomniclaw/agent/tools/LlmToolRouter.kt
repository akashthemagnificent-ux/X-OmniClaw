package com.shijing.xomniclaw.agent.tools

import android.util.Log
import com.shijing.xomniclaw.providers.llm.systemMessage
import com.shijing.xomniclaw.providers.llm.userMessage
import org.json.JSONArray
import org.json.JSONObject
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.providers.LLMResponse
import kotlinx.coroutines.CancellationException

/**
 * **LLM-as-a-Router**：首跳不调 function-calling 全量 tools，只让模型在紧凑清单里
 * 选「需注入完整 Schema 的按需工具名」，并可选给主 Agent 一句 [hint]（Cascading 参数线索）。
 */
object LlmToolRouter {

    private const val TAG = "LlmToolRouter"

    /** 关闭时由 [com.shijing.xomniclaw.agent.loop.AgentLoop] 退化为关键词启发式 [LlmOnDemandToolInclusion]。 */
    const val ENABLED: Boolean = true

    private const val ROUTER_MAX_TOKENS = 420
    private const val SYS_SNIPPET_MAX = 1_200

    /**
     * 路由成功：按需工具名(已白名单过滤) + 可选 [hint]；解析失败为 null 由上游走启发式。
     */
    data class Outcome(
        val onDemandNames: Set<String>,
        val hint: String?
    )

    fun systemPromptText(): String = """
You are the tool router. You have **no** function-calling ability. Given the user question and the system notes, choose the subset of "routable on-demand tool names" most likely needed **this turn** (mostly memory/gallery/scheduling/app install/system settings); if none are needed, tools is empty.

Output **exactly one** JSON object and nothing else: no Markdown, no code fences, no explanation, no prefix or suffix. Format:
{"tools":["name1",...] ,"hint":""}
- tools: only English identifiers listed under "routable tool names" above; [] means no on-demand large tools this turn
- hint: optional; a **very short** keyword or slot for the main Agent to follow (e.g. search term, time slot, guessed app name); empty string if none
    """.trimIndent()

    fun buildRouterUserText(
        routableNamesSorted: List<String>,
        userMessage: String,
        systemPromptSnippet: String
    ): String = buildString {
        appendLine("[Routable on-demand tool names]")
        appendLine(routableNamesSorted.joinToString(", "))
        appendLine()
        appendLine("[User question]")
        appendLine(userMessage)
        if (systemPromptSnippet.isNotBlank()) {
            appendLine()
            appendLine("[Main system prompt (truncated, context only)]")
            append(systemPromptSnippet.take(SYS_SNIPPET_MAX))
        }
    }

    /**
     * 从可注册名中筛出**当前运行时可路由**的按需名(与 ON_DEMAND 交集中已在 ToolRegistry 或 AndroidToolRegistry 中存在的名)。
     */
    fun buildRoutableNameList(
        onDemandSet: Set<String>,
        toolRegistryNames: Set<String>,
        androidRegistryNames: Set<String>
    ): List<String> {
        val present = toolRegistryNames + androidRegistryNames
        return onDemandSet.filter { it in present }.sorted()
    }

    /**
     * 从模型正文中解出 JSON: 支持 `{"tools":[...], "hint":"..."} ` 或纯 `["a","b"]`。
     */
    fun parseRouterModelContent(raw: String, allowed: Set<String>): Outcome? {
        val t = stripThinkAndFences(raw).trim()
        if (t.isEmpty()) return null
        return try {
            when {
                t.startsWith("[") -> {
                    val arr = JSONArray(t)
                    val set = (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).trim().takeIf { it in allowed }
                    }.toSet()
                    Outcome(set, null)
                }
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    val out = linkedSetOf<String>()
                    o.optJSONArray("tools")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val n = arr.optString(i).trim()
                            if (n.isNotBlank() && n in allowed) out.add(n)
                        }
                    } ?: o.optJSONArray("工具")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val n = arr.optString(i).trim()
                            if (n.isNotBlank() && n in allowed) out.add(n)
                        }
                    }
                    val h = when {
                        o.has("hint") -> o.optString("hint", "")
                        o.has("clue") -> o.optString("clue", "")
                        o.has("query_hint") -> o.optString("query_hint", "")
                        else -> ""
                    }.trim()
                    Outcome(out, h.ifBlank { null })
                }
                else -> {
                    // 从夹杂文本中找第一段 [...] 或 {...}
                    val b1 = t.indexOf('[').let { i -> if (i < 0) -1 to -1 else i to t.indexOf(']', i) }
                    if (b1.first >= 0 && b1.second > b1.first) {
                        val slice = t.substring(b1.first, b1.second + 1)
                        return parseRouterModelContent(slice, allowed)
                    }
                    val j = t.indexOf('{')
                    val j2 = t.lastIndexOf('}')
                    if (j >= 0 && j2 > j) {
                        return parseRouterModelContent(t.substring(j, j2 + 1), allowed)
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseRouterModelContent: ${e.message}", e)
            null
        }
    }

    private val THINKING_BLOCK = Regex("""<(?:think|thinking|reasoning)[^>]*>[\s\S]*?</(?:think|thinking|reasoning)>""", RegexOption.IGNORE_CASE)
    private val CODE_FENCE = Regex("""^```(?:json)?\s*|[\r\n]+```$""", RegexOption.MULTILINE)

    private fun stripThinkAndFences(s: String): String {
        var x = s
        if (x.contains("```")) {
            x = x.replace(CODE_FENCE, "").trim()
            val f = x.indexOf("```")
            if (f >= 0) {
                var rest = x.removeRange(f, minOf(f + 3, x.length))
                val f2 = rest.indexOf("```")
                if (f2 >= 0) {
                    val inner = rest.substring(0, f2).trim()
                    if (inner.startsWith("{") || inner.startsWith("[")) x = inner
                }
            }
        }
        return x.replace(THINKING_BLOCK, "").trim()
    }

    /**
     * 执行首跳 LLM(无 tools)并解析; 失败返回 null。
     */
    suspend fun runRouterLlm(
        provider: UnifiedLLMProvider,
        modelRef: String?,
        userMessage: String,
        systemPrompt: String,
        routableNames: List<String>
    ): Outcome? {
        if (routableNames.isEmpty()) return null
        val allowed = routableNames.toSet()
        val userText = buildRouterUserText(
            routableNamesSorted = routableNames,
            userMessage = userMessage,
            systemPromptSnippet = systemPrompt
        )
        val messages = listOf(
            systemMessage(systemPromptText()),
            userMessage(userText)
        )
        // 与主循环区分 dump 的 iteration; 用完后恢复，避免影响后续 call_llm 的 rememberRequest 行为。
        val prevIterHint = provider.currentIterationHint
        return try {
            provider.currentIterationHint = 0
            val resp: LLMResponse = provider.chatWithTools(
                messages = messages,
                tools = null,
                modelRef = modelRef,
                temperature = 0.1,
                maxTokens = ROUTER_MAX_TOKENS,
                reasoningEnabled = false
            )
            val content = (resp.content?.trim() ?: resp.thinkingContent?.trim() ?: "")
            if (content.isEmpty()) {
                Log.w(TAG, "router: empty model content")
                return null
            }
            val parsed = parseRouterModelContent(content, allowed) ?: return null
            Log.i(
                TAG,
                "router outcome tools=${parsed.onDemandNames} hint=${
                    (parsed.hint?.take(120) ?: "null")
                }"
            )
            parsed
        } catch (e: CancellationException) {
            // 协程取消必须向上传播，不能当作「路由失败」吞掉
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "runRouterLlm failed: ${e.message}", e)
            null
        } finally {
            provider.currentIterationHint = prevIterHint
        }
    }
}
