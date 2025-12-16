package com.albacontrol.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.albacontrol.R
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.util.Log
import java.io.*
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color

class OpcionesFragment : Fragment() {
    private val DEBUG_INTERACTION = false
    private var debugToastShown = false

    private val PREFS = "alba_prefs"
    private val KEY_RECEP = "recepcionistas"
    private val KEY_UBIC = "ubicaciones"
    private val KEY_EMAILS = "emails"
    private val KEY_LANG = "language"
    private val KEY_OCR = "ocr_passes"
    private val KEY_AUTO_DELETE_AGE_ENABLED = "auto_delete_age_enabled"
    private val KEY_AUTO_DELETE_AGE_DAYS = "auto_delete_age_days"
    private val KEY_AUTO_DELETE_COUNT_ENABLED = "auto_delete_count_enabled"
    private val KEY_AUTO_DELETE_COUNT_MAX = "auto_delete_count_max"
    private val KEY_LAST_AUTO_CLEANUP = "last_auto_cleanup_ts"
    private val KEY_CONFIG_COLLAPSED = "config_collapsed"
    private val KEY_PREFERRED_EMAIL_APP = "preferred_email_app"

    private lateinit var containerRecep: LinearLayout
    private lateinit var containerUbic: LinearLayout
    private lateinit var containerEmails: LinearLayout
    private lateinit var spinnerLang: Spinner
    // private lateinit var seekOcr: SeekBar // REMOVIDO - Sistema Multi-Pass OCR usa 5 pasadas fijas
    private lateinit var spinnerEmailApp: Spinner
    private var progressDialog: AlertDialog? = null
    private val TAG = "OpcionesFragment"
    // UI for auto-delete
    private lateinit var switchAutoDeleteAge: Switch
    private lateinit var etAutoDeleteDays: EditText
    private lateinit var switchAutoDeleteCount: Switch
    private lateinit var etAutoDeleteMax: EditText
    private var skipInitialLanguageSelection = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_opciones, container, false)
        if (DEBUG_INTERACTION) {
            Log.d(TAG, "onCreateView: fragment_opciones inflado")
            if (!debugToastShown) {
                Toast.makeText(requireContext(), getString(R.string.options_loading), Toast.LENGTH_SHORT).show()
                debugToastShown = true
            }
        }

        containerRecep = view.findViewById(R.id.containerRecepcionistas)
        containerUbic = view.findViewById(R.id.containerUbicaciones)
        containerEmails = view.findViewById(R.id.containerEmails)
        spinnerLang = view.findViewById(R.id.spinnerLanguages)
        // seekOcr = view.findViewById(R.id.seekOcrPasses) // REMOVIDO
        spinnerEmailApp = view.findViewById(R.id.spinnerEmailApp)
        switchAutoDeleteAge = view.findViewById(R.id.switchAutoDeleteAge)
        etAutoDeleteDays = view.findViewById(R.id.etAutoDeleteDays)
        switchAutoDeleteCount = view.findViewById(R.id.switchAutoDeleteCount)
        etAutoDeleteMax = view.findViewById(R.id.etAutoDeleteMax)

        view.findViewById<View>(R.id.btnAddRecepcionistaOptions).setOnClickListener {
            Log.d(TAG, "Click btnAddRecepcionistaOptions")
            addOptionDialog(KEY_RECEP, getString(R.string.new_receptionist))
        }
        view.findViewById<View>(R.id.btnAddRecepcionistaOptions).setOnTouchListener { v, ev ->
            if (DEBUG_INTERACTION) Log.d(TAG, "Touch btnAddRecepcionistaOptions action=${ev.action}")
            false
        }
        view.findViewById<View>(R.id.btnAddUbicacionOptions).setOnClickListener {
            Log.d(TAG, "Click btnAddUbicacionOptions")
            addOptionDialog(KEY_UBIC, getString(R.string.new_location))
        }
        view.findViewById<View>(R.id.btnAddUbicacionOptions).setOnTouchListener { v, ev ->
            if (DEBUG_INTERACTION) Log.d(TAG, "Touch btnAddUbicacionOptions action=${ev.action}")
            false
        }
        view.findViewById<View>(R.id.btnAddEmailOptions).setOnClickListener {
            Log.d(TAG, "Click btnAddEmailOptions")
            addOptionDialog(KEY_EMAILS, getString(R.string.new_email), inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        }
        view.findViewById<View>(R.id.btnAddEmailOptions).setOnTouchListener { v, ev ->
            if (DEBUG_INTERACTION) Log.d(TAG, "Touch btnAddEmailOptions action=${ev.action}")
            false
        }
        // Instrumentación rápida para detectar si los clics llegan
        // Ya son Button, no se necesita instrumentación adicional

        // backup buttons
        view.findViewById<ImageButton>(R.id.btnExportDrafts).setOnClickListener { confirmExport("Borradores") }
        view.findViewById<ImageButton>(R.id.btnImportDrafts).setOnClickListener { confirmImport("Borradores") }
        view.findViewById<ImageButton>(R.id.btnExportHistory).setOnClickListener { confirmExport("Historial") }
        view.findViewById<ImageButton>(R.id.btnImportHistory).setOnClickListener { confirmImport("Historial") }
        view.findViewById<ImageButton>(R.id.btnExportProviders).setOnClickListener { confirmExport("Proveedores/Productos") }
        view.findViewById<ImageButton>(R.id.btnImportProviders).setOnClickListener { confirmImport("Proveedores/Productos") }
        view.findViewById<ImageButton>(R.id.btnExportPatterns).setOnClickListener { confirmExport("Patrones") }
        view.findViewById<ImageButton>(R.id.btnImportPatterns).setOnClickListener { confirmImport("Patrones") }
        // Backup general (export/import everything at once)
        view.findViewById<ImageButton>(R.id.btnExportBackupAll).setOnClickListener { confirmExport("BackupGeneral") }
        view.findViewById<ImageButton>(R.id.btnImportBackupAll).setOnClickListener { confirmImport("BackupGeneral") }

        // Restaurar app (borrar datos)
        view.findViewById<Button>(R.id.btnRestoreApp).setOnClickListener { confirmRestoreApp() }

        // Activity result for picking import file
        pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleImportUri(it) }
        }

        setupLanguageSpinner()
        setupEmailAppSpinner()
        loadOptions()
        loadAutoDeleteOptions()
        performAutoCleanupIfNeeded()
        setupAppInfoSection()

        // Config section collapsible (siempre colapsado al abrir)
        try {
            val llConfigHeader = view.findViewById<View>(R.id.llConfigHeader)
            val configContent = view.findViewById<View>(R.id.configContent)
            val tvConfigArrow = view.findViewById<TextView>(R.id.tvConfigArrow)
            var collapsed = true  // Siempre empieza colapsado
            configContent.visibility = if (collapsed) View.GONE else View.VISIBLE
            tvConfigArrow.rotation = if (collapsed) -90f else 0f
            llConfigHeader?.setOnClickListener {
                collapsed = !collapsed
                configContent.visibility = if (collapsed) View.GONE else View.VISIBLE
                tvConfigArrow.animate().rotation(if (collapsed) -90f else 0f).setDuration(200).start()
                // No guardamos el estado - siempre empieza colapsado
            }
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo inicializar header collapsible: ${e.message}")
        }

        // OCR SeekBar: REMOVIDO - Sistema Multi-Pass OCR ahora usa 5 pasadas estándar fijas
        // El slider ya no es necesario ya que el sistema está optimizado con 5 pasadas
        // que proporcionan el mejor balance entre precisión y rendimiento
        // (La sección se ha removido del layout XML)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (DEBUG_INTERACTION) {
            Log.d(TAG, "onViewCreated: listo para interacción")
            view.isClickable = true
            view.setOnTouchListener { _, ev ->
                Log.d(TAG, "Touch root action=${ev.action} x=${ev.x} y=${ev.y}")
                false
            }
            enumerateClickableViews(view)
        }
    }

    override fun onResume() {
        super.onResume()
        if (DEBUG_INTERACTION) Log.d(TAG, "onResume: OpcionesFragment visible")
    }

    private fun enumerateClickableViews(root: View) {
        if (!DEBUG_INTERACTION) return
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c.isClickable) {
                    Log.d(TAG, "Clickable view id=${c.resources.getResourceEntryName(c.id)} class=${c.javaClass.simpleName}")
                }
                enumerateClickableViews(c)
            }
        }
    }

    // --- Export / Import implementation ---
    private var pendingImportArea: String? = null
    private var pendingImportReplace: Boolean = false
    private lateinit var pickFileLauncher: androidx.activity.result.ActivityResultLauncher<String>

    private fun confirmExport(area: String) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.export_area_title, area))
            .setMessage(getString(R.string.export_area_message, area))
            .setPositiveButton(getString(R.string.export)) { _, _ ->
                showProgress(getString(R.string.exporting_progress, area))
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            when (area) {
                                "Borradores" -> exportDrafts()
                                "Historial" -> exportHistory()
                                "Proveedores/Productos" -> exportProviders()
                                "Patrones" -> exportPatterns()
                                "BackupGeneral" -> exportBackupAll()
                            }
                        }
                        Toast.makeText(requireContext(), getString(R.string.export_complete, area), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(R.string.export_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    } finally {
                        hideProgress()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)

        val dlg = builder.show()
        try {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        } catch (_: Exception) {}
    }

    private fun confirmImport(area: String) {
        val choices = arrayOf(getString(R.string.choice_merge), getString(R.string.choice_replace))
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.import_area_title, area))
            .setItems(choices) { _, which ->
                pendingImportArea = area
                pendingImportReplace = (which == 1)
                // Launch picker: prefer json for most, zip for history and for BackupGeneral
                val mime = if (area == "Historial" || area == "BackupGeneral") "application/zip" else "application/json"
                pickFileLauncher.launch("$mime")
            }
            .setNegativeButton(getString(R.string.cancel), null)

        val dlg = builder.show()
        try {
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        } catch (_: Exception) {}
    }

    private fun handleImportUri(uri: Uri) {
        val area = pendingImportArea ?: return
        val replace = pendingImportReplace
        showProgress(getString(R.string.importing_progress, area))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (area) {
                        "Borradores" -> importDrafts(uri, replace)
                        "Proveedores/Productos" -> importProvidersFile(uri, replace)
                        "Patrones" -> importPatterns(uri, replace)
                        "Historial" -> importHistoryZip(uri, replace)
                        "BackupGeneral" -> importBackupAll(uri, replace)
                    }
                }
                Toast.makeText(requireContext(), getString(R.string.import_complete, area), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.import_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                hideProgress()
            }
        }
    }

    private suspend fun exportDrafts() {
        Log.d(TAG, "exportDrafts: iniciando export de borradores")
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val drafts = db.draftDao().getAll()
        Log.d(TAG, "exportDrafts: encontrados ${drafts.size} borradores")
        val arr = JSONArray()
        for (d in drafts) {
            val jo = JSONObject()
            jo.put("id", d.id)
            jo.put("providerId", d.providerId)
            jo.put("dataJson", d.dataJson)
            jo.put("createdAt", d.createdAt)
            jo.put("updatedAt", d.updatedAt)
            arr.put(jo)
        }
        val outDir = requireContext().getExternalFilesDir("exports")
        outDir?.mkdirs()
        val file = File(outDir, "drafts_${System.currentTimeMillis()}.json")
        file.writeText(arr.toString(2))
        Log.d(TAG, "exportDrafts: escrito ${file.absolutePath}")
    }

    private suspend fun exportProviders() {
        Log.d(TAG, "exportProviders: iniciando export de proveedores")
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val completed = db.completedDao().getAll()
        val map = org.json.JSONObject()
        for (c in completed) {
            try {
                val jo = JSONObject(c.dataJson)
                val provider = jo.optString("proveedor", "")
                if (provider.isBlank()) continue
                val productsArr = jo.optJSONArray("products") ?: JSONArray()
                val pArr = map.optJSONArray(provider) ?: JSONArray()
                for (i in 0 until productsArr.length()) {
                    val p = productsArr.getJSONObject(i)
                    pArr.put(p.optString("descripcion", ""))
                }
                map.put(provider, pArr)
            } catch (_: Exception) {}
        }
        val outDir = requireContext().getExternalFilesDir("exports")
        outDir?.mkdirs()
        val file = File(outDir, "providers_${System.currentTimeMillis()}.json")
        file.writeText(map.toString(2))
        Log.d(TAG, "exportProviders: escrito ${file.absolutePath}")
    }

    private suspend fun exportPatterns() {
        Log.d(TAG, "exportPatterns: iniciando export de patrones")
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val tDao = db.templateDao()
        val templates = tDao.getAllTemplates()
        val samples = tDao.getAllSamples()

        val out = JSONObject()
        val tArr = JSONArray()
        for (t in templates) {
            val jo = JSONObject()
            jo.put("id", t.id)
            jo.put("providerNif", t.providerNif)
            jo.put("mappings", JSONObject(t.mappings ?: emptyMap<String, String>()))
            tArr.put(jo)
        }
        val sArr = JSONArray()
        for (s in samples) {
            val jo = JSONObject()
            jo.put("id", s.id)
            jo.put("providerNif", s.providerNif)
            jo.put("imagePath", s.imagePath)
            jo.put("fieldMappings", JSONObject(s.fieldMappings ?: emptyMap<String, String>()))
            jo.put("createdAt", s.createdAt)
            sArr.put(jo)
        }
        out.put("templates", tArr)
        out.put("samples", sArr)

        val outDir = requireContext().getExternalFilesDir("exports")
        outDir?.mkdirs()
        val file = File(outDir, "patterns_${System.currentTimeMillis()}.json")
        file.writeText(out.toString(2))
        Log.d(TAG, "exportPatterns: escrito ${file.absolutePath}")
    }

    private suspend fun exportHistory() {
        Log.d(TAG, "exportHistory: iniciando export de historial")
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val completed = db.completedDao().getAll()
        val outDir = requireContext().getExternalFilesDir("exports")
        outDir?.mkdirs()
        val zipFile = File(outDir, "history_${System.currentTimeMillis()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            val manifest = JSONArray()
            for (c in completed) {
                try {
                    val jo = JSONObject(c.dataJson)
                    val pdf = jo.optString("pdf_path", null)
                    if (pdf.isNullOrEmpty()) continue
                    val f = File(pdf)
                    if (!f.exists()) continue
                    val entryName = f.name
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(f).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                    Log.d(TAG, "exportHistory: añadido ${f.absolutePath} al zip")
                    val item = JSONObject()
                    item.put("id", c.id)
                    item.put("providerId", c.providerId)
                    item.put("file", entryName)
                    manifest.put(item)
                } catch (_: Exception) {}
            }
            // add manifest
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        Log.d(TAG, "exportHistory: creado ${zipFile.absolutePath}")
    }

    // --- Import implementations ---
    private suspend fun importDrafts(uri: Uri, replace: Boolean) {
        Log.d(TAG, "importDrafts: importar desde Uri=$uri replace=$replace")
        val content = readTextFromUri(uri)
        val arr = JSONArray(content)
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        if (replace) {
            // delete all drafts (no DAO method, we can delete by id via getAll then delete)
            val existing = db.draftDao().getAll()
            for (d in existing) db.draftDao().deleteById(d.id)
        }
        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            val dataJson = jo.optString("dataJson", "")
            val providerId = if (jo.has("providerId") && !jo.isNull("providerId")) jo.optLong("providerId") else null
            val draft = com.albacontrol.data.Draft(providerId = providerId, dataJson = dataJson, createdAt = jo.optLong("createdAt", System.currentTimeMillis()), updatedAt = jo.optLong("updatedAt", System.currentTimeMillis()))
            db.draftDao().insert(draft)
        }
        Log.d(TAG, "importDrafts: import completado, ${arr.length()} elementos procesados")
    }

    private fun importProvidersFile(uri: Uri, replace: Boolean) {
        Log.d(TAG, "importProvidersFile: importar desde Uri=$uri replace=$replace")
        // store the file into app folder for later use; if replace, overwrite
        val content = readTextFromUri(uri)
        val outDir = requireContext().getExternalFilesDir("imports")
        outDir?.mkdirs()
        val file = File(outDir, "providers_import_${System.currentTimeMillis()}.json")
        file.writeText(content)
        Log.d(TAG, "importProvidersFile: escrito ${file.absolutePath}")
        // for replace, you might want to move to a canonical file
        if (replace) {
            val target = File(requireContext().getExternalFilesDir(null), "providers_override.json")
            file.copyTo(target, overwrite = true)
            Log.d(TAG, "importProvidersFile: sobrescrito providers_override.json")
        }
    }

    private suspend fun importPatterns(uri: Uri, replace: Boolean) {
        Log.d(TAG, "importPatterns: importar desde Uri=$uri replace=$replace")
        val content = readTextFromUri(uri)
        val jo = JSONObject(content)
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val dao = db.templateDao()
        if (replace) {
            dao.deleteAllSamples()
            dao.deleteAllTemplates()
        }
        val tArr = jo.optJSONArray("templates") ?: JSONArray()
        for (i in 0 until tArr.length()) {
            val t = tArr.getJSONObject(i)
            val mappings = mutableMapOf<String, String>()
            val mjo = t.optJSONObject("mappings")
            if (mjo != null) {
                val keys = mjo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    mappings[k] = mjo.optString(k)
                }
            }
            val tpl = com.albacontrol.data.OCRTemplate(providerNif = t.optString("providerNif"), mappings = mappings)
            dao.insertTemplate(tpl)
        }
        val sArr = jo.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until sArr.length()) {
            val s = sArr.getJSONObject(i)
            val fm = mutableMapOf<String, String>()
            val fjo = s.optJSONObject("fieldMappings")
            if (fjo != null) {
                val keys = fjo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    fm[k] = fjo.optString(k)
                }
            }
            val sample = com.albacontrol.data.TemplateSample(providerNif = s.optString("providerNif"), imagePath = s.optString("imagePath"), fieldMappings = fm, createdAt = s.optLong("createdAt", System.currentTimeMillis()))
            dao.insertSample(sample)
        }
        Log.d(TAG, "importPatterns: import completado")
    }

    // --- Backup General: export all into single ZIP ---
    private suspend fun exportBackupAll() {
        Log.d(TAG, "exportBackupAll: iniciando export backup general")
        // ensure individual exports exist
        exportDrafts()
        exportProviders()
        exportPatterns()
        // exportHistory creates a zip; also call to ensure PDFs/manifest exist
        exportHistory()

        val exportsDir = requireContext().getExternalFilesDir("exports")
        exportsDir?.mkdirs()
        val allFiles = exportsDir?.listFiles() ?: arrayOf()
        val outFile = File(exportsDir, "backup_all_${System.currentTimeMillis()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
            for (f in allFiles) {
                try {
                    val entryName = f.name
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(f).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                    Log.d(TAG, "exportBackupAll: añadido ${f.absolutePath} al backup")
                } catch (_: Exception) {}
            }
        }
        Log.d(TAG, "exportBackupAll: creado ${outFile.absolutePath}")
    }

    private suspend fun importBackupAll(uri: Uri, replace: Boolean) {
        Log.d(TAG, "importBackupAll: importando backup desde Uri=$uri replace=$replace")
        // copy zip to imports and unzip into temp
        val importsDir = requireContext().getExternalFilesDir("imports")
        importsDir?.mkdirs()
        val target = File(importsDir, "backup_import_${System.currentTimeMillis()}.zip")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "importBackupAll: zip copiado a ${target.absolutePath}")

        val tmpDir = File(importsDir, "tmp_${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        // extract into tmpDir
        tmpDir.mkdirs()
        extractZipToDir(target, tmpDir)

        // Now inspect tmpDir and route files to the existing import handlers
        // If replace==true, clear targets first (do it once here, then pass false to avoid double deletion)
        if (replace) {
            val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
            // delete drafts
            val drafts = db.draftDao().getAll()
            for (d in drafts) db.draftDao().deleteById(d.id)
            // delete completed
            val comp = db.completedDao().getAll()
            for (c in comp) db.completedDao().deleteById(c.id)
            // delete templates
            val tplDao = db.templateDao()
            tplDao.deleteAllSamples(); tplDao.deleteAllTemplates()
        }

        // Pass false to individual importers since we already cleared data if replace=true
        tmpDir.listFiles()?.forEach { f ->
            try {
                val name = f.name.lowercase(Locale.ROOT)
                when {
                        name.contains("draft") && name.endsWith(".json") -> importDraftsFromFile(f, false)
                    name.contains("providers") && name.endsWith(".json") -> importProvidersFileFromFile(f, false)
                    name.contains("pattern") && name.endsWith(".json") -> importPatternsFromFile(f, false)
                    name.endsWith(".zip") && name.contains("history") -> importHistoryZipFromFile(f, false)
                    name.endsWith(".json") -> {
                        // try detect content
                        val text = f.readText()
                        Log.d(TAG, "importBackupAll: analizando ${f.absolutePath}")
                        if (text.trim().startsWith("[")) {
                            // could be drafts array
                            try { JSONArray(text); importDraftsFromFile(f, false); return@forEach } catch (_: Exception) {}
                        }
                    }
                    name.endsWith(".pdf") -> {
                        // put into history and create CompletedAlbaran
                        val historyDir = requireContext().getExternalFilesDir("history")
                        historyDir?.mkdirs()
                        val dest = File(historyDir, f.name)
                        f.copyTo(dest, overwrite = true)
                        val jo = JSONObject()
                        jo.put("pdf_path", dest.absolutePath)
                        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
                        db.completedDao().insert(com.albacontrol.data.CompletedAlbaran(providerId = null, dataJson = jo.toString(), createdAt = System.currentTimeMillis()))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // --- Restaurar App (borrar todo) ---
    private fun confirmRestoreApp() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(android.R.layout.simple_list_item_1, null)
        val tv = TextView(requireContext())
        tv.text = getString(com.albacontrol.R.string.restore_warning)
        tv.setPadding(20,20,20,20)

        val check = CheckBox(requireContext())
        check.text = getString(com.albacontrol.R.string.ok)
        check.isEnabled = false

        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        container.addView(tv)
        container.addView(check)

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.restore_confirm_title))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.restore)) { _, _ ->
                    if (check.isChecked) {
                    performRestoreApp()
                } else {
                    Toast.makeText(requireContext(), getString(com.albacontrol.R.string.restore_confirm_missing), Toast.LENGTH_SHORT).show()
                }
            }

        val dialog = builder.create()
        dialog.show()

        // force button colors
        try {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        } catch (_: Exception) {}

        // disable positive button until checkbox enabled and checked
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false

        // countdown 5 seconds then enable checkbox
        val handler = android.os.Handler()
        var seconds = 5
        val runnable = object : Runnable {
            override fun run() {
                if (seconds <= 0) {
                    check.isEnabled = true
                    positive.isEnabled = true
                    check.setOnCheckedChangeListener { _, isChecked -> positive.isEnabled = isChecked }
                } else {
                    tv.text = getString(com.albacontrol.R.string.restore_warning).replace("5", seconds.toString())
                    seconds--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(runnable)
    }

    private fun performRestoreApp() {
        lifecycleScope.launch {
            try {
                showProgress(getString(R.string.restoring_progress))
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "performRestoreApp: iniciando restauración (replace)")
                    val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
                    // delete drafts
                    val drafts = db.draftDao().getAll()
                    for (d in drafts) db.draftDao().deleteById(d.id)
                    // delete completed
                    val comp = db.completedDao().getAll()
                    for (c in comp) db.completedDao().deleteById(c.id)
                    // delete templates
                    val tplDao = db.templateDao()
                    tplDao.deleteAllSamples(); tplDao.deleteAllTemplates()

                    // clear shared preferences
                    val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()

                    // delete app files in external files dir (exports, imports, history)
                    val base = requireContext().getExternalFilesDir(null)
                    base?.listFiles()?.forEach { f ->
                        try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (e: Exception) { Log.e(TAG, "performRestoreApp: error eliminando archivo ${f.absolutePath}", e) }
                    }
                    Log.d(TAG, "performRestoreApp: restauración IO completada")
                }
                Toast.makeText(requireContext(), getString(R.string.restore_completed), Toast.LENGTH_LONG).show()
                Log.d(TAG, "performRestoreApp: restauración finalizada con éxito")
            } catch (e: Exception) {
                Log.e(TAG, "performRestoreApp: error durante restauración", e)
                Toast.makeText(requireContext(), getString(R.string.restore_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
                hideProgress()
        }
    }

    private suspend fun importHistoryZip(uri: Uri, replace: Boolean) {
        // Copy ZIP to imports folder then delegate to file-based importer
        val importsDir = requireContext().getExternalFilesDir("imports")
        importsDir?.mkdirs()
        val target = File(importsDir, "history_import_${System.currentTimeMillis()}.zip")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        importHistoryZipFromFile(target, replace)
    }

    // --- Zip helpers: extract to directory and read manifest ---
    private fun extractZipToDir(zipFile: File, destDir: File): List<File> {
        Log.d(TAG, "extractZipToDir: extrayendo ${zipFile.absolutePath} -> ${destDir.absolutePath}")
        val extracted = mutableListOf<File>()
        java.util.zip.ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.isDirectory) { entry = zis.nextEntry; continue }
                val outFile = File(destDir, entry.name)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                extracted.add(outFile)
                Log.d(TAG, "extractZipToDir: extraído ${outFile.absolutePath}")
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }

    private fun readManifestFromDir(dir: File): org.json.JSONArray? {
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return null
        return try {
            org.json.JSONArray(mf.readText())
        } catch (_: Exception) { null }
    }

    private fun showProgress(message: String) {
        try {
            activity?.runOnUiThread {
                if (progressDialog?.isShowing == true) return@runOnUiThread
                val layout = LinearLayout(requireContext())
                layout.orientation = LinearLayout.HORIZONTAL
                layout.setPadding(40, 30, 40, 30)
                val pb = ProgressBar(requireContext())
                pb.isIndeterminate = true
                val tv = TextView(requireContext())
                tv.text = message
                tv.setPadding(30, 0, 0, 0)
                layout.addView(pb)
                layout.addView(tv)
                val dialog = AlertDialog.Builder(requireContext())
                    .setView(layout)
                    .setCancelable(false)
                    .create()
                progressDialog = dialog
                dialog.show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showProgress: error mostrando diálogo de progreso", e)
        }
    }

    private fun hideProgress() {
        try {
            activity?.runOnUiThread {
                progressDialog?.dismiss()
                progressDialog = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "hideProgress: error cerrando diálogo de progreso", e)
        }
    }

    // --- File-based import helpers (avoid Uri.fromFile) ---
    private suspend fun importDraftsFromFile(file: File, replace: Boolean) {
        Log.d(TAG, "importDraftsFromFile: importando ${file.absolutePath} replace=$replace")
        val content = file.readText()
        val arr = JSONArray(content)
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        if (replace) {
            val existing = db.draftDao().getAll()
            for (d in existing) db.draftDao().deleteById(d.id)
        }
        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            val dataJson = jo.optString("dataJson", "")
            val providerId = if (jo.has("providerId") && !jo.isNull("providerId")) jo.optLong("providerId") else null
            val draft = com.albacontrol.data.Draft(providerId = providerId, dataJson = dataJson, createdAt = jo.optLong("createdAt", System.currentTimeMillis()), updatedAt = jo.optLong("updatedAt", System.currentTimeMillis()))
            db.draftDao().insert(draft)
        }
        Log.d(TAG, "importDraftsFromFile: import completado, ${arr.length()} elementos procesados")
    }

    private fun importProvidersFileFromFile(file: File, replace: Boolean) {
        Log.d(TAG, "importProvidersFileFromFile: importando ${file.absolutePath} replace=$replace")
        val content = file.readText()
        val outDir = requireContext().getExternalFilesDir("imports")
        outDir?.mkdirs()
        val target = File(outDir, "providers_import_${System.currentTimeMillis()}.json")
        target.writeText(content)
        Log.d(TAG, "importProvidersFileFromFile: escrito ${target.absolutePath}")
        if (replace) {
            val dest = File(requireContext().getExternalFilesDir(null), "providers_override.json")
            target.copyTo(dest, overwrite = true)
            Log.d(TAG, "importProvidersFileFromFile: sobrescrito providers_override.json")
        }
    }

    private suspend fun importPatternsFromFile(file: File, replace: Boolean) {
        Log.d(TAG, "importPatternsFromFile: importando ${file.absolutePath} replace=$replace")
        val content = file.readText()
        val jo = JSONObject(content)
        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        val dao = db.templateDao()
        if (replace) {
            dao.deleteAllSamples()
            dao.deleteAllTemplates()
        }
        val tArr = jo.optJSONArray("templates") ?: JSONArray()
        for (i in 0 until tArr.length()) {
            val t = tArr.getJSONObject(i)
            val mappings = mutableMapOf<String, String>()
            val mjo = t.optJSONObject("mappings")
            if (mjo != null) {
                val keys = mjo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    mappings[k] = mjo.optString(k)
                }
            }
            val tpl = com.albacontrol.data.OCRTemplate(providerNif = t.optString("providerNif"), mappings = mappings)
            dao.insertTemplate(tpl)
        }
        val sArr = jo.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until sArr.length()) {
            val s = sArr.getJSONObject(i)
            val fm = mutableMapOf<String, String>()
            val fjo = s.optJSONObject("fieldMappings")
            if (fjo != null) {
                val keys = fjo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    fm[k] = fjo.optString(k)
                }
            }
            val sample = com.albacontrol.data.TemplateSample(providerNif = s.optString("providerNif"), imagePath = s.optString("imagePath"), fieldMappings = fm, createdAt = s.optLong("createdAt", System.currentTimeMillis()))
            dao.insertSample(sample)
        }
        Log.d(TAG, "importPatternsFromFile: import completado")
    }

    private suspend fun importHistoryZipFromFile(file: File, replace: Boolean) {
        Log.d(TAG, "importHistoryZipFromFile: importando ${file.absolutePath} replace=$replace")
        // Copy the provided zip file into imports and then process similarly to importHistoryZip
        val importsDir = requireContext().getExternalFilesDir("imports")
        importsDir?.mkdirs()
        val target = File(importsDir, "history_import_${System.currentTimeMillis()}.zip")
        file.copyTo(target, overwrite = true)
        Log.d(TAG, "importHistoryZipFromFile: zip copiado a ${target.absolutePath}")

        // Unzip and extract PDFs into history folder
        val historyDir = requireContext().getExternalFilesDir("history")
        historyDir?.mkdirs()

        val manifestItems = mutableListOf<org.json.JSONObject>()
        java.util.zip.ZipInputStream(FileInputStream(target)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (entry.isDirectory) {
                    entry = zis.nextEntry
                    continue
                }
                if (name.equals("manifest.json", ignoreCase = true)) {
                    val baos = java.io.ByteArrayOutputStream()
                    zis.copyTo(baos)
                    val manifestStr = baos.toString(Charsets.UTF_8.name())
                    try {
                        val arr = org.json.JSONArray(manifestStr)
                        for (i in 0 until arr.length()) manifestItems.add(arr.getJSONObject(i))
                    } catch (_: Exception) {}
                } else if (name.toLowerCase().endsWith(".pdf")) {
                    val outFile = File(historyDir, name)
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
        if (replace) {
            val existing = db.completedDao().getAll()
            for (c in existing) db.completedDao().deleteById(c.id)
        }

        for (m in manifestItems) {
            try {
                val fileName = m.optString("file")
                val pdfFile = File(historyDir, fileName)
                if (!pdfFile.exists()) continue
                val jo = org.json.JSONObject()
                jo.put("pdf_path", pdfFile.absolutePath)
                jo.put("imported_from", target.name)
                jo.put("providerId", if (m.has("providerId")) m.optLong("providerId") else null)
                val completed = com.albacontrol.data.CompletedAlbaran(providerId = null, dataJson = jo.toString(), createdAt = System.currentTimeMillis())
                db.completedDao().insert(completed)
            } catch (_: Exception) {}
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        Log.d(TAG, "readTextFromUri: leyendo contenido desde $uri")
        return requireContext().contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() } ?: ""
    }

    private fun setupLanguageSpinner() {
        // Use flag emojis and language names for simplicity
        // languages display names and corresponding BCP-47 tags
        val langList = listOf(
            Pair("\uD83C\uDDEA\uD83C\uDDF8 Español", "es"),
            Pair("\uD83C\uDDEC\uD83C\uDDE7 English", "en"),
            Pair("\uD83C\uDDF5\uD83C\uDDF9 Português", "pt"),
            Pair("\uD83C\uDDEB\uD83C\uDDF7 Français", "fr"),
            Pair("\uD83C\uDDE9\uD83C\uDDEA Deutsch", "de"),
            Pair("\uD83C\uDDEE\uD83C\uDDF9 Italiano", "it"),
            // Cataluña: usando secuencia de bandera catalana (🏴󠁥󠁳󠁣󠁴󠁿)
            Pair("\uD83C\uDFF4\uDB40\uDC65\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F Català", "ca"),
            Pair("\uD83C\uDDF9\uD83C\uDDF7 Türkçe", "tr"),
            Pair("\uD83C\uDDF8\uD83C\uDDE6 العربية", "ar"),
            // Wolof mayoritario en Senegal: se usa bandera de Senegal
            Pair("\uD83C\uDDF8\uD83C\uDDF3 Wolof", "wo")
        )
        val languages = langList.map { it.first }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLang.adapter = adapter

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // stored value is language tag (e.g. "es")
        val currentTag = prefs.getString(KEY_LANG, "es") ?: "es"
        val pos = langList.indexOfFirst { it.second == currentTag }
        if (pos >= 0) spinnerLang.setSelection(pos)
        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tag = langList[position].second
                // Evitar bucle de recreación: ignorar la primera selección programática
                if (skipInitialLanguageSelection) {
                    skipInitialLanguageSelection = false
                    if (DEBUG_INTERACTION) Log.d(TAG, "setupLanguageSpinner: ignorada selección inicial tag=$tag")
                    return
                }
                // Si no cambia el idioma, no recrear
                val prevTag = prefs.getString(KEY_LANG, "es") ?: "es"
                if (prevTag == tag) {
                    if (DEBUG_INTERACTION) Log.d(TAG, "setupLanguageSpinner: selección igual (prev=$prevTag) sin recrear")
                    return
                }
                prefs.edit().putString(KEY_LANG, tag).apply()
                try {
                    val locales = LocaleListCompat.forLanguageTags(tag)
                    AppCompatDelegate.setApplicationLocales(locales)
                    Toast.makeText(requireContext(), "Idioma: ${langList[position].first}", Toast.LENGTH_SHORT).show()
                    // Forzar recarga: recrear actividad y navegar a Home
                    activity?.recreate()
                    try {
                        requireActivity().supportFragmentManager.beginTransaction()
                            .replace(com.albacontrol.R.id.fragment_container, com.albacontrol.ui.HomeFragment())
                            .commitAllowingStateLoss()
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying locale $tag", e)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupEmailAppSpinner() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageManager = requireContext().packageManager
        
        // Usar un Set para evitar duplicados
        val seenPackages = mutableSetOf<String>()
        val appList = mutableListOf<Pair<String, String>>()
        appList.add(Pair(getString(R.string.email_app_chooser), ""))
        
        // Método 1: Intentar con ACTION_SENDTO y mailto: (más específico)
        try {
            val mailtoIntent = Intent(Intent.ACTION_SENDTO)
            mailtoIntent.data = Uri.parse("mailto:")
            val mailtoApps = packageManager.queryIntentActivities(mailtoIntent, 0)
            
            for (resolveInfo in mailtoApps) {
                val packageName = resolveInfo.activityInfo.packageName
                if (packageName !in seenPackages) {
                    val appName = resolveInfo.loadLabel(packageManager).toString()
                    appList.add(Pair(appName, packageName))
                    seenPackages.add(packageName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying mailto apps: ${e.message}")
        }
        
        // Método 2: Si no encontró apps, usar ACTION_SEND con message/rfc822 y filtrar
        if (seenPackages.isEmpty()) {
            try {
                val sendIntent = Intent(Intent.ACTION_SEND)
                sendIntent.type = "message/rfc822"
                val sendApps = packageManager.queryIntentActivities(sendIntent, 0)
                
                // Lista de packages conocidos que NO son de email (para filtrar)
                val excludePackages = setOf(
                    "com.android.bluetooth",
                    "com.google.android.apps.nbu.files", // Files by Google (Quick Share)
                    "com.samsung.android.app.sharelive", // Quick Share Samsung
                    "com.google.android.gms", // Google Play Services
                    "android", // Sistema Android genérico
                    "com.whatsapp",
                    "com.facebook.orca",
                    "com.telegram.messenger",
                    "com.snapchat.android"
                )
                
                for (resolveInfo in sendApps) {
                    val packageName = resolveInfo.activityInfo.packageName
                    // Filtrar packages excluidos y duplicados
                    if (packageName !in seenPackages && packageName !in excludePackages) {
                        // Filtrar también por nombre de actividad que contenga palabras clave de email
                        val activityName = resolveInfo.activityInfo.name.lowercase()
                        val packageLower = packageName.lowercase()
                        val isLikelyEmail = packageLower.contains("mail") || 
                                          packageLower.contains("email") ||
                                          activityName.contains("mail") ||
                                          activityName.contains("compose")
                        
                        if (isLikelyEmail) {
                            val appName = resolveInfo.loadLabel(packageManager).toString()
                            appList.add(Pair(appName, packageName))
                            seenPackages.add(packageName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying send apps: ${e.message}")
            }
        }
        
        // Ordenar alfabéticamente (excepto la primera opción "Elegir cada vez")
        val sortedApps = appList.drop(1).sortedBy { it.first }
        val finalList = mutableListOf(appList[0]) + sortedApps
        
        Log.d(TAG, "setupEmailAppSpinner: found ${finalList.size - 1} email apps")
        for (app in finalList) {
            Log.d(TAG, "  - ${app.first} (${app.second})")
        }
        
        val displayNames = finalList.map { it.first }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEmailApp.adapter = adapter

        val savedPackage = prefs.getString(KEY_PREFERRED_EMAIL_APP, "") ?: ""
        val pos = finalList.indexOfFirst { it.second == savedPackage }
        if (pos >= 0) spinnerEmailApp.setSelection(pos)

        spinnerEmailApp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val packageName = finalList[position].second
                val appName = finalList[position].first
                prefs.edit().putString(KEY_PREFERRED_EMAIL_APP, packageName).apply()
                Log.d(TAG, "setupEmailAppSpinner: seleccionada app='$appName' package='$packageName'")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadOptions() {
        containerRecep.removeAllViews()
        containerUbic.removeAllViews()
        containerEmails.removeAllViews()

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadListIntoContainer(prefs.getString(KEY_RECEP, "[]")!!, containerRecep, KEY_RECEP)
        loadListIntoContainer(prefs.getString(KEY_UBIC, "[]")!!, containerUbic, KEY_UBIC)
        loadListIntoContainer(prefs.getString(KEY_EMAILS, "[]")!!, containerEmails, KEY_EMAILS)
    }

    private fun loadAutoDeleteOptions() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ageEnabled = prefs.getBoolean(KEY_AUTO_DELETE_AGE_ENABLED, true)
        val ageDays = prefs.getInt(KEY_AUTO_DELETE_AGE_DAYS, 365)
        val countEnabled = prefs.getBoolean(KEY_AUTO_DELETE_COUNT_ENABLED, true)
        val countMax = prefs.getInt(KEY_AUTO_DELETE_COUNT_MAX, 500)

        switchAutoDeleteAge.isChecked = ageEnabled
        etAutoDeleteDays.setText(ageDays.toString())
        switchAutoDeleteCount.isChecked = countEnabled
        etAutoDeleteMax.setText(countMax.toString())

        // listeners to persist changes
        switchAutoDeleteAge.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_DELETE_AGE_ENABLED, checked).apply()
        }
        // persist immediately on text change
        etAutoDeleteDays.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null) prefs.edit().putInt(KEY_AUTO_DELETE_AGE_DAYS, v).apply()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        switchAutoDeleteCount.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_DELETE_COUNT_ENABLED, checked).apply()
        }
        // persist immediately on text change
        etAutoDeleteMax.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null) prefs.edit().putInt(KEY_AUTO_DELETE_COUNT_MAX, v).apply()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun performAutoCleanupIfNeeded() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_AUTO_CLEANUP, 0L)
        val now = System.currentTimeMillis()
        // Limitar ejecución a una vez cada 6 horas para evitar mostrar toast siempre
        val sixHoursMs = 6L * 60L * 60L * 1000L
        if (now - lastTs < sixHoursMs) {
            Log.d(TAG, "performAutoCleanupIfNeeded: omitido (ejecutado hace menos de 6h)")
            return
        }

        val ageEnabled = prefs.getBoolean(KEY_AUTO_DELETE_AGE_ENABLED, true)
        val ageDays = prefs.getInt(KEY_AUTO_DELETE_AGE_DAYS, 365)
        val countEnabled = prefs.getBoolean(KEY_AUTO_DELETE_COUNT_ENABLED, true)
        val countMax = prefs.getInt(KEY_AUTO_DELETE_COUNT_MAX, 500)

        lifecycleScope.launch {
            var deletedCount = 0
            withContext(Dispatchers.IO) {
                try {
                    val db = com.albacontrol.data.AppDatabase.getInstance(requireContext())
                    val list = db.completedDao().getAll().toMutableList()
                    if (list.isEmpty()) return@withContext

                    // Por antigüedad
                    if (ageEnabled) {
                        val cutoff = System.currentTimeMillis() - ageDays * 24L * 60L * 60L * 1000L
                        val toDelete = list.filter { it.createdAt < cutoff }
                        for (c in toDelete) {
                            try {
                                try {
                                    val jo = JSONObject(c.dataJson)
                                    val pdf = jo.optString("pdf_path", null)
                                    if (!pdf.isNullOrEmpty()) File(pdf).takeIf { it.exists() }?.delete()
                                } catch (_: Exception) {}
                                db.completedDao().deleteById(c.id)
                                deletedCount++
                                Log.d(TAG, "performAutoCleanup: eliminado id=${c.id} (antigüedad)")
                            } catch (e: Exception) { Log.e(TAG, "performAutoCleanup: error borrando id=${c.id}", e) }
                        }
                        val refreshed = db.completedDao().getAll().toMutableList()
                        list.clear(); list.addAll(refreshed)
                    }

                    // Por exceso de cantidad
                    if (countEnabled && list.size > countMax) {
                        list.sortByDescending { it.createdAt }
                        val toRemove = list.drop(countMax)
                        for (c in toRemove) {
                            try {
                                try {
                                    val jo = JSONObject(c.dataJson)
                                    val pdf = jo.optString("pdf_path", null)
                                    if (!pdf.isNullOrEmpty()) File(pdf).takeIf { it.exists() }?.delete()
                                } catch (_: Exception) {}
                                db.completedDao().deleteById(c.id)
                                deletedCount++
                                Log.d(TAG, "performAutoCleanup: eliminado id=${c.id} (exceso)")
                            } catch (e: Exception) { Log.e(TAG, "performAutoCleanup: error borrando id=${c.id}", e) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "performAutoCleanup: error durante limpieza automática", e)
                }
            }
            // Mostrar toast solo si realmente se eliminó algo
            if (deletedCount > 0) {
                prefs.edit().putLong(KEY_LAST_AUTO_CLEANUP, now).apply()
                activity?.runOnUiThread { Toast.makeText(requireContext(), getString(R.string.cleanup_completed_deleted, deletedCount), Toast.LENGTH_SHORT).show() }
            } else {
                prefs.edit().putLong(KEY_LAST_AUTO_CLEANUP, now).apply()
                Log.d(TAG, "performAutoCleanupIfNeeded: no se eliminaron elementos, no se muestra toast")
            }
        }
    }

    private fun loadListIntoContainer(jsonArrayStr: String, container: LinearLayout, key: String) {
        try {
            val arr = JSONArray(jsonArrayStr)
            for (i in 0 until arr.length()) {
                val text = arr.optString(i)
                val item = LayoutInflater.from(requireContext()).inflate(R.layout.option_item, container, false)
                val tv = item.findViewById<TextView>(R.id.tvOptionText)
                val btnEdit = item.findViewById<ImageButton>(R.id.btnEditOption)
                val btnDelete = item.findViewById<ImageButton>(R.id.btnDeleteOption)
                tv.text = text

                btnEdit.setOnClickListener {
                    addOptionDialog(key, getString(R.string.edit), initial = text)
                }

                btnDelete.setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.delete))
                        .setMessage(getString(R.string.confirm_delete, text))
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            removeOption(key, text)
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show().also { dlg ->
                            try {
                                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                                dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                            } catch (_: Exception) {}
                        }
                }

                container.addView(item)
            }
        } catch (e: Exception) {
        }
    }

    private fun addOptionDialog(key: String, title: String, initial: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT) {
        val et = EditText(requireContext())
        et.inputType = inputType
        et.setText(initial)
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(et)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty()) {
                    addOption(key, text)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.empty_text_not_added), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)

        val dlg = builder.show()
        try {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        } catch (_: Exception) {}
    }

    private fun addOption(key: String, value: String) {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(key, "[]"))
        // avoid duplicates
        for (i in 0 until arr.length()) if (arr.optString(i) == value) {
            Toast.makeText(requireContext(), getString(R.string.already_exists, value), Toast.LENGTH_SHORT).show()
            return
        }
        arr.put(value)
        prefs.edit().putString(key, arr.toString()).apply()
        loadOptions()
        Toast.makeText(requireContext(), getString(R.string.added, value), Toast.LENGTH_SHORT).show()
    }

    private fun removeOption(key: String, value: String) {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(key, "[]"))
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v != value) newArr.put(v)
        }
        prefs.edit().putString(key, newArr.toString()).apply()
        loadOptions()
    }

    private fun setupAppInfoSection() {
        try {
            val headerAppInfo = view?.findViewById<View>(R.id.headerAppInfo)
            val appInfoContent = view?.findViewById<View>(R.id.appInfoContent)
            val arrowAppInfo = view?.findViewById<TextView>(R.id.arrowAppInfo)
            val btnTechnicalInfo = view?.findViewById<Button>(R.id.btnTechnicalInfo)
            val btnHelpUsage = view?.findViewById<Button>(R.id.btnHelpUsage)
            val btnHelpConfiguration = view?.findViewById<Button>(R.id.btnHelpConfiguration)
            val btnContactSuggestions = view?.findViewById<Button>(R.id.btnContactSuggestions)

            // Siempre empieza colapsado
            var collapsed = true
            appInfoContent?.visibility = View.GONE
            arrowAppInfo?.rotation = -90f

            // Toggle collapse/expand
            headerAppInfo?.setOnClickListener {
                collapsed = !collapsed
                appInfoContent?.visibility = if (collapsed) View.GONE else View.VISIBLE
                arrowAppInfo?.animate()?.rotation(if (collapsed) -90f else 0f)?.setDuration(200)?.start()
            }

            // Información técnica de la app
            btnTechnicalInfo?.setOnClickListener {
                showTechnicalInfoDialog()
            }

            // Ayuda modo de uso
            btnHelpUsage?.setOnClickListener {
                showHelpUsageDialog()
            }

            // Ayuda configuración
            btnHelpConfiguration?.setOnClickListener {
                showHelpConfigurationDialog()
            }

            // Contacto / Sugerencias
            btnContactSuggestions?.setOnClickListener {
                showContactSuggestionsDialog()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupAppInfoSection: error", e)
        }
    }

    private fun showDetailedHelpDialog() {
        val message = """
            📸 <b>Nuevo Albarán</b>
            
            1. Pulsa el botón grande "+" en el menú principal
            2. Sube una foto o archivo del albarán
            3. La app procesará la imagen y rellenará automáticamente los campos
            
            ✏️ <b>Corrige los datos</b>
            
            • Revisa todos los campos extraídos
            • Corrige cualquier error que encuentres
            • Añade productos si faltan
            • Marca incidencias si las hay
            
            Esto es muy importante: cada corrección ayuda a la app a aprender del proveedor.
            
            🎯 <b>Siguientes albaranes</b>
            
            Los próximos documentos del mismo proveedor se reconocerán mucho mejor automáticamente gracias a tu corrección.
            
            💾 <b>Guardar y Finalizar</b>
            
            • <b>Guardar Borrador</b>: Guarda el albarán para completarlo más tarde
            • <b>Finalizar</b>: Genera el PDF y lo envía por email
            • <b>Cancelar</b>: Descarta los cambios
            
            📋 <b>Otras secciones</b>
            
            • <b>Borradores</b>: Albaranes guardados sin finalizar
            • <b>Historial</b>: Albaranes finalizados y enviados
            • <b>Proveedores/Productos</b>: Base de datos de proveedores conocidos
            • <b>Opciones</b>: Configuración de la app
        """.trimIndent()

        val formattedMessage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(message)
        }

        val messageView = TextView(requireContext()).apply {
            text = formattedMessage
            setPadding(60, 40, 60, 20)
            textSize = 14f
            setLineSpacing(6f, 1f)
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(messageView)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.detailed_help_title))
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showTechnicalInfoDialog() {
        val message = getString(R.string.coming_soon)
        
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.technical_specs_title))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHelpUsageDialog() {
        val message = getString(R.string.coming_soon)
        
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.help_usage_mode))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHelpConfigurationDialog() {
        val message = getString(R.string.coming_soon)
        
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.help_configuration))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showContactSuggestionsDialog() {
        val message = getString(R.string.coming_soon)
        
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.contact_info_title))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openContactEmail() {
        try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.contact_email)))
                putExtra(Intent.EXTRA_SUBJECT, "AlbaControl - Ayuda/Sugerencias")
            }
            startActivity(emailIntent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No se pudo abrir el cliente de email", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "openContactEmail: error", e)
        }
    }

}

