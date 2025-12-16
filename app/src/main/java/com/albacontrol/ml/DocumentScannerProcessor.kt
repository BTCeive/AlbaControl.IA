package com.albacontrol.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Procesador de documentos usando ML Kit Document Scanner API.
 * Proporciona mejor detección de bordes y preprocesamiento automático.
 */
object DocumentScannerProcessor {
    private const val TAG = "DocumentScanner"
    
    /**
     * Crea un escáner de documentos configurado.
     */
    fun createScanner(context: Context): GmsDocumentScanner {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        
        return GmsDocumentScanning.getClient(options)
    }
    
    /**
     * Procesa un bitmap usando Document Scanner (si está disponible).
     * Retorna el bitmap procesado o el original si falla.
     * 
     * NOTA: Document Scanner API requiere actividad para mostrar UI.
     * Esta función es un placeholder para integración futura.
     */
    suspend fun processBitmap(bitmap: Bitmap): Bitmap {
        // Document Scanner API requiere una Activity para mostrar la UI de escaneo.
        // Para procesamiento automático, usamos el preprocesamiento OpenCV existente.
        // Esta función puede ser extendida en el futuro para usar Document Scanner
        // cuando el usuario quiera escanear manualmente.
        
        Log.d(TAG, "Document Scanner: using OpenCV preprocessing (Document Scanner requires Activity)")
        return bitmap
    }
    
    /**
     * Verifica si Document Scanner está disponible en el dispositivo.
     */
    fun isAvailable(context: Context): Boolean {
        return try {
            // Verificar si Google Play Services está disponible
            val gmsAvailable = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
            gmsAvailable
        } catch (e: Exception) {
            false
        }
    }
}
