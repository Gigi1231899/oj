FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.12-slim

WORKDIR /app

COPY pyproject.toml .
RUN pip install --no-cache-dir fastapi uvicorn openai httpx redis pydantic sse-starlette python-dotenv

COPY . .

EXPOSE 8765

CMD ["uvicorn", "agent:app", "--host", "0.0.0.0", "--port", "8765"]
