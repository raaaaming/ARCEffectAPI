package kr.arcadia.arcEffectAPI.core.animation

data class Timeline(
    val durationTicks: Int,
    val easing: Easing = Easings.linear,
    val loop: Boolean = false,
)
