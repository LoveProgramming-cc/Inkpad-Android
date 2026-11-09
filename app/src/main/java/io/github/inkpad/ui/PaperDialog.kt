package io.github.inkpad.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.inkpad.core.BOARD_SIZE
import io.github.inkpad.core.COVER_COLORS
import io.github.inkpad.core.COVER_STYLES
import io.github.inkpad.core.Color as Rgb
import io.github.inkpad.core.PAPERS
import io.github.inkpad.core.PAPER_COLORS
import io.github.inkpad.core.Page
import io.github.inkpad.core.SIZES
import io.github.inkpad.core.Template
import io.github.inkpad.core.tr
import io.github.inkpad.render.Env
import io.github.inkpad.render.drawPaper
import io.github.inkpad.render.renderCover
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Choosing paper (B:dialogs.py PaperDialog) and covers (cover_picker).

/** A sample of a kind of paper, w × h dp at most. */
@Composable
fun PaperSample(kind: String, t: Template, board: Boolean, maxW: Int, maxH: Int) {
    val d = LocalDensity.current.density
    val bmp = remember(kind, t.w, t.h, t.color.toList(), board, maxW, maxH) {
        // A whiteboard has no edges: show a patch of its background.
        val page = if (board) Page(maxW * 2.2, maxH * 2.2, kind, color = t.color, board = true) else Page(t.w, t.h, kind, color = t.color)
        val k = min(maxW / page.w, maxH / page.h)
        val w = max(1, (page.w * k * d).roundToInt())
        val h = max(1, (page.h * k * d).roundToInt())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.scale((w / page.w).toFloat(), (h / page.h).toFloat())
        drawPaper(c, Env(0.0, 0.0, page.w / w), page, if (board) doubleArrayOf(0.0, 0.0, page.w, page.h) else null)
        b
    }
    Image(bmp.asImageBitmap(), null, Modifier.size((bmp.width / d).dp, (bmp.height / d).dp).shadow(2.dp, RoundedCornerShape(3.dp)))
}

/**
 * Pick page size, paper colour, orientation and a template. With [cover] not null (as when making
 * a notebook) the dialog also asks for a name and a cover; [board] is the whiteboard variant:
 * background style and colour only. done(template, title, cover), or null when cancelled.
 */
@Composable
fun PaperDialog(template: Template, board: Boolean, heading: String, apply: String, cover: Pair<String, Rgb>? = null, withTitle: Boolean = false,
                done: (Template?, String, Pair<String, Rgb>?) -> Unit) {
    val p = palette
    var landscape by remember { mutableStateOf(template.w > template.h) }
    var size by remember {
        val short = min(template.w, template.h)
        val long = max(template.w, template.h)
        mutableStateOf(SIZES.indexOfFirst { abs(it.second.first - short) < 2 && abs(it.second.second - long) < 2 }.coerceAtLeast(0))
    }
    var color by remember { mutableStateOf(PAPER_COLORS.indexOfFirst { (_, c) -> (0..2).all { abs(c[it] - template.color[it]) < 0.02 } }.coerceAtLeast(0)) }
    var paper by remember { mutableStateOf(template.paper) }
    var title by remember { mutableStateOf("") }
    var chosenCover by remember { mutableStateOf<Pair<String, Rgb>?>(null) }
    fun current(): Template {
        val c = PAPER_COLORS[color].second
        if (board) return Template(BOARD_SIZE.first, BOARD_SIZE.second, paper, c)
        var (w, h) = SIZES[size].second
        if (landscape) w = h.also { h = w }
        return Template(w, h, paper, c)
    }
    Dialog({ done(null, "", null) }, DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val narrow = maxWidth < 700.dp
            Column(Modifier.widthIn(max = if (withTitle) 880.dp else 660.dp).heightIn(max = 640.dp).shadow(30.dp, RoundedCornerShape(15.dp)).background(p.windowBg, RoundedCornerShape(15.dp))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialogButton(tr("取消")) { done(null, "", null) }
                    Spacer(Modifier.weight(1f))
                    Label(heading, 16f, FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    DialogButton(apply, suggested = true) { done(current(), title.trim(), chosenCover) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink(0.1f)))
                val side: @Composable () -> Unit = {
                    Column(Modifier.then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(250.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label(tr("标题"), 14f, FontWeight.Bold)
                        Field(title, { title = it }, if (board) tr("未命名的白板") else tr("无标题的笔记本"), focus = false)
                        if (!board) {
                            Label(tr("封面"), 14f, FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            CoverPicker(chosenCover, title.ifBlank { tr("无标题的笔记本") }) { chosenCover = it }
                        }
                        if (!narrow) {
                            Label(if (board) tr("背景") else tr("纸张样式"), 14f, FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            Box(Modifier.align(Alignment.CenterHorizontally)) { PaperSample(paper, current(), board, 150, 200) }
                        }
                    }
                }
                val choices: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth()) {
                        FlowRow(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!board) Segmented(SIZES.map { it.first }, size) { size = it }
                            Segmented(PAPER_COLORS.map { tr(it.first) }, color) { color = it }
                            if (!board) Segmented(listOf(tr("竖向"), tr("横向")), if (landscape) 1 else 0) { landscape = it == 1 }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink(0.1f)))
                        val groups = if (board) listOf(tr("背景样式") to PAPERS[0].second.filter { it.first != "wide" }) else PAPERS.map { tr(it.first) to it.second }
                        val t = current()
                        for ((group, kinds) in groups) {
                            Label(group, 14f, FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                            run {
                                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for ((kind, name) in kinds) {
                                        val on = kind == paper
                                        Column(Modifier.width(110.dp).clip(RoundedCornerShape(12.dp)).background(if (on) p.accent.copy(alpha = 0.16f) else Color.Transparent)
                                            .border(if (on) 2.dp else 0.dp, if (on) p.accent else Color.Transparent, RoundedCornerShape(12.dp))
                                            .clickable { paper = kind }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(Modifier.height(136.dp), contentAlignment = Alignment.BottomCenter) { PaperSample(kind, t, board, 96, 128) }
                                            Label(tr(name), 13f)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (narrow || !withTitle) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (withTitle) side()
                        choices()
                    }
                } else {
                    Row {
                        Box(Modifier.verticalScroll(rememberScrollState())) { side() }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(p.ink(0.1f)))
                        Box(Modifier.verticalScroll(rememberScrollState())) { choices() }
                    }
                }
            }
        }
    }
}

/** A row of linked toggle buttons. */
@Composable
fun Segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val p = palette
    Row(Modifier.background(p.ink(0.06f), RoundedCornerShape(10.dp)).padding(3.dp)) {
        labels.forEachIndexed { i, t ->
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (i == selected) p.cardBg else Color.Transparent).clickable { onPick(i) }
                .padding(horizontal = 10.dp, vertical = 6.dp)) { Label(t, 13f) }
        }
    }
}

/** No cover, or one of the designed covers in one of the colours. */
@Composable
fun CoverPicker(chosen: Pair<String, Rgb>?, title: String, onPick: (Pair<String, Rgb>?) -> Unit) {
    val p = palette
    var style by remember { mutableStateOf(chosen?.first ?: "simple") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val none = chosen == null
            Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).border(if (none) 2.dp else 1.dp, if (none) p.accent else p.ink(0.2f), RoundedCornerShape(4.dp))
                .clickable { onPick(null) }, contentAlignment = Alignment.Center) { Label(tr("无"), 11f, color = Color.Black) }
            for (s in COVER_STYLES) {
                val on = chosen?.first == s
                val bmp = remember(s) { renderCover(s, COVER_COLORS[2], "", 72, 96) }
                Image(bmp.asImageBitmap(), null, Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(4.dp))
                    .border(if (on) 2.dp else 0.dp, if (on) p.accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable {
                        style = s
                        onPick(Pair(s, chosen?.second ?: COVER_COLORS[2]))
                    })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in COVER_COLORS) {
                val on = chosen != null && (0..2).all { abs(chosen.second[it] - c[it]) < 0.01 }
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(io.github.inkpad.render.argb(c)))
                    .border(if (on) 2.dp else 1.dp, if (on) p.accent else p.ink(0.2f), RoundedCornerShape(12.dp))
                    .clickable { onPick(Pair(chosen?.first ?: style, c)) })
            }
        }
        if (chosen != null) {
            val bmp = remember(chosen.first, chosen.second.toList(), title) { renderCover(chosen.first, chosen.second, title, 150, 200) }
            Image(bmp.asImageBitmap(), null, Modifier.size(75.dp, 100.dp).shadow(4.dp, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
fun CoverDialog(spec: Pair<String, Rgb>?, title: String, done: (Pair<String, Rgb>?, Boolean) -> Unit) {
    var chosen by remember { mutableStateOf(spec) }
    DialogCard({ done(null, false) }) {
        Label(tr("笔记本封面"), 17f, FontWeight.Bold)
        Label(tr("封面显示在文稿库里。"), 14f, color = palette.ink(0.7f))
        CoverPicker(chosen, title) { chosen = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(tr("取消")) { done(null, false) }
            Spacer(Modifier.width(8.dp))
            DialogButton(tr("应用"), suggested = true) { done(chosen, true) }
        }
    }
}
