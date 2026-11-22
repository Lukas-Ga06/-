# V2.2 正式验收：本地全新 MySQL 8.4 实例（Docker 不可用时的等价方案）
#
# 与 mysql-compose-acceptance.ps1 一一对应：安装（初始化 datadir）→ Flyway 迁移 →
# 核心链路 → 重启 backend → 重启 mysqld → 数据持久化。差别只有「MySQL 是否跑在容器里」。
#
# 用法：powershell -ExecutionPolicy Bypass -File .e2e\acceptance\mysql-local-acceptance.ps1 [-AmapKey <key>]
param(
    [string]$AmapKey = "",
    [int]$MysqlPort = 3317,
    [int]$BackendPort = 18093,
    [string]$EvidenceDir = ""
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root
$base = Join-Path $root '.tools\mysql84\mysql-8.4.10-winx64'
$mysqld = Join-Path $base 'bin\mysqld.exe'
$mysqlCli = Join-Path $base 'bin\mysql.exe'
if (-not (Test-Path $mysqld)) { throw "找不到 $mysqld（先解压 MySQL 8.4 zip 到 .tools\mysql84）" }
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $root ('.evidence\mysql84-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
# 一次运行内固定、跨运行随机：重启恢复测试需要同一把密钥，仓库里不需要任何固定密钥
$JwtSecret = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { [Convert]::ToBase64String([byte[]](1..48 | ForEach-Object { Get-Random -Maximum 256 })) }
$data = Join-Path $EvidenceDir 'data'
$summary = New-Object System.Collections.Generic.List[string]
function Say([string]$line) { Write-Host $line; $summary.Add($line) }
function Wait-Port([int]$port, [int]$timeoutSec = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $port); $c.Close(); return $true } catch { Start-Sleep -Milliseconds 800 }
    }
    return $false
}
function Wait-Health([int]$timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $r = Invoke-RestMethod "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 5; if ($r.status -eq 'UP') { return $true } } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}
function Start-Backend([string]$tag) {
    $log = Join-Path $EvidenceDir "backend-$tag.log"
    $err = Join-Path $EvidenceDir "backend-$tag.err.log"
    # 测试用 JWT：每次运行随机生成（可用环境变量 JWT_SECRET 覆盖）；仓库不保存任何固定密钥
    $env:JWT_SECRET = $script:JwtSecret
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:MYSQL_HOST = '127.0.0.1'
    $env:MYSQL_PORT = "$MysqlPort"
    $env:MYSQL_DATABASE = 'travel_agent'
    $env:MYSQL_USERNAME = 'travel'
    $env:MYSQL_PASSWORD = 'travel_pw'
    $env:LLM_ENABLED = 'false'
    $env:RATE_LIMIT_ENABLED = 'false'
    $env:DEMO_ACCOUNT = 'true'
    if ($AmapKey) { $env:AMAP_KEY = $AmapKey }
    $jar = Join-Path $root 'travel-agent-server\target\travel-agent-server-0.1.0.jar'
    $javaArgs = @('-jar', $jar, "--server.port=$BackendPort")
    $p = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory (Join-Path $root 'travel-agent-server') `
        -RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Hidden
    return $p
}

Say "# 本地全新 MySQL 8.4 验收（Docker 环境不可用时的等价方案）"
Say "- MySQL 二进制: $base"
Say "- 证据目录: $EvidenceDir"

# ── 1. 全新 datadir（等价 docker 全新卷）──────────────────
Say "`n## 1. 初始化全新 datadir"
if (Test-Path $data) { Remove-Item $data -Recurse -Force }
New-Item -ItemType Directory -Force -Path $data | Out-Null
$initLog = Join-Path $EvidenceDir 'mysqld-initialize.log'
$init = Start-Process -FilePath $mysqld -ArgumentList @("--initialize-insecure", "--basedir=$base", "--datadir=$data", '--console') `
    -RedirectStandardOutput $initLog -RedirectStandardError (Join-Path $EvidenceDir 'mysqld-initialize.err.log') -PassThru -WindowStyle Hidden
$init.WaitForExit(180000) | Out-Null
$initOk = Test-Path (Join-Path $data 'mysql')
Say "- mysqld --initialize-insecure 完成（datadir 为全新目录，系统表目录存在=$initOk）"
if (-not $initOk) { throw "初始化失败，见 mysqld-initialize.err.log" }

# ── 2. 启动 mysqld ────────────────────────────────────────
$myLog = Join-Path $EvidenceDir 'mysqld.log'
$my = Start-Process -FilePath $mysqld -ArgumentList @("--basedir=$base", "--datadir=$data", "--port=$MysqlPort",
    '--bind-address=127.0.0.1', '--mysqlx=OFF', "--log-error=$myLog", "--pid-file=$(Join-Path $data 'mysqld.pid')") -PassThru -WindowStyle Hidden
if (-not (Wait-Port $MysqlPort 90)) { throw "mysqld 未在 $MysqlPort 就绪" }
Say "- mysqld 已监听 127.0.0.1:$MysqlPort（pid=$($my.Id)）"

$version = (& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -N -e "SELECT VERSION();" 2>&1 | Out-String).Trim()
Say "- SELECT VERSION(): $version"
if ($version -notmatch '^8\.4\.') { Say "  ⚠ 不是 8.4.x" }

& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -e "CREATE DATABASE travel_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'travel'@'%' IDENTIFIED BY 'travel_pw'; GRANT ALL PRIVILEGES ON travel_agent.* TO 'travel'@'%'; FLUSH PRIVILEGES;" 2>&1 | Out-Null
Say "- 建库 travel_agent + 用户 travel（空库，等待 Flyway 迁移）"
$empty = (& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='travel_agent';" 2>&1 | Out-String).Trim()
Say "- 迁移前表数量: $empty"

# ── 3. 启动后端（mysql profile），Flyway 迁移 ─────────────
Say "`n## 2. 启动后端 + Flyway 迁移"
$sw = [Diagnostics.Stopwatch]::StartNew()
$be = Start-Backend 'boot'
if (-not (Wait-Health 180)) {
    Get-Content (Join-Path $EvidenceDir 'backend-boot.err.log') -Tail 40 -ErrorAction SilentlyContinue
    throw "后端未在 180s 内健康"
}
$sw.Stop()
Say "- 后端健康，启动耗时 $([math]::Round($sw.Elapsed.TotalSeconds,1))s"

$bootLog = Get-Content (Join-Path $EvidenceDir 'backend-boot.log') -Raw
$fly = ($bootLog -split "`n" | Select-String -Pattern 'Successfully applied|now at version|Migrating schema' | Out-String)
Say "- Flyway 关键日志："
foreach ($l in (($fly -split "`n") | Where-Object { $_.Trim() })) { Say "    $l" }
$applied = (& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -N -e "SELECT COUNT(*), MAX(version), SUM(success=0) FROM travel_agent.flyway_schema_history;" 2>&1 | Out-String).Trim()
Say "- flyway_schema_history (applied / latest / failed): $applied"
$tables = (& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='travel_agent';" 2>&1 | Out-String).Trim()
Say "- 迁移后表数量: $tables"
$fly = ($bootLog -split "`n" | Select-String -Pattern 'Successfully applied|now at version|Migrating schema|validate' | Out-String)
[System.IO.File]::WriteAllText((Join-Path $EvidenceDir 'flyway-lines.txt'), $fly, (New-Object System.Text.UTF8Encoding($false)))

# ── 4. 核心链路（验收脚本）────────────────────────────────
Say "`n## 3. 核心链路（真实 HTTP + FR 断言）"
$nodeArgs = @('.e2e\acceptance\fr-acceptance.mjs', '--base', "http://127.0.0.1:$BackendPort", '--out', (Join-Path $EvidenceDir 'fr-acceptance.json'))
if ($AmapKey) { $nodeArgs += @('--expect-amap', '--amap-key', $AmapKey) }
$accOut = (& node @nodeArgs 2>&1 | Out-String)
Say $accOut
$accExit = $LASTEXITCODE
Say "- fr-acceptance exit=$accExit"

# 取基线（供重启对比）
$login = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/auth/login" -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes('{"username":"demo","password":"demo123"}'))
$h = @{ Authorization = "Bearer $($login.data.token)" }
$trips = (Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/trips" -Headers $h).data
$runs = (Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/agent/runs?limit=50" -Headers $h).data
$tripId = $trips[0].id
$before = @{ trips = $trips.Count; runs = $runs.Count; itinerary = (Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/trips/$tripId/itinerary" -Headers $h).data.Count }
Say "- 重启前基线: trips=$($before.trips) runs=$($before.runs) itinerary=$($before.itinerary)"

# ── 5. 重启 backend ──────────────────────────────────────
Say "`n## 4. 重启恢复"
Stop-Process -Id $be.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
$be2 = Start-Backend 'restart'
if (-not (Wait-Health 180)) { throw "backend 重启失败" }
$login2 = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/auth/login" -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes('{"username":"demo","password":"demo123"}'))
$h2 = @{ Authorization = "Bearer $($login2.data.token)" }
$after = @{ trips = (Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/trips" -Headers $h2).data.Count
    runs       = (Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/agent/runs?limit=50" -Headers $h2).data.Count
    itinerary  = (Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/trips/$tripId/itinerary" -Headers $h2).data.Count }
Say "- backend 重启后: trips=$($after.trips) runs=$($after.runs) itinerary=$($after.itinerary)"
$ok1 = ($after.trips -eq $before.trips -and $after.runs -eq $before.runs -and $after.itinerary -eq $before.itinerary)
Say "- backend 重启数据一致: $ok1"

# ── 6. 重启 mysqld（后端不重启，验证连接池恢复）───────────
Say "`n## 5. 重启 mysqld（后端进程不动）"
& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -e "SHUTDOWN;" 2>&1 | Out-Null
Start-Sleep -Seconds 4
$my2 = Start-Process -FilePath $mysqld -ArgumentList @("--basedir=$base", "--datadir=$data", "--port=$MysqlPort",
    '--bind-address=127.0.0.1', '--mysqlx=OFF', "--log-error=$myLog", "--pid-file=$(Join-Path $data 'mysqld.pid')") -PassThru -WindowStyle Hidden
$back = Wait-Port $MysqlPort 90
Say "- mysqld 重新监听: $back"
$recovered = $false
for ($i = 0; $i -lt 12; $i++) {
    Start-Sleep -Seconds 5
    try { $null = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/trips" -Headers $h2 -TimeoutSec 5; $recovered = $true; break } catch { }
}
Say "- 后端在 mysqld 重启后恢复读: $recovered"
$chatBody = [Text.Encoding]::UTF8.GetBytes('{"message":"杭州两日游，两个人，预算2000元"}')
$chat = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/agent/chat" -Method Post -Headers $h2 -ContentType 'application/json' -Body $chatBody
Say "- mysqld 重启后新规划: status=$($chat.data.status)（写链路恢复）"

# ── 7. 收尾 ─────────────────────────────────────────────
& $mysqlCli --protocol=TCP "--host=127.0.0.1" "--port=$MysqlPort" -uroot -e "SHUTDOWN;" 2>&1 | Out-Null
Stop-Process -Id $be2.Id -Force -ErrorAction SilentlyContinue
Say "`n## 6. 结论"
Say "- MySQL 版本: $version"
Say "- Flyway: $applied；表数量: $tables"
Say "- 核心链路: fr-acceptance exit=$accExit"
Say "- backend 重启一致: $ok1；mysqld 重启恢复: $recovered"
[System.IO.File]::WriteAllText((Join-Path $EvidenceDir 'summary.md'), ($summary -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "`n证据目录: $EvidenceDir"