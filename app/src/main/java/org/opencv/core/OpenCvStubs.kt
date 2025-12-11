package org.opencv.core

// Very small compile-time stubs for common OpenCV core types used in preprocessing.
open class Mat {
    fun release() {}
    fun cols(): Int = 0
    fun rows(): Int = 0
}

open class MatOfPoint : Mat() {
    fun toArray(): Array<Point> = emptyArray()
}

class MatOfPoint2f(vararg pts: Point) : Mat() {
    @Suppress("UNCHECKED_CAST")
    private val arr: Array<Point> = pts as Array<Point>
    fun toArray(): Array<Point> = arr
    fun total(): Long = arr.size.toLong()
}

data class Point(var x: Double = 0.0, var y: Double = 0.0)

data class Size(var width: Double = 0.0, var height: Double = 0.0)
