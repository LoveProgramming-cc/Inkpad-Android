package io.github.inkpad.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeomTest {
    private val f = fixture("geom.json").obj()

    private class Item(override val kind: String, override val bbox: Box, val line: List<Pt>, val samples: List<Pt>) : Shaped {
        override fun polyline() = line
        override fun sample() = samples
    }

    private val items = f["items"].arr().map {
        val s = it.obj()
        Item(s["kind"] as String, s["bbox"].point(), s["polyline"].points(), s["sample"].points())
    }

    private fun indices(picked: List<Item>) = picked.map { items.indexOf(it).toLong() }

    @Test
    fun recognizeShape() {
        for ((i, c) in f["shapes"].arr().withIndex()) {
            val case = c.obj()
            val got = Geom.recognizeShape(case["pts"].points())
            near(got?.let { listOf(it.shape, it.x0, it.y0, it.x1, it.y1) }, case["shape"], "shape $i")
        }
    }

    @Test
    fun looseScribbleIsBsEarlierRule() {
        for ((i, c) in f["scribbles"].arr().withIndex()) {
            val case = c.obj()
            assertEquals(case["is"], Geom.isScribble(case["pts"].points(), loose = true), "scribble $i")
        }
    }

    @Test
    fun scribblesAsTheDesktopAndIosAppsJudgeThem() {
        // A's 55 synthetic strokes, with what B's geom.is_scribble says of each.
        val samples = fixture("scribbles.json").obj()["samples"].arr().map { it.obj() }
        for (s in samples) {
            val pts = s["pts"].points()
            assertEquals(s["is"], Geom.isScribble(pts), s["name"] as String)
            if (s["name"] == "word:tro") assertTrue(Geom.isScribble(pts, loose = true), "word:tro (loose)")
        }
        assertTrue(samples.any { it["name"] == "word:tro" && it["is"] == false })
        assertTrue(samples.any { it["name"] == "scratch-out over a line of words" && it["is"] == true })
    }

    @Test
    fun scribbleTargetsLassoAndRectPickTheSameItemsAsB() {
        for ((i, c) in f["scribble_targets"].arr().withIndex()) {
            val case = c.obj()
            near(indices(Geom.scribbleTargets(case["pts"].points(), items)), case["hits"], "scribble target $i")
        }
        for ((i, c) in f["lasso"].arr().withIndex()) {
            val case = c.obj()
            near(indices(Geom.lassoPick(case["poly"].points(), items)), case["hits"], "lasso $i")
        }
        for ((i, c) in f["rect"].arr().withIndex()) {
            val case = c.obj()
            near(indices(Geom.rectPick(case["box"].point(), items)), case["hits"], "rect $i")
        }
    }

    @Test
    fun eraseRuns() {
        var cut = 0
        val cases = f["erase"].arr()
        for ((i, c) in cases.withIndex()) {
            val case = c.obj()
            val s = case["seg"].point()
            val r = Geom.eraseRuns(case["pts"].points(), s[0], s[1], s[2], s[3], case["r"].num())
            near(r, case["out"], "erase $i")
            if (r != null) cut++
        }
        assertTrue(cut > 5 && cut < cases.size)
    }

    @Test
    fun convexHullDecimateDensify() {
        for ((i, c) in f["hull"].arr().withIndex()) {
            val case = c.obj()
            near(Geom.convexHull(case["pts"].points()), case["hull"], "hull $i")
        }
        for (c in f["decimate"].arr()) {
            val case = c.obj()
            val pts = List(case["n"].num().toInt()) { pt(it.toDouble(), 0.0, 0.0) }
            near(Geom.decimate(pts, case["limit"].num().toInt()).map { it[0] }, case["idx"])
        }
        for ((i, c) in f["densify"].arr().withIndex()) {
            val case = c.obj()
            near(Geom.densify(case["pts"].points(), case["step"].num()), case["out"], "densify $i")
        }
    }

    @Test
    fun smallHelpers() {
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), Geom.union(null, doubleArrayOf(1.0, 2.0, 3.0, 4.0)))
        assertContentEquals(doubleArrayOf(0.0, -1.0, 3.0, 1.0), Geom.union(doubleArrayOf(0.0, 0.0, 1.0, 1.0), doubleArrayOf(2.0, -1.0, 3.0, 0.0)))
        assertTrue(Geom.intersects(doubleArrayOf(0.0, 0.0, 1.0, 1.0), doubleArrayOf(1.0, 1.0, 2.0, 2.0)))
        assertFalse(Geom.intersects(doubleArrayOf(0.0, 0.0, 1.0, 1.0), doubleArrayOf(1.1, 1.0, 2.0, 2.0)))
        assertContentEquals(doubleArrayOf(0.0, 1.0, 4.0, 6.0), Geom.bboxOf(listOf(doubleArrayOf(1.0, 5.0), doubleArrayOf(3.0, 2.0)), 1.0))
        val square = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0), doubleArrayOf(10.0, 10.0), doubleArrayOf(0.0, 10.0))
        assertTrue(Geom.inPolygon(square, 5.0, 5.0))
        assertFalse(Geom.inPolygon(square, 15.0, 5.0))
        val line = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0))
        assertTrue(Geom.polylineHit(line, 5.0, 1.0, 1.5))
        assertFalse(Geom.polylineHit(line, 5.0, 2.0, 1.5))
        assertNull(Geom.recognizeShape(emptyList()))
    }
}
