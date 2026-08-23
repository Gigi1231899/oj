# ═══════════════════════════════════════════════════════════════
# AKS + ACR + NGINX Ingress Controller
# ═══════════════════════════════════════════════════════════════
#
# 功能：一键创建 Azure 托管 K8s 集群 + 容器镜像仓库 + 入口网关
#       应用镜像推送至 ACR，中间件全部 K8s 内部署
#
# 执行顺序（Terraform 按依赖自动推导，无需手动排顺序）：
#   ① Resource Group                     (资源组，逻辑容器)
#   ② ACR / AKS                          (互不依赖，并行创建)
#   ③ az aks update --attach-acr         (授权 AKS 从 ACR 拉镜像)
#   ④ helm install ingress-nginx         (部署入口网关)
#   ⑤ 读取 Ingress 公网 IP               (供 DNS 解析用)
#
# 运行前：
#   az login                      # 登录 Azure CLI（③ provisioner 依赖）
#   terraform init                # 下载 provider 插件
#   terraform plan                # 预览变更
#   terraform apply -auto-approve # 执行创建（约 8-15 分钟）
#
# ⚠️ 计费提醒：AKS 节点 VM + ACR + Azure LB 均为持续计费资源
#   用完请 terraform destroy 释放，避免产生费用
# ═══════════════════════════════════════════════════════════════

# ─────────────────────────────────────────────────────────────
# ① Resource Group — Azure 资源的逻辑容器
# ─────────────────────────────────────────────────────────────
# 所有后续资源（ACR、AKS）都创建在这个组内
# 删除资源组 = 一键删除所有关联资源（可用于快速清理）
#
# 语法解说：
#   resource "azurerm_resource_group" "rg" { ... }
#     └ resource          : 关键字，声明创建/管理一个资源
#     └ azurerm_          : provider 前缀，用 azurerm 插件
#     └ resource_group    : Azure 资源类型
#     └ "rg"              : 本地引用名，仅供本目录引用
#                           → 其他资源写 azurerm_resource_group.rg.name 访问
# ─────────────────────────────────────────────────────────────
resource "azurerm_resource_group" "rg" {
  # "${var.prefix}"：字符串插值，把变量拼进字符串
  # 结果：doj-rg（prefix 默认值来自 variables.tf）
  name = "${var.prefix}-rg"
  # var.location：直接引用变量（无需插值）
  # 默认 eastasia（东亚=香港），可改 japaneast / southeastasia 等
  location = var.location
}

# ─────────────────────────────────────────────────────────────
# ② ACR — Azure Container Registry（容器镜像仓库）
# ─────────────────────────────────────────────────────────────
# 私有版 Docker Hub，存放你的应用镜像
# build-push.sh 里 docker push 的目的地就是这里
# ─────────────────────────────────────────────────────────────
resource "azurerm_container_registry" "acr" {
  # ⚠️ ACR 名称全局唯一，且只能小写字母+数字，不要用中划线
  name = "${var.prefix}acr" # 结果：dojacr
  # ── 引用 RG 的属性 ────────────────────────────────────
  # azurerm_resource_group.rg  → Terraform 内部引用路径
  # .name                      → 取 RG 实际 Azure 名称
  # 这种引用同时隐式声明了依赖：先建 RG 再建 ACR
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location # 与 RG 同区域
  sku                 = "Basic"                            # 定价层：Basic ≈ $0.17/天
  # 可选 Basic/Standard/Premium
  admin_enabled = true # 开启管理员账户
  # → 生成用户名+密码供 docker login
  # → build-push.sh 和 K8s imagePullSecrets 依赖它
  # 生产建议关闭，改用 Managed Identity
}

# ─────────────────────────────────────────────────────────────
# ② AKS — Azure Kubernetes Service（托管 K8s 集群）
# ─────────────────────────────────────────────────────────────
# Azure 托管控制面（Master 免费），只付 Worker 节点 VM 费用
# ─────────────────────────────────────────────────────────────
resource "azurerm_kubernetes_cluster" "aks" {
  name     = "${var.prefix}-aks" # 结果：doj-aks
  location = azurerm_resource_group.rg.location
  # 自动推导依赖，先创建 RG 再创建 AKS
  resource_group_name = azurerm_resource_group.rg.name
  # API Server 域名前缀 → https://doj-aks-xxxx.hcp.eastasia.azmk8s.io
  dns_prefix = "${var.prefix}-aks"

  # ─── 默认节点池（Worker 节点）────────────────────────
  # node_count 台 VM 组成一个 VMSS（虚拟机规模集）
  # 每台 VM 运行 kubelet，承载你的 Pod
  default_node_pool {
    name    = "agentpool"      # 节点池名称（创建后不可改）
    vm_size = var.node_vm_size # VM 规格：Standard_D2s_v3 = 2vCPU/8GB
    # 可选 D4s_v3(4C16G) / F4s_v2(4C8G 计算优化)
    node_count = var.node_count # 初始节点数：2
    # ⚠️ 只是初始值，后续可手动扩缩/配自动扩缩
    zones = var.zones # 可用区 [1,2,3]：VM 分散到 3 个
    # 物理隔离的数据中心，单区故障不影响整体

    # ─── 省钱配置（测试环境）──────────────────────────────
    # ⚠️ 注意：Spot 竞价在这里用不上！
    #    Azure 官方限制：Spot 节点池不能作为默认/系统节点池
    #    （只能作为次要 user 节点池），且业务 Pod 需额外配
    #    toleration 才能调度到 Spot 节点 —— 故此处不再配置
    #    enable_spot_instances / spot_max_price。
    #    代价：VM 按需计费省不掉（D2s_v3 已是 eastasia 最便宜的
    #    8GB x86 机型），这部分是测试期的主要成本。
    # 系统盘用 Ephemeral 临时盘：免费（已含在 VM 价格内），
    # 相比原 Premium SSD 托管系统盘（128GB，持续计费）直接省掉。
    os_disk_type          = "Ephemeral"        # 临时系统盘：免费（已含在 VM 价格内）
    os_disk_size_gb       = var.os_disk_size_gb # 必须 ≤ VM 临时盘容量（D2s_v3=50GB）

    # 节点标签：供 Pod 的 nodeSelector / affinity 使用
    node_labels = {
      "nodepool-type" = "system" # 标记为系统节点池
    }
  }

  # ─── 身份认证 ─────────────────────────────────────────
  identity {
    type = "SystemAssigned" # 自动创建托管身份，无需管理密码
    # ③ attach-acr 就是给这个身份授权
  }

  # ─── 网络配置 ─────────────────────────────────────────
  network_profile {
    network_plugin = "azure" # CNI：Pod,Node 都用 Azure 网络，直接拿 VNet 内 IP
    # 性能好，但 VNet IP 有限，大集群需规划子网
    network_policy = "azure" # 支持 NetworkPolicy 限制 Pod 间通信
    # 如 sandbox 只允许 gateway 访问
    load_balancer_sku = "standard" # LB 规格：支持可用区+健康探测
    # Basic 免费但不支持可用区
    outbound_type = "loadBalancer" # Pod 出公网走 LB SNAT
  }

  # K8s 版本：1.36
  # 查看可用版本：az aks get-versions -l eastasia
  kubernetes_version = var.kubernetes_version
}

# ─────────────────────────────────────────────────────────────
# ③ ACR-AKS 绑定 — 授权 AKS 从 ACR 拉取镜像
# ─────────────────────────────────────────────────────────────
# 为什么不直接用 azurerm_role_assignment？
#   → Terraform 的 RBAC 资源有已知时序问题
#   → az aks update --attach-acr 是 Azure 官方推荐方案
#   → 本质：给 AKS 的 Managed Identity 授予 ACR 的 AcrPull 角色
#   → 之后 Pod 就能通过 imagePullSecrets 直接拉 ACR 镜像
#
# terraform_data：无状态占位资源，配合 provisioner 在指定时机
#   执行一次本机命令（local-exec）
# ─────────────────────────────────────────────────────────────
resource "terraform_data" "attach_acr" {
  # triggers_replace：列表里任意值变化 → 强制重新执行 provisioner
  # → AKS 或 ACR 被重建时，自动重新绑定权限
  triggers_replace = [
    azurerm_kubernetes_cluster.aks.id, # AKS 资源 ID 变化（如重建集群）
    azurerm_container_registry.acr.id, # ACR 资源 ID 变化（如重建仓库）
  ]

  # local-exec：在本机执行命令（不是 Azure 上）
  # ⚠️ 前提：已安装 Azure CLI 且已 az login
  provisioner "local-exec" {
    interpreter = ["PowerShell", "-Command"] # Windows 用 PowerShell
    # 实际执行：az aks update --resource-group doj-rg --name doj-aks --attach-acr dojacr
    # 内部动作：
    #   1. 查 AKS 的 Managed Identity
    #   2. 在 ACR 的 IAM 上添加 AcrPull 角色分配
    #   3. K8s 即可用 imagePullSecrets 拉取 ACR 镜像
    command = "az aks update --resource-group ${azurerm_resource_group.rg.name} --name ${azurerm_kubernetes_cluster.aks.name} --attach-acr ${azurerm_container_registry.acr.name}"
  }

  # 显式依赖：等 AKS 和 ACR 都创建完成再执行绑定
  depends_on = [azurerm_kubernetes_cluster.aks, azurerm_container_registry.acr]
}

# ─────────────────────────────────────────────────────────────
# ④ NGINX Ingress Controller — K8s 入口网关
# ─────────────────────────────────────────────────────────────
# 通过 Helm 在集群里安装 ingress-nginx
# chart 包含：nginx Pod(Deployment) + Service + RBAC 等
#
# Service type=LoadBalancer 的底层机制：
#   1. Helm 创建 LoadBalancer 类型 Service
#   2. AKS 的 cloud-controller-manager 检测到
#   3. 自动调用 Azure API 创建 Azure Load Balancer
#   4. 分配公网 IP → 外部流量 → LB → nginx Pod → 后端 Service
#   → 不是 Terraform 建 LB，是 AKS 自动完成，Terraform 只是启动这个过程
#
# ⚠️ 此 LB 持续计费（~$0.025/小时 + 流量费）
# ─────────────────────────────────────────────────────────────
resource "helm_release" "ingress_nginx" {
  name             = "ingress-nginx"                              # Helm Release 名
  repository       = "https://kubernetes.github.io/ingress-nginx" # chart 仓库地址
  chart            = "ingress-nginx"                              # chart 名
  version          = "4.11.4"                                     # chart 版本（锁定，避免漂移）
  namespace        = "ingress-nginx"                              # 安装到哪个 namespace
  create_namespace = true                                         # 不存在就自动创建
  wait             = true                                         # 等 Pod 就绪才算成功
  timeout          = 600                                          # 超时 600s（LB 创建较慢）

  # set {}：运行时覆盖 chart 默认值（等效 helm --set）
  set {
    name  = "controller.service.type"
    value = "LoadBalancer" # Service 类型 → 触发 Azure LB + 公网 IP
  }

  set {
    name  = "controller.service.externalTrafficPolicy"
    value = "Local" # 保留客户端真实源 IP（配合日志/限流）
    # 副作用：流量可能不均衡，需配合健康探测
  }

  set {
    name  = "controller.allowSnippetAnnotations"
    value = "true" # 允许 nginx 片段注解
    # ⚠️ 生产环境慎开，有安全风险（可执行任意 nginx 配置）
  }

  # 等 ③ 授权完成后才装（否则后续拉镜像可能失败）
  depends_on = [terraform_data.attach_acr]
}

# ─────────────────────────────────────────────────────────────
# ④.5 cert-manager — 证书自动管理（Let's Encrypt）
# ─────────────────────────────────────────────────────────────
# cert-manager 是 K8s 的证书管理工具，负责向 Let's Encrypt 申请
# 并自动续期 TLS 证书。安装后集群内就有 Certificate / Issuer /
# ClusterIssuer 三类 CRD，ingress 通过注解触发自动签发。
#
# 与 ingress-nginx 同层：集群级组件，由 terraform 统一管理，
# 无需手动 kubectl apply。
# ─────────────────────────────────────────────────────────────
resource "helm_release" "cert_manager" {
  name             = "cert-manager"                              # Helm Release 名
  repository       = "https://charts.jetstack.io"                # cert-manager 官方 chart 仓库
  chart            = "cert-manager"                              # chart 名
  version          = "v1.13.3"                                   # chart 版本（与 cert-manager 应用版本一致）
  namespace        = "cert-manager"                              # 独立 namespace，不污染业务命名空间
  create_namespace = true                                        # 不存在就自动创建
  wait             = true                                        # 等 CRD + Pod 就绪
  timeout          = 300                                         # 超时 300s

  # 官方要求：安装时启用 CRD（helm 管理 CRD 生命周期）
  set {
    name  = "installCRDs"
    value = "true"
  }

  # 等 ingress-nginx 装完再装（无强依赖，仅保证顺序清晰）
  depends_on = [helm_release.ingress_nginx]
}

# ─────────────────────────────────────────────────────────────
# ⑤ data source — 读取已存在资源（只读，不创建）
# ─────────────────────────────────────────────────────────────
# 查询 ingress-nginx-controller 这个 Service 的 status
# 拿到 Azure LB 自动分配的公网 IP
# 输出在 outputs.tf 的 ingress_public_ip，供配置 DNS A 记录
# ─────────────────────────────────────────────────────────────
data "kubernetes_service" "ingress" {
  metadata {
    name      = "ingress-nginx-controller"
    namespace = "ingress-nginx"
  }
  # 等 helm 部署完 + LB IP 分配完才查询
  depends_on = [helm_release.ingress_nginx]
}
