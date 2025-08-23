package kr.arcadia.arcEffectAPI.core.animation

import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

data class Transform(
    val translate: (Double, Double, Double)-> Vector = { x, y, z -> Vector(x, y, z) },
    val rotate: (Double, Double)-> Vector = { yaw, pitch ->
        val yawRad = Math.toRadians(yaw)
        val pitchRad = Math.toRadians(pitch)
        val x = -cos(pitchRad) * cos(yawRad)
        val y = -sin(pitchRad)
        val z = cos(pitchRad) * sin(yawRad)
        Vector(x, y, z).normalize()
    },
    val scale: (Double, Double, Double)-> Vector = { x, y, z -> Vector(x, y, z) }
)