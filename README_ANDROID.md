# Pruebas en dispositivo Android (AlbaControl)

Este documento explica cómo usar `scripts/android_capture_and_test.sh` para compilar, instalar la app en un dispositivo Android, capturar `logcat` y verificar el envío de correcciones al backend.

## Requisitos
- `adb` (Android platform-tools) disponible en el `PATH`.
- Dispositivo Android con **Depuración USB** activada y la clave RSA aceptada.
- Proyecto Android con `gradlew` funcional en la raíz del repo.
- Backend `corrections-api` en funcionamiento en `http://127.0.0.1:5001` (cabecera `X-API-Key`, por defecto `dev-key`).

## Ubicaciones importantes
- Script: `scripts/android_capture_and_test.sh`
- Logs generados: `artifacts_device/android_logs/run_YYYYMMDD_HHMMSS.log`
- Correcciones persistidas: `artifacts_device/persisted_corrections/`
- Learning queue / manifiesto: `artifacts_device/learning_queue_from_corrections/` y `artifacts_device/learning_manifest.csv`

## Uso rápido

Desde la raíz del repo ejecuta:

```bash
# ejecución normal
./scripts/android_capture_and_test.sh

# si tu package es distinto, pásalo así:
APP_ID=com.miempresa.albacontrol ./scripts/android_capture_and_test.sh
```

Qué hace el script:
- Ejecuta `./gradlew assembleDebug` y comprueba que existe `app-debug.apk`.
- Instala el APK en el dispositivo (`adb install -r`).
- Inicia `adb logcat` en background guardando todo en `artifacts_device/android_logs/...`.
- Muestra un filtro útil en consola (errores y trazas relacionadas con OCR/OpenCV/Tesseract).
- Lanza la app con `adb shell monkey` y te guía para hacer pruebas manuales.
- Tras pulsar ENTER detiene la captura y muestra un extracto de logs.


## Verificar que el backend recibió correcciones

Mientras realizas la prueba en el móvil, revisa en otra terminal:

```bash
# lista las correcciones recibidas
curl -sS -H "X-API-Key: dev-key" http://127.0.0.1:5001/api/v1/corrections | jq .

# comprobar archivos persistidos en disco
ls -lah artifacts_device/persisted_corrections/
```

Si la app envía correcciones correctamente verás nuevos JSONs en `persisted_corrections`.


## Regenerar paquete de aprendizaje (opcional)

Tras acumular correcciones, puedes importar y generar el paquete de entrenamiento:

```bash
python3 scripts/import_corrections.py
python3 scripts/generate_learning_package.py
```

Esto crea `artifacts_device/learning_queue_from_corrections/`, un `learning_manifest.csv` y, si lo deseas, un `learning_package_*.tgz`.


## Troubleshooting rápido

- `adb devices` lista vacío: verifica cable, permisos, y que el dispositivo muestra la notificación para aceptar la clave RSA.
- `./gradlew assembleDebug` falla: revisa la salida de Gradle; ejecuta `./gradlew assembleDebug --stacktrace` para más detalle.
- `adb install` falla por firma: ejecuta `adb uninstall <APP_ID>` y vuelve a intentar.
- No llegan POSTs al backend: asegúrate de que `corrections-api` está ejecutándose localmente:

```bash
# modo desarrollo (no systemd):
python3 scripts/corrections_api.py

# o si usas systemd (si existe):
sudo systemctl status corrections-api.service
```

- Si necesitas sólo capturar logs sin compilar ni instalar, puedes ejecutar manualmente:

```bash
mkdir -p artifacts_device/android_logs
adb logcat -v time > artifacts_device/android_logs/run_manual_$(date +%Y%m%d_%H%M%S).log
```


## Notas de seguridad y buenas prácticas

- La clave `X-API-Key` por defecto es `dev-key` y **no** es segura para producción. Cámbiala en `CORRECTIONS_API_KEY` y en el cliente Android antes de desplegar.
- Los logs pueden contener datos sensibles (NIFs, nombres, números). Trata los artefactos en `artifacts_device` según la política de privacidad de tu organización.


---

Si quieres, añado esta sección resumida al `README.md` principal o la dejo como fichero separado (`README_ANDROID.md`).