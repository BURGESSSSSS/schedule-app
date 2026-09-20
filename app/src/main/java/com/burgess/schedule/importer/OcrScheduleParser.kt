package com.burgess.schedule.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

 data class OcrCourseDraft(
    val name: String,
    val detail: String,
    val dayIndex: Int,
    val period: Int,
    val endPeriod: Int
)

object OcrScheduleParser {
    private data class GridGeometry(
        val left: Int,
        val top: Int,
        val cellWidth: Float,
        val rowHeight: Float
    )

    private data class Region(
        val dayIndex: Int,
        val period: Int,
        val endPeriod: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private data class LocationHint(
        val detail: String,
        val centerX: Float,
        val centerY: Float
    )

    private data class Rgb(val red: Int, val green: Int, val blue: Int)

    suspend fun parseScheduleImage(context: Context, uri: Uri): List<OcrCourseDraft> = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream) ?: error("无法读取图片")
        }
        val geometry = detectGrid(bitmap)
        val regions = detectRegions(bitmap, geometry)
        if (regions.isEmpty()) {
            bitmap.recycle()
            return@withContext emptyList()
        }

        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        try {
            val fullResult = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val locationHints = fullResult.textBlocks
                .flatMap { block -> block.lines }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val detail = Regex("\\[([^\\]]+)]").find(line.text)?.groupValues?.get(1)
                        ?.replace(Regex("[\\s|丨]+"), "")
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    LocationHint(detail, box.centerX().toFloat(), box.centerY().toFloat())
                }
            regions.mapNotNull { region ->
                val padding = 12
                val cropLeft = (region.left - padding).coerceAtLeast(0)
                val cropTop = (region.top - padding).coerceAtLeast(0)
                val cropRight = (region.right + padding).coerceAtMost(bitmap.width)
                val cropBottom = (region.bottom + padding).coerceAtMost(bitmap.height)
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                val scaledCrop = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(scaledCrop, 0)))
                scaledCrop.recycle()
                crop.recycle()
                val parsed = simplify(result.text) ?: return@mapNotNull null
                val locationHint = locationHints
                    .minByOrNull { hint ->
                        kotlin.math.abs(hint.centerX - region.centerX) + kotlin.math.abs(hint.centerY - region.centerY)
                    }
                    ?.takeIf { hint ->
                        kotlin.math.abs(hint.centerX - region.centerX) < region.width * 1.15f &&
                            kotlin.math.abs(hint.centerY - region.centerY) < region.height * 1.25f
                    }
                // The app only supports paired lessons. Keep the crop based on
                // the detected region, but normalize its visible lesson range.
                val normalizedPeriod = (region.period / 2) * 2
                val normalizedEndPeriod = normalizedPeriod + 1
                OcrCourseDraft(
                    name = parsed.first,
                    detail = locationHint?.detail ?: parsed.second,
                    dayIndex = region.dayIndex,
                    period = normalizedPeriod,
                    endPeriod = normalizedEndPeriod
                )
            }.distinctBy { it.dayIndex.toString() + "-" + it.period + "-" + it.name }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun detectGrid(bitmap: Bitmap): GridGeometry {
        val scale = 4
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        for (y in (bitmap.height * 0.25f).toInt() until bitmap.height step scale) {
            for (x in 0 until bitmap.width step scale) {
                if (!isCourseColor(bitmap.getPixel(x, y))) continue
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        val detected = right > left && bottom > top && right - left > bitmap.width * 0.5f
        val fallbackLeft = (bitmap.width * 0.081f).toInt()
        val fallbackTop = (bitmap.height * 0.306f).toInt()
        val fallbackRight = (bitmap.width * 0.994f).toInt()
        val fallbackBottom = (bitmap.height * 0.963f).toInt()
        // A sparse timetable may leave the first or last rows empty. The
        // colored blocks therefore cannot safely define the vertical grid.
        val gridLeft = if (detected) left else fallbackLeft
        val gridTop = fallbackTop
        val gridRight = if (detected) right + scale else fallbackRight
        val gridBottom = fallbackBottom
        return GridGeometry(
            left = gridLeft,
            top = gridTop,
            cellWidth = (gridRight - gridLeft) / 5f,
            rowHeight = (gridBottom - gridTop) / 8f
        )
    }

    private fun detectRegions(bitmap: Bitmap, geometry: GridGeometry): List<Region> {
        val cellColors = Array(5) { arrayOfNulls<Rgb>(8) }
        for (day in 0 until 5) {
            for (period in 0 until 8) {
                cellColors[day][period] = sampleCellColor(bitmap, geometry, day, period)
            }
        }

        val regions = mutableListOf<Region>()
        for (day in 0 until 5) {
            var period = 0
            while (period < 8) {
                val color = cellColors[day][period]
                if (color == null) {
                    period++
                    continue
                }
                val start = period
                var end = period
                while (end + 1 < 8 && similar(color, cellColors[day][end + 1])) end++
                val left = (geometry.left + day * geometry.cellWidth + 3).toInt().coerceIn(0, bitmap.width - 1)
                val top = (geometry.top + start * geometry.rowHeight + 3).toInt().coerceIn(0, bitmap.height - 1)
                val right = (geometry.left + (day + 1) * geometry.cellWidth - 3).toInt().coerceAtMost(bitmap.width)
                val bottom = (geometry.top + (end + 1) * geometry.rowHeight - 3).toInt().coerceAtMost(bitmap.height)
                if (right > left && bottom > top) {
                    regions += Region(day, start, end, left, top, right, bottom)
                }
                period = end + 1
            }
        }
        return regions
    }

    private fun sampleCellColor(bitmap: Bitmap, geometry: GridGeometry, day: Int, period: Int): Rgb? {
        val centerX = geometry.left + (day + 0.5f) * geometry.cellWidth
        val centerY = geometry.top + (period + 0.5f) * geometry.rowHeight
        val offsets = listOf(
            -0.38f to -0.38f,
            0.38f to -0.38f,
            -0.38f to 0.38f,
            0.38f to 0.38f,
            0f to -0.38f,
            -0.38f to 0f,
            0.38f to 0f,
            0f to 0.38f,
            0f to 0f
        )
        return offsets.mapNotNull { (xOffset, yOffset) ->
            val x = (centerX + xOffset * geometry.cellWidth).toInt().coerceIn(0, bitmap.width - 1)
            val y = (centerY + yOffset * geometry.rowHeight).toInt().coerceIn(0, bitmap.height - 1)
            bitmap.getPixel(x, y).takeIf { isCourseColor(it) }?.toRgb()
        }.firstOrNull()
    }

    private fun simplify(raw: String): Pair<String, String>? {
        val compact = raw.replace(Regex("\\s+"), "").trim()
        if (compact.isBlank()) return null
        val atIndex = compact.indexOf('@')
        val beforeAt = if (atIndex >= 0) compact.substring(0, atIndex) else compact
        val rawName = beforeAt.substringBefore('[').replace("|", "").replace("丨", "").trim('，', ',', '：', ':')
        val locationMarkers = listOf("先进制造", "进制造", "制造产教", "产教融合", "实训基地")
        val locationStart = locationMarkers
            .map { rawName.indexOf(it) }
            .filter { it >= 2 }
            .minOrNull()
        val name = if (locationStart != null) rawName.substring(0, locationStart) else rawName
        if (name.length < 2) return null
        val detail = Regex("\\[([^\\]]+)]").find(compact)?.groupValues?.get(1)
            ?.replace(Regex("[\\s|丨]+"), "")
            .orEmpty()
        return collapseRepeatedText(name) to detail
    }

    private fun collapseRepeatedText(text: String): String {
        if (text.length % 2 == 0) {
            val half = text.length / 2
            if (text.substring(0, half) == text.substring(half)) return text.substring(0, half)
        }
        return text
    }

    private fun Int.toRgb(): Rgb = Rgb(
        red = android.graphics.Color.red(this),
        green = android.graphics.Color.green(this),
        blue = android.graphics.Color.blue(this)
    )

    private fun isCourseColor(pixel: Int): Boolean {
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        val brightest = max(red, max(green, blue))
        val darkest = min(red, min(green, blue))
        if (brightest < 90) return false
        val saturation = (brightest - darkest).toFloat() / brightest
        val isHeaderBlue = blue > red * 1.35f && blue > green * 1.1f
        return saturation > 0.28f && !isHeaderBlue
    }

    private fun similar(first: Rgb, second: Rgb?): Boolean {
        if (second == null) return false
        return abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue) < 90
    }
}
