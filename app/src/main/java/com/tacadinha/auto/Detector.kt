package com.tacadinha.auto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
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

    // Menor = mais rápido. Se ficar impreciso demais, teste 0.20f.
    private const val SCALE = 0.18f

    private data class Bounds(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private data class Cluster(
        val type: Int, // 1 = branca, 2 = bola colorida
        val count: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val sumX: Long,
        val sumY: Long,
        val sumR: Long,
        val sumG: Long,
        val sumB: Long
    )

    private data class BallHit(
        val ball: Ball,
        val cluster: Cluster
    )

    private fun isGreen(r: Int, g: Int, b: Int): Boolean {
        return g > 50 && g > r + 10 && g > b + 10
    }

    private fun isWhiteBall(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)

        return mx > 160 &&
                mn > 125 &&
                abs(r - g) < 48 &&
                abs(g - b) < 48 &&
                abs(r - b) < 55
    }

    private fun isColoredBall(r: Int, g: Int, b: Int): Boolean {
        if (isGreen(r, g, b)) return false
        if (isWhiteBall(r, g, b)) return false

        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val sat = if (mx > 0) (mx - mn).toFloat() / mx.toFloat() else 0f

        val colorful = sat > 0.20f && mx > 55
        val blackBall = mx < 78 && mn < 60

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

        val bounds = findTableBounds(pixels, smallW, smallH)
        val pockets = createPockets(bounds, inv)

        val mask = buildMask(pixels, smallW, smallH, bounds)
        val clusters = findClusters(mask, pixels, smallW, smallH, bounds)

        val whiteHits = clusters
            .asSequence()
            .filter { it.type == 1 }
            .mapNotNull { clusterToBallHit(it, inv, true) }
            .filter { isValidBall(it.ball) }
            .filter { isInsideTable(it.ball, bounds, inv) }
            .toList()

        val cue = whiteHits
            .maxByOrNull { cueScore(it) }
            ?.ball

        val coloredHits = clusters
            .asSequence()
            .filter { it.type == 2 }
            .mapNotNull { clusterToBallHit(it, inv, false) }
            .filter { isValidBall(it.ball) }
            .filter { isInsideTable(it.ball, bounds, inv) }
            .filterNot { isDarkCluster(it.cluster) && nearAnyPocket(it.ball, pockets, 55f) }
            .filter {
                cue == null || distance(
                    it.ball.x,
                    it.ball.y,
                    cue.x,
                    cue.y
                ) > (it.ball.r + cue.r) * 0.95f
            }
            .toList()

        val balls = coloredHits
            .map { it.ball }
            .distinctByApprox()
            .sortedByDescending { it.r }
            .take(16)

        return Triple(cue, balls, pockets)
    }

    private fun buildMask(
        pixels: IntArray,
        w: Int,
        h: Int,
        bounds: Bounds
    ): ByteArray {
        val mask = ByteArray(w * h)

        for (y in bounds.minY..bounds.maxY) {
            val row = y * w

            for (x in bounds.minX..bounds.maxX) {
                val idx = row + x
                if (idx !in pixels.indices) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                mask[idx] = when {
                    isWhiteBall(r, g, b) -> 1
                    isColoredBall(r, g, b) -> 2
                    else -> 0
                }
            }
        }

        return mask
    }

    private fun findTableBounds(
        pixels: IntArray,
        w: Int,
        h: Int
    ): Bounds {
        val rowGreen = IntArray(h)
        val colGreen = IntArray(w)

        var totalGreen = 0

        for (y in 0 until h) {
            val row = y * w

            for (x in 0 until w) {
                val p = pixels[row + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (isGreen(r, g, b)) {
                    rowGreen[y]++
                    colGreen[x]++
                    totalGreen++
                }
            }
        }

        if (totalGreen < 400) {
            return Bounds(
                (w * 0.05f).toInt(),
                (h * 0.08f).toInt(),
                (w * 0.95f).toInt(),
                (h * 0.92f).toInt()
            )
        }

        val minColGreen = max(8, (h * 0.10f).toInt())
        val minRowGreen = max(12, (w * 0.10f).toInt())

        var minX = 0
        var maxX = w - 1
        var minY = 0
        var maxY = h - 1

        for (x in 0 until w) {
            if (colGreen[x] >= minColGreen) {
                minX = x
                break
            }
        }

        for (x in w - 1 downTo 0) {
            if (colGreen[x] >= minColGreen) {
                maxX = x
                break
            }
        }

        for (y in 0 until h) {
            if (rowGreen[y] >= minRowGreen) {
                minY = y
                break
            }
        }

        for (y in h - 1 downTo 0) {
            if (rowGreen[y] >= minRowGreen) {
                maxY = y
                break
            }
        }

        if (minX >= maxX || minY >= maxY) {
            return Bounds(
                (w * 0.05f).toInt(),
                (h * 0.08f).toInt(),
                (w * 0.95f).toInt(),
                (h * 0.92f).toInt()
            )
        }

        val padX = (w * 0.012f).toInt()
        val padY = (h * 0.012f).toInt()

        return Bounds(
            (minX - padX).coerceAtLeast(0),
            (minY - padY).coerceAtLeast(0),
            (maxX + padX).coerceAtMost(w - 1),
            (maxY + padY).coerceAtMost(h - 1)
        )
    }

    private fun findClusters(
        mask: ByteArray,
        pixels: IntArray,
        w: Int,
        h: Int,
        bounds: Bounds
    ): List<Cluster> {
        val visited = BooleanArray(w * h)
        val result = mutableListOf<Cluster>()

        for (y in bounds.minY..bounds.maxY) {
            for (x in bounds.minX..bounds.maxX) {
                val idx = y * w + x

                if (idx !in mask.indices) continue
                if (visited[idx]) continue

                val type = mask[idx]
                if (type.toInt() == 0) continue

                val cluster = floodFill(
                    mask,
                    pixels,
                    visited,
                    w,
                    h,
                    x,
                    y,
                    type,
                    bounds
                )

                if (isReasonableCluster(cluster)) {
                    result.add(cluster)
                }
            }
        }

        return result
    }

    private fun floodFill(
        mask: ByteArray,
        pixels: IntArray,
        visited: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
        type: Byte,
        bounds: Bounds
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
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()

            if (idx !in mask.indices || visited[idx]) continue
            if (mask[idx] != type) continue

            val x = idx % w
            val y = idx / w

            if (x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY) {
                continue
            }

            visited[idx] = true

            val p = pixels[idx]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            count++
            sumX += x.toLong()
            sumY += y.toLong()
            sumR += r.toLong()
            sumG += g.toLong()
            sumB += b.toLong()

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
            type = type.toInt(),
            count = count,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            sumX = sumX,
            sumY = sumY,
            sumR = sumR,
            sumG = sumG,
            sumB = sumB
        )
    }

    private fun isReasonableCluster(c: Cluster): Boolean {
        if (c.count < 4) return false
        if (c.count > 1600) return false

        val cw = c.maxX - c.minX + 1
        val ch = c.maxY - c.minY + 1

        if (cw < 2 || ch < 2) return false
        if (cw > 80 || ch > 80) return false

        val ratio = cw.toFloat() / ch.toFloat().coerceAtLeast(1f)

        return ratio in 0.42f..2.35f
    }

    private fun clusterToBallHit(
        c: Cluster,
        inv: Float,
        isCue: Boolean
    ): BallHit? {
        if (c.count <= 0) return null

        val centroidX = c.sumX.toFloat() / c.count.toFloat()
        val centroidY = c.sumY.toFloat() / c.count.toFloat()

        val boxCenterX = (c.minX + c.maxX) / 2f
        val boxCenterY = (c.minY + c.maxY) / 2f

        val cx = (boxCenterX * 0.65f + centroidX * 0.35f) * inv
        val cy = (boxCenterY * 0.65f + centroidY * 0.35f) * inv

        val bw = (c.maxX - c.minX + 1) * inv
        val bh = (c.maxY - c.minY + 1) * inv

        val radiusByBox = ((bw + bh) / 4f).coerceIn(6f, 36f)
        val radiusByArea = (sqrt(c.count / Math.PI.toFloat()) * inv).coerceIn(6f, 36f)

        val r = (radiusByBox * 0.7f + radiusByArea * 0.3f).coerceIn(7f, 34f)

        return BallHit(
            Ball(cx, cy, r, isCue),
            c
        )
    }

    private fun isValidBall(ball: Ball): Boolean {
        return ball.r in 7f..34f &&
                ball.x.isFinite() &&
                ball.y.isFinite()
    }

    private fun cueScore(hit: BallHit): Float {
        val ball = hit.ball
        val c = hit.cluster

        val avgR = c.sumR.toFloat() / c.count.toFloat()
        val avgG = c.sumG.toFloat() / c.count.toFloat()
        val avgB = c.sumB.toFloat() / c.count.toFloat()

        val brightness = (avgR + avgG + avgB) / 3f
        val sizeScore = ball.r * 12f
        val brightScore = brightness * 0.8f

        return sizeScore + brightScore + c.count
    }

    private fun createPockets(
        bounds: Bounds,
        inv: Float
    ): List<Pocket> {
        val sx1 = bounds.minX * inv
        val sy1 = bounds.minY * inv
        val sx2 = bounds.maxX * inv
        val sy2 = bounds.maxY * inv
        val midX = (sx1 + sx2) / 2f

        val cornerOffset = 24f

        return listOf(
            Pocket(sx1 + cornerOffset, sy1 + cornerOffset),
            Pocket(midX, sy1 + 12f),
            Pocket(sx2 - cornerOffset, sy1 + cornerOffset),
            Pocket(sx1 + cornerOffset, sy2 - cornerOffset),
            Pocket(midX, sy2 - 12f),
            Pocket(sx2 - cornerOffset, sy2 - cornerOffset)
        )
    }

    private fun isInsideTable(
        ball: Ball,
        bounds: Bounds,
        inv: Float
    ): Boolean {
        val minX = bounds.minX * inv
        val minY = bounds.minY * inv
        val maxX = bounds.maxX * inv
        val maxY = bounds.maxY * inv

        return ball.x in minX..maxX && ball.y in minY..maxY
    }

    private fun isDarkCluster(c: Cluster): Boolean {
        val avgR = c.sumR.toFloat() / c.count.toFloat()
        val avgG = c.sumG.toFloat() / c.count.toFloat()
        val avgB = c.sumB.toFloat() / c.count.toFloat()

        return avgR < 75f && avgG < 75f && avgB < 75f
    }

    private fun nearAnyPocket(
        ball: Ball,
        pockets: List<Pocket>,
        maxDistance: Float
    ): Boolean {
        return pockets.any {
            distance(ball.x, ball.y, it.x, it.y) < maxDistance
        }
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        return hypot(x1 - x2, y1 - y2)
    }

    private fun List<Ball>.distinctByApprox(): List<Ball> {
        val result = mutableListOf<Ball>()

        for (ball in this.sortedByDescending { it.r }) {
            val duplicate = result.any {
                distance(ball.x, ball.y, it.x, it.y) < max(ball.r, it.r) * 1.15f
            }

            if (!duplicate) {
                result.add(ball)
            }
        }

        return result
    }
}
