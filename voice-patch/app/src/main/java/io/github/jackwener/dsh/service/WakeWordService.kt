package io.github.jackwener.dsh.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.feature.quick.QuickActivity
import io.github.jackwener.dsh.feature.voice.WakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * 语音唤醒: a foreground service that keeps the phone's own recognizer listening for the wake word and
 * opens the quick panel already listening when it hears it.
 *
 * Deliberately simple (轻量版): the on-device recognizer when the phone has one, else the default
 * SpeechRecognizer, restarted after every result/error. That costs battery and shares the microphone
 * with everything else, so it only runs while the setting is on and is cancelled while DSH speaks.
 */
@AndroidEntryPoint
class WakeWordService : Service() {
    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var restart: Job? = null
    /** While the panel takes over the microphone no wake word is matched. */
    private var pausedUntil = 0L
    private var words = WakeWord.DEFAULT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        scope.launch { settingsRepo.settings.collect { s -> words = s.wakeWord } }
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        restart?.cancel()
        destroyRecognizer()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wake_word), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.dsw_microphone_outline)
            .setContentTitle(getString(R.string.wake_word))
            .setContentText(getString(R.string.wake_word_running, WakeWord.DEFAULT))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun listen() {
        restart?.cancel()
        if (SystemClock.elapsedRealtime() < pausedUntil) { schedule(pausedUntil - SystemClock.elapsedRealtime() + 200); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        destroyRecognizer()
        val r = runCatching {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
        }.getOrNull()
        if (r == null) { schedule(3_000); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) = heard(best(partialResults))
            override fun onResults(results: Bundle?) = heard(best(results), final = true)
            override fun onError(error: Int) {
                if (recognizer !== r) return
                // 6/7 = nothing heard; 8/9 = busy (another app has the microphone); the rest restarts too.
                schedule(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_500 else 400)
            }
        })
        runCatching { r.startListening(intent()) }.onFailure { schedule(1_000) }
    }

    private fun heard(text: String, final: Boolean = false) {
        val word = WakeWord.firstMatch(text, words)
        if (word != null) { trigger(word); return }
        if (final) schedule(300)
    }

    private fun trigger(word: String) {
        destroyRecognizer()
        // The panel takes the microphone itself: stay quiet until it is done (continuous conversation may run on).
        pausedUntil = SystemClock.elapsedRealtime() + QUIET_MS
        startActivity(Intent(this, QuickActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(QuickActivity.EXTRA_VOICE, true)
        })
        schedule(QUIET_MS)
    }

    private fun schedule(inMs: Long) {
        restart?.cancel()
        restart = scope.launch { delay(inMs); listen() }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel(); r.destroy() }
    }

    companion object {
        private const val CHANNEL = "wake_word"
        private const val ID = 42
        private const val QUIET_MS = 12_000L
    }
}
