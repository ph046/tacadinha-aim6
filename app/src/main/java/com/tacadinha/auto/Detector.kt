package com.tacadinha.auto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Ball(
    val x: Float,
    val y: Float,
    val r: Float,
    val isCue: Boolean = false
)

data class Pocket(
    val x: Float,
    val y: Float
)

object Detector {

    private const val SCALE = 0.25f

    private data class Cluster(
        val count: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val sumX: Long,
        val sumY: Long
    )

    private fun isGreen(r: Int, g: Int, b: Int): Boolean {
        return g > 55 && g > r + 12 && g > b + 12
    }

    private fun isDarkPocket(r: Int, g: Int, b: Int): Boolean {
        return r < 45 && g < 45 && b < 45
    }

    private fun isWhiteBall(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        return mx > 165 && mn > 135 && abs(r - g) < 45 && abs(g - b) < 45
    }

    private fun isColoredBall(r: Int, g: Int, b: Int): Boolean {
        if (isGreen(r, g, b)) return false
        if (isWhiteBall(r, g, b)) return false

        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val sat = if (mx > 0) (mx - mn).toFloat() / mx.toFloat() else 0f

        val colorful = sat > 0.18f && mx > 45
        val blackBall = mx < 80 && mn < 60

        return colorful || blackBall
    }

    fun analyze(
        bmp: Bitmap,
        screenW: Int,
        screenH: Int
    ): Triple<Ball?, List<Ball>, List<Pocket>> {

        val smallW = (bmp.width * SCALE).toInt().coerceAtLeast(1)
        val smallH = (bmp.height * SCALE).toInt().coerceAtLeast(1)

        val small = Bitmap.createScaledBitmap(bmp, smallW, smallH, false)
        val pixels = IntArray(smallW * smallH)

        small.getPixels(
            pixels,
            0,
            smallW,
            0,
            0,
            smallW,
            smallH
        )

        small.recycle()

        val inv = 1f / SCALE

        val tableBounds = findTableBounds(pixels, smallW, smallH)
        val minTX = tableBounds[0]
        val minTY = tableBounds[1]
        val maxTX = tableBounds[2]
        val maxTY = tableBounds[3]

        val whiteClusters = findClusters(
            pixels,
            smallW,
            smallH,
            minTX,
            minTY,
            maxTX,
            maxTY
        ) { r, g, b ->
            isWhiteBall(r, g, b)
        }

        val colorClusters = findClusters(
            pixels,
            smallW,
            smallH,
            minTX,
            minTY,
            maxTX,
            maxTY
        ) { r, g, b ->
            isColoredBall(r, g, b)
        }

        val cue = whiteClusters
            .mapNotNull { clusterToBall(it, inv, true) }
            .filter { isInsideTable(it.x, it.y, minTX * inv, minTY * inv, maxTX * inv, maxTY * inv) }
            .maxByOrNull { it.r }

        val balls = colorClusters
            .mapNotNull { clusterToBall(it, inv, false) }
            .filter { isInsideTable(it.x, it.y, minTX * inv, minTY * inv, maxTX * inv, maxTY * inv) }
            .filter { cue == null || distance(it.x, it.y, cue.x, cue.y) > it.r + cue.r }
            .distinctByApprox()

        val sx1 = minTX * inv
        val sy1 = minTY * inv
        val sx2 = maxTX * inv
        val sy2 = maxTY * inv
        val midX = (sx1 + sx2) / 2f

        val pocketOffset = 22f

        val pockets = listOf(
            Pocket(sx1 + pocketOffset, sy1 + pocketOffset),
            Pocket(midX, sy1 + 10f),
            Pocket(sx2 - pocketOffset, sy1 + pocketOffset),
            Pocket(sx1 + pocketOffset, sy2 - pocketOffset),
            Pocket(midX, sy2 - 10f),
            Pocket(sx2 - pocketOffset, sy2 - pocketOffset)
        )

        return Triple(cue, balls, pockets)
    }

    private fun findTableBounds(
        pixels: IntArray,
        w: Int,
        h: Int
    ): IntArray {
        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0
        var greenCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (isGreen(r, g, b)) {
                    greenCount++

                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (greenCount < 500 || minX >= maxX || minY >= maxY) {
            return intArrayOf(
                (w * 0.05f).toInt(),
                (h * 0.08f).toInt(),
                (w * 0.95f).toInt(),
                (h * 0.92f).toInt()
            )
        }

        val padX = (w * 0.015f).toInt()
        val padY = (h * 0.015f).toInt()

        minX = (minX - padX).coerceAtLeast(0)
        minY = (minY - padY).coerceAtLeast(0)
        maxX = (maxX + padX).coerceAtMost(w - 1)
        maxY = (maxY + padY).coerceAtMost(h - 1)

        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun findClusters(
        pixels: IntArray,
        w: Int,
        h: Int,
        minTX: Int,
        minTY: Int,
        maxTX: Int,
        maxTY: Int,
        matcher: (Int, Int, Int) -> Boolean
    ): List<Cluster> {
        val visited = BooleanArray(w * h)
        val clusters = mutableListOf<Cluster>()

        val minSize = 5
        val maxSize = 2200

        for (y in minTY..maxTY) {
            for (x in minTX..maxTX) {
                val idx = y * w + x
                if (idx !in pixels.indices || visited[idx]) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (!matcher(r, g, b)) continue

                val cluster = floodFill(
                    pixels,
                    visited,
                    w,
                    h,
                    x,
                    y,
                    minTX,
                    minTY,
                    maxTX,
                    maxTY,
                    matcher
                )

                if (cluster.count in minSize..maxSize) {
                    val cw = cluster.maxX - cluster.minX + 1
                    val ch = cluster.maxY - cluster.minY + 1

                    val ratio = cw.toFloat() / ch.toFloat().coerceAtLeast(1f)

                    if (ratio in 0.45f..2.2f) {
                        clusters.add(cluster)
                    }
                }
            }
        }

        return clusters
    }

    private fun floodFill(
        pixels: IntArray,
        visited: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
        minTX: Int,
        minTY: Int,
        maxTX: Int,
        maxTY: Int,
        matcher: (Int, Int, Int) -> Boolean
    ): Cluster {
        val queue = ArrayDeque<Int>()
        queue.add(startY * w + startX)

        var count = 0
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY
        var sumX = 0L
        var sumY = 0L

        while (queue.isNotEmpty() && count < 3000) {
            val idx = queue.removeFirst()

            if (idx !in pixels.indices || visited[idx]) continue

            val x = idx % w
            val y = idx / w

            if (x < minTX || x > maxTX || y < minTY || y > maxTY) continue

            val p = pixels[idx]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            if (!matcher(r, g, b)) continue

            visited[idx] = true

            count++
            sumX += x.toLong()
            sumY += y.toLong()

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            if (x + 1 < w) queue.add(idx + 1)
            if (x - 1 >= 0) queue.add(idx - 1)
            if (y + 1 < h) queue.add(idx + w)
            if (y - 1 >= 0) queue.add(idx - w)
        }

        return Cluster(
            count = count,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            sumX = sumX,
            sumY = sumY
        )
    }

    private fun clusterToBall(
        c: Cluster,
        inv: Float,
        isCue: Boolean
    ): Ball? {
        if (c.count <= 0) return null

        val cx = (c.sumX.toFloat() / c.count.toFloat()) * inv
        val cy = (c.sumY.toFloat() / c.count.toFloat()) * inv

        val w = (c.maxX - c.minX + 1) * inv
        val h = (c.maxY - c.minY + 1) * inv

        val radiusByBox = ((w + h) / 4f).coerceIn(7f, 34f)
        val radiusByArea = (sqrt(c.count / Math.PI.toFloat()) * inv).coerceIn(7f, 34f)

        val r = ((radiusByBox + radiusByArea) / 2f).coerceIn(8f, 32f)

        return Ball(cx, cy, r, isCue)
    }

    private fun isInsideTable(
        x: Float,
        y: Float,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float
    ): Boolean {
        return x in minX..maxX && y in minY..maxY
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun List<Ball>.distinctByApprox(): List<Ball> {
        val result = mutableListOf<Ball>()

        for (ball in this.sortedByDescending { it.r }) {
            val duplicate = result.any {
                distance(ball.x, ball.y, it.x, it.y) < max(ball.r, it.r) * 1.2f
            }

            if (!duplicate) {
                result.add(ball)
            }
        }

        return result
    }
}
