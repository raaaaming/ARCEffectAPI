package kr.arcadia.arcEffectAPI.core.shape.preset

import kr.arcadia.arcEffectAPI.core.animation.Easings
import kr.arcadia.arcEffectAPI.core.animation.Timeline
import kr.arcadia.arcEffectAPI.core.animation.Transform
import kr.arcadia.arcEffectAPI.core.effect.ParticleEffect
import kr.arcadia.arcEffectAPI.core.particle.ParticleContext
import kr.arcadia.arcEffectAPI.core.particle.ParticleParams
import kr.arcadia.arcEffectAPI.core.shape.Shapes
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Presets {

    fun ringPulse(ctx: ParticleContext, center: ()-> Location, radius: Double, secs: Double) =
        ParticleEffect.builder()
            .context(ctx)
            .origin(center)
            .shape(Shapes.circle(radius, 96))
            .transform(Transform(
                translate = {_, _, _ -> Vector(0, 0, 0)},
                scale = {x, y, z -> Vector(1 + 0.5*sin(x*PI), y, 1 + 0.5*sin(z*PI))},
                rotate = {_, _ -> Vector(0, 0, 0)},
            ))
            .particle(ParticleParams(
                type = Particle.DUST,
                count = 1,
                color = Color.fromRGB(255, 80, 80),
                size = 1.5f
            ))
            .timeline(Timeline(durationTicks = (secs*20).toInt(), easing = Easings.linear, loop = false))
            .build()

    fun spiralAscend(ctx: ParticleContext, center: () -> Location, height: Double, turns: Int, samples: Int) =
        ParticleEffect.builder()
            .context(ctx)
            .origin(center)
            .shape { progress, _ ->
                val pts = ArrayList<Vector>(samples)
                for (i in 0 until samples) {
                    val t = (i.toDouble() / samples + progress) % 1.0
                    val angle = 2 * PI * turns * t
                    val y = height * t
                    pts += Vector(cos(angle), 0.0, sin(angle)).multiply(1.5).setY(y)
                }
                pts
            }
            .particle(ParticleParams(
                type = Particle.FLAME,
                count = 1,
                speed = 0.01,
            ))
            .timeline(Timeline(durationTicks = 100, loop = false))
            .build()
}