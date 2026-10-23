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

    // The device tool is the only way to touch the screen, so it must never be gated behind
    // the on-demand router: on 2026-10-23 the router left it out and the model answered
    // "there's no device tool actually listed in my available functions" for "open youtube".
    @Test
    fun deviceToolIsAlwaysExposed() {
        assertFalse(LlmOnDemandToolInclusion.isOnDemandTool("device"))
    }

    @Test
    fun onDemandToolNamesStillCoverEverythingElse() {
        listOf("read_file", "exec", "start_activity", "memory_search", "install_app").forEach {
            assertTrue("expected on-demand: $it", LlmOnDemandToolInclusion.isOnDemandTool(it))
        }
    }

    @Test
    fun requestsAreNotGreetings() {
        listOf("open settings", "take a screenshot", "show me my photos", "what's the weather").forEach {
            assertFalse("must not be a greeting: $it", LlmOnDemandToolInclusion.isGreetingOnlyMessage(it))
        }
    }
}
