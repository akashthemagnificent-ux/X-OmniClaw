package com.shijing.xomniclaw.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.ProviderConfig
import com.shijing.xomniclaw.config.VisionConfig
import com.shijing.xomniclaw.core.MainEntryNew
import com.shijing.xomniclaw.agent.loop.LlmTokenUsage
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.providers.llm.Message
import com.shijing.xomniclaw.vision.CameraFramePusher
import com.shijing.xomniclaw.vision.VisionFrameBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 将历史 PC Hub（xomniclaw_hub_v2）的核心链路搬到手机：STT → 快捷指令 → 多模态 VLM → 解析 direct_action。
 * STT / VLM 统一通过 models.providers 配置，vision 仅保留帧率与 AEC 相关参数。
 */
class LocalVoiceVisionHub(
    context: Context,
    private val vision: VisionConfig
) {
    companion object {
        private const val TAG = "LocalVoiceVisionHub"
        private const val SYSTEM_PROMPT_ASSET = "bootstrap/VOICE_VISION_SYSTEM_PROMPT.md"
        private const val ORCHESTRATION_PROMPT_ASSET = "bootstrap/VOICE_VISION_ORCHESTRATION_PROMPT.md"

        // 兜底文案仅在 assets 文件丢失时生效，主配置应维护在 bootstrap markdown 中。
        private const val DEFAULT_SYSTEM_PROMPT_FALLBACK = "You are the X-OmniClaw Android assistant. Understand intent from voice and screenshots; for single-step tasks output a JSON command, for multi-step tasks prefer agent_task."
        private const val DEFAULT_ORCHESTRATION_PROMPT_FALLBACK = "This task is handed to the main AgentLoop. Output an execution hint for the Agent (task understanding / execution constraints / completion criteria) rather than the final answer."
    }

    /** 每个 Hub 实例维护独立短期会话，避免跨会话串话。 */
    private val chatHistoryBrain = ArrayDeque<Pair<String, String?>>(10)
    private val appContext = context.applicationContext
    /** 复用统一 Provider，让语音视觉链路与文本链路共享默认模型选择逻辑。 */
    private val llmProvider = UnifiedLLMProvider(appContext)
    /** provider 读取器：STT/VLM 均从 models.providers 中获取。 */
    private val configLoader = ConfigLoader(appContext)
    /** 应用名到包名映射，来自 assets/bootstrap/APP_CONFIG.json。 */
    private val voiceShortcuts: VoiceAppBootstrapConfig by lazy {
        VoiceAppBootstrapConfig.load(appContext)
    }

    /** 从 assets 读取主提示词，便于后续直接通过 markdown 热更新文案。 */
    private val systemPrompt: String by lazy {
        loadPromptFromAssets(SYSTEM_PROMPT_ASSET, DEFAULT_SYSTEM_PROMPT_FALLBACK)
    }
    /** 多步编排专用提示词，单独文件维护，避免 Kotlin 内嵌长字符串。 */
    private val orchestrationSystemPrompt: String by lazy {
        loadPromptFromAssets(ORCHESTRATION_PROMPT_ASSET, DEFAULT_ORCHESTRATION_PROMPT_FALLBACK)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 返回与历史 PC Hub sync_audio 相同形状的 JSON：text, reply, direct_action?, status, error?
     */
    suspend fun runSyncAudio(
        wavBytes: ByteArray,
        alignedFrameTsMs: Long? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val out = JSONObject()
        try {
            val sttProvider = loadProvider("stt")
                ?: run {
                    out.put("status", "error")
                    out.put("error", "Missing STT provider configuration (models.providers.stt)")
                    return@withContext out
                }
            val sttKey = sttProvider.apiKey.orEmpty()
            val sttUrl = sttProvider.baseUrl
            val sttModel = sttProvider.models.firstOrNull()?.id.orEmpty()
            if (sttKey.isBlank() || sttUrl.isBlank() || sttModel.isBlank()) {
                out.put("status", "error")
                out.put(
                    "error",
                    "Missing STT provider configuration (needs models.providers.stt.apiKey/baseUrl/models[0].id)"
                )
                return@withContext out
            }
            val sttText = transcribeAudio(wavBytes, sttProvider)
            return@withContext askFromTranscript(
                sttText = sttText,
                alignedFrameTsMs = alignedFrameTsMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "runSyncAudio failed", e)
            trace("error", "runSyncAudio failed: ${e.message}")
            out.put("status", "error")
            out.put("error", e.message ?: "unknown")
        }
        out
    }

    /**
     * 先单独执行 STT，供上层在等待大模型回复前就把识别文本打到屏幕上。
     */
    suspend fun transcribeAudio(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val sttProvider = loadProvider("stt")
            ?: throw IllegalStateException("STT provider not configured (models.providers.stt)")
        transcribeAudio(wavBytes, sttProvider)
    }

    /**
     * 使用指定 STT provider 执行语音识别，供 runSyncAudio 复用。
     */
    suspend fun transcribeAudio(wavBytes: ByteArray, sttProvider: ProviderConfig): String = withContext(Dispatchers.IO) {
        val sttText = transcribeWav(wavBytes, sttProvider)
        trace("stt", "text=${sttText.take(200)}")
        sttText
    }

    /**
     * 已拿到 STT 文本后继续走视觉理解与动作决策。
     * 返回结构与历史 sync_audio 保持一致，便于上层沿用原有解析逻辑。
     */
    suspend fun askFromTranscript(
        sttText: String,
        alignedFrameTsMs: Long? = null,
        forceAlignedFrameDisclosure: Boolean = false,
        voiceStartedWithVision: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        val out = JSONObject()
        out.put("text", sttText)
        if (sttText.isBlank()) {
            out.put("status", "ok")
            out.put("error", "No speech recognized")
            return@withContext out
        }

        val ask = internalAsk(
            userInput = sttText,
            requireVlm = true,
            alignedFrameTsMs = alignedFrameTsMs,
            voiceStartedWithVision = voiceStartedWithVision
        )
        trace(
            "decision",
            "reply=${ask.optString("reply", "").take(120)} action=${ask.optJSONObject("direct_action")?.toString()?.take(280) ?: "<none>"}"
        )
        out.put("reply", ask.optString("reply", ""))
        ask.optJSONObject("direct_action")?.let { out.put("direct_action", it) }
        // 主界面纯语音：仅话术涉及时回传；视觉叠加层（摄像头/屏幕推流）内按键说话则强制回传，避免被关键词过滤。
        val rawAligned = ask.optString("aligned_frame_path", "").trim()
        val disclose =
            forceAlignedFrameDisclosure ||
                VoiceIntentAtoms.impliesAlignedScreenshotDisclosureForChat(
                    sttText,
                    voiceShortcuts.appPackages.keys
                )
        if (rawAligned.isNotBlank() && disclose) {
            out.put("aligned_frame_path", rawAligned)
        }
        out.put("status", "ok")
        out
    }

    private fun transcribeWav(wavBytes: ByteArray, sttProvider: ProviderConfig): String {
        val sttModel = sttProvider.models.firstOrNull()?.id.orEmpty()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", sttModel)
            .addFormDataPart("language", "zh")
            .addFormDataPart(
                "file",
                "recording.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val req = Request.Builder()
            .url(sttProvider.baseUrl)
            .addHeader("Authorization", "Bearer ${sttProvider.apiKey.orEmpty()}")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("STT HTTP ${resp.code}: $raw")
            }
            val json = JSONObject(raw)
            return json.optString("text", "").trim()
        }
    }

    private suspend fun internalAsk(
        userInput: String,
        requireVlm: Boolean,
        alignedFrameTsMs: Long? = null,
        voiceStartedWithVision: Boolean = false
    ): JSONObject {
        val ts = System.currentTimeMillis()
        synchronized(chatHistoryBrain) {
            while (chatHistoryBrain.size >= 10) chatHistoryBrain.removeFirst()
            chatHistoryBrain.addLast(userInput to null)
        }

        val searchIntent = VoiceIntentAtoms.matchAny(userInput, VoiceIntentAtoms.SEARCH)
        val isMultiStep = inferMultiStep(userInput, searchIntent)
        val wantsOrchestration = isMultiStep ||
            VoiceIntentAtoms.matchAny(userInput, VoiceIntentAtoms.ORCHESTRATION_EXTRA)
        trace("intent", "multi=$isMultiStep orchestration=$wantsOrchestration search=$searchIntent input=${userInput.take(180)}")
        // 约束：仅纯文本答复可本地直返；凡涉及设备操作（哪怕一步）都交给 Agent。
        // 因此此处不再做“打开 App”直返 shortcut，统一继续后续流程。

        // 无联网气象 API 时 VLM 易空答；单轮天气问句直接交给主 Agent 在本机打开天气应用或浏览器。
        if (!isMultiStep && VoiceIntentAtoms.looksLikeWeatherQuery(userInput)) {
            trace("rule", "weather_query -> agent_task")
            return finalize(
                "I will have the main assistant check the live weather on this device.",
                buildWeatherAgentTask(userInput),
                ts,
                userInput
            )
        }

        if (!requireVlm) {
            return finalize("I did not understand that.", null, ts, userInput)
        }

        val cameraRunningNow = CameraFramePusher.isAnyCameraRunning()
        val shouldUseVisualRoute = voiceStartedWithVision || cameraRunningNow
        // 仅当“按下时不在视觉模式”且“当前也无相机采样”时，才走纯文本直通。
        // 这样可避免：按下说话时在视觉模式，中途关闭预览导致被错误切到纯文本链路。
        if (!shouldUseVisualRoute) {
            trace("route", "pure-voice detected -> bypass local VLM and forward STT text to AgentLoop")
            return finalize(
                aiReply = "",
                command = buildForwardTextAction(),
                ts = ts,
                userInput = userInput
            )
        }
        if (!cameraRunningNow && voiceStartedWithVision) {
            trace("route", "vision-latched by press-time snapshot -> keep VLM route after preview closed")
        }

        // 替身模式优先取“按下说话”时刻对齐的那一帧；取不到时再回退到原有最近帧策略。
        val frames = selectFramesForPrompt(
            userInput = userInput,
            alignedFrameTsMs = alignedFrameTsMs,
            allowBufferFramesWhenCameraStopped = voiceStartedWithVision
        )
        return coroutineScope {
            // 调试落盘与 VLM 请求并行，避免同步写盘占用调用大模型前的准备时间。
            val saveDeferred = async(Dispatchers.IO) {
                saveDebugFrames(
                    userInput = userInput,
                    alignedFrameTsMs = alignedFrameTsMs,
                    frames = frames
                )
            }

            val messages = mutableListOf<Message>()
            messages.add(Message(role = "system", content = systemPrompt))
            // 注入本机日期/星期，否则纯 VLM 易误判「无法获取实时日期」。
            messages.add(Message(role = "system", content = VoiceDeviceTimeContext.buildSystemLine()))
            if (wantsOrchestration) {
                messages.add(Message(role = "system", content = orchestrationSystemPrompt))
            }

            // 本轮已在 internalAsk 入口把 (userInput, null) 入队；若原样拼进 context，会与下方带截图的
            // user 条重复。仅展开「已写入 assistant 的前序轮」+ 当前多模态 user 一次。
            val historySnapshot = synchronized(chatHistoryBrain) {
                val list = chatHistoryBrain.toList()
                if (list.isNotEmpty() && list.last().first == userInput && list.last().second == null) {
                    list.dropLast(1)
                } else {
                    list
                }
            }
            for ((u, a) in historySnapshot) {
                messages.add(Message(role = "user", content = u))
                if (a != null) {
                    messages.add(Message(role = "assistant", content = a))
                }
            }

            val imageDataUrls = mutableListOf<String>()
            for (jpeg in frames) {
                val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                imageDataUrls.add(b64)
            }
            messages.add(
                Message(
                    role = "user",
                    content = userInput,
                    imageDataUrls = imageDataUrls.takeIf { it.isNotEmpty() }
                )
            )

            val rawAiReply = callVlm(messages)
            val alignedFramePath = saveDeferred.await()

            val sanitized = VoiceVlmReplyProcessor.sanitize(
                rawAiReply = rawAiReply,
                wantsOrchestration = wantsOrchestration,
                userInput = userInput,
                looksLikeQuiz = { t -> looksLikeQuizText(t) },
            )
            trace("vlm_raw", sanitized.plainNoJson.take(360))

            var command = sanitized.command
            var cleaned = sanitized.replySeed

            val resolved = resolveDirectActionAndReply(
                userInput = userInput,
                searchIntent = searchIntent,
                wantsOrchestration = wantsOrchestration,
                vlmTextNoJson = sanitized.plainNoJson,
                command = command,
                cleaned = cleaned,
            )
            command = resolved.first
            cleaned = resolved.second

            val (normalizedCommand, normalizedReply) = VoiceVlmReplyProcessor.applyQuizScopeDefault(
                userInput = userInput,
                command = command,
                currentReply = cleaned,
                looksLikeQuiz = { t -> looksLikeQuizText(t) },
                hasExplicitSingleQuestionScope = { u -> hasExplicitSingleQuestionScope(u) },
            )
            if (normalizedCommand != command || normalizedReply != cleaned) {
                trace("fallback", "quiz scope normalized -> full flow by default")
            }
            command = normalizedCommand
            cleaned = normalizedReply

            finalize(
                aiReply = cleaned,
                command = command,
                ts = ts,
                userInput = userInput,
                alignedFramePath = alignedFramePath
            )
        }
    }

    /**
     * 是否多步：原子词库命中 ∪（提到已配置 App 名且带搜索意图）。
     * [searchIntent] 由上层算好，避免对 SEARCH 原子重复扫描。
     */
    private fun inferMultiStep(userInput: String, searchIntent: Boolean): Boolean {
        if (VoiceIntentAtoms.matchAny(userInput, VoiceIntentAtoms.MULTI_STEP_ANY)) return true
        val appMentioned = voiceShortcuts.appPackages.keys.any { userInput.contains(it) }
        return appMentioned && searchIntent
    }

    private fun selectFramesForPrompt(
        userInput: String,
        alignedFrameTsMs: Long?,
        allowBufferFramesWhenCameraStopped: Boolean
    ): List<ByteArray> {
        val cameraRunning = CameraFramePusher.isAnyCameraRunning()
        if (!cameraRunning && !allowBufferFramesWhenCameraStopped) {
            trace("frame_policy", "camera not running -> skip attaching frame")
            return emptyList()
        }

        if (alignedFrameTsMs == null) {
            if (!cameraRunning) {
                trace("frame_align", "camera stopped but vision-latched, pressStartTs missing -> try buffered query frames")
            } else {
                trace("frame_align", "pressStartTs missing, fallback to query policy")
            }
            return VisionFrameBuffer.selectFramesForQuery(userInput)
        }
        val alignedFrame = VisionFrameBuffer.getLatestFrameAtOrBefore(alignedFrameTsMs)
            ?: VisionFrameBuffer.getFrameClosestTo(alignedFrameTsMs)
        if (alignedFrame != null) {
            trace("frame_align", "use aligned frame at pressStartTs=$alignedFrameTsMs")
            return listOf(alignedFrame)
        }
        trace("frame_align", "aligned frame missing, fallback to query policy (cameraRunning=$cameraRunning)")
        return VisionFrameBuffer.selectFramesForQuery(userInput)
    }

    /**
     * 将本次真正送给大模型的截图落盘，便于直接核对“看到的是哪一帧”。
     * 保存失败不影响语音主链路。
     */
    private fun saveDebugFrames(
        userInput: String,
        alignedFrameTsMs: Long?,
        frames: List<ByteArray>
    ): String? {
        try {
            val debugRoot = File("/sdcard/.xomniclaw/workspace/debug/voice-vision-frames")
            if (!debugRoot.exists()) debugRoot.mkdirs()

            val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val sessionDir = File(debugRoot, sessionId)
            if (!sessionDir.exists()) sessionDir.mkdirs()

            frames.forEachIndexed { index, jpeg ->
                File(sessionDir, "frame_${index + 1}.jpg").writeBytes(jpeg)
            }

            val meta = JSONObject().apply {
                put("savedAt", timestamp())
                put("alignedFrameTsMs", alignedFrameTsMs ?: JSONObject.NULL)
                put("frameCount", frames.size)
                put("userInput", userInput)
                put(
                    "files",
                    org.json.JSONArray().apply {
                        frames.indices.forEach { index ->
                            put("frame_${index + 1}.jpg")
                        }
                    }
                )
            }
            File(sessionDir, "meta.json").writeText(meta.toString(2))
            trace(
                "frame_debug",
                "saved ${frames.size} frame(s) for prompt under ${sessionDir.absolutePath}"
            )
            return if (frames.isNotEmpty()) {
                File(sessionDir, "frame_1.jpg").absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            trace("frame_debug_error", e.message ?: "save debug frames failed")
            return null
        }
    }

    /**
     * 搜索兜底与替身编排兜底按固定优先级一次完成，避免多次覆盖 [command] / cleaned。
     * 顺序：先处理搜索意图（open / 无 JSON），再处理需要主 Agent 编排的场景。
     * 多步/编排时：VLM 若输出 open/act，与 agent_task 一样交主 Agent（`buildCompanionAgentTask` + 短 Ack），
     * 避免在本机只执行半屏「打开」而中断需要 device 的连贯任务。
     */
    private fun resolveDirectActionAndReply(
        userInput: String,
        searchIntent: Boolean,
        wantsOrchestration: Boolean,
        vlmTextNoJson: String,
        command: JSONObject?,
        cleaned: String,
    ): Pair<JSONObject?, String> {
        var cmd = command
        var reply = cleaned

        if (searchIntent) {
            when {
                cmd?.optString("action", "") == "open" -> {
                    cmd = buildAgentTaskCommand(userInput)
                    reply = "Okay, I will continue the search and report the results to you"
                    trace("fallback", "converted open -> agent_task for search intent")
                }
                cmd == null -> {
                    cmd = buildAgentTaskCommand(userInput)
                    reply = "Okay, I will continue the search as you asked and report back"
                    trace("fallback", "no command -> agent_task for search intent")
                }
            }
        }

        if (!wantsOrchestration) {
            return cmd to reply
        }

        val hintBase = reply.ifBlank { vlmTextNoJson }
        when {
            cmd == null -> {
                cmd = buildCompanionAgentTask(userInput, hintBase, embeddedCommand = null)
                reply = generateOrchestrationUserAck()
                trace("fallback", "orchestration: null command -> companion agent_task")
            }
            cmd.optString("action", "") == "act" -> {
                val hint = reply.ifBlank { vlmTextNoJson }
                cmd = buildCompanionAgentTask(userInput, hint, embeddedCommand = cmd)
                reply = generateOrchestrationUserAck()
                trace("fallback", "orchestration: act -> companion agent_task")
            }
            cmd.optString("action", "") == "open" -> {
                // 与 act 相同：编排骨架下把首轮「打开包/URI」只作提示嵌入 task，由主 Agent 在 snapshot 后执行。
                val hint = reply.ifBlank { vlmTextNoJson }
                cmd = buildCompanionAgentTask(userInput, hint, embeddedCommand = cmd)
                reply = generateOrchestrationUserAck()
                trace("fallback", "orchestration: open -> companion agent_task")
            }
            cmd.optString("action", "") == "agent_task" -> {
                val snapshot = cmd
                val hint = vlmTextNoJson.ifBlank { snapshot.optString("task", "") }
                cmd = buildCompanionAgentTask(userInput, hint, embeddedCommand = null)
                reply = generateOrchestrationUserAck()
                trace("fallback", "orchestration: agent_task normalized")
            }
        }
        return cmd to reply
    }

    /**
     * 编排转主 Agent 时用户侧 Ack：仅简短可播报句。
     * 「默认全题 / 仅做单题」等策略只写在 agent_task 与提示词中供模型与 Agent 使用，不向用户念条件从句。
     */
    private fun generateOrchestrationUserAck(): String {
        return "I have the execution plan ready; the main assistant will now carry out the steps."
    }

    private fun buildAgentTaskCommand(userInput: String): JSONObject {
        val appName = voiceShortcuts.appPackages.keys.firstOrNull { userInput.contains(it) }
        val query = extractSearchQuery(userInput).ifBlank { userInput.trim() }
        val task = if (!appName.isNullOrBlank()) {
            "Open $appName, search for $query, and reply to me with the key information."
        } else {
            "Complete the following multi-step task and report the result: $userInput"
        }
        return JSONObject().put("action", "agent_task").put("task", task)
    }

    /**
     * 纯语音直通文本主链：
     * 上层看到非 agent_task 的 action 时，会用 STT 原文调用 sendMessage，
     * 从而与手动文本输入完全一致。
     */
    private fun buildForwardTextAction(): JSONObject {
        return JSONObject().put("action", "forward_text")
    }

    /**
     * 天气类问句：由主 Agent 在本机打开系统天气或浏览器，避免 VLM 无 API 时仅空答。
     */
    private fun buildWeatherAgentTask(userInput: String): JSONObject {
        val body = buildString {
            append("[For the main AgentLoop] The user wants the weather. Original words: ").append(userInput.trim()).append("\n\n")
            append("The voice pipeline has no weather service; complete this with the device tool:\n")
            append("1) Prefer opening the system Weather app, or the home screen / left-most screen weather card; try common package names (pick by brand, and search the app drawer for \"weather\" if that fails):")
            append("com.huawei.android.totemweather、com.miui.weather2、com.coloros.weather2、")
            append("com.vivo.weather、com.oneplus.weather、com.samsung.android.weather 等。\n")
            append("2) If the weather app cannot be opened, search \"today weather <current city>\" in a browser or the system search box.\n")
            append("3) Snapshot before acting each round; never tap blind.\n")
            append("Completion criteria: today's weather summary is visible on screen (conditions, temperature range, etc.), or the reason it cannot be opened is clearly reported.")
        }
        return JSONObject().put("action", "agent_task").put("task", body)
    }

    /**
     * 组装替身模式交给 [ScreenCompanionController.launchAgentTask] 的 task 正文。
     */
    private fun buildCompanionAgentTask(
        userInput: String,
        vlmSummary: String,
        embeddedCommand: JSONObject?
    ): JSONObject {
        val body = StringBuilder()
        body.append("[Execution hint for the main AgentLoop]\n")
        body.append("User goal: ").append(userInput.trim()).append("\n\n")
        body.append("Task understanding:\n")
        body.append("- This request comes from the on-screen proxy voice entry; the main Agent must complete it step by step with the device tool.\n")
        body.append("- Supplementary VLM observation (reference only; execute against a live snapshot):\n")
        val summary = vlmSummary.trim().ifBlank { "(the model gave no extra text)" }
        body.append(summary).append("\n\n")
        body.append("Execution constraints:\n")
        body.append("1. Snapshot first each round, then decide; never tap blind.\n")
        body.append("2. On failure or an abnormal page, do not stop; recover and keep advancing the goal.\n")
        body.append("3. Prefer accessibility refs; use coordinates only as a fallback.\n")
        if (looksLikeQuizText(userInput) || looksLikeQuizText(vlmSummary)) {
            body.append("4. Questions may be single-choice, multiple-choice, or fill-in; read each one before answering.\n")
            body.append("5. If one question fails, do not stop; continue to the next until the whole set is done.\n")
            body.append("6. Unless the user explicitly says \"only one question\" or \"only question N\", complete every question.\n")
            body.append("7. After finishing, if another set is available, ask the user whether to continue.\n")
        }
        if (embeddedCommand != null) {
            body.append("\nSuggested first-round action (reference only; never skip the snapshot check):\n")
            body.append(embeddedCommand.toString())
        }
        body.append("\n\nCompletion criteria: report to the user only after the target state is reached.")
        return JSONObject().put("action", "agent_task").put("task", body.toString())
    }

    private fun looksLikeQuizText(text: String): Boolean {
        if (text.isBlank()) return false
        return VoiceIntentAtoms.matchAny(text, VoiceIntentAtoms.QUIZ_SURFACE)
    }

    private fun hasExplicitSingleQuestionScope(userInput: String): Boolean {
        val normalized = userInput.replace(" ", "")
        return Regex("""只做(一道|这道|本题|第\d+题)""").containsMatchIn(normalized) ||
            Regex("""只答(一道|这道|本题|第\d+题)""").containsMatchIn(normalized) ||
            Regex("""仅做(一道|这道|本题|第\d+题)""").containsMatchIn(normalized) ||
            Regex("""只需要做(一道|这道|本题|第\d+题)""").containsMatchIn(normalized) ||
            Regex("""只做一题""").containsMatchIn(normalized) ||
            Regex("""(?i)(only|just)\s+(do\s+)?(one|a single|this)\s+(question|quiz)""").containsMatchIn(userInput) ||
            Regex("""(?i)only\s+question\s+\d+""").containsMatchIn(userInput)
    }

    private fun extractSearchQuery(userInput: String): String {
        val patterns = listOf(
            Regex("""(?:搜(?:索)?(?:一下|下)?|查(?:找|询)?(?:一下|下)?|找(?:一下|下)?|看看|检索)\s*(.+)$"""),
            Regex("""(?:关于)\s*(.+)$"""),
            Regex("""(?i)(?:search(?:\s+for)?|look\s+up|find|about)\s+(.+)$""")
        )
        for (re in patterns) {
            val m = re.find(userInput) ?: continue
            val query = m.groupValues.getOrNull(1)?.trim().orEmpty()
                .trim('。', '！', '!', '？', '?', '，', ',', '；', ';', '：', ':')
            if (query.isNotBlank()) return query
        }
        return ""
    }

    private fun finalize(
        aiReply: String,
        command: JSONObject?,
        ts: Long,
        userInput: String,
        alignedFramePath: String? = null
    ): JSONObject {
        synchronized(chatHistoryBrain) {
            if (chatHistoryBrain.isNotEmpty() && chatHistoryBrain.last().first == userInput && chatHistoryBrain.last().second == null) {
                chatHistoryBrain.removeLast()
                chatHistoryBrain.addLast(userInput to aiReply)
            }
        }
        val o = JSONObject()
        o.put("reply", aiReply)
        if (command != null) o.put("direct_action", command)
        if (!alignedFramePath.isNullOrBlank()) {
            o.put("aligned_frame_path", alignedFramePath)
        }
        o.put("_ts", ts)
        trace("finalize", "reply=${aiReply.take(120)} action=${command?.toString()?.take(280) ?: "<none>"}")
        return o
    }

    private fun trace(stage: String, message: String) {
        val safe = message.replace('\n', ' ').replace('\r', ' ').take(2000)
        val line = "[${timestamp()}][$stage] $safe"
        Log.i(TAG, line)
        appendTraceFile(line)
    }

    /**
     * 从 assets/bootstrap 读取提示词，缺失时回退到代码兜底文案，保证线上链路可用。
     */
    private fun loadPromptFromAssets(assetPath: String, fallback: String): String {
        return try {
            appContext.assets.open(assetPath).bufferedReader().use { it.readText() }.trim()
                .ifBlank { fallback }
        } catch (e: Exception) {
            trace("prompt_load_fallback", "asset=$assetPath reason=${e.message ?: "unknown"}")
            fallback
        }
    }

    @Synchronized
    private fun appendTraceFile(line: String) {
        try {
            val dir = File("/sdcard/.xomniclaw/workspace/logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "voice-vision-local.log")
            file.appendText(line + "\n")
        } catch (_: Exception) {
            // Keep runtime resilient: logging failure must not break voice pipeline.
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    /**
     * 本地语音视觉链路改为复用统一 Provider。
     *
     * 这样 provider/model 的选择与文本链路保持一致，默认跟随
     * `agents.defaults.model.primary`。若配置了 `models.providers.vlm`，
     * 则优先使用 `vlm/<models[0].id>` 作为独立模型。
     */
    private suspend fun callVlm(messages: List<Message>): String {
        return try {
            val config = configLoader.loadOmniClawConfig()
            val followAgent = config.vision?.vlmUseAgentModel ?: true
            val vlmProvider = config.resolveProviders()["vlm"]
            val resolvedModelRef = if (followAgent) {
                null
            } else {
                vlmProvider
                    ?.models
                    ?.firstOrNull()
                    ?.id
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "vlm/$it" }
            }
            if (!followAgent && vlmProvider != null && resolvedModelRef == null) {
                trace("vlm_config_warn", "Separate VLM provider detected but no model configured; falling back to the Agent default model")
            }
            val response = llmProvider.chatWithTools(
                messages = messages,
                tools = null,
                modelRef = resolvedModelRef,
                temperature = 0.2,
                maxTokens = 8192,
                reasoningEnabled = false
            )
            // 语音视觉链路同样纳入状态页 token 实时累计（不再只覆盖文本 AgentLoop）。
            response.usage?.let { usage ->
                MainEntryNew.recordExternalTokenUsage(
                    usage = LlmTokenUsage(
                        promptTokens = usage.promptTokens,
                        completionTokens = usage.completionTokens,
                        totalTokens = usage.totalTokens
                    )
                )
            }
            // 部分网关/模型在 content 为空时把可播报正文放在 thinkingContent，需回退，否则 vlm_raw 会空。
            // 主界面与 TTS 只应展示/朗读「对用户的答案」：主文本非空时绝不去拼接思考过程。
            val main = response.content?.trim().orEmpty()
            val think = response.thinkingContent?.trim().orEmpty()
            if (main.isNotBlank() && think.isNotBlank()) {
                trace("vlm_thinking_omitted", "omitted_len=${think.length} (UI and speech use content only)")
            }
            var merged = when {
                main.isNotBlank() -> main
                think.isNotBlank() -> think
                else -> ""
            }
            // 少数模型在纯补全场景下用 tool_calls 代替 message.content（即使本次请求 tools=null）。
            // 若不拼接为文本，sanitize 会看到「模型完全无输出」，vlm_raw 为空。
            val tc = response.toolCalls.orEmpty()
            if (merged.isBlank() && tc.isNotEmpty()) {
                val names = tc.joinToString(",") { it.name }
                trace("vlm_tool_calls_only", "count=${tc.size} tools=[$names]")
                merged = tc.joinToString("\n") { call ->
                    "[tool_calls_only] ${call.name} args=${call.arguments.take(800)}${if (call.arguments.length > 800) "…" else ""}"
                }
            }
            if (merged.isBlank()) {
                trace("vlm_empty", "unified provider returned empty content and empty thinkingContent")
                "Error: VLM returned empty content"
            } else {
                merged
            }
        } catch (e: Exception) {
            val message = e.message ?: "VLM request failed"
            trace("vlm_provider_error", message.take(300))
            "Error: $message"
        }
    }

    /**
     * 从最新配置中读取指定 provider，避免使用过时缓存。
     */
    private fun loadProvider(providerId: String): ProviderConfig? {
        return runCatching {
            configLoader.loadOmniClawConfig().resolveProviders()[providerId]
        }.getOrNull()
    }

}
