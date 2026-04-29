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

    private const val MAX_CUT_ANGLE_DEG = 52f
    private const val EASY_CUT_ANGLE_DEG = 22f
    private const val PATH_CLEARANCE_FACTOR = 1.12f
    private const val GHOST_FACTOR = 0.98f

    fun bestShot(
        cue: Ball,
        balls: List<Ball>,
        pockets: List<Pocket>
    ): Shot? {
        if (balls.isEmpty() || pockets.isEmpty()) return null

        val cleanBalls = balls
            .filter { it.r in 7f..36f }
            .filter {
                distance(it.x, it.y, cue.x, cue.y) > cue.r + it.r + 3f
            }

        if (cleanBalls.isEmpty()) return null

        val candidates = mutableListOf<Shot>()

        for (target in cleanBalls) {
            for (pocket in pockets) {
                val shot = evaluate(
                    cue = cue,
                    target = target,
                    pocket = pocket,
                    allBalls = cleanBalls,
                    pockets = pockets
                ) ?: continue

                if (shot.willScore) {
                    candidates.add(shot)
                }
            }
        }

        return candidates
            .sortedByDescending { it.score }
            .firstOrNull()
    }

    private fun evaluate(
        cue: Ball,
        target: Ball,
        pocket: Pocket,
        allBalls: List<Ball>,
        pockets: List<Pocket>
    ): Shot? {
        val table = tableBoundsFromPockets(pockets)

        val targetToPocketDist = distance(
            target.x,
            target.y,
            pocket.x,
            pocket.y
        )

        if (targetToPocketDist < target.r * 2.2f) return null

        val targetPocketAngle = atan2(
            (pocket.y - target.y).toDouble(),
            (pocket.x - target.x).toDouble()
        )

        val ux = cos(targetPocketAngle).toFloat()
        val uy = sin(targetPocketAngle).toFloat()

        val ghostDistance = (cue.r + target.r) * GHOST_FACTOR

        val ghostX = target.x - ux * ghostDistance
        val ghostY = target.y - uy * ghostDistance

        if (!insideTable(ghostX, ghostY, table, 10f)) return null

        val cueToGhostDist = distance(
            cue.x,
            cue.y,
            ghostX,
            ghostY
        )

        if (cueToGhostDist < cue.r * 2f) return null

        val cueToGhostAngle = atan2(
            (ghostY - cue.y).toDouble(),
            (ghostX - cue.x).toDouble()
        )

        val cueToTargetAngle = atan2(
            (target.y - cue.y).toDouble(),
            (target.x - cue.x).toDouble()
        )

        val cutAngle = angleDiff(
            cueToTargetAngle,
            targetPocketAngle
        )

        val maxCut = Math.toRadians(MAX_CUT_ANGLE_DEG.toDouble())

        if (cutAngle > maxCut) return null

        val cuePathBlocked = isPathBlocked(
            startX = cue.x,
            startY = cue.y,
            endX = ghostX,
            endY = ghostY,
            ignore = target,
            balls = allBalls,
            clearance = max(cue.r, target.r) * PATH_CLEARANCE_FACTOR
        )

        if (cuePathBlocked) return null

        val targetPathBlocked = isPathBlocked(
            startX = target.x,
            startY = target.y,
            endX = pocket.x,
            endY = pocket.y,
            ignore = target,
            balls = allBalls,
            clearance = target.r * PATH_CLEARANCE_FACTOR
        )

        if (targetPathBlocked) return null

        val pocketQuality = pocketQualityScore(
            target = target,
            pocket = pocket,
            pockets = pockets
        )

        val cutDeg = Math.toDegrees(cutAngle).toFloat()

        val straightScore = if (cutDeg <= EASY_CUT_ANGLE_DEG) {
            3200f
        } else {
            val cutRatio = (cutDeg / MAX_CUT_ANGLE_DEG).coerceIn(0f, 1f)
            3200f * (1f - cutRatio)
        }

        val distanceScore =
            2600f -
                    cueToGhostDist * 0.85f -
                    targetToPocketDist * 0.55f

        val ghostLineScore = lineAgreementScore(
            cue.x,
            cue.y,
            ghostX,
            ghostY,
            target.x,
            target.y,
            pocket.x,
            pocket.y
        )

        val railPenalty = railPenalty(
            ghostX,
            ghostY,
            table
        )

        val score =
            straightScore +
                    distanceScore +
                    pocketQuality +
                    ghostLineScore -
                    railPenalty

        if (score < 400f) return null

        return Shot(
            angleRad = normalizeAngle(cueToGhostAngle),
            targetBall = target,
            pocket = pocket,
            willScore = true,
            ghostX = ghostX,
            ghostY = ghostY,
            score = score
        )
    }

    private data class TableBounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    )

    private fun tableBoundsFromPockets(pockets: List<Pocket>): TableBounds {
        val minX = pockets.minOf { it.x } - 35f
        val maxX = pockets.maxOf { it.x } + 35f
        val minY = pockets.minOf { it.y } - 35f
        val maxY = pockets.maxOf { it.y } + 35f

        return TableBounds(minX, minY, maxX, maxY)
    }

    private fun insideTable(
        x: Float,
        y: Float,
        table: TableBounds,
        margin: Float
    ): Boolean {
        return x >= table.minX + margin &&
                x <= table.maxX - margin &&
                y >= table.minY + margin &&
                y <= table.maxY - margin
    }

    private fun isPathBlocked(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        ignore: Ball,
        balls: List<Ball>,
        clearance: Float
    ): Boolean {
        for (ball in balls) {
            if (sameBall(ball, ignore)) continue

            val d = segDist(
                ball.x,
                ball.y,
                startX,
                startY,
                endX,
                endY
            )

            if (d < ball.r + clearance) {
                return true
            }
        }

        return false
    }

    private fun pocketQualityScore(
        target: Ball,
        pocket: Pocket,
        pockets: List<Pocket>
    ): Float {
        val corner = isCornerPocket(pocket, pockets)
        val base = if (corner) 850f else 650f

        val dist = distance(
            target.x,
            target.y,
            pocket.x,
            pocket.y
        )

        val distanceBonus = (900f - dist * 0.45f).coerceAtLeast(0f)

        return base + distanceBonus
    }

    private fun lineAgreementScore(
        cueX: Float,
        cueY: Float,
        ghostX: Float,
        ghostY: Float,
        targetX: Float,
        targetY: Float,
        pocketX: Float,
        pocketY: Float
    ): Float {
        val a1 = atan2(
            (ghostY - cueY).toDouble(),
            (ghostX - cueX).toDouble()
        )

        val a2 = atan2(
            (pocketY - targetY).toDouble(),
            (pocketX - targetX).toDouble()
        )

        val diff = angleDiff(a1, a2)
        val maxDiff = Math.toRadians(80.0)

        val ratio = (diff / maxDiff).toFloat().coerceIn(0f, 1f)

        return 1000f * (1f - ratio)
    }

    private fun railPenalty(
        x: Float,
        y: Float,
        table: TableBounds
    ): Float {
        val left = abs(x - table.minX)
        val right = abs(table.maxX - x)
        val top = abs(y - table.minY)
        val bottom = abs(table.maxY - y)

        val minDist = minOf(left, right, top, bottom)

        return when {
            minDist < 18f -> 900f
            minDist < 35f -> 420f
            else -> 0f
        }
    }

    private fun sameBall(
        a: Ball,
        b: Ball
    ): Boolean {
        return distance(a.x, a.y, b.x, b.y) < max(a.r, b.r) * 0.75f
    }

    private fun isCornerPocket(
        pocket: Pocket,
        pockets: List<Pocket>
    ): Boolean {
        val minX = pockets.minOf { it.x }
        val maxX = pockets.maxOf { it.x }
        val minY = pockets.minOf { it.y }
        val maxY = pockets.maxOf { it.y }

        val nearLeft = abs(pocket.x - minX) < 45f
        val nearRight = abs(pocket.x - maxX) < 45f
        val nearTop = abs(pocket.y - minY) < 45f
        val nearBottom = abs(pocket.y - maxY) < 45f

        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    private fun angleDiff(
        a: Double,
        b: Double
    ): Double {
        var diff = abs(a - b)

        while (diff > Math.PI) {
            diff = abs(diff - Math.PI * 2.0)
        }

        return diff
    }

    private fun normalizeAngle(
        angle: Double
    ): Double {
        var a = angle

        while (a < -Math.PI) {
            a += Math.PI * 2.0
        }

        while (a > Math.PI) {
            a -= Math.PI * 2.0
        }

        return a
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
