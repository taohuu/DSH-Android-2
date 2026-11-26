package io.github.jackwener.dsh.feature.voice

/**
 * 语音唤醒: what counts as the wake word in a transcript.
 *
 * The recognizer returns text with spaces and punctuation ("你好 DSH。"), the setting holds plain
 * words ("你好DSH"); both sides are normalized before the match. Several words may be configured,
 * separated by commas or newlines.
 */
object WakeWord {
    const val DEFAULT = "大肥鱼,你好DSH,你好 dsh"

    /** Lowercase, no whitespace and no punctuation: comparison is on the words only. */
    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》…—·]+"), "")

    fun words(configured: String): List<String> =
        configured.split('\n', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    /** Whether [heard] (a partial or final transcript) contains the wake word [word]. */
    fun matches(heard: String, word: String): Boolean {
        val w = normalize(word)
        return w.isNotEmpty() && normalize(heard).contains(w)
    }

    /** The first configured word [heard] contains, or null. */
    fun firstMatch(heard: String, configured: String): String? =
        words(configured).firstOrNull { matches(heard, it) }
}
