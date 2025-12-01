package com.albacontrol.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.albacontrol.R

class HomeFragment : Fragment() {
    private val TAG = "HomeFragment"
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        view.findViewById<FrameLayout>(R.id.cardNuevoAlbaran).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NuevoAlbaranFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<Button>(R.id.btnBorradores).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, BorradoresFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<Button>(R.id.btnHistorial).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HistorialFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<Button>(R.id.btnProveedores).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ProveedoresProductosFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<Button>(R.id.btnOpciones).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, OpcionesFragment())
                .addToBackStack(null)
                .commit()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        try {
            val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            android.util.Log.d(TAG, "localeTags=" + locales.toLanguageTags())
            val ctx = requireContext()
            val sNew = ctx.getString(com.albacontrol.R.string.home_new_albaran)
            val sBor = ctx.getString(com.albacontrol.R.string.home_borradores)
            val sHist = ctx.getString(com.albacontrol.R.string.home_historial)
            val sProv = ctx.getString(com.albacontrol.R.string.home_proveedores)
            val sOpt = ctx.getString(com.albacontrol.R.string.home_opciones)
            android.util.Log.d(TAG, "home_new_albaran=" + sNew)
            android.util.Log.d(TAG, "home_borradores=" + sBor)
            android.util.Log.d(TAG, "home_historial=" + sHist)
            android.util.Log.d(TAG, "home_proveedores=" + sProv)
            android.util.Log.d(TAG, "home_opciones=" + sOpt)
        } catch (_: Exception) {}
    }
}
