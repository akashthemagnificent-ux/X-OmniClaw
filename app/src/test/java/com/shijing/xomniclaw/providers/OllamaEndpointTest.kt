package com.shijing.xomniclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class OllamaEndpointTest {

    // Regression: provider "ollama" pointed at an OpenAI-compatible router (/v1) was sent to
    // /v1/api/chat, which bansos answers with 404 not found — every local request failed.
    @Test
    fun v1BaseUrlUsesOpenAiChatCompletions() {
        assertEquals(
            "http://127.0.0.1:17070/v1/chat/completions",
            ollamaChatEndpoint("http://127.0.0.1:17070/v1")
        )
        assertEquals(
            "http://127.0.0.1:17070/v1/chat/completions",
            ollamaChatEndpoint("http://127.0.0.1:17070/v1/")
        )
    }

    @Test
    fun bareHostUsesNativeOllamaEndpoint() {
        assertEquals(
            "http://127.0.0.1:11434/api/chat",
            ollamaChatEndpoint("http://127.0.0.1:11434")
        )
        assertEquals(
            "http://192.168.1.5:11434/api/chat",
            ollamaChatEndpoint("http://192.168.1.5:11434/")
        )
    }

    @Test
    fun alreadyCompletePathIsNotDoubled() {
        assertEquals(
            "http://127.0.0.1:17070/v1/chat/completions",
            ollamaChatEndpoint("http://127.0.0.1:17070/v1/chat/completions/")
        )
        assertEquals(
            "http://127.0.0.1:11434/api/chat",
            ollamaChatEndpoint("http://127.0.0.1:11434/api/chat/")
        )
    }
}
