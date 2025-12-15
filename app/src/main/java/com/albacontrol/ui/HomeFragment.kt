package com.albacontrol.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.albacontrol.R
import com.google.android.material.floatingactionbutton.FloatingActionButton

class HomeFragment : Fragment() {
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

        // Botón de ayuda
        view.findViewById<FloatingActionButton>(R.id.fabHelp).setOnClickListener {
            showHelpDialog()
        }

        return view
    }

    private fun showHelpDialog() {
        val message = getString(R.string.help_message)
        val formattedMessage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(message)
        }

        val messageView = TextView(requireContext()).apply {
            text = formattedMessage
            setPadding(60, 40, 60, 20)
            textSize = 16f
            setLineSpacing(8f, 1f)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.help_title))
            .setView(messageView)
            .setPositiveButton("OK", null)
            .setNeutralButton(getString(R.string.help_more_info)) { _, _ ->
                // TODO: Abrir página web con más información cuando esté lista
                android.widget.Toast.makeText(requireContext(), "Próximamente: Guía completa", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // Removed temporary locale debug logging.
}
