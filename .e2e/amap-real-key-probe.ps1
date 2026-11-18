<#
.SYNOPSIS
    高德「Web 服务」Key 真实联调探针：一条命令起独立实例、跑规划与路线段、落盘证据。

.DESCRIPTION
    背景：本项目的路线 / POI 有三级取数（高德 MCP -> 高德 REST -> 本地 haversine 估算），
    每段路线带 source 标记（amap / estimated）。本脚本用后端「Web 服务」Key（环境变量 AMAP_KEY）
    起一个**独立 H2 文件库 + 独立端口**的实例，验证真实 POI / 路径 / GCJ-02 坐标链路，并把证据落盘。

    验证项（每个结果同时打印到控制台并写入 -OutDir）：
      0) 启动独立实例（或 -SkipStart 复用已启动实例）并等待 /actuator/health = UP
      1) GET /api/system/status：amapConfigured / geoProvider / routeProvider / routeHint
      2) demo/demo123 登录（curl.exe + UTF-8 body 文件，避免 PowerShell 5.1 中文乱码）
      3) 规划一个两日游 -> 确认 -> GET /api/trips/{id}/route-legs，统计总段数 / amap 段数 / estimated 段数，
         断言至少一段 source=amap；全部 estimated 时判定 FAIL 并给出排查提示
      4) POI / 地理编码：
         4a) 后端对外 REST 接口 —— 当前**没有**该接口（见 docs/guide/mcp-amap.md 新增章节），如实记 SKIP；
         4b) 直连高德 REST /v3/place/text 与 /v3/direction/driving 做 Key 诊断（与 AmapDataService 同一端点），
             记录返回条数并校验坐标落在 GCJ-02 合理范围（中国境内经纬度包围盒）
      5) 汇总 PASS/FAIL 表写入 -OutDir\summary.md（并写一份同内容的 summmary.md，兼容需求原文拼写）

    进程安全：启动时记录 java 进程 PID，结束时只用 taskkill /PID <pid> /T 停掉**本脚本启动的那棵树**；
    不枚举、不杀其它 java 进程。-SkipStart 时不做任何启停。

.PARAMETER AmapKey
    高德开放平台「Web 服务」类型的 Key（不是 JS API Key）。只注入子进程环境变量 AMAP_KEY。

.PARAMETER Port
    探针实例端口，默认 18090（不与默认开发端口 8080 冲突）。

.PARAMETER SkipStart
    复用已在 -Port 上运行的后端实例，不启动/不停止任何进程。该实例必须已配置 AMAP_KEY。

.PARAMETER OutDir
    证据输出目录，默认 <仓库根>\.e2e\amap-key-<yyyyMMdd-HHmmss>。

.PARAMETER DryRun
    只校验参数与本地配置（Key 格式、端口、curl/java/mvn、jar），不启动服务、不发起联调请求。

.PARAMETER JarPath
    指定后端 jar；缺省时用 travel-agent-server\target\travel-agent-server-*.jar，
    没有则先执行 mvn -B -DskipTests package。

.PARAMETER StartupTimeoutSec
    轮询 /actuator/health 的超时，默认 180 秒。

.PARAMETER SkipRestDiag
    跳过 4b 直连高德 REST 诊断（例如离线环境）。第 3 步仍会经后端调用高德。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .e2e\amap-real-key-probe.ps1 -AmapKey "你的Web服务Key"

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .e2e\amap-real-key-probe.ps1 -AmapKey "fake" -DryRun

.NOTES
    兼容 Windows PowerShell 5.1（脚本保存为 UTF-8 with BOM）。不写任何明文 Key 到证据目录：
    证据里只记录 Key 的 SHA-256 前 8 位指纹。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AmapKey,

    [int]$Port = 18090,

    [switch]$SkipStart,

    [string]$OutDir,

    [switch]$DryRun,

    [string]$JarPath,

    [int]$StartupTimeoutSec = 180,

    [switch]$SkipRestDiag
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# ============================== 全局状态 ==============================

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:ServerDir = Join-Path $script:RepoRoot 'travel-agent-server'
$script:BaseUrl = 'http://127.0.0.1:' + $Port
$script:Checks = New-Object System.Collections.ArrayList
$script:StartedProc = $null
$script:ServerPid = 0
# Windows 上 PATH 里的 java 可能是 javapath 启动器：它会再拉一个真正的 JVM 子进程。
# 这里额外记录「实际监听端口的那个 PID」，结束时连同父 PID 一起停（只停本脚本启动的树）。
$script:ServerChildPid = 0
$script:ServerStdout = $null
$script:ServerStderr = $null
$script:EnvBackup = @{}
$script:CookieJar = $null
$script:ExitCode = 0
$script:Phase = 'init'
$script:KeyFingerprint = 'none'
$script:StartedAt = Get-Date
$script:TripId = $null
$script:PlanId = $null
$script:RouteLegStats = $null
$script:HealthResult = 'not-started'
$script:CleanupNote = '未启动进程（-SkipStart）'
$script:RunDir = $null

# ============================== 基础工具 ==============================

function Write-Step([int]$n, [string]$title) {
    Write-Host ''
    Write-Host ("== [{0}] {1} ==" -f $n, $title) -ForegroundColor Cyan
}

function Write-StepTitle([string]$title) {
    Write-Host ''
    Write-Host ("== " + $title) -ForegroundColor Cyan
}

function Write-Ok([string]$msg) {
    Write-Host ("  PASS  " + $msg) -ForegroundColor Green
}

function Write-Fail([string]$msg) {
    Write-Host ("  FAIL  " + $msg) -ForegroundColor Red
}

function Write-Skip([string]$msg) {
    Write-Host ("  SKIP  " + $msg) -ForegroundColor Yellow
}

function Write-Info([string]$msg) {
    Write-Host ("        " + $msg) -ForegroundColor DarkGray
}

function Write-Warn([string]$msg) {
    Write-Host ("  WARN  " + $msg) -ForegroundColor Yellow
}

function Add-Check([string]$id, [string]$name, [string]$status, [string]$detail) {
    [void]$script:Checks.Add([pscustomobject]@{
        id     = $id
        name   = $name
        status = $status
        detail = $detail
    })
    switch ($status) {
        'PASS' { Write-Ok ($id + ' ' + $name + '：' + $detail) }
        'FAIL' { Write-Fail ($id + ' ' + $name + '：' + $detail) }
        'SKIP' { Write-Skip ($id + ' ' + $name + '：' + $detail) }
        default { Write-Info ($id + ' ' + $name + '：' + $detail) }
    }
}

function Write-Utf8NoBom([string]$path, [string]$text) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $enc)
}

function Write-EvidenceText([string]$name, [string]$text) {
    $p = Join-Path $script:OutDir $name
    Write-Utf8NoBom $p $text
    return $p
}

function Write-EvidenceJson([string]$name, $obj) {
    $json = $obj | ConvertTo-Json -Depth 20
    return Write-EvidenceText $name $json
}

function ConvertFrom-JsonSafe([string]$text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

function Get-KeyFingerprint([string]$key) {
    if ([string]::IsNullOrEmpty($key)) { return 'none' }
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($key))
        } finally {
            $sha.Dispose()
        }
        return (([System.BitConverter]::ToString($bytes)).Replace('-', '').Substring(0, 8).ToLowerInvariant())
    } catch {
        return 'unavailable'
    }
}

function Test-PortOpen([int]$p) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect('127.0.0.1', $p, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(1200)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# 监听端口的 PID；拿不到时返回 0（Get-NetTCPConnection 在 Win8+ 可用）
function Resolve-ListenerPid([int]$p) {
    try {
        $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($conn -and $conn.OwningProcess) { return [int]$conn.OwningProcess }
    } catch { }
    return 0
}

# 判断某个 PID 是否是本脚本启动进程的直接子进程（javapath -> 真 JVM 这一层）
function Test-IsDirectChildOf([int]$childPid, [int]$parentPid) {
    try {
        $proc = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $childPid) -ErrorAction Stop
        return ($proc -and [int]$proc.ParentProcessId -eq $parentPid)
    } catch {
        return $false
    }
}

function Assert-RepoLayout {
    if (-not (Test-Path -LiteralPath (Join-Path $script:ServerDir 'pom.xml'))) {
        throw ('找不到后端模块：' + $script:ServerDir + '。请把脚本放在 <仓库根>\.e2e\ 下运行（或从仓库根调用）。')
    }
}

# 用 curl.exe 发请求：body 一律走 UTF-8 无 BOM 文件（避免 PowerShell 5.1 把中文写成 ANSI）。
# 返回 @{ http = <int 状态码，失败为 -1>; body = <响应文本>; curlExit = <进程退出码>; outFile = <响应落盘路径> }
function Invoke-CurlRequest {
    param(
        [string]$Method = 'GET',
        [string]$Url,
        [string]$BodyFile,
        [string]$CookieJar,
        [int]$MaxTime = 60,
        [string]$OutFile
    )
    if (-not $OutFile) {
        $OutFile = Join-Path $script:TmpDir ('resp-' + [guid]::NewGuid().ToString('N') + '.out')
    }
    $errFile = Join-Path $script:TmpDir ('curl-' + [guid]::NewGuid().ToString('N') + '.err')
    $cargs = @('-s', '-S', '--max-time', [string]$MaxTime, '-o', $OutFile, '-w', '%{http_code}')
    if ($Method -ne 'GET') { $cargs += @('-X', $Method) }
    if ($BodyFile) {
        $cargs += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $BodyFile))
    }
    if ($CookieJar) {
        if (Test-Path -LiteralPath $CookieJar) { $cargs += @('-b', $CookieJar) }
        $cargs += @('-c', $CookieJar)
    }
    $cargs += $Url

    $codeText = ''
    try {
        $codeText = (& curl.exe @cargs 2> $errFile)
    } catch {
        $codeText = ''
    }
    $curlExit = $LASTEXITCODE
    $body = ''
    if (Test-Path -LiteralPath $OutFile) {
        try { $body = [System.IO.File]::ReadAllText($OutFile, [System.Text.Encoding]::UTF8) } catch { $body = '' }
    }
    $http = -1
    if (-not [int]::TryParse(([string]$codeText).Trim(), [ref]$http)) { $http = -1 }
    return [pscustomobject]@{
        http     = $http
        body     = $body
        curlExit = $curlExit
        outFile  = $OutFile
        errFile  = $errFile
    }
}

function New-JsonBodyFile([string]$name, [string]$json) {
    $p = Join-Path $script:OutDir $name
    Write-Utf8NoBom $p $json
    return $p
}

# 列服务器日志里的 ASCII 结构行（编码无关），用于启动失败/降级排查提示
function Get-LogHints([string]$path, [int]$max = 12) {
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return @() }
    $lines = New-Object System.Collections.ArrayList
    try {
        $bytes = [System.IO.File]::ReadAllBytes($path)
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        if ($text.IndexOf([char]0xFFFD) -ge 0) {
            $text = [System.Text.Encoding]::GetEncoding(936).GetString($bytes)
        }
        foreach ($line in ($text -split "`r?`n")) {
            if ($line -match 'APPLICATION FAILED TO START|Caused by|ERROR|WARN|Started TravelAgentApplication|Tomcat started|AMAP|amap|MCP') {
                [void]$lines.Add($line.Trim())
                if ($lines.Count -ge $max) { break }
            }
        }
    } catch {
        return @()
    }
    return @($lines)
}

# ============================== 参数与配置校验 ==============================

function Initialize-OutDir {
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $OutDir = Join-Path $script:RepoRoot ('.e2e\amap-key-' + $stamp)
    }
    if (-not [System.IO.Path]::IsPathRooted($OutDir)) {
        $OutDir = Join-Path (Get-Location).Path $OutDir
    }
    if (-not (Test-Path -LiteralPath $OutDir)) {
        New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    }
    $script:OutDir = (Resolve-Path -LiteralPath $OutDir).Path
    $script:TmpDir = Join-Path $env:TEMP ('amap-key-probe-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $script:TmpDir -Force | Out-Null
}

function Test-KeyShape([string]$key) {
    if ([string]::IsNullOrWhiteSpace($key)) { return 'Key 为空' }
    if ($key -ne $key.Trim()) { return 'Key 首尾有空白字符' }
    if ($key -match '\s') { return 'Key 中间含空白字符' }
    if ($key.Length -lt 16) { return ('Key 长度偏短（' + $key.Length + ' 字符），高德 Web 服务 Key 通常为 32 位') }
    return ''
}

function Get-SuspectedPlaceholder([string]$key) {
    $lower = $key.ToLowerInvariant()
    foreach ($word in @('fake', 'placeholder', 'yourkey', 'your-', 'xxx', '<', '>', 'changeme', 'demo-key')) {
        if ($lower.Contains($word)) { return $word }
    }
    return ''
}

function Resolve-ServerJar {
    if ($JarPath) {
        if (-not (Test-Path -LiteralPath $JarPath)) {
            throw ('-JarPath 指定的文件不存在：' + $JarPath)
        }
        return (Resolve-Path -LiteralPath $JarPath).Path
    }
    $target = Join-Path $script:ServerDir 'target'
    $find = {
        if (-not (Test-Path -LiteralPath $target)) { return $null }
        return (Get-ChildItem -LiteralPath $target -Filter 'travel-agent-server-*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*.original' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    }
    $jar = & $find
    if ($jar) { return $jar.FullName }

    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw '未找到可用的后端 jar，且 PATH 里没有 mvn。请先执行 mvn -DskipTests package，或用 -JarPath 指定 jar。'
    }
    Write-Info '未找到后端 jar，先执行 mvn -B -DskipTests package（日志：mvn-package.log）…'
    $buildLog = Join-Path $script:OutDir 'mvn-package.log'
    Push-Location -LiteralPath $script:ServerDir
    try {
        & mvn -B -DskipTests package *> $buildLog
        $buildExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildExit -ne 0) {
        throw ('mvn package 失败（exit=' + $buildExit + '），见 ' + $buildLog)
    }
    $jar = & $find
    if (-not $jar) { throw ('mvn package 结束但 target 下仍没有 jar，见 ' + $buildLog) }
    return $jar.FullName
}

# ============================== 实例启停 ==============================

function Set-ProbeEnv {
    param([hashtable]$vars)
    foreach ($k in $vars.Keys) {
        if (-not $script:EnvBackup.ContainsKey($k)) {
            $script:EnvBackup[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
        }
        [Environment]::SetEnvironmentVariable($k, [string]$vars[$k], 'Process')
    }
}

function Restore-ProbeEnv {
    foreach ($k in $script:EnvBackup.Keys) {
        [Environment]::SetEnvironmentVariable($k, $script:EnvBackup[$k], 'Process')
    }
    $script:EnvBackup = @{}
}

function Start-ProbeServer {
    param([string]$Jar)

    $runDir = Join-Path $script:OutDir 'server-run'
    $dataDir = Join-Path $runDir 'data'
    $invoiceDir = Join-Path $runDir 'invoices'
    $photoDir = Join-Path $runDir 'photos'
    foreach ($d in @($dataDir, $invoiceDir, $photoDir)) {
        if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
    }
    $script:RunDir = $runDir

    $dataSlash = $dataDir.Replace('\', '/')
    $invoiceSlash = $invoiceDir.Replace('\', '/')
    $photoSlash = $photoDir.Replace('\', '/')

    # 每次运行生成一次性 JWT_SECRET（Base64，解码后 48 字节），不复用仓库 .env.local
    $jwtBytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($jwtBytes) } finally { $rng.Dispose() }
    $jwtSecret = [Convert]::ToBase64String($jwtBytes)

    # 环境变量名全部以代码实际读取为准：
    #   AMAP_KEY            -> travel.amap.key（AmapProperties）
    #   JWT_SECRET          -> travel.jwt.secret
    #   RATE_LIMIT_ENABLED  -> travel.rate-limit.enabled
    #   LLM_ENABLED         -> travel.llm.enabled（关掉，走规则规划，避免需要 LLM_API_KEY）
    #   WEATHER_REAL        -> travel.weather.real（关掉，避免外呼天气）
    #   MCP_ENABLED         -> travel.mcp.enabled（关掉，确保本次只验证 REST Key，不混入 MCP）
    #   SPRING_DATASOURCE_URL / SERVER_PORT / INVOICE_DIR / PHOTOS_DIR -> 隔离实例与数据落盘
    $vars = @{
        'AMAP_KEY'              = $script:AmapKey
        # MCP 子进程专用变量（与 AMAP_KEY 分开注入）；本脚本 MCP_ENABLED=false，因此本次不生效，
        # 仅保证环境变量口径与 application.yml 的 travel.mcp.servers[].env 一致。
        'AMAP_MAPS_API_KEY'     = $script:AmapKey
        'JWT_SECRET'            = $jwtSecret
        'RATE_LIMIT_ENABLED'    = 'false'
        'LLM_ENABLED'           = 'false'
        'WEATHER_REAL'          = 'false'
        'MCP_ENABLED'           = 'false'
        'DEMO_ACCOUNT'          = 'true'
        'SERVER_PORT'           = [string]$Port
        'SPRING_DATASOURCE_URL' = ('jdbc:h2:file:' + $dataSlash + '/travel-agent;MODE=MySQL;AUTO_SERVER=TRUE')
        'INVOICE_DIR'           = $invoiceSlash
        'PHOTOS_DIR'            = $photoSlash
    }
    Set-ProbeEnv $vars

    $script:ServerStdout = Join-Path $script:OutDir 'server-stdout.log'
    $script:ServerStderr = Join-Path $script:OutDir 'server-stderr.log'
    if (Test-Path -LiteralPath $script:ServerStdout) { Remove-Item -LiteralPath $script:ServerStdout -Force }
    if (Test-Path -LiteralPath $script:ServerStderr) { Remove-Item -LiteralPath $script:ServerStderr -Force }

    $jargs = @('-Dfile.encoding=UTF-8', '-jar', ('"' + $Jar + '"'))
    Write-Info ('启动：java ' + ($jargs -join ' '))
    Write-Info ('工作目录：' + $script:ServerDir)
    Write-Info ('独立 H2 库：' + $dataSlash + '/travel-agent.mv.db')
    Write-Info ('端口：' + $Port + '；AMAP_KEY 已注入（指纹 ' + $script:KeyFingerprint + '）')

    $script:StartedProc = Start-Process -FilePath 'java' -ArgumentList $jargs `
        -WorkingDirectory $script:ServerDir `
        -RedirectStandardOutput $script:ServerStdout `
        -RedirectStandardError $script:ServerStderr `
        -PassThru -WindowStyle Hidden
    if (-not $script:StartedProc) { throw 'java 进程启动失败（Start-Process 未返回进程对象）' }
    $script:ServerPid = $script:StartedProc.Id
    Write-Info ('后端进程 PID = ' + $script:ServerPid + '（脚本只会在结束时停止该 PID 及其子树）')
}

function Stop-ProbeServer {
    if ($SkipStart) {
        $script:CleanupNote = '未启动进程（-SkipStart），未做任何启停'
        return
    }
    if (-not $script:StartedProc) {
        $script:CleanupNote = '未启动进程或启动失败，无需清理'
        return
    }
    $serverPid = $script:ServerPid
    $childPid = $script:ServerChildPid
    Write-Step 9 ('停止本脚本启动的实例（PID ' + $serverPid + '）')
    $killed = $false
    try {
        $script:StartedProc.Refresh()
        if (-not $script:StartedProc.HasExited) {
            & taskkill.exe /PID $serverPid /T /F 2>&1 | Out-Null
            $killed = $true
        }
    } catch {
        Write-Warn ('taskkill 失败：' + $_.Exception.Message)
    }
    # Windows 上 PATH 里的 java 可能是 javapath 启动器（它会再拉真 JVM 子进程）。
    # /T 已覆盖子树；这里再针对「实际监听端口的子 PID」兜底一次，仍只动本脚本启动的进程。
    if ($childPid -gt 0 -and $childPid -ne $serverPid) {
        for ($i = 0; $i -lt 12; $i++) {
            if (-not (Get-Process -Id $childPid -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Milliseconds 250
        }
        if (Get-Process -Id $childPid -ErrorAction SilentlyContinue) {
            try {
                & taskkill.exe /PID $childPid /T /F 2>&1 | Out-Null
                $killed = $true
            } catch { }
        }
    }
    # 兜底：只针对已记录的 PID，绝不按进程名扫描
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $script:StartedProc.Refresh()
            if ($script:StartedProc.HasExited) { break }
        } catch { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $script:StartedProc.HasExited) {
        try { Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue } catch { }
    }
    Start-Sleep -Milliseconds 300
    $portOpen = Test-PortOpen $Port
    $stillRunning = $false
    try {
        $script:StartedProc.Refresh()
        $stillRunning = -not $script:StartedProc.HasExited
    } catch {
        $stillRunning = $false
    }
    $childAlive = $false
    if ($childPid -gt 0 -and $childPid -ne $serverPid) {
        $childAlive = [bool](Get-Process -Id $childPid -ErrorAction SilentlyContinue)
    }
    $script:CleanupNote = '已停止本脚本启动的进程（PID ' + $serverPid + '）'
    if ($childPid -gt 0 -and $childPid -ne $serverPid) {
        $script:CleanupNote = $script:CleanupNote + '，实际监听 PID ' + $childPid + ' 存活=' + $childAlive
    }
    $script:CleanupNote = $script:CleanupNote + '，端口 ' + $Port + ' 监听=' + $portOpen
    if ($stillRunning -or $childAlive -or $portOpen) {
        Write-Warn ('仍有进程/端口存活（PID ' + $serverPid + '/' + $childPid + '，端口监听=' + $portOpen + '），请检查 cleanup.txt（脚本不会误杀其它 java 进程）')
    } else {
        Write-Info ('已停止 PID ' + $serverPid + '（只停本脚本启动的进程树）')
    }
    $cleanupFile = Join-Path $script:OutDir 'cleanup.txt'
    $lines = @(
        'pid=' + $serverPid,
        'listener_child_pid=' + $childPid,
        'taskkill_called=' + $killed,
        'port_open_after_stop=' + $portOpen,
        'process_alive_after_stop=' + $stillRunning,
        'listener_child_alive_after_stop=' + $childAlive,
        'note=只停止本脚本记录的 PID 及其子树，未枚举/未杀其它 java 进程'
    )
    Write-Utf8NoBom $cleanupFile (($lines -join "`r`n") + "`r`n")
}

function Wait-ServerHealth([int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $lastErr = ''
    while ((Get-Date) -lt $deadline) {
        if ($script:StartedProc) {
            try {
                $script:StartedProc.Refresh()
                if ($script:StartedProc.HasExited) {
                    return [pscustomobject]@{ result = 'exited'; detail = ('进程已退出，exitCode=' + $script:StartedProc.ExitCode) }
                }
            } catch { }
        }
        $out = Join-Path $script:TmpDir 'health.out'
        $r = Invoke-CurlRequest -Method 'GET' -Url ($script:BaseUrl + '/actuator/health') -MaxTime 3 -OutFile $out
        if ($r.http -eq 200 -and $r.body -match '"status"\s*:\s*"UP"') {
            return [pscustomobject]@{ result = 'up'; detail = '/actuator/health = UP' }
        }
        if ($r.http -ge 0) { $lastErr = ('HTTP ' + $r.http) }
        Start-Sleep -Seconds 2
    }
    return [pscustomobject]@{ result = 'timeout'; detail = ('等待 ' + $timeoutSec + ' 秒仍未 UP（最后状态：' + $lastErr + '）') }
}

# ============================== 验证步骤 ==============================

function Invoke-StepStatus {
    Write-Step 1 '系统能力自检：GET /api/system/status'
    $out = Join-Path $script:OutDir '01-system-status.json'
    $r = Invoke-CurlRequest -Method 'GET' -Url ($script:BaseUrl + '/api/system/status') -CookieJar $script:CookieJar -MaxTime 30 -OutFile $out
    if ($r.http -ne 200) {
        Add-Check '1' '系统能力自检' 'FAIL' ('HTTP ' + $r.http + '（期望 200；该端点需要登录，见第 2 步）')
        return $null
    }
    Write-Utf8NoBom $out $r.body
    $j = ConvertFrom-JsonSafe $r.body
    if (-not $j -or -not $j.data) {
        Add-Check '1' '系统能力自检' 'FAIL' '响应不是预期的 ApiResponse JSON'
        return $null
    }
    $d = $j.data
    Write-Info ('llmConfigured=' + $d.llmConfigured + '  amapConfigured=' + $d.amapConfigured + '  geoProvider=' + $d.geoProvider)
    Write-Info ('routeProvider=' + $d.routeProvider + '  routeHint=' + $d.routeHint)
    $ok = ($d.amapConfigured -eq $true) -and ($d.routeProvider -eq 'amap')
    if ($ok) {
        Add-Check '1' '系统能力自检' 'PASS' ('amapConfigured=true, geoProvider=' + $d.geoProvider + ', routeProvider=amap（配置层面已就绪；Key 是否有效由第 3/4 步证明）')
    } else {
        $detail = 'amapConfigured=' + $d.amapConfigured + ', routeProvider=' + $d.routeProvider
        Add-Check '1' '系统能力自检' 'FAIL' ($detail + ' —— 后端没读到有效的 AMAP_KEY（-SkipStart 时旧实例可能未带 Key；空 Key 或 MCP 关闭时 routeProvider 必为 estimated）')
    }
    return $d
}

function Invoke-StepLogin {
    # 注意：GET /api/system/status 不在 SecurityConfig 的 publicPaths 里，必须登录后才能访问，
    # 因此脚本先执行第 2 步登录，再执行第 1 步自检；编号仍按验证项编号。
    Write-StepTitle '[2] demo 登录：POST /api/auth/login（curl.exe + UTF-8 body 文件；先于第 1 步执行，因为 status 需要认证）'
    $bodyFile = New-JsonBodyFile 'request-login.json' '{"username":"demo","password":"demo123"}'
    $out = Join-Path $script:OutDir '02-login.json'
    $r = Invoke-CurlRequest -Method 'POST' -Url ($script:BaseUrl + '/api/auth/login') -BodyFile $bodyFile -CookieJar $script:CookieJar -MaxTime 30 -OutFile $out
    if ($r.http -ne 200) {
        $snippet = ''
        if ($r.body) { $snippet = $r.body.Substring(0, [Math]::Min(200, $r.body.Length)) }
        Add-Check '2' 'demo 登录' 'FAIL' ('HTTP ' + $r.http + ' ' + $snippet)
        return $false
    }
    $j = ConvertFrom-JsonSafe $r.body
    # 证据里脱敏 token（cookie jar 只放临时目录，结束时删除）
    $sanitized = $r.body
    $m = [regex]::Match($r.body, '"token"\s*:\s*"[^"]*"')
    if ($m.Success) { $sanitized = $r.body.Replace($m.Value, '"token":"<redacted>"') }
    Write-Utf8NoBom $out $sanitized
    if (-not $j -or -not $j.data) {
        Add-Check '2' 'demo 登录' 'FAIL' 'HTTP 200 但响应体不是预期的登录 JSON'
        return $false
    }
    Write-Info ('username=' + $j.data.username + '  id=' + $j.data.id + '  nickname=' + $j.data.nickname)
    Add-Check '2' 'demo 登录' 'PASS' ('username=' + $j.data.username + '，Cookie 已写入临时 jar（未落证据目录）')
    return $true
}

function Invoke-StepRouteLegs {
    Write-Step 3 '规划两日游 -> 确认 -> GET /api/trips/{id}/route-legs'
    $message = '帮我规划杭州两日游，预算2000元，2个人'
    $chatJson = '{"message":"' + $message + '"}'
    $chatBody = New-JsonBodyFile 'request-plan.json' $chatJson
    $planOut = Join-Path $script:OutDir '03-plan-response.json'
    Write-Info ('规划请求：' + $message)
    $r = Invoke-CurlRequest -Method 'POST' -Url ($script:BaseUrl + '/api/agent/chat') -BodyFile $chatBody -CookieJar $script:CookieJar -MaxTime 180 -OutFile $planOut
    if ($r.http -ne 200) {
        Add-Check '3' '两日游规划与路线段' 'FAIL' ('POST /api/agent/chat HTTP ' + $r.http + ' ' + $r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
        return
    }
    Write-Utf8NoBom $planOut $r.body
    $plan = ConvertFrom-JsonSafe $r.body
    if (-not $plan -or -not $plan.data) {
        Add-Check '3' '两日游规划与路线段' 'FAIL' '规划响应不是预期的 ApiResponse JSON'
        return
    }
    $data = $plan.data
    Write-Info ('intent=' + $data.intent + '  status=' + $data.status)
    if ($data.message) {
        $msgLine = ([string]$data.message).Replace("`r", ' ').Replace("`n", ' ')
        Write-Info ('message=' + $msgLine.Substring(0, [Math]::Min(180, $msgLine.Length)))
    }

    if ($data.status -eq 'NEED_CONFIRMATION') {
        $planId = $null
        if ($data.confirmation -and $data.confirmation.planId) { $planId = $data.confirmation.planId }
        if (-not $planId) { $planId = $data.planId }
        if (-not $planId) {
            Add-Check '3' '两日游规划与路线段' 'FAIL' '需要确认但响应里没有 planId（confirmation.planId / planId 均为空）'
            return
        }
        $script:PlanId = $planId
        Write-Info ('待确认方案 planId=' + $planId + '，提交确认…')
        $confirmJson = (@{ planId = $planId; acknowledgeIssues = $true } | ConvertTo-Json -Compress)
        $confirmBody = New-JsonBodyFile 'request-confirm.json' $confirmJson
        $confirmOut = Join-Path $script:OutDir '04-confirm-response.json'
        $c = Invoke-CurlRequest -Method 'POST' -Url ($script:BaseUrl + '/api/agent/confirm') -BodyFile $confirmBody -CookieJar $script:CookieJar -MaxTime 120 -OutFile $confirmOut
        if ($c.http -ne 200) {
            Add-Check '3' '两日游规划与路线段' 'FAIL' ('POST /api/agent/confirm HTTP ' + $c.http + ' ' + $c.body.Substring(0, [Math]::Min(200, $c.body.Length)))
            return
        }
        Write-Utf8NoBom $confirmOut $c.body
        $confirmed = ConvertFrom-JsonSafe $c.body
        if (-not $confirmed -or -not $confirmed.data) {
            Add-Check '3' '两日游规划与路线段' 'FAIL' '确认响应不是预期的 ApiResponse JSON'
            return
        }
        Write-Info ('确认结果：status=' + $confirmed.data.status + '  tripId=' + $confirmed.data.tripId)
        if ($confirmed.data.message) {
            $cm = ([string]$confirmed.data.message).Replace("`r", ' ').Replace("`n", ' ')
            Write-Info ('确认 message=' + $cm.Substring(0, [Math]::Min(180, $cm.Length)))
        }
        if ($confirmed.data.tripId) {
            $script:TripId = $confirmed.data.tripId
        } elseif ($data.tripId) {
            $script:TripId = $data.tripId
        }
    } else {
        if ($data.tripId) { $script:TripId = $data.tripId }
        Write-Info ('规划直接返回 status=' + $data.status + '，不经过确认门')
    }

    if (-not $script:TripId) {
        $why = ''
        if ($data.status) { $why = '（规划 status=' + $data.status + '）' }
        Add-Check '3' '两日游规划与路线段' 'FAIL' ('没有拿到 tripId，无法查询 route-legs' + $why + '；planId=' + $script:PlanId)
        return
    }

    $legsOut = Join-Path $script:OutDir '05-route-legs.json'
    $l = Invoke-CurlRequest -Method 'GET' -Url ($script:BaseUrl + '/api/trips/' + $script:TripId + '/route-legs') -CookieJar $script:CookieJar -MaxTime 180 -OutFile $legsOut
    if ($l.http -ne 200) {
        Add-Check '3' '两日游规划与路线段' 'FAIL' ('GET /api/trips/' + $script:TripId + '/route-legs HTTP ' + $l.http + ' ' + $l.body.Substring(0, [Math]::Min(200, $l.body.Length)))
        return
    }
    Write-Utf8NoBom $legsOut $l.body
    $legsJson = ConvertFrom-JsonSafe $l.body
    if (-not $legsJson) {
        Add-Check '3' '两日游规划与路线段' 'FAIL' 'route-legs 响应不是合法 JSON'
        return
    }
    $legs = @($legsJson.data)
    $total = 0; $amap = 0; $estimated = 0; $other = 0
    $sampleAmap = $null
    $badCoord = 0
    foreach ($leg in $legs) {
        if ($null -eq $leg) { continue }
        $total++
        $src = [string]$leg.source
        if ($src -eq 'amap') {
            $amap++
            if (-not $sampleAmap) { $sampleAmap = $leg }
        } elseif ($src -eq 'estimated') {
            $estimated++
        } else {
            $other++
        }
        foreach ($pair in @(@($leg.fromLatitude, $leg.fromLongitude), @($leg.toLatitude, $leg.toLongitude))) {
            if ($null -ne $pair[0] -and $null -ne $pair[1]) {
                if (-not (Test-ChinaBbox ([double]$pair[0]) ([double]$pair[1]))) { $badCoord++ }
            }
        }
    }
    $script:RouteLegStats = [pscustomobject]@{
        tripId = $script:TripId
        total  = $total
        amap   = $amap
        estimated = $estimated
        other  = $other
        badCoordinateCount = $badCoord
        sampleAmapLeg = $sampleAmap
    }
    Write-Info ('段数打点：总段数=' + $total + '  amap=' + $amap + '  estimated=' + $estimated + '  其它=' + $other)
    if ($sampleAmap) {
        Write-Info ('首段 amap：' + $sampleAmap.fromTitle + ' -> ' + $sampleAmap.toTitle + '  ' + $sampleAmap.distanceKm + 'km / ' + $sampleAmap.driveMinutes + 'min')
    }
    if ($total -eq 0) {
        Add-Check '3' '两日游规划与路线段' 'FAIL' 'route-legs 返回 0 段（行程条目没有坐标，或计划未落库）'
        return
    }
    if ($amap -gt 0) {
        Add-Check '3' '两日游规划与路线段' 'PASS' ('总段数=' + $total + '，amap=' + $amap + '，estimated=' + $estimated + '（已观察到 source=amap 的真实路径）')
    } else {
        $hint = '全部 ' + $total + ' 段为 estimated —— 高德 REST 调用未成功，后端已静默降级。排查顺序：' +
                '① Key 是否「Web 服务」类型（不是 JS API Key）；② Key 是否有效/已开通「Web 服务」；' +
                '③ 高德控制台是否配置了 IP/域名白名单且含本机出口 IP；④ 是否配额/日调用量超限；' +
                '⑤ 本机能否访问 restapi.amap.com；⑥ 后端是否真的读到了 AMAP_KEY（看第 1 步 amapConfigured）。'
        Add-Check '3' '两日游规划与路线段' 'FAIL' ('总段数=' + $total + '，amap=0，estimated=' + $estimated + '。' + $hint)
    }
}

function Test-ChinaBbox([double]$lat, [double]$lng) {
    # 中国境内粗包围盒（GCJ-02 / WGS-84 都远小于该盒；用于排除 0,0、经纬度写反等明显错误）
    return ($lat -ge 3.0 -and $lat -le 54.5 -and $lng -ge 73.0 -and $lng -le 136.0)
}

function Invoke-StepPoi {
    Write-Step 4 'POI / 地理编码验证'

    # 4a) 后端对外接口：经代码核对，当前没有
    Write-Info '4a) 后端对外 REST 接口：经核对当前没有（如实记 SKIP，不伪造调用）'
    $note4a = '后端没有暴露 POI 搜索 / 地理编码的 REST 端点：' +
              'AmapDataService.searchPoi / AmapGeocodeClient 只被规划器等内部组件调用（或不接线），' +
              '没有对应 Controller。因此无法通过 HTTP 直接要「POI 列表」。' +
              '本节 4b 改为直连高德官方 REST（与 AmapDataService 同一端点）验证 Key 本身。'
    Add-Check '4a' '后端 POI/地理编码接口' 'SKIP' ('当前没有该接口 —— ' + $note4a)
    Write-Utf8NoBom (Join-Path $script:OutDir '06a-backend-poi-endpoint.txt') ($note4a + "`r`n")

    if ($SkipRestDiag) {
        Add-Check '4b' '直连高德 REST 诊断' 'SKIP' '-SkipRestDiag 已指定，未发起直连诊断'
        return
    }

    # 4b) 直连高德 REST 诊断
    Write-Info '4b) 直连高德 REST 诊断（/v3/place/text + /v3/direction/driving，与 AmapDataService 同端点）'
    $kw = [System.Uri]::EscapeDataString('西湖')
    $city = [System.Uri]::EscapeDataString('杭州')
    $poiUrl = 'https://restapi.amap.com/v3/place/text?key=' + [System.Uri]::EscapeDataString($script:AmapKey) +
              '&keywords=' + $kw + '&city=' + $city + '&offset=3&extensions=base'
    $poiOut = Join-Path $script:OutDir '06-direct-rest-poi.json'
    $pr = Invoke-CurlRequest -Method 'GET' -Url $poiUrl -MaxTime 30 -OutFile $poiOut
    if ($pr.http -ne 200) {
        Add-Check '4b' '直连高德 REST 诊断' 'FAIL' ('网络不可达：HTTP ' + $pr.http + '（curlExit=' + $pr.curlExit + '）')
        return
    }
    Write-Utf8NoBom $poiOut $pr.body
    $poi = ConvertFrom-JsonSafe $pr.body
    if (-not $poi) {
        Add-Check '4b' '直连高德 REST 诊断' 'FAIL' '高德返回的不是 JSON：' + $pr.body.Substring(0, [Math]::Min(200, $pr.body.Length))
        return
    }
    $status = [string]$poi.status
    $info = [string]$poi.info
    $infocode = [string]$poi.infocode
    $pois = @()
    if ($poi.pois) { $pois = @($poi.pois) }
    Write-Info ('place/text: status=' + $status + '  info=' + $info + '  infocode=' + $infocode + '  条数=' + $pois.Count)
    if ($status -ne '1') {
        Add-Check '4b' '直连高德 REST 诊断' 'FAIL' ('高德拒绝：status=' + $status + ' info=' + $info + ' infocode=' + $infocode + '（INVALID_USER_KEY=Key 无效/类型不对；DAILY_QUERY_OVER_LIMIT=配额；USERKEY_PLAT_NOMATCH=类型/平台不匹配；USER_IP_ALIVE=IP 白名单）')
        return
    }
    if ($pois.Count -eq 0) {
        Add-Check '4b' '直连高德 REST 诊断' 'FAIL' 'status=1 但返回 0 条 POI（Key 有效但结果为空，检查 keywords/city 参数）'
        return
    }
    $badCoord = 0
    $samples = New-Object System.Collections.ArrayList
    foreach ($p in $pois) {
        $loc = [string]$p.location
        if (-not $loc) { $badCoord++; continue }
        $parts = $loc.Split(',')
        if ($parts.Length -ne 2) { $badCoord++; continue }
        $lng = 0.0; $lat = 0.0
        $okLng = [double]::TryParse($parts[0], [ref]$lng)
        $okLat = [double]::TryParse($parts[1], [ref]$lat)
        if (-not $okLng -or -not $okLat -or -not (Test-ChinaBbox $lat $lng)) { $badCoord++; continue }
        [void]$samples.Add(($p.name + ' (' + $lat + ',' + $lng + ')'))
    }
    Write-Info ('坐标样例（GCJ-02，后端会反算为 WGS-84）：' + (($samples | Select-Object -First 3) -join ' | '))
    if ($badCoord -gt 0) {
        Add-Check '4b' '直连高德 REST 诊断' 'FAIL' ('' + $pois.Count + ' 条中有 ' + $badCoord + ' 条坐标不在中国境内合理范围（GCJ-02 校验）')
        return
    }

    # 路径直连诊断：固定杭州附近两个 GCJ-02 坐标（与后端 AmapDataService 同端点）
    $routeOut = Join-Path $script:OutDir '07-direct-rest-direction.json'
    $dirUrl = 'https://restapi.amap.com/v3/direction/driving?key=' + [System.Uri]::EscapeDataString($script:AmapKey) +
              '&origin=120.155100,30.274100&destination=120.149000,30.243100'
    $dr = Invoke-CurlRequest -Method 'GET' -Url $dirUrl -MaxTime 30 -OutFile $routeOut
    if ($dr.http -eq 200) {
        Write-Utf8NoBom $routeOut $dr.body
        $dir = ConvertFrom-JsonSafe $dr.body
        if ($dir) {
            $dstatus = [string]$dir.status
            $dinfo = [string]$dir.info
            if ($dstatus -eq '1' -and $dir.route -and @($dir.route.paths).Count -gt 0) {
                $p0 = @($dir.route.paths)[0]
                Write-Info ('direction/driving: distance=' + $p0.distance + 'm duration=' + $p0.duration + 's')
            } else {
                Write-Info ('direction/driving: status=' + $dstatus + ' info=' + $dinfo + '（路径接口单独失败）')
            }
        }
    } else {
        Write-Info ('direction/driving: HTTP ' + $dr.http + '（网络失败，已跳过）')
    }

    Add-Check '4b' '直连高德 REST 诊断' 'PASS' ('place/text status=1，返回 ' + $pois.Count + ' 条 POI，坐标均在中国境内合理范围（GCJ-02）；direction/driving 见 07 号证据')
}

# ============================== 汇总输出 ==============================

function Get-OverallStatus {
    if ($script:Checks.Count -eq 0) { return 'FAIL' }
    $required = @('0', '1', '2', '3')
    if (-not $SkipRestDiag) { $required += '4b' }
    foreach ($c in $script:Checks) {
        if ($required -contains $c.id) {
            if ($c.status -eq 'FAIL') { return 'FAIL' }
            if ($c.status -eq 'SKIP') { return 'FAIL' }
        }
    }
    return 'PASS'
}

# 汇总表按验证项编号排序（执行顺序上登录在第 1 步之前，表里仍按 0/1/2/3/4a/4b 展示）
function Get-SortedChecks {
    $order = @('0', '1', '2', '3', '4a', '4b')
    $ranked = New-Object System.Collections.ArrayList
    foreach ($c in $script:Checks) {
        $rank = $order.Length
        for ($i = 0; $i -lt $order.Length; $i++) {
            if ($order[$i] -eq $c.id) { $rank = $i; break }
        }
        [void]$ranked.Add([pscustomobject]@{ rank = $rank; check = $c })
    }
    return @($ranked | Sort-Object rank | ForEach-Object { $_.check })
}

function Write-Summary {
    $overall = Get-OverallStatus
    $instanceLine = '本脚本启动（PID ' + $script:ServerPid + '）'
    if ($SkipStart) { $instanceLine = '复用已启动实例（-SkipStart，未做启停）' }

    $rows = New-Object System.Collections.ArrayList
    [void]$rows.Add('| # | 检查项 | 结果 | 关键证据 |')
    [void]$rows.Add('| --- | --- | --- | --- |')
    $sortedChecks = Get-SortedChecks
    foreach ($c in $sortedChecks) {
        $detail = [string]$c.detail
        $detail = $detail.Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
        [void]$rows.Add('| ' + $c.id + ' | ' + $c.name + ' | **' + $c.status + '** | ' + $detail + ' |')
    }

    $statLines = @()
    if ($script:RouteLegStats) {
        $s = $script:RouteLegStats
        $statLines += '- tripId=' + $s.tripId
        $statLines += '- route-legs 总段数=' + $s.total + '，source=amap 段数=' + $s.amap + '，source=estimated 段数=' + $s.estimated + '，其它=' + $s.other
        if ($s.sampleAmapLeg) {
            $statLines += '- 首段 amap：' + $s.sampleAmapLeg.fromTitle + ' -> ' + $s.sampleAmapLeg.toTitle + '，' + $s.sampleAmapLeg.distanceKm + 'km / 驾车 ' + $s.sampleAmapLeg.driveMinutes + ' 分钟'
        }
    } else {
        $statLines += '- route-legs 未取得（见第 3 步结果）'
    }

    $trouble = @()
    if ($overall -ne 'PASS') {
        $trouble += @(
            '## 失败排查（按顺序）',
            '',
            '1. **Key 类型错**：高德控制台必须是「Web 服务」Key。JS API / Web 端（VITE_AMAP_KEY）Key 用在后端会报 `USERKEY_PLAT_NOMATCH` / `INVALID_USER_KEY`。',
            '2. **Key 无效或未开通**：高德返回 `INVALID_USER_KEY`（infocode 10001）——复制错、被删除、或该 Key 未启用「Web 服务」平台。',
            '3. **白名单 / IP 限制**：Key 配了 IP 白名单而本机出口 IP 不在列表时，高德返回 `USER_IP_ALIVE` 一类错误；联调时先临时放开或加入出口 IP。',
            '4. **配额 / 日调用量**：返回 `DAILY_QUERY_OVER_LIMIT` 等，等配额重置或在控制台提额。',
            '5. **后端没读到 Key**：第 1 步 `amapConfigured=false` / `routeProvider=estimated` 说明 `AMAP_KEY` 没进到进程；检查启动环境或 -SkipStart 复用实例的配置。',
            '6. **网络不可达**：本机访问不了 `restapi.amap.com`（代理/防火墙），后端会静默降级为 estimated。',
            '7. **坐标口径**：高德返回 **GCJ-02**，本项目对外统一 **WGS-84**（`CoordinateTransform` 反算）；拿高德返回值直接与 GPS/WGS-84 比会差 300~600 米，不是 bug。',
            ''
        )
    }

    $files = @(
        '01-system-status.json', '02-login.json', '03-plan-response.json', '04-confirm-response.json',
        '05-route-legs.json', '06a-backend-poi-endpoint.txt', '06-direct-rest-poi.json',
        '07-direct-rest-direction.json', 'checks.json', 'run-meta.json', 'server-stdout.log', 'server-stderr.log'
    ) | Where-Object { Test-Path -LiteralPath (Join-Path $script:OutDir $_) }

    $md = New-Object System.Collections.ArrayList
    [void]$md.Add('# 高德 Web 服务 Key 联调证据')
    [void]$md.Add('')
    [void]$md.Add('- 生成时间：' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    [void]$md.Add('- 仓库提交：' + $script:GitCommit)
    [void]$md.Add('- 实例：' + $instanceLine + '；端口 ' + $Port)
    [void]$md.Add('- Key 指纹（SHA-256 前 8 位，不落明文）：`' + $script:KeyFingerprint + '`')
    [void]$md.Add('- 证据目录：`' + $script:OutDir + '`')
    [void]$md.Add('- 进程清理：' + $script:CleanupNote)
    [void]$md.Add('')
    [void]$md.Add('## 结论：**' + $overall + '**')
    [void]$md.Add('')
    foreach ($line in $rows) { [void]$md.Add($line) }
    [void]$md.Add('')
    [void]$md.Add('## 关键数值')
    [void]$md.Add('')
    foreach ($line in $statLines) { [void]$md.Add($line) }
    [void]$md.Add('')
    foreach ($line in $trouble) { [void]$md.Add($line) }
    [void]$md.Add('## 证据文件')
    [void]$md.Add('')
    foreach ($f in $files) { [void]$md.Add('- `' + $f + '`') }
    [void]$md.Add('')
    [void]$md.Add('> 本目录为联调证据（体积很小），不要提交 git；重跑一次会生成新的时间戳目录。')
    $mdText = ($md -join "`r`n") + "`r`n"
    Write-Utf8NoBom (Join-Path $script:OutDir 'summary.md') $mdText
    # 需求原文写作 OutDir\summmary.md（三个 m），同时写一份同内容别名，避免路径对不上
    Write-Utf8NoBom (Join-Path $script:OutDir 'summmary.md') $mdText

    $checksJson = @{
        overall  = $overall
        startedAt = $script:StartedAt.ToString('yyyy-MM-dd HH:mm:ss')
        port     = $Port
        skipStart = [bool]$SkipStart
        dryRun   = [bool]$DryRun
        keyFingerprint = $script:KeyFingerprint
        serverPid = $script:ServerPid
        cleanup  = $script:CleanupNote
        checks   = @($sortedChecks)
        routeLegs = $script:RouteLegStats
    }
    $null = Write-EvidenceJson 'checks.json' $checksJson

    Write-Host ''
    Write-Host ('==== 结论：' + $overall + ' ====') -ForegroundColor ($(if ($overall -eq 'PASS') { 'Green' } else { 'Red' }))
    foreach ($c in $sortedChecks) {
        Write-Host ('  [' + $c.status + '] ' + $c.id + ' ' + $c.name) -ForegroundColor ($(if ($c.status -eq 'PASS') { 'Green' } elseif ($c.status -eq 'FAIL') { 'Red' } else { 'Yellow' }))
    }
    Write-Host ''
    Write-Host ('证据目录：' + $script:OutDir)
    Write-Host ('汇总报告：' + (Join-Path $script:OutDir 'summary.md'))
    return $overall
}

# ============================== 主流程 ==============================

$script:GitCommit = 'unknown'
try {
    # 主流程包在脚本块里：内部 return 只退出本块，仍会走 finally 与最后的 exit <code>，
    # 不会把「主动中止」变成退出码 0。
    & {
    Assert-RepoLayout
    $AmapKey = ([string]$AmapKey).Trim()
    $script:KeyFingerprint = Get-KeyFingerprint $AmapKey

    Initialize-OutDir

    # 仓库提交（缺 git 时不影响主流程）
    try {
        $gc = (& git -C $script:RepoRoot rev-parse --short HEAD 2>$null)
        if ($gc) { $script:GitCommit = ([string]$gc).Trim() }
    } catch { }

    $script:MvnVersion = ''
    try {
        if (Get-Command mvn -ErrorAction SilentlyContinue) {
            $script:MvnVersion = ([string]((& cmd /c 'mvn -v 2>&1' | Select-Object -First 1))).Trim()
        }
    } catch { }

    $shapeError = Test-KeyShape $AmapKey
    $placeholder = Get-SuspectedPlaceholder $AmapKey

    # ---------- DryRun：只校验参数与本地配置 ----------
    if ($DryRun) {
        Write-Host '== DRY RUN：只校验参数与配置，不启动服务、不发起联调请求 ==' -ForegroundColor Cyan
        $problems = New-Object System.Collections.ArrayList
        if ($shapeError) { [void]$problems.Add('AmapKey：' + $shapeError) }
        if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { [void]$problems.Add('PATH 里没有 curl.exe（Windows 10 1803+ 自带）') }
        if (-not (Get-Command java -ErrorAction SilentlyContinue)) { [void]$problems.Add('PATH 里没有 java（需要 JDK 17+）') }
        if (-not $SkipStart) {
            if (Test-PortOpen $Port) { [void]$problems.Add('端口 ' + $Port + ' 已被占用；换 -Port 或加 -SkipStart 复用该实例') }
            $jarFound = $false
            if ($JarPath) {
                $jarFound = Test-Path -LiteralPath $JarPath
                if (-not $jarFound) { [void]$problems.Add('-JarPath 不存在：' + $JarPath) }
            } else {
                $cand = Get-ChildItem -LiteralPath (Join-Path $script:ServerDir 'target') -Filter 'travel-agent-server-*.jar' -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
                $jarFound = ($null -ne $cand)
            }
            if (-not $jarFound -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
                [void]$problems.Add('没有后端 jar 且 PATH 里没有 mvn：无法启动实例')
            }
        } else {
            if (-not (Test-PortOpen $Port)) { [void]$problems.Add('-SkipStart 指定复用实例，但 ' + $Port + ' 端口没有监听') }
            else {
                $h = Invoke-CurlRequest -Method 'GET' -Url ($script:BaseUrl + '/actuator/health') -MaxTime 5
                if ($h.http -ne 200 -or $h.body -notmatch '"status"\s*:\s*"UP"') { [void]$problems.Add('复用实例的 /actuator/health 不是 UP（HTTP ' + $h.http + '）') }
            }
        }
        if ($placeholder) { Write-Warn ('AmapKey 疑似占位值（含 "' + $placeholder + '"）：DryRun 通过，但真实联调大概率失败') }
        if ($shapeError) { Write-Warn ('AmapKey 格式提示：' + $shapeError) }

        $dry = [ordered]@{
            dryRun        = $true
            port          = $Port
            skipStart     = [bool]$SkipStart
            outDir        = $script:OutDir
            keyFingerprint = $script:KeyFingerprint
            keyShapeError = $shapeError
            placeholderHint = $placeholder
            java          = [string]((Get-Command java -ErrorAction SilentlyContinue).Source)
            curl          = [string]((Get-Command curl.exe -ErrorAction SilentlyContinue).Source)
            mvn           = $script:MvnVersion
            problems      = @($problems)
            envVarsInjected = @('AMAP_KEY', 'AMAP_MAPS_API_KEY', 'JWT_SECRET', 'RATE_LIMIT_ENABLED', 'LLM_ENABLED', 'WEATHER_REAL', 'MCP_ENABLED', 'DEMO_ACCOUNT', 'SERVER_PORT', 'SPRING_DATASOURCE_URL', 'INVOICE_DIR', 'PHOTOS_DIR')
            endpoints     = @('/actuator/health', '/api/system/status', '/api/auth/login', '/api/agent/chat', '/api/agent/confirm', '/api/trips/{id}/route-legs')
        }
        $null = Write-EvidenceJson 'dryrun.json' $dry
        $dryLines = @('# DryRun 校验结果', '')
        $dryLines += ('- 端口：' + $Port + '；SkipStart=' + [bool]$SkipStart)
        $dryLines += ('- Key 指纹：' + $script:KeyFingerprint + '；Key 形状：' + $(if ($shapeError) { $shapeError } else { 'OK' }))
        $dryLines += ('- 环境变量注入（仅子进程）：' + ($dry.envVarsInjected -join ', '))
        $dryLines += ('- 待验证端点：' + ($dry.endpoints -join ' '))
        $dryLines += ''
        if ($problems.Count -eq 0) {
            $dryLines += '## 结论：PASS（配置可用于真实联调）'
        } else {
            $dryLines += '## 结论：FAIL（配置有问题）'
            foreach ($p in $problems) { $dryLines += ('- ' + $p) }
        }
        Write-Utf8NoBom (Join-Path $script:OutDir 'summary.md') (($dryLines -join "`r`n") + "`r`n")
        Write-Utf8NoBom (Join-Path $script:OutDir 'summmary.md') (($dryLines -join "`r`n") + "`r`n")
        if ($problems.Count -eq 0) {
            Write-Host ('[PASS] DryRun 配置校验通过；证据：' + (Join-Path $script:OutDir 'dryrun.json')) -ForegroundColor Green
            $script:ExitCode = 0
        } else {
            foreach ($p in $problems) { Write-Host ('[FAIL] ' + $p) -ForegroundColor Red }
            $script:ExitCode = 2
        }
        $script:CleanupNote = 'DryRun：未启动任何进程'
        return
    }

    if ($shapeError) {
        Write-Warn ('AmapKey 格式提示：' + $shapeError + '（继续执行，预期会优雅失败）')
    }
    if ($placeholder) {
        Write-Warn ('AmapKey 疑似占位值（含 "' + $placeholder + '"）：这可用于验证「Key 无效」时的优雅失败路径，但不会 PASS')
    }

    $script:CookieJar = Join-Path $script:TmpDir 'cookies.txt'

    # ---------- 0) 实例 ----------
    Write-Step 0 '实例准备'
    $script:Phase = 'startup'
    if ($SkipStart) {
        Write-Info ('-SkipStart：复用 ' + $script:BaseUrl + ' 上已运行的实例，本脚本不做启停')
        if (-not (Test-PortOpen $Port)) {
            Add-Check '0' '实例准备' 'FAIL' ('-SkipStart 但端口 ' + $Port + ' 没有监听')
            $script:ExitCode = 4
            return
        }
        $script:HealthResult = 'reused'
        Add-Check '0' '实例准备' 'PASS' ('复用已启动实例（端口 ' + $Port + '，未启停任何进程）')
    } else {
        if (Test-PortOpen $Port) {
            Add-Check '0' '实例准备' 'FAIL' ('端口 ' + $Port + ' 已被占用；换 -Port，或加 -SkipStart 复用该实例')
            $script:ExitCode = 4
            return
        }
        $jar = Resolve-ServerJar
        Write-Info ('使用 jar：' + $jar)
        Start-ProbeServer -Jar $jar
        $health = Wait-ServerHealth $StartupTimeoutSec
        $script:HealthResult = $health.result
        Write-Info ('健康检查：' + $health.detail)
        if ($health.result -ne 'up') {
            $hints = Get-LogHints $script:ServerStdout
            foreach ($h in $hints) { Write-Info ('log: ' + $h) }
            Add-Check '0' '实例准备' 'FAIL' ($health.detail + '；日志见 ' + $script:ServerStdout)
            $script:ExitCode = 4
            return
        }
        Add-Check '0' '实例准备' 'PASS' ('独立实例已 UP（PID ' + $script:ServerPid + '，端口 ' + $Port + '，独立 H2 文件库）')
        # javapath 启动器会再拉一个真 JVM：记录实际监听端口的直接子进程，结束时可兜底停止
        $listenerPid = Resolve-ListenerPid $Port
        if ($listenerPid -gt 0 -and $listenerPid -ne $script:ServerPid -and (Test-IsDirectChildOf $listenerPid $script:ServerPid)) {
            $script:ServerChildPid = $listenerPid
            Write-Info ('实际监听进程 PID = ' + $listenerPid + '（PID ' + $script:ServerPid + ' 的直接子进程），结束时会一并停止')
        }
    }

    # ---------- 1~4 验证 ----------
    $script:Phase = 'checks'
    # status 需要认证（publicPaths 不含 /api/system/status），所以先登录再自检
    $loggedIn = Invoke-StepLogin
    if (-not $loggedIn) {
        Add-Check '1' '系统能力自检' 'SKIP' '登录失败，未执行'
        Add-Check '3' '两日游规划与路线段' 'SKIP' '登录失败，未执行'
        Add-Check '4a' '后端 POI/地理编码接口' 'SKIP' '登录失败，未执行'
        Add-Check '4b' '直连高德 REST 诊断' 'SKIP' '登录失败，未执行'
    } else {
        $status = Invoke-StepStatus
        Invoke-StepRouteLegs
        Invoke-StepPoi
    }

    $overall = Get-OverallStatus
    if ($overall -eq 'PASS') {
        $script:ExitCode = 0
    } else {
        $script:ExitCode = 3
    }
    }
} catch {
    $script:ExitCode = 1
    if ($script:Phase -eq 'startup') { $script:ExitCode = 4 }
    Write-Host ''
    Write-Host ('[ERROR] ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ($_.ScriptStackTrace) -ForegroundColor DarkGray
    if ($script:Checks.Count -eq 0) {
        Add-Check '0' '实例准备' 'FAIL' ('脚本异常：' + $_.Exception.Message)
    }
} finally {
    # 清理：只停自己启动的 PID；删除临时 cookie（含 JWT）
    try { Stop-ProbeServer } catch { Write-Warn ('停止实例时出错：' + $_.Exception.Message) }
    Restore-ProbeEnv
    if ($script:CookieJar -and (Test-Path -LiteralPath $script:CookieJar)) {
        Remove-Item -LiteralPath $script:CookieJar -Force -ErrorAction SilentlyContinue
    }
    # 运行元信息
    try {
        if ($script:OutDir -and (Test-Path -LiteralPath $script:OutDir)) {
            $meta = [ordered]@{
                generatedAt    = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
                gitCommit      = $script:GitCommit
                port           = $Port
                skipStart      = [bool]$SkipStart
                dryRun         = [bool]$DryRun
                skipRestDiag   = [bool]$SkipRestDiag
                keyFingerprint = $script:KeyFingerprint
                serverPid      = $script:ServerPid
                listenerChildPid = $script:ServerChildPid
                health         = $script:HealthResult
                cleanup        = $script:CleanupNote
                os             = [System.Environment]::OSVersion.VersionString
                psVersion      = $PSVersionTable.PSVersion.ToString()
                java           = [string]((Get-Command java -ErrorAction SilentlyContinue).Source)
                evidenceDir    = $script:OutDir
            }
            $null = Write-EvidenceJson 'run-meta.json' $meta
        }
    } catch { }
    try {
        if (-not $DryRun -and $script:OutDir -and (Test-Path -LiteralPath $script:OutDir) -and -not (Test-Path -LiteralPath (Join-Path $script:OutDir 'summary.md'))) {
            # 早期失败（端口占用/启动失败）也要有汇总
            $null = Write-Summary
        } elseif (-not $DryRun -and $script:OutDir) {
            $null = Write-Summary
        }
    } catch { Write-Warn ('写汇总失败：' + $_.Exception.Message) }
    if ($script:TmpDir -and (Test-Path -LiteralPath $script:TmpDir)) {
        Remove-Item -LiteralPath $script:TmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

exit $script:ExitCode