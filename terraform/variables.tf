variable "prefix" {
  type        = string
  description = "Azure resource name prefix."
  default     = "doj"
}

variable "location" {
  type        = string
  description = "Azure region for resources."
  default     = "eastasia"
}

variable "zones" {
  type        = list(string)
  description = "Availability zones for AKS. 空列表 = 单区不跨可用区（不要高可用，省钱）。"
  default     = []
}

variable "node_count" {
  type        = number
  description = "Initial node count for the AKS default node pool."
  default     = 2
}

variable "node_vm_size" {
  type        = string
  description = "VM size for the AKS default node pool. eastasia 一代 B 系列被禁用、Bpsv2 配额为 0、Bsv2 是 Arm64 不兼容；最便宜的 8GB x86 机器为 D2s_v3/D2s_v4/D2_v4/D2as_v4，价格均为 $0.1320/时，选最成熟的 D2s_v3。"
  default     = "Standard_D2s_v3"
}

variable "kubernetes_version" {
  type        = string
  description = "Kubernetes version for AKS."
  default     = "1.36"
}

# ─── 省钱配置（测试环境）───────────────────────────────
# ⚠️ 重要：Spot 竞价无法用于 AKS 默认/系统节点池（Azure 官方限制），
#    main.tf 的 default_node_pool 已不再引用 use_spot / spot_max_price。
#    以下变量保留仅作说明；若未来想加独立的 Spot user 节点池
#    （需同时给业务 Pod 配 toleration），再在 node pool 资源中使用。
variable "use_spot" {
  type        = bool
  description = "（当前未使用）Spot 竞价节点池开关。AKS 系统节点池不支持 Spot，仅可作次要 user 节点池使用。"
  default     = false
}

variable "spot_max_price" {
  type        = number
  description = "（当前未使用）Spot 最高出价。-1 = 接受 Azure 定价（最便宜，也最容易被回收）。"
  default     = -1
}

variable "os_disk_size_gb" {
  type        = number
  description = "节点 OS 系统盘大小（GB）。配合 Ephemeral 临时盘使用，必须 ≤ VM 临时盘容量（Standard_D2s_v3 = 50GB）。"
  default     = 30
}

variable "domain_name" {
  type        = string
  description = "Public DNS name for the ingress. Leave empty to use the LB public IP directly."
  default     = ""
}
