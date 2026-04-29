package com.tacadinha.auto

import kotlin.math.*

data class Shot(
    val angleRad: Double,
    val power: Float,
    val confidence: Float,
    val willScore: Boolean
)

object ShotCalculator {

    fun bestShot(cue: Ball, balls: List<Ball>, pockets: List<Pocket>): Shot? {
        if (balls.isEmpty()) return null

        val candidates = mutableListOf<Shot>()

        for (target in balls) {
            for (pocket in pockets) {
                val direct = evaluateDirect(cue, target, pocket, balls)
                if (direct != null) candidates.add(direct)

                val bank = evaluateBank(cue, target, pocket, balls, pockets)
                if (bank != null) candidates.add(bank)
            }
        }

        return candidates
            .filter { it.willScore }
            .maxByOrNull { it.confidence }
            ?: candidates.maxByOrNull { it.confidence }
    }

    private fun evaluateDirect(
        cue: Ball, target: Ball, pocket: Pocket, all: List<Ball>
    ): Shot? {
        val tpx = pocket.x - target.x
        val tpy = pocket.y - target.y
        val tpDist = hypot(tpx, tpy)
        if (tpDist < 5f) return null

        val nx = tpx / tpDist; val ny = tpy / tpDist
        val gx = target.x - nx * (cue.r + target.r)
        val gy = target.y - ny * (cue.r + target.r)
        val cgx = gx - cue.x; val cgy = gy - cue.y
        val cgDist = hypot(cgx, cgy)
        if (cgDist < 5f) return null

        val angle = atan2(cgy.toDouble(), cgx.toDouble())

        val cueBlocked = all.filter { it != target }.any { o ->
            segDist(o.x, o.y, cue.x, cue.y, gx, gy) < (cue.r + o.r) * 0.80f
        }
        if (cueBlocked) return null

        val targetBlocked = all.filter { it != target }.any { o ->
            segDist(o.x, o.y, target.x, target.y, pocket.x, pocket.y) < (target.r + o.r) * 0.80f
        }
        if (targetBlocked) return null

        // Scores
        val distScore = (3500f - (cgDist + tpDist)).coerceAtLeast(0f) / 3500f
        val cutDot = ((cgx / cgDist) * nx + (cgy / cgDist) * ny).toFloat().coerceIn(-1f, 1f)
        val cutScore = (cutDot + 1f) / 2f
        val cueDistScore = (1200f - cgDist).coerceAtLeast(0f) / 1200f
        val nearbyBalls = all.filter { it != target }
            .count { hypot(it.x - target.x, it.y - target.y) < target.r * 4 }
        val clusterScore = (1f - nearbyBalls * 0.15f).coerceIn(0f, 1f)

        // Segurança: estima onde a branca vai parar
        val estX = cue.x + cos(angle).toFloat() * 150f
        val estY = cue.y + sin(angle).toFloat() * 150f
        val nearPocket = all.any { o ->
            o != target && hypot(estX - o.x, estY - o.y) < cue.r * 2.5f
        }
        val safeScore = if (nearPocket) 0.2f else 0.9f

        val confidence = distScore * 0.28f + cutScore * 0.22f +
                cueDistScore * 0.15f + safeScore * 0.20f + clusterScore * 0.15f

        val power = (cgDist / 850f).coerceIn(0.22f, 0.90f)

        return Shot(angle, power, confidence, willScore = true)
    }

    private fun evaluateBank(
        cue: Ball, target: Ball, pocket: Pocket,
        all: List<Ball>, pockets: List<Pocket>
    ): Shot? {
        val tableMinX = pockets.minOfOrNull { it.x }?.plus(40f) ?: return null
        val tableMaxX = pockets.maxOfOrNull { it.x }?.minus(40f) ?: return null
        val midX = (tableMinX + tableMaxX) / 2f
        val mirroredX = if (pocket.x < midX)
            tableMinX - (pocket.x - tableMinX)
        else
            tableMaxX + (tableMaxX - pocket.x)

        val reflectPocket = Pocket(mirroredX.coerceIn(tableMinX, tableMaxX), pocket.y)
        val shot = evaluateDirect(cue, target, reflectPocket, all) ?: return null
        return shot.copy(confidence = shot.confidence * 0.62f)
    }

    private fun segDist(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val dx = x2 - x1; val dy = y2 - y1; val l2 = dx * dx + dy * dy
        if (l2 < 0.001f) return hypot(px - x1, py - y1)
        val t = ((px - x1) * dx + (py - y1) * dy).div(l2).coerceIn(0f, 1f)
        return hypot(px - x1 - t * dx, py - y1 - t * dy)
    }
}
