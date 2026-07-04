FROM python:3.12-slim

WORKDIR /app

ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && update-ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir \
    -i "${PIP_INDEX_URL}" \
    --trusted-host "${PIP_TRUSTED_HOST}" \
    -r requirements.txt

COPY app ./app

ENV HUIXIAO_API_HOST=0.0.0.0
ENV HUIXIAO_API_PORT=8080

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "2"]
