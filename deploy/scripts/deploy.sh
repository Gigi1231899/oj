#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# D-OnlineJudge — 部署脚本 (deploy.sh)
# ═══════════════════════════════════════════════════════════════
# 用法:
#   ./deploy.sh <acr-name> <tag> [namespace]
#
# 示例:
#   ./deploy.sh dojacr v1.0            # 默认 namespace: doj
#   ./deploy.sh dojacr v1.0 prod       # 指定 namespace: prod
#
# 前提条件:
#   1. 已通过 az acr login --name <acr-name> 登录 ACR
#   2. kubectl 已连接到 AKS 集群
#   3. 镜像已通过 build-push.sh 推送至 ACR
#
# 脚本流程:
#   ① 创建 namespace（如不存在）
#   ② 创建 ACR 拉取密钥（acr-secret）
#   ③ 构建并推送 init-schema 镜像（MySQL 建表 DDL 载体，>1MB 不能进 ConfigMap）
#   ④a 阶段 1：仅部署中间件 + Nacos（微服务暂缓，--wait 确保就绪）
#   ④b 阶段 2：Nacos 就绪后通过 Open API 自动导入 shared-*.yaml 配置
#   ④c 阶段 3：全量部署微服务 + 前端 + Ingress（配置已就绪，服务起来即用）
#   ⑤ 等待所有 Pod 就绪
#   ⑥ 输出访问地址
# ═══════════════════════════════════════════════════════════════

set -e

# ─── 参数解析 ─────────────────────────────────────────────────
ACR_NAME=${1:?请指定 ACR 名称（如 dojacr）}
TAG=${2:?请指定镜像标签（如 v1.0）}
NAMESPACE=${3:-doj}                          # 默认 namespace = doj
ACR_LOGIN_SERVER="${ACR_NAME}.azurecr.io"     # 补全为完整 ACR 地址
IMAGE_REGISTRY="${ACR_LOGIN_SERVER}/doj"      # 镜像仓库前缀

# ─── 路径计算 ─────────────────────────────────────────────────
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
HELM_DIR="${ROOT_DIR}/deploy/helm/doj"

# ─── AKS 信息（保持与 Terraform 一致）──────────────────────────
AKS_RG="doj-rg"
AKS_NAME="doj-aks"

# ═══ 0.5 若 AKS 处于 Stopped，先自动启动 ═══════════════════════
echo ">>> 检查 AKS 状态..."
POWER_STATE=$(az aks show --resource-group "${AKS_RG}" --name "${AKS_NAME}" --query powerState.code -o tsv 2>/dev/null || echo "Unknown")
if [ "${POWER_STATE}" = "Stopped" ]; then
  echo ">>> AKS 当前 Stopped，正在启动..."
  az aks start --resource-group "${AKS_RG}" --name "${AKS_NAME}"
  echo ">>> 等待 AKS 恢复 Running..."
  until [ "$(az aks show --resource-group "${AKS_RG}" --name "${AKS_NAME}" --query powerState.code -o tsv)" = "Running" ]; do
    sleep 10
  done
  echo ">>> ✓ AKS 已恢复 Running"
elif [ "${POWER_STATE}" = "Unknown" ]; then
  echo "   ⚠ 无法获取 AKS 状态，请确认 az login 及集群名正确"
  exit 1
else
  echo "   AKS 状态: ${POWER_STATE}"
fi
az aks get-credentials -g doj-rg -n doj-aks --overwrite-existing
kubectl cluster-info
az acr login --name ${ACR_NAME}
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          D-OnlineJudge K8s 部署脚本                          ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  ACR:          ${ACR_LOGIN_SERVER}"
echo "║  镜像前缀:     ${IMAGE_REGISTRY}"
echo "║  镜像标签:     ${TAG}"
echo "║  Namespace:    ${NAMESPACE}"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# ═══ ① 创建 Namespace ═════════════════════════════════════════
echo ">>> 创建 Namespace ${NAMESPACE}（如不存在）..."
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

# ═══ ② 创建 ACR 镜像拉取密钥 ══════════════════════════════════
# 原理：K8s 通过 docker-registry 类型的 Secret 认证 ACR
# 这里使用 ACR 管理员账号自动获取密码
echo ">>> 创建 ACR 拉取密钥 acr-secret..."
ACR_PASSWORD=$(az acr credential show -n "${ACR_NAME}" --query "passwords[0].value" -o tsv 2>/dev/null || echo "")

if [ -n "${ACR_PASSWORD}" ]; then
  kubectl delete secret acr-secret -n "${NAMESPACE}" --ignore-not-found
  kubectl create secret docker-registry acr-secret \
    --docker-server="${ACR_LOGIN_SERVER}" \
    --docker-username="${ACR_NAME}" \
    --docker-password="${ACR_PASSWORD}" \
    --namespace="${NAMESPACE}"
  echo "   ✓ acr-secret 已创建"
else
  echo "   ⚠ 无法获取 ACR 密码，请确认已登录: az acr login --name ${ACR_NAME}"
  exit 1
fi

# ═══ ②.5 构建并推送 init-schema 镜像 ══════════════════════════
# 建表 DDL（03-init-tables.sql）>1MB 不能进 ConfigMap，
# 打包成 init-schema 镜像，由 MySQL initContainer 引用
echo ""
echo ">>> 构建并推送 init-schema 镜像..."
docker build -t "${IMAGE_REGISTRY}/init-schema:${TAG}" "${ROOT_DIR}/deploy/init-schema"
docker push "${IMAGE_REGISTRY}/init-schema:${TAG}"
echo "   ✓ init-schema 镜像已推送: ${IMAGE_REGISTRY}/init-schema:${TAG}"

# ═══ ③a 阶段 1：仅部署中间件 + Nacos ═══════════════════════════
# 原因：微服务的 wait-for-nacos initContainer 只等 Nacos HTTP 就绪，
# 不检查 shared-*.yaml 配置是否已导入。若一把梭部署，微服务会在配置
# 导入前启动 → 拉不到配置 → CrashLoop → helm --wait 超时。
# 所以先只起中间件 + Nacos，导入配置后再全量部署。
echo ""
echo ">>> [阶段 1/3] 部署中间件 + Nacos（微服务暂缓）..."
# --reset-values：helm upgrade 默认沿用上次 release 保存的 values，
# 加它保证每次渲染都从 chart 默认值 + 本次 --set 出发，脚本可重复执行、
# 不受历史 release 残留影响（否则重复部署时 enabled=false 可能越积越多）。
helm upgrade --install doj "${HELM_DIR}" \
  --namespace "${NAMESPACE}" \
  --set global.imageRegistry="${IMAGE_REGISTRY}" \
  --set global.imageTag="${TAG}" \
  --set global.imagePullSecrets[0].name=acr-secret \
  --set mysql.initSchemaImage="${IMAGE_REGISTRY}/init-schema:${TAG}" \
  --set gateway.enabled=false \
  --set user.enabled=false \
  --set problem.enabled=false \
  --set sandbox.enabled=false \
  --set submission.enabled=false \
  --set agent.enabled=false \
  --set frontend.enabled=false \
  --set ingress.enabled=false \
  --reset-values \
  --timeout 20m \
  --wait


# ═══ ③b 阶段 2：自动导入 Nacos shared 配置 ════════════════════
# Nacos 是 ClusterIP，本地无法直连，用 port-forward 打通 Open API
echo ""
echo ">>> [阶段 2/3] 等待 Nacos 就绪并自动导入 shared 配置..."
kubectl rollout status deployment/nacos -n "${NAMESPACE}" --timeout=600s

kubectl port-forward -n "${NAMESPACE}" svc/nacos 8848:8848 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "${PF_PID}" 2>/dev/null || true' EXIT

# 等 port-forward 生效（Nacos readiness 返回 OK）
for i in $(seq 1 30); do
  if curl -s -m 2 -o /dev/null "http://127.0.0.1:8848/nacos/v1/console/health/readiness"; then
    break
  fi
  sleep 2
done

# 幂等导入（Open API 重复提交同 dataId 即覆盖，无副作用）
for f in shared-jdbc shared-jwt shared-rabbitmq shared-redis shared-swagger; do
  # || true：Nacos 未就绪时 curl 会返回非 0，避免 set -e 中断整个脚本
  HTTP_CODE=$(curl -s -m 10 -o /tmp/nacos-import.log -w "%{http_code}" \
    -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${f}.yaml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=$(cat "${ROOT_DIR}/deploy/nacos-configs/${f}.yaml")") || true
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "   ✓ ${f}.yaml 已导入"
  else
    echo "   ⚠ ${f}.yaml 导入失败 (HTTP ${HTTP_CODE}): $(cat /tmp/nacos-import.log)"
  fi
done

kill "${PF_PID}" 2>/dev/null || true
trap - EXIT

# ═══ ③c 阶段 3：全量部署（微服务 + 前端 + Ingress）══════════════
# 此时 shared 配置已就绪，微服务 wait-for-nacos 通过后即可正常拉取配置
echo ""
echo ">>> [阶段 3/3] 全量部署（微服务 + 前端 + Ingress）..."
# 关键：--reset-values 必须有！
# helm upgrade 默认会沿用上次 release 保存的 values 并叠加本次 --set。
# 阶段 1 写入的 gateway/user/.../ingress.enabled=false 会原样残留到本次渲染，
# 导致微服务/前端/Ingress 永远不部署。--reset-values 丢弃历史 values，
# 改用 chart 默认值（values.yaml 中 enabled 全为 true）+ 本次 --set 渲染。
helm upgrade --install doj "${HELM_DIR}" \
  --namespace "${NAMESPACE}" \
  --set global.imageRegistry="${IMAGE_REGISTRY}" \
  --set global.imageTag="${TAG}" \
  --set global.imagePullSecrets[0].name=acr-secret \
  --set mysql.initSchemaImage="${IMAGE_REGISTRY}/init-schema:${TAG}" \
  --reset-values \
  --timeout 25m \
  --wait

# ═══ ④ 等待所有 Pod 就绪 ═════════════════════════════════════
echo ""
echo ">>> 等待所有 Pod 就绪..."
kubectl wait --for=condition=ready pod \
  -l "app in (mysql,rabbitmq,redis,nacos,elasticsearch,gateway,user,problem,sandbox,submission,agent,doj-fe)" \
  -n "${NAMESPACE}" \
  --timeout=10m || true

# ═══ ⑤ 状态摘要 ══════════════════════════════════════════════
echo ""
echo ">>> Pod 状态:"
kubectl get pods -n "${NAMESPACE}"

echo ""
echo ">>> Service:"
kubectl get svc -n "${NAMESPACE}"

echo ""
echo ">>> Ingress 公网 IP:"
INGRESS_IP=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "未就绪")
echo "  ${INGRESS_IP}"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  部署完成！                                                  ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  公网 IP:  ${INGRESS_IP}"
echo "║  请在 DNS 添加 A 记录指向此 IP                                ║"
echo "║  查看日志: kubectl logs -f <pod-name> -n ${NAMESPACE}       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
