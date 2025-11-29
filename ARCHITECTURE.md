# Arquitectura propuesta — OCR y aprendizaje local

Decisión inicial: desarrollar en Android nativo (Kotlin). Motivos:
- Integración directa y estable con ML Kit (Text Recognition) y APIs Android.
- Control total sobre Room (persistencia local) y manejo de ficheros/keystore para Play Store.

Componentes clave:
- OCR: Google ML Kit Text Recognition (on-device) para extraer texto de fotografías localmente.
- Almacenamiento local: Room (SQLite) para guardar borradores, albaranes finalizados, proveedores, productos y plantillas OCR.
- Aprendizaje/plantillas por proveedor: cuando el usuario corrige campos extraídos, guardaremos "muestras" que asocien regiones del documento (bounding boxes) a nombres de campo para ese `providerNIF`. Con suficientes muestras, construiremos una plantilla por proveedor y aplicaremos heurísticas (coincidencia por posición y similitud de texto) para mapear campos automáticamente.

Formato de plantilla:
- `OCRTemplate` por proveedor: lista de mappings { fieldName, normalizedBBox }.
- `TemplateSample`: muestras con `imagePath` y `fieldMappings` (map fieldName -> bbox + recognizedText).

Flujo básico:
1. Usuario sube foto del albarán.
2. ML Kit local extrae bloques de texto con bounding boxes.
3. Sistema intenta aplicar plantilla del proveedor (si existe) para prellenar el formulario.
4. Usuario corrige/ajusta campos: al guardar, se crea una `TemplateSample` asociada al `providerNIF`.
5. Un proceso local de generación de plantilla (simple, heurístico) actualiza/crea la `OCRTemplate` para ese proveedor.

Notas sobre privacidad y Play Store:
- Toda la extracción y aprendizaje se hace localmente en el dispositivo.
- Para publicar en Play Store, añadiremos permisos de cámara y almacenamiento en el `AndroidManifest` y documentaremos el uso de datos.

Próximos pasos (implementación):
- Crear entidades Room y converters.
- Integrar ML Kit text recognition en la pantalla de captura.
- Implementar almacenamiento de muestras y generación de plantillas heurística.
