FROM python:3.12-slim

COPY sync_backend.py /app/sync_backend.py

ENV PYTHONUNBUFFERED=1
ENV SYNC_HOST=0.0.0.0
ENV SYNC_PORT=7878
ENV SYNC_STATE_FILE=/data/sync-hub-state.json
ENV SYNC_NAMESPACES_DIR=/data/modules

RUN useradd --system --no-create-home --shell /usr/sbin/nologin synchub \
    && mkdir -p /data \
    && chown -R synchub:synchub /data /app

USER synchub
WORKDIR /app

EXPOSE 7878
VOLUME ["/data"]

CMD ["python", "/app/sync_backend.py"]
