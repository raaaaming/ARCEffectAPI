package kr.arcadia.arcEffectAPI.core.particle.policy

import org.bukkit.entity.Entity
import org.bukkit.entity.Player

sealed interface LodPolicy {
    data object Default : LodPolicy
    data class DistanceScale(val center: Entity, val steps: List<Pair<Double, Double>>): LodPolicy
}