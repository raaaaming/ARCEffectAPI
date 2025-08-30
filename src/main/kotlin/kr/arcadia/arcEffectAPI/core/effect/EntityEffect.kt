package kr.arcadia.arcEffectAPI.core.effect

import kr.arcadia.arcEffectAPI.core.animation.Timeline
import kr.arcadia.arcEffectAPI.core.animation.Transform
import kr.arcadia.arcEffectAPI.core.entity.EntityContext
import kr.arcadia.arcEffectAPI.core.entity.EntityParams
import kr.arcadia.arcEffectAPI.core.particle.ParticleContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.util.Vector

data class EntityEffect(
    val context: EntityContext,
    val origin: Location,
    val type: EntityType,
    val transform: Transform,
    val offset: Vector,
    val param: EntityParams,
    val timeline: Timeline,
    val model: String? = null
) {
    class Builder() {
        private var ctx = EntityContext(
            Bukkit.getWorlds().first(),
            Bukkit.getPlayer("")!!
        )
        private var origin: Location = ctx.world.spawnLocation
        private var transform: Transform = Transform()
        private var offset: Vector = Vector(0, 0, 0)
        private var param: EntityParams = EntityParams(
            isGravity = false,
            isVisible = false,
            isSmall = false,
            isMarker = false,
            rigidBody = false,
            attack = {_,_,_ -> },
            extra = {_,_,_ -> })
        private var timeline: Timeline = Timeline(40)
        private var model: String? = null

        fun context(ctx: EntityContext) = apply { this.ctx = ctx }
        fun origin(origin: Location) = apply { this.origin = origin }
        fun transform(transform: Transform) = apply { this.transform = transform }
        fun offset(offset: Vector) = apply { this.offset = offset }
        fun param(param: EntityParams) = apply { this.param = param}
        fun timeline(timeline: Timeline) = apply { this.timeline = timeline }
        fun model(model: String) = apply { this.model = model }

        fun build() = EntityEffect(ctx, origin, EntityType.ARMOR_STAND, transform, offset, param, timeline, model)
    }
    companion object {
        fun builder() = Builder()
    }
}