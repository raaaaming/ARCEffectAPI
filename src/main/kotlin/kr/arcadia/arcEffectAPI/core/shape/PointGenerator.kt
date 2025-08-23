package kr.arcadia.arcEffectAPI.core.shape

import org.bukkit.util.Vector

fun interface PointGenerator {
    fun points(progress: Double, seed: Long): List<Vector>
}