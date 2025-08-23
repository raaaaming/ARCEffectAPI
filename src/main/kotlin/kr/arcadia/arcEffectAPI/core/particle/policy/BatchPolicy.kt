package kr.arcadia.arcEffectAPI.core.particle.policy

sealed interface BatchPolicy {
    data object Auto : BatchPolicy
    data class Size(val maxPerTick: Int): BatchPolicy
    data class Time(val maxMillisPerTick: Long): BatchPolicy
}