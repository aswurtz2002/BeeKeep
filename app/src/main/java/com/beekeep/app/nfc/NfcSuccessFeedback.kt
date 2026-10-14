package com.beekeep.app.nfc

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short, non-blocking confirmation feedback after a hive/tag assignment is saved.
 * Audio and vibration fail independently so a muted device or missing vibrator
 * never affects the assignment itself.
 */
fun playNfcAssignmentSuccessFeedback(context: Context) {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
        if (tone.startTone(ToneGenerator.TONE_PROP_ACK, 170)) {
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 220L)
        } else {
            tone.release()
        }
    }

    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return@runCatching
        if (!vibrator.hasVibrator()) return@runCatching
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(45L, 85))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(45L)
        }
    }
}
