package com.albacontrol.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.FileOutputStream
import com.albacontrol.R
import com.albacontrol.data.AppDatabase
import com.albacontrol.data.Draft
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

// Temporary conservative stubs/constants to satisfy references introduced
// by OCR/template patches. These are safe placeholders and should be
// replaced by proper implementations later.
private const val EMBEDDING_WEIGHT: Float = 1.0f
private const val AUTO_SAVE_COOLDOWN_MS: Long = 30000L
private val normalizedFields: Map<String, String> = emptyMap()
private val fieldConfidences: Map<String, Float> = emptyMap()
private const val version: Int = 1
private const val active: Boolean = false
private val createdFromSampleIds: List<Long> = emptyList()
private const val fieldConfidence: Float = 0.0f

class NuevoAlbaranFragment : Fragment() {

    private lateinit var productContainer: LinearLayout
    private lateinit var btnAddProduct: Button
    private lateinit var checkSinAlbaran: CheckBox
    private lateinit var checkIncidenciaAlbaran: CheckBox

    private var lastOcrResult: com.albacontrol.ml.OCRResult? = null
    private var lastOcrBitmap: android.graphics.Bitmap? = null
    // TFLite variables removed (not used)
    private val photoPaths: MutableList<String> = mutableListOf()
    private var restoredFromState: Boolean = false
    private val pendingDeletionRunnables: MutableMap<String, Runnable> = mutableMapOf()
    // Keep track of preprocessed PDF files created during this form session.
    private val preprocPdfs: MutableList<String> = mutableListOf()
    // Flag set to true when the form is explicitly saved as draft (do not delete preproc files on cancel)
    private var draftSaved: Boolean = false
    // URI for temporary camera photo (full resolution)
    private var currentPhotoUri: Uri? = null
    // Track whether the user manually edited specific form fields to avoid overwriting
    private val userEdited: MutableMap<String, Boolean> = mutableMapOf(
        "proveedor" to false,
        "nif" to false,
        "numero_albaran" to false,
        "fecha_albaran" to false
    )
    // If the user manually edited/deleted products, avoid auto-repopulating them
    private var productsManuallyEdited: Boolean = false
    // Once OCR populates the form, avoid further automatic updates until next capture
    private var autoPopulateDone: Boolean = false

    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var pickPhotosNoOcrLauncher: ActivityResultLauncher<Array<String>>

    // onCreateView and UI init follow (rest of file)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_nuevo_albaran, container, false)

        try { com.albacontrol.util.DebugLogger.init(requireContext()); com.albacontrol.util.DebugLogger.log("NuevoAlbaranFragment","onCreateView: start") } catch (_: Exception) {}

        // Inicializar ActivityResultLaunchers antes de que los botones los usen
        try {
            takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                try {
                    if (success && currentPhotoUri != null) {
                        // Load full-resolution bitmap from saved URI
                        val stream = requireContext().contentResolver.openInputStream(currentPhotoUri!!)
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        if (bmp != null) {
                            Log.d("AlbaTpl", "Camera captured full-resolution image: ${bmp.width}x${bmp.height}")
                            onImageCaptured(bmp)
                        }
                        // Clean up temporary file
                        try {
                            currentPhotoUri?.let { uri ->
                                requireContext().contentResolver.delete(uri, null, null)
                            }
                        } catch (_: Exception) {}
                        currentPhotoUri = null
                    }
                } catch (e: Exception) {
                    Log.e("AlbaTpl", "Error loading camera image: ${e.message}")
                }
            }

            pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                try {
                    if (uri != null) {
                        val stream = requireContext().contentResolver.openInputStream(uri)
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        if (bmp != null) onImageCaptured(bmp)
                    }
                } catch (_: Exception) {}
            }

            pickPhotosNoOcrLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                try {
                    if (uris != null) {
                        for (u in uris) {
                            try {
                                val stream = requireContext().contentResolver.openInputStream(u)
                                val bmp = BitmapFactory.decodeStream(stream)
                                stream?.close()
                                if (bmp != null) {
                                    // Guardar la foto sin procesar OCR
                                    saveBitmapAndAdd(bmp)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // protección adicional: si falla el registro, evitar crash posterior
        }

        // TFLite and Tesseract initialization removed - App uses ML Kit OCR only



                // Inicializar vistas primero antes de usarlas
                productContainer = view.findViewById(R.id.product_container)
                btnAddProduct = view.findViewById(R.id.btnAddProduct)
                checkSinAlbaran = view.findViewById(R.id.checkSinAlbaran)
                checkIncidenciaAlbaran = view.findViewById(R.id.checkIncidenciaAlbaran)

                // comentarios y productos (leer form_state si existe en argumentos o savedInstanceState)
                val jo = try {
                    val s = arguments?.getString("form_state") ?: savedInstanceState?.getString("form_state") ?: "{}"
                    JSONObject(s)
                } catch (_: Exception) { JSONObject() }

                try {
                    view.findViewById<EditText>(R.id.etComentarios).setText(jo.optString("comments", ""))
                    view.findViewById<EditText>(R.id.etProveedor).setText(jo.optString("proveedor", ""))
                    view.findViewById<EditText>(R.id.etNif).setText(jo.optString("nif", ""))
                    view.findViewById<EditText>(R.id.etNumeroAlbaran).setText(jo.optString("numero_albaran", ""))
                    view.findViewById<EditText>(R.id.etFechaAlbaran).setText(jo.optString("fecha_albaran", ""))
                    checkSinAlbaran.isChecked = jo.optBoolean("sin_albaran", false)
                    checkIncidenciaAlbaran.isChecked = jo.optBoolean("tiene_incidencias", false)
                    Log.d("AlbaTpl", "Restaurando estado: ${jo.optJSONArray("products")?.length() ?: 0} productos")
                } catch (e: Exception) {
                    Log.e("AlbaTpl", "Error restaurando campos", e)
                }

                try {
                    val productsArray = jo.optJSONArray("products")
                    if (productsArray != null && productsArray.length() > 0) {
                        productContainer.removeAllViews()
                        for (i in 0 until productsArray.length()) {
                            val p = productsArray.getJSONObject(i)
                            val inflater2 = LayoutInflater.from(requireContext())
                            val item = inflater2.inflate(R.layout.product_item, productContainer, false)
                            item.findViewById<EditText>(R.id.etDescripcion).setText(p.optString("descripcion", ""))
                            item.findViewById<EditText>(R.id.etUnidades).setText(p.optString("unidades", ""))
                            item.findViewById<EditText>(R.id.etPrecio).setText(p.optString("precio", ""))
                            item.findViewById<EditText>(R.id.etImporte).setText(p.optString("importe", ""))
                            val inc = p.optBoolean("incidencia", false)
                            item.findViewById<CheckBox>(R.id.checkIncidencia).isChecked = inc
                            
                            // Attach watchers to restored product items
                            attachWatchersToProductItem(item)
                            
                            val btnDelete = item.findViewById<ImageButton>(R.id.btnDeleteProduct)
                            btnDelete.setOnClickListener {
                                val dialog = AlertDialog.Builder(requireContext())
                                    .setTitle(getString(R.string.delete_product_title))
                                    .setMessage(getString(R.string.confirm_delete_this_product))
                                    .setPositiveButton(getString(R.string.delete)) { _, _ -> productContainer.removeView(item) }
                                    .setNegativeButton(getString(R.string.cancel), null)
                                    .create()
                                dialog.setOnShowListener {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                                }
                                dialog.show()
                            }
                            productContainer.addView(item)
                        }
                        // Mark that we restored products, so we don't add an empty one below
                        restoredFromState = true
                    }
                } catch (_: Exception) {}

        // Fecha de creación
        val tvCreatedAt = view.findViewById<TextView>(R.id.tvCreatedAt)
        val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
        tvCreatedAt.text = getString(R.string.created_at, sdf.format(Date()))

        // Añadir primer producto vacío al iniciar (solo si no se restauraron productos)
        if (!restoredFromState) {
        addProductBox()
        }

        // Attach watchers to main form fields so we don't overwrite user edits
        try {
            val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
            val etNif = view.findViewById<EditText>(R.id.etNif)
            val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
            val etFecha = view.findViewById<EditText>(R.id.etFechaAlbaran)

            fun attachMainWatcher(key: String, et: EditText) {
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        // mark as user edited only when user is typing (has focus)
                        try {
                            if (et.hasFocus()) userEdited[key] = true
                        } catch (_: Exception) {}
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }

            attachMainWatcher("proveedor", etProveedor)
            attachMainWatcher("nif", etNif)
            attachMainWatcher("numero_albaran", etNumero)
            attachMainWatcher("fecha_albaran", etFecha)
        } catch (_: Exception) {}

        btnAddProduct.setOnClickListener {
            addProductBox()
        }

        // Botones Cámara / Subir (placeholders)
        view.findViewById<Button>(R.id.btnCamera).setOnClickListener {
            // Create temporary file for full-resolution camera capture
            try {
                val photoFile = java.io.File.createTempFile(
                    "camera_",
                    ".jpg",
                    requireContext().cacheDir
                )
                currentPhotoUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().packageName + ".fileprovider",
                    photoFile
                )
                takePictureLauncher.launch(currentPhotoUri)
            } catch (e: Exception) {
                Log.e("AlbaTpl", "Error creating temp file for camera: ${e.message}")
                Toast.makeText(requireContext(), "Error al iniciar cámara: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.btnUpload).setOnClickListener {
            // Abrir selector de imágenes
            pickImageLauncher.launch("image/*")
        }

        // El botón "Guardar patrón" se eliminó del layout; el patrón se guardará automáticamente al finalizar

        // Contenedor de miniaturas y botón añadir fotografía
        val photoContainer = view.findViewById<LinearLayout>(R.id.photo_container)
        // botón lanzar selector múltiple
        view.findViewById<Button>(R.id.btnAddPhoto).setOnClickListener {
            // lanzar selector que permite múltiples selecciones SIN OCR (para fotos de desperfectos)
            try {
                pickPhotosNoOcrLauncher.launch(arrayOf("image/*"))
            } catch (e: Exception) {
                // fallback: abrir selector simple y no procesar OCR (usar pickImageLauncher but saving only)
                pickImageLauncher.launch("image/*")
            }
        }

        // Guardar borrador
        view.findViewById<Button>(R.id.btnGuardarBorrador).setOnClickListener {
            // Serializar campos del formulario a JSON
            val json = JSONObject()
            val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
            val etNif = view.findViewById<EditText>(R.id.etNif)
            val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
            val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)

            json.put("proveedor", etProveedor.text.toString())
            json.put("nif", etNif.text.toString())
            json.put("numero_albaran", etNumero.text.toString())
            json.put("fecha_albaran", etFechaAlb.text.toString())
            json.put("created_at", System.currentTimeMillis())
            try {
                val etComments = view.findViewById<EditText>(R.id.etComentarios)
                json.put("comments", etComments.text.toString())
            } catch (_: Exception) {}
            try {
                val etComments = view.findViewById<EditText>(R.id.etComentarios)
                json.put("comments", etComments.text.toString())
            } catch (_: Exception) {}
            // comentarios
            val etComments = view.findViewById<EditText>(R.id.etComentarios)
            json.put("comments", etComments.text.toString())

            val productsArray = JSONArray()
            for (i in 0 until productContainer.childCount) {
                val item = productContainer.getChildAt(i)
                val desc = item.findViewById<EditText>(R.id.etDescripcion).text.toString()
                val unid = item.findViewById<EditText>(R.id.etUnidades).text.toString()
                val precio = item.findViewById<EditText>(R.id.etPrecio).text.toString()
                val importe = item.findViewById<EditText>(R.id.etImporte).text.toString()
                val inc = item.findViewById<CheckBox>(R.id.checkIncidencia).isChecked

                val p = JSONObject()
                p.put("descripcion", desc)
                p.put("unidades", unid)
                p.put("precio", precio)
                p.put("importe", importe)
                p.put("incidencia", inc)
                productsArray.put(p)
            }
            json.put("products", productsArray)

            // Include preprocessed PDFs in the draft JSON so they are preserved
            try {
                val arr = org.json.JSONArray()
                synchronized(preprocPdfs) { for (p in preprocPdfs) arr.put(p) }
                json.put("preproc_pdfs", arr)
            } catch (_: Exception) {}

            // Guardar en Room usando coroutines
            val db = AppDatabase.getInstance(requireContext())
            val draft = Draft(providerId = null, dataJson = json.toString(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            lifecycleScope.launch {
                try {
                    val id = withContext(Dispatchers.IO) { db.draftDao().insert(draft) }
                        Toast.makeText(requireContext(), getString(com.albacontrol.R.string.toast_draft_saved, id), Toast.LENGTH_SHORT).show()
                        // Mark that we've saved a draft: keep preproc files
                        try { draftSaved = true } catch (_: Exception) {}
                        // Volver automáticamente a Home después de guardar borrador
                        try { parentFragmentManager.popBackStack() } catch (_: Exception) {}
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(com.albacontrol.R.string.toast_draft_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }

        // Cancelar
        view.findViewById<Button>(R.id.btnCancelar).setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(com.albacontrol.R.string.cancel))
                .setMessage(getString(com.albacontrol.R.string.confirm_delete, ""))
                .setPositiveButton(getString(com.albacontrol.R.string.ok)) { _, _ ->
                    try {
                        // If draft wasn't saved, remove temporary preproc PDFs
                        if (!draftSaved) {
                            try {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        synchronized(preprocPdfs) {
                                            for (p in preprocPdfs) {
                                                try { java.io.File(p).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
                                            }
                                            preprocPdfs.clear()
                                        }
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    parentFragmentManager.popBackStack()
                }
                .setNegativeButton(getString(com.albacontrol.R.string.cancel), null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
            }
            dialog.show()
        }

        // Finalizar: validar proveedor obligatorio, generar PDF e insertar CompletedAlbaran
        view.findViewById<Button>(R.id.btnFinalizar).setOnClickListener {
            val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
            val proveedor = etProveedor.text.toString().trim()
            if (proveedor.isEmpty()) {
                Toast.makeText(requireContext(), getString(com.albacontrol.R.string.provider_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Serializar campos del formulario a JSON (igual que en guardar borrador)
            val json = JSONObject()
            val etNif = view.findViewById<EditText>(R.id.etNif)
            val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
            val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)

            json.put("proveedor", proveedor)
            json.put("nif", etNif.text.toString())
            json.put("numero_albaran", etNumero.text.toString())
            json.put("fecha_albaran", etFechaAlb.text.toString())
            
            // Recepcionista y ubicación
            try {
                val spinnerRecep = view.findViewById<Spinner>(R.id.spinnerRecepcionista)
                val recep = spinnerRecep.selectedItem?.toString() ?: ""
                json.put("recepcionista", recep)
            } catch (_: Exception) { json.put("recepcionista", "") }
            
            try {
                val spinnerUbic = view.findViewById<Spinner>(R.id.spinnerUbicacion)
                val ubic = spinnerUbic.selectedItem?.toString() ?: ""
                json.put("ubicacion_recogida", ubic)
            } catch (_: Exception) { json.put("ubicacion_recogida", "") }
            
            // Sin albarán
            json.put("sin_albaran", checkSinAlbaran.isChecked)
            
            // comentarios
            try {
                val etComments = view.findViewById<EditText>(R.id.etComentarios)
                json.put("comments", etComments.text.toString())
            } catch (_: Exception) {}
            // include preprocessed PDFs so they are kept with the form
            try {
                val arr = org.json.JSONArray()
                synchronized(preprocPdfs) { for (p in preprocPdfs) arr.put(p) }
                json.put("preproc_pdfs", arr)
            } catch (_: Exception) {}
            json.put("created_at", System.currentTimeMillis())

            val productsArray = JSONArray()
            var tieneIncidencias = false
            for (i in 0 until productContainer.childCount) {
                val item = productContainer.getChildAt(i)
                val desc = item.findViewById<EditText>(R.id.etDescripcion).text.toString()
                val unid = item.findViewById<EditText>(R.id.etUnidades).text.toString()
                val precio = item.findViewById<EditText>(R.id.etPrecio).text.toString()
                val importe = item.findViewById<EditText>(R.id.etImporte).text.toString()
                val inc = item.findViewById<CheckBox>(R.id.checkIncidencia).isChecked
                
                if (inc) tieneIncidencias = true

                val p = JSONObject()
                p.put("descripcion", desc)
                p.put("unidades", unid)
                p.put("precio", precio)
                p.put("importe", importe)
                p.put("incidencia", inc)
                productsArray.put(p)
            }
            json.put("products", productsArray)
            json.put("tiene_incidencias", tieneIncidencias)

            // Generar PDF y guardar CompletedAlbaran usando coroutines
            val db = AppDatabase.getInstance(requireContext())
            lifecycleScope.launch {
                try {
                    // If we have a preprocessed PDF, render first page and extract a coords map
                    try {
                        val lastPdf = synchronized(preprocPdfs) { if (preprocPdfs.isNotEmpty()) preprocPdfs.last() else null }
                        if (!lastPdf.isNullOrBlank()) {
                            val pdfBmp = renderPdfFirstPageToBitmap(lastPdf)
                            if (pdfBmp != null) {
                                val pdfOcr = withContext(Dispatchers.IO) {
                                    kotlinx.coroutines.suspendCancellableCoroutine<com.albacontrol.ml.OCRResult?> { cont ->
                                        try {
                                            com.albacontrol.ml.OcrProcessor.processBitmap(pdfBmp) { res, err ->
                                                if (err != null) cont.resumeWith(Result.failure(err)) else cont.resumeWith(Result.success(res))
                                            }
                                        } catch (e: Exception) { cont.resumeWith(Result.failure(e)) }
                                    }
                                }
                                if (pdfOcr != null) {
                                    try {
                                        val coordsObj = buildCoordsMapForJson(pdfOcr, pdfBmp, json, productsArray)
                                        if (coordsObj.length() > 0) json.put("coords_map", coordsObj)
                                    } catch (e: Exception) {
                                        Log.d("AlbaTpl", "coords extraction error: ${e.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("AlbaTpl", "preproc pdf coords extraction skipped: ${e.message}")
                    }
                    // incluir rutas de fotos en el JSON para que el generador las añada al PDF
                    json.put("photo_paths", org.json.JSONArray(photoPaths))
                    val pdfFile = withContext(Dispatchers.IO) { generatePdfFromJson(json) }
                    // añadir ruta pdf al json
                    json.put("pdf_path", pdfFile.absolutePath)

                    val completed = com.albacontrol.data.CompletedAlbaran(providerId = null, dataJson = json.toString(), createdAt = System.currentTimeMillis())
                    val id = withContext(Dispatchers.IO) { db.completedDao().insert(completed) }

                    Toast.makeText(requireContext(), getString(com.albacontrol.R.string.toast_finalized, id), Toast.LENGTH_SHORT).show()
                    // Guardar patrón automáticamente al finalizar el albarán (si hay resultado OCR reciente)
                    try {
                        val saveJob = savePatternFromCorrections()
                        try { saveJob.join() } catch (_: Exception) {}
                    } catch (_: Exception) {}
                    
                    // Enviar corrección al backend API para aprendizaje
                    try {
                        sendCorrectionToBackend(json, lastOcrResult, lastOcrBitmap)
                    } catch (e: Exception) {
                        Log.e("AlbaTpl", "Error al enviar corrección al backend: ${e.message}", e)
                        // No mostrar error al usuario, es opcional
                    }
                    // Abrir cliente de correo con adjunto (asunto/cuerpo/recipientes desde opciones)
                    try {
                        val uri: Uri = FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".fileprovider", pdfFile)

                        // obtener ubicacion y recepcionista si están en los spinners
                        val ubic: String? = try { view.findViewById<Spinner>(R.id.spinnerUbicacion).selectedItem?.toString() } catch (_: Exception) { null }
                        val recep: String? = try { view.findViewById<Spinner>(R.id.spinnerRecepcionista).selectedItem?.toString() } catch (_: Exception) { null }

                        // asunto: AlbaControl.IA _ yyyy.MM.dd _ Ubicacion de recogida _ proveedor
                        val sdfDay = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault())
                        val day = sdfDay.format(java.util.Date())
                        val safeUbic = ubic?.replace("\n", " ")?.trim().orEmpty()
                        val safeProv = proveedor.replace("\n", " ").trim()
                        val subject = "AlbaControl.IA _ $day _ $safeUbic _ $safeProv"

                        // obtener destinatarios desde opciones (SharedPreferences)
                        val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
                        val emailsJson = prefs.getString("emails", "[]") ?: "[]"
                        val toList = mutableListOf<String>()
                        try {
                            val arr = org.json.JSONArray(emailsJson)
                            for (i in 0 until arr.length()) {
                                val e = arr.optString(i).trim()
                                if (e.isNotBlank()) toList.add(e)
                            }
                        } catch (_: Exception) {}

                        // cuerpo con aviso GDPR / privacidad (texto breve)
                        val body = StringBuilder()
                        body.append("Adjunto albarán generado desde AlbaControl.IA.\n")
                        if (safeUbic.isNotBlank()) body.append("Ubicación de recogida: $safeUbic\n")
                        if (!recep.isNullOrBlank()) body.append("Recepcionista: ${recep.trim()}\n")
                        body.append("\nPor favor, trate los datos personales conforme al RGPD. Los datos contenidos en este documento se usan únicamente para la gestión logística y entrega de mercancías.")

                        val email = Intent(Intent.ACTION_SEND)
                        email.type = "application/pdf"
                        if (toList.isNotEmpty()) email.putExtra(Intent.EXTRA_EMAIL, toList.toTypedArray())
                        email.putExtra(Intent.EXTRA_SUBJECT, subject)
                        email.putExtra(Intent.EXTRA_TEXT, body.toString())
                        email.putExtra(Intent.EXTRA_STREAM, uri)
                        email.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        
                        // Verificar si hay app de email preferida configurada
                        val preferredEmailApp = prefs.getString("preferred_email_app", "") ?: ""
                        if (preferredEmailApp.isNotBlank()) {
                            // Abrir directamente la app preferida
                            email.setPackage(preferredEmailApp)
                            try {
                                startActivity(email)
                            } catch (e: Exception) {
                                // Si la app no está instalada, mostrar chooser
                                Log.w("AlbaTpl", "App preferida no disponible: $preferredEmailApp, mostrando chooser")
                                email.setPackage(null)
                        startActivity(Intent.createChooser(email, getString(R.string.send_chooser)))
                            }
                        } else {
                            // Mostrar chooser si no hay app preferida
                            startActivity(Intent.createChooser(email, getString(R.string.send_chooser)))
                        }
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(com.albacontrol.R.string.toast_email_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                    // Volver automáticamente a Home después de finalizar y lanzar (o intentar) el envío
                    try { parentFragmentManager.popBackStack() } catch (_: Exception) {}
                    // Remove temporary preproc PDFs now that the form has been finalized (we embedded coords_map)
                    try {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                synchronized(preprocPdfs) {
                                    for (p in preprocPdfs) {
                                        try { java.io.File(p).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
                                    }
                                    preprocPdfs.clear()
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(com.albacontrol.R.string.toast_finalize_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }

        // Spinners + botones añadir: cargar opciones guardadas y manejar añadir
        val spinnerUbic = view.findViewById<Spinner>(R.id.spinnerUbicacion)
        val spinnerRecep = view.findViewById<Spinner>(R.id.spinnerRecepcionista)

        fun loadListFromPrefs(key: String): MutableList<String> {
            val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(key, "[]") ?: "[]"
            val list = mutableListOf<String>()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (!s.isNullOrBlank()) list.add(s)
                }
            } catch (_: Exception) {}
            return list
        }

        fun saveListToPrefs(key: String, list: List<String>) {
            val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            for (s in list) arr.put(s)
            prefs.edit().putString(key, arr.toString()).apply()
        }

        fun refreshSpinner(spinner: Spinner, items: List<String>, lastKey: String?) {
            val display = if (items.isEmpty()) listOf(getString(R.string.no_data)) else items
            val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, display)
            spinner.adapter = adapter
            // seleccionar último usado si existe
            if (!lastKey.isNullOrBlank()) {
                val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
                val last = prefs.getString(lastKey, null)
                if (!last.isNullOrBlank()) {
                    val idx = display.indexOf(last)
                    if (idx >= 0) spinner.setSelection(idx)
                }
            }
        }

        fun showAddDialog(prefKey: String, title: String, hint: String, spinner: Spinner, lastKey: String) {
            val et = EditText(requireContext())
            et.hint = hint
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(et)
                .setPositiveButton(getString(R.string.add), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            dialog.show()
            // ahora que el diálogo está mostrado, configurar listener y colores de botones
            try {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val v = et.text.toString().trim()
                    if (v.isBlank()) {
                        et.error = getString(R.string.enter_value)
                        return@setOnClickListener
                    }
                    val list = loadListFromPrefs(prefKey)
                    if (!list.contains(v)) {
                        list.add(0, v) // añadir arriba
                        saveListToPrefs(prefKey, list)
                    }
                    refreshSpinner(spinner, loadListFromPrefs(prefKey), lastKey)
                    // guardar como último usado
                    val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString(lastKey, v).apply()
                    // seleccionar el nuevo elemento
                    val adapter = spinner.adapter as? android.widget.ArrayAdapter<String>
                    val pos = (0 until (adapter?.count ?: 0)).firstOrNull { adapter?.getItem(it) == v } ?: -1
                    if (pos >= 0) spinner.setSelection(pos)
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
            } catch (_: Exception) {}
        }

        // inicializar spinners
        val ubicList = loadListFromPrefs("ubicaciones")
        val recepList = loadListFromPrefs("recepcionistas")
        refreshSpinner(spinnerUbic, ubicList, "last_ubicacion")
        refreshSpinner(spinnerRecep, recepList, "last_recepcionista")

        // guardar último usado al seleccionar
        spinnerUbic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, viewSel: View?, position: Int, id: Long) {
                val item = parent?.getItemAtPosition(position)?.toString() ?: return
                if (item == getString(R.string.no_data)) return
                val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("last_ubicacion", item).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerRecep.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, viewSel: View?, position: Int, id: Long) {
                val item = parent?.getItemAtPosition(position)?.toString() ?: return
                if (item == getString(R.string.no_data)) return
                val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("last_recepcionista", item).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Cuando el usuario modifica proveedor o NIF, programar auto-save de patrón
        try {
            val etProveedorMain = view.findViewById<EditText>(R.id.etProveedor)
            val etNifMain = view.findViewById<EditText>(R.id.etNif)
            val watcherProv = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (lastOcrResult != null) scheduleAutoSavePattern()
                    // Trigger template application on manual entry
                    if (s != null && s.length > 3) {
                        applyTemplateForProvider(s.toString())
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
            etProveedorMain.addTextChangedListener(watcherProv)
            etNifMain.addTextChangedListener(watcherProv)
        } catch (_: Exception) {}

        // wiring botones añadir
        view.findViewById<ImageButton>(R.id.btnAddUbicacion).setOnClickListener {
            showAddDialog("ubicaciones", getString(R.string.new_location), getString(R.string.new_location_hint), spinnerUbic, "last_ubicacion")
        }
        view.findViewById<ImageButton>(R.id.btnAddRecepcionista).setOnClickListener {
            showAddDialog("recepcionistas", getString(R.string.new_receptionist), getString(R.string.new_receptionist_hint), spinnerRecep, "last_recepcionista")
        }

        // Tesseract OCR initialization removed - App uses ML Kit only

        return view
    }

    private fun onImageCaptured(bitmap: android.graphics.Bitmap) {
        // New capture -> allow OCR/template to repopulate products and reset auto-populate lock
        productsManuallyEdited = false
        autoPopulateDone = false
        Log.d("AlbaTpl", "onImageCaptured: reset flags - autoPopulateDone=false, productsManuallyEdited=false")
        // Validate resolution
        if (bitmap.width < 500 || bitmap.height < 500) {
            Log.e("AlbaTpl", "onImageCaptured: Rejected low resolution image (${bitmap.width}x${bitmap.height})")
            Toast.makeText(requireContext(), "Error: La imagen es demasiado pequeña (${bitmap.width}x${bitmap.height}). Por favor use una imagen de alta calidad.", Toast.LENGTH_LONG).show()
            return
        }

        // Cuando se obtiene una imagen, procesarla con Tesseract OCR
        checkSinAlbaran.isChecked = false
        // guardar bitmap para posible muestra de plantilla
        lastOcrBitmap = bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)

        // Guardar copia de la foto y añadir a miniaturas (sincronizado Path handling)
        saveBitmapAndAdd(bitmap)

        // TFLite embedding inference - Disabled/Not implemented
        // Ejecutar preprocesado OpenCV antes del OCR (deskew / recorte / mejora)
        lifecycleScope.launch {
            val preprocessedBitmap = withContext(Dispatchers.IO) {
                try {
                    val outDir = requireContext().getExternalFilesDir("templates_debug")
                    outDir?.mkdirs()
                    val outPdf = java.io.File(outDir, "preproc_${System.currentTimeMillis()}.pdf")
                    val (procBmp, pdfFile) = com.albacontrol.ocr.DocumentPreprocessorOpenCv.preprocessAndSavePdfWithOpenCv(requireContext(), bitmap, outPdf)
                    try {
                        pdfFile?.absolutePath?.let { path ->
                            synchronized(preprocPdfs) { if (!preprocPdfs.contains(path)) preprocPdfs.add(path) }
                            android.util.Log.d("AlbaTpl", "onImageCaptured: registered preproc pdf=$path")
                        }
                    } catch (_: Exception) {}
                    procBmp ?: bitmap
                } catch (e: Exception) {
                    bitmap
                }
            }

            // Object detector (TensorFlow Lite) - Disabled/Not implemented
            // The app uses ML Kit OCR + template matching instead
            // Future enhancement: could add field detection model here

            // Process with Tesseract OCR (optimized for photographed documents)
            processWithTesseractOcr(preprocessedBitmap)
        }
    }
    
    /**
     * Process image with Tesseract OCR (optimized for photographed documents)
     */
    private fun processWithTesseractOcr(bitmap: android.graphics.Bitmap) {
        lifecycleScope.launch {
            try {
                // Show progress dialog
                val progressDialog = android.app.ProgressDialog(requireContext()).apply {
                    setMessage("Procesando documento...")
                    setCancelable(false)
                    show()
                }

                // Process with OCR via centralized OcrProcessor (uses Tesseract under the hood)
                val tesseractResult = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.suspendCancellableCoroutine<com.albacontrol.ml.OCRResult?> { cont ->
                        try {
                            com.albacontrol.ml.OcrProcessor.processBitmap(bitmap) { res, err ->
                                if (err != null) cont.resumeWith(Result.failure(err))
                                else cont.resumeWith(Result.success(res))
                            }
                        } catch (e: Exception) {
                            cont.resumeWith(Result.failure(e))
                        }
                    }
                }

                progressDialog.dismiss()

                if (tesseractResult != null) {
                    Log.d("AlbaTpl", "Tesseract OCR: success - proveedor='${tesseractResult.proveedor}' nif='${tesseractResult.nif}' numero='${tesseractResult.numeroAlbaran}' fecha='${tesseractResult.fechaAlbaran}'")
                    lastOcrResult = tesseractResult
                    
                    // IMPORTANTE: Aplicar plantilla ANTES de OCR para que la plantilla tenga prioridad
                    try {
                        Log.d("AlbaTpl", "Attempting to apply template before OCR form population")
                        applyTemplateIfExists(tesseractResult, bitmap)
                    } catch (e: Exception) {
                        Log.e("AlbaTpl", "Error applying template: ${e.message}", e)
                    }
                    
                    // Luego aplicar OCR solo si no hay plantilla o para campos vacíos
                    applyOcrResultToForm(tesseractResult)
                } else {
                    Log.e("AlbaTpl", "Tesseract OCR: failed to process image")
                    Toast.makeText(requireContext(), "Error procesando documento. Intente de nuevo.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("AlbaTpl", "Tesseract OCR: error: ${e.message}")
                Toast.makeText(requireContext(), "Error en OCR: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveBitmapAndAdd(bitmap: android.graphics.Bitmap) {
        lifecycleScope.launch {
            try {
                var path: String? = null
                withContext(Dispatchers.IO) {
                    val outDir = requireContext().getExternalFilesDir("history_photos")
                    outDir?.mkdirs()
                    val f = java.io.File(outDir, "photo_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { fos ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                    path = f.absolutePath
                }
                if (!path.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        photoPaths.add(0, path!!)
                        updatePhotoThumbnails()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updatePhotoThumbnails() {
        try {
            val container = view?.findViewById<LinearLayout>(R.id.photo_container) ?: return
            container.removeAllViews()
            val dp = requireContext().resources.displayMetrics.density
            val size = (80 * dp).toInt()
            val margin = (6 * dp).toInt()
            for ((index, path) in photoPaths.withIndex()) {
                try {
                    val frame = FrameLayout(requireContext())
                    val flp = LinearLayout.LayoutParams(size, size)
                    flp.setMargins(margin, margin, margin, margin)
                    frame.layoutParams = flp

                    val iv = ImageView(requireContext())
                    val ivLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    iv.layoutParams = ivLp
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    val bmp = android.graphics.BitmapFactory.decodeFile(path)
                    if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(android.R.drawable.ic_menu_report_image)

                    val btn = ImageButton(requireContext())
                    val btnSize = (22 * dp).toInt()
                    val btnLp = FrameLayout.LayoutParams(btnSize, btnSize)
                    btnLp.gravity = android.view.Gravity.END or android.view.Gravity.TOP
                    btn.layoutParams = btnLp
                    btn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    btn.setBackgroundResource(android.R.color.transparent)
                    btn.scaleType = ImageView.ScaleType.CENTER

                    // clic para ver en grande
                    iv.setOnClickListener {
                        try {
                            val dialog = android.app.Dialog(requireContext())
                            val iv2 = ImageView(requireContext())
                            iv2.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
                            iv2.adjustViewBounds = true
                            dialog.setContentView(iv2)
                            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            dialog.show()
                        } catch (_: Exception) {}
                    }

                    // eliminar con confirmación y opción deshacer (Snackbar)
                    btn.setOnClickListener {
                        try {
                            val removedIndex = photoPaths.indexOf(path)
                            if (removedIndex >= 0) {
                                // remove from list and update UI
                                photoPaths.removeAt(removedIndex)
                                updatePhotoThumbnails()

                                val handler = Handler(Looper.getMainLooper())
                                val delRunnable = Runnable {
                                    try { java.io.File(path).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
                                    pendingDeletionRunnables.remove(path)
                                }
                                pendingDeletionRunnables[path] = delRunnable
                                handler.postDelayed(delRunnable, 5000)

                                // show snackbar with undo
                                val rootView = requireActivity().findViewById<View>(android.R.id.content) ?: requireView()
                                Snackbar.make(rootView, getString(com.albacontrol.R.string.photo_deleted), Snackbar.LENGTH_LONG).setAction(getString(com.albacontrol.R.string.undo)) {
                                    // cancel deletion and restore
                                    pendingDeletionRunnables[path]?.let { handler.removeCallbacks(it) }
                                    pendingDeletionRunnables.remove(path)
                                    val insertAt = removedIndex.coerceAtMost(photoPaths.size)
                                    photoPaths.add(insertAt, path)
                                    updatePhotoThumbnails()
                                }.show()
                            }
                        } catch (_: Exception) {}
                    }

                    frame.addView(iv)
                    frame.addView(btn)
                    container.addView(frame)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun applyTemplateForProvider(nifOrName: String) {
        val bmp = lastOcrBitmap ?: return
        val result = lastOcrResult 
        
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
            val templates = db.templateDao().getAllTemplates()
            
            // Find best matching template for the typed text
            val normInput = nifOrName.trim().lowercase()
            val bestTpl = templates.find { 
                val tNif = it.providerNif.trim().lowercase()
                tNif == normInput || (normInput.length > 4 && tNif.contains(normInput))
            }

            if (bestTpl != null) {
                applySpecificTemplate(bestTpl, bmp, result, overwrite = false)
            }
        }
    }

    private suspend fun applyTemplateIfExists(result: com.albacontrol.ml.OCRResult, bitmap: android.graphics.Bitmap) {
        // NO verificar autoPopulateDone aquí - las plantillas deben aplicarse siempre que existan
        Log.d("AlbaTpl", "applyTemplateIfExists: entry - candidates nif='${result.nif}' proveedor='${result.proveedor}'")
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())

        // Construir un conjunto amplio de candidatos a partir de todo el texto OCR disponible
        // Usar OcrUtils para consistencia en la normalización
        fun normalizeKey(s: String?): String {
            if (s == null) return ""
            return com.albacontrol.util.OcrUtils.normalizeToken(s)
        }

        val candidatesSet = mutableSetOf<String>()
        // NIF y proveedor (normalizados eliminando no alfanuméricos)
        result.nif?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }
        result.proveedor?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }

        // otros campos identificadores
        result.numeroAlbaran?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }
        result.fechaAlbaran?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }

        // campos de productos: descripcion, unidades, precio, importe
        for (p in result.products) {
            p.descripcion?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }
            p.unidades?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }
            p.precio?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }
            p.importe?.trim()?.takeIf { it.isNotEmpty() }?.let { candidatesSet.add(normalizeKey(it)) }
        }

        // texto completo (si está disponible), dividir en tokens significativos
        result.allBlocks.forEach { (text, _) ->
            text.split(Regex("\\s+")).forEach { tkn -> if (tkn.length >= 4) candidatesSet.add(normalizeKey(tkn)) }
        }

                // remove blank candidates in a way compatible with older Android APIs
                val iter = candidatesSet.iterator()
                while (iter.hasNext()) {
                    val v = iter.next()
                    if (v == null || v.isBlank()) {
                        iter.remove()
                    }
                }
        if (candidatesSet.isEmpty()) {
            Log.d("AlbaTpl", "no candidates extracted from OCR to match templates")
            return
        }

        Log.d("AlbaTpl", "template matching candidatesSet=$candidatesSet")
        val templates = withContext(Dispatchers.IO) { db.templateDao().getAllTemplates() }
        val allSamples = try { withContext(Dispatchers.IO) { db.templateDao().getAllSamples() } } catch (_: Exception) { emptyList() }

        // helper: parse embedding CSV stored in normalizedFields["embedding"]
        fun parseEmbeddingCsv(csv: String?): FloatArray? {
            if (csv.isNullOrBlank()) return null
            try {
                val parts = csv.split(',').mapNotNull { it.toFloatOrNull() }
                if (parts.isEmpty()) return null
                return parts.toFloatArray()
            } catch (_: Exception) { return null }
        }

        fun cosineSim(a: FloatArray, b: FloatArray): Double {
            try {
                val n = minOf(a.size, b.size)
                if (n == 0) return 0.0
                var dot = 0.0
                var na = 0.0
                var nb = 0.0
                for (i in 0 until n) {
                    val av = a[i].toDouble()
                    val bv = b[i].toDouble()
                    dot += av * bv
                    na += av * av
                    nb += bv * bv
                }
                if (na <= 0.0 || nb <= 0.0) return 0.0
                return dot / (Math.sqrt(na) * Math.sqrt(nb))
            } catch (_: Exception) { return 0.0 }
        }

        // helper: extract only digits
        fun digitsOnly(s: String): String = s.filter { it.isDigit() }

        val candidatesList = candidatesSet.toList()

        // Local scoring helpers that combine IoU, text match and embedding similarity
        fun computeTemplateScore(t: com.albacontrol.data.OCRTemplate): Double {
            try {
                val pNorm = normalizeKey(t.providerNif)
                val pDigits = digitsOnly(pNorm)
                var textMatches = 0
                var bestTextMatch = 0.0
                
                if (pNorm.isNotBlank()) {
                    for (cand in candidatesList) {
                        val candNorm = normalizeKey(cand)
                        val candDigits = digitsOnly(candNorm)
                        
                        // Exact match (score 1.0)
                        if (candNorm == pNorm) {
                            textMatches++
                            bestTextMatch = kotlin.math.max(bestTextMatch, 1.0)
                            continue
                        }
                        
                        // Contains match (score 0.8-0.95 based on length ratio)
                        if (candNorm.contains(pNorm) || pNorm.contains(candNorm)) {
                            // Dar mejor score si la longitud es similar (más específico)
                            val lenRatio = minOf(candNorm.length, pNorm.length).toDouble() / maxOf(candNorm.length, pNorm.length).toDouble()
                            val score = 0.8 + (lenRatio * 0.15) // 0.8 a 0.95
                            textMatches++
                            bestTextMatch = kotlin.math.max(bestTextMatch, score)
                            continue
                        }
                        
                        // NIF digit matching (score 0.6-0.9 based on overlap)
                        if (pDigits.isNotBlank() && candDigits.isNotBlank()) {
                            if (pDigits == candDigits) {
                                textMatches++
                                bestTextMatch = kotlin.math.max(bestTextMatch, 0.9)
                            } else {
                                val overlap = calculateDigitOverlap(pDigits, candDigits)
                                if (overlap > 0.5) {
                                    textMatches++
                                    bestTextMatch = kotlin.math.max(bestTextMatch, 0.6 + overlap * 0.3)
                                }
                            }
                        }
                        
                        // Fuzzy match usando OcrUtils (score 0.5-0.7)
                        // Threshold reducido de 0.7 a 0.5 para ser más permisivo
                        if (com.albacontrol.util.OcrUtils.fuzzyContains(candNorm, pNorm, 0.5)) {
                            val ratio = levenshteinRatio(candNorm, pNorm)
                            textMatches++
                            bestTextMatch = kotlin.math.max(bestTextMatch, 0.5 + ratio * 0.2)
                        }
                    }
                }
                
                // Usar el mejor match en lugar de binario
                val textScore = bestTextMatch
                Log.d("AlbaTpl", "Template '${t.providerNif}': textScore=$textScore (matches=$textMatches)")

                var iouSum = 0.0
                var iouCount = 0
                val bwf = bitmap.width.toFloat()
                val bhf = bitmap.height.toFloat()
                val fieldPairs = listOf(
                    "proveedor" to (result.proveedorBBox),
                    "nif" to (result.nifBBox),
                    "numero_albaran" to (result.numeroBBox),
                    "fecha_albaran" to (result.fechaBBox),
                    "product_row" to null
                )
                for ((k, detectedRect) in fieldPairs) {
                    val tplB = t.mappings[k]
                    val tplRect = rectFromNormalized(tplB, bwf, bhf)
                    if (tplRect != null && detectedRect != null) {
                        val overlap = iou(tplRect, detectedRect)
                        iouSum += overlap
                        iouCount++
                    }
                }
                val iouScore = if (iouCount == 0) 0.0 else iouSum / iouCount.toDouble()

                // Embedding score removed (not used)
                val embScore = 0.0

                val wIou = com.albacontrol.data.TemplateLearningConfig.IOU_WEIGHT
                val wText = com.albacontrol.data.TemplateLearningConfig.TEXT_SIM_WEIGHT
                val totalW = wIou + wText
                if (totalW <= 0.0) return 0.0
                
                val finalScore = (wIou * iouScore + wText * textScore) / totalW
                Log.d("AlbaTpl", "Template '${t.providerNif}': finalScore=$finalScore (iou=$iouScore text=$textScore emb=$embScore)")
                return finalScore
            } catch (e: Exception) { 
                Log.e("AlbaTpl", "Error computing template score for '${t.providerNif}': ${e.message}", e)
                return 0.0 
            }
        }

        var bestTpl: com.albacontrol.data.OCRTemplate? = null
        var bestScore = 0.0
        for (t in templates) {
            try {
                val score = computeTemplateScore(t)
                if (score > bestScore) {
                    bestScore = score
                    bestTpl = t
                }
            } catch (_: Exception) {}
        }

        Log.d("AlbaTpl", "Template matching complete: bestScore=$bestScore threshold=${com.albacontrol.data.TemplateLearningConfig.SCORE_APPLY_THRESHOLD} bestTpl=${bestTpl?.providerNif}")

        if (bestTpl != null && bestScore >= com.albacontrol.data.TemplateLearningConfig.SCORE_APPLY_THRESHOLD) {
             // Show feedback to user about template application
             withContext(Dispatchers.Main) {
                 val sampleCount = try {
                     val bestTplKey = normalizeProviderKey(bestTpl.providerNif)
                     allSamples.count { normalizeProviderKey(it.providerNif) == bestTplKey }
                 } catch (_: Exception) { 0 }
                // Plantilla aplicada silenciosamente (sin toast para usuario final)
            }
            Log.d("AlbaTpl", "Applying template '${bestTpl.providerNif}' with score $bestScore")
             applySpecificTemplate(bestTpl, bitmap, result)
             // Marcar que ya se aplicó contenido para evitar que OCR sobrescriba
             autoPopulateDone = true
             Log.d("AlbaTpl", "Template applied, set autoPopulateDone=true")
        } else {
             if (bestTpl != null) {
                 Log.d("AlbaTpl", "Template '${bestTpl.providerNif}' found but score $bestScore below threshold ${com.albacontrol.data.TemplateLearningConfig.SCORE_APPLY_THRESHOLD}")
             } else {
                 Log.d("AlbaTpl", "No matching template found (checked ${templates.size} templates)")
             }
        }
    }

    private suspend fun applySpecificTemplate(chosenTpl: com.albacontrol.data.OCRTemplate, bitmap: android.graphics.Bitmap, result: com.albacontrol.ml.OCRResult?, overwrite: Boolean = true) {
        Log.d("AlbaTpl", "applySpecificTemplate provider='${chosenTpl.providerNif}'")

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // 1. Calculate Alignment Offset
        var deltaX = 0f
        var deltaY = 0f
        
        if (result != null) {
            // Try to find the template's anchor text in the current OCR blocks
            // Priority: NIF, then Provider
            val anchors = listOf(
                "nif" to chosenTpl.providerNif,
                "proveedor" to (chosenTpl.mappings["proveedor_text"] ?: "") // We might not have provider name stored separately, but we can try matching the key if needed. For now rely on NIF.
            )

            // Helper to find a block matching the text
            fun findBlockForText(target: String): android.graphics.Rect? {
                val t = target.trim().lowercase()
                if (t.length < 3) return null
                // 1. Exact match
                result.allBlocks.firstOrNull { it.first.trim().lowercase() == t }?.let { return it.second }
                // 2. Contains match (if target is long enough)
                if (t.length > 4) {
                    result.allBlocks.firstOrNull { it.first.trim().lowercase().contains(t) }?.let { return it.second }
                }
                return null
            }

            // Multi-Anchor Alignment Strategy
            // We try to find ALL possible anchors to calculate a robust average offset
            val detectedOffsets = mutableListOf<Pair<Float, Float>>()
            
            // Helper to calculate center offset between template and detected
            fun addAnchorOffset(anchorName: String, tplMapping: String?, detectedRect: android.graphics.Rect?) {
                if (tplMapping != null && detectedRect != null) {
                    val parts = tplMapping.split(',')
                    if (parts.size == 4) {
                        try {
                            val tCx = parts[0].toFloat() + parts[2].toFloat() / 2f
                            val tCy = parts[1].toFloat() + parts[3].toFloat() / 2f
                            val dCx = detectedRect.centerX().toFloat() / bw
                            val dCy = detectedRect.centerY().toFloat() / bh
                            val dx = dCx - tCx
                            val dy = dCy - tCy
                            detectedOffsets.add(dx to dy)
                            Log.d("AlbaTpl", "Anchor '$anchorName' found: template_center=($tCx,$tCy) detected_center=($dCx,$dCy) delta=($dx, $dy)")
                        } catch (e: Exception) {
                            Log.d("AlbaTpl", "Anchor '$anchorName' parse error: ${e.message}")
                        }
                    }
                }
            }
            
            // 1. Anchor: NIF
            val tplNif = chosenTpl.providerNif
            val tplNifMapping = chosenTpl.mappings["nif"]
            if (!tplNif.isBlank() && tplNifMapping != null) {
                val detectedAnchorRect = findBlockForText(tplNif)
                addAnchorOffset("NIF", tplNifMapping, detectedAnchorRect)
            }

            // 2. Anchor: Provider Name (if available in current OCR)
            val tplProvMapping = chosenTpl.mappings["proveedor"]
            if (result.proveedorBBox != null && tplProvMapping != null) {
                addAnchorOffset("Provider", tplProvMapping, result.proveedorBBox)
            }
            
            // 3. Anchor: Invoice Number
            val tplNumMapping = chosenTpl.mappings["numero_albaran"]
            if (result.numeroBBox != null && tplNumMapping != null) {
                addAnchorOffset("InvoiceNum", tplNumMapping, result.numeroBBox)
            }
            
            // 4. Anchor: Date
            val tplDateMapping = chosenTpl.mappings["fecha_albaran"]
            if (result.fechaBBox != null && tplDateMapping != null) {
                addAnchorOffset("Date", tplDateMapping, result.fechaBBox)
            }
            
            // 5. Calculate Robust Offset (Median to handle outliers)
            if (detectedOffsets.isNotEmpty()) {
                val sortedX = detectedOffsets.map { it.first }.sorted()
                val sortedY = detectedOffsets.map { it.second }.sorted()
                
                // Use median instead of average to be robust against outliers
                fun median(list: List<Float>): Float {
                    return if (list.size % 2 == 1) {
                        list[list.size / 2]
                    } else {
                        (list[list.size / 2 - 1] + list[list.size / 2]) / 2f
                    }
                }
                
                deltaX = median(sortedX)
                deltaY = median(sortedY)
                Log.d("AlbaTpl", "Final Alignment (Median of ${detectedOffsets.size} anchors): deltaX=$deltaX, deltaY=$deltaY")
            } else {
                Log.w("AlbaTpl", "Alignment: No anchors found. Defaulting to 0 offset. This may cause misalignment.")
                // Intentar usar un offset basado en la posición del primer bloque OCR si está disponible
                if (result.allBlocks.isNotEmpty()) {
                    val firstBlock = result.allBlocks.first()
                    val firstRect = firstBlock.second
                    if (firstRect != null) {
                        // Usar un pequeño offset basado en la posición del primer bloque
                        // Esto es una heurística simple para documentos con desplazamiento pequeño
                        val estimatedOffsetX = (firstRect.left.toFloat() / bw) - 0.1f // Asumir que el documento empieza ~10% desde la izquierda
                        val estimatedOffsetY = (firstRect.top.toFloat() / bh) - 0.1f
                        deltaX = estimatedOffsetX.coerceIn(-0.2f, 0.2f) // Limitar el offset
                        deltaY = estimatedOffsetY.coerceIn(-0.2f, 0.2f)
                        Log.d("AlbaTpl", "Using estimated offset from first block: deltaX=$deltaX deltaY=$deltaY")
                    }
                }
            }
        }

        // Obtener las muestras originales para extraer el texto reconocido
        val samples = try {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(requireContext())
                db.templateDao().getAllSamples().filter {
                    normalizeProviderKey(it.providerNif) == normalizeProviderKey(chosenTpl.providerNif)
                }
            }
        } catch (e: Exception) {
            Log.e("AlbaTpl", "Failed to load samples: ${e.message}")
            emptyList()
        }
        
        // Extraer el texto más común de las muestras para cada campo
        val fieldTexts = mutableMapOf<String, String>()
        for (sample in samples) {
            for ((fieldName, mappingValue) in sample.fieldMappings) {
                // mappingValue tiene formato "bbox::recognizedText"
                val textPart = mappingValue.substringAfter("::", "").trim()
                // Filtrar valores vacíos, "null" literal, y otros valores inválidos
                if (textPart.isNotEmpty() && textPart != "null" && textPart.length > 1) {
                    fieldTexts[fieldName] = textPart
                }
            }
        }
        
        Log.d("AlbaTpl", "applyTemplate: extracted texts from samples: $fieldTexts")

        for ((field, bboxStr) in chosenTpl.mappings) {
            try {
                val parts = bboxStr.split(',')
                if (parts.size != 4) continue
                val x = parts[0].toFloat()
                val y = parts[1].toFloat()
                val w = parts[2].toFloat()
                val h = parts[3].toFloat()
                
                // Usar el texto guardado en las muestras en lugar de hacer OCR de nuevo
                val text = fieldTexts[field]
                Log.d("AlbaTpl", "applyTemplate: field=$field using stored text='$text'")
                
                        if (!text.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                val view = requireView()
                                when (field) {
                                    "proveedor" -> {
                                        val et = view.findViewById<EditText>(R.id.etProveedor)
                                if ((overwrite && (userEdited["proveedor"] != true)) || et.text.isBlank()) {
                                    et.setText(text)
                                    Log.d("AlbaTpl", "applyTemplate: set proveedor='$text'")
                                }
                                    }
                                    "nif" -> {
                                        val et = view.findViewById<EditText>(R.id.etNif)
                                if ((overwrite && (userEdited["nif"] != true)) || et.text.isBlank()) {
                                    et.setText(text)
                                    Log.d("AlbaTpl", "applyTemplate: set nif='$text'")
                                }
                                    }
                                    "numero_albaran" -> {
                                        val et = view.findViewById<EditText>(R.id.etNumeroAlbaran)
                                if ((overwrite && (userEdited["numero_albaran"] != true)) || et.text.isBlank()) {
                                    et.setText(text)
                                    Log.d("AlbaTpl", "applyTemplate: set numero_albaran='$text'")
                                }
                                    }
                                    "fecha_albaran" -> {
                                        val et = view.findViewById<EditText>(R.id.etFechaAlbaran)
                                if ((overwrite && (userEdited["fecha_albaran"] != true)) || et.text.isBlank()) {
                                    et.setText(text)
                                    Log.d("AlbaTpl", "applyTemplate: set fecha_albaran='$text'")
                                }
                                    }
                                    else -> {}
                                }
                            }
                } else {
                    Log.w("AlbaTpl", "applyTemplate: field=$field has no stored text")
                        }
            } catch (e: Exception) {
                Log.e("AlbaTpl", "applyTemplate: error processing field=$field: ${e.message}", e)
                    }
        }

        try {
            val pr = chosenTpl.mappings["product_row"]
            if (pr != null) {
                val parts = pr.split(',')
                if (parts.size == 4) {
                    val px = parts[0].toFloat() + deltaX
                    val py = parts[1].toFloat() + deltaY
                    val pw = parts[2].toFloat()
                    val ph = parts[3].toFloat()
                    val left = (px * bw).toInt().coerceIn(0, bitmap.width - 1)
                    val top = (py * bh).toInt().coerceIn(0, bitmap.height - 1)
                    val right = (left + (pw * bw).toInt()).coerceAtMost(bitmap.width)
                    val bottom = (top + (ph * bh).toInt()).coerceAtMost(bitmap.height)
                    if (right > left && bottom > top) {
                        val candidates = result?.products?.filter { it.bbox != null && android.graphics.Rect.intersects(it.bbox, android.graphics.Rect(left, top, right, bottom)) } ?: emptyList()
                        val rowsToUse = candidates 

                        val colKeys = chosenTpl.mappings.keys.filter { it.startsWith("product_") && it != "product_row" }
                        val cols = colKeys.mapNotNull { k -> chosenTpl.mappings[k]?.let { k to it } }.toMap()

                        if (rowsToUse.isNotEmpty() && cols.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                productContainer.removeAllViews()
                            }
                            for (p in rowsToUse) {
                                val rowB = p.bbox!!
                                val descText = p.descripcion
                                val unidadesText = StringBuilder()
                                val precioText = StringBuilder()
                                val importeText = StringBuilder()
                                for ((key, bboxStr) in cols) {
                                    try {
                                        val partsC = bboxStr.split(',')
                                        if (partsC.size != 4) continue
                                        val cx = partsC[0].toFloat() + deltaX
                                        val cy = partsC[1].toFloat() + deltaY 
                                        val cw = partsC[2].toFloat()
                                        val ch = partsC[3].toFloat()
                                        
                                        val rowTop = rowB.top
                                        val rowBottom = rowB.bottom
                                        
                                        val padCX = (cw * bw * 0.05f).toInt()
                                        val cleft = ((cx * bw).toInt() - padCX).coerceIn(0, bitmap.width - 1)
                                        val cright = ((cx * bw + cw * bw).toInt() + padCX).coerceAtMost(bitmap.width)
                                        
                                        if (cright <= cleft) continue
                                        
                                        val crop = android.graphics.Bitmap.createBitmap(bitmap, cleft, rowTop, cright - cleft, rowBottom - rowTop)
                                        val txt = suspendCancellableCoroutine<String> { continuation ->
                                            com.albacontrol.ml.OcrProcessor.processBitmap(crop) { mlKitResult, error ->
                                                if (error != null) {
                                                    Log.e("AlbaTpl", "applyTemplate: product OCR error for key=$key: ${error.message}")
                                                    continuation.resume("")
                                                } else if (mlKitResult != null) {
                                                    continuation.resume(mlKitResult.allBlocks.joinToString(" ") { it.first }.trim())
                                                } else {
                                                    continuation.resume("")
                                                }
                                            }
                                        }
                                        when (key) {
                                            "product_unidades" -> unidadesText.append(txt)
                                            "product_precio" -> precioText.append(txt)
                                            "product_importe" -> importeText.append(txt)
                                            else -> {}
                                        }
                                    } catch (_: Exception) {}
                                }
                                withContext(Dispatchers.Main) {
                                    val inflater = LayoutInflater.from(requireContext())
                                    val item = inflater.inflate(R.layout.product_item, productContainer, false)
                                    item.findViewById<EditText>(R.id.etDescripcion).setText(descText)
                                    item.findViewById<EditText>(R.id.etUnidades).setText(unidadesText.toString())
                                    item.findViewById<EditText>(R.id.etPrecio).setText(precioText.toString())
                                    item.findViewById<EditText>(R.id.etImporte).setText(importeText.toString())
                                    attachWatchersToProductItem(item)
                                    productContainer.addView(item)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        
        // Aplicar productos guardados directamente desde las muestras (sistema mejorado basado en descripción)
        // IMPORTANTE: Solo rellenar datos (unidades, precio, importe) de productos que coinciden
        // NO añadir productos guardados que no están en el OCR actual
        try {
            // Extraer todos los productos guardados (soporta formato antiguo product_0_* y nuevo product_desc_*)
            val savedProducts = mutableMapOf<String, MutableMap<String, String>>()
            
            for (key in fieldTexts.keys) {
                if (key.startsWith("product_") && key.contains("_")) {
                    val parts = key.removePrefix("product_").split("_", limit = 2)
                    if (parts.size == 2) {
                        val productKey = parts[0]
                        val fieldType = parts[1]
                        
                        // Si es formato antiguo (product_0_desc, product_1_desc), usar índice como clave temporal
                        // Si es formato nuevo (product_desc_normalizada), usar la clave normalizada
                        val finalKey = if (productKey.matches(Regex("^\\d+$"))) {
                            // Formato antiguo: usar descripción del campo para crear clave normalizada
                            val desc = fieldTexts["product_${productKey}_desc"] ?: ""
                            if (desc.isNotEmpty()) normalizeProductName(desc) else "old_$productKey"
                        } else {
                            // Formato nuevo: usar la clave directamente
                            productKey
                        }
                        
                        if (!savedProducts.containsKey(finalKey)) {
                            savedProducts[finalKey] = mutableMapOf()
                        }
                        savedProducts[finalKey]!![fieldType] = fieldTexts[key] ?: ""
                    }
                }
            }
            
            if (savedProducts.isNotEmpty()) {
                Log.d("AlbaTpl", "applyTemplate: found ${savedProducts.size} unique products in samples")
                
                withContext(Dispatchers.Main) {
                    // Matching fuzzy: emparejar productos del contenedor (del OCR actual) con productos guardados
                    val matched = mutableSetOf<String>() // Claves de productos guardados ya emparejados
                    val SIMILARITY_THRESHOLD = 0.7 // 70% de similitud mínima
                    
                    // Iterar sobre productos actuales en el contenedor (del OCR)
                    for (i in 0 until productContainer.childCount) {
                        val item = productContainer.getChildAt(i)
                        val currentDesc = item.findViewById<EditText>(R.id.etDescripcion).text.toString().trim()
                        
                        if (currentDesc.isNotEmpty()) {
                            // Buscar el producto guardado más similar
                            var bestMatch: String? = null
                            var bestSimilarity = 0.0
                            var bestFields: MutableMap<String, String>? = null
                            
                            for ((productKey, fields) in savedProducts) {
                                if (matched.contains(productKey)) continue // Ya emparejado
                                
                                val savedDesc = fields["desc"] ?: ""
                                if (savedDesc.isEmpty()) continue
                                
                                val similarity = productNameSimilarity(currentDesc, savedDesc)
                                if (similarity > bestSimilarity && similarity >= SIMILARITY_THRESHOLD) {
                                    bestSimilarity = similarity
                                    bestMatch = productKey
                                    bestFields = fields
                                }
                            }
                            
                            // Si encontramos un match, aplicar SOLO los datos guardados (unidades, precio, importe)
                            // MANTENER la descripción del OCR actual (más precisa)
                            if (bestMatch != null && bestFields != null) {
                                // NO sobrescribir descripción - mantener la del OCR actual
                                // Solo rellenar unidades, precio e importe si están vacíos o si el match es muy bueno
                                val etUnidades = item.findViewById<EditText>(R.id.etUnidades)
                                val etPrecio = item.findViewById<EditText>(R.id.etPrecio)
                                val etImporte = item.findViewById<EditText>(R.id.etImporte)
                                
                                // Solo rellenar si el campo está vacío o si el match es excelente (>90%)
                                if (bestSimilarity > 0.9 || etUnidades.text.isBlank()) {
                                    etUnidades.setText(bestFields["units"] ?: "")
                                }
                                if (bestSimilarity > 0.9 || etPrecio.text.isBlank()) {
                                    etPrecio.setText(bestFields["price"] ?: "")
                                }
                                if (bestSimilarity > 0.9 || etImporte.text.isBlank()) {
                                    etImporte.setText(bestFields["total"] ?: "")
                                }
                                
                                matched.add(bestMatch)
                                Log.d("AlbaTpl", "applyTemplate: matched product '$currentDesc' -> '${bestFields["desc"]}' (similarity=${"%.2f".format(bestSimilarity)}) - filled units/price/total")
                            } else if (currentDesc.isNotEmpty()) {
                                Log.d("AlbaTpl", "applyTemplate: product '$currentDesc' has no match in saved products (threshold=$SIMILARITY_THRESHOLD)")
                            }
                        }
                    }
                    
                    // NO añadir productos guardados que no se emparejaron
                    // Solo mostrar en log para debugging
                    val unmatchedCount = savedProducts.size - matched.size
                    if (unmatchedCount > 0) {
                        Log.d("AlbaTpl", "applyTemplate: $unmatchedCount saved products not found in current OCR (not adding to avoid duplicates)")
                    }
                }
            } else {
                Log.d("AlbaTpl", "applyTemplate: no products found in samples")
            }
        } catch (e: Exception) {
            Log.e("AlbaTpl", "applyTemplate: error applying products: ${e.message}", e)
        }
        
        try {
            val providerKey = chosenTpl.providerNif.trim().lowercase()
            if (providerKey.isNotBlank()) {
                val prefs = requireContext().getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
                val cooldownKey = "last_auto_sample_${'$'}{providerKey}"
                val lastSaved = prefs.getLong(cooldownKey, 0L)
                val now = System.currentTimeMillis()
                val cooldown = com.albacontrol.data.TemplateLearningConfig.AUTO_SAVE_COOLDOWN_MS
                if (now - lastSaved >= cooldown) {
                     val saveJob = saveSampleFromOcr()
                     try { saveJob.join() } catch (_: Exception) {}
                     prefs.edit().putLong(cooldownKey, now).apply()
                }
            }
        } catch (_: Exception) {}
    }

    private fun applyOcrResultToForm(result: com.albacontrol.ml.OCRResult) {
        // Verificar si ya se aplicó para evitar sobrescribir ediciones del usuario
        if (autoPopulateDone) {
            Log.d("AlbaTpl", "applyOcrResultToForm: skipped because autoPopulateDone=true (user already has data)")
            return
        }
        val view = requireView()
        val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
        val etNif = view.findViewById<EditText>(R.id.etNif)
        val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
        val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)

        // Only populate OCR results if the field is empty or the user hasn't edited it
        try {
            if (!result.proveedor.isNullOrBlank() && (etProveedor.text.isBlank() || userEdited["proveedor"] != true)) etProveedor.setText(result.proveedor)
        } catch (_: Exception) {}
        try {
            if (!result.nif.isNullOrBlank() && (etNif.text.isBlank() || userEdited["nif"] != true)) etNif.setText(result.nif)
        } catch (_: Exception) {}
        try {
            if (!result.numeroAlbaran.isNullOrBlank() && (etNumero.text.isBlank() || userEdited["numero_albaran"] != true)) etNumero.setText(result.numeroAlbaran)
        } catch (_: Exception) {}
        try {
            if (!result.fechaAlbaran.isNullOrBlank() && (etFechaAlb.text.isBlank() || userEdited["fecha_albaran"] != true)) etFechaAlb.setText(result.fechaAlbaran)
        } catch (_: Exception) {}

        // Añadir productos detectados: borrar los vacíos iniciales y añadir los detectados
        if (!productsManuallyEdited) {
            productContainer.removeAllViews()
            if (result.products.isEmpty()) {
                addProductBox()
            } else {
                for (p in result.products) {
                    val inflater = LayoutInflater.from(requireContext())
                    val item = inflater.inflate(R.layout.product_item, productContainer, false)
                    item.findViewById<EditText>(R.id.etDescripcion).setText(p.descripcion)
                    item.findViewById<EditText>(R.id.etUnidades).setText(p.unidades ?: "")
                    item.findViewById<EditText>(R.id.etPrecio).setText(p.precio ?: "")
                    item.findViewById<EditText>(R.id.etImporte).setText(p.importe ?: "")
                    val btnDelete = item.findViewById<ImageButton>(R.id.btnDeleteProduct)
                    btnDelete.setOnClickListener {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.delete_product_title))
                            .setMessage(getString(R.string.confirm_delete_this_product))
                            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                                productsManuallyEdited = true
                                productContainer.removeView(item)
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .create()
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                        }
                        dialog.show()
                    }
                    // attach watchers for auto-save
                    attachWatchersToProductItem(item)
                    productContainer.addView(item)
                }
            }
        } else {
            Log.d("AlbaTpl", "applyOcrResultToForm: products skipped auto-population because user edited products")
        }

        // Mark that we've auto-populated the form from OCR or template
        // Further automatic updates should not overwrite user edits
        autoPopulateDone = true
        Log.d("AlbaTpl", "applyOcrResultToForm: completed, set autoPopulateDone=true")
    }

    private fun savePatternFromCorrections(): kotlinx.coroutines.Job {
        Log.d("AlbaTpl", "savePatternFromCorrections: entry")
        val view = requireView()
        val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
        val etNif = view.findViewById<EditText>(R.id.etNif)

        val providerKeyRaw = etNif.text.toString().ifBlank { etProveedor.text.toString() }
        val providerKey = normalizeProviderKey(providerKeyRaw)
        // Capturar valores en variables locales inmutables para evitar problemas de smart cast/concurrencia
        val result = lastOcrResult
        val bmp = lastOcrBitmap
        if (result == null || bmp == null) {
            Log.d("AlbaTpl", "savePatternFromCorrections: missing result or bitmap or products - aborting")
            return lifecycleScope.launch { }
        }

        // sólo guardar si el usuario ha corregido al menos un campo relevante
        val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
        val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)
        val etProveedorText = etProveedor.text.toString().trim()
        val etNifText = etNif.text.toString().trim()
        val etNumeroText = etNumero.text.toString().trim()
        val etFechaText = etFechaAlb.text.toString().trim()

        var corrections = 0
        if (!result.proveedor.isNullOrBlank() && result.proveedor.trim() != etProveedorText) corrections++
        if (!result.nif.isNullOrBlank() && result.nif.trim() != etNifText) corrections++
        if (!result.numeroAlbaran.isNullOrBlank() && result.numeroAlbaran.trim() != etNumeroText) corrections++
        if (!result.fechaAlbaran.isNullOrBlank() && result.fechaAlbaran.trim() != etFechaText) corrections++
        // también comprobar productos: si hay diferencias en al menos una línea
        val prodDiff = run {
            try {
                val detected = result.products
                for (i in 0 until productContainer.childCount) {
                    val item = productContainer.getChildAt(i)
                    val desc = item.findViewById<EditText>(R.id.etDescripcion).text.toString().trim()
                    val unid = item.findViewById<EditText>(R.id.etUnidades).text.toString().trim()
                    val precio = item.findViewById<EditText>(R.id.etPrecio).text.toString().trim()
                    val imp = item.findViewById<EditText>(R.id.etImporte).text.toString().trim()
                    if (i < detected.size) {
                        val p = detected[i]
                        if ((p.descripcion?.trim() ?: "") != desc) return@run true
                        if ((p.unidades ?: "") != unid) return@run true
                        if ((p.precio ?: "") != precio) return@run true
                        if ((p.importe ?: "") != imp) return@run true
                    } else {
                        if (desc.isNotEmpty() || unid.isNotEmpty() || precio.isNotEmpty() || imp.isNotEmpty()) return@run true
                    }
                }
            } catch (_: Exception) {}
            false
        }
        if (prodDiff) corrections++

        if (corrections == 0) {
            Log.d("AlbaTpl", "savePatternFromCorrections: no corrections detected - aborting")
            return lifecycleScope.launch { }
        }

        val mappings = mutableMapOf<String, String>()
        
        // Helper to find bbox for text in allBlocks
        // Helper to find bbox for text in allBlocks
        fun findBBox(text: String, originalText: String?, originalBBox: android.graphics.Rect?): android.graphics.Rect? {
            val t = text.trim()
            if (t.isEmpty()) return null
            
            Log.d("AlbaTpl", "findBBox: searching for text='$t' (original='$originalText')")
            
            // 1. If text matches original OCR result exactly, trust the original bbox (optimization)
            if (originalText != null && t == originalText.trim()) {
                Log.d("AlbaTpl", "findBBox: exact match with original, using originalBBox=$originalBBox")
                return originalBBox
            }
            
            // Helper: Normalize text (delegate to centralized utility)
            fun normalize(s: String): String {
                return com.albacontrol.util.OcrUtils.normalizeToken(s)
            }
            val tNorm = normalize(t)
            
            // 2. Search in allBlocks (Exact Normalized Match)
            val exactMatches = result.allBlocks.filter { normalize(it.first) == tNorm }
            if (exactMatches.isNotEmpty()) {
                val bbox = exactMatches.minByOrNull { it.second.width() * it.second.height() }?.second
                Log.d("AlbaTpl", "findBBox: found exact normalized match, bbox=$bbox")
                return bbox
            }
            
            // 3. Multi-word Union Strategy with Spatial Proximity (handle multi-word names)
            // Clean punctuation but keep letters/digits; remove dots inside acronyms so "S.A.U" -> "SAU"
            val cleaned = t.replace(Regex("[,;:\\-]"), " ").replace(".", "")
            val words = cleaned.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (words.size > 1) {
                Log.d("AlbaTpl", "findBBox: multi-word search for ${words.size} words: $words (cleaned='$cleaned')")
                val foundRects = mutableListOf<android.graphics.Rect>()
                
                for (word in words) {
                    val wNorm = normalize(word)
                    var bestWordRect: android.graphics.Rect? = null
                    var bestMatchType = ""
                    
                    // First pass: exact match
                                        for (entry in result.allBlocks) {
                                            val bText: String = entry.first
                                            val bRect: android.graphics.Rect = entry.second
                                            val bNorm = normalize(bText)
                                            if (bNorm == wNorm) {
                                                bestWordRect = bRect
                                                bestMatchType = "exact"
                                                break
                                            }
                                        }
                    
                    // Second pass: fuzzy contains match using OcrUtils (tolerant to OCR noise)
                    // Reducir threshold para ser más permisivo
                    if (bestWordRect == null) {
                        for (entry in result.allBlocks) {
                            val bText: String = entry.first
                            val bRect: android.graphics.Rect = entry.second
                            // use fuzzyContains which normalizes and applies Levenshtein ratio
                            // Threshold reducido de 0.6 a 0.4 para ser más permisivo
                            if (com.albacontrol.util.OcrUtils.fuzzyContains(bText, word, 0.4)) {
                                // Check spatial proximity to already found words
                                if (foundRects.isNotEmpty()) {
                                    val avgY = foundRects.map { r: android.graphics.Rect -> r.centerY() }.average()
                                    val avgX = foundRects.map { r: android.graphics.Rect -> r.centerX() }.average()
                                    val distY = kotlin.math.abs(bRect.centerY() - avgY)
                                    val distX = kotlin.math.abs(bRect.centerX() - avgX)
                                    // Only accept if vertically close (same line) and horizontally reasonable
                                    if (distY < bRect.height() * 2 && distX < bmp.width * 0.5) {
                                        bestWordRect = bRect
                                        bestMatchType = "fuzzyContains+proximity"
                                        break
                                    }
                                } else {
                                    bestWordRect = bRect
                                    bestMatchType = "fuzzyContains"
                                    break
                                }
                            }
                        }
                    }

                    // Third pass: per-word fuzzy match (Levenshtein) with relaxed threshold
                    if (bestWordRect == null) {
                        var bestDistWord = Int.MAX_VALUE
                        var bestRectWord: android.graphics.Rect? = null
                        for (entry in result.allBlocks) {
                            val bText: String = entry.first
                            val bRect: android.graphics.Rect = entry.second
                            val bNorm = normalize(bText)
                            if (bNorm.isEmpty()) continue
                            val maxLen = kotlin.math.max(wNorm.length, bNorm.length)
                            if (bNorm.length < (wNorm.length * 0.25).toInt()) continue
                            fun levenshteinWord(a: CharSequence, b: CharSequence): Int {
                                val aLen = a.length
                                val bLen = b.length
                                var costs = IntArray(aLen + 1) { it }
                                var newCosts = IntArray(aLen + 1)
                                for (i in 1..bLen) {
                                    newCosts[0] = i
                                    for (j in 1..aLen) {
                                        val match = if (a[j - 1] == b[i - 1]) 0 else 1
                                        val costReplace = costs[j - 1] + match
                                        val costInsert = costs[j] + 1
                                        val costDelete = newCosts[j - 1] + 1
                                        newCosts[j] = kotlin.math.min(kotlin.math.min(costInsert, costDelete), costReplace)
                                    }
                                    val tmpCosts = costs
                                    costs = newCosts
                                    newCosts = tmpCosts
                                }
                                return costs[aLen]
                            }
                            val dist = levenshteinWord(wNorm, bNorm)
                            val maxDist = kotlin.math.max(1, (wNorm.length * 0.6).toInt())
                            if (dist <= maxDist && dist < bestDistWord) {
                                bestDistWord = dist
                                bestRectWord = bRect
                            }
                        }
                        if (bestRectWord != null) {
                            bestWordRect = bestRectWord
                            bestMatchType = "fuzzyWord"
                        }
                    }
                    
                    if (bestWordRect != null) {
                        foundRects.add(bestWordRect)
                        Log.d("AlbaTpl", "findBBox: word '$word' found ($bestMatchType) at $bestWordRect")
                    } else {
                        Log.d("AlbaTpl", "findBBox: word '$word' NOT found")
                    }
                }
                
                // If we found at least ~33% of the words, create union bbox (more lenient)
                if (foundRects.size >= kotlin.math.max(1, (words.size + 2) / 3)) {
                    val left = foundRects.minOf { it.left }
                    val top = foundRects.minOf { it.top }
                    val right = foundRects.maxOf { it.right }
                    val bottom = foundRects.maxOf { it.bottom }
                    val unionBBox = android.graphics.Rect(left, top, right, bottom)
                    Log.d("AlbaTpl", "findBBox: multi-word union created from ${foundRects.size}/${words.size} words, bbox=$unionBBox")
                    return unionBBox
                } else {
                    Log.d("AlbaTpl", "findBBox: multi-word search found only ${foundRects.size}/${words.size} words, insufficient")
                }
            }

            // 4. Fuzzy contains on whole string (use OcrUtils for Levenshtein ratio)
            if (t.length >= 3) {
                for (entry in result.allBlocks) {
                    val bText: String = entry.first
                    val bRect: android.graphics.Rect = entry.second
                    if (com.albacontrol.util.OcrUtils.fuzzyContains(bText, t, 0.5)) {
                        Log.d("AlbaTpl", "findBBox: fuzzyContains whole-string found '$t' in '$bText' -> bbox=$bRect")
                        return bRect
                    }
                }
            }
            
            // 5. FALLBACK: If we still haven't found the text, but we have an originalBBox 
            // from the initial OCR detection (even if the text was wrong), use it.
            if (originalBBox != null) {
                Log.d("AlbaTpl", "findBBox: text not found, falling back to originalBBox=$originalBBox")
                return originalBBox
            }

            Log.d("AlbaTpl", "findBBox: text '$t' not found in OCR blocks")
            return null
        }

        Log.d("AlbaTpl", "savePattern: searching bboxes for corrected fields...")
        // Track fields that had to use a full-page fallback so we can mark them low confidence
        val lowConfidenceFields = mutableSetOf<String>()
        val fieldToBBox = mutableMapOf<String, android.graphics.Rect?>()
        fieldToBBox["proveedor"] = findBBox(etProveedorText, result.proveedor, result.proveedorBBox)
        fieldToBBox["nif"] = findBBox(etNifText, result.nif, result.nifBBox)
        fieldToBBox["numero_albaran"] = findBBox(etNumeroText, result.numeroAlbaran, result.numeroBBox)
        fieldToBBox["fecha_albaran"] = findBBox(etFechaText, result.fechaAlbaran, result.fechaBBox)

        // If any critical field failed to match, fall back to the original OCR bbox if present
        if (fieldToBBox["proveedor"] == null && result.proveedorBBox != null) {
            fieldToBBox["proveedor"] = result.proveedorBBox
            Log.d("AlbaTpl", "savePattern: fallback proveedor -> originalBBox used")
        }
        if (fieldToBBox["nif"] == null && result.nifBBox != null) {
            fieldToBBox["nif"] = result.nifBBox
            Log.d("AlbaTpl", "savePattern: fallback nif -> originalBBox used")
        }
        if (fieldToBBox["numero_albaran"] == null && result.numeroBBox != null) {
            fieldToBBox["numero_albaran"] = result.numeroBBox
            Log.d("AlbaTpl", "savePattern: fallback numero_albaran -> originalBBox used")
        }
        if (fieldToBBox["fecha_albaran"] == null && result.fechaBBox != null) {
            fieldToBBox["fecha_albaran"] = result.fechaBBox
            Log.d("AlbaTpl", "savePattern: fallback fecha_albaran -> originalBBox used")
        }

        // Final fallback: if still null, use whole-page bbox to avoid aborting learning
        val fullPage = android.graphics.Rect(0, 0, bmp.width, bmp.height)
        for (k in listOf("proveedor", "nif", "numero_albaran", "fecha_albaran")) {
            if (fieldToBBox[k] == null) {
                fieldToBBox[k] = fullPage
                lowConfidenceFields.add(k)
                Log.d("AlbaTpl", "savePattern: final fallback for $k -> full page bbox used (marked LOW_CONF)")
            }
        }

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        for ((field, bbox) in fieldToBBox) {
            if (bbox != null && bw > 0 && bh > 0) {
                val x = bbox.left.toFloat() / bw
                val y = bbox.top.toFloat() / bh
                val w = bbox.width().toFloat() / bw
                val h = bbox.height().toFloat() / bh
                Log.d("AlbaTpl", "savePattern: field=$field text='${when(field) {
                    "proveedor" -> etProveedorText
                    "nif" -> etNifText
                    "numero_albaran" -> etNumeroText
                    "fecha_albaran" -> etFechaText
                    else -> ""
                }}' bbox=$bbox normalized=($x,$y,$w,$h)")
                var s = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", x, y, w, h)
                if (lowConfidenceFields.contains(field)) {
                    s = s + "::LOW_CONF"
                }
                mappings[field] = s
            }
        }

        // --- Debug dump: write structured debug JSON to templates_debug for offline analysis ---
        try {
            val dbgDir = requireContext().getExternalFilesDir("templates_debug")
            dbgDir?.mkdirs()
            val dbgFile = java.io.File(dbgDir, "debug_${System.currentTimeMillis()}_${providerKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
            val dbgJson = JSONObject()
            dbgJson.put("ts", System.currentTimeMillis())
            dbgJson.put("providerKey", providerKey)
            val formVals = JSONObject()
            formVals.put("etProveedor", etProveedorText)
            formVals.put("etNif", etNifText)
            formVals.put("etNumero", etNumeroText)
            formVals.put("etFecha", etFechaText)
            dbgJson.put("form", formVals)
            // preproc pdfs
            try {
                val arr = org.json.JSONArray()
                synchronized(preprocPdfs) { for (p in preprocPdfs) arr.put(p) }
                dbgJson.put("preproc_pdfs", arr)
            } catch (_: Exception) {}

            // OCR result summary and blocks
            try {
                val o = JSONObject()
                o.put("proveedor", result.proveedor ?: "")
                o.put("nif", result.nif ?: "")
                o.put("numero", result.numeroAlbaran ?: "")
                o.put("fecha", result.fechaAlbaran ?: "")
                val blocks = org.json.JSONArray()
                for ((t,b) in result.allBlocks) {
                    try {
                        val bo = JSONObject()
                        bo.put("text", t)
                        val r = JSONObject()
                        r.put("left", b.left)
                        r.put("top", b.top)
                        r.put("right", b.right)
                        r.put("bottom", b.bottom)
                        bo.put("rect", r)
                        blocks.put(bo)
                    } catch (_: Exception) {}
                }
                o.put("blocks", blocks)
                dbgJson.put("ocr", o)
            } catch (e: Exception) { Log.e("AlbaTpl", "debug dump ocr error: ${e.message}") }

            // Field to bbox mapping (pixel + normalized)
            try {
                val fmap = JSONObject()
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                for ((k, v) in fieldToBBox) {
                    try {
                        val fo = JSONObject()
                        if (v != null) {
                            val r = JSONObject()
                            r.put("left", v.left)
                            r.put("top", v.top)
                            r.put("right", v.right)
                            r.put("bottom", v.bottom)
                            fo.put("rect_pixels", r)
                            val nx = v.left.toFloat() / bw
                            val ny = v.top.toFloat() / bh
                            val nw = v.width().toFloat() / bw
                            val nh = v.height().toFloat() / bh
                            fo.put("rect_norm", String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", nx, ny, nw, nh))
                        } else {
                            fo.put("rect_pixels", JSONObject())
                            fo.put("rect_norm", "")
                        }
                        fmap.put(k, fo)
                    } catch (_: Exception) {}
                }
                dbgJson.put("fieldToBBox", fmap)
            } catch (_: Exception) {}

            // mappings learned (normalized bbox strings)
            try {
                val maps = JSONObject()
                for ((k, v) in mappings) maps.put(k, v)
                dbgJson.put("mappings_candidate", maps)
            } catch (_: Exception) {}

            // write file
            try {
                dbgFile.writeText(dbgJson.toString(2))
                Log.d("AlbaTpl", "debug dump written: ${dbgFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("AlbaTpl", "debug dump write error: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("AlbaTpl", "savePattern debug dump error: ${e.message}")
        }

        if (mappings.isEmpty()) {
            return lifecycleScope.launch { }
        }

        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        return lifecycleScope.launch {
            try {
                // also derive product-row patterns if possible
                // Filter products: only use those that match the final UI products
                val validProductBBoxes = mutableListOf<android.graphics.Rect>()
                val validUnidadesBBoxes = mutableListOf<android.graphics.Rect>()
                val validPrecioBBoxes = mutableListOf<android.graphics.Rect>()
                val validImporteBBoxes = mutableListOf<android.graphics.Rect>()

                val uiProducts = mutableListOf<JSONObject>()
                for (i in 0 until productContainer.childCount) {
                    val item = productContainer.getChildAt(i)
                    val p = JSONObject()
                    p.put("descripcion", item.findViewById<EditText>(R.id.etDescripcion).text.toString().trim())
                    p.put("unidades", item.findViewById<EditText>(R.id.etUnidades).text.toString().trim())
                    p.put("precio", item.findViewById<EditText>(R.id.etPrecio).text.toString().trim())
                    p.put("importe", item.findViewById<EditText>(R.id.etImporte).text.toString().trim())
                    uiProducts.add(p)
                }

                // Match UI products to OCR products to find their bboxes
                for (uiP in uiProducts) {
                    val uDesc = uiP.getString("descripcion")
                    val uUnid = uiP.getString("unidades")
                    val uPrecio = uiP.getString("precio")
                    val uImp = uiP.getString("importe")
                    
                    // Find best match in OCR results
                    // We use a simple scoring: matches in description words + matches in numbers
                    var bestMatch: com.albacontrol.ml.OCRProduct? = null
                    var bestScore = 0
                    
                    for (ocrP in result.products) {
                        var score = 0
                        if (ocrP.descripcion.isNotEmpty() && uDesc.contains(ocrP.descripcion, ignoreCase = true)) score += 5
                        if (uDesc.isNotEmpty() && ocrP.descripcion.contains(uDesc, ignoreCase = true)) score += 5
                        
                        // Check numeric fields (exact or close match)
                        if (ocrP.unidades == uUnid && uUnid.isNotEmpty()) score += 3
                        if (ocrP.precio == uPrecio && uPrecio.isNotEmpty()) score += 3
                        if (ocrP.importe == uImp && uImp.isNotEmpty()) score += 3
                        
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = ocrP
                        }
                    }
                    
                    Log.d("AlbaTpl", "savePattern: product UI='$uDesc' matched to OCR='${bestMatch?.descripcion}' score=$bestScore")
                    if (bestMatch != null && bestScore >= 3 && bestMatch.bbox != null) {
                        validProductBBoxes.add(bestMatch.bbox!!)
                        // collect numeric columns
                        bestMatch.unidadesBBox?.let { validUnidadesBBoxes.add(it) }
                        bestMatch.precioBBox?.let { validPrecioBBoxes.add(it) }
                        bestMatch.importeBBox?.let { validImporteBBoxes.add(it) }
                    }
                }

                if (validProductBBoxes.isNotEmpty()) {
                    // Use the union of VALID product bboxes
                    val left = validProductBBoxes.minOf { it.left }
                    val top = validProductBBoxes.minOf { it.top }
                    val right = validProductBBoxes.maxOf { it.right }
                    val bottom = validProductBBoxes.maxOf { it.bottom }
                    val prBBox = android.graphics.Rect(left, top, right, bottom)
                    
                    val bw = bmp.width.toFloat()
                    val bh = bmp.height.toFloat()
                    val x = prBBox.left.toFloat() / bw
                    val y = prBBox.top.toFloat() / bh
                    val w = prBBox.width().toFloat() / bw
                    val h = prBBox.height().toFloat() / bh
                    mappings["product_row"] = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", x, y, w, h)

                    // derive numeric column boxes
                    val colMap = mapOf(
                        "product_unidades" to validUnidadesBBoxes,
                        "product_precio" to validPrecioBBoxes,
                        "product_importe" to validImporteBBoxes
                    )

                    for ((key, cols) in colMap) {
                        if (cols.isNotEmpty()) {
                            val cLeft = cols.minOf { it.left }
                            val cRight = cols.maxOf { it.right }
                            // column spans full row vertically (relative to product_row)
                            val colX = cLeft.toFloat() / bw
                            val colW = (cRight - cLeft).toFloat() / bw
                            val colY = y
                            val colH = h
                            mappings[key] = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", colX, colY, colW, colH)
                        }
                    }
                } else if (result.products.isNotEmpty()) {
                     // Fallback: if no UI match found (e.g. user deleted everything?), use original heuristic but try to filter outliers?
                     // For now, keep original logic as fallback but maybe restrict it?
                     // Actually, if user corrected products, we trust the matching above. 
                     // If nothing matched, maybe we shouldn't learn bad product rows.
                     // Let's skip product learning if we can't match UI to OCR.
                }

                // Guardar imagen de muestra y registrar TemplateSample
                val outDir = requireContext().getExternalFilesDir("templates")
                outDir?.mkdirs()
                val file = java.io.File(outDir, "tpl_sample_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    file.outputStream().use { fos ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                }

                // fieldMappings: bbox::correctedText (usar texto corregido por el usuario, no el OCR original)
                val fieldMappings = mutableMapOf<String, String>()
                for ((k, v) in mappings) {
                    val correctedText = when (k) {
                        "proveedor" -> etProveedorText
                        "nif" -> etNifText
                        "numero_albaran" -> etNumeroText
                        "fecha_albaran" -> etFechaText
                        "product_row" -> ""
                        else -> ""
                    }
                    fieldMappings[k] = "$v::$correctedText"
                    Log.d("AlbaTpl", "savePattern: storing field=$k correctedText='$correctedText'")
                }
                
                // Guardar productos individuales del formulario (basado en descripción normalizada)
                for (i in 0 until productContainer.childCount) {
                    val item = productContainer.getChildAt(i)
                    val desc = item.findViewById<EditText>(R.id.etDescripcion).text.toString().trim()
                    val units = item.findViewById<EditText>(R.id.etUnidades).text.toString().trim()
                    val price = item.findViewById<EditText>(R.id.etPrecio).text.toString().trim()
                    val total = item.findViewById<EditText>(R.id.etImporte).text.toString().trim()
                    
                    if (desc.isNotEmpty()) {
                        // Usar descripción normalizada como clave en lugar de índice
                        val productKey = normalizeProductName(desc)
                        fieldMappings["product_${productKey}_desc"] = "::$desc"
                        if (units.isNotEmpty()) fieldMappings["product_${productKey}_units"] = "::$units"
                        if (price.isNotEmpty()) fieldMappings["product_${productKey}_price"] = "::$price"
                        if (total.isNotEmpty()) fieldMappings["product_${productKey}_total"] = "::$total"
                        Log.d("AlbaTpl", "savePattern: storing product key='$productKey': desc='$desc' units='$units' price='$price' total='$total'")
                    }
                }

                // Normalized fields (embedding removed - not used)
                val normalized = mutableMapOf<String, String>()

                val sample = com.albacontrol.data.TemplateSample(providerNif = providerKey, imagePath = file.absolutePath, fieldMappings = fieldMappings, normalizedFields = if (normalized.isEmpty()) null else normalized)
                var sampleId: Long = -1
                try {
                    sampleId = withContext(Dispatchers.IO) { db.templateDao().insertSample(sample) as Long }
                    Log.d("AlbaTpl", "inserted TemplateSample id=${sampleId} provider='${providerKey}' image='${file.absolutePath}' mappings=${fieldMappings.keys}")
                        try {
                        withContext(Dispatchers.IO) {
                                try {
                                    val dbgDir = requireContext().getExternalFilesDir("templates_debug")
                                    dbgDir?.mkdirs()
                                    val dbgFile = java.io.File(dbgDir, "debug_log.txt")
                                    val tsSample = System.currentTimeMillis()
                                    dbgFile.appendText("${'$'}tsSample,SAMPLE,provider=${providerKey},id=${sampleId},image=${file.absolutePath}\n")
                                } catch (e: Exception) {
                                    Log.e("AlbaTpl", "debug write sample error: ${e.message}")
                                }
                            }
                        } catch (_: Exception) {}
                        try { exportTablesToExternal(db) } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.e("AlbaTpl", "insertSample error for provider='${providerKey}': ${e.message}")
                }

                // contar samples existentes para este provider y, si hay suficientes, agregar/actualizar plantilla
                val existing = try { 
                    withContext(Dispatchers.IO) { 
                        val allSamples = db.templateDao().getAllSamples()
                        Log.d("AlbaTpl", "Total samples in DB: ${allSamples.size}")
                        val filtered = allSamples.filter { 
                            val sampleKey = normalizeProviderKey(it.providerNif)
                            val matches = sampleKey == providerKey
                            if (matches) {
                                Log.d("AlbaTpl", "Sample matches provider '$providerKey': id=${it.id} providerNif='${it.providerNif}'")
                            }
                            matches
                        }
                        Log.d("AlbaTpl", "Found ${filtered.size} samples for provider '$providerKey'")
                        filtered
                    } 
                } catch (e: Exception) { 
                    Log.e("AlbaTpl", "countSamples error: ${e.message}", e)
                    emptyList<com.albacontrol.data.TemplateSample>() 
                }
                val count = existing.size
                val MIN_SAMPLES_CREATE_TEMPLATE = com.albacontrol.data.TemplateLearningConfig.MIN_SAMPLES_CREATE_TEMPLATE
                
                Log.d("AlbaTpl", "Template creation check: count=$count MIN_SAMPLES=$MIN_SAMPLES_CREATE_TEMPLATE provider='$providerKey'")

                if (count >= MIN_SAMPLES_CREATE_TEMPLATE) {
                    Log.d("AlbaTpl", "Creating/updating template for provider '$providerKey' from $count samples")
                    val aggregated = mutableMapOf<String, String>()
                    try {
                        // collect per-field list of bbox arrays
                        val perField = mutableMapOf<String, MutableList<List<Double>>>()
                        for (s in existing) {
                            val m = s.fieldMappings
                            for ((k, v) in m) {
                                val bbox = v.split("::").firstOrNull() ?: continue
                                val parts = bbox.split(',').mapNotNull { it.toDoubleOrNull() }
                                if (parts.size == 4) {
                                    perField.getOrPut(k) { mutableListOf() }.add(parts)
                                }
                            }
                        }
                        for ((k, lists) in perField) {
                            if (lists.isEmpty()) {
                                Log.w("AlbaTpl", "Field '$k' has no samples, skipping")
                                continue
                            }
                            val xs = lists.map { it[0] }.sorted()
                            val ys = lists.map { it[1] }.sorted()
                            val ws = lists.map { it[2] }.sorted()
                            val hs = lists.map { it[3] }.sorted()
                            fun median(list: List<Double>): Double {
                                return if (list.isEmpty()) 0.0 else if (list.size % 2 == 1) list[list.size/2] else (list[list.size/2 - 1] + list[list.size/2]) / 2.0
                            }
                            val mx = median(xs)
                            val my = median(ys)
                            val mw = median(ws)
                            val mh = median(hs)
                            aggregated[k] = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", mx, my, mw, mh)
                            Log.d("AlbaTpl", "Aggregated field '$k': median=($mx,$my,$mw,$mh) from ${lists.size} samples")
                        }
                        
                        if (aggregated.isEmpty()) {
                            Log.e("AlbaTpl", "No fields aggregated for template! This should not happen.")
                            return@launch
                        }
                        // compute field confidences: proportion of samples that contained the field
                        val fieldConfidence = mutableMapOf<String, Double>()
                        val createdFrom = mutableListOf<Long>()
                        for (s in existing) createdFrom.add(s.id)
                        for ((k, lists) in perField) {
                            val presentCount = lists.size
                            val conf = if (count > 0) presentCount.toDouble() / count.toDouble() else 0.0
                            fieldConfidence[k] = conf
                        }

                        // determine version: if existing template exists, increment version
                        val existingTpl = withContext(Dispatchers.IO) { 
                            db.templateDao().getAllTemplates().firstOrNull { 
                                normalizeProviderKey(it.providerNif) == providerKey 
                            } 
                        }
                        val nextVersion = (existingTpl?.version ?: 0) + 1

                        val tpl = com.albacontrol.data.OCRTemplate(
                            providerNif = providerKey,
                            mappings = aggregated,
                            version = nextVersion,
                            active = true,
                            createdFromSampleIds = createdFrom.joinToString(","),
                            fieldConfidence = fieldConfidence.mapValues { it.value.toString() }
                        )

                        var tplId: Long = -1
                        try {
                            tplId = withContext(Dispatchers.IO) {
                                // insert (REPLACE semantics via DAO) - previous template will be replaced but we keep version metadata
                                val insertedId = db.templateDao().insertTemplate(tpl)
                                Log.d("AlbaTpl", "Template inserted with ID: $insertedId")
                                insertedId
                            }
                            Log.d("AlbaTpl", "inserted/updated OCRTemplate id=${tplId} provider='${providerKey}' version=${nextVersion} mappings=${aggregated.keys.size} fields confidences=${fieldConfidence.size} fields")
                            
                            // Verificar que la plantilla se guardó correctamente
                            val verifyTpl = withContext(Dispatchers.IO) {
                                db.templateDao().getAllTemplates().firstOrNull { 
                                    normalizeProviderKey(it.providerNif) == providerKey 
                                }
                            }
                            if (verifyTpl != null) {
                                Log.d("AlbaTpl", "Template verification: OK - found template with ${verifyTpl.mappings.size} mappings")
                            } else {
                                Log.e("AlbaTpl", "Template verification: FAILED - template not found after insert!")
                            }
                                        try {
                                        withContext(Dispatchers.IO) {
                                        try {
                                            val dbgDir = requireContext().getExternalFilesDir("templates_debug")
                                            dbgDir?.mkdirs()
                                            val dbgFile = java.io.File(dbgDir, "debug_log.txt")
                                            val tsTpl = System.currentTimeMillis()
                                            dbgFile.appendText("${'$'}tsTpl,TEMPLATE,provider=${providerKey},id=${tplId},version=${nextVersion}\n")
                                        } catch (e: Exception) {
                                            Log.e("AlbaTpl", "debug write template error: ${e.message}")
                                        }
                                    }
                                } catch (_: Exception) {}
                                try { exportTablesToExternal(db) } catch (_: Exception) {}
                        } catch (e: Exception) {
                            Log.e("AlbaTpl", "insertTemplate error for provider='${providerKey}': ${e.message}")
                        }

                        // log current counts after template insert
                        try {
                            val totalSamples = withContext(Dispatchers.IO) { db.templateDao().getAllSamples().filter { it.providerNif.trim().lowercase() == providerKey }.size }
                            val totalTemplates = withContext(Dispatchers.IO) { db.templateDao().getAllTemplates().size }
                            Log.d("AlbaTpl", "post-insert counts: providerSamples=${totalSamples} totalTemplates=${totalTemplates}")
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.d("AlbaTpl", "error creating template: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d("AlbaTpl", "error saving pattern: ${e.message}")
            }
        }
    }

    /**
     * Envía una corrección al backend API cuando el usuario finaliza un albarán.
     * Solo envía si hay correcciones detectadas (diferencias entre OCR y valores finales).
     */
    private fun sendCorrectionToBackend(
        formJson: JSONObject,
        ocrResult: com.albacontrol.ml.OCRResult?,
        bitmap: android.graphics.Bitmap?
    ) {
        Log.d("AlbaTpl", "sendCorrectionToBackend: entry - ocrResult=${ocrResult != null} bitmap=${bitmap != null}")
        
        if (ocrResult == null || bitmap == null) {
            Log.w("AlbaTpl", "sendCorrectionToBackend: skipping (no OCR result or bitmap)")
            return
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("AlbaTpl", "sendCorrectionToBackend: starting coroutine")
                val view = try { requireView() } catch (_: Exception) { null }
                val etProveedor = view?.findViewById<EditText>(R.id.etProveedor)
                val etNif = view?.findViewById<EditText>(R.id.etNif)
                val etNumero = view?.findViewById<EditText>(R.id.etNumeroAlbaran)
                val etFecha = view?.findViewById<EditText>(R.id.etFechaAlbaran)
                
                val proveedorFinal = etProveedor?.text?.toString()?.trim() ?: ""
                val nifFinal = etNif?.text?.toString()?.trim() ?: ""
                val numeroFinal = etNumero?.text?.toString()?.trim() ?: ""
                val fechaFinal = etFecha?.text?.toString()?.trim() ?: ""
                
                // Verificar si hay correcciones (diferencias entre OCR y valores finales)
                var hasCorrections = false
                if (!ocrResult.proveedor.isNullOrBlank() && ocrResult.proveedor.trim() != proveedorFinal) hasCorrections = true
                if (!ocrResult.nif.isNullOrBlank() && ocrResult.nif.trim() != nifFinal) hasCorrections = true
                if (!ocrResult.numeroAlbaran.isNullOrBlank() && ocrResult.numeroAlbaran.trim() != numeroFinal) hasCorrections = true
                if (!ocrResult.fechaAlbaran.isNullOrBlank() && ocrResult.fechaAlbaran.trim() != fechaFinal) hasCorrections = true
                
                // También verificar productos
                val productosArray = formJson.optJSONArray("productos")
                if (productosArray != null && productosArray.length() > 0) {
                    // Si hay productos en el formulario, considerar que puede haber correcciones
                    hasCorrections = true
                }
                
                if (!hasCorrections) {
                    Log.d("AlbaTpl", "sendCorrectionToBackend: no corrections detected, skipping")
                    return@launch
                }
                
                // Construir payload de corrección
                val debugId = "debug_${System.currentTimeMillis()}_${nifFinal.takeIf { it.isNotEmpty() } ?: proveedorFinal.take(8)}"
                val nifNormalized = if (nifFinal.isNotEmpty()) {
                    com.albacontrol.util.OcrUtils.normalizeToken(nifFinal).uppercase()
                } else null
                
                // Construir array de productos en formato esperado por el backend
                val productosBackend = org.json.JSONArray()
                if (productosArray != null) {
                    for (i in 0 until productosArray.length()) {
                        val p = productosArray.getJSONObject(i)
                        val prodObj = JSONObject()
                        prodObj.put("descripcion", p.optString("descripcion", ""))
                        val unidades = p.optString("unidades", "")
                        if (unidades.isNotEmpty()) {
                            try {
                                prodObj.put("unidades", unidades.toDoubleOrNull() ?: 0.0)
                            } catch (_: Exception) {
                                prodObj.put("unidades", 0.0)
                            }
                        }
                        val precio = p.optString("precio", "")
                        if (precio.isNotEmpty()) {
                            try {
                                prodObj.put("precio_unitario", precio.toDoubleOrNull() ?: 0.0)
                            } catch (_: Exception) {
                                prodObj.put("precio_unitario", 0.0)
                            }
                        }
                        val importe = p.optString("importe", "")
                        if (importe.isNotEmpty()) {
                            try {
                                prodObj.put("importe_linea", importe.toDoubleOrNull() ?: 0.0)
                            } catch (_: Exception) {
                                prodObj.put("importe_linea", 0.0)
                            }
                        }
                        productosBackend.put(prodObj)
                    }
                }
                
                // Backend API de correcciones deshabilitado (CorrectionsApiClient eliminado)
                Log.d("AlbaTpl", "Correcciones backend: No disponible (API no implementada)")
            } catch (e: Exception) {
                Log.e("AlbaTpl", "Error al construir/enviar corrección: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    private fun addProductBox() {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.product_item, productContainer, false)

        val btnDelete = item.findViewById<ImageButton>(R.id.btnDeleteProduct)
        val checkInc = item.findViewById<CheckBox>(R.id.checkIncidencia)

        checkInc.setOnCheckedChangeListener { _, isChecked ->
            // Si algún producto marca incidencia, marcar incidencia en albarán (automático)
            if (isChecked) checkIncidenciaAlbaran.isChecked = true
        }

        btnDelete.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_product_title))
                .setMessage(getString(R.string.confirm_delete_this_product))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    productContainer.removeView(item)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
            }
            dialog.show()
        }

        // attach watchers to new item for auto-save (and mark manual edits when user types)
        attachWatchersToProductItem(item)

        // If user deletes via this addProductBox flow, mark manual edit
        try {
            val del = item.findViewById<ImageButton>(R.id.btnDeleteProduct)
            del?.setOnClickListener {
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.delete_product_title))
                    .setMessage(getString(R.string.confirm_delete_this_product))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        productsManuallyEdited = true
                        productContainer.removeView(item)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                }
                dialog.show()
            }
        } catch (_: Exception) {}

        productContainer.addView(item) // añadir al final (encima del botón "+ añadir producto")
    }

    /**
     * Persistir una TemplateSample a partir del OCR actual (sin requerir correcciones manuales).
     * Diseñado para pruebas rápidas / aprendizaje automático: guarda una muestra usando los
     * valores detectados por OCR y la imagen en `lastOcrBitmap`.
     */
    private fun saveSampleFromOcr(): kotlinx.coroutines.Job {
        Log.d("AlbaTpl", "saveSampleFromOcr: entry")
        val result = lastOcrResult
        val bmp = lastOcrBitmap
        if (result == null || bmp == null) {
            Log.d("AlbaTpl", "saveSampleFromOcr: missing ocr result or bitmap - aborting")
            return lifecycleScope.launch { }
        }

        return lifecycleScope.launch {
            try {
                // Preferir valores corregidos por el usuario en los EditText (si existen),
                // antes de usar el valor detectado por OCR. Esto evita asociar muestras
                // al NIF incorrecto que el OCR puede haber extraído.
                val view = try { requireView() } catch (_: Exception) { null }
                val etNifUi = view?.findViewById<EditText>(R.id.etNif)
                val etProvUi = view?.findViewById<EditText>(R.id.etProveedor)
                val uiNif = etNifUi?.text?.toString()?.trim()
                val uiProv = etProvUi?.text?.toString()?.trim()
                val providerKeyRaw = when {
                    !uiNif.isNullOrBlank() -> uiNif
                    !uiProv.isNullOrBlank() -> uiProv
                    else -> result.nif?.ifBlank { result.proveedor } ?: (result.proveedor ?: "")
                }
                val providerKey = normalizeProviderKey(providerKeyRaw)
                if (providerKey.isBlank()) {
                    Log.d("AlbaTpl", "saveSampleFromOcr: provider key empty - aborting")
                    return@launch
                }

                val mappings = mutableMapOf<String, String>()
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()

                // helper to convert rect to normalized string
                fun normFromRect(r: android.graphics.Rect?): String? {
                    if (r == null) return null
                    val x = r.left.toFloat() / bw
                    val y = r.top.toFloat() / bh
                    val w = r.width().toFloat() / bw
                    val h = r.height().toFloat() / bh
                    return String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", x, y, w, h)
                }

                normFromRect(result.proveedorBBox)?.let { mappings["proveedor"] = it }
                normFromRect(result.nifBBox)?.let { mappings["nif"] = it }
                normFromRect(result.numeroBBox)?.let { mappings["numero_albaran"] = it }
                normFromRect(result.fechaBBox)?.let { mappings["fecha_albaran"] = it }

                if (result.products.isNotEmpty()) {
                    val productBBoxes = result.products.mapNotNull { it.bbox }
                    if (productBBoxes.isNotEmpty()) {
                        val left = productBBoxes.minOf { it.left }
                        val top = productBBoxes.minOf { it.top }
                        val right = productBBoxes.maxOf { it.right }
                        val bottom = productBBoxes.maxOf { it.bottom }
                        val prBBox = android.graphics.Rect(left, top, right, bottom)
                        normFromRect(prBBox)?.let { mappings["product_row"] = it }
                    }
                }

                if (mappings.isEmpty()) {
                    Log.d("AlbaTpl", "saveSampleFromOcr: no valid mappings found - aborting")
                    return@launch
                }

                // Save sample image
                val outDir = requireContext().getExternalFilesDir("templates")
                outDir?.mkdirs()
                val file = java.io.File(outDir, "tpl_sample_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    file.outputStream().use { fos -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos) }
                }

                val fieldMappings = mutableMapOf<String, String>()
                for ((k, v) in mappings) {
                    val recognized = when (k) {
                        "proveedor" -> result.proveedor ?: ""
                        "nif" -> result.nif ?: ""
                        "numero_albaran" -> result.numeroAlbaran ?: ""
                        "fecha_albaran" -> result.fechaAlbaran ?: ""
                        else -> ""
                    }
                    fieldMappings[k] = "$v::$recognized"
                }

                // Normalized fields (embedding removed - not used)
                val normalized = mutableMapOf<String, String>()

                val sample = com.albacontrol.data.TemplateSample(providerNif = providerKey, imagePath = file.absolutePath, fieldMappings = fieldMappings, normalizedFields = if (normalized.isEmpty()) null else normalized)
                try {
                    val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
                    val sampleId = withContext(Dispatchers.IO) { db.templateDao().insertSample(sample) }
                    Log.d("AlbaTpl", "auto-inserted TemplateSample id=${'$'}sampleId provider='${providerKey}' image='${file.absolutePath}' mappings=${fieldMappings.keys}")
                    try {
                        val dbgDir = requireContext().getExternalFilesDir("templates_debug")
                        dbgDir?.mkdirs()
                        val dbgFile = java.io.File(dbgDir, "debug_log.txt")
                        val ts = System.currentTimeMillis()
                        dbgFile.appendText("${'$'}ts,SAMPLE,provider=${providerKey},id=${'$'}sampleId,image=${file.absolutePath}\n")
                        try { exportTablesToExternal(db) } catch (_: Exception) {}
                    } catch (e: Exception) { Log.e("AlbaTpl", "debug write sample error: ${'$'}{e.message}") }
                } catch (e: Exception) {
                    Log.e("AlbaTpl", "insertSample error in auto-save provider='${providerKey}': ${e.message}")
                }
            } catch (e: Exception) {
                Log.d("AlbaTpl", "saveSampleFromOcr error: ${e.message}")
            }
        }
    }

    private suspend fun exportTablesToExternal(db: com.albacontrol.data.AppDatabase) {
        try {
            withContext(Dispatchers.IO) {
                try {
                    val samples = db.templateDao().getAllSamples()
                    val templates = db.templateDao().getAllTemplates()
                    val root = JSONObject()
                    root.put("exported_at", System.currentTimeMillis())

                    val sArr = JSONArray()
                    for (s in samples) {
                        val so = JSONObject()
                        so.put("id", s.id)
                        so.put("providerNif", s.providerNif)
                        so.put("imagePath", s.imagePath)
                        val fm = JSONObject()
                        for ((k, v) in s.fieldMappings) fm.put(k, v)
                        so.put("fieldMappings", fm)
                        val nf = JSONObject()
                        s.normalizedFields?.forEach { (k, v) -> nf.put(k, v) }
                        so.put("normalizedFields", nf)
                        val fc = JSONObject()
                        s.fieldConfidences?.forEach { (k, v) -> fc.put(k, v) }
                        so.put("fieldConfidences", fc)
                        so.put("createdAt", s.createdAt)
                        sArr.put(so)
                    }
                    root.put("template_samples", sArr)

                    val tArr = JSONArray()
                    for (t in templates) {
                        val to = JSONObject()
                        to.put("providerNif", t.providerNif)
                        val maps = JSONObject()
                        for ((k, v) in t.mappings) maps.put(k, v)
                        to.put("mappings", maps)
                        to.put("version", t.version)
                        to.put("active", t.active)
                        to.put("created_from_samples", t.createdFromSampleIds)
                        val tf = JSONObject()
                        t.fieldConfidence?.forEach { (k, v) -> tf.put(k, v) }
                        to.put("field_confidence", tf)
                        tArr.put(to)
                    }
                    root.put("ocr_templates", tArr)

                    val dbgDir = requireContext().getExternalFilesDir("templates_debug")
                    dbgDir?.mkdirs()
                    val ts = System.currentTimeMillis()
                    val file = java.io.File(dbgDir, "tables_dump_${'$'}ts.json")
                    file.writeText(root.toString(2))
                    // Also write a latest-overwriteable dump for easy extraction (tables_dump_latest.json)
                    try {
                        val latest = java.io.File(dbgDir, "tables_dump_latest.json")
                        latest.writeText(root.toString(2))
                    } catch (e: Exception) {
                        Log.e("AlbaTpl", "write latest dump error: ${'$'}{e.message}")
                    }
                } catch (e: Exception) {
                    Log.e("AlbaTpl", "exportTablesToExternal error: ${e.message}")
                }
            }
        } catch (_: Exception) {}
    }

    private var patternSaveJob: Job? = null

    private fun scheduleAutoSavePattern() {
        // Auto-save disabled to avoid creating template samples during the
        // user editing flow. Templates must only be created when the user
        // finalizes the albarán (see btnFinalizar flow which calls
        // `savePatternFromCorrections()` explicitly).
        patternSaveJob?.cancel()
        patternSaveJob = null
        Log.d("AlbaTpl", "scheduleAutoSavePattern: disabled (save only at finalize)")
    }

    private fun attachWatchersToProductItem(item: View) {
        val etDesc = item.findViewById<EditText>(R.id.etDescripcion)
        val etUnid = item.findViewById<EditText>(R.id.etUnidades)
        val etPrecio = item.findViewById<EditText>(R.id.etPrecio)
        val etImporte = item.findViewById<EditText>(R.id.etImporte)
        val checkIncidencia = item.findViewById<CheckBox>(R.id.checkIncidencia)

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // schedule auto-save when user edits product fields
                if (lastOcrResult != null) scheduleAutoSavePattern()
                // mark that products were manually edited when the user types into product fields
                try {
                    if (etDesc.hasFocus() || etUnid.hasFocus() || etPrecio.hasFocus() || etImporte.hasFocus()) {
                        productsManuallyEdited = true
                        // Marcar automáticamente la casilla de incidencia cuando se edita un campo
                        if (checkIncidencia != null && !checkIncidencia.isChecked) {
                            checkIncidencia.isChecked = true
                        }
                    }
                } catch (_: Exception) {}
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }

        etDesc.addTextChangedListener(watcher)
        etUnid.addTextChangedListener(watcher)
        etPrecio.addTextChangedListener(watcher)
        etImporte.addTextChangedListener(watcher)
        
        // Asegurar que el botón eliminar funciona para items añadidos dinámicamente
        try {
            val btnDelete = item.findViewById<ImageButton>(R.id.btnDeleteProduct)
            btnDelete?.setOnClickListener {
                try {
                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.delete_product_title))
                        .setMessage(getString(R.string.confirm_delete_this_product))
                        .setPositiveButton(getString(R.string.delete)) { _, _ -> productContainer.removeView(item) }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                    }
                    dialog.show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Asegurar comportamiento de checkbox de incidencia en items añadidos
        try {
            val checkInc = item.findViewById<CheckBox>(R.id.checkIncidencia)
            checkInc?.setOnCheckedChangeListener { _, isChecked -> if (isChecked) checkIncidenciaAlbaran.isChecked = true }
        } catch (_: Exception) {}
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        try {
            val json = JSONObject()
            val view = requireView()
            val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
            val etNif = view.findViewById<EditText>(R.id.etNif)
            val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
            val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)
            val etComentarios = view.findViewById<EditText>(R.id.etComentarios)
            val spinnerRecep = view.findViewById<Spinner>(R.id.spinnerRecepcionista)
            val spinnerUbic = view.findViewById<Spinner>(R.id.spinnerUbicacion)

            json.put("proveedor", etProveedor.text.toString())
            json.put("nif", etNif.text.toString())
            json.put("numero_albaran", etNumero.text.toString())
            json.put("fecha_albaran", etFechaAlb.text.toString())
            json.put("comments", etComentarios.text.toString())
            json.put("recepcionista", spinnerRecep.selectedItem?.toString() ?: "")
            json.put("ubicacion_recogida", spinnerUbic.selectedItem?.toString() ?: "")
            json.put("sin_albaran", checkSinAlbaran.isChecked)
            json.put("tiene_incidencias", checkIncidenciaAlbaran.isChecked)
            json.put("created_at", System.currentTimeMillis())

            val productsArray = JSONArray()
            for (i in 0 until productContainer.childCount) {
                val item = productContainer.getChildAt(i)
                val desc = item.findViewById<EditText>(R.id.etDescripcion).text.toString()
                val unid = item.findViewById<EditText>(R.id.etUnidades).text.toString()
                val precio = item.findViewById<EditText>(R.id.etPrecio).text.toString()
                val importe = item.findViewById<EditText>(R.id.etImporte).text.toString()
                val inc = item.findViewById<CheckBox>(R.id.checkIncidencia).isChecked

                val p = JSONObject()
                p.put("descripcion", desc)
                p.put("unidades", unid)
                p.put("precio", precio)
                p.put("importe", importe)
                p.put("incidencia", inc)
                productsArray.put(p)
            }
            json.put("products", productsArray)

            outState.putString("form_state", json.toString())
            Log.d("AlbaTpl", "onSaveInstanceState: Guardados ${productsArray.length()} productos")
        } catch (e: Exception) {
            Log.e("AlbaTpl", "onSaveInstanceState: error", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // TFLite cleanup - Disabled (not used)
    }

    // Save a draft silently when the user presses back
    fun saveDraftSilent() {
        try {
            val json = JSONObject()
            val view = requireView()
            val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
            val etNif = view.findViewById<EditText>(R.id.etNif)
            val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
            val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)

            json.put("proveedor", etProveedor.text.toString())
            json.put("nif", etNif.text.toString())
            json.put("numero_albaran", etNumero.text.toString())
            json.put("fecha_albaran", etFechaAlb.text.toString())
            json.put("created_at", System.currentTimeMillis())

            val productsArray = JSONArray()
            for (i in 0 until productContainer.childCount) {
                val item = productContainer.getChildAt(i)
                val desc = item.findViewById<EditText>(R.id.etDescripcion).text.toString()
                val unid = item.findViewById<EditText>(R.id.etUnidades).text.toString()
                val precio = item.findViewById<EditText>(R.id.etPrecio).text.toString()
                val importe = item.findViewById<EditText>(R.id.etImporte).text.toString()
                val inc = item.findViewById<CheckBox>(R.id.checkIncidencia).isChecked

                val p = JSONObject()
                p.put("descripcion", desc)
                p.put("unidades", unid)
                p.put("precio", precio)
                p.put("importe", importe)
                p.put("incidencia", inc)
                productsArray.put(p)
            }
            json.put("products", productsArray)

            val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
            // include preproc_pdfs for silent draft saves as well
            try {
                val arr = org.json.JSONArray()
                synchronized(preprocPdfs) { for (p in preprocPdfs) arr.put(p) }
                json.put("preproc_pdfs", arr)
            } catch (_: Exception) {}

            val draft = com.albacontrol.data.Draft(providerId = null, dataJson = json.toString(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            lifecycleScope.launch(Dispatchers.IO) { try { db.draftDao().insert(draft); try { draftSaved = true } catch (_: Exception) {} } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    // Helper: construir rect desde bbox normalizado "x,y,w,h" y tamaño de imagen
    private fun rectFromNormalized(bbox: String?, bwf: Float, bhf: Float): android.graphics.Rect? {
        if (bbox.isNullOrBlank()) return null
        val parts = bbox.split(',').mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4) return null
        val left = (parts[0] * bwf).toInt()
        val top = (parts[1] * bhf).toInt()
        val right = left + (parts[2] * bwf).toInt()
        val bottom = top + (parts[3] * bhf).toInt()
        if (right <= left || bottom <= top) return null
        return android.graphics.Rect(left, top, right.coerceAtMost(bwf.toInt()), bottom.coerceAtMost(bhf.toInt()))
    }

    /**
     * Normaliza el nombre de un producto para usarlo como clave de identificación.
     * Elimina espacios, puntuación, convierte a minúsculas.
     */
    private fun normalizeProductName(name: String): String {
        return name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9áéíóúñü]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(50) // Limitar longitud
    }

    /**
     * Calcula similitud entre dos nombres de productos (0.0 a 1.0).
     */
    private fun productNameSimilarity(name1: String, name2: String): Double {
        val norm1 = normalizeProductName(name1)
        val norm2 = normalizeProductName(name2)
        
        if (norm1 == norm2) return 1.0
        if (norm1.isEmpty() || norm2.isEmpty()) return 0.0
        
        // Levenshtein distance
        val len1 = norm1.length
        val len2 = norm2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j
        
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (norm1[i - 1] == norm2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        
        val distance = dp[len1][len2]
        val maxLen = maxOf(len1, len2)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    // Render first page of a PDF file to a Bitmap (or null if fails)
    private fun renderPdfFirstPageToBitmap(path: String): android.graphics.Bitmap? {
        try {
            val fd = android.os.ParcelFileDescriptor.open(java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(fd)
            if (renderer.pageCount <= 0) {
                renderer.close(); fd.close(); return null
            }
            val page = renderer.openPage(0)
            val width = page.width
            val height = page.height
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            fd.close()
            return bmp
        } catch (e: Exception) {
            Log.d("AlbaTpl", "renderPdfFirstPageToBitmap error: ${e.message}")
            return null
        }
    }

    // Build a JSON object mapping form fields to arrays of normalized bbox strings (x,y,w,h) found in the pdfOcr result
    private fun buildCoordsMapForJson(pdfOcr: com.albacontrol.ml.OCRResult, pdfBmp: android.graphics.Bitmap, formJson: JSONObject, productsArray: org.json.JSONArray): JSONObject {
        val out = JSONObject()
        try {
            fun normalizeForMatch(s: String?): String {
                if (s == null) return ""
                val tmp = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                val noAccents = tmp.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                return noAccents.filter { it.isLetterOrDigit() || it.isWhitespace() }.lowercase().trim().replace(Regex("\\s+"), " ")
            }

            fun rectToNormalized(r: android.graphics.Rect): String {
                val bw = pdfBmp.width.toFloat()
                val bh = pdfBmp.height.toFloat()
                val x = r.left.toFloat() / bw
                val y = r.top.toFloat() / bh
                val w = r.width().toFloat() / bw
                val h = r.height().toFloat() / bh
                return String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", x, y, w, h)
            }

            // helper levenshtein
            fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
                val lhsLength = lhs.length
                val rhsLength = rhs.length
                var cost = IntArray(lhsLength + 1) { it }
                var newCost = IntArray(lhsLength + 1)
                for (i in 1..rhsLength) {
                    newCost[0] = i
                    for (j in 1..lhsLength) {
                        val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                        val costReplace = cost[j - 1] + match
                        val costInsert = cost[j] + 1
                        val costDelete = newCost[j - 1] + 1
                        newCost[j] = kotlin.math.min(kotlin.math.min(costInsert, costDelete), costReplace)
                    }
                    val swap = cost
                    cost = newCost
                    newCost = swap
                }
                return cost[lhsLength]
            }

            // build list of OCR blocks with normalized text and rect
            val blocks = pdfOcr.allBlocks.map { Pair(normalizeForMatch(it.first), it.second) }

            // Fields to check: proveedor, nif, numero_albaran, fecha_albaran
            val fields = listOf("proveedor", "nif", "numero_albaran", "fecha_albaran")
            for (f in fields) {
                try {
                    val value = formJson.optString(f, "").trim()
                    if (value.isBlank()) continue
                    val vNorm = normalizeForMatch(value)
                    val arr = org.json.JSONArray()
                    for ((bText, bRect) in blocks) {
                        if (bText.isBlank()) continue
                        var matched = false
                        if (bText.contains(vNorm) || vNorm.contains(bText)) matched = true
                        else {
                            // fuzzy: allow small distance relative to length
                            val maxDist = kotlin.math.max(1, (vNorm.length * 0.4).toInt())
                            val dist = levenshtein(vNorm, bText)
                            if (dist <= maxDist) matched = true
                        }
                        if (matched) arr.put(rectToNormalized(bRect))
                    }
                    if (arr.length() > 0) out.put(f, arr)
                } catch (_: Exception) {}
            }

            // Products: iterate productsArray and try to locate each field inside the document
            val prodCoords = org.json.JSONArray()
            for (i in 0 until productsArray.length()) {
                try {
                    val prod = productsArray.getJSONObject(i)
                    val desc = prod.optString("descripcion", "").trim()
                    val unid = prod.optString("unidades", "").trim()
                    val precio = prod.optString("precio", "").trim()
                    val importe = prod.optString("importe", "").trim()
                    val itemObj = JSONObject()
                    fun addMatches(key: String, value: String) {
                        if (value.isBlank()) return
                        val vNorm = normalizeForMatch(value)
                        val arr = org.json.JSONArray()
                        for ((bText, bRect) in blocks) {
                            if (bText.isBlank()) continue
                            if (bText.contains(vNorm) || vNorm.contains(bText) || levenshtein(vNorm, bText) <= kotlin.math.max(1, (vNorm.length * 0.4).toInt())) {
                                arr.put(rectToNormalized(bRect))
                            }
                        }
                        if (arr.length() > 0) itemObj.put(key, arr)
                    }
                    addMatches("descripcion", desc)
                    addMatches("unidades", unid)
                    addMatches("precio", precio)
                    addMatches("importe", importe)
                    if (itemObj.length() > 0) prodCoords.put(itemObj)
                } catch (_: Exception) {}
            }
            if (prodCoords.length() > 0) out.put("products", prodCoords)

        } catch (e: Exception) {
            Log.d("AlbaTpl", "buildCoordsMapForJson error: ${e.message}")
        }
        return out
    }

    /**
     * Normaliza una clave de proveedor (NIF o nombre) para uso consistente en matching.
     * Elimina espacios, símbolos y convierte a minúsculas, similar a OcrUtils.normalizeToken
     * pero preservando la estructura para NIFs.
     */
    private fun normalizeProviderKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        // Normalizar usando OcrUtils para consistencia
        val normalized = com.albacontrol.util.OcrUtils.normalizeToken(key)
        // Si parece un NIF (tiene dígitos y letras), mantener mayúsculas para la letra final
        // pero para matching usamos minúsculas
        return normalized.lowercase()
    }
    
    /**
     * Calcula el ratio de similitud Levenshtein entre dos strings (0.0 a 1.0).
     */
    private fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshteinDistance(a, b)
        val maxLen = kotlin.math.max(a.length, b.length)
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }
    
    /**
     * Calcula la distancia de Levenshtein entre dos strings.
     */
    private fun levenshteinDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[n][m]
    }
    
    /**
     * Calcula el overlap de dígitos entre dos strings (útil para matching de NIFs).
     * Retorna un valor entre 0.0 y 1.0 indicando qué proporción de dígitos coinciden.
     */
    private fun calculateDigitOverlap(digits1: String, digits2: String): Double {
        if (digits1.isEmpty() || digits2.isEmpty()) return 0.0
        val set1 = digits1.toSet()
        val set2 = digits2.toSet()
        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size
        return if (union > 0) intersection.toDouble() / union.toDouble() else 0.0
    }

    private fun iou(a: android.graphics.Rect, b: android.graphics.Rect): Double {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0)
        val interH = (interBottom - interTop).coerceAtLeast(0)
        val interArea = interW.toDouble() * interH.toDouble()
        val areaA = (a.width().toDouble() * a.height().toDouble())
        val areaB = (b.width().toDouble() * b.height().toDouble())
        val union = areaA + areaB - interArea
        if (union <= 0.0) return 0.0
        return interArea / union
    }

    private fun generatePdfFromJson(json: JSONObject): java.io.File {
        val provider = json.optString("proveedor", "")
        val number = json.optString("numero_albaran", "")
        val createdAt = json.optLong("created_at", System.currentTimeMillis())
        val recepcionista = json.optString("recepcionista", "")
        val ubicacion = json.optString("ubicacion_recogida", "")
        val sinAlbaran = json.optBoolean("sin_albaran", false)
        val tieneIncidencias = json.optBoolean("tiene_incidencias", false)

        val outDir = requireContext().getExternalFilesDir("history")
        outDir?.mkdirs()
        val file = java.io.File(outDir, "albaran_${System.currentTimeMillis()}.pdf")

        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNumber = 1
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        val paint = Paint()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = 14f

        var y = 40f
        // Header
        canvas.drawText(getString(R.string.pdf_title), 40f, y, paint)
        y += 28f
        paint.textSize = 12f
        canvas.drawText(getString(R.string.pdf_provider, provider), 40f, y, paint)
        y += 18f
        canvas.drawText(getString(R.string.pdf_number, number), 40f, y, paint)
        y += 18f
        canvas.drawText(getString(R.string.pdf_created_at, java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(createdAt))), 40f, y, paint)
        y += 18f
        
        // Recepcionista y Ubicación
        if (recepcionista.isNotBlank()) {
            canvas.drawText("Recepcionista: $recepcionista", 40f, y, paint)
            y += 18f
        }
        if (ubicacion.isNotBlank()) {
            canvas.drawText("Ubicación de recogida: $ubicacion", 40f, y, paint)
            y += 18f
        }
        
        // Incidencias (resaltado en amarillo si hay)
        val paintYellow = Paint()
        paintYellow.color = android.graphics.Color.YELLOW
        paintYellow.style = Paint.Style.FILL
        
        val incidenciasText = "Incidencias: ${if (tieneIncidencias) "SÍ" else "NO"}"
        if (tieneIncidencias) {
            // Dibujar fondo amarillo
            val textWidth = paint.measureText(incidenciasText)
            canvas.drawRect(38f, y - 12f, 42f + textWidth, y + 4f, paintYellow)
        }
        canvas.drawText(incidenciasText, 40f, y, paint)
        y += 18f
        
        // Sin albarán (solo si está marcado, resaltado en amarillo)
        if (sinAlbaran) {
            val sinAlbaranText = "Sin albarán de entrega"
            val textWidth = paint.measureText(sinAlbaranText)
            canvas.drawRect(38f, y - 12f, 42f + textWidth, y + 4f, paintYellow)
            canvas.drawText(sinAlbaranText, 40f, y, paint)
            y += 18f
        }
        
        y += 8f

        // Productos como tabla simple
        val products = json.optJSONArray("products") ?: JSONArray()
        // column positions
        val leftCol = 40f
        val descCol = leftCol
        val unidadesCol = 360f
        val precioCol = 430f
        val importeCol = 500f

        paint.textSize = 11f
        // header row
        canvas.drawText(getString(R.string.pdf_col_descripcion), descCol, y, paint)
        canvas.drawText(getString(R.string.pdf_col_unid), unidadesCol, y, paint)
        canvas.drawText(getString(R.string.pdf_col_precio), precioCol, y, paint)
        canvas.drawText(getString(R.string.pdf_col_importe), importeCol, y, paint)
        y += 16f

        for (i in 0 until products.length()) {
            val p = products.getJSONObject(i)
            val desc = p.optString("descripcion", "")
            val unidades = p.optString("unidades", "")
            val precio = p.optString("precio", "")
            val importe = p.optString("importe", "")
            val tieneIncidencia = p.optBoolean("incidencia", false)

            // wrap description if too long
            val maxDescWidth = (unidadesCol - descCol - 8).toInt()
            val lines = mutableListOf<String>()
            var remaining = desc
            while (remaining.isNotEmpty()) {
                // naive split by length to avoid complex measuring
                val take = if (remaining.length > 80) 80 else remaining.length
                lines.add(remaining.substring(0, take))
                remaining = if (take < remaining.length) remaining.substring(take) else ""
            }

            for ((li, ltext) in lines.withIndex()) {
                if (y > pageHeight - 80) {
                    doc.finishPage(page)
                    pageNumber += 1
                    page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    canvas = page.canvas
                    y = 40f
                }
                
                // Dibujar fondo amarillo si tiene incidencia
                if (tieneIncidencia && li == 0) {
                    canvas.drawRect(leftCol - 2f, y - 12f, pageWidth - 40f, y + 4f, paintYellow)
                }
                
                if (li == 0) {
                    canvas.drawText(ltext, descCol, y, paint)
                    canvas.drawText(unidades, unidadesCol, y, paint)
                    canvas.drawText(precio, precioCol, y, paint)
                    canvas.drawText(importe, importeCol, y, paint)
                } else {
                    canvas.drawText(ltext, descCol, y, paint)
                }
                y += 14f
            }
            y += 4f
        }

        // total (try to compute sum of importes)
        var total = 0.0
        for (i in 0 until products.length()) {
            val p = products.getJSONObject(i)
            val imp = p.optString("importe", "").replace(',', '.').replace("€", "").trim()
            total += try { imp.toDouble() } catch (_: Exception) { 0.0 }
        }
        val totalStr = getString(R.string.pdf_total, total)
        // draw total right-aligned
        paint.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        paint.textSize = 12f
        val xTotal = pageWidth - 40f - paint.measureText(totalStr)
        if (y > pageHeight - 80) {
            doc.finishPage(page)
            pageNumber += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = 40f
        }
        canvas.drawText(totalStr, xTotal, y + 8f, paint)

        // finalizar la última página de texto
        doc.finishPage(page)

        // Añadir páginas para cada foto (una por página)
        val photos = json.optJSONArray("photo_paths")
        if (photos != null) {
            for (i in 0 until photos.length()) {
                try {
                    val path = photos.optString(i)
                    if (path.isNullOrBlank()) continue
                    val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: continue
                    pageNumber += 1
                    val pInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    val pPage = doc.startPage(pInfo)
                    val c = pPage.canvas

                    // calcular rect para centrar la imagen manteniendo ratio
                    val margin = 20
                    val availW = pageWidth - margin * 2
                    val availH = pageHeight - margin * 2
                    val scale = Math.min(availW.toFloat() / bmp.width.toFloat(), availH.toFloat() / bmp.height.toFloat())
                    val dw = (bmp.width * scale).toInt()
                    val dh = (bmp.height * scale).toInt()
                    val left = (pageWidth - dw) / 2
                    val top = (pageHeight - dh) / 2
                    val dest = android.graphics.Rect(left, top, left + dw, top + dh)
                    c.drawBitmap(bmp, null, dest, null)
                    doc.finishPage(pPage)
                } catch (_: Exception) {}
            }
        }

        // Escribir archivo
        FileOutputStream(file).use { fos ->
            doc.writeTo(fos)
        }
        doc.close()
        return file
    }

}

