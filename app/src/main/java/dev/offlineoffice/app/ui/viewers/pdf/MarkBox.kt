// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.session.MarkGeometry
import dev.offlineoffice.app.session.PdfMark
import kotlin.math.roundToInt

private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

/**
 * A pending mark drawn over its page. When selected it can be dragged, resized from the
 * corner handle, edited (text), deleted, or committed with Done.
 */
@Composable
fun MarkBox(state: PdfViewerState, mark: PdfMark, scale: Float, selected: Boolean) {
    val current by rememberUpdatedState(mark)
    val size = state.session.pageSizes[mark.page]
    val r = PdfLayoutMath.pageToView(mark.rect, scale)
    val density = LocalDensity.current
    val editingText = selected && mark is PdfMark.Text

    Box(
        Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .pixelSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1))
            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .pointerInput(mark.id) { detectTapGestures(onTap = { state.selectedMark = current.id }) }
            .then(
                if (editingText) Modifier else Modifier.pointerInput(mark.id) {
                    detectDragGestures(onDragStart = { state.selectedMark = current.id }) { change, drag ->
                        change.consume()
                        val m = current
                        state.updateMark(m.moved(MarkGeometry.offset(m.rect, drag.x / scale, drag.y / scale, size.width, size.height)))
                    }
                },
            ),
    ) {
        when (mark) {
            is PdfMark.Text -> if (editingText) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(mark.id) { focus.requestFocus() }
                val fontPx = MarkGeometry.fontSizeFor(mark.rect) * scale
                BasicTextField(
                    value = mark.text,
                    onValueChange = { text ->
                        val m = current as PdfMark.Text
                        val w = PdfViewerState.textWidthPts(text, MarkGeometry.fontSizeFor(m.rect))
                        state.updateMark(m.copy(text = text, rect = m.rect.copy(right = (m.rect.left + w).coerceAtMost(size.width))))
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = with(density) { fontPx.toSp() },
                        lineHeight = with(density) { (fontPx * MarkGeometry.TEXT_LINE_FACTOR).toSp() },
                        fontFamily = FontFamily.SansSerif,
                        color = Color(mark.argb),
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.commitMark(current) }),
                    modifier = Modifier.fillMaxSize().background(Color(0x22448AFF)).focusRequester(focus),
                )
            } else {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = mark.text.ifEmpty { "Text mark" } }) {
                    textPaint.color = mark.argb
                    textPaint.textSize = MarkGeometry.fontSizeFor(mark.rect) * scale
                    val baseline = (MarkGeometry.baselineFor(mark.rect) - mark.rect.top) * scale
                    drawIntoCanvas { it.nativeCanvas.drawText(mark.text, 0f, baseline, textPaint) }
                }
            }
            is PdfMark.Symbol -> Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = if (mark.cross) "Cross mark" else "Check mark"
            }) {
                val pts = MarkGeometry.symbolPoints(mark.rect, mark.cross)
                val stroke = MarkGeometry.symbolStroke(mark.rect) * scale
                var i = 0
                while (i + 3 < pts.size) {
                    if (!pts[i].isNaN() && !pts[i + 2].isNaN()) {
                        drawLine(
                            Color(mark.argb),
                            Offset((pts[i] - mark.rect.left) * scale, (pts[i + 1] - mark.rect.top) * scale),
                            Offset((pts[i + 2] - mark.rect.left) * scale, (pts[i + 3] - mark.rect.top) * scale),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                    i += 2
                }
            }
            is PdfMark.Signature -> Image(mark.image.asImageBitmap(), "Signature", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }

    if (!selected) return

    // Resize handle on the bottom-right corner.
    val handle = with(density) { 22.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((r.right - handle / 2).roundToInt(), (r.bottom - handle / 2).roundToInt()) }
            .size(22.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(mark.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val m = current
                    val resized = when (m) {
                        is PdfMark.Text -> {
                            val grown = MarkGeometry.resize(m.rect, 0f, drag.y / scale, aspect = null)
                            val w = PdfViewerState.textWidthPts(m.text, MarkGeometry.fontSizeFor(grown))
                            grown.copy(right = grown.left + w)
                        }
                        is PdfMark.Symbol -> MarkGeometry.resize(m.rect, drag.x / scale, drag.y / scale, aspect = 1f)
                        is PdfMark.Signature -> MarkGeometry.resize(m.rect, drag.x / scale, drag.y / scale, aspect = m.rect.width / m.rect.height)
                    }
                    if (resized.right <= size.width && resized.bottom <= size.height) state.updateMark(m.moved(resized))
                }
            },
    )

    // Move handle for text marks (their body is the text editor).
    if (mark is PdfMark.Text) {
        Box(
            Modifier
                .offset { IntOffset((r.left - handle / 2).roundToInt(), (r.top - handle / 2).roundToInt()) }
                .size(22.dp)
                .background(MaterialTheme.colorScheme.secondary, CircleShape)
                .pointerInput(mark.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val m = current
                        state.updateMark(m.moved(MarkGeometry.offset(m.rect, drag.x / scale, drag.y / scale, size.width, size.height)))
                    }
                },
        )
    }

    // Action bar above the mark (below it near the top of the page).
    val barHeight = with(density) { 48.dp.toPx() }
    val barY = if (r.top > barHeight + handle) r.top - barHeight - handle / 2 else r.bottom + handle / 2
    Surface(
        Modifier.offset { IntOffset(r.left.roundToInt(), barY.roundToInt()) },
        shape = MaterialTheme.shapes.small,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row {
            TextButton(onClick = { state.commitMark(current) }) { Text("Done") }
            TextButton(onClick = { state.deleteMark(current) }) { Text("Delete") }
            if (mark is PdfMark.Signature) {
                TextButton(
                    onClick = { state.updateMark(mark.copy(asAnnotation = !mark.asAnnotation)) },
                    modifier = Modifier.semantics {
                        role = Role.Checkbox
                        stateDescription = if (mark.asAnnotation) "Checked" else "Not checked"
                    },
                ) {
                    Text(if (mark.asAnnotation) "☑ Keep as annotation" else "☐ Keep as annotation")
                }
            }
        }
    }
}
