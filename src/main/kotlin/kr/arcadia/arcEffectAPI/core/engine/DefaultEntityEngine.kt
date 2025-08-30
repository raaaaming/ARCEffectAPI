package kr.arcadia.arcEffectAPI.core.engine

import kr.arcadia.arcEffectAPI.core.effect.EntityEffect
import kr.arcadia.arcEffectAPI.core.effect.EntityEffectHandle
import kr.arcadia.arcEffectAPI.core.effect.EntityEngine
import kr.arcadia.arcEffectAPI.core.math.moveRelative
import kr.arcadia.arcEffectAPI.core.math.relativeByYaw
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3
import org.bukkit.craftbukkit.entity.CraftArmorStand
import org.bukkit.craftbukkit.entity.CraftExperienceOrb
import org.bukkit.craftbukkit.entity.CraftItem
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.Collections

class DefaultEntityEngine(
    private val plugin: JavaPlugin
): EntityEngine {

    private val tasks = mutableSetOf<BukkitTask>()

    override fun play(effect: EntityEffect): EntityEffectHandle {
        val base = effect.origin.moveRelative(effect.offset.multiply(-1))
        val entity = effect.context.world.spawnEntity(base, effect.type) as ArmorStand
        entity.isVisible = effect.param.isVisible
        entity.isMarker = effect.param.isMarker
        entity.isSmall = effect.param.isSmall
        entity.isCollidable = effect.param.rigidBody
        entity.setGravity(effect.param.isGravity)

        val craft = entity as CraftArmorStand
        val nms = craft.handle
        val level = nms.level()

        var damaged: Collection<Entity> = Collections.synchronizedCollection(mutableSetOf())

        val task = object: BukkitRunnable() {
            var tick = 0
            override fun run() {
                val dur = effect.timeline.durationTicks
                val tRaw = (tick % dur).toDouble() / dur
                val t = effect.timeline.easing(tRaw)
                val yaw = base.yaw

                send(nms, yaw, effect.transform.translate(t, t, t), effect.param.speed)

                val box = nms.boundingBox.inflate(effect.param.hitbox(t))

                val nearby: List<Entity> = level.getEntities(nms, box).map { it.bukkitEntity }.filter { it != effect.context.caster && (it !is CraftItem && it !is CraftExperienceOrb) }
                nearby.forEach {
                    if (!damaged.contains(it)) {
                        effect.param.attack(effect.context.caster, entity, it)
                        effect.param.extra(effect.context.caster, entity, it)
                        damaged += it
                    }
                }


                tick++
                if (!effect.timeline.loop && tick >= dur) cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)
        tasks.add(task)
        return object: EntityEffectHandle {
            override val entity = entity
            override fun cancel() {
                entity.remove()
                task.cancel()
                tasks.remove(task)
            }
        }
    }

    override fun cancel(handle: EntityEffectHandle) {
        handle.entity.remove()
        handle.cancel()
    }

    override fun cancelAll(owner: Any?) {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun send(nms: net.minecraft.world.entity.decoration.ArmorStand, yaw: Float, translate: Vector, speed: Double) {
        nms.deltaMovement = Vec3(translate.x, translate.y, translate.z).relativeByYaw(yaw).multiply(Vec3(speed, speed, speed))
        nms.move(MoverType.SELF, nms.deltaMovement)
        nms.hasImpulse = true
    }
}