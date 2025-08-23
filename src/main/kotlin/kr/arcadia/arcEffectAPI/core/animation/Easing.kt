package kr.arcadia.arcEffectAPI.core.animation

fun interface Easing { operator fun invoke(t: Double): Double }