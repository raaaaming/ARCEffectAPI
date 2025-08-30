package kr.arcadia.arcEffectAPI.core.effect

import org.bukkit.entity.Entity

interface EntityEffectHandle {
    val entity: Entity
    fun cancel()
}