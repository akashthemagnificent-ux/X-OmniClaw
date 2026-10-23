package com.shijing.xomniclaw.providers

/**
 * Ollama's native chat endpoint is `/api/chat` on the bare host; its OpenAI-compatible surface
 * lives under `/v1`. So a baseUrl ending in `/v1` is always the OpenAI surface — a local router
 * (bansos, LM Studio, llama.cpp) configured as provider `ollama` + `/v1` must not be sent to
 * `/api/chat`, which 404s.
 */
internal fun ollamaChatEndpoint(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    val lower = base.lowercase()
    return when {
        lower.endsWith("/api/chat") || lower.endsWith("/chat/completions") -> base
        lower.endsWith("/v1") -> "$base/chat/completions"
        else -> "$base/api/chat"
    }
}
