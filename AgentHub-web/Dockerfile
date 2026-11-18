FROM node:20-alpine AS build
WORKDIR /web
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
ENV VITE_API_BASE=
RUN npm run build

FROM nginx:alpine
# openssl：首次启动在卷里生成自签证书；不把证书打进镜像
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint-agenthub.sh
COPY --from=build /web/dist /usr/share/nginx/html
RUN chmod +x /docker-entrypoint-agenthub.sh
# 仅对外 443；证书由入口脚本写入 /etc/nginx/certs（compose 命名卷）
EXPOSE 443
ENTRYPOINT ["/docker-entrypoint-agenthub.sh"]
