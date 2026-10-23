package com.shijing.xomniclaw.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmOnDemandToolInclusionTest {

    @Test
    fun greetingsAreDetected() {
        listOf("hi", "hello", "hey", "Hello!", "  hey  ", "你好", "hello啊", "[xxx] hello").forEach {
            assertTrue("expected greeting: $it", LlmOnDemandToolInclusion.isGreetingOnlyMessage(it))
        }
    }

    // Substring regression: "they're" contains "hey" and "this" contains "hi", so a contains()
    // check classified ordinary replies as greetings and sent tools=[], disabling the agent.
    @Test
    fun wordsContainingGreetingSubstringsAreNotGreetings() {
        listOf(
            "They're granted",
            "I said they're granted",
            "this is broken",
            "Which app is this",
            "his car"
        ).forEach {
            assertFalse("must not be a greeting: $it", LlmOnDemandToolInclusion.isGreetingOnlyMessage(it))
        }
    }

    @Test
    fun requestsAreNotGreetings() {
        listOf("open settings", "take a screenshot", "show me my photos", "what's the weather").forEach {
            assertFalse("must not be a greeting: $it", LlmOnDemandToolInclusion.isGreetingOnlyMessage(it))
        }
    }
}
