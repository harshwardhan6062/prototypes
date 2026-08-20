FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY service.py .

# `opentelemetry-instrument` is the zero-code part: it wraps the process, finds
# Flask and requests already imported, and patches them to emit spans. Nothing in
# service.py asks for this.
ENTRYPOINT ["opentelemetry-instrument", "python", "service.py"]
