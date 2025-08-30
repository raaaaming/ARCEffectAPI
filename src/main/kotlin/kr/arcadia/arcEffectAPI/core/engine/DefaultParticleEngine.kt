package kr.arcadia.arcEffectAPI.core.engine

import kr.arcadia.arcEffectAPI.core.effect.EffectHandle
import kr.arcadia.arcEffectAPI.core.effect.ParticleEffect
import kr.arcadia.arcEffectAPI.core.effect.ParticleEngine
import kr.arcadia.arcEffectAPI.core.particle.ParticleContext
import kr.arcadia.arcEffectAPI.core.particle.ParticleParams
import kr.arcadia.arcEffectAPI.core.effect.ViewerFilter
import kr.arcadia.arcEffectAPI.core.math.applyTransform
import kr.arcadia.arcEffectAPI.core.math.moveRelative
import kr.arcadia.arcEffectAPI.core.particle.policy.BatchPolicy
import kr.arcadia.arcEffectAPI.core.particle.policy.LodPolicy
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import kotlin.math.abs

class DefaultParticleEngine(
    private val plugin: JavaPlugin
): ParticleEngine {
    private val tasks = mutableSetOf<BukkitTask>()

    override fun play(effect: ParticleEffect): EffectHandle {
        val task = object: BukkitRunnable() {
            var tick = 0
            override fun run() {
                val dur = effect.timeline.durationTicks
                val tRaw = (tick % dur).toDouble() / dur
                val t = effect.timeline.easing(tRaw)
                var base: Location
                if (effect.origin != null) {
                    base = effect.origin.location.moveRelative(effect.offset)
                } else {
                    base = effect.location.moveRelative(effect.offset)
                }
                val viewers = resolveViewers(effect.context)
                val pts = effect.shape.points(t, effect.seed)
                val density = resolveDensity(effect.context.lod, base, viewers)
                val capped = capByBatchPolicy(effect.context.batch, pts.size)

                var sent = 0
                for (p in pts) {
                    if (sent >= capped) break
                    val v = applyTransform(p, effect.transform, t)
                    val loc = base.clone().moveRelative(v)
                    send(viewers, loc, density, effect.particle)
                    sent++
                }

                tick++
                if (!effect.timeline.loop && tick >= dur) cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)
        tasks.add(task)
        return object: EffectHandle {
            override fun cancel() {
                task.cancel()
                tasks.remove(task)
            }
        }
    }

    override fun cancel(handle: EffectHandle) {
        handle.cancel()
    }

    override fun cancelAll(owner: Any?) {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun resolveViewers(ctx: ParticleContext): Collection<Player> = when(ctx.viewers) {
        ViewerFilter.All -> plugin.server.onlinePlayers
        is ViewerFilter.Radius -> plugin.server.onlinePlayers.filter { it.world == ctx.world && it.location.distance(ctx.viewers.center.location) <= ctx.viewers.radius }
        is ViewerFilter.Predicate -> plugin.server.onlinePlayers.filter(ctx.viewers.test)
    }

    private fun resolveDensity(lod: LodPolicy, base: Location, viewers: Collection<Player>): Double = when(lod) {
        LodPolicy.Default -> 1.0
        is LodPolicy.DistanceScale -> {
            val avg = viewers.map { it.location.distance(base) }.ifEmpty { listOf(0.0) }.average()
            lod.steps.minBy { abs(it.first - avg) }.second
        }
    }

    private fun capByBatchPolicy(policy: BatchPolicy, want: Int): Int = when(policy) {
        BatchPolicy.Auto -> want.coerceAtMost(512)
        is BatchPolicy.Size -> want.coerceAtMost(policy.maxPerTick)
        is BatchPolicy.Time -> (want * 0.7).toInt()
    }

    private fun send(viewers: Collection<Player>, loc: Location, density: Double, params: ParticleParams) {
        val builder = Particle.DUST.builder()
            .particle(params.type)
            .location(loc)
            .count(density.toInt())
            .receivers(viewers)
            .extra(params.speed)
        if(params.type == Particle.DUST) {
            builder.color(params.color, params.size)
        }
        builder.spawn()
    }
}