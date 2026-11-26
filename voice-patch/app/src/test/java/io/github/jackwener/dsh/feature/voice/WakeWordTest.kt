package io.github.jackwener.dsh.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun `spaces and punctuation do not matter`() {
        assertTrue(WakeWord.matches("你好 DSH。", "你好DSH"))
        assertTrue(WakeWord.matches("嗯，你好 dsh，帮我看", "你好 dsh"))
    }

    @Test fun `another phrase does not wake it`() {
        assertFalse(WakeWord.matches("你好，帮我看一下这个", "你好DSH"))
        assertFalse(WakeWord.matches("", "你好DSH"))
    }

    @Test fun `the first configured word wins`() {
        assertEquals("你好DSH", WakeWord.firstMatch("你好DSH，帮我看", WakeWord.DEFAULT))
        assertEquals("打开DSH语音", WakeWord.firstMatch("打开 dsh 语音", WakeWord.DEFAULT))
        assertNull(WakeWord.firstMatch("今天天气不错", WakeWord.DEFAULT))
    }
}
