package com.albacontrol.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albacontrol.R
import com.albacontrol.data.AppDatabase
import com.albacontrol.data.Draft
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class BorradoresFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_borradores, container, false)
        recycler = view.findViewById(R.id.recyclerDrafts)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        loadDrafts()

        return view
    }

    private fun loadDrafts() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val drafts = withContext(Dispatchers.IO) { db.draftDao().getAll() }
            recycler.adapter = DraftsAdapter(drafts.toMutableList())
        }
    }

    inner class DraftsAdapter(private val items: MutableList<Draft>) : RecyclerView.Adapter<DraftViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.draft_item, parent, false)
            return DraftViewHolder(v)
        }

        override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
            val draft = items[position]
            // parse dataJson to get provider and possibly other fields
            var provider = ""
            try {
                val jo = JSONObject(draft.dataJson)
                provider = jo.optString("proveedor", "")
            } catch (_: Exception) {}

            holder.tvDate.text = sdf.format(Date(draft.updatedAt))
            holder.tvProvider.text = provider

            holder.btnEdit.setOnClickListener {
                // Abrir NuevoAlbaranFragment con el draft JSON
                val frag = NuevoAlbaranFragment()
                val b = Bundle()
                b.putString("draft_json", draft.dataJson)
                b.putLong("draft_id", draft.id)
                frag.arguments = b
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack(null)
                    .commit()
            }

            holder.btnDelete.setOnClickListener {
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar borrador")
                    .setMessage("¿Quieres eliminar este borrador?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        lifecycleScope.launch {
                            val db = AppDatabase.getInstance(requireContext())
                            withContext(Dispatchers.IO) { db.draftDao().deleteById(draft.id) }
                            items.removeAt(position)
                            notifyItemRemoved(position)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
                }
                dialog.show()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class DraftViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate = v.findViewById<android.widget.TextView>(R.id.tvDraftDate)
        val tvProvider = v.findViewById<android.widget.TextView>(R.id.tvDraftProvider)
        val btnEdit = v.findViewById<ImageButton>(R.id.btnEditDraft)
        val btnDelete = v.findViewById<ImageButton>(R.id.btnDeleteDraft)
    }
}
