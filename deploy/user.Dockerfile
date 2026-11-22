# syntax=docker/dockerfile:1
# 用户端前端：Node 构建 -> Nginx 托管
# 注意：user 的 vite.config.ts 里 alias 'shared' -> ../shared，所以构建上下文必须是项目根目录

FROM node:22-alpine AS build
WORKDIR /build/user

COPY user/package.json user/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm install --no-audit --no-fund --maxsockets=50

COPY shared /build/shared
COPY user/ .

RUN npx vite build

# ------------------------------------------------------------
FROM nginx:1.27-alpine
COPY deploy/nginx/user.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/user/dist /usr/share/nginx/html
EXPOSE 80
