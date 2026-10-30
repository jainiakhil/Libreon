// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.session.PdfSession

/** Continuous vertical page list (M1). Zoom, tiles, thumbnails and search come in M3. */
@Composable
fun PdfPagesView(session: PdfSession) {
    val listState = rememberLazyListState(session.initialPage, session.initialOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (page, offset) ->
                session.currentPage = page
                session.currentOffset = offset
            }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF5F6368))) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 16.dp).toPx().toInt() }.coerceAtLeast(1)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(session.pageSizes.size) { index -> PdfPage(session, index, widthPx) }
        }
        Surface(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
        ) {
            Text(
                "${listState.firstVisibleItemIndex + 1} / ${session.pageSizes.size}",
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PdfPage(session: PdfSession, index: Int, widthPx: Int) {
    val size = session.pageSizes[index]
    val bitmap by produceState<Bitmap?>(null, session, index, widthPx) {
        value = runCatching { session.renderPage(index, widthPx) }.getOrNull()
    }
    Box(Modifier.fillMaxWidth().aspectRatio(size.width / size.height).background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize()) }
    }
}
