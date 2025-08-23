package kr.arcadia.arcEffectAPI.core.engine

import kr.arcadia.arcEffectAPI.core.animation.Transform
import kr.arcadia.arcEffectAPI.core.effect.EffectHandle
import kr.arcadia.arcEffectAPI.core.effect.ParticleEffect
import kr.arcadia.arcEffectAPI.core.effect.ParticleEngine
import kr.arcadia.arcEffectAPI.core.particle.ParticleContext
import kr.arcadia.arcEffectAPI.core.particle.ParticleParams
import kr.arcadia.arcEffectAPI.core.particle.ViewerFilter
import kr.arcadia.arcEffectAPI.core.particle.policy.BatchPolicy
import kr.arcadia.arcEffectAPI.core.particle.policy.LodPolicy
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

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
                val base = effect.origin()
                val viewers = resolveViewers(effect.context)

                val pts = effect.shape.points(t, effect.seed)
                val density = resolveDensity(effect.context.lod, base, viewers)
                val capped = capByBatchPolicy(effect.context.batch, pts.size)

                var sent = 0
                for (p in pts) {
                    if (sent >= capped) break
                    val v = applyTransform(p, effect.transform, t)
                    val loc = base.clone().add(v)
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
        is ViewerFilter.Radius -> plugin.server.onlinePlayers.filter { it.world == ctx.world && it.location.distance(ctx.viewers.center) <= ctx.viewers.radius }
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

    private fun applyTransform(p: Vector, tf: Transform, t: Double): Vector {
        val s = tf.scale(t, t, t)
        val r = tf.rotate(t, t)
        val tr = tf.translate(t, t, t)
        var v = Vector(p.x*s.x, p.y*s.y, p.z*s.z)
        v = v.rotateTo(r)
        return v.add(tr)
    }

    private fun Vector.rotateTo(to: Vector): Vector {
        val v = this.clone()
        val b = to.clone()

        val axis = v.clone().crossProduct(b)
        val axisLenSq = axis.lengthSquared()

        if (axisLenSq < 1e-10) {
            if(v.dot(b) > 0) return v
            val ortho = if (abs(v.x) < 0.9) Vector(1.0, 0.0, 0.0) else Vector(0.0, 1.0, 0.0)
            val newAxis = v.clone().crossProduct(ortho).normalize()
            return v.rotateAroundAxis(newAxis, Math.PI)
        }
        axis.normalize()

        val angle = acos(v.dot(b).coerceIn(-1.0, 1.0))

        val term1 = v.clone().multiply(cos(angle))
        val term2 = axis.clone().crossProduct(v).multiply(sin(angle))
        val term3 = axis.clone().multiply(axis.dot(v) * (1 - cos(angle)))

        return term1.add(term2).add(term3)
    }

    private fun send(viewers: Collection<Player>, loc: Location, density: Double, params: ParticleParams) {
        val builder = Particle.DUST.builder()
            .particle(params.type)
            .location(loc)
            .offset(params.offset.x, params.offset.y, params.offset.z)
            .count(density.toInt())
            .receivers(viewers)
            .extra(params.speed)
        if(params.type == Particle.DUST) {
            builder.color(params.color, params.size)
        }
        builder.spawn()
    }
}