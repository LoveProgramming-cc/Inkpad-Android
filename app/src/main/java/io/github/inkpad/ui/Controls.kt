package io.github.inkpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.inkpad.core.tr

/** Text in the interface's own style. */
@Composable
fun Label(text: String, size: Float = 15f, weight: FontWeight = FontWeight.Normal, color: Color = palette.ink, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier, align: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Unspecified) {
    BasicText(text, modifier, style = TextStyle(fontSize = size.sp, fontWeight = weight, color = color, textAlign = align), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Places a popover below its anchor, or above when there is no room, and keeps it on screen. */
private class Below(val gap: Int, val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // Placed by its card: the content is the card with a clear margin all round.
        val w = popupContentSize.width - 2 * margin
        val h = popupContentSize.height - 2 * margin
        var x = anchorBounds.center.x - w / 2
        x = x.coerceIn(8, maxOf(8, windowSize.width - w - 8))
        var y = anchorBounds.bottom + gap
        if (y + h > windowSize.height - 8 && anchorBounds.top - gap - h > 8) y = anchorBounds.top - gap - h
        return IntOffset(x - margin, y.coerceAtLeast(8) - margin)
    }
}

/** A popover card: rounded, with a shadow, scrolling when it is taller than the screen allows. */
@Composable
fun PopoverCard(content: @Composable ColumnScope.() -> Unit) {
    val p = palette
    // A popup is a window of its own, as big as what it holds: the margin gives the shadow room
    // to fade out, where it would otherwise be cut off at the window's edge in a hard band.
    Box(Modifier.padding(POPOVER_MARGIN).appear(androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f))) {
        Column(
            Modifier
                .shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.22f))
                .background(p.popoverBg, RoundedCornerShape(16.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp, horizontal = 6.dp),
            content = content,
        )
    }
}

/** The clear margin round a popover's card (see [PopoverCard]). */
val POPOVER_MARGIN = 14.dp

/** A button that opens a popover; the content gets a function that closes it. */
@Composable
fun PopButton(
    label: @Composable (open: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        label { open = true }
        if (open) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Popup(popupPositionProvider = Below(with(density) { 6.dp.roundToPx() }, with(density) { POPOVER_MARGIN.roundToPx() }), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                PopoverCard { content { open = false } }
            }
        }
    }
}

/** A popover shown at a point of the screen (a long press, the selection). */
@Composable
fun PopAt(x: Int, y: Int, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val m = with(androidx.compose.ui.platform.LocalDensity.current) { POPOVER_MARGIN.roundToPx() }
    Popup(popupPositionProvider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val w = popupContentSize.width - 2 * m
            val h = popupContentSize.height - 2 * m
            return IntOffset((x - w / 2).coerceIn(8, maxOf(8, windowSize.width - w - 8)) - m,
                (if (y + h > windowSize.height - 8) y - h else y).coerceAtLeast(8) - m)
        }
    }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        PopoverCard(content)
    }
}

class MenuItem(val text: String, val icon: String? = null, val danger: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)

/** A choice among a few, as GNOME's combo rows and iOS's menu pickers: the current one with a
 *  ⌄, and a menu of all of them with a tick by the current one. */
@Composable
fun DropDown(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val p = palette
    PopButton({ open ->
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = open).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Label(labels.getOrElse(selected) { "" }, 15f, color = p.ink(0.75f), maxLines = 1)
            Icon("chevrons_up_down", p.ink(0.5f), 14.dp)
        }
    }) { close ->
        Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 150.dp, max = 320.dp)) {
            labels.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable { close(); onPick(i) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(16.dp)) { if (i == selected) Icon("check", p.accent, 16.dp) }
                    Label(t, maxLines = 1)
                }
            }
        }
    }
}

/** A menu's rows; null is a separator. */
@Composable
fun MenuList(items: List<MenuItem?>, close: () -> Unit, fill: Boolean = false) {
    val p = palette
    // As wide as the longest row, as the other editions' menus are; not a fixed broad card. In a
    // panel ([fill]) the rows and the lines between them go the panel's whole width.
    Column(if (fill) Modifier.fillMaxWidth() else Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 200.dp, max = 320.dp)) {
        for (item in items) {
            if (item == null) {
                Box(Modifier.padding(vertical = 4.dp, horizontal = 8.dp).fillMaxWidth().height(1.dp).background(p.ink(0.1f)))
                continue
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(enabled = item.enabled) {
                        close()
                        item.action()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val c = if (item.danger) p.danger else p.ink(if (item.enabled) 1f else 0.35f)
                if (item.icon != null) Icon(item.icon, c, 20.dp)
                Label(item.text, 16f, color = c)
            }
        }
    }
}

/** A dialog card in the middle of the window, as the desktop app's. */
@Composable
fun DialogCard(onDismiss: () -> Unit, width: Int = 420, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val p = palette
        Column(
            Modifier
                .padding(16.dp)
                .appear(androidx.compose.ui.graphics.TransformOrigin.Center)
                .widthIn(max = width.dp)
                .shadow(30.dp, RoundedCornerShape(15.dp))
                .background(p.windowBg, RoundedCornerShape(15.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A suggested (accent) or plain button in a dialog. */
@Composable
fun DialogButton(text: String, suggested: Boolean = false, danger: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = palette
    val bg = when {
        suggested -> p.accent
        danger -> p.danger
        else -> p.ink(0.1f)
    }
    // A:Sheets.swift DialogButtonStyle: 34 high, 9 round, bold only when it is the one to press.
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, weight = if (suggested || danger) FontWeight.SemiBold else FontWeight.Normal, color = if (suggested || danger) Color.White else p.ink)
    }
}

/** A one-line (or several-line) text field in the interface's style. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String = "", singleLine: Boolean = true, minHeight: Int = 0, focus: Boolean = false, modifier: Modifier = Modifier) {
    val p = palette
    val fr = remember { FocusRequester() }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(40, minHeight).dp)
            .background(p.ink(0.06f), RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Label(placeholder, color = p.ink(0.4f))
        BasicTextField(value, onChange, Modifier.fillMaxWidth().focusRequester(fr), singleLine = singleLine,
            textStyle = TextStyle(fontSize = 15.sp, color = p.ink), cursorBrush = SolidColor(p.accent))
    }
    if (focus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

/** Ask for a line of text. */
@Composable
fun Prompt(title: String, initial: String, ok: String = tr("确定"), message: String? = null, multiline: Boolean = false, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    DialogCard({ onDone(null) }) {
        Label(title, 17f, FontWeight.Bold)
        if (message != null) Label(message, 14f, color = palette.ink(0.7f))
        Field(text, { text = it }, singleLine = !multiline, minHeight = if (multiline) 110 else 0, focus = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(tr("取消")) { onDone(null) }
            Spacer(Modifier.width(8.dp))
            DialogButton(ok, suggested = true) { onDone(text) }
        }
    }
}

/** Ask to confirm something. */
@Composable
fun Confirm(title: String, message: String, ok: String, danger: Boolean = false, onDone: (Boolean) -> Unit) {
    DialogCard({ onDone(false) }) {
        Label(title, 17f, FontWeight.Bold)
        Label(message, 14f, color = palette.ink(0.75f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(tr("取消")) { onDone(false) }
            Spacer(Modifier.width(8.dp))
            DialogButton(ok, suggested = !danger, danger = danger) { onDone(true) }
        }
    }
}

/** Adwaita's toast: a dark rounded slab near the bottom edge, with at most one button. */
@Composable
fun Toast(text: String, button: String?, onButton: () -> Unit) {
    Row(
        Modifier
            .shadow(8.dp, CircleShape)
            .background(Color(0xF0292929), CircleShape)
            .padding(horizontal = 18.dp, vertical = if (button == null) 11.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Label(text, 15f, color = Color.White)
        if (button != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onButton)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Label(button, 15f, FontWeight.SemiBold, Color.White) }
        }
    }
}

/** A switch in the desktop app's look. */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val p = palette
    // The knob slides across and the track's colour follows, as GNOME's and iOS's switches do.
    val spec = androidx.compose.animation.core.tween<Float>(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val t by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, spec, label = "switch")
    val track = androidx.compose.ui.graphics.lerp(p.ink(0.2f), p.accent, t)
    Box(
        Modifier
            .size(51.dp, 31.dp)   // the size of iOS's switch
            .clip(CircleShape)
            .background(track)
            .clickable { onChange(!on) }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = 20.dp * t).size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** A row of a settings list: a title, an optional subtitle, and a control at the end. */
@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, end: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(title)
            if (subtitle != null) Label(subtitle, 13f, color = palette.ink(0.6f))
        }
        Spacer(Modifier.width(12.dp))
        end()
    }
}

/** A titled group of rows on a card. */
@Composable
fun SettingGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (title != null) Label(title, 14f, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(1.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
                .background(p.cardBg, RoundedCornerShape(12.dp))
                .border(1.dp, p.ink(0.07f), RoundedCornerShape(12.dp)),
            content = content,
        )
    }
}


/** Grows in from [origin] and fades in, as iOS's menus and alerts do, instead of being there at
 *  once: a quick spring that settles without a wobble. */
@Composable
fun Modifier.appear(origin: androidx.compose.ui.graphics.TransformOrigin): Modifier {
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        t.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 900f))
    }
    return this.graphicsLayer {
        val v = t.value
        alpha = v.coerceIn(0f, 1f)
        val k = 0.9f + 0.1f * v
        scaleX = k
        scaleY = k
        transformOrigin = origin
    }
}


/** Linked buttons, one of them chosen (A:Theme.swift Linked): equal segments on a faint tint, the
 *  chosen one tinted more, hairlines between them. Seen the same in light and dark. */
@Composable
fun <T> Linked(entries: List<Pair<T, String>>, value: T, modifier: Modifier = Modifier, fill: Boolean = true, onPick: (T) -> Unit) {
    val p = palette
    Row(modifier.clip(RoundedCornerShape(9.dp)).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        entries.forEachIndexed { i, (v, text) ->
            val on = v == value
            Box(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .background(p.ink(if (on) 0.2f else 0.08f))
                    .clickable { onPick(v) }
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Label(text, 14f, maxLines = 1) }
            if (i < entries.size - 1) Box(Modifier.width(1.dp).fillMaxHeight().background(p.ink(0.12f)))
        }
    }
}
