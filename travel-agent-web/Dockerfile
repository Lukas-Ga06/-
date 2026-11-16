# ---- 构建阶段：Node 20 + Vite ----
FROM node:20-bookworm-slim AS build
WORKDIR /build
COPY package.json package-lock.json ./
# 本机 npm 10.8.2 有 arborist bug；容器内 npm 版本更新，加 --legacy-peer-deps 兼容 canvas peer 声明
RUN npm ci --legacy-peer-deps
COPY . .
# 高德 Web 端配置在 Vite 构建时注入；不要将后端 Web 服务 AMAP_KEY 复用到此处。
ARG VITE_AMAP_KEY=""
ARG VITE_AMAP_SECURITY_CODE=""
ARG VITE_FOOTPRINT_BASEMAP="amap"
ENV VITE_AMAP_KEY=$VITE_AMAP_KEY \
    VITE_AMAP_SECURITY_CODE=$VITE_AMAP_SECURITY_CODE \
    VITE_FOOTPRINT_BASEMAP=$VITE_FOOTPRINT_BASEMAP
RUN npm run build

# ---- 运行阶段：nginx 托管 SPA + 反代 /api ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
