# Research image: the KINA Laya image plus the packages the non-HTTP experiments need.
#   docker build -t kina-laya-research:local scripts/research/docker
FROM kina-laya:v0.3.27
USER root
RUN /opt/venv/bin/pip install --no-cache-dir "sentence-transformers>=5,<6" "scikit-learn>=1.5" psutil \
 && /opt/venv/bin/python -c "import torch, transformers, sentence_transformers, sklearn; print(torch.__version__, transformers.__version__, sentence_transformers.__version__, sklearn.__version__)"
USER laya
