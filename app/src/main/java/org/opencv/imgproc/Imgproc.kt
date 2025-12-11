package org.opencv.imgproc

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size

object Imgproc {
    const val RETR_LIST = 0
    const val CHAIN_APPROX_SIMPLE = 0
    const val COLOR_RGB2GRAY = 0
    const val COLOR_RGBA2RGB = 0
    const val COLOR_GRAY2RGB = 0
    const val ADAPTIVE_THRESH_GAUSSIAN_C = 0
    const val THRESH_BINARY = 0

    fun cvtColor(src: Mat, dst: Mat, code: Int) {}
    fun GaussianBlur(src: Mat, dst: Mat, ksize: Size, sigmaX: Double) {}
    fun Canny(src: Mat, edges: Mat, threshold1: Double, threshold2: Double) {}
    fun findContours(image: Mat, contours: MutableList<org.opencv.core.MatOfPoint>, hierarchy: Mat, mode: Int, method: Int) {}
    fun arcLength(curve: MatOfPoint2f, closed: Boolean): Double = 0.0
    fun approxPolyDP(curve: MatOfPoint2f, approxCurve: MatOfPoint2f, epsilon: Double, closed: Boolean) {}
    fun contourArea(c: org.opencv.core.MatOfPoint): Double = 0.0
    fun getPerspectiveTransform(src: MatOfPoint2f, dst: MatOfPoint2f): Mat = Mat()
    fun warpPerspective(src: Mat, dst: Mat, M: Mat, dsize: Size) {}
    fun adaptiveThreshold(src: Mat, dst: Mat, maxValue: Double, adaptiveMethod: Int, thresholdType: Int, blockSize: Int, C: Double) {}
    fun createCLAHE(clipLimit: Double, tileGridSize: Size): CLAHE = CLAHE()

    class CLAHE {
        fun apply(src: Mat, dst: Mat) {}
    }
}
