package com.example.myapplication

import android.util.Log
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs
import kotlin.math.sqrt

data class DrawStroke(
    val points: List<Offset>,
    val color: Color = Color.Black,
    val strokeWidth: Float = 4f
) {
    fun toPath(): Path {
        val path = Path()
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { point ->
                path.lineTo(point.x, point.y)
            }
        }
        return path
    }

    // Check if a point is near this stroke for erasing
    fun containsPoint(point: Offset, tolerance: Float = 20f): Boolean {
        return points.any { strokePoint ->
            val distance = sqrt(
                (strokePoint.x - point.x) * (strokePoint.x - point.x) +
                        (strokePoint.y - point.y) * (strokePoint.y - point.y)
            )
            distance <= tolerance
        }
    }
}

@Composable
fun WhiteboardCanvas(modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val strokes = remember { mutableStateListOf<DrawStroke>() }
    val currentPoints = remember { mutableStateListOf<Offset>() }
    var isDrawing by remember { mutableStateOf(false) }
    var isErasing by remember { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstPointer = awaitFirstDown(requireUnconsumed = false)

                    // Check input tool type
                    val isStylus = firstPointer.type == PointerType.Stylus

                    if (isStylus) {
                        // For stylus, check if button is pressed in the next event
                        val nextEvent = awaitPointerEvent()
                        val isStylusEraser = nextEvent.buttons.isPrimaryPressed
                        //val isStylusEraser = nextEvent.buttons.isSecondaryPressed

                        if (isStylusEraser) {
                            handleErasing(
                                startPointer = firstPointer,
                                scale = scale,
                                offset = offset,
                                strokes = strokes,
                                onErasingStart = { isErasing = true },
                                onErasingEnd = { isErasing = false }
                            )
                        } else {
                            handleDrawing(
                                startPointer = firstPointer,
                                scale = scale,
                                offset = offset,
                                onPathStart = { startPoint ->
                                    currentPoints.clear()
                                    currentPoints.add(startPoint)
                                    isDrawing = true
                                },
                                onPathUpdate = { point ->
                                    currentPoints.add(point)
                                },
                                onPathEnd = {
                                    if (currentPoints.isNotEmpty()) {
                                        strokes.add(DrawStroke(currentPoints.toList()))
                                    }
                                    currentPoints.clear()
                                    isDrawing = false
                                }
                            )
                        }
                    } else {
                        // Wait briefly to detect multi-touch for finger gestures
                        val secondPointer = withTimeoutOrNull(100) {
                            awaitPointerEvent().changes.find { it.id != firstPointer.id }
                        }

                        if (secondPointer != null) {
                            // Multi-finger touch gesture (pan/zoom)
                            handlePanAndZoom(
                                onTransform = { centroid, pan, zoom ->
                                    scale = (scale * zoom).coerceIn(0.2f, 10f)
                                    offset += pan
                                }
                            )
                        } else {
                            // Single finger touch gesture (pan only)
                            handlePanning(
                                startPointer = firstPointer,
                                onPan = { pan ->
                                    offset += pan
                                }
                            )
                        }
                    }
                }
            }
    ) {
        // Fill the entire canvas with white background
        drawRect(Color.White, size = size)

        // Save canvas state
        drawContext.canvas.save()

        // Apply transformations
        drawContext.canvas.translate(offset.x, offset.y)
        drawContext.canvas.scale(scale, scale)

        // Draw all completed strokes
        strokes.fastForEach { stroke ->
            drawPath(
                path = stroke.toPath(),
                color = stroke.color,
                style = Stroke(
                    width = stroke.strokeWidth / scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // Draw current stroke being drawn
        if (isDrawing && currentPoints.size > 1) {
            val currentStroke = DrawStroke(currentPoints.toList())
            drawPath(
                path = currentStroke.toPath(),
                color = currentStroke.color,
                style = Stroke(
                    width = currentStroke.strokeWidth / scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // Restore canvas state
        drawContext.canvas.restore()
    }
}

private suspend fun AwaitPointerEventScope.handlePanning(
    startPointer: PointerInputChange,
    onPan: (Offset) -> Unit
) {
    var lastPosition = startPointer.position

    do {
        val event = awaitPointerEvent()
        val change = event.changes.find { it.id == startPointer.id }

        if (change != null && change.pressed) {
            val currentPosition = change.position
            val pan = currentPosition - lastPosition
            onPan(pan)
            lastPosition = currentPosition
            change.consume()
        }
    } while (change != null && change.pressed)
}

private suspend fun AwaitPointerEventScope.handlePanAndZoom(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit
) {
    var lastCentroid = Offset.Zero
    var lastDistance = 0f

    do {
        val event = awaitPointerEvent()
        val activePointers = event.changes.filter { it.pressed }

        if (activePointers.size >= 2) {
            val centroid = activePointers
                .map { it.position }
                .reduce { acc, offset -> acc + offset } / activePointers.size.toFloat()

            val distance = activePointers
                .zipWithNext { a, b ->
                    val diff = a.position - b.position
                    sqrt(diff.x * diff.x + diff.y * diff.y)
                }
                .average().toFloat()

            if (lastDistance > 0f) {
                val zoom = distance / lastDistance
                val pan = centroid - lastCentroid
                onTransform(centroid, pan, zoom)

                activePointers.fastForEach { it.consume() }
            }

            lastCentroid = centroid
            lastDistance = distance
        }
    } while (activePointers.size >= 2)
}

private suspend fun AwaitPointerEventScope.handleDrawing(
    startPointer: PointerInputChange,
    scale: Float,
    offset: Offset,
    onPathStart: (Offset) -> Unit,
    onPathUpdate: (Offset) -> Unit,
    onPathEnd: () -> Unit
) {
    val startPos = (startPointer.position - offset) / scale
    onPathStart(startPos)

    var lastPos = startPos

    do {
        val event = awaitPointerEvent()
        val change = event.changes.find { it.id == startPointer.id }

        if (change != null && change.pressed) {
            val currentPos = (change.position - offset) / scale

            // Add smoothing to reduce jitter
            val distance = sqrt(
                (currentPos.x - lastPos.x) * (currentPos.x - lastPos.x) +
                        (currentPos.y - lastPos.y) * (currentPos.y - lastPos.y)
            )

            if (distance > 2f) { // Only add points if they're far enough apart
                onPathUpdate(currentPos)
                lastPos = currentPos
            }

            change.consume()
        }
    } while (change != null && change.pressed)

    onPathEnd()
}

private suspend fun AwaitPointerEventScope.handleErasing(
    startPointer: PointerInputChange,
    scale: Float,
    offset: Offset,
    strokes: MutableList<DrawStroke>,
    onErasingStart: () -> Unit,
    onErasingEnd: () -> Unit
) {
    onErasingStart()

    do {
        val event = awaitPointerEvent()
        val change = event.changes.find { it.id == startPointer.id }

        if (change != null && change.pressed) {
            val eraserPos = (change.position - offset) / scale
            val eraserRadius = 30f / scale // Adjust eraser size based on zoom

            // Remove strokes that intersect with the eraser
            strokes.removeAll { stroke ->
                stroke.containsPoint(eraserPos, eraserRadius)
            }

            change.consume()
        }

    } while (change != null && change.pressed)

    onErasingEnd()
}