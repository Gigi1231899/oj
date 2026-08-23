#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# D-OnlineJudge — 测试环境一键启动 (start-test.sh)
# ═══════════════════════════════════════════════════════════════
# 用途：把 Azure 上全部资源从零拉起并部署应用，适合"用完就毁"的
#       按次计费测试模式（配合 stop-test.sh 使用）。
#
# 用法：
#   ./start-test.sh                # 默认 ACR: dojacr, tag: v1.0
#   ./start-test.sh dojacr v1.0    # 指定 ACR 与 tag
#
# 流程：
#   ① terraform apply      (AKS + ACR + ingress-nginx + cert-manager)
#   ② 配置 kubectl 并输出 Ingress IP → 暂停等你改 DNS
#   ③ push 中间件镜像       (push-middleware.sh)
#   ④ build + push 应用镜像 (build-push.sh)
#   ⑤ deploy               (deploy.sh: ns + acr-secret + init-schema + helm)
#
# ⚠️ 注意：
#   - 前置：已 az login；本地 Docker 已有中间件镜像（见 push-middleware.sh）
#   - 每次启动公网 IP 会变，DNS 的 A 记录 TTL 建议设 60s
#   - 测试完务必执行 ./stop-test.sh 销毁，避免持续扣费
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

ACR_NAME="${1:-dojacr}"
TAG="${2:-v1.0}"
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
TERRAFORM_DIR="${ROOT_DIR}/terraform"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║        D-OnlineJudge 测试环境一键启动                        ║"
echo "║  ACR: ${ACR_NAME}    Tag: ${TAG}    Region: eastasia         ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# ═══ ① Terraform 拉起基础设施 ══════════════════════════════════
echo ""
echo ">>> ① terraform apply（AKS+ACR+Ingress+cert-manager，约 10-15 分钟）..."
cd "${TERRAFORM_DIR}"
terraform apply -auto-approve

# ═══ ② 若集群处于 Stopped 则先启动 + kubectl + Ingress IP + DNS 确认 ═══
echo ""
echo ">>> ② 检查 AKS 状态并配置 kubectl..."
RG_NAME=$(terraform output -raw resource_group_name)
AKS_NAME=$(terraform output -raw aks_cluster_name)

POWER_STATE=$(az aks show --resource-group "${RG_NAME}" --name "${AKS_NAME}" --query powerState.code -o tsv)
if [ "${POWER_STATE}" = "Stopped" ]; then
  echo ">>> 集群处于 Stopped（上次用 az aks stop 停用），正在启动..."
  az aks start --resource-group "${RG_NAME}" --name "${AKS_NAME}"
  echo ">>> 等待集群恢复 Running..."
  until [ "$(az aks show --resource-group "${RG_NAME}" --name "${AKS_NAME}" --query powerState.code -o tsv)" = "Running" ]; do
    sleep 10
  done
  echo ">>> ✓ 集群已恢复 Running"
else
  echo ">>> 集群状态: ${POWER_STATE}（无需启动）"
fi

az aks get-credentials --resource-group "${RG_NAME}" --name "${AKS_NAME}" --overwrite-existing

echo ""
echo ">>> 获取 Ingress 公网 IP..."
INGRESS_IP=$(terraform output -raw ingress_public_ip)
echo "    公网 IP: ${INGRESS_IP}"
echo ""
echo ">>> ⚠️ 请到域名控制台（阿里云/腾讯云/Cloudflare）把 A 记录指向: ${INGRESS_IP}"
echo "    （TTL 建议 60s，下次重建 IP 会变）"
read -r -p ">>> DNS 已生效后按回车继续部署... " _

# ═══ ③ 推送中间件镜像 ═════════════════════════════════════════
echo ""
echo ">>> ③ 推送中间件镜像到 ACR..."
cd "${ROOT_DIR}"
./deploy/scripts/push-middleware.sh "${ACR_NAME}"

# ═══ ④ 构建并推送应用镜像 ═════════════════════════════════════
echo ""
echo ">>> ④ 构建并推送应用镜像（5 Java + agent + fe）..."
./deploy/scripts/build-push.sh doj "${TAG}" "${ACR_NAME}"

# ═══ ⑤ 部署应用 ══════════════════════════════════════════════
echo ""
echo ">>> ⑤ 部署应用到集群..."
./deploy/scripts/deploy.sh "${ACR_NAME}" "${TAG}"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  ✅ 部署完成！                                               ║"
echo "║  访问: https://ohjudge.asia                                  ║"
echo "║                                                              ║"
echo "║  ⚠️ 测试完记得执行:                                          ║"
echo "║     ./deploy/scripts/stop-test.sh                            ║"
echo "║     销毁全部资源，避免持续扣费                               ║"
echo "╚══════════════════════════════════════════════════════════════╝"
