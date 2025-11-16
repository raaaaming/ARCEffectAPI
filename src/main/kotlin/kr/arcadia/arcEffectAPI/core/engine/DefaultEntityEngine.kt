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
    // 샤딩 관련
    private val maxPerRunner: Int = 20,
    // 전송/프리즈 주기 (20TPS 기준)
    private val clientPacketIntervalTicks: Int = 2,    // 0.1s
    private val physicsFreezeIntervalTicks: Int = 10,  // 0.5s
    // 1틱 처리 가드
    private val timeBudgetNanosPerShard: Long = 2_000_000L,
    private val batchSizePerTickPerShard: Int = 256,
    // 전송 범위
    private val trackingRangeSq: Double = 64.0 * 64.0
) : EntityEngine {

    // === 공통 리소스 ===
    private val protocol: ProtocolManager = ProtocolLibrary.getProtocolManager()
    private val relScale = 4096.0
    private val shards = Collections.synchronizedList(mutableListOf<Shard>())
    private val computePool: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))

    // ---------- EntityEngine 구현 ----------

    override fun play(effect: EntityEffect): EntityEffectHandle {
        // 서버-프리즈 스폰
        val base = effect.origin.clone()
        val stand = effect.context.world.spawnEntity(base, effect.type) as ArmorStand
        stand.isVisible = effect.param.isVisible
        stand.isMarker = effect.param.isMarker
        stand.isSmall = effect.param.isSmall
        freezeServerPhysics(stand)

        val nms = (stand as CraftArmorStand).handle

        if (effect.param.me4 != null) {
            val modeledEntity = ModelEngineAPI.getModeledEntity(stand) ?: ModelEngineAPI.createModeledEntity(stand)
            val activeModel = ModelEngineAPI.createActiveModel(effect.param.me4.modelId)
            modeledEntity.addModel(activeModel, true)

            val handler = activeModel.animationHandler
            effect.param.me4.animation?.let { animName ->
                if (handler.animations.containsKey(animName)) {
                    handler.playAnimation(handler.animations[animName], false)
                } else {
                    plugin.logger.warning("ME4 animation not found: $animName on model ${effect.param.me4.modelId}")
                }
            }
        }

        val active = ActiveEffect(
            effect = effect,
            entity = stand,
            nms = nms,
            damagedOnce = Collections.synchronizedSet(mutableSetOf()),
            lastAnalytic = toVec3(base)
        )

        // 샤드 할당
        val shard = getOrCreateShard()
        shard.add(active)

        return object : EntityEffectHandle {
            override val entity = stand
            override fun cancel() {
                shard.remove(active, alsoRemoveEntity = true)
            }
        }
    }

    override fun cancel(handle: EntityEffectHandle) {
        if (handle.entity is ArmorStand) {
            val asu = handle.entity as ArmorStand
            asu.remove()
        }
        handle.cancel()
    }

    override fun cancelAll(owner: Any?) {
        synchronized(shards) {
            shards.forEach { it.stopAll() }
            shards.clear()
        }
    }

    // ---------- 샤드 관리 ----------

    private fun getOrCreateShard(): Shard {
        synchronized(shards) {
            shards.firstOrNull { it.size() < maxPerRunner && it.isRunning() }?.let { return it }
            // 새 샤드 생성
            val shard = Shard(
                plugin = plugin,
                id = shards.size + 1,
                capacity = maxPerRunner,
                protocol = protocol,
                computePool = computePool,
                // 공통 파라미터 전달
                clientPacketIntervalTicks = clientPacketIntervalTicks,
                physicsFreezeIntervalTicks = physicsFreezeIntervalTicks,
                timeBudgetNanos = timeBudgetNanosPerShard,
                batchSizePerTick = batchSizePerTickPerShard,
                trackingRangeSq = trackingRangeSq,
                relScale = relScale
            )
            shard.start()
            shards += shard
            return shard
        }
    }

    // ---------- 공통 유틸 ----------

    private fun freezeServerPhysics(stand: ArmorStand) {
        stand.setGravity(false)
        stand.velocity = Vector(0, 0, 0)
        val nms = (stand as CraftArmorStand).handle
        nms.deltaMovement = Vec3(0.0, 0.0, 0.0)
        nms.hasImpulse = false
    }

    // ---------- 데이터 ----------

//    private data class ActiveEffect(
//        val effect: EntityEffect,
//        val entity: ArmorStand,
//        val nms: net.minecraft.world.entity.Entity,
//        val damagedOnce: MutableSet<Entity>,
//        var localTick: Int = 0,
//        var finished: Boolean = false,
//        var lastAnalytic: Vec3,
//        var residual: Vector = Vector() // 양자화 잔여 누적
//    )

    private data class ActiveEffect(
        val effect: EntityEffect,
        val entity: ArmorStand,
        val nms: net.minecraft.world.entity.Entity,
        val damagedOnce: MutableSet<Entity>,
        var localTick: Int = 0,
        var finished: Boolean = false,
        var lastAnalytic: Vec3,
    )

    private data class ModeledBundle(
        val entity: ModeledEntity?,
        val active: ActiveModel?
    )

    private class Shard(
        private val plugin: JavaPlugin,
        private val id: Int,
        private val capacity: Int,
        private val protocol: ProtocolManager,
        private val computePool: ExecutorService,
        // 공통 파라미터
        private val clientPacketIntervalTicks: Int,
        private val physicsFreezeIntervalTicks: Int,
        private val timeBudgetNanos: Long,
        private val batchSizePerTick: Int,
        private val trackingRangeSq: Double,
        private val relScale: Double
    ) {
        private val actives = Collections.synchronizedList(mutableListOf<ActiveEffect>())
        private val applyQueue: ConcurrentLinkedQueue<() -> Unit> = ConcurrentLinkedQueue()
        private var task: BukkitTask? = null
        private var cursor = 0
        private var lastYawByte: Byte = 0
        private var lastPitchByte: Byte = 0

        fun size(): Int = actives.size
        fun isRunning(): Boolean = task != null

        fun start() {
            if (task != null) return
            task = object : BukkitRunnable() {
                override fun run() {
                    if (actives.isEmpty()) {
                        stop()
                        return
                    }

                    val start = System.nanoTime()

                    // 1) apply 단계
                    while (true) {
                        if (System.nanoTime() - start > timeBudgetNanos) break
                        val job = applyQueue.poll() ?: break
                        job.invoke()
                    }

                    // 2) 슬라이스 처리
                    val sizeNow = actives.size
                    if (sizeNow > 0) {
                        val limit = FastMath.min(batchSizePerTick, sizeNow)
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
                                applyQueue.add { a.entity.remove() }
                            }
                        }
                        actives.removeIf { it.finished || !it.entity.isValid || it.entity.isDead }
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L)
        }

        fun stop() {
            task?.cancel()
            task = null
        }

        fun stopAll() {
            actives.forEach { it.entity.remove() }
            actives.clear()
            stop()
        }

        fun add(a: ActiveEffect) {
            actives += a
        }

        fun remove(a: ActiveEffect, alsoRemoveEntity: Boolean) {
            if (alsoRemoveEntity) a.entity.remove()
            actives.remove(a)
            if (actives.isEmpty()) stop()
        }

        // ----- 샤드 내부 로직 -----

        private fun freezeServerPhysics(stand: ArmorStand) {
            stand.setGravity(false)
            stand.velocity = Vector(0, 0, 0)
            val nms = (stand as CraftArmorStand).handle
            nms.deltaMovement = Vec3(0.0, 0.0, 0.0)
            nms.hasImpulse = false
        }

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

        private fun quantizeRel(d: Double): Double {
            val q = (d * relScale).toInt().toShort().toInt()
            return q / relScale
        }

        // 앞=로컬 +X, 오른쪽=로컬 +Z
        private fun analyticalPos(effect: EntityEffect, t: Double): Vec3 {
            val base = effect.origin.add(effect.offset.relativeByYaw(effect.origin.yaw))
            val yaw = base.yaw
            val localOffset: Vector = effect.transform.translate(t, t, t)
            val wo = localOffset.relativeByYaw(yaw)
            return Vec3(base.x + wo.x, base.y + wo.y, base.z + wo.z)
        }

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

        private fun quantize(d: Double): Pair<Short, Double> {
            val q = (d * relScale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val residual = d - q / relScale
            return q.toShort() to residual
        }

        // yaw/pitch 변할 때만 LOOK 포함 (보간 끊김 방지)
        private fun sendRelativeSmart(entity: Entity, qxS: Short, qyS: Short, qzS: Short) {
            val yawB = yawToByte(entity.location.yaw)
            val pitchB = pitchToByte(entity.location.pitch)
            val moveOnly = (yawB == lastYawByte && pitchB == lastPitchByte)
            lastYawByte = yawB; lastPitchByte = pitchB

            val viewers = viewersOf(entity, entity.location)
            if (moveOnly) {
                val p = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE)
                p.integers.write(0, entity.entityId)
                p.shorts.write(0, qxS); p.shorts.write(1, qyS); p.shorts.write(2, qzS)
                for (v in viewers) protocol.sendServerPacket(v, p)
            } else {
                val p = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK)
                p.integers.write(0, entity.entityId)
                p.shorts.write(0, qxS); p.shorts.write(1, qyS); p.shorts.write(2, qzS)
                p.bytes.write(0, yawB); p.bytes.write(1, pitchB)
                p.booleans.write(0, true)
                for (v in viewers) protocol.sendServerPacket(v, p)
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
    }

    // ----- 작은 공통 유틸 -----
    private fun toVec3(loc: Location): Vec3 = Vec3(loc.x, loc.y, loc.z)

    // ====== 공개 API ======
//    override fun play(effect: EntityEffect): EntityEffectHandle {
//        val base = effect.origin.add(effect.offset.relativeByYaw(effect.origin.yaw)) // 서버 좌표는 고정
//        val stand = effect.context.world.spawnEntity(base, effect.type) as ArmorStand
//
//        // 서버 물리 완전 차단
//        stand.isVisible = effect.param.isVisible
//        stand.isMarker = effect.param.isMarker
//        stand.isSmall = effect.param.isSmall
//        freezeServerPhysics(stand)
//
//        val nms = (stand as CraftArmorStand).handle
//        val damaged = Collections.synchronizedSet(mutableSetOf<Entity>())
//
//        // === ME4: 모델 바인딩 및 애니메이션 재생 ===
//        val me4Spec = effect.param.me4
//        val modeledEntity: ModeledEntity?
//        val activeModel: ActiveModel?
//        if (me4Spec != null) {
//            // 서버 엔티티와 연결된 ModeledEntity 생성/획득
//            modeledEntity = ModelEngineAPI.getModeledEntity(stand.uniqueId) ?: ModelEngineAPI.createModeledEntity(stand)
//            // 모델 로드
//            val model = ModelEngineAPI.createActiveModel(me4Spec.modelId)
//            require(model != null) { "ME4 model not found: ${me4Spec.modelId}" }
//            activeModel = modeledEntity.addModel(model, true).get()
//
//            // 애니메이션 재생 (컨트롤러/핸들러 이름은 ME4 버전에 따라 다를 수 있음)
//            // 일반적으로 ActiveModel의 animationHandler 또는 animator에 play 호출
//            val handler = activeModel.animationHandler
//            me4Spec.animation?.let { animName ->
//                // 존재 여부 확인 후 재생
//                if (handler.animations.containsKey(animName)) {
//                    handler.playAnimation(handler.animations[animName], false)
//                } else {
//                    plugin.logger.warning("ME4 animation not found: $animName on model ${me4Spec.modelId}")
//                }
//            }
//        } else {
//            modeledEntity = null
//            activeModel = null
//        }
//
//        val active = ActiveEffect(
//            effect = effect,
//            entity = stand,
//            nms = nms,
//            damagedOnce = damaged,
//            lastAnalytic = toVec3(base),
//            modeled = ModeledBundle(modeledEntity, activeModel)
//        )
//        actives += active
//
//        if (mainTask == null) startMainLoop()
//
//        return object : EntityEffectHandle {
//            override val entity = stand
//            override fun cancel() {
//                // ME4 정리
//                teardownModel(active)
//                stand.remove()
//                actives.remove(active)
//            }
//        }
//    }
//
//    // ====== 메인 루프 ======
//    private fun startMainLoop() {
//        mainTask = object : BukkitRunnable() {
//            override fun run() {
//                if (actives.isEmpty()) return
//                val start = System.nanoTime()
//
//                // (1) apply 큐
//                runApplyPhase(start)
//
//                // (2) 슬라이스
//                val size = actives.size
//                if (size > 0) {
//                    val limit = minOf(batchSizePerTick, size)
//                    repeat(limit) {
//                        if (System.nanoTime() - start > timeBudgetNanos) return
//
//                        if (cursor >= actives.size) cursor = 0
//                        val a = actives.getOrNull(cursor++) ?: return@repeat
//                        if (a.finished || !a.entity.isValid || a.entity.isDead) return@repeat
//
//                        val effect = a.effect
//                        val dur = effect.timeline.durationTicks
//                        val tick = a.localTick
//                        val tRaw = (tick % dur).toDouble() / dur
//                        val t = effect.timeline.easing(tRaw)
//
//                        if (tick % clientPacketIntervalTicks == 0) {
//                            scheduleVisualMoveAndCollision(a, t)
//                        }
//                        if (tick % physicsFreezeIntervalTicks == 0) {
//                            applyQueue.add { freezeServerPhysics(a.entity) }
//                        }
//
//                        a.localTick++
//                        if (!effect.timeline.loop && a.localTick >= dur) {
//                            a.finished = true
//                            // 종료 시 모델 정리
//                            applyQueue.add { teardownModel(a); a.entity.remove() }
//                        }
//                    }
//                    actives.removeIf { it.finished || !it.entity.isValid || it.entity.isDead }
//                }
//            }
//        }.runTaskTimer(plugin, 0L, 1L)
//    }
//
//    private fun runApplyPhase(start: Long) {
//        while (true) {
//            if (System.nanoTime() - start > timeBudgetNanos) break
//            val job = applyQueue.poll() ?: break
//            job.invoke()
//        }
//    }
}