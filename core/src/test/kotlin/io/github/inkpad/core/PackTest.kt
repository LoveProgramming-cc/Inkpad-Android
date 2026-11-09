package io.github.inkpad.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackTest {
    @Test
    fun packPointsIsIdenticalToBAndReadsWhatBWrote() {
        for ((i, c) in fixture("pack.json").arr().withIndex()) {
            val case = c.obj()
            assertEquals(case["q"], packPoints(case["pts"].points()), "case $i")
            near(unpackPoints(case["q"] as String), case["back"], "case $i")
        }
    }

    @Test
    fun whiteboardCoordinatesSurviveTheTripToHundredths() {
        val pts = listOf(pt(5e7, 5e7, 0.5), pt(5e7 + 0.37, 5e7 - 12.55, 0.6))
        val back = unpackPoints(packPoints(pts))
        assertEquals(5e7, back[0][0])
        assertTrue(abs(back[1][0] - (5e7 + 0.37)) < 0.006)
        assertTrue(abs(back[1][1] - (5e7 - 12.55)) < 0.006)
    }

    @Test
    fun pythonRoundingGoesToTheEvenNeighbour() {
        assertEquals(2L, pyRound(2.5))
        assertEquals(4L, pyRound(3.5))
        assertEquals(-2L, pyRound(-2.5))
        assertEquals(3L, pyRound(2.6))
    }
}
