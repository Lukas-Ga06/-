# V2.2 正式验收：Docker Compose + 全新 MySQL 8.4
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .e2e\acceptance\mysql-compose-acceptance.ps1 -AmapKey "你的Web服务Key"
#
# 口径：
#   * 从「零卷」开始（down -v），验证 mysql:8.4 全新实例上的 Flyway V1..V18 迁移；
#   * 跑核心链路（登录/规划/确认/追踪/路线段）；
#   * 分别重启 backend / mysql / 整栈，验证重启恢复与数据不丢；
#   * 全程日志落 -EvidenceDir，最后写 summary.md。
param(
    [string]$AmapKey = "",
    [string]$EvidenceDir = "",
    [switch]$SkipBuild,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root
if (-not $EvidenceDir) {
    $EvidenceDir = Join-Path $root ('.evidence\docker-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$summary = New-Object System.Collections.Generic.List[string]
function Say([string]$line) {
    Write-Host $line
    $summary.Add($line)
}
function Save([string]$name, [string]$text) {
    $path = Join-Path $EvidenceDir $name
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}
function Http {
    param([string]$Url, [string]$Method = 'GET', $Body = $null, $Headers = $null)
    $req = @{ Uri = $Url; Method = $Method; TimeoutSec = 30 }
    if ($Headers) { $req.Headers = $Headers }
    if ($Body) {
        # PS 5.1 对字符串 Body 默认按 ISO-8859-1 编码，中文会乱码；显式转 UTF-8 字节
        $req.ContentType = 'application/json; charset=utf-8'
        $req.Body = [System.Text.Encoding]::UTF8.GetBytes([string]$Body)
    }
    return Invoke-RestMethod @req
}

# ── 0. 前置：docker / compose 可用 ─────────────────────────
Say "## 0. 环境"
$dockerVersion = (& docker version --format '{{.Server.Version}}' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "docker 不可用：$dockerVersion" }
$composeVersion = (& docker compose version 2>&1 | Out-String).Trim()
Say "- docker server: $dockerVersion"
Say "- $composeVersion"

$composeArgs = @('-f', 'docker-compose.yml', '-f', '.e2e/acceptance/docker-compose.acceptance.yml')
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { [Convert]::ToBase64String([byte[]](1..48 | ForEach-Object { Get-Random -Maximum 256 })) }
$env:DEMO_ACCOUNT = 'true'
$env:MYSQL_ROOT_PASSWORD = 'travel_root_pw'
$env:MYSQL_PASSWORD = 'travel_pw'
if ($AmapKey) { $env:AMAP_KEY = $AmapKey }

# ── 1. 全新环境：清空卷后 up --build ───────────────────────
Say "`n## 1. 全新安装（down -v → up -d --build）"
& docker compose @composeArgs down -v --remove-orphans 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDir 'compose-down.log') | Out-Null
$upArgs = @('compose') + $composeArgs + @('up', '-d')
if (-not $SkipBuild) { $upArgs += '--build' }
$upStart = Get-Date
& docker @upArgs 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDir 'compose-up.log')
if ($LASTEXITCODE -ne 0) { throw "compose up 失败，见 compose-up.log" }
Say "- compose up 完成，耗时 $([math]::Round(((Get-Date) - $upStart).TotalSeconds,1))s"

# 等健康：后端 healthcheck + web 端口
$deadline = (Get-Date).AddMinutes(6)
$backendHealthy = $false
while ((Get-Date) -lt $deadline) {
    $ps = (& docker compose @composeArgs ps --format json 2>$null | Out-String)
    $ok = $false
    try { $ok = ($ps -match '"Health":"healthy"') } catch {}
    try {
        $h = Http 'http://127.0.0.1:8081/api/system/status'
        $ok = $ok -and ($null -ne $h)
    } catch { $ok = $false }
    if ($ok) { $backendHealthy = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $backendHealthy) {
    & docker compose @composeArgs logs --no-color 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDir 'compose-logs-boot-fail.log')
    throw "服务未在 6 分钟内健康"
}
$bootSeconds = [math]::Round(((Get-Date) - $upStart).TotalSeconds, 1)
Say "- 全栈健康，启动耗时 ${bootSeconds}s；web=http://127.0.0.1:8081"

# ── 2. MySQL 版本 / Flyway 迁移证据 ───────────────────────
Say "`n## 2. MySQL 8.4 与 Flyway 迁移"
$mysqlVersion = (& docker compose @composeArgs exec -T mysql mysql -uroot -ptravel_root_pw -N -e "SELECT VERSION();" 2>&1 | Out-String).Trim()
Say "- MySQL VERSION(): $mysqlVersion"
if ($mysqlVersion -notmatch '^8\.4\.') { Say "  ⚠ 版本不是 8.4.x" }

$flyway = (& docker compose @composeArgs exec -T mysql mysql -uroot -ptravel_root_pw -N -e "SELECT COUNT(*) AS applied, MAX(version) AS latest, SUM(success=0) AS failed FROM travel_agent.flyway_schema_history;" 2>&1 | Out-String).Trim()
Say "- flyway_schema_history (applied/latest/failed): $flyway"

$tables = (& docker compose @composeArgs exec -T mysql mysql -uroot -ptravel_root_pw -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='travel_agent';" 2>&1 | Out-String).Trim()
Say "- travel_agent 表数量: $tables"

$logs = (& docker compose @composeArgs logs --no-color backend 2>&1 | Out-String)
Save 'backend-boot.log' $logs
$migLines = ($logs -split "`n" | Select-String -Pattern 'Successfully applied|now at version|Migrating schema|validate' | Out-String)
Save 'flyway-lines.txt' $migLines
Say "- Flyway 日志关键行："
foreach ($l in ($migLines -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 8)) { Say "    $l" }

# ── 3. 核心链路（真实 HTTP，经 nginx 反代）────────────────
Say "`n## 3. 核心链路（登录 → 规划 → 确认 → 追踪 → 路线段）"
$login = Http 'http://127.0.0.1:8081/api/auth/login' 'POST' (@{ username = 'demo'; password = 'demo123' } | ConvertTo-Json)
$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }
Say "- 登录: HTTP OK, user=$($login.data.username)"

$plan = Http 'http://127.0.0.1:8081/api/agent/chat' 'POST' (@{ message = '上海三日游，两个人，预算5000元' } | ConvertTo-Json)
Say "- 规划: status=$($plan.data.status) intent=$($plan.data.intent) diff=$($plan.data.diff.changes.Count) 项 contentHash=$($plan.data.confirmation.contentHash.Substring(0,8))…"
if ($plan.data.status -ne 'NEED_CONFIRMATION') { throw "规划未进入待确认：$($plan.data.status)" }

$confirmBody = @{ planId = $plan.data.confirmation.planId; contentHash = $plan.data.confirmation.contentHash }
if ($plan.data.confirmation.requiresAcknowledgement) { $confirmBody.acknowledgeIssues = $true }
$confirm = Http 'http://127.0.0.1:8081/api/agent/confirm' 'POST' ($confirmBody | ConvertTo-Json)
Say "- 确认: status=$($confirm.data.status) tripId=$($confirm.data.tripId) execution=$($confirm.data.execution.executionId) 模块=$((($confirm.data.execution.modules | ForEach-Object { $_.module + ':' + $_.status }) -join ','))"
$tripId = $confirm.data.tripId

$runs = Http 'http://127.0.0.1:8081/api/agent/runs?limit=5' -headers $headers
$runId = $runs.data[0].runId
$detail = Http "http://127.0.0.1:8081/api/agent/runs/$runId" -headers $headers
$taskChain = (($detail.data.tasks | ForEach-Object { $_.taskType }) -join ' → ')
Say "- 追踪: runId=$runId 任务链=$taskChain 工具调用=$($detail.data.toolCalls.Count) 次"
Save 'run-detail.json' ($detail | ConvertTo-Json -Depth 8)
Save 'plan-result.json' ($plan | ConvertTo-Json -Depth 8)

$legs = Http "http://127.0.0.1:8081/api/trips/$tripId/route-legs" -headers $headers
$amapCount = @($legs.data | Where-Object { $_.source -eq 'amap' }).Count
$estCount = @($legs.data | Where-Object { $_.source -eq 'estimated' }).Count
Say "- 路线段: 共 $($legs.data.Count) 段，amap=$amapCount，estimated=$estCount"

$status = Http 'http://127.0.0.1:8081/api/system/status' -headers $headers
Say "- system/status: routeProvider=$($status.data.routeProvider) geoProvider=$($status.data.geoProvider) amapConfigured=$($status.data.amapConfigured)"
if ($AmapKey -and $amapCount -eq 0) { Say "  ⚠ 配了 Key 但没有 amap 段" }

# 记录重启前基线
$before = @{
    tripId     = $tripId
    runId      = $runId
    taskChain  = $taskChain
    toolCalls  = $detail.data.toolCalls.Count
    trips      = (Http 'http://127.0.0.1:8081/api/trips' -headers $headers).data.Count
    runs       = (Http 'http://127.0.0.1:8081/api/agent/runs?limit=50' -headers $headers).data.Count
    itinerary  = (Http "http://127.0.0.1:8081/api/trips/$tripId/itinerary" -headers $headers).data.Count
}
Say "- 重启前基线: trips=$($before.trips) runs=$($before.runs) itinerary=$($before.itinerary)"

# ── 4. 重启恢复 ───────────────────────────────────────────
Say "`n## 4. 重启恢复"
function Wait-Healthy([int]$timeoutSec = 240) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $null = Http 'http://127.0.0.1:8081/api/system/status'; return $true } catch { Start-Sleep -Seconds 4 }
    }
    return $false
}

Say "`n### 4.1 仅重启 backend（容器重建，MySQL 不动）"
& docker compose @composeArgs restart backend 2>&1 | Out-Null
if (-not (Wait-Healthy 240)) { throw "backend 重启后未恢复" }
$login2 = Http 'http://127.0.0.1:8081/api/auth/login' 'POST' (@{ username = 'demo'; password = 'demo123' } | ConvertTo-Json)
$t2 = $login2.data.token
$h2 = @{ Authorization = "Bearer $t2" }
$trips2 = (Http 'http://127.0.0.1:8081/api/trips' -headers $h2).data.Count
$runs2 = (Http 'http://127.0.0.1:8081/api/agent/runs?limit=50' -headers $h2).data.Count
$it2 = (Http "http://127.0.0.1:8081/api/trips/$tripId/itinerary" -headers $h2).data.Count
$trace2 = Http "http://127.0.0.1:8081/api/agent/runs/$runId" -headers $h2
Say "- backend 重启后: trips=$trips2 runs=$runs2 itinerary=$it2；旧 runId 仍可查（任务链=$((($trace2.data.tasks | ForEach-Object { $_.taskType }) -join ' → '))）"
if ($trips2 -ne $before.trips -or $runs2 -ne $before.runs -or $it2 -ne $before.itinerary) { Say "  ⚠ 数据与重启前基线不一致" } else { Say "  ✓ 数据与重启前基线一致" }

Say "`n### 4.2 仅重启 mysql（后端进程不动，验证连接池恢复）"
& docker compose @composeArgs restart mysql 2>&1 | Out-Null
$mysqlBack = $false
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    $ping = (& docker compose @composeArgs exec -T mysql mysqladmin ping -uroot -ptravel_root_pw 2>&1 | Out-String)
    if ($ping -match 'mysqld is alive') { $mysqlBack = $true; break }
    Start-Sleep -Seconds 3
}
Say "- mysql 重启后 ping: $mysqlBack"
$recovered = $false
for ($i = 0; $i -lt 12; $i++) {
    Start-Sleep -Seconds 5
    try {
        $probe = Http 'http://127.0.0.1:8081/api/trips' -headers $h2
        if ($null -ne $probe.data) { $recovered = $true; break }
    } catch { }
}
Say "- 后端在 mysql 重启后的恢复: $recovered（trip 列表可读=$([bool]$recovered)）"
$after = Http 'http://127.0.0.1:8081/api/agent/chat' 'POST' (@{ message = '杭州两日游，两个人，预算2000元' } | ConvertTo-Json)
Say "- mysql 重启后新规划: status=$($after.data.status)（证明写链路恢复）"

Say "`n### 4.3 整栈 down → up（保留卷，验证重启恢复而非重建）"
& docker compose @composeArgs down 2>&1 | Out-Null
& docker compose @composeArgs up -d 2>&1 | Out-Null
if (-not (Wait-Healthy 300)) { throw "整栈重启后未恢复" }
$login3 = Http 'http://127.0.0.1:8081/api/auth/login' 'POST' (@{ username = 'demo'; password = 'demo123' } | ConvertTo-Json)
$h3 = @{ Authorization = "Bearer $($login3.data.token)" }
$trips3 = (Http 'http://127.0.0.1:8081/api/trips' -headers $h3).data.Count
$it3 = (Http "http://127.0.0.1:8081/api/trips/$tripId/itinerary" -headers $h3).data.Count
$runs3 = (Http 'http://127.0.0.1:8081/api/agent/runs?limit=50' -headers $h3).data.Count
Say "- 整栈重启后: trips=$trips3 runs=$runs3 itinerary=$it3"
$persist = ($trips3 -eq $before.trips -and $runs3 -eq $before.runs -and $it3 -eq $before.itinerary)
Say "- 数据持久化: $(if ($persist) { '✓ 与基线一致' } else { '⚠ 与基线不一致' })"

# ── 5. 汇总 ─────────────────────────────────────────────
Say "`n## 5. 结论"
$verdict = @(
    "MySQL 版本: $mysqlVersion",
    "Flyway: $flyway",
    "启动耗时: ${bootSeconds}s",
    "核心链路: 规划=$($plan.data.status) 确认=$($confirm.data.status) 三模块=$((($confirm.data.execution.modules | ForEach-Object { $_.status }) -join '/'))",
    "路线段: amap=$amapCount estimated=$estCount",
    "重启恢复: $(if ($persist) { 'PASS' } else { 'CHECK' })"
)
foreach ($v in $verdict) { Say "- $v" }
Save 'summary.md' ($summary -join "`n")

if (-not $KeepRunning) {
    Say "`n(服务保留运行，便于复核；如需清理：docker compose $($composeArgs -join ' ') down -v)"
}
Write-Host "`n证据目录: $EvidenceDir"