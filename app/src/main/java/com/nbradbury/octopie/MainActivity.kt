package com.nbradbury.octopie

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nbradbury.octopie.game.GameScreen

private const val KEY_BEST = "best_score"
private const val KEY_SOUND = "sound_on"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The volume keys adjust the game's sound, not the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC
        // Full screen: hide the status and navigation bars; a swipe from the edge shows them briefly.
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val prefs = getSharedPreferences("scores", Context.MODE_PRIVATE)
        setContent {
            var best by remember { mutableIntStateOf(prefs.getInt(KEY_BEST, 0)) }
            var soundOn by remember { mutableStateOf(prefs.getBoolean(KEY_SOUND, true)) }
            GameScreen(
                bestScore = best,
                onNewBest = {
                    best = it
                    prefs.edit { putInt(KEY_BEST, it) }
                },
                soundOn = soundOn,
                onSoundChange = {
                    soundOn = it
                    prefs.edit { putBoolean(KEY_SOUND, it) }
                },
            )
        }
    }
}
