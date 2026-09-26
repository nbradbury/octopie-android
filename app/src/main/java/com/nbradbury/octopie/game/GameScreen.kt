package com.nbradbury.octopie.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbradbury.octopie.R
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The slice of engine state the overlays care about, so they only recompose when it changes. */
private data class Hud(val phase: Phase, val score: Int, val lives: Int, val level: Int, val showResults: Boolean)

@Composable
fun GameScreen(bestScore: Int, onNewBest: (Int) -> Unit) {
    val engine = remember { GameEngine() }
    var frameTime by remember { mutableLongStateOf(0L) }
    var hud by remember { mutableStateOf(Hud(engine.phase, 0, engine.lives, 1, false)) }
    var bestAtStart by remember { mutableIntStateOf(bestScore) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // Clamp so a resume from the background doesn't teleport the pies.
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                engine.update(dt)
                frameTime = now
                hud = Hud(engine.phase, engine.score, engine.lives, engine.level, engine.showResults)
            }
        }
    }
    LaunchedEffect(hud.phase) {
        if (hud.phase == Phase.GAME_OVER && engine.score > bestScore) onNewBest(engine.score)
    }

    val reef = ImageBitmap.imageResource(R.drawable.reef_bg)
    val octopus = ImageBitmap.imageResource(R.drawable.octopus)
    val octopusSurprised = ImageBitmap.imageResource(R.drawable.octopus_surprised)
    val popBits = remember { popBits() }
    val pie = ImageBitmap.imageResource(R.drawable.pie)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { engine.resize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                // React on finger down (not up) and to every finger, so fast multi-finger play works.
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.filter { it.changedToDown() }.forEach { engine.tap(it.position.x, it.position.y) }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val time = frameTime / 1e9f
            drawCentreCropped(reef)
            engine.tentacles.forEach {
                drawTentacle(
                    origin = Offset(engine.originX, engine.originY),
                    tip = Offset(it.tipX, it.tipY),
                    curl = it.curl,
                    age = it.age,
                    baseWidth = engine.octoWidth * 0.09f,
                )
            }
            drawOctopus(engine, time, octopus, octopusSurprised, popBits)
            engine.pies.forEach { drawPie(pie, it.x, it.y, it.width, sin(it.wobble) * 12f) }
            engine.tentacles.filter { it.carrying }.forEach {
                // Shrink the pie as it's reeled in toward the octopus's mouth.
                val remaining = hypot(it.tipX - engine.originX, it.tipY - engine.originY) / it.grabDistance
                drawPie(pie, it.tipX, it.tipY, it.pie.width * (0.4f + 0.6f * remaining), 0f)
            }
            drawMissFlash(engine.elapsed - engine.lastMissAt)
        }

        when (hud.phase) {
            Phase.READY -> Panel {
                Title(stringResource(R.string.app_name), 48.sp)
                Label(stringResource(R.string.tagline))
                PlayButton(stringResource(R.string.play)) {
                    bestAtStart = bestScore
                    engine.start()
                }
            }
            Phase.PLAYING -> HudBar(hud)
            Phase.GAME_OVER -> if (hud.showResults) Panel {
                Title(stringResource(R.string.game_over), 40.sp)
                Title(stringResource(R.string.score, hud.score), 28.sp)
                Label(
                    if (hud.score > bestAtStart) {
                        stringResource(R.string.new_best)
                    } else {
                        stringResource(R.string.best, bestScore)
                    },
                )
                PlayButton(stringResource(R.string.play_again)) {
                    bestAtStart = bestScore
                    engine.start()
                }
            }
        }
    }
}

private fun DrawScope.drawCentreCropped(image: ImageBitmap) {
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcW = (size.width / scale).roundToInt()
    val srcH = (size.height / scale).roundToInt()
    drawImage(
        image,
        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

private fun DrawScope.drawPie(image: ImageBitmap, x: Float, y: Float, width: Float, degrees: Float) {
    val height = width * GameEngine.PIE_ASPECT
    rotate(degrees, pivot = Offset(x, y)) {
        drawImage(
            image,
            dstOffset = IntOffset((x - width / 2).roundToInt(), (y - height / 2).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
        )
    }
}

private fun DrawScope.drawMissFlash(sinceMiss: Float) {
    val alpha = (1f - sinceMiss / 0.5f).coerceIn(0f, 1f) * 0.6f
    if (alpha <= 0f) return
    drawRect(
        Brush.verticalGradient(
            0.6f to Color.Transparent,
            1f to Color.Red.copy(alpha = alpha),
        ),
    )
}

private val TextShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 6f)

@Composable
private fun Title(text: String, size: TextUnit) = BasicText(
    text,
    style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.Black, shadow = TextShadow),
)

@Composable
private fun Label(text: String) = BasicText(
    text,
    style = TextStyle(color = Color.White, fontSize = 18.sp, shadow = TextShadow),
)

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(bottom = 160.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xAA06283A))
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun PlayButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE2632A))
            .clickable(onClick = onClick)
            .padding(horizontal = 40.dp, vertical = 14.dp),
    ) {
        BasicText(
            text,
            style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun HudBar(hud: Hud) {
    Row(
        Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Title(stringResource(R.string.score, hud.score), 28.sp)
            Label(stringResource(R.string.level, hud.level))
        }
        repeat(GameEngine.MAX_LIVES) { i ->
            Image(
                painterResource(R.drawable.pie),
                contentDescription = null,
                modifier = Modifier.size(width = 44.dp, height = 24.dp).alpha(if (i < hud.lives) 1f else 0.25f),
            )
        }
    }
}
