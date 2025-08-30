package kr.arcadia.arcEffectAPI.core.entity

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

data class EntityParams(
    val isGravity: Boolean = false,
    val isVisible: Boolean = true,
    val isSmall: Boolean = false,
    val isMarker: Boolean = false,
    val rigidBody: Boolean = false,
    val speed: Double = 0.1,
    val attack: (Player, Entity, Entity) -> Unit = { caster, entity, target-> },
    val extra: (Player, Entity, Entity) -> Unit = {caster, entity, target-> },
    val hitbox: (Double) -> Double = { t -> 0.0 }
)
