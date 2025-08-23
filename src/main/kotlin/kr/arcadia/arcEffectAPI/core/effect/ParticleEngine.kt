package kr.arcadia.arcEffectAPI.core.effect

interface ParticleEngine {
    fun play(effect: ParticleEffect): EffectHandle
    fun cancel(handle: EffectHandle)
    fun cancelAll(owner: Any? = null)
}