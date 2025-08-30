package kr.arcadia.arcEffectAPI.core.shape

import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

object Shapes {
    fun line(start: Vector, end: Vector, samples: Int) = PointGenerator { _, _ ->
        val dir = end.clone().subtract(start)
        (0..samples).map { i ->
            val t = i.toDouble() / samples
            start.clone().add(dir.clone().multiply(t))
        }

    }

    fun circle(radius: Double, samples: Int) = PointGenerator { _, _ ->
        (0..samples).map { i ->
            val a = 2 * Math.PI * i / samples
            Vector(radius * cos(a), 0.0, radius * sin(a))
        }
    }

    fun sphere(radius: Double, rings: Int, segments: Int) = PointGenerator { _, _ ->
        buildList {
            for (r in 1 until rings) {
                val phi = Math.PI * r / rings
                val y = radius * cos(phi)
                val ringR = radius * sin(phi)
                for (s in 0 until segments) {
                    val a = 2 * Math.PI * s / segments
                    add(Vector(ringR * cos(a), y, ringR * sin(a)))
                }
            }
        }
    }

    fun bezier(points: List<Vector>, samples: Int) = PointGenerator { _, _ ->
        require(points.isNotEmpty()) { "At least one control point is required" }
        (0..samples).map { i ->
            val t = i.toDouble() / samples
            deCasteljau(points, t)
        }
    }

    // 호출 편의를 위한 vararg 버전
    fun bezier(samples: Int, vararg points: Vector) = bezier(points.toList(), samples)


    private fun deCasteljau(ctrl: List<Vector>, t: Double): Vector {
        if (ctrl.size == 1) return ctrl[0].clone()
        var current = ctrl.map { it.clone() }
        while (current.size > 1) {
            current = current.zipWithNext { a, b -> lerp(a, b, t) }
        }
        return current[0]
    }

    private fun lerp(a: Vector, b: Vector, t: Double): Vector {
        val u = 1.0 - t
        return a.clone().multiply(u).add(b.clone().multiply(t))
    }

}