package com.tacadinha.auto

import kotlin.math.*

data class Shot(
    val angleRad: Double,
    val targetBall: Ball,
    val pocket: Pocket,
    val willScore: Boolean,
    val ghostX: Float = 0f,
    val ghostY: Float = 0f,
    val score: Float = 0f
)

object ShotCalculator {

    fun bestShot(
        cue: Ball,
        balls: List<Ball>,
        pockets: List<Pocket>
    ): Shot? {
        if (balls.isEmpty() || pockets.isEmpty()) return null

        val candidates = mutableListOf<Shot>()

        for (target in balls) {
            if (distance(cue.x, cue.y, target.x, target.y) < cue.r + target.r + 4f) {
                continue
            }

            for (pocket in pockets) {
                val shot = evaluate(cue, target, pocket, balls, pockets) ?: continue

                if (shot.willScore) {
                    candidates.add(shot)
                }
            }
        }

        return candidates.maxByOrNull { it.score }
    }

    private fun evaluate(
        cue: Ball,
        target: Ball,
        pocket: Pocket,
        allBalls: List<Ball>,
        pockets: List<Pocket>
    ): Shot? {
        val tableMinX = pockets.minOf { it.x } - 25f
        val tableMaxX = pockets.maxOf { it.x } + 25f
        val tableMinY = pockets.minOf { it.y } - 25f
        val tableMaxY = pockets.maxOf { it.y } + 25f

        val targetToPocketX = pocket.x - target.x
        val targetToPocketY = pocket.y - target.y
        val targetToPocketDist = hypot(targetToPocketX, targetToPocketY)

        if (targetToPocketDist < 30f) return null

        val ux = targetToPocketX / targetToPocketDist
        val uy = targetToPocketY / targetToPocketDist

        val contactDistance = (cue.r + target.r).coerceIn(18f, 48f)

        val ghostX = target.x - ux * contactDistance
        val ghostY = target.y - uy * contactDistance

        if (ghostX !in tableMinX..tableMaxX || ghostY !in tableMinY..tableMaxY) {
            return null
        }

        val cueToGhostDist = distance(cue.x, cue.y, ghostX, ghostY)

        if (cueToGhostDist < 20f) return null

        val aimAngle = atan2(
            (ghostY - cue.y).toDouble(),
            (ghostX - cue.x).toDouble()
        )

        val cueToTargetAngle = atan2(
            (target.y - cue.y).toDouble(),
            (target.x - cue.x).toDouble()
        )

        val targetToPocketAngle = atan2(
            targetToPocketY.toDouble(),
            targetToPocketX.toDouble()
        )

        val cutAngle = angleDiff(cueToTargetAngle, targetToPocketAngle)

        if (cutAngle > Math.toRadians(75.0)) {
            return null
        }

        val pathCueBlocked = allBalls.any { other ->
            if (sameBall(other, target)) {
                false
            } else {
                segDist(
                    other.x,
                    other.y,
                    cue.x,
                    cue.y,
                    ghostX,
                    ghostY
                ) < (cue.r + other.r) * 0.92f
            }
        }

        if (pathCueBlocked) {
            return null
        }

        val pathTargetBlocked = allBalls.any { other ->
            if (sameBall(other, target)) {
                false
            } else {
                segDist(
                    other.x,
                    other.y,
                    target.x,
                    target.y,
                    pocket.x,
                    pocket.y
                ) < (target.r + other.r) * 0.92f
            }
        }

        if (pathTargetBlocked) {
            return null
        }

        val straightBonus = (1.0 - cutAngle / Math.toRadians(75.0)).toFloat()
        val distancePenalty = cueToGhostDist * 0.45f + targetToPocketDist * 0.25f

        val pocketCenterBonus = when {
            isCornerPocket(pocket, pockets) -> 120f
            else -> 80f
        }

        val score =
            5000f +
            straightBonus * 2500f +
            pocketCenterBonus -
            distancePenalty

        return Shot(
            angleRad = normalizeAngle(aimAngle),
            targetBall = target,
            pocket = pocket,
            willScore = true,
            ghostX = ghostX,
            ghostY = ghostY,
            score = score
        )
    }

    private fun sameBall(a: Ball, b: Ball): Boolean {
        return distance(a.x, a.y, b.x, b.y) < max(a.r, b.r) * 0.8f
    }

    private fun isCornerPocket(
        pocket: Pocket,
        pockets: List<Pocket>
    ): Boolean {
        val minX = pockets.minOf { it.x }
        val maxX = pockets.maxOf { it.x }
        val minY = pockets.minOf { it.y }
        val maxY = pockets.maxOf { it.y }

        val nearLeft = abs(pocket.x - minX) < 40f
        val nearRight = abs(pocket.x - maxX) < 40f
        val nearTop = abs(pocket.y - minY) < 40f
        val nearBottom = abs(pocket.y - maxY) < 40f

        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var diff = abs(a - b)
        while (diff > Math.PI) {
            diff -= Math.PI * 2.0
        }
        return abs(diff)
    }

    private fun normalizeAngle(angle: Double): Double {
        var a = angle
        while (a < -Math.PI) a += Math.PI * 2.0
        while (a > Math.PI) a -= Math.PI * 2.0
        return a
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        return hypot(x1 - x2, y1 - y2)
    }

    private fun segDist(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val l2 = dx * dx + dy * dy

        if (l2 < 0.001f) {
            return distance(px, py, x1, y1)
        }

        val t = (((px - x1) * dx + (py - y1) * dy) / l2)
            .coerceIn(0f, 1f)

        val projX = x1 + t * dx
        val projY = y1 + t * dy

        return distance(px, py, projX, projY)
    }
}
