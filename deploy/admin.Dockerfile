# syntax=docker/dockerfile:1
# 管理端前端：Node 构建 -> Nginx 托管（构建上下文为项目根目录）

FROM node:22-alpine AS build
WORKDIR /build/admin

COPY admin/package.json admin/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm install --no-audit --no-fund --maxsockets=50

# admin 里用了 ../../../shared/utils/security.js 这类相对引用，
# 容器内必须保持与仓库一致的相对布局：/build/admin 与 /build/shared
COPY shared /build/shared
COPY admin/ .

# 只用 vite build，不跑 vue-tsc：类型检查在本地/CI 做，避免类型报错阻断镜像构建
RUN npx vite build

# ------------------------------------------------------------
FROM nginx:1.27-alpine
COPY deploy/nginx/admin.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/admin/dist /usr/share/nginx/html
EXPOSE 80
