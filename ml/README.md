ML / OCR — plan y ubicación

Carpeta `ml/` contiene documentación y utilidades para el procesamiento de imágenes y generación de plantillas.

Propuesta inicial:
- Usar ML Kit Text Recognition on-device.
- Extraer bloques de texto con bounding boxes y normalizar coordenadas respecto al tamaño de imagen.
- Almacenar `TemplateSample` por proveedor en la base de datos y en `storage/templates/` (si necesita guardar imágenes o artefactos).
- Algoritmo de generación de plantilla (fase 1): heurístico — agrupar campos por proximidad a posiciones esperadas y elegir bounding boxes más repetidas.

Futuros avances:
- Entrenamiento de un clasificador local (p. ej. modelo ligero TensorFlow Lite) que, dado características de layout y texto, prediga el fieldName para cada bbox.
