package kr.arcadia.arcEffectAPI.core.particle.policy

import org.bukkit.Location

sealed interface LodPolicy {
    data object Default : LodPolicy
    data class DistanceScale(val center: Location, val steps: List<Pair<Double, Double>>): LodPolicy
}