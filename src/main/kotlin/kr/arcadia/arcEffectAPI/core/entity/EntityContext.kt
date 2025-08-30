package kr.arcadia.arcEffectAPI.core.entity

import org.bukkit.World
import org.bukkit.entity.Player

data class EntityContext(
    val world: World,
    val caster: Player
)