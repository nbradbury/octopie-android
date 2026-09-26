package com.nbradbury.octopie.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

enum class Phase { READY, PLAYING, GAME_OVER }

class Pie(
    var x: Float,
    var y: Float,
    val width: Float,
    val speed: Float,
    val wobbleSpeed: Float,
    /** A bonus pie: worth [GameEngine.GOLDEN_POINTS], and letting it fall costs nothing. */
    val golden: Boolean = false,
) {
    val height get() = width * GameEngine.PIE_ASPECT
    val points get() = if (golden) GameEngine.GOLDEN_POINTS else 1
    var wobble = 0f
    var targeted = false
}

/** A reaching arm, growing from ([rootX], [rootY]) behind the octopus. */
class Tentacle(val pie: Pie, val rootX: Float, val rootY: Float, val curl: Float) {
    var tipX = rootX
    var tipY = rootY
    var age = 0f
    var returning = false
    var carrying = false
    var grabDistance = 1f
}

/** Pure game model in pixel space. The UI calls [resize], then drives it with [update] and [tap]. */
class GameEngine(private val random: Random = Random.Default) {
    var width = 0f
        private set
    var height = 0f
        private set

    // Octopus sprite bounds.
    var octoLeft = 0f
        private set
    var octoTop = 0f
        private set
    var octoWidth = 0f
        private set
    val octoHeight get() = octoWidth * OCTOPUS_ASPECT
    private val eyesX get() = octoLeft + octoWidth * 0.5f
    private val eyesY get() = octoTop + octoHeight * 0.25f

    val pies = mutableListOf<Pie>()
    val tentacles = mutableListOf<Tentacle>()

    var phase = Phase.READY
        private set
    var paused = false
        private set
    var score = 0
        private set
    var lives = MAX_LIVES
        private set
    var elapsed = 0f
        private set
    var lastMissAt = Float.NEGATIVE_INFINITY
        private set

    /** Seconds since the game ended; drives the surprised-then-pop animation. */
    var overTime = 0f
        private set
    val octopusPopped get() = phase == Phase.GAME_OVER && overTime >= SURPRISE_SECONDS + POP_SECONDS
    val showResults get() = phase == Phase.GAME_OVER && overTime >= RESULTS_DELAY

    /** Where the octopus is looking: a vector of length ≤ 1 that eases toward the closest falling pie. */
    var gazeX = 0f
        private set
    var gazeY = 0f
        private set
    val level get() = 1 + (elapsed / LEVEL_SECONDS).toInt()

    // Running totals across games; the UI plays a sound each time one goes up.
    var reaches = 0
        private set
    var gulps = 0
        private set

    private var spawnTimer = 0f
    private var lastRoot = 0

    fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        octoWidth = min(width * 0.62f, height * 0.4f)
        octoLeft = (width - octoWidth) / 2
        octoTop = height - octoHeight * 0.9f
    }

    fun start() {
        pies.clear()
        tentacles.clear()
        score = 0
        lives = MAX_LIVES
        elapsed = 0f
        lastMissAt = Float.NEGATIVE_INFINITY
        overTime = 0f
        paused = false
        gazeX = 0f
        gazeY = 0f
        spawnTimer = 0.5f
        phase = Phase.PLAYING
    }

    /** Freezes a game in progress; nothing moves and taps are ignored until [resume]. */
    fun pause() {
        if (phase == Phase.PLAYING) paused = true
    }

    fun resume() {
        paused = false
    }

    /** Abandons the current game and goes back to the start screen. */
    fun quit() {
        pies.clear()
        tentacles.clear()
        paused = false
        phase = Phase.READY
    }

    fun update(dt: Float) {
        if (phase == Phase.READY || paused) return
        elapsed += dt
        if (phase == Phase.GAME_OVER) {
            // Leftover pies drift off and arms retract while the octopus reacts.
            overTime += dt
            pies.forEach { it.y += it.speed * dt }
            pies.removeAll { it.y - it.height / 2 > height }
            updateTentacles(dt)
            return
        }

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnBurst()
            spawnTimer = Difficulty.spawnInterval(level)
        }

        for (pie in pies) {
            pie.y += pie.speed * dt
            pie.wobble += pie.wobbleSpeed * dt
        }
        updateTentacles(dt)
        updateGaze(dt)

        val missed = pies.filter { it.y - it.height / 2 > height }
        pies.removeAll(missed)
        tentacles.filter { it.pie in missed }.forEach { it.returning = true }
        val lost = missed.count { !it.golden }
        if (lost > 0) {
            lives = (lives - lost).coerceAtLeast(0)
            lastMissAt = elapsed
            if (lives == 0) {
                phase = Phase.GAME_OVER
                tentacles.forEach { it.returning = true }
            }
        }
    }

    /** Sends a tentacle after the pie under ([x], [y]). Returns false if there's no pie there or no arm free. */
    fun tap(x: Float, y: Float): Boolean {
        if (phase != Phase.PLAYING || paused || tentacles.size >= MAX_TENTACLES) return false
        val pie = pies
            .filter { !it.targeted && hypot(it.x - x, it.y - y) <= it.width / 2 * TAP_SLOP }
            .minByOrNull { hypot(it.x - x, it.y - y) }
            ?: return false
        pie.targeted = true
        val curl = if (random.nextBoolean()) 1f else -1f
        // A random arm, but never the one used last, so consecutive reaches look different.
        lastRoot = (lastRoot + 1 + random.nextInt(ARM_ROOTS.size - 1)) % ARM_ROOTS.size
        val (fx, fy) = ARM_ROOTS[lastRoot]
        tentacles += Tentacle(pie, octoLeft + octoWidth * fx, octoTop + octoHeight * fy, curl)
        reaches++
        return true
    }

    private fun updateTentacles(dt: Float) {
        val step = height * REACH_SPEED * dt
        val done = mutableListOf<Tentacle>()
        for (t in tentacles) {
            t.age += dt
            if (!t.returning) {
                if (moveTip(t, t.pie.x, t.pie.y, step)) {
                    pies.remove(t.pie)
                    score += t.pie.points
                    t.returning = true
                    t.carrying = true
                    t.grabDistance = hypot(t.tipX - t.rootX, t.tipY - t.rootY).coerceAtLeast(1f)
                }
            } else if (moveTip(t, t.rootX, t.rootY, step)) {
                done += t
                if (t.carrying) gulps++
            }
            if (t.carrying) {
                t.pie.x = t.tipX
                t.pie.y = t.tipY
            }
        }
        tentacles.removeAll(done)
    }

    private fun updateGaze(dt: Float) {
        var targetX = 0f
        var targetY = 0f
        pies.minByOrNull { hypot(it.x - eyesX, it.y - eyesY) }?.let {
            val dist = hypot(it.x - eyesX, it.y - eyesY).coerceAtLeast(1f)
            targetX = (it.x - eyesX) / dist
            targetY = (it.y - eyesY) / dist
        }
        val ease = min(1f, dt * GAZE_EASE)
        gazeX += (targetX - gazeX) * ease
        gazeY += (targetY - gazeY) * ease
    }

    /** Moves the tip up to [step] toward the target and returns true once it arrives. */
    private fun moveTip(t: Tentacle, x: Float, y: Float, step: Float): Boolean {
        val dist = hypot(x - t.tipX, y - t.tipY)
        if (dist <= step) {
            t.tipX = x
            t.tipY = y
            return true
        }
        t.tipX += (x - t.tipX) / dist * step
        t.tipY += (y - t.tipY) / dist * step
        return false
    }

    private fun spawnBurst() {
        val pieWidth = min(width * 0.2f, height * 0.11f)
        val speed = height * Difficulty.fallSpeed(level)
        val placed = mutableListOf<Float>()
        repeat(Difficulty.burstSize(level, random)) {
            var x = 0f
            // Keep pies in the same burst from overlapping; give up gracefully on narrow screens.
            for (attempt in 0 until 10) {
                x = pieWidth / 2 + random.nextFloat() * (width - pieWidth)
                if (placed.none { abs(it - x) < pieWidth }) break
            }
            placed += x
            pies += Pie(
                x = x,
                y = -pieWidth * PIE_ASPECT * (0.5f + random.nextFloat()),
                width = pieWidth,
                speed = speed * (0.85f + random.nextFloat() * 0.3f),
                wobbleSpeed = 2f + random.nextFloat() * 2f,
                golden = random.nextFloat() < GOLDEN_CHANCE,
            )
        }
    }

    companion object {
        const val MAX_LIVES = 3
        const val MAX_TENTACLES = 8
        const val LEVEL_SECONDS = 15f
        const val PIE_ASPECT = 215f / 400f
        const val OCTOPUS_ASPECT = 550f / 800f
        const val TAP_SLOP = 1.3f
        const val GOLDEN_POINTS = 5
        const val GOLDEN_CHANCE = 1f / 12
        const val SURPRISE_SECONDS = 1.1f
        const val POP_SECONDS = 0.18f
        const val RESULTS_DELAY = 2f
        const val GAZE_EASE = 8f

        /**
         * Where reaching arms start, as fractions of the octopus sprite: the body and the thick bases of the
         * side arms, where the sprite is solid enough to hide an arm's base.
         */
        val ARM_ROOTS = listOf(
            0.21f to 0.65f,
            0.30f to 0.65f,
            0.34f to 0.55f,
            0.50f to 0.52f,
            0.56f to 0.62f,
            0.64f to 0.55f,
            0.75f to 0.67f,
        )

        /** Tentacle tip speed in screen heights per second. */
        const val REACH_SPEED = 2.5f
    }
}

object Difficulty {
    /** Fall speed in screen heights per second. */
    fun fallSpeed(level: Int) = min(0.6f, 0.28f + 0.045f * (level - 1))

    fun spawnInterval(level: Int) = maxOf(0.3f, 0.8f / (1 + 0.25f * (level - 1)))

    /** Background music tempo: a little faster each level, back to normal outside play. */
    fun musicSpeed(level: Int) = min(1.4f, 1f + 0.05f * (level - 1))

    fun burstSize(level: Int, random: Random) = when {
        level < 4 -> 1
        level < 7 -> 1 + random.nextInt(2)
        else -> 1 + random.nextInt(3)
    }
}
