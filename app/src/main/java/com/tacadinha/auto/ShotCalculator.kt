package com.tacadinha.auto

import kotlin.math.*

data class Shot(val angleRad: Double, val targetBall: Ball, val pocket: Pocket, val willScore: Boolean)

object ShotCalculator {
    fun bestShot(cue: Ball, balls: List<Ball>, pockets: List<Pocket>): Shot? {
        val candidates = mutableListOf<Pair<Float, Shot>>()
        for (target in balls) for (pocket in pockets) {
            val shot = evaluate(cue, target, pocket, balls) ?: continue
            val dist = hypot(cue.x - target.x, cue.y - target.y)
            if (shot.willScore) candidates.add((1_000_000f - dist) to shot)
        }
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun evaluate(cue: Ball, target: Ball, pocket: Pocket, all: List<Ball>): Shot? {
        val tpx = pocket.x-target.x; val tpy = pocket.y-target.y
        val tpd = hypot(tpx, tpy); if (tpd < 5f) return null
        val nx = tpx/tpd; val ny = tpy/tpd
        val gx = target.x - nx*(cue.r+target.r); val gy = target.y - ny*(cue.r+target.r)
        val cgd = hypot(gx-cue.x, gy-cue.y); if (cgd < 5f) return null
        val angle = atan2((gy-cue.y).toDouble(), (gx-cue.x).toDouble())
        val blocked = all.any { o -> if (o==target) false else segDist(o.x,o.y,cue.x,cue.y,gx,gy)<(cue.r+o.r)*0.85f }
        val tBlocked = all.any { o -> if (o==target) false else segDist(o.x,o.y,target.x,target.y,pocket.x,pocket.y)<(target.r+o.r)*0.85f }
        return Shot(angle, target, pocket, !blocked && !tBlocked)
    }

    private fun segDist(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx=x2-x1; val dy=y2-y1; val l2=dx*dx+dy*dy
        if (l2<0.001f) return hypot(px-x1, py-y1)
        val t=((px-x1)*dx+(py-y1)*dy).div(l2).coerceIn(0f,1f)
        return hypot(px-x1-t*dx, py-y1-t*dy)
    }
}
