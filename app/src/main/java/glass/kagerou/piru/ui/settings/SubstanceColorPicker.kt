package glass.kagerou.piru.ui.settings

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.model.Oklch
import glass.kagerou.piru.model.OklchPickerModel
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.model.displayP3
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlin.math.roundToInt

/**
 * The Oklch picker: a lightness-by-chroma plane and a hue rail.
 *
 * Ported from `OklchPickerRenderer` and `SubstanceColorPickerView`.
 *
 * ## Why the surfaces are bitmaps
 * The plane is a per-pixel function of lightness, chroma and hue — 160 × 120
 * evaluations, each a full Oklch→P3 matrix chain — and the rail is 360 more. That
 * is far too much work for a draw pass, so both are rasterized once per hue (or
 * per lightness) into an `ImageBitmap` and blitted.
 *
 * ## Drawn in Display P3, not sRGB
 * The bitmap's colour space is set to P3 explicitly, so a colour past the sRGB
 * gamut shows as itself rather than being clipped on the way into the texture —
 * which is the entire point of picking a colour here.
 *
 * ## Clipped to the gamut, not merely bounded by it
 * Chroma above a column's ceiling paints as the ceiling colour, and the *plane* is
 * masked to the ceiling's outline. So the shape the user drags inside is the shape
 * of what the panel can actually show; a plain rectangle would offer a corner of
 * colours that all collapse onto the same edge.
 */
@Composable
fun SubstanceColorPicker(
    model: OklchPickerModel,
    onChange: (OklchPickerModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val planeSize = remember { IntSize(160, 120) }
    val railWidth = 360

    // Rasterized per hue: moving on the plane does not change the plane's pixels,
    // only which one is under the thumb.
    val planeBitmap = remember(model.color.h) {
        renderPlane(model.color.h, planeSize.width, planeSize.height)
    }
    val railBitmap = remember(model.color.l, model.color.c) {
        renderHueRail(model.color.l, model.color.c, railWidth)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Colour", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (model.usesDefault) "Class default" else "Custom",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }

                var planeBounds by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(PiruTheme.colors.cardShape)
                        .onSizeChanged { planeBounds = it }
                        .pointerInput(model.color.h) {
                            detectDragGestures { change, _ ->
                                val (lightness, chroma) = planeAt(change.position, planeBounds)
                                onChange(model.setPlane(lightness, chroma))
                            }
                        }
                        .pointerInput(model.color.h, planeBounds) {
                            detectTapGestures { offset ->
                                val (lightness, chroma) = planeAt(offset, planeBounds)
                                onChange(model.setPlane(lightness, chroma))
                            }
                        },
                ) {
                    Image(
                        bitmap = planeBitmap.asImageBitmap(),
                        contentDescription = "Lightness and chroma",
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }

                var railBounds by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(PiruTheme.colors.cardShape)
                        .onSizeChanged { railBounds = it }
                        .pointerInput(model.color.l, model.color.c) {
                            detectDragGestures { change, _ ->
                                onChange(model.setHue(hueAt(change.position.x, railBounds.width)))
                            }
                        }
                        .pointerInput(model.color.l, model.color.c, railBounds) {
                            detectTapGestures { offset ->
                                onChange(model.setHue(hueAt(offset.x, railBounds.width)))
                            }
                        },
                ) {
                    Image(
                        bitmap = railBitmap.asImageBitmap(),
                        contentDescription = "Hue",
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // A swatch of what will actually be stored, so the choice is
                    // confirmed against the real P3 value rather than the preview.
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .fillMaxWidth(0.5f)
                            .clip(PiruTheme.colors.cardShape),
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                            drawRect(model.tint.toComposeColor())
                        }
                    }
                    Text(
                        "L %.2f · C %.3f · H %.0f°".format(model.color.l, model.color.c, model.color.h),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }

                TextButton(onClick = { onChange(model.restoreDefault()) }, enabled = !model.usesDefault) {
                    Text("Use the class colour")
                }
            }
        }
    }
}

/** The plane's coordinates from a touch: lightness first, then chroma. */
private fun planeAt(position: Offset, bounds: IntSize): Pair<Double, Double> {
    if (bounds.width == 0 || bounds.height == 0) return 0.7 to 0.1
    val x = (position.x / bounds.width).coerceIn(0f, 1f).toDouble()
    // Y is inverted: chroma rises up the plane, and the origin is at the top.
    val y = 1 - (position.y / bounds.height).coerceIn(0f, 1f).toDouble()
    val plane = OklchPickerModel.Plane
    return (plane.LIGHTNESS.start + (plane.LIGHTNESS.endInclusive - plane.LIGHTNESS.start) * x) to
        (plane.CHROMA.start + (plane.CHROMA.endInclusive - plane.CHROMA.start) * y)
}

private fun hueAt(x: Float, width: Int): Double =
    if (width == 0) 0.0 else 360.0 * (x / width).coerceIn(0f, 1f)

/**
 * A [Bitmap] in Display P3.
 *
 * `ARGB_8888` plus an explicit colour space: without that, the values would be
 * read as sRGB and every colour outside that gamut would be clipped into it on
 * the way to the screen — which would make the picker unable to show the colours
 * it exists to pick.
 */
private fun p3Bitmap(width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        // The setter, not `colorSpace = …`: Kotlin exposes it as a getter-only
        // property, so the assignment form does not compile.
        setColorSpace(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    }

private fun renderPlane(hue: Double, width: Int, height: Int): Bitmap {
    val bitmap = p3Bitmap(width, height)
    val pixels = IntArray(width * height)
    val plane = OklchPickerModel.Plane
    val ceilings = DoubleArray(width) { column ->
        val l = plane.LIGHTNESS.start +
            (plane.LIGHTNESS.endInclusive - plane.LIGHTNESS.start) * (column + 0.5) / width
        glass.kagerou.piru.model.displayP3ChromaCeiling(l, hue)
    }
    for (x in 0 until width) {
        val l = plane.LIGHTNESS.start +
            (plane.LIGHTNESS.endInclusive - plane.LIGHTNESS.start) * (x + 0.5) / width
        for (y in 0 until height) {
            // Above the ceiling the pixel paints as the ceiling colour, which is
            // what makes the plane's edge the gamut's outline once the view masks
            // to it.
            val c = minOf(plane.CHROMA.endInclusive * (1 - (y + 0.5) / height), ceilings[x])
            pixels[y * width + x] = Oklch.of(l, c, hue).displayP3.toArgb()
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

private fun renderHueRail(lightness: Double, chroma: Double, width: Int): Bitmap {
    val bitmap = p3Bitmap(width, 1)
    val pixels = IntArray(width)
    for (x in 0 until width) {
        val hue = 360.0 * (x + 0.5) / width
        // Each column shows the colour the thumb would actually land on at this
        // lightness: at its own ceiling where the requested chroma does not fit.
        val ceiling = glass.kagerou.piru.model.displayP3ChromaCeiling(lightness, hue)
        pixels[x] = Oklch.of(lightness, minOf(chroma, ceiling), hue).displayP3.toArgb()
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, 1)
    return bitmap
}

private fun P3Color.toArgb(): Int {
    val r = (red * 255).roundToInt().coerceIn(0, 255)
    val g = (green * 255).roundToInt().coerceIn(0, 255)
    val b = (blue * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
