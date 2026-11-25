package io.github.jackwener.dsh.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {
    @Test fun `code fences and inline code do not reach the speaker`() {
        val md = "先看这段：\n```kotlin\nfun main() = println(1)\n```\n然后 `grep` 一下。"
        val spoken = SpokenText.clean(md)
        assertTrue(spoken.contains("先看这段"))
        assertTrue(spoken.contains("然后"))
        assertTrue(!spoken.contains("println"))
        assertTrue(!spoken.contains("`"))
    }

    @Test fun `links keep their text, images and markup go`() {
        val spoken = SpokenText.clean("## 结论\n- 见 [文档](https://example.com/x) ![图](a.png)\n- **重点**在这里")
        assertTrue(spoken.contains("文档"))
        assertTrue(!spoken.contains("https://"))
        assertTrue(!spoken.contains("a.png"))
        assertTrue(!spoken.contains("#"))
        assertTrue(!spoken.contains("*"))
    }

    @Test fun `long answers are cut at a sentence boundary`() {
        val long = "第一句话。".repeat(200)
        val spoken = SpokenText.clean(long)
        assertTrue(spoken.length <= SpokenText.MAX_CHARS + 1)
        assertTrue(spoken.endsWith("。"))
    }

    @Test fun `sentences split after punctuation and keep it`() {
        val s = SpokenText.sentences("打开微信。然后发消息！还有吗？")
        assertEquals(listOf("打开微信。", "然后发消息！", "还有吗？"), s)
    }

    @Test fun `blank answer speaks nothing`() {
        assertEquals(emptyList<String>(), SpokenText.sentences(SpokenText.clean("   ")))
    }
}
