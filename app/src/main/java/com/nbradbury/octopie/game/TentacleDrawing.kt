package com.nbradbury.octopie.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot
import kotlin.math.sin

private val Skin = Color(0xFFE2632A)
private val SkinEdge = Color(0xFF9C3A16)
private val Sucker = Color(0xFFF7D9BE)
private const val SEGMENTS = 28

/**
 * Draws a tapered, curling arm from [origin] to [tip] along a quadratic Bézier. The control point is
 * pushed sideways by [curl] (±1) and wobbles with [age] so the arm looks alive.
 */
fun DrawScope.drawTentacle(origin: Offset, tip: Offset, curl: Float, age: Float, baseWidth: Float) {
    val dx = tip.x - origin.x
    val dy = tip.y - origin.y
    val length = hypot(dx, dy)
    if (length < 1f) return
    val normal = Offset(-dy / length, dx / length)
    val bend = length * (0.22f + 0.08f * sin(age * 7f)) * curl
    val control = Offset(origin.x + dx / 2, origin.y + dy / 2) + normal * bend

    val tipWidth = baseWidth * 0.22f
    val left = ArrayList<Offset>(SEGMENTS + 1)
    val right = ArrayList<Offset>(SEGMENTS + 1)
    val centres = ArrayList<Offset>(SEGMENTS + 1)
    val normals = ArrayList<Offset>(SEGMENTS + 1)
    val widths = FloatArray(SEGMENTS + 1)
    for (i in 0..SEGMENTS) {
        val t = i / SEGMENTS.toFloat()
        val u = 1 - t
        val p = origin * (u * u) + control * (2 * u * t) + tip * (t * t)
        val d = (control - origin) * (2 * u) + (tip - control) * (2 * t)
        val dLen = d.getDistance().coerceAtLeast(0.001f)
        val n = Offset(-d.y / dLen, d.x / dLen)
        val w = baseWidth + (tipWidth - baseWidth) * t
        centres += p
        normals += n
        widths[i] = w
        left += p + n * (w / 2)
        right += p - n * (w / 2)
    }

    val outline = Path().apply {
        moveTo(left[0].x, left[0].y)
        left.drop(1).forEach { lineTo(it.x, it.y) }
        right.asReversed().forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(outline, Skin)
    drawPath(outline, SkinEdge, style = Stroke(width = baseWidth * 0.06f))
    drawCircle(Skin, tipWidth / 2, tip)

    // Suckers run along the inside of the curl.
    val side = -curl
    for (i in 4 until SEGMENTS step 2) {
        val w = widths[i]
        drawCircle(Sucker, w * 0.17f, centres[i] + normals[i] * (side * w * 0.22f))
    }
}
