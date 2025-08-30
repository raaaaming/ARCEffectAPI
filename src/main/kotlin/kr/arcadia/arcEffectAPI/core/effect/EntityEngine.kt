package kr.arcadia.arcEffectAPI.core.effect

interface EntityEngine {
    fun play(effect: EntityEffect): EntityEffectHandle
    fun cancel(handle: EntityEffectHandle)
    fun cancelAll(owner: Any? = null)
}