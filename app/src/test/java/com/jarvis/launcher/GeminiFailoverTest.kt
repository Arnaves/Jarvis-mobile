package com.jarvis.launcher

import com.jarvis.launcher.ai.GeminiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiFailoverTest {
    @Test fun ranksFreeTierFriendlyFirst() {
        val ranked = GeminiClient.rank(
            listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite", "gemini-2.5-flash-preview-09-2025", "gemma-3-27b-it")
        )
        assertEquals(
            listOf("gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-preview-09-2025", "gemma-3-27b-it", "gemini-2.5-pro"),
            ranked,
        )
    }

    @Test fun parsesRetryDelay() {
        val body = """{"error":{"code":429,"message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""
        assertEquals(38_000L, GeminiClient.retryDelayMs(body))
    }

    @Test fun parsesFractionalRetryDelay() = assertEquals(13_500L, GeminiClient.retryDelayMs("""{"retryDelay": "12.5s"}"""))
    @Test fun noRetryDelay() = assertNull(GeminiClient.retryDelayMs("""{"error":{"code":429}}"""))
}
