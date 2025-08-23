package kr.arcadia.arcEffectAPI.core.particle

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.util.Vector

data class ParticleParams(
    val type: Particle,
    val count: Int = 1,
    val speed: Double = 0.0,
    val offset: Vector = Vector(0, 0, 0),
    val color: Color? = null,
    val size: Float = 1.0f,
)
