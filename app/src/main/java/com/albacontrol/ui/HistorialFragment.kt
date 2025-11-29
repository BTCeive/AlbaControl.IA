package com.albacontrol.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albacontrol.R
import com.albacontrol.data.AppDatabase
import com.albacontrol.data.CompletedAlbaran
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistorialFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_historial, container, false)
        recycler = view.findViewById(R.id.recyclerHistory)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        loadHistory()

        return view
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val items = withContext(Dispatchers.IO) { db.completedDao().getAll() }
            recycler.adapter = HistoryAdapter(items.toMutableList())
        }
    }

    inner class HistoryAdapter(private val items: MutableList<CompletedAlbaran>) : RecyclerView.Adapter<HistoryViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.history_item, parent, false)
            return HistoryViewHolder(v)
        }

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val item = items[position]
            var provider = ""
            var pdfPath: String? = null
            try {
                val jo = JSONObject(item.dataJson)
                provider = jo.optString("proveedor", "")
                pdfPath = jo.optString("pdf_path", null)
            } catch (_: Exception) {}

            holder.tvDate.text = sdf.format(Date(item.createdAt))
            holder.tvProvider.text = provider

            holder.btnView.setOnClickListener {
                if (pdfPath.isNullOrEmpty()) {
                    // No hay PDF guardado
                    android.widget.Toast.makeText(requireContext(), "No se ha generado PDF para este albarán", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    openPdf(File(pdfPath))
                }
            }

            holder.btnSend.setOnClickListener {
                if (pdfPath.isNullOrEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "No hay PDF para enviar", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    shareFile(File(pdfPath))
                }
            }

            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar albarán")
                    .setMessage("¿Quieres eliminar este albarán del historial?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        lifecycleScope.launch {
                            val db = AppDatabase.getInstance(requireContext())
                            withContext(Dispatchers.IO) { db.completedDao().deleteById(item.id) }
                            items.removeAt(position)
                            notifyItemRemoved(position)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class HistoryViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate = v.findViewById<android.widget.TextView>(R.id.tvHistoryDate)
        val tvProvider = v.findViewById<android.widget.TextView>(R.id.tvHistoryProvider)
        val btnView = v.findViewById<ImageButton>(R.id.btnViewPdf)
        val btnSend = v.findViewById<ImageButton>(R.id.btnSendPdf)
        val btnDelete = v.findViewById<ImageButton>(R.id.btnDeleteHistory)
    }

    private fun openPdf(file: File) {
        if (!file.exists()) {
            android.widget.Toast.makeText(requireContext(), "Archivo no encontrado: ${file.path}", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val uri: Uri = FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/pdf")
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(Intent.createChooser(intent, "Abrir PDF"))
    }

    private fun shareFile(file: File) {
        if (!file.exists()) {
            android.widget.Toast.makeText(requireContext(), "Archivo no encontrado: ${file.path}", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".fileprovider", file)
        val share = Intent(Intent.ACTION_SEND)
        share.type = "application/pdf"
        share.putExtra(Intent.EXTRA_STREAM, uri)
        share.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        startActivity(Intent.createChooser(share, "Enviar albarán"))
    }
}
