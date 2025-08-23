package kr.arcadia.arcEffectAPI

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kr.arcadia.arcEffectAPI.core.animation.Easings
import kr.arcadia.arcEffectAPI.core.animation.Timeline
import kr.arcadia.arcEffectAPI.core.animation.Transform
import kr.arcadia.arcEffectAPI.core.effect.ParticleEffect
import kr.arcadia.arcEffectAPI.core.engine.DefaultParticleEngine
import kr.arcadia.arcEffectAPI.core.particle.ParticleContext
import kr.arcadia.arcEffectAPI.core.particle.ParticleParams
import kr.arcadia.arcEffectAPI.core.particle.ViewerFilter
import kr.arcadia.arcEffectAPI.core.particle.policy.BatchPolicy
import kr.arcadia.arcEffectAPI.core.particle.policy.LodPolicy
import kr.arcadia.arcEffectAPI.core.shape.Shapes
import kr.arcadia.arcEffectAPI.core.shape.preset.Presets
import kr.arcadia.core.bukkit.ARCCoreBukkitPlugin
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.util.Vector
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ARCEffectAPI : ARCCoreBukkitPlugin() {

    lateinit var engine: DefaultParticleEngine

    override fun onPreEnable() {
        super.onPreEnable()
        engine = DefaultParticleEngine(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { command ->
            command.registrar().register(buildCommand)
        }
    }

    val buildCommand: LiteralCommandNode<CommandSourceStack> = LiteralArgumentBuilder.literal<CommandSourceStack>("aea")
        .then(Commands.literal("play")
            .then(Commands.argument("shape", StringArgumentType.string())
                .suggests { ctx, builder -> CompletableFuture.supplyAsync {
                    arrayOf("ring", "spiral").forEach { builder.suggest(it) }
                    return@supplyAsync builder.build()
                }}
                .executes { ctx ->
                    val source = ctx.source
                    val location = ctx.source.location
                    val shape = StringArgumentType.getString(ctx, "shape")
                    val preset : ParticleEffect? = when (shape) {
                        "ring" -> {
                            Presets.ringPulse(
                                ctx = ParticleContext(
                                    world = location.world,
                                    viewers = ViewerFilter.Radius(location, 32.0),
                                    lod = LodPolicy.DistanceScale(location, listOf(16.0 to 1.0, 32.0 to 0.6, 48.0 to 0.35)),
                                    batch = BatchPolicy.Auto,
                                ),
                                center = { location },
                                radius = 3.0,
                                secs = 20.0,
                            )
                        }
                        "spiral" -> {
                            Presets.spiralAscend(
                                ctx = ParticleContext(
                                    world = location.world
                                ),
                                center = { location.add(0.0, 1.7, 0.0) },
                                height = 4.0,
                                turns = 3,
                                samples = 180,
                            )
                        }
                        "circle" -> {
                            ParticleEffect.builder()
                                .context(ParticleContext(
                                    world = location.world,
                                    viewers = ViewerFilter.Radius(location, 32.0),
                                    lod = LodPolicy.DistanceScale(location, listOf(16.0 to 1.0, 32.0 to 0.6, 48.0 to 0.35)),
                                    batch = BatchPolicy.Auto,
                                ))
                                .origin(location::clone)
                                .shape(Shapes.circle(2.0, 96))
                                .transform(Transform(
                                    translate = {_, _, _ -> Vector(0, 0, 0)},
                                    scale = {_, _, _ -> Vector(1, 1, 1)},
                                    rotate = {_, _ -> Vector(0, 0, 0)},
                                ))
                                .particle(ParticleParams(
                                    type = Particle.DUST,
                                    count = 1,
                                    color = Color.fromRGB(255, 80, 80),
                                    size = 1.5f
                                ))
                                .timeline(Timeline(durationTicks = 40, easing = Easings.linear, loop = true))
                                .build()
                        }
                        else -> null
                    }
                    if(preset == null) return@executes Command.SINGLE_SUCCESS
                    val h = engine.play(preset)
                    return@executes Command.SINGLE_SUCCESS
                }
            )
        ).then(Commands.literal("sin")
            .executes { ctx ->
                val sender = ctx.source.sender
                (0..360).forEach { logger.info("${sin(Math.toRadians(it.toDouble()))}") }
                return@executes Command.SINGLE_SUCCESS
            }
        )
        .build()

    override fun onPreDisable() {
        super.onPreDisable()
        engine.cancelAll()
    }
}
