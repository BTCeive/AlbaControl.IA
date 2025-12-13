Persistencia de correcciones - Instrucciones rápidas

Validaciones aplicadas por la API
- debug_id es obligatorio.
- template_id o nif es obligatorio.
- nif se normaliza (quita espacios y símbolos, pasa a mayúsculas).
- fecha_albaran acepta ISO YYYY-MM-DD o ISO completo; también dd/mm/YYYY como fallback.
- productos debe ser una lista; cada producto requiere descripcion; unidades y precio_unitario deben ser numéricos si se proporcionan.

Uso
- Ejecutar la API: crear virtualenv, instalar Flask y ejecutar `scripts/corrections_api.py`
- Enviar corrección (POST JSON) a `/api/v1/corrections`
- Los JSON válidos se guardan en `artifacts_device/persisted_corrections/`

Formato de corrección (campos típicos)
- `debug_id`, `user_id`, `timestamp`, `template_id` (o `nif`), `proveedor`, `nif`
- `numero_albaran`, `fecha_albaran`, `productos` (lista de objetos con `descripcion`, `unidades`, `precio_unitario`, `importe_linea`)
- `field_id`, `text_corrected`, `confidence_before`, `bbox` (relativo 0..1), `crop_path`, `notes`

Buenas prácticas
- Guardar bbox relativo a la plantilla (x_min,y_min,x_max,y_max) en valores 0..1
- Solo persistir si el usuario modificó algún campo
- Mantener una plantilla maestra por NIF/proveedor y versionarla al actualizar
