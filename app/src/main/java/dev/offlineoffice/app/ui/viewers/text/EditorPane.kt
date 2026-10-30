// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.text

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.session.TextSession
import kotlinx.coroutines.flow.first

/** Scroll and layout handles of the editor, so find and go-to-line can reveal an offset. */
@Stable
class EditorController {
    val vScroll = ScrollState(0)
    val hScroll = ScrollState(0)
    internal var layout: () -> TextLayoutResult? = { null }
    internal var viewportHeight = 0
    internal var viewportWidth = 0
    internal var paddingPx = 0f
    internal var wrap = true

    /** Selects [start, end) and scrolls it into view (the editor need not be focused). */
    suspend fun reveal(session: TextSession, start: Int, end: Int = start) {
        val length = session.state.text.length
        session.state.edit { selection = TextRange(start.coerceIn(0, length), end.coerceIn(0, length)) }
        val result = layout() ?: return
        val offset = start.coerceIn(0, result.layoutInput.text.length)
        val line = result.getLineForOffset(offset)
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        val visible = (viewportHeight - 2 * paddingPx).coerceAtLeast(1f)
        if (top < vScroll.value || bottom > vScroll.value + visible) {
            vScroll.animateScrollTo((top - visible / 3).toInt().coerceAtLeast(0))
        }
        if (!wrap) {
            val x = result.getHorizontalPosition(offset, true)
            val width = (viewportWidth - 2 * paddingPx).coerceAtLeast(1f)
            if (x < hScroll.value || x > hScroll.value + width) {
                hScroll.animateScrollTo((x - width / 3).toInt().coerceAtLeast(0))
            }
        }
    }
}

/** The editable text area, with find matches highlighted behind the text. */
@Composable
fun EditorPane(session: TextSession, controller: EditorController, find: FindState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val matchColor = colors.tertiary.copy(alpha = 0.25f)
    val currentColor = colors.tertiary.copy(alpha = 0.55f)
    val pad = 12.dp
    val padPx = with(LocalDensity.current) { pad.toPx() }
    controller.paddingPx = padPx
    controller.wrap = session.wrap

    // Restore the saved scroll position once the text is laid out, then keep reporting it.
    LaunchedEffect(session) {
        if (session.editorScroll > 0) {
            snapshotFlow { controller.vScroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
            controller.vScroll.scrollTo(session.editorScroll)
        }
        snapshotFlow { controller.vScroll.value }.collect { session.editorScroll = it }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val fieldModifier = if (session.wrap) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxHeight().horizontalScroll(controller.hScroll).widthIn(min = maxWidth)
        }
        BasicTextField(
            state = session.state,
            modifier = fieldModifier
                .onSizeChanged { controller.viewportHeight = it.height; if (session.wrap) controller.viewportWidth = it.width }
                .drawBehind {
                    val matches = find.matches
                    if (!find.visible || matches.isEmpty() || find.query.isEmpty()) return@drawBehind
                    val result = controller.layout() ?: return@drawBehind
                    val scroll = controller.vScroll.value.toFloat()
                    val textLength = result.layoutInput.text.length
                    val len = find.query.length
                    clipRect(top = padPx, bottom = size.height - padPx) {
                        translate(padPx, padPx - scroll) {
                            val firstLine = result.getLineForVerticalPosition(scroll)
                            val lastLine = result.getLineForVerticalPosition(scroll + size.height)
                            val from = result.getLineStart(firstLine)
                            val to = result.getLineEnd(lastLine)
                            var i = matches.binarySearch(from).let { if (it < 0) -it - 1 else it }
                            while (i < matches.size && matches[i] <= to) {
                                val s = matches[i]
                                if (s + len <= textLength) {
                                    drawPath(result.getPathForRange(s, s + len), if (i == find.current) currentColor else matchColor)
                                }
                                i++
                            }
                        }
                    }
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = colors.onSurface,
                fontFamily = if (session.monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = SolidColor(colors.primary),
            scrollState = controller.vScroll,
            onTextLayout = { getResult -> controller.layout = getResult },
            decorator = TextFieldDecorator { inner -> Box(Modifier.padding(pad)) { inner() } },
        )
        if (!session.wrap) controller.viewportWidth = constraints.maxWidth
    }
}
