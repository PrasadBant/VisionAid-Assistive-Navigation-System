package com.visionaid.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TextToSpeechManager(context: Context) {

    enum class Priority {
        LOW,
        NORMAL,
        IMPORTANT,
        EMERGENCY
    }

    companion object {
        private const val TAG = "TTS"

        private const val MIN_LOW_GAP_MS = 12000L
        private const val MIN_NORMAL_GAP_MS = 6000L
        private const val MIN_IMPORTANT_GAP_MS = 2500L
        private const val MIN_EMERGENCY_GAP_MS = 1000L
        private const val SAME_TEXT_BLOCK_MS = 20000L
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    private var lastSpeechAt = 0L
    private var lastText = ""

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.03f)
                tts?.setPitch(1.02f)
                isReady = true
                Log.i(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(text: String, priority: Priority = Priority.NORMAL) {
        if (!isReady || text.isBlank()) return

        val now = System.currentTimeMillis()

        val minimumGap = when (priority) {
            Priority.LOW -> MIN_LOW_GAP_MS
            Priority.NORMAL -> MIN_NORMAL_GAP_MS
            Priority.IMPORTANT -> MIN_IMPORTANT_GAP_MS
            Priority.EMERGENCY -> MIN_EMERGENCY_GAP_MS
        }

        if (text == lastText && now - lastSpeechAt < SAME_TEXT_BLOCK_MS) return
        if (now - lastSpeechAt < minimumGap) return

        val params = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                if (priority == Priority.EMERGENCY) 1.0f else 0.92f
            )
        }

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "vision_aid_$now"
        )

        lastText = text
        lastSpeechAt = now

        Log.d(TAG, "Speaking[$priority]: $text")
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
