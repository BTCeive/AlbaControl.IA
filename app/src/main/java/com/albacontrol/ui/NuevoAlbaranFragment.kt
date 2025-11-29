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
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
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
import java.util.*

class NuevoAlbaranFragment : Fragment() {

    private lateinit var productContainer: LinearLayout
    private lateinit var btnAddProduct: Button
    private lateinit var checkSinAlbaran: CheckBox
    private lateinit var checkIncidenciaAlbaran: CheckBox

    // estado para aprendizaje: último OCR y bitmap usado
    private var lastOcrResult: com.albacontrol.ml.OCRResult? = null
    private var lastOcrBitmap: android.graphics.Bitmap? = null

    // Rutas de fotos añadidas por el usuario (se incluirán en el PDF)
    private val photoPaths: MutableList<String> = mutableListOf()
    private val pendingDeletionRunnables: MutableMap<String, Runnable> = mutableMapOf()

    // ActivityResultLaunchers (registro en onCreate para evitar IllegalStateException)
    private lateinit var takePictureLauncher: ActivityResultLauncher<Void?>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var pickPhotosLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                onImageCaptured(bitmap)
            }
        }
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val bmp = try {
                    requireActivity().contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    onImageCaptured(bmp)
                } else {
                    Toast.makeText(requireContext(), "No se pudo decodificar la imagen seleccionada.", Toast.LENGTH_LONG).show()
                }
            }
        }
        pickPhotosLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<android.net.Uri>? ->
            if (uris == null || uris.isEmpty()) return@registerForActivityResult
            for (u in uris) {
                try {
                    val bmp = requireActivity().contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    if (bmp != null) onImageCaptured(bmp)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_nuevo_albaran, container, false)
        // Inicializar contenedor de productos tempranamente para evitar perder datos al restaurar estado
        productContainer = view.findViewById(R.id.product_container)

        // Si venimos a editar un borrador, cargar sus datos
        val draftFromArgs = arguments?.getString("draft_json")

        // Si hay estado guardado por rotación, restaurarlo primero
        val saved = savedInstanceState?.getString("form_state")
        val toLoad = saved ?: draftFromArgs
        toLoad?.let { draftJson ->
            try {
                val jo = org.json.JSONObject(draftJson)
                view.findViewById<EditText>(R.id.etProveedor).setText(jo.optString("proveedor", ""))
                view.findViewById<EditText>(R.id.etNif).setText(jo.optString("nif", ""))
                view.findViewById<EditText>(R.id.etNumeroAlbaran).setText(jo.optString("numero_albaran", ""))
                view.findViewById<EditText>(R.id.etFechaAlbaran).setText(jo.optString("fecha_albaran", ""))

                // cargar productos
                val productsArray = jo.optJSONArray("products")
                if (productsArray != null) {
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
                        val btnDelete = item.findViewById<ImageButton>(R.id.btnDeleteProduct)
                        btnDelete.setOnClickListener {
                            val dialog = AlertDialog.Builder(requireContext())
                                .setTitle("Eliminar producto")
                                .setMessage("¿Seguro que quieres eliminar este producto?")
                                .setPositiveButton("Eliminar") { _, _ -> productContainer.removeView(item) }
                                .setNegativeButton("Cancelar", null)
                                .create()
                            dialog.setOnShowListener {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                            }
                            dialog.show()
                        }
                        productContainer.addView(item)
                    }
                }
            } catch (e: Exception) {
                // ignore parse errors
            }
        }

        // Fecha de creación
        val tvCreatedAt = view.findViewById<TextView>(R.id.tvCreatedAt)
        val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
        tvCreatedAt.text = "Creado: ${sdf.format(Date())}"

        productContainer = view.findViewById(R.id.product_container)
        btnAddProduct = view.findViewById(R.id.btnAddProduct)
        checkSinAlbaran = view.findViewById(R.id.checkSinAlbaran)
        checkIncidenciaAlbaran = view.findViewById(R.id.checkIncidenciaAlbaran)

        // Añadir primer producto vacío al iniciar
        addProductBox()

        btnAddProduct.setOnClickListener {
            addProductBox()
        }

        // Botones Cámara / Subir (placeholders)
        view.findViewById<Button>(R.id.btnCamera).setOnClickListener {
            // Lanzar cámara (preview) y procesar imagen
            takePictureLauncher.launch(null)
        }

        view.findViewById<Button>(R.id.btnUpload).setOnClickListener {
            // Abrir selector de imágenes
            pickImageLauncher.launch("image/*")
        }

        // Botón para guardar patrón a partir de la última lectura OCR y correcciones
        view.findViewById<Button>(R.id.btnSavePattern)?.setOnClickListener {
            savePatternFromCorrections()
        }

        // Contenedor de miniaturas y botón añadir fotografía
        val photoContainer = view.findViewById<LinearLayout>(R.id.photo_container)
        // botón lanzar selector múltiple
        view.findViewById<Button>(R.id.btnAddPhoto).setOnClickListener {
            // lanzar selector que permite múltiples selecciones
            try {
                pickPhotosLauncher.launch(arrayOf("image/*"))
            } catch (e: Exception) {
                // fallback: abrir selector simple
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

            // Guardar en Room usando coroutines
            val db = AppDatabase.getInstance(requireContext())
            val draft = Draft(providerId = null, dataJson = json.toString(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            lifecycleScope.launch {
                try {
                    val id = withContext(Dispatchers.IO) { db.draftDao().insert(draft) }
                    Toast.makeText(requireContext(), "Borrador guardado (id=$id)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error guardando borrador: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Cancelar
        view.findViewById<Button>(R.id.btnCancelar).setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("Cancelar")
                .setMessage("¿Descartar cambios y salir?")
                .setPositiveButton("Sí") { _, _ -> parentFragmentManager.popBackStack() }
                .setNegativeButton("No", null)
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
                Toast.makeText(requireContext(), "El campo 'Proveedor' es obligatorio.", Toast.LENGTH_SHORT).show()
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

            // Generar PDF y guardar CompletedAlbaran usando coroutines
            val db = AppDatabase.getInstance(requireContext())
            lifecycleScope.launch {
                try {
                    // incluir rutas de fotos en el JSON para que el generador las añada al PDF
                    json.put("photo_paths", org.json.JSONArray(photoPaths))
                    val pdfFile = withContext(Dispatchers.IO) { generatePdfFromJson(json) }
                    // añadir ruta pdf al json
                    json.put("pdf_path", pdfFile.absolutePath)

                    val completed = com.albacontrol.data.CompletedAlbaran(providerId = null, dataJson = json.toString(), createdAt = System.currentTimeMillis())
                    val id = withContext(Dispatchers.IO) { db.completedDao().insert(completed) }

                    Toast.makeText(requireContext(), "Albarán finalizado (id=$id)", Toast.LENGTH_SHORT).show()
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
                        startActivity(Intent.createChooser(email, "Enviar albarán"))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Albarán guardado, pero no se pudo abrir el cliente de correo: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error finalizando albarán: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Spinners + botones añadir (placeholders)
        view.findViewById<ImageButton>(R.id.btnAddUbicacion).setOnClickListener {
            Toast.makeText(requireContext(), "Añadir ubicación (placeholder)", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<ImageButton>(R.id.btnAddRecepcionista).setOnClickListener {
            Toast.makeText(requireContext(), "Añadir recepcionista (placeholder)", Toast.LENGTH_SHORT).show()
        }

        return view
    }

    private fun onImageCaptured(bitmap: android.graphics.Bitmap) {
        // Cuando se obtiene una imagen, procesarla con ML Kit
        checkSinAlbaran.isChecked = false
        Toast.makeText(requireContext(), "Procesando imagen...", Toast.LENGTH_SHORT).show()
        // guardar bitmap para posible muestra de plantilla
        lastOcrBitmap = bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)

        // Guardar copia de la foto en background y añadir a photoPaths + actualizar miniaturas
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val outDir = requireContext().getExternalFilesDir("history_photos")
                    outDir?.mkdirs()
                    val f = java.io.File(outDir, "photo_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { fos ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                    val path = f.absolutePath
                    // añadir al inicio de la lista
                    withContext(Dispatchers.Main) {
                        photoPaths.add(0, path)
                        updatePhotoThumbnails()
                    }
                }
            } catch (_: Exception) {}
        }
        com.albacontrol.ml.OcrProcessor.processBitmap(bitmap) { result, error ->
            activity?.runOnUiThread {
                if (error != null) {
                    Toast.makeText(requireContext(), "Error OCR: ${error.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                if (result != null) {
                    lastOcrResult = result
                    applyOcrResultToForm(result)
                    // Intentar aplicar plantilla si existe
                    lifecycleScope.launch {
                        try {
                            applyTemplateIfExists(result, bitmap)
                        } catch (_: Exception) {}
                    }
                    Toast.makeText(requireContext(), "OCR aplicado", Toast.LENGTH_SHORT).show()
                }
            }
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
                                Snackbar.make(rootView, "Foto eliminada", Snackbar.LENGTH_LONG).setAction("Deshacer") {
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

    private suspend fun applyTemplateIfExists(result: com.albacontrol.ml.OCRResult, bitmap: android.graphics.Bitmap) {
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val candidates = listOfNotNull(result.nif?.trim(), result.proveedor?.trim()).filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return

        val templates = withContext(Dispatchers.IO) { db.templateDao().getAllTemplates() }
        val tpl = templates.firstOrNull { candidates.contains(it.providerNif) } ?: return

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        for ((field, bboxStr) in tpl.mappings) {
            try {
                val parts = bboxStr.split(',')
                if (parts.size != 4) continue
                val x = parts[0].toFloat()
                val y = parts[1].toFloat()
                val w = parts[2].toFloat()
                val h = parts[3].toFloat()
                val left = (x * bw).toInt().coerceIn(0, bitmap.width - 1)
                val top = (y * bh).toInt().coerceIn(0, bitmap.height - 1)
                val right = (left + (w * bw).toInt()).coerceAtMost(bitmap.width)
                val bottom = (top + (h * bh).toInt()).coerceAtMost(bitmap.height)
                if (right <= left || bottom <= top) continue

                val crop = android.graphics.Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val image = com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0)
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                val visionText = withContext(Dispatchers.IO) {
                    try {
                        val task = recognizer.process(image)
                        // wait for completion (wrap with suspendCoroutine)
                        kotlinx.coroutines.suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                            task.addOnSuccessListener { t -> cont.resume(t) {} }
                            task.addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                val text = visionText?.text?.trim()
                if (!text.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        val view = requireView()
                        when (field) {
                            "proveedor" -> view.findViewById<EditText>(R.id.etProveedor).setText(text)
                            "nif" -> view.findViewById<EditText>(R.id.etNif).setText(text)
                            "numero_albaran" -> view.findViewById<EditText>(R.id.etNumeroAlbaran).setText(text)
                            "fecha_albaran" -> view.findViewById<EditText>(R.id.etFechaAlbaran).setText(text)
                            else -> {}
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore per-field errors
            }
        }

        // apply product-row and product columns if template defines them
        try {
            val pr = tpl.mappings["product_row"]
            if (pr != null) {
                val parts = pr.split(',')
                if (parts.size == 4) {
                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()
                    val px = parts[0].toFloat()
                    val py = parts[1].toFloat()
                    val pw = parts[2].toFloat()
                    val ph = parts[3].toFloat()
                    val left = (px * bw).toInt().coerceIn(0, bitmap.width - 1)
                    val top = (py * bh).toInt().coerceIn(0, bitmap.height - 1)
                    val right = (left + (pw * bw).toInt()).coerceAtMost(bitmap.width)
                    val bottom = (top + (ph * bh).toInt()).coerceAtMost(bitmap.height)
                    if (right > left && bottom > top) {
                        // find candidate product lines from lastOcrResult products that intersect this area
                        val candidates = lastOcrResult?.products?.filter { it.bbox != null && android.graphics.Rect.intersects(it.bbox, android.graphics.Rect(left, top, right, bottom)) } ?: emptyList()
                        val rowsToUse = if (candidates.isNotEmpty()) candidates else {
                            // fallback: attempt to split the product_row area into multiple rows by scanning for horizontal text lines via ML Kit on the cropped area
                            emptyList<com.albacontrol.ml.OCRProduct>()
                        }

                        // collect column mappings
                        val colKeys = tpl.mappings.keys.filter { it.startsWith("product_") && it != "product_row" }
                        val cols = colKeys.mapNotNull { k -> tpl.mappings[k]?.let { k to it } }.toMap()

                        if (rowsToUse.isNotEmpty() && cols.isNotEmpty()) {
                            // clear current products and fill with detected rows
                            withContext(Dispatchers.Main) {
                                productContainer.removeAllViews()
                            }
                            for (p in rowsToUse) {
                                val rowB = p.bbox!!
                                // for each column, crop and run recognizer
                                val descText = p.descripcion
                                val unidadesText = StringBuilder()
                                val precioText = StringBuilder()
                                val importeText = StringBuilder()
                                for ((key, bboxStr) in cols) {
                                    try {
                                        val partsC = bboxStr.split(',')
                                        if (partsC.size != 4) continue
                                        val cx = partsC[0].toFloat()
                                        val cy = partsC[1].toFloat()
                                        val cw = partsC[2].toFloat()
                                        val ch = partsC[3].toFloat()
                                        val cleft = (cx * bw).toInt().coerceIn(0, bitmap.width - 1)
                                        val ctop = (cy * bh).toInt().coerceIn(0, bitmap.height - 1)
                                        val cright = (cleft + (cw * bw).toInt()).coerceAtMost(bitmap.width)
                                        val cbottom = (ctop + (ch * bh).toInt()).coerceAtMost(bitmap.height)
                                        if (cright <= cleft || cbottom <= ctop) continue
                                        val crop = android.graphics.Bitmap.createBitmap(bitmap, cleft, ctop, cright - cleft, cbottom - ctop)
                                        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0)
                                        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                                        val visionText = withContext(Dispatchers.IO) {
                                            try {
                                                val task = recognizer.process(image)
                                                kotlinx.coroutines.suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                                                    task.addOnSuccessListener { t -> cont.resume(t) {} }
                                                    task.addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
                                                }
                                            } catch (e: Exception) {
                                                null
                                            }
                                        }
                                        val txt = visionText?.text?.trim() ?: ""
                                        when (key) {
                                            "product_unidades" -> unidadesText.append(txt)
                                            "product_precio" -> precioText.append(txt)
                                            "product_importe" -> importeText.append(txt)
                                            else -> {}
                                        }
                                    } catch (_: Exception) {}
                                }
                                // add product item view with recognized texts
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
    }

    private fun applyOcrResultToForm(result: com.albacontrol.ml.OCRResult) {
        val view = requireView()
        val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
        val etNif = view.findViewById<EditText>(R.id.etNif)
        val etNumero = view.findViewById<EditText>(R.id.etNumeroAlbaran)
        val etFechaAlb = view.findViewById<EditText>(R.id.etFechaAlbaran)

        if (!result.proveedor.isNullOrBlank()) etProveedor.setText(result.proveedor)
        if (!result.nif.isNullOrBlank()) etNif.setText(result.nif)
        if (!result.numeroAlbaran.isNullOrBlank()) etNumero.setText(result.numeroAlbaran)
        if (!result.fechaAlbaran.isNullOrBlank()) etFechaAlb.setText(result.fechaAlbaran)

        // Añadir productos detectados: borrar los vacíos iniciales y añadir los detectados
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
                        .setTitle("Eliminar producto")
                        .setMessage("¿Seguro que quieres eliminar este producto?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            productContainer.removeView(item)
                        }
                        .setNegativeButton("Cancelar", null)
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

    }

    private fun savePatternFromCorrections() {
        val view = requireView()
        val etProveedor = view.findViewById<EditText>(R.id.etProveedor)
        val etNif = view.findViewById<EditText>(R.id.etNif)

        val providerKey = etNif.text.toString().ifBlank { etProveedor.text.toString() }
        if (providerKey.isBlank()) {
            Toast.makeText(requireContext(), "Necesitas identificar proveedor o NIF para guardar el patrón.", Toast.LENGTH_SHORT).show()
            return
        }

        val result = lastOcrResult
        val bmp = lastOcrBitmap
        if (result == null || bmp == null) {
            Toast.makeText(requireContext(), "No hay resultado OCR reciente para generar patrón.", Toast.LENGTH_SHORT).show()
            return
        }

        val mappings = mutableMapOf<String, String>()
        val fieldToBBox = mapOf(
            "proveedor" to result.proveedorBBox,
            "nif" to result.nifBBox,
            "numero_albaran" to result.numeroBBox,
            "fecha_albaran" to result.fechaBBox
        )

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        for ((field, bbox) in fieldToBBox) {
            if (bbox != null && bw > 0 && bh > 0) {
                val x = bbox.left.toFloat() / bw
                val y = bbox.top.toFloat() / bh
                val w = bbox.width().toFloat() / bw
                val h = bbox.height().toFloat() / bh
                val s = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", x, y, w, h)
                mappings[field] = s
            }
        }

        if (mappings.isEmpty()) {
            Toast.makeText(requireContext(), "No se han detectado bboxes válidos para los campos; no se guardará patrón.", Toast.LENGTH_SHORT).show()
            return
        }

        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        lifecycleScope.launch {
            try {
                // also derive product-row patterns if possible
                if (result.products.isNotEmpty()) {
                    // collect product bboxes
                    val productBBoxes = result.products.mapNotNull { it.bbox }
                    if (productBBoxes.isNotEmpty()) {
                        val left = productBBoxes.minOf { it.left }
                        val top = productBBoxes.minOf { it.top }
                        val right = productBBoxes.maxOf { it.right }
                        val bottom = productBBoxes.maxOf { it.bottom }
                        val prBBox = android.graphics.Rect(left, top, right, bottom)
                        val bw = bmp.width.toFloat()
                        val bh = bmp.height.toFloat()
                        val x = prBBox.left.toFloat() / bw
                        val y = prBBox.top.toFloat() / bh
                        val w = prBBox.width().toFloat() / bw
                        val h = prBBox.height().toFloat() / bh
                        mappings["product_row"] = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", x, y, w, h)

                        // derive numeric column boxes by index across product lines
                        val maxNums = result.products.maxOf { it.numericElementBBoxes.size }
                        for (i in 0 until maxNums) {
                            val cols = mutableListOf<android.graphics.Rect>()
                            for (p in result.products) {
                                val nb = p.numericElementBBoxes.getOrNull(i)
                                if (nb != null) cols.add(nb)
                            }
                            if (cols.isNotEmpty()) {
                                val cLeft = cols.minOf { it.left }
                                val cRight = cols.maxOf { it.right }
                                // column spans full row vertically
                                val colX = cLeft.toFloat() / bw
                                val colW = (cRight - cLeft).toFloat() / bw
                                val colY = y
                                val colH = h
                                val key = when (i) {
                                    0 -> "product_unidades"
                                    1 -> "product_precio"
                                    2 -> "product_importe"
                                    else -> "product_col_$i"
                                }
                                mappings[key] = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", colX, colY, colW, colH)
                            }
                        }
                    }
                }

                val tpl = com.albacontrol.data.OCRTemplate(providerNif = providerKey, mappings = mappings)
                withContext(Dispatchers.IO) { db.templateDao().insertTemplate(tpl) }

                // Guardar imagen de muestra
                val outDir = requireContext().getExternalFilesDir("templates")
                outDir?.mkdirs()
                val file = java.io.File(outDir, "tpl_sample_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    file.outputStream().use { fos ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                }

                // fieldMappings: bbox::recognizedText
                val fieldMappings = mutableMapOf<String, String>()
                for ((k, v) in mappings) {
                    val recognized = when (k) {
                        "proveedor" -> result.proveedor ?: ""
                        "nif" -> result.nif ?: ""
                        "numero_albaran" -> result.numeroAlbaran ?: ""
                        "fecha_albaran" -> result.fechaAlbaran ?: ""
                        "product_row" -> ""
                        else -> ""
                    }
                    fieldMappings[k] = "$v::$recognized"
                }

                val sample = com.albacontrol.data.TemplateSample(providerNif = providerKey, imagePath = file.absolutePath, fieldMappings = fieldMappings)
                withContext(Dispatchers.IO) { db.templateDao().insertSample(sample) }

                Toast.makeText(requireContext(), "Plantilla guardada para: $providerKey", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error guardando plantilla: ${e.message}", Toast.LENGTH_LONG).show()
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
                .setTitle("Eliminar producto")
                .setMessage("¿Seguro que quieres eliminar este producto?")
                .setPositiveButton("Eliminar") { _, _ ->
                    productContainer.removeView(item)
                }
                .setNegativeButton("Cancelar", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
            }
            dialog.show()
        }

        // attach watchers to new item for auto-save
        attachWatchersToProductItem(item)

        productContainer.addView(item, 0) // añadir encima del botón
    }

    private var patternSaveJob: Job? = null

    private fun scheduleAutoSavePattern() {
        patternSaveJob?.cancel()
        patternSaveJob = lifecycleScope.launch {
            delay(1800)
            savePatternFromCorrections()
        }
    }

    private fun attachWatchersToProductItem(item: View) {
        val etDesc = item.findViewById<EditText>(R.id.etDescripcion)
        val etUnid = item.findViewById<EditText>(R.id.etUnidades)
        val etPrecio = item.findViewById<EditText>(R.id.etPrecio)
        val etImporte = item.findViewById<EditText>(R.id.etImporte)

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // schedule auto-save when user edits product fields
                if (lastOcrResult != null) scheduleAutoSavePattern()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }

        etDesc.addTextChangedListener(watcher)
        etUnid.addTextChangedListener(watcher)
        etPrecio.addTextChangedListener(watcher)
        etImporte.addTextChangedListener(watcher)
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

            outState.putString("form_state", json.toString())
        } catch (_: Exception) {}
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
            val draft = com.albacontrol.data.Draft(providerId = null, dataJson = json.toString(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            lifecycleScope.launch(Dispatchers.IO) { try { db.draftDao().insert(draft) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun generatePdfFromJson(json: JSONObject): java.io.File {
        val provider = json.optString("proveedor", "")
        val number = json.optString("numero_albaran", "")
        val createdAt = json.optLong("created_at", System.currentTimeMillis())

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
        canvas.drawText("Albarán", 40f, y, paint)
        y += 28f
        paint.textSize = 12f
        canvas.drawText("Proveedor: $provider", 40f, y, paint)
        y += 18f
        canvas.drawText("Número: $number", 40f, y, paint)
        y += 18f
        canvas.drawText("Fecha creación: ${java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(createdAt))}", 40f, y, paint)
        y += 22f

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
        canvas.drawText("Descripción", descCol, y, paint)
        canvas.drawText("Unid.", unidadesCol, y, paint)
        canvas.drawText("Precio", precioCol, y, paint)
        canvas.drawText("Importe", importeCol, y, paint)
        y += 16f

        for (i in 0 until products.length()) {
            val p = products.getJSONObject(i)
            val desc = p.optString("descripcion", "")
            val unidades = p.optString("unidades", "")
            val precio = p.optString("precio", "")
            val importe = p.optString("importe", "")

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
        val totalStr = String.format(java.util.Locale.getDefault(), "Importe total: %.2f €", total)
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

