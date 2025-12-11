package com.albacontrol.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * DocumentPreprocessorOpenCv: usa OpenCV para detectar el contorno del documento,
 * corregir perspectiva (deskew) y recortar bordes. Devuelve bitmap procesado
 * y guarda un PDF temporal si se pide.
 */
object DocumentPreprocessorOpenCv {

    suspend fun preprocessAndSavePdfWithOpenCv(context: Context, bitmap: Bitmap, outPdf: File, maxDim: Int = 2000): Pair<Bitmap?, File?> =
        withContext(Dispatchers.Default) {
            try {
                com.albacontrol.util.DebugLogger.init(context)
                com.albacontrol.util.DebugLogger.log("DocumentPreprocessorOpenCv", "preprocessAndSavePdfWithOpenCv: start (w=${bitmap.width}, h=${bitmap.height})")
                // Fallback to simple preprocessor which is more reliable for Tesseract
                val (denoised, pdf) = DocumentPreprocessor.preprocessAndSavePdf(context, bitmap, outPdf, maxDim)
                if (pdf != null) com.albacontrol.util.DebugLogger.log("DocumentPreprocessorOpenCv", "saved preproc PDF=${pdf.absolutePath}")
                // also try to save denoised image for inspection
                try {
                    denoised?.let { bmp -> com.albacontrol.util.DebugLogger.saveCrop(context, bmp, "preproc_${System.currentTimeMillis()}.png") }
                } catch (_: Exception) {}
                com.albacontrol.util.DebugLogger.log("DocumentPreprocessorOpenCv", "preprocessAndSavePdfWithOpenCv: finished")
                return@withContext Pair(denoised, pdf)
            } catch (e: Exception) {
                com.albacontrol.util.DebugLogger.logException("DocumentPreprocessorOpenCv", e)
                return@withContext Pair(bitmap, null)
            }
        }

    private fun detectAndWarpDocument(srcRgb: Mat): Mat? {
        try {
            val gray = Mat()
            Imgproc.cvtColor(srcRgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            val edged = Mat()
            Imgproc.Canny(gray, edged, 75.0, 200.0)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            if (contours.isEmpty()) return null

            // sort by area descending
            contours.sortByDescending { Imgproc.contourArea(it) }

            var docCnt: MatOfPoint2f? = null
            for (c in contours) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.02 * peri, true)
                if (approx.total() == 4L) {
                    docCnt = approx
                    break
                }
            }

            if (docCnt == null) return null

            val pts = docCnt.toArray()
            val ordered = orderPoints(pts)

            val (tl, tr, br, bl) = ordered
            val widthA = distance(br, bl)
            val widthB = distance(tr, tl)
            val maxWidth = Math.max(widthA, widthB).toInt()

            val heightA = distance(tr, br)
            val heightB = distance(tl, bl)
            val maxHeight = Math.max(heightA, heightB).toInt()

            // Sanity check: if detected document is too small, it's likely noise or a wrong contour.
            // Return null to fallback to simple preprocessing (full image).
            if (maxWidth < 500 || maxHeight < 500) {
                android.util.Log.w("AlbaTpl", "DocumentPreprocessorOpenCv: detected document too small (${maxWidth}x${maxHeight}), falling back to full image")
                return null
            }

            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point((maxWidth - 1).toDouble(), 0.0),
                Point((maxWidth - 1).toDouble(), (maxHeight - 1).toDouble()),
                Point(0.0, (maxHeight - 1).toDouble())
            )

            val M = Imgproc.getPerspectiveTransform(MatOfPoint2f(*ordered), dst)
            val warped = Mat()
            Imgproc.warpPerspective(srcRgb, warped, M, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            // Enhanced contrast processing for better OCR
            val warpedGray = Mat()
            Imgproc.cvtColor(warped, warpedGray, Imgproc.COLOR_RGB2GRAY)
            
            // Apply CLAHE (Contrast Limited Adaptive Histogram Equalization)
        // This improves contrast without amplifying noise
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(warpedGray, warpedGray)
        
        // Skip manual adaptive thresholding - let Tesseract handle binarization
        // Tesseract LSTM engine works better with grayscale/color images
        
        val colored = Mat()
        Imgproc.cvtColor(warpedGray, colored, Imgproc.COLOR_GRAY2RGB)
        android.util.Log.d("AlbaTpl", "DocumentPreprocessorOpenCv: applied CLAHE (skipping manual threshold)")
        return colored
        } catch (e: Exception) {
            Log.w("DocumentPreprocessorOCV", "detect warp failed: ${e.message}")
            return null
        }
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        // order: tl, tr, br, bl
        val sumSorted = pts.sortedBy { it.x + it.y }
        val diffSorted = pts.sortedBy { it.y - it.x }
        val tl = sumSorted.first()
        val br = sumSorted.last()
        val tr = diffSorted.first()
        val bl = diffSorted.last()
        return arrayOf(tl, tr, br, bl)
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.hypot(dx, dy)
    }

}

    /**
     * Generate simple preprocessing variants: original, CLAHE, adaptiveThreshold.
     * Returns a list of Bitmaps (in the same order).
     */
    fun generateVariants(srcBmp: Bitmap): List<Bitmap> {
        val out = mutableListOf<Bitmap>()
        try {
            val srcMat = Mat()
            Utils.bitmapToMat(srcBmp, srcMat)
            // Original (convert to RGB if needed)
            val orig = Mat()
            Imgproc.cvtColor(srcMat, orig, Imgproc.COLOR_RGBA2RGB)
            val bmpOrig = Bitmap.createBitmap(orig.cols(), orig.rows(), Config.ARGB_8888)
            Utils.matToBitmap(orig, bmpOrig)
            out.add(bmpOrig)

            // CLAHE variant
            val gray = Mat()
            Imgproc.cvtColor(orig, gray, Imgproc.COLOR_RGB2GRAY)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val cl = Mat()
            clahe.apply(gray, cl)
            val clColor = Mat()
            Imgproc.cvtColor(cl, clColor, Imgproc.COLOR_GRAY2RGB)
            val bmpCl = Bitmap.createBitmap(clColor.cols(), clColor.rows(), Config.ARGB_8888)
            Utils.matToBitmap(clColor, bmpCl)
            out.add(bmpCl)

            // Adaptive threshold variant
            val at = Mat()
            Imgproc.adaptiveThreshold(gray, at, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10.0)
            val atColor = Mat()
            Imgproc.cvtColor(at, atColor, Imgproc.COLOR_GRAY2RGB)
            val bmpAt = Bitmap.createBitmap(atColor.cols(), atColor.rows(), Config.ARGB_8888)
            Utils.matToBitmap(atColor, bmpAt)
            out.add(bmpAt)

            // release mats
            try { srcMat.release() } catch (_: Exception) {}
            try { orig.release() } catch (_: Exception) {}
            try { gray.release() } catch (_: Exception) {}
            try { cl.release() } catch (_: Exception) {}
            try { clColor.release() } catch (_: Exception) {}
            try { at.release() } catch (_: Exception) {}
            try { atColor.release() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w("DocumentPreprocessorOpenCv", "generateVariants failed: ${e.message}")
            // fallback: return the original
            out.clear()
            out.add(srcBmp)
        }
        return out
    }
