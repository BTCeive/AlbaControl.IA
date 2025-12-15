# Capturas de Pantalla para Ayuda

Esta carpeta contiene las capturas de pantalla que se mostrarán en las secciones de ayuda de la app.

## Ubicación de las imágenes:

Las capturas de pantalla deben colocarse en esta carpeta: `app/src/main/res/drawable-nodpi/`

## Nomenclatura recomendada:

### Para "Ayuda modo de uso":
- `help_usage_01.png` - Pantalla principal
- `help_usage_02.png` - Nuevo albarán
- `help_usage_03.png` - Captura de foto
- `help_usage_04.png` - Formulario
- `help_usage_05.png` - Finalizar
- etc.

### Para "Ayuda configuración":
- `help_config_01.png` - Menú opciones
- `help_config_02.png` - Idiomas
- `help_config_03.png` - Sistema OCR
- `help_config_04.png` - Backup
- `help_config_05.png` - Borrado automático
- etc.

## Formato recomendado:

- **Formato**: PNG (con transparencia si es necesario)
- **Resolución**: Capturas de pantalla reales del dispositivo
- **Tamaño**: Android optimizará automáticamente para diferentes densidades
- **Carpeta**: `drawable-nodpi` (sin escalado automático, mantiene tamaño original)

## Uso en el código:

```kotlin
// Para mostrar una imagen en un diálogo o actividad:
val imageView = ImageView(context)
imageView.setImageResource(R.drawable.help_usage_01)
```

## Notas:

- Las imágenes en `drawable-nodpi` no se escalarán automáticamente
- Esto es ideal para capturas de pantalla que deben mantener su tamaño original
- Si necesitas diferentes tamaños para diferentes densidades, usa:
  - `drawable-mdpi/`
  - `drawable-hdpi/`
  - `drawable-xhdpi/`
  - `drawable-xxhdpi/`
  - `drawable-xxxhdpi/`
