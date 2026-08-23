#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# D-OnlineJudge — 测试环境一键销毁 (stop-test.sh)
# ═══════════════════════════════════════════════════════════════
# 用途：terraform destroy 全部 Azure 资源
#       （AKS / ACR / LoadBalancer / 公网 IP / 磁盘 / 网络 / cert-manager 等）
#       → 销毁后零持有成本，下次测试跑 start-test.sh 即可
#
# 用法：
#   ./stop-test.sh                # 默认直接销毁
#
# ⚠️ 注意：
#   - 会删除 ACR 内的全部镜像，下次 start-test.sh 会重新推送
#   - 会删除 AKS 数据（MySQL/ES 等 PVC 数据），测试数据不保留
#   - 执行前会二次确认，输入 yes 才真正销毁
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
TERRAFORM_DIR="${ROOT_DIR}/terraform"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║        D-OnlineJudge 测试环境一键销毁                        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "⚠️  将销毁以下全部资源："
echo "    - AKS 集群（含节点 VM，Spot 按秒计费停止）"
echo "    - ACR 镜像仓库（所有镜像丢失）"
echo "    - LoadBalancer / 公网 IP / 磁盘 / 网络"
echo "    - cert-manager 等集群内组件"
echo ""

read -r -p ">>> 确认销毁？输入 yes 继续: " CONFIRM
if [ "${CONFIRM}" != "yes" ]; then
  echo "已取消，资源保留。"
  exit 0
fi

echo ""
echo ">>> terraform destroy（约 5-10 分钟）..."
cd "${TERRAFORM_DIR}"
terraform destroy -auto-approve

echo ""
echo ">>> 验证资源组已删除..."
if az group show -n doj-rg >/dev/null 2>&1; then
  echo "  ⚠ doj-rg 仍存在，请检查上面 destroy 输出"
else
  echo "  ✓ doj-rg 已删除，无持续扣费"
fi

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  ✅ 已全部销毁，无持续扣费                                   ║"
echo "║     下次测试: ./deploy/scripts/start-test.sh                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
