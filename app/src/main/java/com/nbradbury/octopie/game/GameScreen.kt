package com.nbradbury.octopie.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nbradbury.octopie.R
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The slice of engine state the overlays care about, so they only recompose when it changes. */
private data class Hud(
    val phase: Phase,
    val score: Int,
    val lives: Int,
    val level: Int,
    val showResults: Boolean,
    val paused: Boolean,
)

@Composable
fun GameScreen(
    bestScore: Int,
    onNewBest: (Int) -> Unit,
    soundOn: Boolean,
    onSoundChange: (Boolean) -> Unit,
) {
    val engine = remember { GameEngine() }
    var frameTime by remember { mutableLongStateOf(0L) }
    var hud by remember { mutableStateOf(Hud(engine.phase, 0, engine.lives, 1, false, false)) }
    var bestAtStart by remember { mutableIntStateOf(bestScore) }
    val context = LocalContext.current
    val sounds = remember { GameSounds(context) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    SideEffect { sounds.enabled = soundOn }
    BackgroundMusic(
        R.raw.sunlight_through_the_blue,
        enabled = soundOn,
        speed = if (hud.phase == Phase.PLAYING) Difficulty.musicSpeed(hud.level) else 1f,
    )

    LaunchedEffect(Unit) {
        var last = 0L
        var reachesHeard = engine.reaches
        var gulpsHeard = engine.gulps
        var poppedHeard = engine.octopusPopped
        while (true) {
            withFrameNanos { now ->
                // Clamp so a resume from the background doesn't teleport the pies.
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                engine.update(dt)
                if (engine.reaches > reachesHeard) {
                    reachesHeard = engine.reaches
                    sounds.swoosh()
                }
                if (engine.gulps > gulpsHeard) {
                    gulpsHeard = engine.gulps
                    sounds.gulp()
                }
                if (engine.octopusPopped && !poppedHeard) sounds.pop()
                poppedHeard = engine.octopusPopped
                frameTime = now
                hud = Hud(engine.phase, engine.score, engine.lives, engine.level, engine.showResults, engine.paused)
            }
        }
    }
    // Never let the game run on unattended: pause when the app loses focus, and make Back pause (or resume).
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { engine.pause() }
    BackHandler(enabled = hud.phase == Phase.PLAYING) {
        if (engine.paused) engine.resume() else engine.pause()
    }

    LaunchedEffect(hud.phase) {
        if (hud.phase == Phase.GAME_OVER && engine.score > bestScore) onNewBest(engine.score)
    }

    val reef = ImageBitmap.imageResource(R.drawable.reef_bg)
    val octopus = ImageBitmap.imageResource(R.drawable.octopus)
    val octopusIris = ImageBitmap.imageResource(R.drawable.octopus_iris)
    val octopusSurprised = ImageBitmap.imageResource(R.drawable.octopus_surprised)
    val popBits = remember { popBits() }
    val pie = ImageBitmap.imageResource(R.drawable.pie)
    val textMeasurer = rememberTextMeasurer()

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
                    origin = Offset(it.rootX, it.rootY),
                    tip = Offset(it.tipX, it.tipY),
                    curl = it.curl,
                    age = it.age,
                    baseWidth = engine.octoWidth * 0.09f,
                )
            }
            drawOctopus(engine, time, octopus, octopusIris, octopusSurprised, popBits)
            engine.pies.forEach {
                drawPie(pie, it.x, it.y, it.width, sin(it.wobble) * 12f, it.golden, time, textMeasurer)
            }
            engine.tentacles.filter { it.carrying }.forEach {
                // Shrink the pie as it's reeled in toward the octopus's mouth.
                val remaining = hypot(it.tipX - it.rootX, it.tipY - it.rootY) / it.grabDistance
                val width = it.pie.width * (0.4f + 0.6f * remaining)
                drawPie(pie, it.tipX, it.tipY, width, 0f, it.pie.golden, time, textMeasurer)
            }
            drawMissFlash(engine.elapsed - engine.lastMissAt)
        }

        when (hud.phase) {
            Phase.READY -> Panel {
                Title(stringResource(R.string.app_name), 48.sp)
                BasicText(
                    stringResource(R.string.byline),
                    style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, shadow = TextShadow),
                )
                Label(stringResource(R.string.tagline))
                Label(stringResource(R.string.rules, GameEngine.MAX_LIVES))
                if (bestScore > 0) Title(stringResource(R.string.best, bestScore), 24.sp)
                PlayButton(stringResource(R.string.play)) {
                    bestAtStart = bestScore
                    engine.start()
                }
            }
            Phase.PLAYING -> {
                HudBar(hud, onPause = engine::pause.takeUnless { hud.paused })
                if (hud.paused) Panel {
                    Title(stringResource(R.string.paused), 40.sp)
                    Title(stringResource(R.string.score, hud.score), 28.sp)
                    PlayButton(stringResource(R.string.resume), onClick = engine::resume)
                    SecondaryButton(stringResource(R.string.quit), onClick = engine::quit)
                }
            }
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
        if (hud.phase == Phase.READY || hud.showResults || hud.paused) {
            SoundToggle(
                on = soundOn,
                onChange = onSoundChange,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp),
            )
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

private fun DrawScope.drawPie(
    image: ImageBitmap,
    x: Float,
    y: Float,
    width: Float,
    degrees: Float,
    golden: Boolean,
    time: Float,
    measurer: TextMeasurer,
) {
    val height = width * GameEngine.PIE_ASPECT
    val centre = Offset(x, y)
    if (golden) {
        // A soft pulsing halo so bonus pies stand out from the crowd.
        val glow = width * 0.8f
        val alpha = 0.45f + 0.2f * sin(time * 6f)
        drawCircle(
            Brush.radialGradient(listOf(GoldGlow.copy(alpha = alpha), Color.Transparent), centre, glow),
            glow,
            centre,
        )
    }
    rotate(degrees, pivot = centre) {
        drawImage(
            image,
            dstOffset = IntOffset((x - width / 2).roundToInt(), (y - height / 2).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            colorFilter = if (golden) GoldFilter else null,
        )
        if (golden) drawBonusLabel(measurer, "+${GameEngine.GOLDEN_POINTS}", centre, height)
    }
}

/** Outlined white text centred on a golden pie. */
private fun DrawScope.drawBonusLabel(measurer: TextMeasurer, text: String, centre: Offset, pieHeight: Float) {
    // Colour and style go in at draw time, and Fill must be explicit: the layout's paint keeps the last
    // drawStyle it was given, so leaving it unset would stroke the white text too.
    val layout = measurer.measure(text, TextStyle(fontSize = (pieHeight * 0.6f).toSp(), fontWeight = FontWeight.Black))
    val topLeft = centre - Offset(layout.size.width / 2f, layout.size.height / 2f)
    drawText(
        layout,
        color = GoldOutline,
        topLeft = topLeft,
        drawStyle = Stroke(width = pieHeight * 0.09f, join = StrokeJoin.Round),
    )
    drawText(layout, color = Color.White, topLeft = topLeft, drawStyle = Fill)
}

// Recolours the pie photo as gold: its brightness, tinted warm and boosted.
private val GoldFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.41f, 0.80f, 0.15f, 0f, 12f,
            0.32f, 0.62f, 0.12f, 0f, 6f,
            0.10f, 0.20f, 0.04f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)
private val GoldGlow = Color(0xFFFFE27A)
private val GoldOutline = Color(0xFF6B3A00)

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

private val PanelColor = Color(0xAA06283A)
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
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(bottom = 160.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(PanelColor)
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
private fun HudBar(hud: Hud, onPause: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
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
        // While paused the sound toggle takes this corner instead.
        Spacer(Modifier.size(12.dp))
        if (onPause != null) PauseButton(onPause) else Spacer(Modifier.size(44.dp))
    }
}

/** A round speaker button: sound waves when [on], a cross when muted. */
@Composable
private fun SoundToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.sound)
    Canvas(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(PanelColor)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .padding(12.dp),
    ) {
        // Drawn on a 24-unit grid.
        val u = size.width / 24
        val speaker = Path().apply {
            moveTo(3 * u, 9 * u)
            lineTo(8 * u, 9 * u)
            lineTo(13 * u, 4 * u)
            lineTo(13 * u, 20 * u)
            lineTo(8 * u, 15 * u)
            lineTo(3 * u, 15 * u)
            close()
        }
        drawPath(speaker, Color.White)
        if (on) {
            for (radius in listOf(4f, 8f)) {
                drawArc(
                    Color.White,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset((13 - radius) * u, (12 - radius) * u),
                    size = Size(2 * radius * u, 2 * radius * u),
                    style = Stroke(width = 2 * u, cap = StrokeCap.Round),
                )
            }
        } else {
            drawLine(Color.White, Offset(16 * u, 9 * u), Offset(22 * u, 15 * u), 2 * u, StrokeCap.Round)
            drawLine(Color.White, Offset(22 * u, 9 * u), Offset(16 * u, 15 * u), 2 * u, StrokeCap.Round)
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
    }
}

/** A round button with two bars. */
@Composable
private fun PauseButton(onClick: () -> Unit) {
    val label = stringResource(R.string.pause)
    Canvas(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(PanelColor)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(14.dp),
    ) {
        val bar = size.width * 0.3f
        drawRect(Color.White, topLeft = Offset(0f, 0f), size = Size(bar, size.height))
        drawRect(Color.White, topLeft = Offset(size.width - bar, 0f), size = Size(bar, size.height))
    }
}
