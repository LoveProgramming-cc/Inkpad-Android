package io.github.inkpad.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.inkpad.core.Json
import io.github.inkpad.core.obj

// The desktop app's look: Adwaita's colours, the blue of the editor header, and the few controls
// its stylesheet defines (from B:style.css and A:UI/Theme.swift).

class Palette(val dark: Boolean) {
    val header = Color(0xFF37629F)        // the editor's header bar
    val headerInk = Color(0xFF2B5290)     // icons on its white, checked buttons
    val accent = Color(0xFF3584E4)
    val accentText = if (dark) Color(0xFF81D0FF) else Color(0xFF0461BE)
    val danger = if (dark) Color(0xFFFF938C) else Color(0xFFC30000)
    val starOn = Color(0xFFE8563F)
    val mark = Color(0xFFE5484D)
    val ink = if (dark) Color(0xFFFFFFFF) else Color(0xFF1E1E22)
    val viewBg = if (dark) Color(0xFF1D1D20) else Color(0xFFFFFFFF)
    val windowBg = if (dark) Color(0xFF222226) else Color(0xFFF2F2F4)   // a shade under the white cards, so they stand out
    val sidebarBg = if (dark) Color(0xFF2E2E32) else Color(0xFFEBEBED)
    val popoverBg = if (dark) Color(0xFF36363A) else Color(0xFFFFFFFF)
    val cardBg = if (dark) Color(0xFF3F3F44) else Color(0xFFFFFFFF)
    val canvasBg = if (dark) Color(0xFF1F1F22) else Color(0xFFE1E1E2)
    fun ink(alpha: Float) = ink.copy(alpha = alpha)
}

val LocalPalette = staticCompositionLocalOf { Palette(false) }

@Composable
fun InkpadTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalPalette provides Palette(dark), content = content)
}

val palette: Palette @Composable get() = LocalPalette.current

// -- icons: the Lucide paths the other editions use ---------------------------------------------

class IconData(val parts: List<Any>, val fill: Boolean)

object Icons {
    private var icons: Map<String, IconData> = emptyMap()
    private var glyphs: Map<String, IconData> = emptyMap()

    fun load(c: Context) {
        if (icons.isNotEmpty()) return
        val data = Json.parse(c.assets.open("icons.json").readBytes().toString(Charsets.UTF_8)).obj()
        fun read(m: Map<String, Any?>): Map<String, IconData> = m.mapValues { (_, v) ->
            // An icon is {els, fill}; a glyph is just its list of elements.
            val els = if (v is List<*>) v else v.obj()["els"] as List<*>
            IconData(els.map { el ->
                if (el is String) PathParser().parsePathString(el).toPath() else (el as List<*>)
            }, v !is List<*> && v.obj()["fill"] == true)
        }
        icons = read(data["icons"].obj())
        glyphs = read(data["glyphs"].obj())
    }

    operator fun get(name: String) = icons[name] ?: glyphs[name]
    val glyphNames get() = glyphs.keys.toList()
}

/** A Lucide icon: drawn in a 24 × 24 box with a 2-unit line, as the other editions draw it. */
@Composable
fun Icon(name: String, tint: Color = palette.ink, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val data = Icons[name]
    Canvas(modifier.size(size)) {
        if (data == null) return@Canvas
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val line = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (el in data.parts) {
                if (el is Path) {
                    if (data.fill) drawPath(el, tint, style = Fill)
                    drawPath(el, tint, style = line)
                } else {
                    val l = el as List<*>
                    when (l[0]) {
                        "rect" -> {
                            val x = (l[1] as Number).toFloat()
                            val y = (l[2] as Number).toFloat()
                            val w = (l[3] as Number).toFloat()
                            val h = (l[4] as Number).toFloat()
                            val r = (l.getOrNull(5) as? Number)?.toFloat() ?: 0f
                            val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
                            if (data.fill) drawRoundRect(tint, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(w, h), cr, style = Fill)
                            drawRoundRect(tint, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(w, h), cr, style = line)
                        }
                        "circle" -> {
                            val c = androidx.compose.ui.geometry.Offset((l[1] as Number).toFloat(), (l[2] as Number).toFloat())
                            val r = (l[3] as Number).toFloat()
                            if (data.fill) drawCircle(tint, r, c, style = Fill)
                            drawCircle(tint, r, c, style = line)
                        }
                    }
                }
            }
        }
    }
}

// -- floating bars -------------------------------------------------------------------------------

/** The rounded bar that floats over the canvas (.pill). */
@Composable
fun Pill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val p = palette
    Row(
        modifier
            .shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.2f))
            .background(p.popoverBg, RoundedCornerShape(18.dp))
            .border(1.dp, Color.Black.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** A button's look inside a pill: 36 dp square, tinted when checked. */
@Composable
fun PillButton(
    checked: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    round: Boolean = false,
    wide: Boolean = false,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val p = palette
    val shape = RoundedCornerShape(if (round) 17.dp else 12.dp)
    val bg = when {
        tint != null -> tint.copy(alpha = if (pressed) 0.26f else 0.16f)
        checked -> p.ink(0.12f)
        pressed -> p.ink(0.08f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .then(if (wide) Modifier.padding(horizontal = 8.dp).height(36.dp).widthIn(min = 36.dp) else Modifier.size(if (round) 34.dp else 36.dp))
            .then(if (!enabled) Modifier.background(Color.Transparent) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.then(if (!enabled) Modifier.padding(0.dp) else Modifier), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(LocalEnabledAlpha provides if (enabled) 1f else 0.35f) { content() }
        }
    }
}

val LocalEnabledAlpha = staticCompositionLocalOf { 1f }

@Composable
fun PillSeparator() {
    Box(Modifier.padding(horizontal = 7.dp).width(1.dp).height(18.dp).background(palette.ink(0.14f)))
}

/** The little page-and-zoom label in the canvas corner (.status). */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier) {
    val p = palette
    androidx.compose.foundation.text.BasicText(
        text,
        modifier
            .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.1f))
            .background(p.popoverBg, CircleShape)
            .border(1.dp, Color.Black.copy(alpha = 0.06f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        style = TextStyle(fontSize = 13.sp, color = p.ink(0.8f), fontFeatureSettings = "tnum"),
    )
}
