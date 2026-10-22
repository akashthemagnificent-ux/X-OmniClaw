package com.shijing.xomniclaw.agent.memory.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.providers.llm.Message
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * 图片记忆提炼器。
 *
 * 实现策略：
 * 1. 优先尝试云端视觉摘要；
 * 2. 若未配置或调用失败，则回退为元信息摘要；
 * 3. 最后统一经过隐私过滤器，避免敏感内容直接进入长期记忆。
 */
class ImageMemorySummarizer(private val context: Context) {
    companion object {
        private const val TAG = "ImageMemorySummarizer"
        private const val MAX_IMAGE_EDGE = 1280
        private const val JPEG_QUALITY = 80
        private const val MAX_SUMMARY_CHARS = 100
        private const val MAX_EXTRACTED_TEXT_CHARS = 160
        private val JSON_BLOCK_RE = Regex("\\{[\\s\\S]*?\\}")
    }

    /**
     * 复用统一 provider，让图片摘要和语音视觉链路共用同一套模型/鉴权配置。
     */
    private val llmProvider = UnifiedLLMProvider(context.applicationContext)
    private val configLoader = ConfigLoader(context.applicationContext)

    suspend fun summarize(record: AlbumImageRecord): ImageMemoryEntry {
        val rawSummary = runCatching {
            buildVisionSummary(record)
        }.getOrElse { error ->
            Log.w(TAG, "Vision summary failed for ${record.stableKey}: ${error.message}")
            buildFallbackSummary(record)
        }

        val filterResult = ImageMemoryPrivacyFilter.sanitize(rawSummary.summary)
        val sanitizedExtractedText = rawSummary.originalText.takeIf { it.isNotBlank() }?.let {
            ImageMemoryPrivacyFilter.sanitize(it).sanitizedText.take(MAX_EXTRACTED_TEXT_CHARS)
        }

        val finalSummary = if (filterResult.sensitivity == "high") {
            "This image may contain sensitive information; detailed extraction was skipped and only a safe summary is kept: ${buildFallbackSummary(record).summary}"
        } else {
            filterResult.sanitizedText
        }.take(MAX_SUMMARY_CHARS)

        return ImageMemoryEntry(
            stableKey = record.stableKey,
            displayName = record.displayName,
            bucketName = record.bucketName,
            timestampText = record.formattedTimestamp(),
            summary = finalSummary,
            tags = ImageMemoryPrivacyFilter.deriveTags(record, finalSummary),
            sensitivity = filterResult.sensitivity,
            summarySource = rawSummary.source,
            originalTextExcerpt = sanitizedExtractedText
        )
    }

    private suspend fun buildVisionSummary(record: AlbumImageRecord): RawSummary {
        val imageBytes = loadCompressedImageBytes(record) ?: return buildFallbackSummary(record)
        val imageB64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val systemPrompt = """
            You are a "long-term memory extractor for user images".
            Task: from the image content, extract a safe summary suitable for long-term memory.
            You must output JSON with this structure:
            {"summary":"...","extracted_text":"...","reason":"..."}
            Rules:
            - summary is 40-100 words, in English
            - keep only information useful for understanding the user long term
            - summary must be concise; do not list every minor detail
            - never output full ID numbers, phone numbers, bank card numbers, verification codes or order numbers
            - if the image is mainly private or payment-related, summary is only "Sensitive content, details omitted"
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("Summarise this image as a safe long-term memory summary.")
            appendLine("Image name: ${record.displayName}")
            appendLine("Gallery bucket: ${record.bucketName ?: "unknown"}")
            appendLine("Time: ${record.formattedTimestamp()}")
        }

        val response = llmProvider.chatWithTools(
            messages = listOf(
                Message(role = "system", content = systemPrompt),
                Message(
                    role = "user",
                    content = userPrompt,
                    imageDataUrls = listOf(imageB64)
                )
            ),
            tools = null,
            modelRef = resolveVlmModelRef(),
            temperature = 0.2,
            maxTokens = 512,
            reasoningEnabled = false
        )
        val content = response.content?.trim().orEmpty()
        if (content.isBlank() || content.startsWith("Error:", ignoreCase = true)) {
            throw IllegalStateException("UnifiedLLMProvider returned invalid content: ${content.take(120)}")
        }
        val parsed = parseSummaryJson(content)
        val summary = parsed?.optString("summary")
            ?.takeIf { it.isNotBlank() }
            ?: normalizePlainVisionSummary(content)
        return RawSummary(
            summary = summary,
            originalText = parsed?.optString("extracted_text").orEmpty(),
            source = "vision"
        )
    }

    private fun resolveVlmModelRef(): String? {
        return runCatching {
            val config = configLoader.loadOmniClawConfig()
            val followAgent = config.vision?.vlmUseAgentModel ?: true
            if (followAgent) {
                return@runCatching null
            }

            // 与主语音视觉链路保持一致：只有关闭“跟随 Agent”时，才走独立 VLM provider。
            // 用户在 VLM 配置页切换模型/接口后，这里自动读取 models.providers.vlm。
            val modelId = config.resolveProviders()["vlm"]
                ?.models
                ?.firstOrNull()
                ?.id
                ?.takeIf { it.isNotBlank() }
            modelId?.let { "vlm/$it" }
        }.getOrNull()
    }

    private fun buildFallbackSummary(record: AlbumImageRecord): RawSummary {
        val resolution = listOfNotNull(record.width, record.height)
            .takeIf { it.size == 2 }
            ?.joinToString("x")
            ?: "unknown"
        val bucket = record.bucketName ?: "unknown"
        val mime = record.mimeType ?: "unknown"
        return RawSummary(
            summary = "Image from gallery bucket $bucket, filename ${record.displayName}, time ${record.formattedTimestamp()}, format $mime, resolution $resolution.",
            originalText = "",
            source = "fallback"
        )
    }

    private fun loadCompressedImageBytes(record: AlbumImageRecord): ByteArray? {
        val rawBytes = context.contentResolver.openInputStream(record.contentUri)?.use { it.readBytes() } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val scaledBitmap = scaleBitmap(bitmap)
        return ByteArrayOutputStream().use { output ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        if (maxEdge <= MAX_IMAGE_EDGE) {
            return bitmap
        }

        val scale = MAX_IMAGE_EDGE.toFloat() / maxEdge.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun parseSummaryJson(content: String): JSONObject? {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        val jsonText = JSON_BLOCK_RE.find(trimmed)?.value ?: return null
        return runCatching { JSONObject(jsonText) }.getOrNull()
    }

    private fun normalizePlainVisionSummary(content: String): String {
        return content
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "图片已完成视觉识别，但模型未返回可用摘要。"
    }

    private data class RawSummary(
        val summary: String,
        val originalText: String,
        val source: String
    )
}
