# ─────────────────────────────────────────────────────────────
# 方式 A（当前使用）：宿主机构建，纯 nginx 运行镜像
# 前提：deploy/scripts/build-push.sh 会先执行 pnpm build，
#       确保 DOJ-FE/dist/ 已生成后再 docker build
# ─────────────────────────────────────────────────────────────
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/registry.k8s.io/nginx-slim:0.21

COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

# ─────────────────────────────────────────────────────────────
# 方式 B（备选）：镜像内多阶段构建（自包含，CI/换机器也能跑，
#                 不依赖宿主机 node_modules，但每次构建要装依赖较慢）
# 需要时：注释掉上面方式 A 的 FROM，取消下面这段注释
# ─────────────────────────────────────────────────────────────
# FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/node:20-alpine AS build
# WORKDIR /app
# COPY package*.json ./
# COPY pnpm-lock.yaml ./pnpm-workspace.yaml ./scripts/ ./
# # preinstall.js 强制 pnpm，不能用 npm install
# RUN npm config set registry https://repo.huaweicloud.com/repository/npm/ && \
#     corepack enable && \
#     pnpm install --frozen-lockfile
# COPY . .
# RUN npm run build
#
# FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/registry.k8s.io/nginx-slim:0.21
# COPY --from=build /app/dist/ /usr/share/nginx/html/
# COPY nginx.conf /etc/nginx/conf.d/default.conf
# EXPOSE 80
