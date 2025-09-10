package kr.arcadia.arcEffectAPI.core.entity

import org.bukkit.entity.Entity
import org.bukkit.entity.Player

data class EntityParams(
    val isGravity: Boolean = false,
    val isVisible: Boolean = true,
    val isSmall: Boolean = false,
    val isMarker: Boolean = false,
    val rigidBody: Boolean = false,
    val speed: Double = 0.1,
    val attack: (Player, Entity, Entity) -> Unit = { caster, entity, target-> },
    val extra: (Player, Entity, Entity) -> Unit = {caster, entity, target-> },
    val hitbox: (Double) -> Double = { t -> 0.0 },
    val me4: Me4Spec? = null
)

data class Me4Spec(
    val modelId: String,           // 모델 ID (ME4 모델 파일의 이름/키)
    val animation: String? = null, // 재생할 애니메이션 이름 (예: "walk", "attack_01")
    val loop: Boolean = true,      // 루프 여부
    val speed: Double = 1.0        // 재생 속도 배율 (1.0 = 기본)
)