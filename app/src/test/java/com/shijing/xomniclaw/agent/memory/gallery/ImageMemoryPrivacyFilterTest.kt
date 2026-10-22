package com.shijing.xomniclaw.agent.memory.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Summaries are written in the UI language now, so the classifiers must key off
 * both languages — the Chinese-only lists silently stopped categorising.
 */
class ImageMemoryPrivacyFilterTest {

    @Test
    fun englishPaymentSummaryIsHighRisk() {
        assertEquals("high", ImageMemoryPrivacyFilter.sanitize("bank card payment receipt").sensitivity)
    }

    @Test
    fun chinesePaymentSummaryIsHighRisk() {
        assertEquals("high", ImageMemoryPrivacyFilter.sanitize("银行卡支付凭证").sensitivity)
    }

    @Test
    fun phoneNumberIsRedacted() {
        val result = ImageMemoryPrivacyFilter.sanitize("call 13800138000")
        assertTrue(result.redacted)
        assertTrue(result.sanitizedText.contains("[REDACTED]"))
    }

    @Test
    fun ordinarySummaryIsLowRisk() {
        val result = ImageMemoryPrivacyFilter.sanitize("a whiteboard with the sprint plan")
        assertEquals("low", result.sensitivity)
        assertTrue(!result.redacted)
    }
}
