FROM python:3.11-slim

# Evitar buffers en logs
ENV PYTHONUNBUFFERED=1

# Dependencias del sistema (si necesitas tesseract/ocr en el contenedor, añadir aquí)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copiar solo lo necesario
COPY scripts/ ./scripts/
COPY README_persistencia.md ./README_persistencia.md

# Instalar dependencias Python
RUN python -m pip install --upgrade pip setuptools wheel
RUN python -m pip install flask gunicorn

# Crear usuario no-root
RUN useradd --create-home --shell /bin/bash appuser
USER appuser
WORKDIR /home/appuser

# Copiar app al home del usuario
COPY --chown=appuser:appuser scripts/ ./scripts/
COPY --chown=appuser:appuser README_persistencia.md ./

# Exponer puerto
EXPOSE 5001

# Comando por defecto: gunicorn apuntando al app Flask
# scripts.corrections_api:app
CMD ["gunicorn", "--bind", "0.0.0.0:5001", "scripts.corrections_api:app", "--workers", "2", "--threads", "4", "--timeout", "120"]
