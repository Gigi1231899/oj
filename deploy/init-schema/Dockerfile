# ═══════════════════════════════════════════════════════════════
# D-OnlineJudge — MySQL init-schema 镜像
# ═══════════════════════════════════════════════════════════════
# 用途：承载较大的建表 SQL（>1MB 时 ConfigMap 单 key 放不下）
#   由 MySQL StatefulSet 的 initContainer 引用本镜像，
#   把 /sql/*.sql 拷贝到共享 emptyDir，
#   MySQL 主容器首次启动（/var/lib/mysql 为空）时自动执行。
#
# 构建推送（由 deploy.sh 自动执行）：
#   docker build -t <acr>.azurecr.io/doj/init-schema:<tag> deploy/init-schema
#   docker push    <acr>.azurecr.io/doj/init-schema:<tag>
# ═══════════════════════════════════════════════════════════════
FROM dojacr.azurecr.io/doj/busybox:1.36
WORKDIR /sql
COPY 03-init-tables.sql /sql/03-init-tables.sql
COPY nacos.sql /sql/nacos.sql
