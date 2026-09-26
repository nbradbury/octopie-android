package com.nbradbury.octopie.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.nbradbury.octopie.R
import kotlin.random.Random

/** The game's sound effects. Repeated sounds are pitched a little differently each time so they don't grate. */
class GameSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val gulp = pool.load(context, R.raw.gulp, 1)
    private val swoosh = pool.load(context, R.raw.swoosh, 1)
    private val pop = pool.load(context, R.raw.pop, 1)

    var enabled = true

    /** The octopus swallows a pie. */
    fun gulp() = play(gulp)

    /** A tentacle whips out. */
    fun swoosh() = play(swoosh)

    /** The octopus bursts. */
    fun pop() = play(pop, rate = 1f)

    fun release() = pool.release()

    private fun play(sound: Int, rate: Float = 0.9f + Random.nextFloat() * 0.25f) {
        if (enabled) pool.play(sound, 1f, 1f, 1, 0, rate)
    }
}
