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

RUN pip install --no-cache-dir \
    -i "${PIP_INDEX_URL}" \
    --trusted-host "${PIP_TRUSTED_HOST}" \
    funasr==1.3.1 \
    modelscope==1.37.0 \
    numpy==2.2.2 \
    scipy==1.17.1 \
    soundfile==0.13.1 \
    torch==2.11.0 \
    torchaudio==2.11.0 \
    websockets==16.0

COPY funasr_wss_server.py /app/funasr_wss_server.py

CMD ["python", "-u", "/app/funasr_wss_server.py", "--host", "0.0.0.0", "--port", "10095", "--device", "cpu", "--ngpu", "0", "--ncpu", "2", "--vad_max_end_silence_time", "800", "--offline_endpoint_delay_ms", "300", "--offline_merge_silence_ms", "260", "--offline_pre_roll_ms", "240", "--certfile=", "--keyfile="]
