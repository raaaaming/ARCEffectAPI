package kr.arcadia.arcEffectAPI.core.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

fun Entity.isCollision(other: Entity): Boolean {
    if (!this.isAlive || !other.isAlive) return false
    if (this.id == other.id) return false

    val start: Vec3 = this.position()
    val motion: Vec3 = this.deltaMovement
    val end: Vec3 = start.add(motion)
    val otherBox: AABB = other.boundingBox

    // 선분과 박스 교차 검사
    val hitResult = otherBox.clip(start, end)
    if (hitResult.isPresent) return true

    // motion이 거의 없으면 박스 겹침 검사
    return if (motion.lengthSqr() < 1e-6) {
        this.boundingBox.intersects(otherBox)
    } else {
        false
    }
}

fun Entity.getFirstCollision(): Entity? {
    val level = this.level() as? ServerLevel ?: return null
    if (!this.isAlive) return null

    val start: Vec3 = this.position()
    val motion: Vec3 = this.deltaMovement
    val end: Vec3 = start.add(motion)

    // 이동 경로를 포함한 AABB
    val searchBox: AABB = this.boundingBox.expandTowards(motion).inflate(0.3)

    // 주변 엔티티 검색
    val entities = level.getEntities(this, searchBox) { it.isAlive && it.id != this.id }

    var closest: Entity? = null
    var closestDist = Double.MAX_VALUE

    for (target in entities) {
        val clip = target.boundingBox.clip(start, end)
        if (clip.isPresent) {
            val dist = clip.get().distanceToSqr(start)
            if (dist < closestDist) {
                closestDist = dist
                closest = target
            }
        } else if (motion.lengthSqr() < 1e-6 && this.boundingBox.intersects(target.boundingBox)) {
            // 정지 상태일 때는 단순 박스 겹침 체크
            return target
        }
    }

    return closest
}