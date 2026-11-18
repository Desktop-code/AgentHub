#!/bin/sh
# 前端容器入口：确保证书存在后再启动 Nginx。所属域：部署配置，不是业务代码。
# 证书写入挂载卷 /etc/nginx/certs；已有文件则复用，避免每次重启重签。
set -e

CERT_DIR=/etc/nginx/certs
CRT="${CERT_DIR}/server.crt"
KEY="${CERT_DIR}/server.key"

mkdir -p "${CERT_DIR}"

# 缺证书时生成本机自签（仅 Compose 本机 / 单机）；不写入镜像层
if [ ! -f "${CRT}" ] || [ ! -f "${KEY}" ]; then
  echo "[agenthub-web] generating self-signed TLS cert for localhost (persisted in volume)"
  rm -f "${CRT}" "${KEY}"
  # 优先带 SAN；旧版 openssl 无 -addext 时回退到仅 CN=localhost
  if ! openssl req -x509 -nodes -newkey rsa:2048 \
      -keyout "${KEY}" \
      -out "${CRT}" \
      -days 3650 \
      -subj "/CN=localhost" \
      -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"; then
    rm -f "${CRT}" "${KEY}"
    openssl req -x509 -nodes -newkey rsa:2048 \
      -keyout "${KEY}" \
      -out "${CRT}" \
      -days 3650 \
      -subj "/CN=localhost"
  fi
fi

exec nginx -g "daemon off;"
