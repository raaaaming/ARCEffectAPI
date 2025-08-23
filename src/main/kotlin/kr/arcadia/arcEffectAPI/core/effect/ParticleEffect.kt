package kr.arcadia.arcEffectAPI.core.effect

import kr.arcadia.arcEffectAPI.core.animation.Timeline
import kr.arcadia.arcEffectAPI.core.animation.Transform
import kr.arcadia.arcEffectAPI.core.particle.ParticleContext
import kr.arcadia.arcEffectAPI.core.particle.ParticleParams
import kr.arcadia.arcEffectAPI.core.shape.PointGenerator
import kr.arcadia.arcEffectAPI.core.shape.Shapes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World

data class ParticleEffect(
    val context: ParticleContext,
    val origin: ()-> Location,
    val shape: PointGenerator,
    val transform: Transform = Transform(),
    val particle: ParticleParams,
    val timeline: Timeline,
    val seed: Long = System.nanoTime()
) {
    class Builder() {
        private var ctx = ParticleContext(Bukkit.getWorlds().first())
        private var origin: ()-> Location = { ctx.world.spawnLocation }
        private var shape: PointGenerator = Shapes.circle(1.0, 32)
        private var transform: Transform = Transform()
        private var particle: ParticleParams = ParticleParams(Particle.FLAME)
        private var timeline: Timeline = Timeline(40)

        fun context(ctx: ParticleContext) = apply { this.ctx = ctx }
        fun origin(origin: ()-> Location) = apply { this.origin = origin }
        fun shape(shape: PointGenerator) = apply { this.shape = shape }
        fun transform(transform: Transform) = apply { this.transform = transform }
        fun particle(particle: ParticleParams) = apply { this.particle = particle }
        fun timeline(timeline: Timeline) = apply { this.timeline = timeline }

        fun build() = ParticleEffect(ctx, origin, shape, transform, particle, timeline)
    }
    companion object {
        fun builder() = Builder()
    }
}
