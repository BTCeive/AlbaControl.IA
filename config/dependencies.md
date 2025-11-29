
Dependencias y configuración

Aquí se documentarán las dependencias del proyecto (Gradle, libs de UI, persistencia, etc.).

Ejemplo inicial:
- Kotlin + AndroidX
- Room para persistencia local
- Retrofit para sincronización (opcional)
- ML Kit Text Recognition (on-device) para OCR

Gradle (módulo `app`) - ejemplo de dependencias a añadir:

dependencies {
	// Room
	implementation "androidx.room:room-runtime:2.5.0"
	kapt "androidx.room:room-compiler:2.5.0"

	// ML Kit Text Recognition (on-device)
	implementation "com.google.mlkit:text-recognition:16.1.0"

	// Otros: AndroidX, AppCompat, etc.
}

Nota: revisa las versiones al sincronizar en Android Studio.
