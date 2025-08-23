package kr.arcadia.arcEffectAPI.core.particle

import org.bukkit.Location
import org.bukkit.entity.Player

sealed interface ViewerFilter {
    data object All : ViewerFilter
    data class Radius(val center: Location, val radius: Double) : ViewerFilter
    data class Predicate(val test: (Player) -> Boolean): ViewerFilter
}