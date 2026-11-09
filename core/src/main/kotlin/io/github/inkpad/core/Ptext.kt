package io.github.inkpad.core

import java.text.BreakIterator

// Text layout (B:ptext.py, Pango there; W:src/core/ptext.js). Wrapping is "word, then character"
// (Pango's WRAP_WORD_CHAR): break between words, and inside a word that is wider than the line.
// Widths come from Platform.metrics: the screen font on the device, a stand-in in tests.

object Ptext {
    private fun pieces(text: String): List<String> {
        val it = BreakIterator.getWordInstance()
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }

    private fun chars(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }

    /** Break text into lines no wider than [wrap] (0: only at newlines). */
    fun layoutLines(text: String, size: Double, wrap: Double, m: TextMetrics = Platform.metrics): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (wrap <= 0) {
                out.add(para)
                continue
            }
            var line = ""
            fun fits(s: String) = m.width(s.trimEnd(), size) <= wrap
            for (piece in pieces(para)) {
                if (fits(line + piece)) {
                    line += piece
                    continue
                }
                if (line.isNotBlank()) out.add(line)
                line = ""
                if (fits(piece)) {
                    line = piece
                    continue
                }
                // A word wider than the line: break it between characters.
                for (ch in chars(piece)) {
                    if (!fits(line + ch) && line != "") {
                        out.add(line)
                        line = ""
                    }
                    line += ch
                }
            }
            out.add(line)
        }
        return out
    }

    /** Width and height of the text laid out with the given wrap width. */
    fun measure(text: String, size: Double, wrap: Double = 0.0, m: TextMetrics = Platform.metrics): Pair<Double, Double> {
        val lines = layoutLines(text, size, wrap, m)
        var w = 0.0
        for (l in lines) w = maxOf(w, m.width(l.trimEnd(), size))
        return Pair(w, lines.size * m.lineHeight(size))
    }
}
