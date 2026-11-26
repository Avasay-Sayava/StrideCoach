package com.stridecoach.running.core.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import java.util.Locale

class VoiceCoachEngine(ctx: Context) {
    private val app = ctx.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false

    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    ).setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    ).build()

    init {
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
            }
        }
    }

    fun handleEvent(ev: CoachingEvent, voice: Boolean, haptic: Boolean) {
        when (ev) {
            is CoachingEvent.StepStarted -> onStep(ev, voice, haptic)
            is CoachingEvent.PaceAlert -> onPace(ev, voice, haptic)
            is CoachingEvent.SplitCompleted -> onSplit(ev, voice, haptic)
            CoachingEvent.WorkoutCompleted -> onDone(voice, haptic)
        }
    }

    private fun onStep(ev: CoachingEvent.StepStarted, voice: Boolean, haptic: Boolean) {
        if (haptic) buzz(longArrayOf(0, 180, 100, 180))
        if (!voice) return
        val hint = ev.step.step.paceBounds?.let { " Target pace ${it.formatRange()}." } ?: ""
        speak("Next step: ${ev.step.displayName()}.$hint")
    }

    private fun onPace(ev: CoachingEvent.PaceAlert, voice: Boolean, haptic: Boolean) {
        if (haptic) buzz(longArrayOf(0, 90, 70, 90, 70, 90))
        if (!voice) return
        val p = formatPace(ev.currentPaceSec)
        val msg = if (ev.status == PaceStatus.TOO_FAST) {
            "Ease back. Pace $p, faster than target."
        } else {
            "Pick it up slightly. Pace $p."
        }
        speak(msg)
    }

    private fun onSplit(ev: CoachingEvent.SplitCompleted, voice: Boolean, haptic: Boolean) {
        if (haptic) buzz(longArrayOf(0, 220))
        if (voice) speak("Kilometer ${ev.kmNumber} in ${formatDuration(ev.splitDurationMillis)}.")
    }

    private fun onDone(voice: Boolean, haptic: Boolean) {
        if (haptic) buzz(longArrayOf(0, 300, 120, 300))
        if (voice) speak("Workout complete. Great job.")
    }

    fun speak(msg: String) {
        if (!ready) return
        audio.requestAudioFocus(focus)
        tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "cue_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        audio.abandonAudioFocusRequest(focus)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun buzz(pat: LongArray) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createWaveform(pat, -1))
    }
}
