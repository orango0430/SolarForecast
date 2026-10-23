FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app/ml
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY solarcast ./solarcast
COPY entrypoint.sh .
ENV DATA_DIR=/app/data MLFLOW_DISABLE_AGENT_HINT=1 PYTHONUNBUFFERED=1
EXPOSE 8000
ENTRYPOINT ["sh", "entrypoint.sh"]
