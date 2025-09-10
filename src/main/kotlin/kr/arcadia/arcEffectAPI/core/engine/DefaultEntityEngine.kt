package kr.arcadia.arcEffectAPI.core.engine

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.events.PacketContainer
import com.ticxo.modelengine.api.ModelEngineAPI
import com.ticxo.modelengine.api.model.ActiveModel
import com.ticxo.modelengine.api.model.ModeledEntity
import kr.arcadia.arcEffectAPI.core.effect.EntityEffect
import kr.arcadia.arcEffectAPI.core.effect.EntityEffectHandle
import kr.arcadia.arcEffectAPI.core.effect.EntityEngine
import kr.arcadia.arcEffectAPI.core.math.relativeByYaw
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.apache.commons.math3.util.FastMath
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftArmorStand
import org.bukkit.craftbukkit.entity.CraftExperienceOrb
import org.bukkit.craftbukkit.entity.CraftItem
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DefaultEntityEngine(
    private val plugin: JavaPlugin,
    private val clientPacketIntervalTicks: Int = 2,   // 0.1s
    private val physicsFreezeIntervalTicks: Int = 10, // 0.5s
    private val trackingRangeSq: Double = 64.0 * 64.0,
    private val batchSizePerTick: Int = 512,
    private val timeBudgetNanos: Long = 2_000_000L
) : EntityEngine {

    private val protocol: ProtocolManager = ProtocolLibrary.getProtocolManager()

    private val actives = Collections.synchronizedList(mutableListOf<ActiveEffect>())
    private val applyQueue: ConcurrentLinkedQueue<() -> Unit> = ConcurrentLinkedQueue()
    private val computePool: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))

    private var mainTask: BukkitTask? = null
    private var cursor = 0

    // ====== 공개 API ======
    override fun play(effect: EntityEffect): EntityEffectHandle {
        val base = effect.origin.add(effect.offset.relativeByYaw(effect.origin.yaw)) // 서버 좌표는 고정
        val stand = effect.context.world.spawnEntity(base, effect.type) as ArmorStand

        // 서버 물리 완전 차단
        stand.isVisible = effect.param.isVisible
        stand.isMarker = effect.param.isMarker
        stand.isSmall = effect.param.isSmall
        freezeServerPhysics(stand)

        val nms = (stand as CraftArmorStand).handle
        val damaged = Collections.synchronizedSet(mutableSetOf<Entity>())

        // === ME4: 모델 바인딩 및 애니메이션 재생 ===
        val me4Spec = effect.param.me4
        val modeledEntity: ModeledEntity?
        val activeModel: ActiveModel?
        if (me4Spec != null) {
            // 서버 엔티티와 연결된 ModeledEntity 생성/획득
            modeledEntity = ModelEngineAPI.getModeledEntity(stand.uniqueId) ?: ModelEngineAPI.createModeledEntity(stand)
            // 모델 로드
            val model = ModelEngineAPI.createActiveModel(me4Spec.modelId)
            require(model != null) { "ME4 model not found: ${me4Spec.modelId}" }
            activeModel = modeledEntity.addModel(model, true).get()

            // 애니메이션 재생 (컨트롤러/핸들러 이름은 ME4 버전에 따라 다를 수 있음)
            // 일반적으로 ActiveModel의 animationHandler 또는 animator에 play 호출
            val handler = activeModel.animationHandler
            me4Spec.animation?.let { animName ->
                // 존재 여부 확인 후 재생
                if(handler.animations.containsKey(animName)) {
                    handler.playAnimation(handler.animations[animName], false)
                } else {
                        plugin.logger.warning("ME4 animation not found: $animName on model ${me4Spec.modelId}")
                }
            }
        } else {
            modeledEntity = null
            activeModel = null
        }

        val active = ActiveEffect(
            effect = effect,
            entity = stand,
            nms = nms,
            damagedOnce = damaged,
            lastAnalytic = toVec3(base),
            modeled = ModeledBundle(modeledEntity, activeModel)
        )
        actives += active

        if (mainTask == null) startMainLoop()

        return object : EntityEffectHandle {
            override val entity = stand
            override fun cancel() {
                // ME4 정리
                teardownModel(active)
                stand.remove()
                actives.remove(active)
            }
        }
    }

    override fun cancel(handle: EntityEffectHandle) {
        handle.entity.remove()
        handle.cancel()
    }

    override fun cancelAll(owner: Any?) {
        synchronized(actives) {
            actives.forEach { teardownModel(it); it.entity.remove() }
            actives.clear()
        }
        mainTask?.cancel(); mainTask = null
    }

    // ====== 메인 루프 ======
    private fun startMainLoop() {
        mainTask = object : BukkitRunnable() {
            override fun run() {
                if (actives.isEmpty()) return
                val start = System.nanoTime()

                // (1) apply 큐
                runApplyPhase(start)

                // (2) 슬라이스
                val size = actives.size
                if (size > 0) {
                    val limit = minOf(batchSizePerTick, size)
                    repeat(limit) {
                        if (System.nanoTime() - start > timeBudgetNanos) return

                        if (cursor >= actives.size) cursor = 0
                        val a = actives.getOrNull(cursor++) ?: return@repeat
                        if (a.finished || !a.entity.isValid || a.entity.isDead) return@repeat

                        val effect = a.effect
                        val dur = effect.timeline.durationTicks
                        val tick = a.localTick
                        val tRaw = (tick % dur).toDouble() / dur
                        val t = effect.timeline.easing(tRaw)

                        if (tick % clientPacketIntervalTicks == 0) {
                            scheduleVisualMoveAndCollision(a, t)
                        }
                        if (tick % physicsFreezeIntervalTicks == 0) {
                            applyQueue.add { freezeServerPhysics(a.entity) }
                        }

                        a.localTick++
                        if (!effect.timeline.loop && a.localTick >= dur) {
                            a.finished = true
                            // 종료 시 모델 정리
                            applyQueue.add { teardownModel(a); a.entity.remove() }
                        }
                    }
                    actives.removeIf { it.finished || !it.entity.isValid || it.entity.isDead }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun runApplyPhase(start: Long) {
        while (true) {
            if (System.nanoTime() - start > timeBudgetNanos) break
            val job = applyQueue.poll() ?: break
            job.invoke()
        }
    }

    // ====== 서버-프리즈(절대 이동 금지) ======
    private fun freezeServerPhysics(stand: ArmorStand) {
        stand.setGravity(false)
        stand.velocity = Vector(0, 0, 0)
        val nms = (stand as CraftArmorStand).handle
        nms.deltaMovement = Vec3(0.0, 0.0, 0.0)
        nms.hasImpulse = false
    }

    // ====== 비동기 계산 + 적용 ======
    private fun scheduleVisualMoveAndCollision(a: ActiveEffect, t: Double) {
        val effect = a.effect
        val world = a.entity.world

        computePool.submit {
            val prev = a.lastAnalytic
            val now = analyticalPos(effect, t)
            val dx = now.x - prev.x
            val dy = now.y - prev.y
            val dz = now.z - prev.z

            val qx = quantizeRel(dx) // 1/4096 단위 양자화
            val qy = quantizeRel(dy)
            val qz = quantizeRel(dz)

            val swept = sweptAABB(prev, now, effect.param.hitbox(t))

            applyQueue.add {
                if (a.finished || !a.entity.isValid) return@add

                // (A) 화면 이동(패킷)
//                val vec = Vector(qx, qy, qz).relativeByYaw(a.entity.location.yaw)
//                sendRelativeMove(a.entity, vec.x, vec.y, vec.z)
                //println("$qx $qy $qz")
                sendRelativeMove(a.entity, qx, qy, qz)

                // (B) 블록 충돌
//                if (rayHitBlock(prevLoc, nowLoc)) {
//                    a.finished = true
//                    teardownModel(a)
//                    a.entity.remove()
//                    return@add
//                }

                // (C) 엔티티 충돌
                val victims = a.nms.level().getEntities(a.nms, swept)
                    .map { it.bukkitEntity }
                    .filter {
                        it != effect.context.caster &&
                                it !is CraftArmorStand && it !is CraftItem && it !is CraftExperienceOrb
                    }
                for (e in victims) {
                    if (a.damagedOnce.add(e)) {
                        effect.param.attack(effect.context.caster, a.entity, e)
                        effect.param.extra(effect.context.caster, a.entity, e)
                    }
                }

                // (D) 분석적 위치 갱신
                a.lastAnalytic = now
            }
        }
    }

    // ====== 로컬 앞=+X, 오른쪽=+Z 기준 변환 ======
    private fun analyticalPos(effect: EntityEffect, t: Double): Vec3 {
        val base = effect.origin.add(effect.offset.relativeByYaw(effect.origin.yaw))
        val yaw = base.yaw
        val localOffset: Vector = effect.transform.translate(t, t, t)
        val wo = localOffset.relativeByYaw(yaw)
        return Vec3(base.x + wo.x, base.y + wo.y, base.z + wo.z)
    }

    // ====== 충돌 유틸 ======
    private fun sweptAABB(prev: Vec3, now: Vec3, r: Double): AABB {
        val minX = FastMath.min(prev.x, now.x) - r
        val minY = FastMath.min(prev.y, now.y) - r
        val minZ = FastMath.min(prev.z, now.z) - r
        val maxX = FastMath.max(prev.x, now.x) + r
        val maxY = FastMath.max(prev.y, now.y) + r
        val maxZ = FastMath.max(prev.z, now.z) + r
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun rayHitBlock(prev: Location, now: Location): Boolean {
        val w = now.world ?: return false
        val d = now.toVector().subtract(prev.toVector())
        if (d.lengthSquared() <= 1e-8) return false
        val res = w.rayTraceBlocks(prev, d.normalize(), d.length(), FluidCollisionMode.NEVER, true)
        return res != null
    }

    // ====== 패킷 전송 ======
    private val relScale = 4096.0
    private fun quantizeRel(d: Double): Double {
        val q = (d * relScale).toInt().toShort().toInt()
        return q / relScale
    }

    private fun sendRelativeMove(entity: Entity, qx: Double, qy: Double, qz: Double) {
        if (FastMath.abs(qx) + FastMath.abs(qy) + FastMath.abs(qz) < 1e-6) return
        val packet = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK)
        packet.integers.write(0, entity.entityId)
        packet.shorts.write(0, (qx * relScale).toInt().toShort())
        packet.shorts.write(1, (qy * relScale).toInt().toShort())
        packet.shorts.write(2, (qz * relScale).toInt().toShort())

        val loc = entity.location
        packet.bytes.write(0, yawToByte(loc.yaw))
        packet.bytes.write(1, pitchToByte(loc.pitch))
        packet.booleans.write(0, true)

        for (p in viewersOf(entity, loc)) {
            protocol.sendServerPacket(p, packet)
        }
    }

    private fun viewersOf(e: Entity, at: Location = e.location): List<Player> {
        val w = at.world ?: return emptyList()
        return w.players.filter { it.world == w && it.location.distanceSquared(at) <= trackingRangeSq }
    }

    private fun yawToByte(yaw: Float): Byte {
        val y = ((yaw % 360 + 360) % 360) / 360f * 256f
        return y.toInt().toByte()
    }
    private fun pitchToByte(pitch: Float): Byte {
        val p = ((pitch % 360 + 360) % 360) / 360f * 256f
        return p.toInt().toByte()
    }

    private fun toVec3(loc: Location): Vec3 = Vec3(loc.x, loc.y, loc.z)

    // ====== ME4 수명 관리 ======
    private fun teardownModel(a: ActiveEffect) {
        try {
            a.modeled.active?.let { active ->
                // 애니메이션 정지(필요 시)
                val handler = active.animationHandler
                handler.forceStopAllAnimations()
            }
            a.modeled.entity?.destroy()
        } catch (_: Throwable) {
            // ME4 버전별 API 차이에 대비한 안전 처리
        }
    }

    // ====== 상태 ======
    private data class ModeledBundle(
        val entity: ModeledEntity?,
        val active: ActiveModel?
    )

    private data class ActiveEffect(
        val effect: EntityEffect,
        val entity: ArmorStand,
        val nms: net.minecraft.world.entity.Entity,
        val damagedOnce: MutableSet<Entity>,
        var localTick: Int = 0,
        var finished: Boolean = false,
        var lastAnalytic: Vec3,
        val modeled: ModeledBundle
    )
}
