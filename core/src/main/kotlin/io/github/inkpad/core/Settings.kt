package io.github.inkpad.core

import java.io.File

// Tool and app preferences (B:settings.py; W:src/core/settings.js): a JSON map with the same keys
// and defaults as the other editions, kept in the app's own settings.json.

object Settings {
    private fun d(vararg v: Double) = v.toList()

    val DEFAULTS: Map<String, Any?> = linkedMapOf(
        "pen_type" to "fountain",
        // Per pen: three width presets (points), the active preset, pressure sensitivity and
        // stabilisation in percent.
        "pens" to linkedMapOf(
            "fountain" to linkedMapOf("widths" to d(0.9, 1.6, 2.8), "slot" to 1L, "sens" to 50L, "stab" to 0L, "dash" to 0L),
            "ball" to linkedMapOf("widths" to d(0.7, 1.2, 2.0), "slot" to 1L, "sens" to 50L, "stab" to 0L, "dash" to 0L),
            "brush" to linkedMapOf("widths" to d(1.4, 2.4, 3.8), "slot" to 1L, "sens" to 50L, "stab" to 0L, "dash" to 0L),
        ),
        "pen_colors" to listOf(d(0.29, 0.62, 0.96), d(0.66, 0.11, 0.94), d(0.94, 0.24, 0.24), d(0.49, 0.76, 0.19), d(0.0, 0.0, 0.0), d(1.0, 0.6, 0.12)),
        "pen_color" to 4L,
        "hl_colors" to listOf(d(1.0, 0.88, 0.3), d(0.56, 0.89, 0.41), d(1.0, 0.54, 0.82), d(0.49, 0.77, 1.0), d(1.0, 0.7, 0.28)),
        "hl_color" to 0L,
        "hl_w" to 1L,
        "hl_widths" to d(8.0, 14.0, 22.0),
        "laser_mode" to "trail",
        "palm_ms" to 80L,
        "input_log" to false,
        "last_folder" to "",
        "views" to linkedMapOf<String, Any?>(),
        "note_color" to 0L,
        "tape_color" to 0L,
        "tape_w" to 1L,
        "mouse_hand" to true,
        "paging" to "vertical",
        "scrollbars" to true,
        "sync_every" to 30L,
        "max_zoom" to 6.0,
        "hidden_tools" to listOf<Any?>(),
        "crosshair" to true,
        "laser_color" to 0L,
        "eraser_size" to 1L,
        "eraser_mode" to "standard",
        "erase_filter" to listOf("pen", "hl", "shape"),
        "eraser_auto" to false,
        "lasso_rect" to false,
        "lasso_filter" to listOf("ink", "image", "shape", "text"),
        "shape_kind" to "line",
        "text_size" to 1L,
        "hold_shape" to true,
        "scribble_erase" to true,
        "scribble_loose" to false,   // the earlier rule (B:geom.py _loose)
        "scribble_toast" to true,    // after scribbling something out, say how much, with Undo
        "finger_draw" to false,
        "template" to linkedMapOf("w" to 595.0, "h" to 842.0, "paper" to "blank", "color" to d(1.0, 1.0, 1.0)),
        "sort" to "date",
        "theme" to "system",
        "language" to "auto",
        "add_pos" to "after",
        // This edition's own: whether the stylus button erases while held.
        "pen_button_erases" to true,
    )

    /** Python's type(a) is type(b) for JSON values (numbers of either kind count as one). */
    private fun sameType(a: Any?, b: Any?): Boolean = when {
        a is List<*> || b is List<*> -> a is List<*> && b is List<*>
        a is Map<*, *> || b is Map<*, *> -> a is Map<*, *> && b is Map<*, *>
        a == null || b == null -> a == b
        a is Number && b is Number -> true
        else -> a::class == b::class
    }

    /** The defaults, with whatever was saved laid over them (values of the wrong type are ignored). */
    fun fromSaved(saved: Map<String, Any?>?): MutableMap<String, Any?> {
        val st = mapOfJson(deepCopy(DEFAULTS))
        if (saved == null) return st
        for ((key, value) in saved) {
            if (key == "pens" && value is Map<*, *>) {
                val pens = mapOfJson(st["pens"])
                for ((name, pen) in value) {
                    if (name in pens && pen is Map<*, *>) mapOfJson(pens[name]).putAll(mapOfJson(deepCopy(pen)))
                }
            } else if (key in st && sameType(value, st[key])) {
                st[key] = deepCopy(value)
            }
        }
        return st
    }

    fun load(f: File) = fromSaved(if (f.exists()) loadJson(f) else null)
    fun save(f: File, st: Map<String, Any?>) = saveJson(f, st)
}

/** Typed reads of a settings map. */
fun Map<String, Any?>.str(key: String) = this[key] as? String ?: Settings.DEFAULTS[key] as String
fun Map<String, Any?>.bool(key: String) = this[key] as? Boolean ?: Settings.DEFAULTS[key] as Boolean
fun Map<String, Any?>.int(key: String) = (this[key] as? Number ?: Settings.DEFAULTS[key] as Number).toInt()
fun Map<String, Any?>.dbl(key: String) = (this[key] as? Number ?: Settings.DEFAULTS[key] as Number).toDouble()
fun Map<String, Any?>.strings(key: String) = (this[key] as? List<*>)?.map { it as String } ?: emptyList()
fun Map<String, Any?>.colors(key: String): List<Color> = (this[key] as? List<*>)?.map { it.point() } ?: emptyList()
fun Map<String, Any?>.sub(key: String): MutableMap<String, Any?> = mapOfJson(this[key])
