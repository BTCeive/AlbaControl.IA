package com.albacontrol.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.albacontrol.R
import com.albacontrol.data.AppDatabase
import com.albacontrol.data.CompletedAlbaran
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class ProviderEntry(val name: String, val products: MutableList<String>)

class ProveedoresProductosFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: ImageButton
    private var providerList: MutableList<ProviderEntry> = mutableListOf()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_proveedores_productos, container, false)
        recycler = view.findViewById(R.id.recyclerProviders)
        etSearch = view.findViewById(R.id.etSearch)
        btnSearch = view.findViewById(R.id.btnSearch)

        recycler.layoutManager = LinearLayoutManager(requireContext())

        btnSearch.setOnClickListener { applySearch(etSearch.text.toString()) }

        // Búsqueda reactiva: TextWatcher con debounce
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable { applySearch(s?.toString() ?: "") }
                searchHandler.postDelayed(searchRunnable!!, 300)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        loadProviders()

        return view
    }

    private fun loadProviders() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val completed = withContext(Dispatchers.IO) { db.completedDao().getAll() }

            val map = LinkedHashMap<String, MutableList<String>>()
            for (c in completed) {
                try {
                    val jo = JSONObject(c.dataJson)
                    val provider = jo.optString("proveedor", "")
                    if (provider.isBlank()) continue
                    val productsArr = jo.optJSONArray("products") ?: JSONArray()
                    val list = map.getOrPut(provider) { mutableListOf() }
                    for (i in 0 until productsArr.length()) {
                        val p = productsArr.getJSONObject(i)
                        val desc = p.optString("descripcion", "").trim()
                        if (desc.isNotBlank() && !list.contains(desc)) list.add(desc)
                    }
                } catch (_: Exception) {
                }
            }

            providerList = map.map { ProviderEntry(it.key, it.value) }.toMutableList()
            recycler.adapter = ProviderAdapter(providerList)
        }
    }

    private fun applySearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            // reset
            recycler.adapter = ProviderAdapter(providerList)
            return
        }

        val lower = q.lowercase(Locale.getDefault())
        val filtered = mutableListOf<ProviderEntry>()

        for (p in providerList) {
            val nameLower = p.name.lowercase(Locale.getDefault())
            var matchedProvider = false
            val matchingProducts = mutableListOf<String>()

            if (nameLower.contains(lower)) {
                matchedProvider = true
                // keep all products
                matchingProducts.addAll(p.products)
            } else {
                for (prod in p.products) {
                    if (prod.lowercase(Locale.getDefault()).contains(lower)) {
                        matchingProducts.add(prod)
                    }
                }
            }

            if (matchedProvider || matchingProducts.isNotEmpty()) {
                filtered.add(ProviderEntry(p.name, matchingProducts))
            }
        }

        recycler.adapter = ProviderAdapter(filtered, highlight = q)
    }

    inner class ProviderAdapter(private val items: List<ProviderEntry>, private val highlight: String? = null) : RecyclerView.Adapter<ProviderAdapter.ProviderVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProviderVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.provider_item, parent, false)
            return ProviderVH(v)
        }

        override fun onBindViewHolder(holder: ProviderVH, position: Int) {
            val entry = items[position]
            // highlight provider name if needed
            if (!highlight.isNullOrEmpty() && entry.name.lowercase(Locale.getDefault()).contains(highlight.lowercase(Locale.getDefault()))) {
                holder.tvName.text = highlightText(entry.name, highlight)
            } else {
                holder.tvName.text = entry.name
            }

            holder.tvSub.text = getString(R.string.products_count, entry.products.size)

            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.delete_provider_title))
                    .setMessage(getString(R.string.delete_provider_message))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        lifecycleScope.launch {
                            val db = AppDatabase.getInstance(requireContext())
                            withContext(Dispatchers.IO) {
                                val all = db.completedDao().getAll()
                                for (c in all) {
                                    try {
                                        val jo = JSONObject(c.dataJson)
                                        val prov = jo.optString("proveedor", "")
                                        if (prov == entry.name) {
                                            db.completedDao().deleteById(c.id)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            loadProviders()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }

            // toggle products list
            holder.itemView.setOnClickListener {
                holder.productsContainer.visibility = if (holder.productsContainer.visibility == View.GONE) View.VISIBLE else View.GONE
            }

            // populate products
            holder.productsContainer.removeAllViews()
            for (prod in entry.products) {
                val line = LayoutInflater.from(requireContext()).inflate(R.layout.provider_product_line, holder.productsContainer, false)
                val tv = line.findViewById<TextView>(R.id.tvProductDesc)
                if (!highlight.isNullOrEmpty() && prod.lowercase(Locale.getDefault()).contains(highlight.lowercase(Locale.getDefault()))) {
                    tv.text = highlightText(prod, highlight)
                } else {
                    tv.text = prod
                }

                val btnDel = line.findViewById<ImageButton>(R.id.btnDeleteProductLine)
                    btnDel.setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.delete_product_title))
                        .setMessage(getString(R.string.delete_product_message))
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            lifecycleScope.launch {
                                val db = AppDatabase.getInstance(requireContext())
                                withContext(Dispatchers.IO) {
                                    val all = db.completedDao().getAll()
                                    for (c in all) {
                                        try {
                                            val jo = JSONObject(c.dataJson)
                                            val prov = jo.optString("proveedor", "")
                                            if (prov != entry.name) continue
                                            val productsArr = jo.optJSONArray("products") ?: continue
                                            val newArr = JSONArray()
                                            var changed = false
                                            for (i in 0 until productsArr.length()) {
                                                val p = productsArr.getJSONObject(i)
                                                val desc = p.optString("descripcion", "").trim()
                                                if (desc == prod) {
                                                    changed = true
                                                    continue
                                                }
                                                newArr.put(p)
                                            }
                                            if (changed) {
                                                jo.put("products", newArr)
                                                val updated = CompletedAlbaran(id = c.id, providerId = c.providerId, dataJson = jo.toString(), createdAt = c.createdAt)
                                                db.completedDao().insert(updated)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                                loadProviders()
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }

                holder.productsContainer.addView(line)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ProviderVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvProviderName)
            val tvSub: TextView = v.findViewById(R.id.tvProviderSub)
            val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteProvider)
            val productsContainer: LinearLayout = v.findViewById(R.id.productsContainer)
        }
    }

    private fun highlightText(text: String, query: String): CharSequence {
        val spannable = SpannableString(text)
        val lowerText = text.lowercase(Locale.getDefault())
        val lowerQ = query.lowercase(Locale.getDefault())
        var start = lowerText.indexOf(lowerQ)
        val color = ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light)
        while (start >= 0) {
            val end = start + lowerQ.length
            spannable.setSpan(BackgroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = lowerText.indexOf(lowerQ, end)
        }
        return spannable
    }
}
