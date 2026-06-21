FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

RUN pip install --no-cache-dir \
    numpy==2.3.5 \
    websockets==15.0.1 \
    sherpa-onnx==1.13.3

COPY sherpa_asr_server.py /app/sherpa_asr_server.py

EXPOSE 6006

CMD ["python", "/app/sherpa_asr_server.py"]
