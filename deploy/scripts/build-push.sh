#!/bin/bash
# 构建所有服务镜像 + 推送到指定镜像仓库（默认 Docker Hub）
# 用法: ./build-push.sh <namespace> <tag> [registry-server|acr-name]
# 示例 Docker Hub: ./build-push.sh xuqi695 v1.0
# 示例 ACR:       ./build-push.sh doj v1.0 dojacr
#                 ./build-push.sh doj v1.0 dojacr.azurecr.io

set -e

NAMESPACE=${1:?请指定镜像命名空间/用户名}
TAG=${2:-latest}
REGISTRY=${3:-""}  # 可选，如 myregistry.azurecr.io 或 dojacr
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

# 如果只传了 ACR 名称（不含点），自动补全 .azurecr.io
if [ -n "$REGISTRY" ] && [[ "$REGISTRY" != *.* ]]; then
  REGISTRY="${REGISTRY}.azurecr.io"
fi

if [ -n "$REGISTRY" ]; then
  echo "=== 使用镜像仓库 $REGISTRY ==="
  echo "请确保已执行 az acr login --name <acr-name> 或 docker login $REGISTRY"
  IMAGE_PREFIX="${REGISTRY}/${NAMESPACE}"
else
  echo "=== 登录 Docker Hub ==="
  docker login
  IMAGE_PREFIX="${NAMESPACE}"
fi

# Java 服务列表
SERVICES=(
  "gateway-service:gateway:DOJ-BE/gateway-service"
  "user-service:user-service:DOJ-BE/user-service"
  "problem-service:problem-service:DOJ-BE/problem-service"
  "sandbox-service:sandbox-service:DOJ-BE/sandbox-service"
  "submission-service:submission-service:DOJ-BE/submission-service"
)

for svc in "${SERVICES[@]}"; do
  IFS=":" read -r dir image_name dockerfile_dir <<< "$svc"
  echo ""
  echo "=== 构建 doj-${image_name} ==="
  docker build \
    -t "${IMAGE_PREFIX}/doj-${image_name}:${TAG}" \
    -f "${ROOT_DIR}/${dockerfile_dir}/Dockerfile" \
    "${ROOT_DIR}"
  docker push "${IMAGE_PREFIX}/doj-${image_name}:${TAG}"
done

# Python Agent
echo ""
echo "=== 构建 doj-agent ==="
docker build \
  -t "${IMAGE_PREFIX}/doj-agent:${TAG}" \
  -f "${ROOT_DIR}/DOJ-BE/agent/Dockerfile" \
  "${ROOT_DIR}/DOJ-BE/agent"
docker push "${IMAGE_PREFIX}/doj-agent:${TAG}"

# Vue 前端（宿主机构建：先 pnpm build 生成 dist/，再打入 nginx 镜像）
echo ""
echo "=== 构建 doj-fe ==="
cd "${ROOT_DIR}/DOJ-FE"
echo ">>> ① pnpm install（依赖已存在时很快）..."
pnpm install
echo ">>> ② pnpm build（mode=production → VITE_APP_URL=/api）..."
pnpm build
echo ">>> ③ docker build + push..."
docker build \
  -t "${IMAGE_PREFIX}/doj-fe:${TAG}" \
  -f "${ROOT_DIR}/DOJ-FE/Dockerfile" \
  "${ROOT_DIR}/DOJ-FE"
docker push "${IMAGE_PREFIX}/doj-fe:${TAG}"
cd "${ROOT_DIR}"

echo ""
echo "=== 全部完成 ==="
for svc in "${SERVICES[@]}"; do
  IFS=":" read -r dir image_name dockerfile_dir <<< "$svc"
  echo "  ${IMAGE_PREFIX}/doj-${image_name}:${TAG}"
done
echo "  ${IMAGE_PREFIX}/doj-agent:${TAG}"
echo "  ${IMAGE_PREFIX}/doj-fe:${TAG}"
