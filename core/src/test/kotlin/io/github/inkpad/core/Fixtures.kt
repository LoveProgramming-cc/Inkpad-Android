package io.github.inkpad.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Test data made by B's own Python code (shared with the web version's tests). */
fun fixture(name: String): Any? {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(name) ?: error("no fixture $name")
    return Json.parse(stream.readBytes().toString(Charsets.UTF_8))
}

/** Deep comparison; numbers may differ by a relative 1e-9. */
fun near(actual: Any?, expected: Any?, path: String = "$") {
    when (expected) {
        is Number -> {
            val a = (actual as? Number)?.toDouble() ?: fail("$path should be a number, is $actual")
            val e = expected.toDouble()
            assertTrue(abs(a - e) <= 1e-9 * max(1.0, abs(e)), "$path: $a != $e")
        }
        is List<*> -> {
            val a = toList(actual) ?: fail("$path should be a list, is $actual")
            assertEquals(expected.size, a.size, "$path length")
            expected.forEachIndexed { i, v -> near(a[i], v, "$path[$i]") }
        }
        null -> assertEquals(null, actual, path)
        else -> assertEquals(expected, actual, path)
    }
}

private fun toList(x: Any?): List<Any?>? = when (x) {
    is List<*> -> x
    is DoubleArray -> x.toList()
    is Pair<*, *> -> listOf(x.first, x.second)
    else -> null
}
