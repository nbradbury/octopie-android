package com.nbradbury.octopie.game

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/** Loops [res] while the app is visible and [enabled], at [speed] times the normal tempo (pitch unchanged). */
@Composable
fun BackgroundMusic(@RawRes res: Int, enabled: Boolean, speed: Float = 1f, volume: Float = 0.6f) {
    val context = LocalContext.current
    val player = remember(res) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        MediaPlayer.create(context, res, attributes, 0).apply {
            isLooping = true
            setVolume(volume, volume)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val currentSpeed by rememberUpdatedState(speed)
    LifecycleStartEffect(player, enabled) {
        if (enabled) {
            player.start()
            player.setSpeed(currentSpeed)
        }
        // pause() on a never-started player is an illegal state and breaks later start() calls.
        onStopOrDispose { if (player.isPlaying) player.pause() }
    }
    // Setting a speed on a paused player starts it, so only apply it while playing; start() above catches up.
    LaunchedEffect(player, speed) {
        if (player.isPlaying) player.setSpeed(speed)
    }
}

private fun MediaPlayer.setSpeed(speed: Float) {
    playbackParams = playbackParams.setSpeed(speed)
}
