output "resource_group_name" {
  value = azurerm_resource_group.rg.name
}

output "aks_cluster_name" {
  value = azurerm_kubernetes_cluster.aks.name
}

output "acr_login_server" {
  description = "ACR 登录地址，用于 docker push/pull 和 build-push.sh"
  value       = azurerm_container_registry.acr.login_server
}

output "ingress_public_ip" {
  description = "NGINX Ingress 公网 IP（AKS LB 自动分配），DNS A 记录指向此 IP"
  value       = data.kubernetes_service.ingress.status[0].load_balancer[0].ingress[0].ip
}

output "kube_config_command" {
  description = "获取 AKS 凭据的命令"
  value       = "az aks get-credentials --resource-group ${azurerm_resource_group.rg.name} --name ${azurerm_kubernetes_cluster.aks.name}"
}
