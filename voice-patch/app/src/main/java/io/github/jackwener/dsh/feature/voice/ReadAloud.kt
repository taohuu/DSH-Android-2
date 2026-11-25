package io.github.jackwener.dsh.feature.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 朗读回答: the assistant's answer as text worth hearing.
 *
 * dsh answers in Markdown; reading it literally would speak code fences, link targets and bullets.
 * [SpokenText.clean] keeps the words and drops the markup, [SpokenText.sentences] cuts what is left
 * into utterances (so a long answer can be stopped between sentences).
 */
object SpokenText {
    /** Longer answers are cut at a sentence boundary; reading all of it would take minutes. */
    const val MAX_CHARS = 600

    fun clean(markdown: String): String {
        var s = markdown
        s = s.replace(Regex("```[\\s\\S]*?```"), "。")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        s = s.replace(Regex("(?m)^\\s*([-*+]|\\d+[.)])\\s+"), "")
        s = s.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")
        s = s.replace('|', '，')
        s = s.replace(Regex("[*_~]{1,3}"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace(Regex("[ \\t]{2,}"), " ")
        s = s.replace(Regex("\\n{2,}"), "\n")
        s = s.trim()
        if (s.length <= MAX_CHARS) return s
        val cut = s.lastIndexOfAny(charArrayOf('。', '！', '？', '!', '?', '\n'), startIndex = MAX_CHARS - 1)
        return (if (cut > MAX_CHARS / 3) s.substring(0, cut + 1) else s.substring(0, MAX_CHARS)).trim()
    }

    /** Utterances for [text]: split after sentence punctuation and newlines, blanks dropped. */
    fun sentences(text: String): List<String> = text
        .split(Regex("(?<=[。！？!?；;\\n])"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * Speaks answers through the phone's TTS engine (on this phone: com.xiaomi.mibrain.speech), one
 * utterance per sentence. [speak] may be called from any thread: a request that arrives before the
 * engine finished starting is spoken once it is ready. [onDone] fires after the last sentence of a
 * request, which is when a continuous conversation listens again.
 */
@Singleton
class ReadAloud @Inject constructor(@ApplicationContext private val context: Context) {
    private var engine: TextToSpeech? = null
    private var creating = false
    @Volatile private var usable = false
    private var pending: String? = null
    private val counter = AtomicInteger(0)
    private val lock = Any()

    @Volatile var onDone: (() -> Unit)? = null

    fun speak(answer: String, done: (() -> Unit)? = null) {
        val sentences = SpokenText.sentences(SpokenText.clean(answer))
        if (sentences.isEmpty()) return
        if (done != null) onDone = done
        synchronized(lock) {
            if (!usable) {
                pending = answer
                start()
                return
            }
        }
        say(sentences)
    }

    fun stop() {
        synchronized(lock) { pending = null }
        onDone = null
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        usable = false
    }

    private fun start() {
        if (engine != null || creating) return
        creating = true
        engine = TextToSpeech(context) { status ->
            creating = false
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                usable = false
                return@TextToSpeech
            }
            runCatching {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            val language = runCatching { tts.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                runCatching { tts.setLanguage(Locale.getDefault()) }
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "dsh-${counter.get()}") onDone?.invoke()
                }
                @Deprecated("platform signature")
                override fun onError(utteranceId: String?) {}
            })
            usable = true
            val waiting = synchronized(lock) { pending.also { pending = null } }
            if (waiting != null) {
                val sentences = SpokenText.sentences(SpokenText.clean(waiting))
                if (sentences.isNotEmpty()) say(sentences)
            }
        }
    }

    private fun say(sentences: List<String>) {
        val tts = engine ?: return
        val id = counter.incrementAndGet()
        sentences.forEachIndexed { i, sentence ->
            val utteranceId = if (i == sentences.lastIndex) "dsh-$id" else "dsh-$id-$i"
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }
}
