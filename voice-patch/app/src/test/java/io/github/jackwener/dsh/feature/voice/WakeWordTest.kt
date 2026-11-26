package io.github.jackwener.dsh.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun `spaces and punctuation do not matter`() {
        assertTrue(WakeWord.matches("你好 DSH。", "你好DSH"))
        assertTrue(WakeWord.matches("嗯，大肥鱼，帮我看", "大肥鱼"))
    }

    @Test fun `another phrase does not wake it`() {
        assertFalse(WakeWord.matches("你好，帮我看一下这个", "大肥鱼"))
        assertFalse(WakeWord.matches("", "大肥鱼"))
    }

    @Test fun `the configured word wins`() {
        assertEquals("大肥鱼", WakeWord.firstMatch("大肥鱼，帮我看", WakeWord.DEFAULT))
        assertEquals("你好DSH", WakeWord.firstMatch("你好DSH，帮我看", WakeWord.DEFAULT))
        assertNull(WakeWord.firstMatch("今天天气不错", WakeWord.DEFAULT))
    }

    @Test fun `a custom list is honoured`() {
        assertEquals("大肥鱼", WakeWord.firstMatch("大肥鱼你好", "大肥鱼,你好DSH"))
        assertNull(WakeWord.firstMatch("你好DSH", "大肥鱼"))
    }
}
