// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.offlineoffice.app.session.MarkGeometry
import dev.offlineoffice.app.session.PdfMark
import dev.offlineoffice.app.session.PdfSession
import dev.offlineoffice.pdf.AnnotType
import dev.offlineoffice.pdf.FormField
import dev.offlineoffice.pdf.Highlight
import dev.offlineoffice.pdf.PageRect
import dev.offlineoffice.pdf.SearchMatch
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Flat-form tools that place a mark with the next tap on a page. */
enum class PlaceTool(val hint: String) {
    TEXT("Tap the page where the text should go"),
    CHECK("Tap the page to place a check mark"),
    CROSS("Tap the page to place a cross mark"),
    NOTE("Tap the page where the note should go"),
}

/** Annotation tools that draw with a finger on a page. */
enum class DrawTool(val hint: String) {
    INK("Draw with your finger. Pinch to zoom."),
    RECT("Drag to draw a rectangle"),
    ELLIPSE("Drag to draw an ellipse"),
}

/** Strokes being drawn on one page, committed together as one drawing. */
class InkDraft(val page: Int) {
    val strokes = mutableStateListOf<FloatArray>()
    var current by mutableStateOf<FloatArray?>(null)
}

/** A rectangle or ellipse being dragged out on [page], in display points. */
data class ShapeDraft(val page: Int, val startX: Float, val startY: Float, val endX: Float, val endY: Float) {
    val rect: PageRect get() = PageRect(minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY))
}

/** Where a new note goes, while its text is typed. */
data class NoteDraft(val page: Int, val x: Float, val y: Float)

/** A text field being edited in the overlay. */
class FieldEdit(val field: FormField) {
    var text by mutableStateOf(field.value)
    var committed = false
}

/** Text selected on one page: characters [start, end), their rectangles and the text itself. */
data class PdfTextSelection(val page: Int, val start: Int, val end: Int, val rects: List<PageRect>, val text: String)

/** Where a picked signature goes: a signature form field, or the visible part of a page. */
data class SignatureTarget(val page: Int, val box: PageRect?)

/**
 * UI state of one PDF viewer: zoom, scroll, tools, form editing, marks and search.
 * Lives as long as the viewer's composition; persistent bits are mirrored into [session].
 */
class PdfViewerState(
    val session: PdfSession,
    val listState: LazyListState,
    val hScroll: ScrollState,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    var zoom by mutableFloatStateOf(session.initialZoom)
        private set

    /** Extra scale applied visually during a pinch, committed into [zoom] when it ends. */
    var gestureScale by mutableFloatStateOf(1f)
    var gestureFocal by mutableStateOf(Offset.Zero)

    var viewport by mutableStateOf(IntSize.Zero)
    var paddingPx = 0f

    var tool by mutableStateOf<PlaceTool?>(null)
    var selectedMark by mutableStateOf<Long?>(null)
    var editing by mutableStateOf<FieldEdit?>(null)
    var choosing by mutableStateOf<FormField?>(null)
    var signatureTarget by mutableStateOf<SignatureTarget?>(null)

    // drawing, shapes and notes
    var drawTool by mutableStateOf<DrawTool?>(null)
    var inkColor by mutableIntStateOf(INK_COLORS[0])
    var ink by mutableStateOf<InkDraft?>(null)
        private set
    var shape by mutableStateOf<ShapeDraft?>(null)
        private set
    var noteDraft by mutableStateOf<NoteDraft?>(null)

    /** The text of the selected note, once read. */
    var selectedNoteText by mutableStateOf<String?>(null)
        private set

    // text selection and highlights
    var selection by mutableStateOf<PdfTextSelection?>(null)
        private set
    var selectedHighlight by mutableStateOf<Highlight?>(null)
    private var anchor: IntRange? = null
    private var selectJob: Job? = null

    // search
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    val searchMatches = mutableStateListOf<SearchMatch>()
    var searchIndex by mutableIntStateOf(-1)
    var searching by mutableStateOf(false)
    private var searchJob: Job? = null

    private val maxPageWidth: Float get() = session.pageSizes.maxOfOrNull { it.width } ?: 612f
    private val maxPageHeight: Float get() = session.pageSizes.maxOfOrNull { it.height } ?: 792f

    /** Pixels per point at true size (100%: a point is 1/72 inch on screen). Set by the viewer. */
    var actualScale = 1f

    /** Pixels per point at zoom 1 (the widest page fits the screen width). */
    val fitScale: Float
        get() = PdfLayoutMath.fitScale(viewport.width, maxPageWidth, paddingPx)

    /** Pixels per point at the current zoom. */
    val scale: Float get() = fitScale * zoom

    /** The zoom as a percentage of the page's true size, as PDF viewers show it. */
    val percent: Int get() = (scale / actualScale * 100).roundToInt()

    /** Zoom that fits the tallest page on screen. */
    val fitPageZoom: Float
        get() = ((viewport.height - 2 * paddingPx) / (maxPageHeight * fitScale)).coerceAtMost(1f).coerceAtLeast(0.01f)

    val minZoom: Float get() = minOf(PdfSession.MIN_ZOOM, fitPageZoom)

    /** Up to [MAX_PERCENT] of true size, while a page stays within what can be laid out. */
    val maxZoom: Float
        get() {
            val fit = fitScale.coerceAtLeast(0.001f)
            val bySize = MAX_PAGE_PX / (maxOf(maxPageWidth, maxPageHeight) * fit)
            return minOf(MAX_PERCENT / 100f * actualScale / fit, bySize, PdfSession.MAX_ZOOM).coerceAtLeast(1f)
        }

    /** The zoom showing the page at [percent] of its true size. */
    fun zoomForPercent(percent: Int): Float = percent / 100f * actualScale / fitScale.coerceAtLeast(0.001f)

    /** Width of the list content in pixels (wider than the viewport when zoomed in). */
    val contentWidthPx: Int
        get() = maxOf(viewport.width, (maxPageWidth * scale + 2 * paddingPx).roundToInt())

    /** Base render width: sharp at zoom 1 (or the zoom, when smaller) on most screens, bounded for memory. */
    fun baseWidthPx(page: Int): Int =
        (session.pageSizes[page].width * fitScale * zoom.coerceAtMost(1f)).roundToInt().coerceIn(1, MAX_BASE_WIDTH_PX)

    fun pageHeightPx(page: Int, scale: Float): Float = session.pageSizes[page].height * scale

    // --- zoom ---

    fun zoomTo(target: Float) = zoomBy(target / zoom)

    fun zoomBy(factor: Float, focal: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        val target = (zoom * factor).coerceIn(minZoom, maxZoom)
        val oldScale = scale
        val newScale = fitScale * target
        val f = newScale / oldScale
        gestureScale = 1f
        if (f == 1f) return
        val anchor = PdfLayoutMath.anchorAfterZoom(
            itemCount = session.pageSizes.size,
            oldHeight = { pageHeightPx(it, oldScale) },
            newHeight = { pageHeightPx(it, newScale) },
            spacing = paddingPx,
            first = ListAnchor(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset),
            focalY = focal.y,
        )
        val newX = PdfLayoutMath.scrollXAfterZoom(hScroll.value, focal.x, f, paddingPx)
        zoom = target
        session.currentZoom = target
        scope.launch {
            listState.scrollToItem(anchor.index, anchor.offset)
            withFrameNanos { } // let the wider content measure before scrolling sideways
            hScroll.scrollTo(newX)
        }
    }

    fun endPinch() = zoomBy(gestureScale, gestureFocal)

    fun showMessage(text: String) = onMessage(text)

    fun jumpTo(page: Int, topPts: Float = 0f, leftPts: Float? = null) {
        scope.launch {
            val y = (topPts * scale - viewport.height / 3f).coerceAtLeast(0f).roundToInt()
            listState.scrollToItem(page, y)
            if (leftPts != null && contentWidthPx > viewport.width) {
                val pageLeft = (contentWidthPx - session.pageSizes[page].width * scale) / 2
                hScroll.scrollTo((pageLeft + leftPts * scale - viewport.width / 3f).coerceAtLeast(0f).roundToInt())
            }
        }
    }

    // --- taps, forms, marks ---

    /** A tap at (x, y) display points on [page], with that page's form [fields]. */
    fun tapPage(page: Int, x: Float, y: Float, fields: List<FormField>, highlights: List<Highlight> = emptyList()) {
        if (drawTool != null) return
        commitEdit()
        choosing = null
        if (selection != null || selectedHighlight != null) {
            clearSelection()
            selectAnnotation(null)
            return
        }
        tool?.let { t ->
            tool = null
            placeTool(t, page, x, y)
            return
        }
        if (selectedMark != null) {
            selectedMark = null
            return
        }
        val field = fields.lastOrNull { it.rect.contains(x, y) && !it.readOnly }
        if (field == null) {
            selectAnnotation(highlights.lastOrNull { h -> h.rects.any { it.contains(x, y) } })
            return
        }
        when (field.type) {
            FormField.Type.TEXT -> editing = FieldEdit(field)
            FormField.Type.CHECKBOX, FormField.Type.RADIO -> scope.launch { session.click(field) }
            FormField.Type.COMBO_BOX, FormField.Type.LIST_BOX -> choosing = field
            FormField.Type.SIGNATURE -> signatureTarget = SignatureTarget(page, field.rect)
            else -> Unit
        }
    }

    /** Writes [edit] (by default the open one) into the field, once. */
    fun commitEdit(edit: FieldEdit? = editing) {
        if (edit == null) return
        if (editing === edit) editing = null
        if (edit.committed) return
        edit.committed = true
        if (edit.text != edit.field.value) {
            scope.launch { if (!session.setText(edit.field, edit.text)) onMessage("Could not fill ${edit.field.name}") }
        }
    }

    fun choose(field: FormField, option: Int) {
        choosing = null
        scope.launch {
            val selected = if (field.multiSelect) !field.options[option].selected else true
            if (!session.selectOption(field, option, selected)) onMessage("Could not set ${field.name}")
        }
    }

    private fun placeTool(tool: PlaceTool, page: Int, x: Float, y: Float) {
        val size = session.pageSizes[page]
        val mark = when (tool) {
            PlaceTool.NOTE -> {
                noteDraft = NoteDraft(page, x, y)
                return
            }
            PlaceTool.TEXT -> {
                val h = DEFAULT_FONT_PT * MarkGeometry.TEXT_LINE_FACTOR
                PdfMark.Text(session.nextMarkId(), page, MarkGeometry.centredRect(x, y, textWidthPts("", DEFAULT_FONT_PT), h, size.width, size.height), "", INK)
            }
            PlaceTool.CHECK, PlaceTool.CROSS -> PdfMark.Symbol(
                session.nextMarkId(), page,
                MarkGeometry.centredRect(x, y, DEFAULT_SYMBOL_PT, DEFAULT_SYMBOL_PT, size.width, size.height),
                cross = tool == PlaceTool.CROSS, argb = INK,
            )
        }
        session.putMark(mark)
        selectedMark = mark.id
    }

    /** Places a picked signature image on [signatureTarget] (or the visible page). */
    fun placeSignature(image: Bitmap, initials: Boolean) {
        val target = signatureTarget ?: SignatureTarget(visiblePage(), null)
        signatureTarget = null
        val size = session.pageSizes[target.page]
        val aspect = image.width.toFloat() / image.height
        val rect = if (target.box != null) {
            PdfLayoutMath.fitInside(target.box, aspect)
        } else {
            val w = (if (initials) 60f else 150f).coerceAtMost(size.width * 0.6f)
            val (cx, cy) = visibleCentre(target.page)
            MarkGeometry.centredRect(cx, cy, w, w / aspect, size.width, size.height)
        }
        val mark = PdfMark.Signature(session.nextMarkId(), target.page, rect, image)
        session.putMark(mark)
        selectedMark = mark.id
    }

    fun updateMark(mark: PdfMark) = session.putMark(mark)

    fun deleteMark(mark: PdfMark) {
        session.removeMark(mark.id)
        if (selectedMark == mark.id) selectedMark = null
    }

    fun commitMark(mark: PdfMark) {
        selectedMark = null
        if (mark is PdfMark.Text && mark.text.isBlank()) {
            session.removeMark(mark.id)
            return
        }
        scope.launch { if (!session.commit(mark)) onMessage("Could not add that to the page") }
    }

    /** The page occupying the middle of the viewport. */
    fun visiblePage(): Int {
        val middle = viewport.height / 2
        return listState.layoutInfo.visibleItemsInfo.firstOrNull { middle in it.offset..(it.offset + it.size) }?.index
            ?: listState.firstVisibleItemIndex
    }

    private fun visibleCentre(page: Int): Pair<Float, Float> {
        val size = session.pageSizes[page]
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }
        val cy = if (item == null) size.height / 2 else (viewport.height / 2f - item.offset) / scale
        val pageLeft = (contentWidthPx - size.width * scale) / 2
        val cx = (hScroll.value + viewport.width / 2f - pageLeft) / scale
        return cx.coerceIn(0f, size.width) to cy.coerceIn(0f, size.height)
    }

    // --- text selection and highlights ---

    /** Starts a selection at the word under (x, y) on [page] (a long press). */
    fun startSelection(page: Int, x: Float, y: Float) {
        if (drawTool != null) return
        commitEdit()
        selectAnnotation(null)
        selectJob?.cancel()
        selectJob = scope.launch {
            val at = session.charIndexAt(page, x, y, SELECT_TOLERANCE_PT)
            if (at < 0) {
                onMessage("No selectable text here")
                return@launch
            }
            val word = PdfText.wordAround(session.pageText(page), at)
            anchor = word
            select(page, word.first, word.last + 1)
        }
    }

    /** Extends the selection to the word under (x, y) while the finger drags. */
    fun extendSelection(page: Int, x: Float, y: Float) {
        val from = anchor ?: return
        if (selection?.page != page) return
        selectJob?.cancel()
        selectJob = scope.launch {
            val at = session.charIndexAt(page, x, y, DRAG_TOLERANCE_PT)
            if (at < 0) return@launch
            val word = PdfText.wordAround(session.pageText(page), at)
            select(page, minOf(from.first, word.first), maxOf(from.last, word.last) + 1)
        }
    }

    private suspend fun select(page: Int, start: Int, end: Int) {
        val text = session.pageText(page)
        val rects = session.textRects(page, start, end)
        val s = PdfTextSelection(page, start, end, rects, text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length)))
        selection = s
        session.selectedText = s.text
    }

    fun clearSelection() {
        selectJob?.cancel()
        anchor = null
        selection = null
        session.selectedText = null
    }

    fun highlightSelection(argb: Int, type: AnnotType = AnnotType.HIGHLIGHT) {
        val s = selection ?: return
        clearSelection()
        scope.launch { if (!session.highlight(s.page, s.start, s.end, argb, type)) onMessage("Could not mark that text") }
    }

    /** Selects an annotation (or clears the selection with null); a note's text is read for display. */
    fun selectAnnotation(annotation: Highlight?) {
        selectedHighlight = annotation
        selectedNoteText = null
        if (annotation?.type == AnnotType.NOTE) {
            scope.launch {
                val text = session.annotationText(annotation)
                if (selectedHighlight == annotation) selectedNoteText = text
            }
        }
    }

    fun removeSelectedHighlight() {
        val h = selectedHighlight ?: return
        selectAnnotation(null)
        scope.launch { if (!session.removeHighlight(h)) onMessage("Could not remove that") }
    }

    // --- drawing, shapes and notes ---

    fun startDrawing(draw: DrawTool) {
        commitEdit()
        clearSelection()
        selectAnnotation(null)
        tool = null
        drawTool = draw
    }

    /** Ends the drawing tool, committing any strokes drawn. */
    fun finishDrawing() {
        commitInk()
        drawTool = null
        shape = null
    }

    fun drawStart(page: Int, x: Float, y: Float) {
        when (drawTool) {
            DrawTool.INK -> {
                // Strokes on another page start a new drawing there.
                if (ink?.page != page) commitInk()
                val draft = ink ?: InkDraft(page).also { ink = it }
                draft.current = floatArrayOf(x, y)
            }
            DrawTool.RECT, DrawTool.ELLIPSE -> shape = ShapeDraft(page, x, y, x, y)
            null -> Unit
        }
    }

    fun drawMove(page: Int, x: Float, y: Float) {
        when (drawTool) {
            DrawTool.INK -> ink?.takeIf { it.page == page }?.let { draft -> draft.current = draft.current?.plus(floatArrayOf(x, y)) }
            DrawTool.RECT, DrawTool.ELLIPSE -> shape = shape?.takeIf { it.page == page }?.copy(endX = x, endY = y)
            null -> Unit
        }
    }

    fun drawEnd() {
        when (drawTool) {
            DrawTool.INK -> ink?.let { draft ->
                draft.current?.takeIf { it.size >= 4 }?.let { draft.strokes += it }
                draft.current = null
            }
            DrawTool.RECT, DrawTool.ELLIPSE -> {
                val s = shape ?: return
                shape = null
                val r = s.rect
                if (r.width < 4 || r.height < 4) return
                val ellipse = drawTool == DrawTool.ELLIPSE
                scope.launch { if (!session.addShape(s.page, r, ellipse, inkColor, INK_WIDTH)) onMessage("Could not add the shape") }
            }
            null -> Unit
        }
    }

    private fun commitInk() {
        val draft = ink ?: return
        ink = null
        val strokes = draft.strokes.toList()
        if (strokes.isEmpty()) return
        scope.launch { if (!session.addInk(draft.page, strokes, inkColor, INK_WIDTH)) onMessage("Could not add the drawing") }
    }

    /** Adds the note being placed, with [text]. */
    fun addNote(text: String) {
        val draft = noteDraft ?: return
        noteDraft = null
        if (text.isBlank()) return
        scope.launch { if (!session.addNote(draft.page, draft.x, draft.y, text.trim(), NOTE_COLOR)) onMessage("Could not add the note") }
    }

    // --- search ---

    fun runSearch() {
        searchJob?.cancel()
        searchMatches.clear()
        searchIndex = -1
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        searching = true
        searchJob = scope.launch {
            try {
                session.search(query) { found ->
                    searchMatches.addAll(found)
                    if (searchIndex < 0) {
                        searchIndex = 0
                        showMatch()
                    }
                }
                if (searchMatches.isEmpty()) onMessage("No matches for \"$query\"")
            } finally {
                searching = false
            }
        }
    }

    fun stepMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = Math.floorMod(searchIndex + delta, searchMatches.size)
        showMatch()
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchOpen = false
        searchMatches.clear()
        searchIndex = -1
    }

    private fun showMatch() {
        val match = searchMatches.getOrNull(searchIndex) ?: return
        val rect = match.rects.firstOrNull()
        jumpTo(match.page, rect?.top ?: 0f, rect?.left)
    }

    companion object {
        const val MAX_BASE_WIDTH_PX = 1280

        /** Highest zoom offered, in percent of true size. */
        const val MAX_PERCENT = 1600

        /** Largest page size in pixels: layout sizes beyond 2^16 cannot be measured. */
        const val MAX_PAGE_PX = 60_000f

        /** Zoom levels offered in the zoom menu, in percent of true size. */
        val ZOOM_PRESETS = listOf(50, 75, 100, 125, 150, 200, 400, 800, 1600)

        /** Colours for drawings, shapes, notes and underlines. */
        val INK_COLORS = listOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF000000.toInt())
        const val INK_WIDTH = 2f
        const val NOTE_COLOR = 0xFFFFC107.toInt()
        const val DEFAULT_FONT_PT = 12f
        const val DEFAULT_SYMBOL_PT = 14f
        const val INK = 0xFF000000.toInt()
        const val SELECT_TOLERANCE_PT = 6f
        const val DRAG_TOLERANCE_PT = 14f

        /** Highlight colours offered for selected text (PDFium blends them like a marker). */
        val HIGHLIGHT_COLORS = listOf(0xFFFFE135.toInt(), 0xFF8BE36B.toInt(), 0xFF6EC6FF.toInt(), 0xFFFF8FB8.toInt())

        private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

        /** Approximate width of [text] in Helvetica-like metrics, with room for the caret. */
        fun textWidthPts(text: String, fontSize: Float): Float {
            measurePaint.textSize = fontSize
            return maxOf(measurePaint.measureText(text) + fontSize * 0.5f, fontSize * 4)
        }
    }
}
