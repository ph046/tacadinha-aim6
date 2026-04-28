package com.tacadinha.auto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

data class Ball(val x: Float, val y: Float, val r: Float, val isCue: Boolean = false)
data class Pocket(val x: Float, val y: Float)

object Detector {
    private fun isGreen(r: Int, g: Int, b: Int) = g > 80 && g > r + 20 && g > b + 20
    private fun isWhite(r: Int, g: Int, b: Int) = r > 185 && g > 185 && b > 185
    private fun isBall(r: Int, g: Int, b: Int): Boolean {
        if (isWhite(r, g, b) || isGreen(r, g, b)) return false
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        val sat = if (mx > 0) (mx - mn).toFloat() / mx else 0f
        return (sat > 0.22f && mx > 55) || (mx < 65 && mn < 45)
    }

    fun analyze(bmp: Bitmap, screenW: Int, screenH: Int): Triple<Ball?, List<Ball>, List<Pocket>> {
        val scale = 0.2f
        val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, false)
        val inv = 1f / scale
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val visited = BooleanArray(sw * sh)
        val whiteClusters = mutableListOf<List<Int>>()
        val colorClusters = mutableListOf<List<Int>>()
        var minGX = sw; var maxGX = 0; var minGY = sh; var maxGY = 0

        for (idx in pixels.indices) {
            val p = pixels[idx]; val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val ix = idx % sw; val iy = idx / sw
            if (isGreen(r, g, b)) {
                if (ix < minGX) minGX = ix; if (ix > maxGX) maxGX = ix
                if (iy < minGY) minGY = iy; if (iy > maxGY) maxGY = iy
            }
            if (visited[idx]) continue
            val white = isWhite(r, g, b); val ball = isBall(r, g, b)
            if (!white && !ball) continue
            if (!isOnTable(pixels, ix, iy, sw, sh)) continue
            val cluster = mutableListOf<Int>(); val q = ArrayDeque<Int>(); q.add(idx)
            val tr = r; val tg = g; val tb = b
            while (q.isNotEmpty() && cluster.size < 500) {
                val cur = q.removeFirst()
                if (cur < 0 || cur >= pixels.size || visited[cur]) continue
                val cp = pixels[cur]; val cr = Color.red(cp); val cg = Color.green(cp); val cb = Color.blue(cp)
                val matches = if (white) isWhite(cr, cg, cb)
                              else isBall(cr, cg, cb) && abs(cr-tr)<85 && abs(cg-tg)<85 && abs(cb-tb)<85
                if (!matches) continue
                visited[cur] = true; cluster.add(cur)
                val cx = cur % sw; val cy = cur / sw
                if (cx+1<sw) q.add(cur+1); if (cx-1>=0) q.add(cur-1)
                if (cy+1<sh) q.add(cur+sw); if (cy-1>=0) q.add(cur-sw)
            }
            if (cluster.size >= 6) { if (white) whiteClusters.add(cluster) else colorClusters.add(cluster) }
        }

        fun toball(c: List<Int>, isCue: Boolean): Ball {
            val cx = c.map { it % sw }.average().toFloat() * inv
            val cy = c.map { it / sw }.average().toFloat() * inv
            return Ball(cx, cy, (sqrt(c.size / Math.PI.toFloat()) * inv).coerceIn(9f, 20f), isCue)
        }

        val cue = whiteClusters.maxByOrNull { it.size }?.let { toball(it, true) }
        val balls = colorClusters.map { toball(it, false) }
        val sx1 = minGX*inv; val sx2 = maxGX*inv; val sy1 = minGY*inv; val sy2 = maxGY*inv; val mx = (sx1+sx2)/2f
        val pockets = listOf(Pocket(sx1+18f,sy1+18f),Pocket(mx,sy1+8f),Pocket(sx2-18f,sy1+18f),Pocket(sx1+18f,sy2-18f),Pocket(mx,sy2-8f),Pocket(sx2-18f,sy2-18f))
        return Triple(cue, balls, pockets)
    }

    private fun isOnTable(pixels: IntArray, x: Int, y: Int, w: Int, h: Int): Boolean {
        var gc = 0
        for (dy in -3..3 step 2) for (dx in -3..3 step 2) {
            val nx = x+dx; val ny = y+dy
            if (nx<0||ny<0||nx>=w||ny>=h) continue
            val p = pixels[ny*w+nx]
            if (isGreen(Color.red(p), Color.green(p), Color.blue(p))) gc++
        }
        return gc >= 2
    }
}
