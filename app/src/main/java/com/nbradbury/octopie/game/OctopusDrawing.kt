package com.nbradbury.octopie.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** One fleck of the burst when the octopus pops, in units of the octopus width. */
class PopBit(val angle: Float, val speed: Float, val radius: Float, val bubble: Boolean)

fun popBits(random: Random = Random(7)) = List(26) {
    PopBit(
        angle = (it / 26f + random.nextFloat() * 0.03f) * 2 * PI.toFloat(),
        speed = 0.5f + random.nextFloat() * 0.5f,
        radius = 0.015f + random.nextFloat() * 0.03f,
        bubble = it % 3 == 0,
    )
}

/** An eye white and its resting iris, in sprite pixels. Must match EYES in tools/prepare_assets.py. */
private class Eye(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val irisX: Float,
    val irisY: Float,
    val irisRx: Float,
    val irisRy: Float,
)

private val Eyes = listOf(
    Eye(341f, 132f, 20f, 25.5f, irisX = 345f, irisY = 136f, irisRx = 16f, irisRy = 20.5f),
    Eye(456f, 136f, 33f, 28f, irisX = 454.5f, irisY = 138.5f, irisRx = 21.5f, irisRy = 21.5f),
)
private const val SPRITE_WIDTH = 800f

private val Ink = Color(0xFFE2632A)
private val InkDark = Color(0xFFB8471C)

/**
 * Draws the octopus: bobbing while playing and watching the closest pie; on game over it looks surprised and
 * trembles, swells up, then pops into a spray of droplets and bubbles.
 */
fun DrawScope.drawOctopus(
    engine: GameEngine,
    time: Float,
    body: ImageBitmap,
    iris: ImageBitmap,
    surprised: ImageBitmap,
    bits: List<PopBit>,
) {
    val w = engine.octoWidth
    val h = engine.octoHeight
    val centre = Offset(engine.octoLeft + w / 2, engine.octoTop + h * 0.45f)

    if (engine.phase != Phase.GAME_OVER) {
        val top = engine.octoTop + sin(time * 2f) * h * 0.015f
        drawSprite(body, engine.octoLeft, top, w, h)
        drawIrises(iris, engine.octoLeft, top, w / SPRITE_WIDTH, engine.gazeX, engine.gazeY)
        return
    }

    val t = engine.overTime
    val popAt = GameEngine.SURPRISE_SECONDS
    if (t < popAt + GameEngine.POP_SECONDS) {
        val swell: Float
        val dx: Float
        val dy: Float
        var alpha = 1f
        if (t < popAt) {
            // A startled hop, then an ever-faster tremble as it puffs up.
            val p = t / popAt
            dy = -sin(PI.toFloat() * min(t / 0.3f, 1f)) * h * 0.08f
            dx = sin(t * 70f) * w * 0.012f * p
            swell = 1f + 0.08f * p * p
        } else {
            val p = (t - popAt) / GameEngine.POP_SECONDS
            dx = 0f
            dy = 0f
            swell = 1.08f + 0.35f * p
            alpha = 1f - p * p
        }
        scale(swell, pivot = centre) {
            drawSprite(surprised, engine.octoLeft + dx, engine.octoTop + dy, w, h, alpha)
        }
    }

    val burstT = t - popAt - GameEngine.POP_SECONDS * 0.6f
    if (burstT > 0f) drawBurst(centre, w, burstT, bits)
}

private fun DrawScope.drawSprite(image: ImageBitmap, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
    drawImage(
        image,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        alpha = alpha,
    )
}

private fun DrawScope.drawIrises(iris: ImageBitmap, left: Float, top: Float, scale: Float, gazeX: Float, gazeY: Float) {
    for (eye in Eyes) {
        val white = Path().apply {
            addOval(
                Rect(
                    left + (eye.cx - eye.rx) * scale,
                    top + (eye.cy - eye.ry) * scale,
                    left + (eye.cx + eye.rx) * scale,
                    top + (eye.cy + eye.ry) * scale,
                ),
            )
        }
        // Enough travel for the iris to tuck partly under the lid when looking hard to one side.
        val x = eye.irisX + gazeX * (eye.rx - eye.irisRx * 0.6f)
        val y = eye.irisY + gazeY * (eye.ry - eye.irisRy * 0.6f)
        val irisLeft = left + (x - eye.irisRx) * scale
        val irisTop = top + (y - eye.irisRy) * scale
        clipPath(white) {
            drawImage(
                iris,
                dstOffset = IntOffset(irisLeft.roundToInt(), irisTop.roundToInt()),
                dstSize = IntSize((2 * eye.irisRx * scale).roundToInt(), (2 * eye.irisRy * scale).roundToInt()),
            )
        }
    }
}

private const val BURST_SECONDS = 0.8f

private fun DrawScope.drawBurst(centre: Offset, w: Float, t: Float, bits: List<PopBit>) {
    val p = (t / BURST_SECONDS).coerceAtMost(1f)
    if (p >= 1f) return
    val fade = 1f - p
    val travel = 1f - (1f - p) * (1f - p) // ease out

    drawCircle(
        Color.White.copy(alpha = fade * 0.8f),
        radius = w * (0.15f + 0.55f * travel),
        center = centre,
        style = Stroke(width = w * 0.025f * fade + 1f),
    )
    for (bit in bits) {
        val dist = w * bit.speed * travel
        // Bubbles float upward a little as they spread.
        val lift = if (bit.bubble) w * 0.25f * p else 0f
        val pos = centre + Offset(cos(bit.angle) * dist, sin(bit.angle) * dist - lift)
        val r = w * bit.radius * (if (bit.bubble) 1f else fade)
        if (bit.bubble) {
            drawCircle(Color.White.copy(alpha = fade), r, pos, style = Stroke(width = r * 0.25f))
        } else {
            drawCircle((if (bit.radius > 0.03f) InkDark else Ink).copy(alpha = fade), r, pos)
        }
    }
}
