package com.albacontrol.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.albacontrol.R

class HomeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        view.findViewById<Button>(R.id.btnNuevoAlbaran).setOnClickListener {
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
}
