package kr.arcadia.arcEffectAPI.core.particle

import kr.arcadia.arcEffectAPI.core.particle.policy.BatchPolicy
import kr.arcadia.arcEffectAPI.core.particle.policy.LodPolicy
import org.bukkit.World

data class ParticleContext(
    val world: World,
    val viewers: ViewerFilter = ViewerFilter.All,
    val lod: LodPolicy = LodPolicy.Default,
    val batch: BatchPolicy = BatchPolicy.Auto,
)