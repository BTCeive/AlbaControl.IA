package com.example.albacontrol.learning

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils
import android.graphics.Bitmap

object ImagePreprocessor {
    fun preprocess(input: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(input, mat)

        // 1. Convertir a escala de grises
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)

        // 2. Eliminar bordes
        Imgproc.threshold(mat, mat, 0.0, 255.0, Imgproc.THRESH_OTSU)

        // 3. Corrección de perspectiva (placeholder)
        // TODO: detectar contorno principal y aplicar warpPerspective

        // 4. Normalizar tamaño
        val size = Size(2480.0, 3508.0) // A4 virtual
        Imgproc.resize(mat, mat, size)

        val output = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, output)
        return output
    }
}
