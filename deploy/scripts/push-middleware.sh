#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# D-OnlineJudge — 本地镜像 → ACR 推送脚本
# ═══════════════════════════════════════════════════════════════
# 用途：把本地 Docker Desktop 中已有的中间件 + 判题运行时镜像
#       re-tag 成 ACR 完整路径并推送，AKS 节点通过 acr-secret 拉取。
#
# 用法：
#   ./push-middleware.sh                # 默认 ACR: dojacr
#   ./push-middleware.sh dojacr         # 指定 ACR 名称
#   ACR_NAME=myacr ./push-middleware.sh
#
# 前置：
#   az acr login --name dojacr   （或 docker login dojacr.azurecr.io）
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

ACR_NAME="${1:-${ACR_NAME:-dojacr}}"
ACR="${ACR_NAME}.azurecr.io"
NS="doj"

echo "════════════════════════════════════════════════════"
echo " 目标 ACR : ${ACR}/${NS}"
echo "════════════════════════════════════════════════════"

# 登录 ACR
az acr login --name "${ACR_NAME}"

# ─────────────────────────────────────────────────────────
# 1. 中间件镜像（本地已存在，re-tag + push）
# ─────────────────────────────────────────────────────────
push_image() {
    local src="$1"
    local dst="$2"
    echo "──────────────────────────────"
    echo ">>> ${src}  →  ${ACR}/${NS}/${dst}"
    docker tag "${src}" "${ACR}/${NS}/${dst}"
    docker push "${ACR}/${NS}/${dst}"
}

# 本地镜像名（docker images 实际输出）→ ACR 目标路径
push_image "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/mysql:latest"     "mysql:latest"
push_image "redis:alpine"                                                        "redis:alpine"
push_image "rabbitmq:3-management"                                               "rabbitmq:3-management"
push_image "nacos/nacos-server:v2.5.1"                                           "nacos-server:v2.5.1"
push_image "elasticsearch-with-ik:7.10.2"                                        "elasticsearch-with-ik:7.10.2"

# ─────────────────────────────────────────────────────────
# 2. 判题运行时镜像（LanguageEnum 引用，AKS Job 拉取）
#    对应 DOJ_SANDBOX_IMAGE_PREFIX 前缀
# ─────────────────────────────────────────────────────────
push_image "doj-python:latest"  "doj-python:latest"
push_image "doj-java:latest"    "doj-java:latest"
push_image "myoj_time:1.0"      "myoj_time:1.0"

# ─────────────────────────────────────────────────────────
# 3. busybox（init-schema 载体，本地若无则先拉取）
# ─────────────────────────────────────────────────────────

push_image "registry.k8s.io/e2e-test-images/busybox:1.36.1-1" "busybox:1.36"

echo ""
echo "════════════════════════════════════════════════════"
echo " ✅ 全部镜像已推送至 ${ACR}/${NS}"
echo " 部署时记得 values.yaml 的中间件镜像路径已指向此 ACR"
echo "════════════════════════════════════════════════════"
