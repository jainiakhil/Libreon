// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val InkBlack = Color(0xFF111111)
private val InkBlue = Color(0xFF1A3FB0)

/**
 * Signature capture: draw with a finger or stylus, pick black or blue ink, Clear, Done.
 * Strokes are smoothed with [InkSmoothing]; [onDone] receives the vector ink.
 */
@Composable
fun SignaturePadDialog(initials: Boolean, onDismiss: () -> Unit, onDone: (SignatureInk) -> Unit) {
    val strokes = remember { mutableStateListOf<FloatArray>() }
    var current by remember { mutableStateOf<ArrayList<Float>?>(null) }
    var tick by remember { mutableIntStateOf(0) } // redraw trigger while a stroke grows
    var color by remember { mutableStateOf(InkBlack) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.padding(16.dp).widthIn(max = 720.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (initials) "Draw your initials" else "Draw your signature", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (initials) 2f else 3f)
                        .background(Color.White, MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .semantics { contentDescription = if (initials) "Initials pad" else "Signature pad" }
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val points = arrayListOf(down.position.x, down.position.y)
                                current = points
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val p = change.position
                                    val dx = p.x - points[points.size - 2]
                                    val dy = p.y - points[points.size - 1]
                                    // Always keep the release point so fast strokes end where the finger lifted.
                                    if (!change.pressed || dx * dx + dy * dy >= InkSmoothing.MIN_STEP * InkSmoothing.MIN_STEP) {
                                        points += p.x; points += p.y
                                        tick++
                                    }
                                    if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) change.consume()
                                    if (!change.pressed) break
                                }
                                strokes += points.toFloatArray()
                                current = null
                            }
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        @Suppress("UNUSED_EXPRESSION") tick
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = color.toArgb()
                            style = Paint.Style.STROKE
                            strokeWidth = size.height * SignatureStore.STROKE_FRACTION
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                        drawIntoCanvas { canvas ->
                            strokes.forEach { canvas.nativeCanvas.drawPath(SignatureStore.strokePath(it), paint) }
                            current?.let { canvas.nativeCanvas.drawPath(SignatureStore.strokePath(it.toFloatArray()), paint) }
                        }
                    }
                    if (strokes.isEmpty() && current == null) {
                        Text("Sign here", Modifier.align(Alignment.Center), color = Color.LightGray, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorDot(InkBlack, "Black", selected = color == InkBlack) { color = InkBlack }
                    ColorDot(InkBlue, "Blue", selected = color == InkBlue) { color = InkBlue }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = strokes.isNotEmpty(),
                        onClick = {
                            onDone(SignatureInk(padSize.width.toFloat(), padSize.height.toFloat(), color.toArgb(), strokes.toList()))
                        },
                    ) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(32.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
            .padding(5.dp)
            .background(color, CircleShape)
            .semantics {
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick),
    )
}
