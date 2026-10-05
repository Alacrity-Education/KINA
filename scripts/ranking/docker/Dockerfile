# CPU image for fine-tuning and exporting the KINA cross-encoder (scripts/ranking/finetune_cross_encoder.sh).
#   docker build -t kina-ce-finetune:local scripts/ranking/docker
FROM python:3.11-slim-bookworm
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 HF_HUB_DISABLE_TELEMETRY=1
RUN pip install --index-url https://download.pytorch.org/whl/cpu "torch==2.8.*" \
 && pip install "transformers>=4.57,<5" "sentence-transformers>=5,<6" "onnx>=1.17" "onnxruntime==1.23.*" numpy \
 && python -c "import torch, transformers, sentence_transformers, onnx, onnxruntime; print(torch.__version__, transformers.__version__, sentence_transformers.__version__, onnx.__version__, onnxruntime.__version__)"
WORKDIR /work
