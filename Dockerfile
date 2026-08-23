FROM elasticsearch-with-ik:7.10.2

# 关闭安全认证 + 启动为单节点
ENV discovery.type=single-node
ENV xpack.security.enabled=false
ENV xpack.ml.enabled=false
ENV xpack.watcher.enabled=false
ENV xpack.license.self_generated.type=basic

# 暴露端口
EXPOSE 9200 9300
