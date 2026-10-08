FROM python:3.11-slim
RUN pip install --no-cache-dir laya[serve]==0.3.20
EXPOSE 7860
CMD ["python", "-m", "laya", "serve", "--host", "0.0.0.0", "--port", "7860", "--model", "multilingual"]
