package com.albacontrol

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.albacontrol.ui.HomeFragment
import com.albacontrol.ui.NuevoAlbaranFragment
import androidx.fragment.app.Fragment
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Aplicar idioma guardado lo antes posible
        try {
            val prefs = getSharedPreferences("alba_prefs", android.content.Context.MODE_PRIVATE)
            val tag = prefs.getString("language", null)
            if (!tag.isNullOrBlank()) {
                val locales = androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            }
        } catch (_: Exception) {}
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }

        // Manejar el botón atrás con el nuevo sistema
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current: Fragment? = supportFragmentManager.findFragmentById(R.id.fragment_container)
                
                if (current != null && current is NuevoAlbaranFragment) {
                    // Guardar silenciosamente como borrador y volver al Home
                    try { current.saveDraftSilent() } catch (_: Exception) {}
                    supportFragmentManager.popBackStack()
                    if (supportFragmentManager.backStackEntryCount == 0) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, HomeFragment())
                            .commit()
                    }
                    return
                }

                // Si hay fragmentos en el back stack, volver atrás
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }

                // Si estamos en Home y no hay back stack, salir de la app
                if (current is HomeFragment) {
                    finish()
                    return
                }

                // En cualquier otro caso, volver al Home
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, HomeFragment())
                    .commit()
            }
        })
    }
}
