package kr.arcadia.arcEffectAPI.core.effect

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

sealed interface ViewerFilter {
    data object All : ViewerFilter
    data class Radius(val center: Entity, val radius: Double) : ViewerFilter
    data class Predicate(val test: (Player) -> Boolean): ViewerFilter
}