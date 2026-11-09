package io.github.inkpad.core

/**
 * A small JSON reader: objects become LinkedHashMap<String, Any?>, arrays List<Any?>, numbers
 * Double (or Long when written without a fraction or exponent), strings, booleans and null.
 * Written here rather than taken from a library so that the core runs the same on a plain JVM
 * and on Android, and so that the writer that goes with it can print exactly what B prints.
 */
object Json {
    class SyntaxError(message: String, at: Int) : Exception("$message at $at")

    fun parse(text: String): Any? {
        val r = Reader(text)
        r.space()
        val value = r.value()
        r.space()
        if (r.i != text.length) throw SyntaxError("trailing characters", r.i)
        return value
    }

    private class Reader(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw SyntaxError("unexpected end", i)
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9' || c == 'N' || c == 'I') num() else throw SyntaxError("unexpected '$c'", i)
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw SyntaxError("expected $w", i)
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            space()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') throw SyntaxError("expected a key", i)
                val k = str()
                space()
                if (i >= s.length || s[i] != ':') throw SyntaxError("expected ':'", i)
                i++
                space()
                out[k] = value()
                space()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return out }
                throw SyntaxError("expected ',' or '}'", i)
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            space()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                space()
                out.add(value())
                space()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return out }
                throw SyntaxError("expected ',' or ']'", i)
            }
        }

        private fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw SyntaxError("unterminated string", i)
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw SyntaxError("unterminated string", i)
                        when (val e = s[i++]) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw SyntaxError("bad \\u escape", i)
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw SyntaxError("bad escape '\\$e'", i)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            // Python writes these for floats that are not finite.
            for ((w, v) in listOf("NaN" to Double.NaN, "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY)) {
                if (s.startsWith(w, i)) { i += w.length; return v }
            }
            if (i < s.length && s[i] == '-') i++
            var whole = true
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') i++
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { whole = false; i++ }
                else break
            }
            val t = s.substring(start, i)
            if (whole) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw SyntaxError("bad number '$t'", start)
        }
    }
}

/** A JSON number as a Double, whichever way it was written. */
fun Any?.num(): Double = (this as Number).toDouble()

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?> = this as List<Any?>

/** A JSON list of numbers ([x, y, p]) as a point. */
fun Any?.point(): Pt = arr().map { it.num() }.toDoubleArray()

/** A JSON list of points. */
fun Any?.points(): List<Pt> = arr().map { it.point() }

/**
 * Writes JSON the way B does (Python's json.dumps with separators=(',', ':')): floats in Python's
 * repr (595.0, 1e+16), anything outside ASCII as \uXXXX, keys in insertion order. Integral values
 * meant as integers must be passed as Int or Long.
 */
object JsonOut {
    fun write(value: Any?): String = StringBuilder().also { put(it, value) }.toString()

    private fun put(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is Boolean -> b.append(if (v) "true" else "false")
            is Int, is Long, is Short -> b.append(v.toString())
            is Double -> b.append(pyFloat(v))
            is Float -> b.append(pyFloat(v.toDouble()))
            is String -> str(b, v)
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(',')
                    first = false
                    str(b, k as String)
                    b.append(':')
                    put(b, x)
                }
                b.append('}')
            }
            is Iterable<*> -> {
                b.append('[')
                var first = true
                for (x in v) {
                    if (!first) b.append(',')
                    first = false
                    put(b, x)
                }
                b.append(']')
            }
            is DoubleArray -> put(b, v.toList())
            is IntArray -> put(b, v.toList())
            else -> throw IllegalArgumentException("cannot write ${v::class} as JSON")
        }
    }

    private fun str(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c == '\b' -> b.append("\\b")
                c == '\u000c' -> b.append("\\f")
                c < ' ' || c.code > 126 -> b.append("\\u%04x".format(c.code))
                else -> b.append(c)
            }
        }
        b.append('"')
    }

    /** Python's repr() of a float: the shortest digits that read back the same; scientific
     *  notation below 1e-4 and from 1e16 on. */
    fun pyFloat(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
        if (x == 0.0) return if (1.0 / x < 0) "-0.0" else "0.0"
        val bd = java.math.BigDecimal(x.toString())   // Java's toString gives the shortest digits too
        val digits = bd.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
        val exp = bd.precision() - bd.scale() - 1      // decimal exponent of the first digit
        val sign = if (x < 0) "-" else ""
        return if (exp < -4 || exp >= 16) {
            val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            sign + mant + "e" + (if (exp < 0) "-" else "+") + "%02d".format(kotlin.math.abs(exp))
        } else if (exp < 0) {
            sign + "0." + "0".repeat(-exp - 1) + digits
        } else if (digits.length <= exp + 1) {
            sign + digits + "0".repeat(exp + 1 - digits.length) + ".0"
        } else {
            sign + digits.substring(0, exp + 1) + "." + digits.substring(exp + 1)
        }
    }
}
