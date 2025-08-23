package kr.arcadia.arcEffectAPI.core.animation

import kotlin.math.pow

object Easings {
    val linear = Easing { it }
    val easeInOut = Easing { t -> if (t<0.5) 2*t*t else 1 - (-2 * t + 2).pow(2.0) /2 }
}