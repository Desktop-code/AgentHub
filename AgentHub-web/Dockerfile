FROM node:20-alpine AS build
WORKDIR /web
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
ENV VITE_API_BASE=
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
# 443 为默认对外端口，80 仅做跳转；证书由 compose 以只读卷挂到 /etc/nginx/certs
EXPOSE 80 443
