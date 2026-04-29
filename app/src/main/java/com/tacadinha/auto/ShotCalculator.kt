package com.tacadinha.pro

import kotlin.math.*

data class Shot(
    val angleRad: Double,
    val targetBall: Ball,
    val pocket: Pocket,
    val power: Float,
    val confidence: Float,
    val reason: String = ""
)

object ShotCalculator {

    private const val W_DIRECT   = 0.25f
    private const val W_ANGLE    = 0.20f
    private const val W_CUT      = 0.20f
    private const val W_CUE_DIST = 0.10f
    private const val W_SAFE     = 0.15f
    private const val W_CLUSTER  = 0.10f

    fun bestShot(state: GameState): Shot? {
        val cue = state.cueBall ?: return null
        val allBalls = state.myBalls + state.opponentBalls + listOfNotNull(state.blackBall)
        val targets = if (state.myBalls.isEmpty()) listOfNotNull(state.blackBall) else state.myBalls
        if (targets.isEmpty()) return null

        val candidates = mutableListOf<Shot>()
        for (target in targets) {
            for (pocket in state.pockets) {
                evaluateDirect(cue, target, pocket, allBalls, state)?.let { candidates.add(it) }
                evaluateBank(cue, target, pocket, allBalls, state)?.let { candidates.add(it) }
            }
        }
        return candidates.maxByOrNull { it.confidence }
    }

    private fun evaluateDirect(cue: Ball, target: Ball, pocket: Pocket, allBalls: List<Ball>, state: GameState): Shot? {
        val tpx = pocket.x - target.x; val tpy = pocket.y - target.y
        val tpDist = hypot(tpx, tpy); if (tpDist < 5f) return null
        val nx = tpx / tpDist; val ny = tpy / tpDist
        val gx = target.x - nx * (cue.r + target.r); val gy = target.y - ny * (cue.r + target.r)
        val cgx = gx - cue.x; val cgy = gy - cue.y
        val cgDist = hypot(cgx, cgy); if (cgDist < 5f) return null
        val angle = atan2(cgy.toDouble(), cgx.toDouble())

        if (allBalls.filter { it != target }.any { o -> segDist(o.x,o.y,cue.x,cue.y,gx,gy) < (cue.r+o.r)*0.80f }) return null
        if (allBalls.filter { it != target }.any { o -> segDist(o.x,o.y,target.x,target.y,pocket.x,pocket.y) < (target.r+o.r)*0.80f }) return null

        val distScore = (3500f - (cgDist + tpDist)).coerceAtLeast(0f) / 3500f
        val entryDot = (-(tpx/tpDist)*nx + -(tpy/tpDist)*ny).coerceIn(-1f,1f)
        val angleScore = (entryDot + 1f) / 2f
        val cutDot = ((cgx/cgDist)*nx + (cgy/cgDist)*ny).toFloat().coerceIn(-1f,1f)
        val cutScore = (cutDot + 1f) / 2f
        val cueDistScore = (1200f - cgDist).coerceAtLeast(0f) / 1200f
        val safeScore = estimateSafetyScore(cue, angle, allBalls, state)
        val nearbyBalls = allBalls.filter { it != target }.count { hypot(it.x-target.x, it.y-target.y) < target.r*4 }
        val clusterScore = (1f - nearbyBalls * 0.15f).coerceIn(0f, 1f)

        val confidence = distScore*W_DIRECT + angleScore*W_ANGLE + cutScore*W_CUT +
                         cueDistScore*W_CUE_DIST + safeScore*W_SAFE + clusterScore*W_CLUSTER
        val power = (cgDist / 850f).coerceIn(0.22f, 0.90f)
        return Shot(angle, target, pocket, power, confidence, "direto")
    }

    private fun evaluateBank(cue: Ball, target: Ball, pocket: Pocket, allBalls: List<Ball>, state: GameState): Shot? {
        val tableMinX = state.pockets.minOfOrNull { it.x }?.plus(40f) ?: return null
        val tableMaxX = state.pockets.maxOfOrNull { it.x }?.minus(40f) ?: return null
        val midX = (tableMinX + tableMaxX) / 2f
        val mirroredX = if (pocket.x < midX) tableMinX-(pocket.x-tableMinX) else tableMaxX+(tableMaxX-pocket.x)
        val reflectPocket = Pocket(mirroredX.coerceIn(tableMinX, tableMaxX), pocket.y)
        val shot = evaluateDirect(cue, target, reflectPocket, allBalls, state) ?: return null
        return shot.copy(confidence = shot.confidence * 0.65f, reason = "tabela")
    }

    private fun estimateSafetyScore(cue: Ball, angleRad: Double, allBalls: List<Ball>, state: GameState): Float {
        val estX = cue.x + cos(angleRad).toFloat() * 150f
        val estY = cue.y + sin(angleRad).toFloat() * 150f
        if (state.pockets.any { p -> hypot(estX-p.x, estY-p.y) < 80f }) return 0.1f
        val pMinX = state.pockets.minOfOrNull { it.x } ?: 0f
        val pMaxX = state.pockets.maxOfOrNull { it.x } ?: 1000f
        val pMinY = state.pockets.minOfOrNull { it.y } ?: 0f
        val pMaxY = state.pockets.maxOfOrNull { it.y } ?: 1000f
        if (estX < pMinX+60f || estX > pMaxX-60f || estY < pMinY+60f || estY > pMaxY-60f) return 0.3f
        if (state.opponentBalls.any { o -> hypot(estX-o.x, estY-o.y) < cue.r*3 }) return 0.4f
        return 0.9f
    }

    private fun segDist(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx=x2-x1; val dy=y2-y1; val l2=dx*dx+dy*dy
        if (l2<0.001f) return hypot(px-x1,py-y1)
        val t=((px-x1)*dx+(py-y1)*dy).div(l2).coerceIn(0f,1f)
        return hypot(px-x1-t*dx,py-y1-t*dy)
    }
}
