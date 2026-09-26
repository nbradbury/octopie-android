package com.nbradbury.octopie.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {
    private lateinit var engine: GameEngine

    @Before
    fun setUp() {
        engine = GameEngine(Random(42))
        engine.resize(1000f, 2000f)
        engine.start()
    }

    private fun addPie(x: Float, y: Float, speed: Float = 0f) =
        Pie(x, y, width = 200f, speed = speed, wobbleSpeed = 0f).also { engine.pies += it }

    /** Steps the engine without letting the spawner add pies of its own. */
    private fun run(seconds: Float) {
        repeat((seconds / 0.01f).toInt()) {
            val spawned = engine.pies.size
            engine.update(0.01f)
            while (engine.pies.size > spawned && engine.pies.last().y < 0) engine.pies.removeAt(engine.pies.lastIndex)
        }
    }

    @Test
    fun `each missed pie costs a life and the third ends the game`() {
        repeat(3) { i ->
            addPie(500f, 2200f)
            run(0.02f)
            assertEquals(GameEngine.MAX_LIVES - i - 1, engine.lives)
        }
        assertEquals(Phase.GAME_OVER, engine.phase)
    }

    @Test
    fun `after losing the octopus pops before the results appear`() {
        addPie(300f, 600f)
        engine.tap(300f, 600f)
        repeat(3) { addPie(500f, 2200f) }
        run(0.02f)
        assertEquals(Phase.GAME_OVER, engine.phase)
        assertFalse(engine.octopusPopped)
        assertTrue(engine.tentacles.all { it.returning })

        run(GameEngine.SURPRISE_SECONDS + GameEngine.POP_SECONDS)
        assertTrue(engine.octopusPopped)
        assertFalse(engine.showResults)
        assertTrue(engine.tentacles.isEmpty())

        run(GameEngine.RESULTS_DELAY)
        assertTrue(engine.showResults)
        assertEquals(0, engine.lives)
    }

    @Test
    fun `the octopus looks at the closest pie`() {
        addPie(100f, 300f)
        addPie(900f, 100f)
        run(1f)
        assertTrue(engine.gazeX < -0.2f)
        assertTrue(engine.gazeY < -0.9f)
    }

    @Test
    fun `tapping a pie sends a tentacle that catches it`() {
        addPie(300f, 600f, speed = 200f)
        assertTrue(engine.tap(300f, 600f))
        run(2f)
        assertEquals(1, engine.score)
        assertEquals(1, engine.reaches)
        assertEquals(1, engine.gulps)
        assertTrue(engine.pies.isEmpty())
        assertTrue(engine.tentacles.isEmpty())
    }

    @Test
    fun `tapping empty water does nothing`() {
        addPie(300f, 600f)
        assertFalse(engine.tap(800f, 1200f))
        assertTrue(engine.tentacles.isEmpty())
    }

    @Test
    fun `a pie can only be chased by one tentacle`() {
        addPie(300f, 600f)
        assertTrue(engine.tap(300f, 600f))
        assertFalse(engine.tap(300f, 600f))
    }

    @Test
    fun `only eight tentacles can be out at once`() {
        repeat(9) { addPie(100f + it * 100f, 300f) }
        repeat(8) { assertTrue(engine.tap(100f + it * 100f, 300f)) }
        assertFalse(engine.tap(900f, 300f))
    }

    @Test
    fun `tentacles reach out from different arms`() {
        repeat(8) { addPie(100f + it * 100f, 300f) }
        repeat(8) { engine.tap(100f + it * 100f, 300f) }
        val roots = engine.tentacles.map { it.rootX to it.rootY }
        assertTrue(roots.toSet().size > 2)
        assertTrue(roots.zipWithNext().none { (a, b) -> a == b })
    }

    @Test
    fun `difficulty ramps up and then caps`() {
        (1 until 30).forEach {
            assertTrue(Difficulty.fallSpeed(it + 1) >= Difficulty.fallSpeed(it))
            assertTrue(Difficulty.spawnInterval(it + 1) <= Difficulty.spawnInterval(it))
            assertTrue(Difficulty.musicSpeed(it + 1) >= Difficulty.musicSpeed(it))
        }
        assertEquals(1f, Difficulty.musicSpeed(1))
        assertEquals(1.4f, Difficulty.musicSpeed(100))
        assertEquals(0.6f, Difficulty.fallSpeed(100))
        assertEquals(0.3f, Difficulty.spawnInterval(100))
    }
}
