FROM python:3.12-slim

WORKDIR /app

ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn
ARG DEBIAN_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/debian

RUN set -eux; \
    sed -i "s|http://deb.debian.org/debian|${DEBIAN_MIRROR}|g; s|http://security.debian.org/debian-security|${DEBIAN_MIRROR}-security|g" /etc/apt/sources.list.d/debian.sources

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir \
    -i "${PIP_INDEX_URL}" \
    --trusted-host "${PIP_TRUSTED_HOST}" \
    -r requirements.txt

COPY app ./app

ENV HUIXIAO_FFMPEG_PATH=ffmpeg
ENV HUIXIAO_ASR_DEVICE=cpu

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8081"]
