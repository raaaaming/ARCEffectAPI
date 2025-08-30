package kr.arcadia.arcEffectAPI.core.math

import kr.arcadia.arcEffectAPI.core.animation.Transform
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

fun Vec3.relativeByYaw(yaw: Float) : Vec3 {
    val vec = Vector(this.x, this.y, this.z).relativeByYaw(yaw)
    return Vec3(vec.x, vec.y, vec.z)
}

fun Vector.relativeByYaw(yaw: Float) : Vector {
    val radYaw = Math.toRadians(yaw.toDouble())

    val forward = Vec3(-sin(radYaw), 0.0, cos(radYaw)).normalize() // (sin, 0, -cos)
    val right = Vec3(cos(radYaw), 0.0, sin(radYaw)).reverse().normalize()
    val up = Vec3(0.0, 1.0, 0.0).normalize()

    return Vector(
        forward.x * this.x + right.x * this.z + up.x * this.y,
        forward.y * this.x + right.y * this.z + up.y * this.y,
        forward.z * this.x + right.z * this.z + up.z * this.y
    ).normalize()
}

fun Location.moveRelative(move: Vector): Location {
    val radYaw = Math.toRadians(this.yaw.toDouble())

    val forward = Vector(-sin(radYaw), 0.0, cos(radYaw)).multiply(-1).normalize()
    val right = Vector(cos(radYaw), 0.0, sin(radYaw)).normalize()
    val up = Vector(0.0, -1.0, 0.0)

    val worldOffset = forward.multiply(move.x)
        .add(right.multiply(move.z))
        .add(up.multiply(move.y))

    return this.clone().add(worldOffset)
}

fun applyTransform(p: Vector, tf: Transform, t: Double): Vector {
    val s = tf.scale(t, t, t)
    val r = tf.rotate(t, t)
    val tr = tf.translate(t, t, t)
    var v = Vector(p.x*s.x, p.y*s.y, p.z*s.z)
    v = v.rotateTo(r)
    return v.add(tr)
}

fun Vector.rotateTo(to: Vector): Vector {
    val v = this.clone()
    val b = to.clone()

    val axis = v.clone().crossProduct(b)
    val axisLenSq = axis.lengthSquared()

    if (axisLenSq < 1e-10) {
        if(v.dot(b) > 0) return v
        val ortho = if (abs(v.x) < 0.9) Vector(1.0, 0.0, 0.0) else Vector(0.0, 1.0, 0.0)
        val newAxis = v.clone().crossProduct(ortho).normalize()
        return v.rotateAroundAxis(newAxis, Math.PI)
    }
    axis.normalize()

    val angle = acos(v.dot(b).coerceIn(-1.0, 1.0))

    val term1 = v.clone().multiply(cos(angle))
    val term2 = axis.clone().crossProduct(v).multiply(sin(angle))
    val term3 = axis.clone().multiply(axis.dot(v) * (1 - cos(angle)))

    return term1.add(term2).add(term3)
}