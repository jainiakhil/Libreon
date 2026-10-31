// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.offlineoffice.pdf.FormField
import dev.offlineoffice.pdf.FormType
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

/** A sharp re-render of part of a page at a given scale, positioned in page-box pixels. */
private class Detail(val image: ImageBitmap, val left: Int, val top: Int, val scale: Float)

/**
 * One page: a bounded-resolution base render, a sharp "detail" render of the visible part
 * when zoomed beyond it, search highlights, form editing overlays and pending marks.
 */
@OptIn(FlowPreview::class)
@Composable
fun PdfPageItem(state: PdfViewerState, index: Int) {
    val session = state.session
    val size = session.pageSizes[index]
    val scale = state.scale
    val widthPx = (size.width * scale).roundToInt().coerceAtLeast(1)
    val heightPx = (size.height * scale).roundToInt().coerceAtLeast(1)
    val revision by session.revision.collectAsState()
    val baseWidth = state.baseWidthPx(index)

    val base by produceState<Bitmap?>(null, index, baseWidth, revision) {
        value = runCatching { session.renderPage(index, baseWidth) }.getOrNull()
    }
    val fields by produceState(emptyList<FormField>(), index, revision) {
        if (session.formType != FormType.NONE) value = runCatching { session.fields(index) }.getOrDefault(emptyList())
    }

    // Visible part of this page in page-box pixels, tracked for the detail render.
    var visible by remember { mutableStateOf<IntArray?>(null) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    LaunchedEffect(index, widthPx, revision) {
        detail = null
        if (widthPx <= baseWidth * 1.05f) return@LaunchedEffect
        snapshotFlow { visible }.debounce(150).collectLatest { region ->
            if (region == null || state.gestureScale != 1f) return@collectLatest
            val bmp = runCatching {
                session.renderRegion(index, widthPx, heightPx, region[0], region[1], region[2] - region[0], region[3] - region[1])
            }.getOrNull() ?: return@collectLatest
            detail = Detail(bmp.asImageBitmap(), region[0], region[1], scale)
        }
    }

    val tap by rememberUpdatedState { offset: Offset ->
        state.tapPage(index, offset.x / scale, offset.y / scale, fields)
    }
    val marks by session.marks.collectAsState()
    val matches = state.searchMatches.filter { it.page == index }
    val current = state.searchMatches.getOrNull(state.searchIndex)

    Box(
        Modifier
            .pixelSize(widthPx, heightPx)
            .background(Color.White)
            .semantics { contentDescription = "Page ${index + 1}" }
            .onGloballyPositioned { c ->
                val pos = c.positionInRoot()
                val b = c.boundsInRoot()
                visible = PdfLayoutMath.detailRegion(b.left - pos.x, b.top - pos.y, b.right - pos.x, b.bottom - pos.y, widthPx, heightPx)
            }
            .pointerInput(index) { detectTapGestures(onTap = { tap(it) }) }
            .drawWithContent {
                base?.let {
                    drawImage(it.asImageBitmap(), dstSize = IntSize(widthPx, heightPx), filterQuality = FilterQuality.Low)
                }
                detail?.takeIf { it.scale == scale }?.let {
                    drawImage(it.image, dstOffset = IntOffset(it.left, it.top), filterQuality = FilterQuality.None)
                }
                for (m in matches) {
                    val color = if (m === current) Color(0x99FF8F00) else Color(0x66FFEB3B)
                    for (r in m.rects) {
                        drawRect(color, Offset(r.left * scale, r.top * scale), Size(r.width * scale, r.height * scale))
                    }
                }
                drawContent()
            },
    ) {
        state.editing?.takeIf { it.field.page == index }?.let { FieldEditor(state, it, scale) }
        state.choosing?.takeIf { it.page == index }?.let { ChoiceMenu(state, it, scale) }
        for (mark in marks) {
            if (mark.page == index) MarkBox(state, mark, scale, selected = mark.id == state.selectedMark)
        }
    }
}

/** A text field overlay positioned over the PDF field; commits on Done or focus loss. */
@Composable
private fun FieldEditor(state: PdfViewerState, edit: FieldEdit, scale: Float) {
    val field = edit.field
    val r = PdfLayoutMath.pageToView(field.rect, scale)
    val density = LocalDensity.current
    val heightPx = maxOf(r.height, with(density) { 28.dp.toPx() })
    val fontPx = if (field.multiline) minOf(heightPx * 0.6f, with(density) { 16.dp.toPx() }) else heightPx * 0.55f
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(field) { focus.requestFocus() }
    DisposableEffect(edit) { onDispose { state.commitEdit(edit) } }
    BasicTextField(
        value = edit.text,
        onValueChange = { edit.text = it },
        singleLine = !field.multiline,
        textStyle = TextStyle(fontSize = with(density) { fontPx.toSp() }, color = Color.Black),
        visualTransformation = if (field.password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (field.multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { state.commitEdit(edit) }),
        modifier = Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .pixelSize(r.width.roundToInt().coerceAtLeast(1), heightPx.roundToInt())
            .background(Color(0xFFFFFDE7))
            .border(2.dp, MaterialTheme.colorScheme.primary)
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) state.commitEdit(edit)
                focused = it.isFocused
            },
    )
}

/** Options of a combo or list box, shown as a dropdown anchored at the field. */
@Composable
private fun ChoiceMenu(state: PdfViewerState, field: FormField, scale: Float) {
    val r = PdfLayoutMath.pageToView(field.rect, scale)
    Box(Modifier.offset { IntOffset(r.left.roundToInt(), r.bottom.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = { state.choosing = null }) {
            if (field.options.isEmpty()) {
                DropdownMenuItem(text = { Text("No options") }, onClick = { state.choosing = null })
            }
            field.options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text((if (option.selected) "✓ " else "") + option.label) },
                    onClick = { state.choose(field, i) },
                    modifier = Modifier.semantics {
                        role = if (field.multiSelect) Role.Checkbox else Role.RadioButton
                        stateDescription = if (option.selected) "Selected" else "Not selected"
                    },
                )
            }
            if (field.editableChoice) {
                DropdownMenuItem(
                    text = { Text("Type a value…") },
                    onClick = { state.choosing = null; state.editing = FieldEdit(field) },
                )
            }
        }
    }
}

/** Lays out content at an exact pixel size, independent of density rounding. */
fun Modifier.pixelSize(width: Int, height: Int): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
