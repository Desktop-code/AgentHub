# OpenJudge 本机 sidecar 一键启动（Windows PowerShell）。
# 所属：评测域 OpenJudge 本机 sidecar（进程外，独立于 JVM）。
# 约束：只监听 127.0.0.1、端口默认 18765（不擅自换端口）、不改 Java 任何逻辑；
#       启动失败必须**留在屏幕上**（交互窗口 pause）并落盘 start.log，避免窗口秒关看不到原因。
#
# 用法：  .\start.ps1
#        $env:OPENAI_API_KEY="sk-..."; $env:OPENJUDGE_MODEL="qwen-plus"; .\start.ps1
#        $env:PYTHON="D:\Environment\Python\python.exe"; .\start.ps1    # 指定解释器
#        $env:OPENJUDGE_FORCE_INSTALL="1"; .\start.ps1                   # 强制重装依赖
#        $env:OPENJUDGE_NO_PAUSE="1"; .\start.ps1                        # 出错不暂停（CI/脚本调用）

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Port = if ($env:OPENJUDGE_PORT) { $env:OPENJUDGE_PORT } else { "18765" }
$LogPath = Join-Path $PSScriptRoot "start.log"
$Requirements = Join-Path $PSScriptRoot "requirements.txt"

# 打印里程碑并同步落盘：窗口闪退后用户仍能从 start.log 看到停在哪一步
function Write-Step {
    param([string]$Message)
    $line = "[openjudge-sidecar] $Message"
    Write-Host $line
    try {
        Add-Content -Path $LogPath -Value ("{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $line) -Encoding UTF8
    } catch {
        # 日志写不进去不能挡住启动本身
    }
}

# 跑外部命令（python / pip / uv / uvicorn）的统一入口。
# 原因：PowerShell 5.1 在 $ErrorActionPreference=Stop 时会把原生 stderr 变成**终止性**错误，
# 而 pip、uvicorn、`py -3.12` 都会往 stderr 写正常/预期内的信息（缺 pip、找不到 3.12、uvicorn 启动日志），
# 不包一层会让脚本在「预期内」的 stderr 上直接崩掉（这正是之前的隐藏坑）。
# 注意：这里**不**用 `return (& $Action)` 包住输出，否则 uvicorn 的 stdout 会被憋到进程结束才吐；
# 直接让输出自然流过，调用处需要取值时再赋值捕获。退出码仍看 $LASTEXITCODE。
function Invoke-Native {
    param([scriptblock]$Action)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Action } finally { $ErrorActionPreference = $prev }
}

# 仅在交互终端里暂停：CI / Agent / 管道调用时立即退出，避免卡死
function Wait-BeforeExit {
    if ($env:OPENJUDGE_NO_PAUSE -eq "1") { return }
    if ([Console]::IsInputRedirected) { return }
    try { [void](Read-Host "按回车键关闭窗口") } catch { }
}

# 挑一个 >= 3.10 的解释器：PYTHON → py -3.12/3.11 → uv 安装的 3.12 → py -3.10 → PATH 上的 python
# 返回 @{ Exe; Pre; Version }；全都不可用时 throw（由主流程统一 pause + 落盘）
function Resolve-Python {
    $candidates = @()
    if ($env:PYTHON) { $candidates += , @($env:PYTHON, @()) }
    $candidates += , @("py", @("-3.12"))
    $candidates += , @("py", @("-3.11"))

    # uv 安装的 Python 不会出现在 `py -3.12` 里（本机实测报 No suitable Python runtime），单独问一次 uv
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        $uvPy = Invoke-Native { & uv python find 3.12 2>$null }
        if ($LASTEXITCODE -eq 0 -and $uvPy -and (Test-Path "$uvPy".Trim())) {
            $candidates += , @("$uvPy".Trim(), @())
        }
    }

    $candidates += , @("py", @("-3.10"))
    $candidates += , @("python", @())

    foreach ($candidate in $candidates) {
        $exe = $candidate[0]
        $pre = $candidate[1]
        if (-not (Test-Path $exe) -and -not (Get-Command $exe -ErrorAction SilentlyContinue)) { continue }
        $raw = Invoke-Native { & $exe @pre -c "import sys;print('%d.%d' % sys.version_info[:2])" 2>$null }
        if ($LASTEXITCODE -ne 0 -or -not $raw) { continue }
        $parts = "$raw".Trim().Split(".")
        if ([int]$parts[0] -eq 3 -and [int]$parts[1] -ge 10) {
            return @{ Exe = $exe; Pre = $pre; Version = "$raw".Trim() }
        }
    }
    throw "找不到 Python >= 3.10。请先安装（或 `uv python install 3.12`），或设置 `$env:PYTHON 指向解释器路径。"
}

# 判断 venv 解释器可用且 >= 3.10；返回 $true/$false，不抛异常
function Test-VenvPython {
    param([string]$VenvPy)
    if (-not (Test-Path $VenvPy)) { return $false }
    $raw = Invoke-Native { & $VenvPy -c "import sys;print('%d.%d' % sys.version_info[:2])" 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $raw) { return $false }
    $parts = "$raw".Trim().Split(".")
    return ([int]$parts[0] -eq 3 -and [int]$parts[1] -ge 10)
}

# 运行期依赖是否已可 import（供 app.py 使用）；已就绪则跳过安装，省时且不依赖网络
function Test-Deps {
    param([string]$VenvPy)
    return [bool](Invoke-Native {
            & $VenvPy -c "import uvicorn, fastapi, openjudge" 2>$null | Out-Null
            return ($LASTEXITCODE -eq 0)
        })
}

# 在 venv 内安装依赖：
# 1) 有 pip 就直接装；2) 没有 pip（uv 建的 venv 默认不带）先 ensurepip；3) 仍不行用 uv pip 兜底
function Install-Deps {
    param([string]$VenvPy, [string]$RequirementsFile)

    $hasPip = [bool](Invoke-Native {
            & $VenvPy -m pip --version 2>$null | Out-Null
            return ($LASTEXITCODE -eq 0)
        })

    if (-not $hasPip) {
        Write-Step "venv 内没有 pip（uv 建 venv 默认不带），尝试 ensurepip 补齐"
        Invoke-Native { & $VenvPy -m ensurepip --upgrade | Out-Null }
        $hasPip = ($LASTEXITCODE -eq 0)
    }

    if ($hasPip) {
        Invoke-Native { & $VenvPy -m pip install --upgrade pip }
        Invoke-Native { & $VenvPy -m pip install -r $RequirementsFile }
        if ($LASTEXITCODE -ne 0) { throw "依赖安装失败（pip 退出码 $LASTEXITCODE），见上方 pip 输出" }
        return
    }

    if (Get-Command uv -ErrorAction SilentlyContinue) {
        Write-Step "pip 不可用，改用 uv 安装依赖"
        Invoke-Native { & uv pip install --python $VenvPy -r $RequirementsFile }
        if ($LASTEXITCODE -ne 0) { throw "依赖安装失败（uv 退出码 $LASTEXITCODE）" }
        return
    }

    throw "venv 内既没有 pip 也没有 uv，无法安装依赖；请手动执行：`"$VenvPy`" -m ensurepip --upgrade"
}

# 端口占用只报错、不换端口（硬规范：禁止抢口自动换端口）
function Assert-PortFree {
    param([int]$PortNumber)
    $owner = $null
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $conn = Get-NetTCPConnection -LocalPort $PortNumber -State Listen -ErrorAction SilentlyContinue
        if ($conn) { $owner = ($conn | Select-Object -First 1).OwningProcess }
    } else {
        # 老系统没有 Get-NetTCPConnection 时退回 netstat 文本匹配
        $hit = Invoke-Native { netstat -ano 2>$null } | Select-String -Pattern "[:.]$PortNumber\s" | Select-String -Pattern "LISTENING"
        if ($hit) { $owner = "未知（见 netstat）" }
    }
    if ($owner) { throw "端口 $PortNumber 已被占用（PID $owner），请先释放后再启动；本脚本不换端口" }
}

try {
    $python = Resolve-Python
    Write-Step "使用 Python $($python.Version)（$($python.Exe)）"

    # venv：缺失或已损坏（解释器丢失 / 版本 < 3.10）时重建；旧的改名保留便于排查，不直接删
    $venvDir = Join-Path $PSScriptRoot ".venv"
    $venvPy = Join-Path $venvDir "Scripts\python.exe"
    if ((Test-Path $venvDir) -and -not (Test-VenvPython -VenvPy $venvPy)) {
        $brokenName = ".venv.broken-" + (Get-Date -Format "yyyyMMddHHmmss")
        Write-Step "现有 .venv 不可用（缺解释器或低于 Python 3.10），改名保留为 $brokenName 后重建"
        Rename-Item -Path $venvDir -NewName $brokenName
    }
    if (-not (Test-Path $venvPy)) {
        Write-Step "创建 venv（Python $($python.Version)）"
        Invoke-Native { & $python.Exe @($python.Pre) -m venv .venv }
        if ($LASTEXITCODE -ne 0) { throw "创建 venv 失败（退出码 $LASTEXITCODE）" }
    }

    # 依赖已能 import 就跳过安装；强制重装用 OPENJUDGE_FORCE_INSTALL=1
    if ($env:OPENJUDGE_FORCE_INSTALL -eq "1" -or -not (Test-Deps -VenvPy $venvPy)) {
        Install-Deps -VenvPy $venvPy -RequirementsFile $Requirements
        if (-not (Test-Deps -VenvPy $venvPy)) {
            throw "依赖安装后仍无法 import uvicorn / fastapi / openjudge，请查看上方安装输出"
        }
    } else {
        Write-Step "依赖已就绪，跳过安装（强制重装设 `$env:OPENJUDGE_FORCE_INSTALL=1）"
    }

    # 缺这两项时评分会 503（进程仍健康），只提醒不拦截启动
    if (-not $env:OPENAI_API_KEY -or -not $env:OPENJUDGE_MODEL) {
        Write-Step "警告：未设置 OPENAI_API_KEY / OPENJUDGE_MODEL —— /health 仍 200，但评分会 503"
    }

    Assert-PortFree -PortNumber ([int]$Port)

    Write-Step "启动 http://127.0.0.1:$Port （Ctrl+C 停止；日志 $LogPath）"
    Invoke-Native { & $venvPy -m uvicorn app:app --host 127.0.0.1 --port $Port --log-level info }
    if ($LASTEXITCODE -ne 0) {
        throw "uvicorn 退出（退出码 $LASTEXITCODE）：多为端口被占、依赖缺失或 app 导入报错，见上方输出"
    }
}
catch [System.Management.Automation.PipelineStoppedException] {
    # Ctrl+C 属用户主动停止，不算失败，也不暂停
    exit 130
}
catch {
    $msg = $_.Exception.Message
    Write-Host ""
    Write-Host "[openjudge-sidecar] 启动失败：$msg" -ForegroundColor Red
    Write-Host "[openjudge-sidecar] 完整日志：$LogPath" -ForegroundColor Yellow
    Write-Host "[openjudge-sidecar] Windows 请用 PowerShell 运行 .\start.ps1，不要双击 start.sh（窗口会瞬间关闭）" -ForegroundColor Yellow
    try { Add-Content -Path $LogPath -Value ("{0} [ERROR] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg) -Encoding UTF8 } catch { }
    Wait-BeforeExit
    exit 1
}
