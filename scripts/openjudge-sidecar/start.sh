#!/usr/bin/env bash
# OpenJudge 本机 sidecar 一键启动（macOS / Linux / Git Bash）。
# 所属：评测域 OpenJudge 本机 sidecar（进程外，独立于 JVM）。
# 约束：只监听 127.0.0.1、端口默认 18765（不擅自换端口）、不改 Java 任何逻辑；
#       启动失败必须留在屏幕上（交互终端 pause）并落盘 start.log。
# 注意：Windows 上请用 PowerShell 的 .\start.ps1，**不要双击 .sh**（.sh 关联到 Git Bash，
#       出错时窗口会瞬间关闭，看不到任何报错）。
#
# 用法：  ./start.sh
#        OPENAI_API_KEY=sk-... OPENJUDGE_MODEL=qwen-plus ./start.sh
#        PYTHON=/usr/local/bin/python3.12 ./start.sh          # 指定解释器
#        OPENJUDGE_FORCE_INSTALL=1 ./start.sh                 # 强制重装依赖
#        OPENJUDGE_NO_PAUSE=1 ./start.sh                      # 出错不暂停（CI/脚本调用）
set -euo pipefail

cd "$(dirname "$0")"

PORT="${OPENJUDGE_PORT:-18765}"
LOG_FILE="start.log"

# 打印里程碑并同步落盘：窗口闪退后仍能看到停在哪一步
log() {
  printf '[openjudge-sidecar] %s\n' "$*"
  printf '%s [openjudge-sidecar] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >>"$LOG_FILE" 2>/dev/null || true
}

# 仅在交互终端里暂停：被 CI / 管道 / Agent 调用时立即退出，避免卡死
pause_if_interactive() {
  [ "${OPENJUDGE_NO_PAUSE:-0}" = "1" ] && return 0
  [ -t 0 ] || return 0
  printf '按回车键关闭窗口...' >&2
  read -r _ || true
}

# 主动失败出口：先留痕再决定是否暂停，保证用户看得到原因
die() {
  log "$*"
  pause_if_interactive
  exit 1
}

# 兜底：任何未预期的非零退出（set -e）都留痕，避免无声闪退
on_error() {
  local code=$?
  trap - ERR
  log "启动失败（退出码 $code）；完整日志：$(pwd)/$LOG_FILE"
  pause_if_interactive
  exit "$code"
}
trap on_error ERR

# py-openjudge 要求 Python >= 3.10；按优先级挑一个（uv 安装的 3.12 排在最后兜底）
pick_python() {
  local candidates=()
  [ -n "${PYTHON:-}" ] && candidates+=("$PYTHON")
  candidates+=("python3.12" "python3.11" "python3.10" "python3" "python")
  local one
  for one in "${candidates[@]}"; do
    command -v "$one" >/dev/null 2>&1 || continue
    if "$one" -c 'import sys;raise SystemExit(0 if sys.version_info>=(3,10) else 1)' 2>/dev/null; then
      echo "$one"
      return 0
    fi
  done
  # uv 装的解释器常常不在 PATH 的可执行名里，单独问一次
  if command -v uv >/dev/null 2>&1; then
    local uvpy
    uvpy="$(uv python find 3.12 2>/dev/null || true)"
    if [ -n "$uvpy" ] && "$uvpy" -c 'import sys;raise SystemExit(0 if sys.version_info>=(3,10) else 1)' 2>/dev/null; then
      echo "$uvpy"
      return 0
    fi
  fi
  return 1
}

PYTHON_BIN="$(pick_python)" || die "找不到 Python >= 3.10；请安装（或 uv python install 3.12）或设置 PYTHON 环境变量"
log "使用 $("$PYTHON_BIN" -c 'import sys;print("Python %d.%d" % sys.version_info[:2])')（$PYTHON_BIN）"

# venv 定位：Linux/macOS 在 bin/，Windows（Git Bash）在 Scripts/
VENV_DIR=".venv"
venv_py() {
  if [ -x "$VENV_DIR/bin/python" ]; then
    echo "$VENV_DIR/bin/python"
  else
    echo "$VENV_DIR/Scripts/python.exe"
  fi
}

# venv 是否可用（解释器存在且 >= 3.10）
venv_ok() {
  local py
  py="$(venv_py)"
  [ -f "$py" ] || return 1
  "$py" -c 'import sys;raise SystemExit(0 if sys.version_info>=(3,10) else 1)' >/dev/null 2>&1
}

# venv 缺失或损坏（解释器丢失 / 版本 < 3.10）时重建；旧的改名保留便于排查，不直接删
if [ -d "$VENV_DIR" ] && ! venv_ok; then
  BROKEN="$VENV_DIR.broken-$(date '+%Y%m%d%H%M%S')"
  log "现有 .venv 不可用（缺解释器或低于 Python 3.10），改名保留为 $BROKEN 后重建"
  mv "$VENV_DIR" "$BROKEN"
fi
if [ ! -d "$VENV_DIR" ]; then
  log "创建 venv"
  "$PYTHON_BIN" -m venv "$VENV_DIR" || die "创建 venv 失败，请检查 Python 安装是否完整"
fi
VENV_PY="$(venv_py)"
[ -f "$VENV_PY" ] || die "venv 内找不到解释器（$VENV_PY），请删除 .venv 后重跑"

# 运行期依赖是否已可 import；已就绪则跳过安装（省时且不依赖网络）
deps_ok() {
  "$VENV_PY" -c 'import uvicorn, fastapi, openjudge' >/dev/null 2>&1
}

if [ "${OPENJUDGE_FORCE_INSTALL:-0}" = "1" ] || ! deps_ok; then
  log "安装依赖（首次较慢，py-openjudge 依赖较多）"
  # uv 建的 venv 默认不带 pip：先 ensurepip 补齐（离线可用），仍不行再用 uv pip 兜底
  if ! "$VENV_PY" -m pip --version >/dev/null 2>&1; then
    log "venv 内没有 pip（uv 建 venv 默认不带），尝试 ensurepip 补齐"
    "$VENV_PY" -m ensurepip --upgrade >/dev/null 2>&1 || true
  fi
  if "$VENV_PY" -m pip --version >/dev/null 2>&1; then
    "$VENV_PY" -m pip install --upgrade pip
    "$VENV_PY" -m pip install -r requirements.txt || die "依赖安装失败（pip），见上方输出"
  elif command -v uv >/dev/null 2>&1; then
    log "pip 不可用，改用 uv 安装依赖"
    uv pip install --python "$VENV_PY" -r requirements.txt || die "依赖安装失败（uv），见上方输出"
  else
    die "venv 内既没有 pip 也没有 uv，无法安装依赖；请手动执行：\"$VENV_PY\" -m ensurepip --upgrade"
  fi
  deps_ok || die "依赖安装后仍无法 import uvicorn / fastapi / openjudge，见上方安装输出"
else
  log "依赖已就绪，跳过安装（强制重装设 OPENJUDGE_FORCE_INSTALL=1）"
fi

# 缺这两项时评分会 503（进程仍健康），只提醒不拦截启动
if [ -z "${OPENAI_API_KEY:-}" ] || [ -z "${OPENJUDGE_MODEL:-}" ]; then
  log "警告：未设置 OPENAI_API_KEY / OPENJUDGE_MODEL —— /health 仍 200，但评分会 503"
fi

# 端口占用只报告，不换端口（硬规范：不擅自改端口）。
# 用 Python 试连 127.0.0.1：Windows Git Bash 没有 lsof，netstat 文本匹配又依赖本地化格式，Python 最稳。
port_in_use() {
  "$PYTHON_BIN" -c 'import socket,sys; s=socket.socket(); s.settimeout(0.5); rc=s.connect_ex(("127.0.0.1",int(sys.argv[1]))); s.close(); sys.exit(0 if rc==0 else 1)' "$PORT" >/dev/null 2>&1
}
if port_in_use; then
  die "端口 $PORT 已被占用，请先释放后再启动（本脚本不换端口）"
fi

log "启动 http://127.0.0.1:$PORT （Ctrl+C 停止；日志 $(pwd)/$LOG_FILE）"
# 不用 exec：uvicorn 异常退出时还要留痕并暂停，否则窗口会瞬间关闭
UVICORN_CODE=0
"$VENV_PY" -m uvicorn app:app --host 127.0.0.1 --port "$PORT" --log-level info || UVICORN_CODE=$?

# Ctrl+C（130）与正常退出都算用户主动停止，不当作启动失败
if [ "$UVICORN_CODE" -eq 0 ] || [ "$UVICORN_CODE" -eq 130 ]; then
  log "sidecar 已停止（退出码 $UVICORN_CODE）"
  exit 0
fi
die "uvicorn 退出（退出码 $UVICORN_CODE）：多为端口被占、依赖缺失或 app 导入报错，见上方输出"
