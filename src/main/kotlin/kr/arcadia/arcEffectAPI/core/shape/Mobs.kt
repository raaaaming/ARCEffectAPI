package kr.arcadia.arcEffectAPI.core.shape

import kr.arcadia.arcEffectAPI.core.animation.Easings
import kr.arcadia.arcEffectAPI.core.animation.Timeline
import kr.arcadia.arcEffectAPI.core.animation.Transform
import kr.arcadia.arcEffectAPI.core.effect.EntityEffect
import kr.arcadia.arcEffectAPI.core.entity.EntityContext
import kr.arcadia.arcEffectAPI.core.entity.EntityParams
import kr.arcadia.arcEffectAPI.core.entity.Me4Spec
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

object Mobs {

    fun line(player: Player, start: Location) = EntityEffect.builder()
        .context(EntityContext(
            world = start.world,
            player
        ))
        .origin(start)
        .offset(Vector(1.0, 0.0, 0.0))
        .transform(Transform(
            translate = {x, _, _ -> Vector(x, 0.0, 0.0)},
            scale = {_, _, _ -> Vector(1, 1, 1)},
            rotate = {_, _ -> Vector(0, 0, 0)},
        ))
        .timeline(Timeline(
            durationTicks = 100, loop = false, easing = Easings.linear
        ))
        .param(EntityParams(
            isVisible = true,
            isGravity = false,
            isMarker = false,
            isSmall = false,
            rigidBody = true,
            speed = 1.0,
            hitbox = {t -> 0.5},
            attack = {c, e, t ->
                (t as LivingEntity).damage(20.0, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(c).withDirectEntity(c).withDamageLocation(c.location).build())
                e.remove()
                     },
            extra = {_, _, _ ->},
            me4 = Me4Spec("viperwolf")
        ))
        .build()
}