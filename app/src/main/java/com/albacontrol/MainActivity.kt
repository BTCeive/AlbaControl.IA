package com.albacontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.albacontrol.ui.HomeFragment
import com.albacontrol.ui.NuevoAlbaranFragment
import androidx.fragment.app.Fragment
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aplicar idioma guardado en SharedPreferences antes de inflar vistas
        try {
            val prefs = getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
            val tag = prefs.getString("language", null)
            if (!tag.isNullOrBlank()) {
                val locales = androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            }
        } catch (_: Exception) {}
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }

    override fun onBackPressed() {
        val current: Fragment? = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current != null && current is NuevoAlbaranFragment) {
            // save silently as draft and go to Home
            try { current.saveDraftSilent() } catch (_: Exception) {}
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, HomeFragment()).commit()
            return
        }

        if (current != null && current !is HomeFragment) {
            // always go back to home
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, HomeFragment()).commit()
            return
        }

        // if already on Home, follow default (exit)
        super.onBackPressed()
    }
}
