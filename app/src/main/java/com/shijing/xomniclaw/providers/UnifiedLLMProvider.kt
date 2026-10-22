package com.shijing.xomniclaw.providers

/**
 * X-OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/pi-embedded-runner/(all)
 * - ../xomniclaw/src/agents/model-(all)
 *
 * X-OmniClaw adaptation: unified provider dispatch for Android.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.ModelApi
import com.shijing.xomniclaw.config.ModelDefinition
import com.shijing.xomniclaw.config.ProviderConfig
import com.shijing.xomniclaw.providers.llm.Message
import com.shijing.xomniclaw.providers.llm.ToolDefinition as NewToolDefinition
import com.shijing.xomniclaw.providers.llm.FunctionDefinition as NewFunctionDefinition
import com.shijing.xomniclaw.providers.llm.ParametersSchema as NewParametersSchema
import com.shijing.xomniclaw.providers.llm.PropertySchema as NewPropertySchema
import com.shijing.xomniclaw.util.PromptArtifactNaming
import com.shijing.xomniclaw.util.MMKVKeys
import com.shijing.xomniclaw.util.LlmFullRequestLogcat
import com.shijing.xomniclaw.util.requestBodyForWire
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 统一 LLM Provider
 * Supports all X-OmniClaw compatible API types
 *
 * Features:
 * 1. Automatically load provider and model info from config files
 * 2. Support multiple API formats (OpenAI, Anthropic, Ollama, etc.)
 * 3. Use ApiAdapter to handle differences between different APIs
 * 4. Support Extended Thinking / Reasoning
 * 5. Support custom headers and authentication methods
 *
 * Reference: X-OmniClaw src/agents/llm-client.ts
 */
class UnifiedLLMProvider(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedLLMProvider"
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_TEMPERATURE = 0.7

        @Volatile
        private var loggedDumpPromptDisabledHint = false
    }

    private val configLoader = ConfigLoader(context)

    /** Set by KotlinBridge before each call so dump filenames include the iteration index. */
    @Volatile
    var currentIterationHint: Int = 0

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 转换旧的 ToolDefinition 到新格式
     */
    private fun convertToolDefinition(old: ToolDefinition): NewToolDefinition {
        return NewToolDefinition(
            type = old.type,
            function = NewFunctionDefinition(
                name = old.function.name,
                description = old.function.description,
                parameters = NewParametersSchema(
                    type = old.function.parameters.type,
                    properties = old.function.parameters.properties.mapValues { (_, prop) ->
                        convertPropertySchema(prop)
                    },
                    required = old.function.parameters.required
                )
            )
        )
    }

    private fun convertPropertySchema(old: PropertySchema): NewPropertySchema {
        return NewPropertySchema(
            type = old.type,
            description = old.description,
            enum = old.enum,
            items = old.items?.let { convertPropertySchema(it) },
            properties = old.properties?.mapValues { (_, child) -> convertPropertySchema(child) }
        )
    }

    /**
     * 带工具调用的聊天
     *
     * @param messages Message list
     * @param tools Tool definition list (old format)
     * @param modelRef Model reference, format: provider/model-id or just model-id
     * @param temperature Temperature parameter
     * @param maxTokens Maximum generated tokens
     * @param reasoningEnabled Whether to enable reasoning mode
     */
    suspend fun chatWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        modelRef: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int? = null,
        reasoningEnabled: Boolean = false,
        maxRetries: Int = 3
    ): LLMResponse = withContext(Dispatchers.IO) {
        // Convert tool definitions to new format
        val newTools = tools?.map { convertToolDefinition(it) }

        // Retry logic
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                return@withContext performRequest(
                    messages, newTools, modelRef, temperature, maxTokens, reasoningEnabled
                )
            } catch (e: LLMException) {
                lastException = e

                // Check if retryable
                if (!isRetryable(e) || attempt == maxRetries) {
                    throw e
                }

                // Exponential backoff — longer for rate limit (429)
                val isRateLimit = e.message?.contains("429") == true || e.message?.contains("rate limit", ignoreCase = true) == true
                val baseDelay = if (isRateLimit) 5000L else 1000L  // 5s for 429, 1s for others
                val delayMs = baseDelay * attempt  // 5s, 10s, 15s (429) or 1s, 2s, 3s (others)
                Log.w(TAG, "⚠️ LLM request failed (attempt $attempt/$maxRetries), retrying in ${delayMs}ms: ${e.message}")
                delay(delayMs)
            }
        }

        // Should not reach here
        throw lastException!!
    }

    /**
     * 执行实际的 LLM 请求
     */
    private suspend fun performRequest(
        messages: List<Message>,
        tools: List<com.shijing.xomniclaw.providers.llm.ToolDefinition>?,
        modelRef: String?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): LLMResponse {
        try {
            // Parse model reference
            val (providerName, modelId) = parseModelRef(modelRef)

            // Load provider and model config
            val provider = configLoader.getProviderConfig(providerName)
                ?: throw IllegalArgumentException("Provider not found: $providerName")

            val model = provider.models.find { it.id == modelId }
                ?: throw IllegalArgumentException("Model not found: $modelId in provider: $providerName")

            // OpenRouter 对无效/缺失 Key 统一返回 401 + User not found；不再使用已下线的内置 Key，须显式配置
            if (providerName == "openrouter") {
                val k = provider.apiKey?.trim()
                if (k.isNullOrEmpty() || k == "未配置" || k.startsWith("\${")) {
                    throw LLMException(
                        "OpenRouter has no valid API key configured. Enter your personal key (starting with sk-or-v1-) " +
                            "in the app's Model Configuration, or edit /sdcard/.xomniclaw/xomniclaw.json and set " +
                            "models.providers.openrouter.apiKey to the real string (leaving \${OPENROUTER_API_KEY} " +
                            "in place without that system environment variable set injects no key)."
                    )
                }
            }

            val declaredApi = model.api ?: provider.api
            val forceMimimaxToImageApi = shouldForceMimimaxImageApi(
                providerName = providerName,
                declaredApi = declaredApi,
                messages = messages
            )
            val fallbackMiniMaxImageToOpenAI = shouldAutoFallbackMiniMaxImageApi(
                declaredApi = if (forceMimimaxToImageApi) {
                    ModelApi.MINIMAX_IMAGES_UNDERSTAND
                } else {
                    declaredApi
                },
                messages = messages
            )
            val effectiveApi = when {
                forceMimimaxToImageApi -> {
                    Log.w(
                        TAG,
                        "⚠️ provider=mimimax and this request contains images; switching the API to minimax-images-understand."
                    )
                    ModelApi.MINIMAX_IMAGES_UNDERSTAND
                }
                fallbackMiniMaxImageToOpenAI -> {
                    Log.w(
                        TAG,
                        "⚠️ API=minimax-images-understand but this request has no images; " +
                            "falling back to openai-completions so plain text does not hit /images/understand."
                    )
                    ModelApi.OPENAI_COMPLETIONS
                }
                else -> declaredApi
            }
            val effectiveModel = if (effectiveApi != declaredApi) {
                model.copy(api = effectiveApi)
            } else {
                model
            }
            // 历史版本曾支持 bot-adaptor 多格式回退，已移除；统一走 OpenAI 兼容组包
            val requestPlans = listOf(
                LlmRequestPlan(
                    body = ApiAdapter.buildRequestBody(
                        provider = provider,
                        model = effectiveModel,
                        messages = messages,
                        tools = tools,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        reasoningEnabled = reasoningEnabled
                    ),
                    multimodalFormat = "standard"
                )
            )

            val headers = ApiAdapter.buildHeaders(provider, effectiveModel)

            // Build complete API endpoint
            val apiUrl = buildApiUrl(provider, effectiveModel)

            // 让 prompt dump 和后续截图共享同一份 query/iter/timestamp 命名上下文。
            PromptArtifactNaming.rememberRequest(messages, currentIterationHint)

            var lastException: Exception? = null
            requestPlans.forEachIndexed { index, plan ->
                val finalRequestBody = normalizeOpenAiTokenField(effectiveModel, JSONObject(plan.body.toString()))
                val wireBody = finalRequestBody.requestBodyForWire()
                try {
                    val parsed = executeRequest(
                        providerName = providerName,
                        modelId = modelId,
                        apiUrl = apiUrl,
                        messages = messages,
                        requestBody = finalRequestBody,
                        wireRequestBody = wireBody,
                        headers = headers,
                        api = effectiveApi,
                        requestFormat = plan.multimodalFormat
                    )

                    return LLMResponse(
                        content = parsed.content,
                        toolCalls = parsed.toolCalls?.map { tc ->
                            LLMToolCall(
                                id = tc.id,
                                name = tc.name,
                                arguments = tc.arguments
                            )
                        },
                        thinkingContent = parsed.thinkingContent,
                        usage = parsed.usage?.let {
                            LLMUsage(
                                promptTokens = it.promptTokens,
                                completionTokens = it.completionTokens,
                                totalTokens = it.totalTokens
                            )
                        },
                        finishReason = parsed.finishReason
                    )
                } catch (e: Exception) {
                    lastException = e
                    val hasFallback = index < requestPlans.lastIndex
                    if (!hasFallback) {
                        throw e
                    }
                    Log.w(
                        TAG,
                        "⚠️ Request format '${plan.multimodalFormat}' failed, trying fallback: ${e.message}"
                    )
                }
            }

            throw lastException ?: LLMException("LLM request failed without response")

        } catch (e: Exception) {
            Log.e(TAG, "❌ LLM request failed", e)
            throw LLMException("LLM request failed: ${e.message}", e)
        }
    }

    private fun normalizeOpenAiTokenField(model: ModelDefinition, requestBody: JSONObject): JSONObject {
        val modelIdLower = model.id.lowercase()
        val requiresMaxCompletionTokens = modelIdLower.startsWith("gpt-5") ||
            modelIdLower.startsWith("o1") ||
            modelIdLower.startsWith("o3") ||
            modelIdLower.startsWith("gpt-4.1")

        if (!requiresMaxCompletionTokens) return requestBody
        if (requestBody.has("max_tokens")) {
            val value = requestBody.get("max_tokens")
            requestBody.remove("max_tokens")
            if (!requestBody.has("max_completion_tokens")) {
                requestBody.put("max_completion_tokens", value)
            }
        }
        return requestBody
    }

    /**
     * 在 HTTP 非 2xx 时补充可操作的排查说明（不修改上游 JSON 原文，仅追加提示串）。
     * OpenRouter 常见情况：401 + User not found（Key 无效）；429 + rate-limited（尤其 :free 上游限流或配额不足）。
     */
    private fun buildHttpErrorHint(
        code: Int,
        errorBody: String,
        apiUrl: String,
        providerName: String
    ): String {
        val openRouter = apiUrl.contains("openrouter.ai", ignoreCase = true) ||
            providerName.equals("openrouter", ignoreCase = true)

        if (code == 401) {
            if (openRouter && errorBody.contains("User not found", ignoreCase = true)) {
                return " | Hint: this is OpenRouter's 401, meaning the current API key is not valid for the account " +
                    "(mistyped, revoked, or an expired built-in key). Enter your own sk-or-v1-... in the app's " +
                    "Model Configuration, or create a new key at https://openrouter.ai/keys."
            }
            if (openRouter) {
                return " | Hint: OpenRouter returned 401. Check that the API key (sk-or-v1-...) matches the one on your keys page."
            }
            return " | Hint: 401 means authentication failed. Check the apiKey and baseUrl for `$providerName`."
        }

        // OpenRouter 429：免费模型常出现上游临时限流（metadata.raw 中 rate-limited upstream）
        if (code == 429 && openRouter) {
            val freeTier = errorBody.contains(":free", ignoreCase = true) ||
                errorBody.contains("rate-limited", ignoreCase = true) ||
                errorBody.contains("rate limit", ignoreCase = true)
            return if (freeTier) {
                " | Hint: this is OpenRouter's 429 rate limit (common with :free models or when an upstream such as " +
                    "Google is short on quota). Retry later; attach or top up your own key at " +
                    "https://openrouter.ai/settings/integrations to raise the limit; or pick another preset model " +
                    "in Model Configuration (another :free model, or a paid one)."
            } else {
                " | Hint: OpenRouter returned 429, meaning requests were too frequent or the account hit its usage " +
                    "limit. Retry later and check your OpenRouter usage/plan."
            }
        }

        return ""
    }

    private fun executeRequest(
        providerName: String,
        modelId: String,
        apiUrl: String,
        messages: List<Message>,
        requestBody: JSONObject,
        /** 与实际上传一致（已去掉 JSON 中可选的 `\\/` 转义以省 token） */
        wireRequestBody: String,
        headers: okhttp3.Headers,
        api: String,
        requestFormat: String
    ): ParsedResponse {
        maybeDumpPrompt(
            providerName = providerName,
            modelId = modelId,
            apiUrl = apiUrl,
            messages = messages,
            requestBody = requestBody,
            headers = headers
        )

        LlmFullRequestLogcat.logIfEnabled(
            wireRequestBody = wireRequestBody,
            providerName = providerName,
            modelId = modelId,
            apiUrl = apiUrl,
            requestFormat = requestFormat
        )

        val request = Request.Builder()
            .url(apiUrl)
            .headers(headers)
            .post(wireRequestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "❌ API Error (${response.code}) [$requestFormat]: $errorBody")
            val authHint = buildHttpErrorHint(
                code = response.code,
                errorBody = errorBody,
                apiUrl = apiUrl,
                providerName = providerName
            )
            throw LLMException("API request failed: ${response.code} - $errorBody$authHint")
        }

        val responseBody = response.body?.string()
            ?: throw LLMException("Empty response body")

        val trimmed = responseBody.trimStart()
        if (trimmed.startsWith("<") || trimmed.startsWith("<!")) {
            Log.e(TAG, "❌ API returned HTML instead of JSON — check baseUrl and API key")
            throw LLMException(
                "API returned an HTML page instead of JSON. " +
                    "This usually means the baseUrl is wrong or the API key is invalid. " +
                    "URL: $apiUrl"
            )
        }

        return ApiAdapter.parseResponse(api, responseBody)
    }

    private fun maybeDumpPrompt(
        providerName: String,
        modelId: String,
        apiUrl: String,
        messages: List<Message>,
        requestBody: JSONObject,
        headers: okhttp3.Headers
    ) {
        val config = try {
            configLoader.loadOmniClawConfig()
        } catch (e: Exception) {
            Log.w(TAG, "Skip prompt dump: failed to load config: ${e.message}")
            return
        }
        val settingsEnabled = try {
            MMKV.defaultMMKV().decodeBool(MMKVKeys.PROMPT_DUMPS_ENABLED.key, false)
        } catch (e: Exception) {
            Log.w(TAG, "Skip prompt dump: failed to load settings switch: ${e.message}")
            false
        }
        val logging = config.logging
        // 需要同时满足：配置允许 + 设置页开关开启。
        if (!logging.dumpPrompt || !settingsEnabled) {
            if (!loggedDumpPromptDisabledHint) {
                loggedDumpPromptDisabledHint = true
                Log.w(
                    TAG,
                    "Prompt dump disabled (logging.dumpPrompt=${logging.dumpPrompt}, " +
                        "settings.switch=$settingsEnabled). Enable \"Prompt Dumps\" on the settings page and set " +
                        "logging.dumpPrompt=true to write each request to /sdcard/.xomniclaw/workspace/logs/prompt-dumps/."
                )
            }
            return
        }

        try {
            val maxChars = logging.dumpPromptMaxChars.coerceAtLeast(20_000)
            val dumpDir = File("/sdcard/.xomniclaw/workspace/logs/prompt-dumps")
            dumpDir.mkdirs()

            val ts = PromptArtifactNaming.getRememberedTimestamp()
            val filename = PromptArtifactNaming.buildPromptDumpFilename(
                timestamp = ts,
                messages = messages,
                iteration = currentIterationHint,
                providerName = providerName,
                modelId = modelId
            )
            val dumpFile = File(dumpDir, filename)

            val safeHeaders = JSONObject()
            headers.names().forEach { name ->
                if (name.equals("authorization", ignoreCase = true)) {
                    safeHeaders.put(name, "<redacted>")
                } else {
                    safeHeaders.put(name, headers[name] ?: "")
                }
            }

            val root = JSONObject()
            root.put("timestamp", ts)
            root.put("provider", providerName)
            root.put("model", modelId)
            root.put("apiUrl", apiUrl)
            root.put("messageCount", messages.size)
            root.put("estimatedPromptTokens", estimateTokens(messages))
            root.put("headers", safeHeaders)
            root.put("requestBody", requestBody)

            val pretty = root.toString(2)
            val content = if (pretty.length > maxChars) {
                pretty.take(maxChars) + "\n\n... [truncated by logging.dumpPromptMaxChars]"
            } else {
                pretty
            }
            dumpFile.writeText(content)
            Log.i(TAG, "📝 Prompt dumped: ${dumpFile.absolutePath}")

            // 摄像头/语音等「含 base64 图」的请求：主 dump 极易超过 dumpPromptMaxChars 被截断，或难以阅读。
            // 另写小体积可读版：保留每条消息的文本，每图只记录 char 数，便于核对“送给大模型的实际语义”。
            buildMultimodalReadableSnapshot(messages, providerName, modelId, apiUrl)?.let { snap ->
                val base = filename.removeSuffix(".json")
                val readable = File(dumpDir, "${base}_readable.json")
                readable.writeText(snap.toString(2), Charsets.UTF_8)
                Log.i(TAG, "📝 Prompt readable snapshot (no base64): ${readable.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dump prompt: ${e.message}")
        }
    }

    /**
     * 若本次请求在 [Message] 层带图片（如 LocalVoiceVisionHub 的 imageDataUrls），
     * 生成可读的 prompt 快照常用于对照；无图则返回 null，不落副文件。
     */
    private fun buildMultimodalReadableSnapshot(
        messages: List<Message>,
        providerName: String,
        modelId: String,
        apiUrl: String
    ): JSONObject? {
        var hasImage = false
        var totalImageChars = 0L
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            val urls = m.imageDataUrls
            if (!urls.isNullOrEmpty()) {
                hasImage = true
                val lengths = JSONArray()
                for (u in urls) {
                    val n = u.length
                    totalImageChars += n
                    lengths.put(n)
                }
                o.put("imageCount", urls.size)
                o.put("perImageDataUrlOrBase64CharCount", lengths)
            }
            m.name?.let { o.put("name", it) }
            arr.put(o)
        }
        if (!hasImage) return null
        return JSONObject()
            .put("dumpKind", "multimodal_readable")
            .put("provider", providerName)
            .put("model", modelId)
            .put("apiUrl", apiUrl)
            .put("note", "For the full requestBody including base64 in this batch, see the .json without the _readable suffix in the same directory. Voice/camera requests go upstream through LocalVoiceVisionHub.")
            .put("totalApproxImageFieldChars", totalImageChars)
            .put("messages", arr)
    }

    private fun estimateTokens(messages: List<Message>): Int {
        val chars = messages.sumOf { msg ->
            msg.content.length +
                (msg.name?.length ?: 0) +
                (msg.toolCallId?.length ?: 0) +
                (msg.imageDataUrls?.sumOf { it.length }?.toInt() ?: 0) +
                (msg.toolCalls?.sumOf { it.name.length + it.arguments.length + it.id.length } ?: 0)
        }
        return chars / 4
    }

    /**
     * 判断错误是否可重试
     */
    private fun isRetryable(exception: LLMException): Boolean {
        val message = exception.message?.lowercase() ?: ""

        return when {
            // Rate limiting
            message.contains("rate limit") || message.contains("429") -> true
            // Service unavailable
            message.contains("503") || message.contains("service unavailable") -> true
            // Timeout
            message.contains("timeout") || message.contains("timed out") -> true
            // Server errors
            message.contains("500") || message.contains("502") || message.contains("504") -> true
            // Connection issues
            message.contains("connection") || message.contains("network") -> true
            // Overloaded
            message.contains("overloaded") -> true
            // Default: not retryable
            else -> false
        }
    }

    /**
     * 简单聊天（无工具）
     */
    suspend fun simpleChat(
        userMessage: String,
        systemPrompt: String? = null,
        modelRef: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int? = null
    ): String {
        val messages = mutableListOf<Message>()

        if (systemPrompt != null) {
            messages.add(Message(role = "system", content = systemPrompt))
        }

        messages.add(Message(role = "user", content = userMessage))

        val response = chatWithTools(
            messages = messages,
            tools = null,
            modelRef = modelRef,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEnabled = false
        )

        return response.content ?: throw LLMException("No content in response")
    }

    /**
     * 解析模型引用
     * Format: "provider/model-id" or "model-id"
     *
     * @return Pair(providerName, modelId)
     */
    private fun parseModelRef(modelRef: String?): Pair<String, String> {
        // If not specified, use default model
        if (modelRef == null) {
            val config = configLoader.loadOmniClawConfig()
            val defaultModel = config.resolveDefaultModel()
            // If the default model's provider exists, use it
            val parsed = tryParseModelRef(defaultModel)
            if (parsed != null) return parsed

            // Fallback: use the first available provider/model
            val providers = config.resolveProviders()
            val firstEntry = providers.entries.firstOrNull()
            if (firstEntry != null) {
                val firstModel = firstEntry.value.models.firstOrNull()
                if (firstModel != null) {
                    Log.w(TAG, "⚠️ 默认模型 '$defaultModel' 的 provider 不存在，fallback 到 '${firstEntry.key}/${firstModel.id}'")
                    return Pair(firstEntry.key, firstModel.id)
                }
            }
            throw IllegalArgumentException(
                "No usable model configuration; configure a model first. " +
                    "Make sure models.providers in /sdcard/.xomniclaw/xomniclaw.json is not empty and that " +
                    "agents.defaults.model.primary points at an existing provider/model id. " +
                    "If Feishu was enabled without appId/appSecret, update the app or temporarily set " +
                    "channels.feishu.enabled to false."
            )
        }

        return tryParseModelRef(modelRef)
            ?: throw IllegalArgumentException("Invalid model reference: $modelRef")
    }

    /**
     * 尝试解析模型引用，找不到时返回 null 而不是抛异常
     */
    private fun tryParseModelRef(modelRef: String): Pair<String, String>? {
        // Step 1: Try to find complete modelRef as model ID
        val providerForFullId = configLoader.findProviderByModelId(modelRef)
        if (providerForFullId != null) {
            return Pair(providerForFullId, modelRef)
        }

        // Step 2: Parse as "provider/model-id" format
        val parts = modelRef.split("/", limit = 2)
        return when (parts.size) {
            2 -> {
                // Verify provider exists
                val providerConfig = configLoader.getProviderConfig(parts[0])
                if (providerConfig != null) Pair(parts[0], parts[1]) else null
            }
            1 -> {
                // "model-id" format, find corresponding provider
                val providerName = configLoader.findProviderByModelId(parts[0])
                if (providerName != null) Pair(providerName, parts[0]) else null
            }
            else -> null
        }
    }

    /**
     * minimax-images-understand 仅适用于图像输入。
     * 若本次消息没有图片，自动回退到 OpenAI chat/completions，避免把纯文本请求打到 /images/understand。
     */
    private fun shouldAutoFallbackMiniMaxImageApi(
        declaredApi: String,
        messages: List<Message>
    ): Boolean {
        if (declaredApi != ModelApi.MINIMAX_IMAGES_UNDERSTAND) return false
        return messages.none { msg ->
            msg.role == "user" && !msg.imageDataUrls.isNullOrEmpty()
        }
    }

    /**
     * 兼容历史配置：若 provider 已选 mimimax 且本次有图，优先强制走 images/understand。
     * 这样即使配置里 api 仍是 openai-completions，也能自动纠正到图像接口。
     */
    private fun shouldForceMimimaxImageApi(
        providerName: String,
        declaredApi: String,
        messages: List<Message>
    ): Boolean {
        if (providerName != "mimimax") return false
        if (declaredApi == ModelApi.MINIMAX_IMAGES_UNDERSTAND) return false
        return messages.any { msg ->
            msg.role == "user" && !msg.imageDataUrls.isNullOrEmpty()
        }
    }

    /**
     * 构建 API URL
     */
    private fun buildApiUrl(provider: ProviderConfig, model: ModelDefinition): String {
        val baseUrl = provider.baseUrl.trimEnd('/')
        val api = model.api ?: provider.api

        return when (api) {
            ModelApi.ANTHROPIC_MESSAGES -> {
                appendPathIfMissing(baseUrl, "/v1/messages")
            }
            ModelApi.OPENAI_COMPLETIONS -> {
                appendPathIfMissing(baseUrl, "/chat/completions")
            }
            ModelApi.OPENAI_RESPONSES,
            ModelApi.OPENAI_CODEX_RESPONSES -> {
                appendPathIfMissing(baseUrl, "/responses")
            }
            ModelApi.OLLAMA -> {
                appendPathIfMissing(baseUrl, "/api/chat")
            }
            ModelApi.GITHUB_COPILOT -> {
                appendPathIfMissing(baseUrl, "/chat/completions")
            }
            ModelApi.MINIMAX_IMAGES_UNDERSTAND -> {
                if (baseUrl.lowercase().contains("mimimax.cn")) {
                    // mimimax 中转固定走 /v1/images/understand，避免用户填成 /chat/completions 导致错路由。
                    buildMimimaxImagesEndpoint(baseUrl)
                } else {
                    appendPathIfMissing(baseUrl, "/images/understand")
                }
            }
            ModelApi.BEDROCK_CONVERSE_STREAM -> {
                // AWS Bedrock needs special handling
                "$baseUrl/model/${model.id}/converse-stream"
            }
            else -> {
                // Default to OpenAI compatible endpoint
                appendPathIfMissing(baseUrl, "/chat/completions")
            }
        }
    }

    /**
     * 若 baseUrl 已包含目标路径，则直接返回；否则补齐路径，避免出现 `/xxx/yyy/yyy` 这种重复。
     */
    private fun appendPathIfMissing(baseUrl: String, suffix: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedSuffix = suffix.trim()
        return if (normalizedBase.lowercase().endsWith(normalizedSuffix.lowercase())) {
            normalizedBase
        } else {
            normalizedBase + normalizedSuffix
        }
    }

    private fun buildMimimaxImagesEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        val lower = normalized.lowercase()
        val v1Base = when {
            lower.endsWith("/v1") -> normalized
            lower.contains("/v1/images/understand") -> normalized.substringBefore("/images/understand")
            lower.contains("/v1/chat/completions") -> normalized.substringBefore("/chat/completions")
            else -> "$normalized/v1"
        }
        return "$v1Base/images/understand"
    }
}

/**
 * 单次 LLM 请求体与格式标签（与 [ApiAdapter] 组包结果对应）。
 */
private data class LlmRequestPlan(
    val body: JSONObject,
    val multimodalFormat: String
)

/**
 * LLM 响应
 */
data class LLMResponse(
    val content: String?,
    val toolCalls: List<LLMToolCall>? = null,
    val thinkingContent: String? = null,
    val usage: LLMUsage? = null,
    val finishReason: String? = null
)

/**
 * LLM Tool Call
 */
data class LLMToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * LLM Token 使用统计
 */
data class LLMUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * LLM 异常
 */
