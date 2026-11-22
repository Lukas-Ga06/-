# MySQL 8.4.10 native acceptance: fresh instance on :3307 (isolated datadir) →
# Flyway V1..V18 → core chain (login/plan/confirm/trace/route-legs) → restart recovery.
#
# Why native instead of docker: this host has no docker daemon. The instance is a portable
# mysql-8.4.10-winx64 extracted under .tools/mysql8410-3307 with its own datadir + my.ini,
# so it never touches the pre-existing MySQL80 service on :3306.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .e2e\acceptance\mysql-native-3307-acceptance.ps1
#   ... -SkipRestart   # only Flyway + core chain
param(
    [string]$EvidenceDir = "",
    [int]$BackendPort = 8080,
    [switch]$SkipRestart,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$ToolsRoot = Join-Path $RepoRoot '.tools\mysql8410-3307'
$BaseDir = Join-Path $ToolsRoot 'mysql-8.4.10-winx64'
$InstDir = Join-Path $ToolsRoot 'instance'
$MyIni = Join-Path $InstDir 'my.ini'
$Jar = Join-Path $ToolsRoot 'app\travel-agent-server-0.1.0.jar'
$MysqlExe = Join-Path $BaseDir 'bin\mysql.exe'
$MysqlAdmin = Join-Path $BaseDir 'bin\mysqladmin.exe'
$Setup = Join-Path $ToolsRoot 'setup.ps1'
$JavaExe = 'C:\Program Files\Java\jdk-17.0.1\bin\java.exe'
$DbPort = 3307
# 仅本机一次性实例（绑定 127.0.0.1、独立 datadir、用完即删）的 root 口令；不是任何部署凭据
$RootPw = '123456'
$AppDb = 'travel_agent'
$AppUser = 'travel'
$AppPw = 'travel_pw'

# 测试用 JWT：每次运行随机生成（可用环境变量 JWT_SECRET 覆盖）；重启测试共用本次运行的值，仓库不保存固定密钥
$JwtSecret = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { [Convert]::ToBase64String([byte[]](1..48 | ForEach-Object { Get-Random -Maximum 256 })) }

$ClientCnf = Join-Path $InstDir 'client-root.cnf'

if (-not $EvidenceDir) { $EvidenceDir = Join-Path $RepoRoot ('.evidence\mysql84-3307-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$results = New-Object System.Collections.Generic.List[object]
$summary = New-Object System.Collections.Generic.List[string]

function Say([string]$line) { Write-Host $line; $summary.Add($line) }
function Save([string]$name, [string]$text) {
    [System.IO.File]::WriteAllText((Join-Path $EvidenceDir $name), $text, (New-Object System.Text.UTF8Encoding($false)))
}
function Assert([string]$id, [bool]$ok, [string]$detail) {
    $results.Add([pscustomobject]@{ id = $id; ok = $ok; detail = $detail })
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    Say ("  [{0}] {1} — {2}" -f $mark, $id, $detail)
}
function Invoke-MysqlRaw([string[]]$Arguments) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $MysqlExe @Arguments 2>&1
        return [pscustomobject]@{ code = $LASTEXITCODE; out = (($out | Out-String).Trim()) }
    } finally { $ErrorActionPreference = $prev }
}
function DbQuery([string]$sql) {
    if (-not (Test-Path $ClientCnf)) { throw "client cnf missing: $ClientCnf" }
    return (Invoke-MysqlRaw @("--defaults-extra-file=$ClientCnf", '-N', '-B', '-D', $AppDb, '-e', $sql)).out
}
function DbAdmin([string]$sql) {
    if (-not (Test-Path $ClientCnf)) { throw "client cnf missing: $ClientCnf" }
    return (Invoke-MysqlRaw @("--defaults-extra-file=$ClientCnf", '-N', '-B', '-e', $sql)).out
}
function DbDump([string]$sql) {
    return (Invoke-MysqlRaw @("--defaults-extra-file=$ClientCnf", '-B', '-D', $AppDb, '-e', $sql)).out
}
function Invoke-MysqlAdmin([string[]]$Arguments) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $MysqlAdmin @Arguments 2>&1
        return [pscustomobject]@{ code = $LASTEXITCODE; out = (($out | Out-String).Trim()) }
    } finally { $ErrorActionPreference = $prev }
}
function Stop-Mysql {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $null = & $MysqlAdmin "--defaults-extra-file=$ClientCnf" shutdown 2>&1 } finally { $ErrorActionPreference = $prev }
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline -and (PortOpen $DbPort)) { Start-Sleep -Milliseconds 500 }
}
function PortOpen([int]$port) { return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) }

function Http {
    param([string]$Url, [string]$Method = 'GET', $Body = $null, [string]$Token = $null, [int]$TimeoutSec = 60)
    $req = @{ Uri = $Url; Method = $Method; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
    if ($Token) { $req.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $req.ContentType = 'application/json; charset=utf-8'
        $req.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    try {
        $r = Invoke-WebRequest @req
        # PS 5.1 returns byte[] for JSON media types it does not decode (e.g. actuator health)
        $text = $r.Content
        if ($text -is [byte[]]) {
            $text = [System.Text.Encoding]::UTF8.GetString($text)
        } elseif ([string]$r.Headers['Content-Type'] -notmatch 'charset=') {
            # PS 5.1 decodes JSON without an explicit charset as ISO-8859-1; undo that.
            $text = [System.Text.Encoding]::UTF8.GetString([System.Text.Encoding]::GetEncoding(28591).GetBytes($text))
        }
        $parsed = $null
        try { $parsed = $text | ConvertFrom-Json } catch { }
        return [pscustomobject]@{ code = [int]$r.StatusCode; json = $parsed; raw = $text; error = $null }
    } catch {
        $resp = $_.Exception.Response
        $raw = $null
        if ($resp) {
            try {
                $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
                $raw = $sr.ReadToEnd()
            } catch { }
        }
        return [pscustomobject]@{ code = if ($resp) { [int]$resp.StatusCode } else { -1 }; json = $null; raw = $raw; error = $_.Exception.Message }
    }
}

# ── backend lifecycle ───────────────────────────────────────────────
function Start-Backend {
    param([string]$Tag = 'boot')
    $safe = $Tag -replace '[^A-Za-z0-9]', '_'
    $stdout = Join-Path $EvidenceDir "backend-$safe-stdout.log"
    $stderr = Join-Path $EvidenceDir "backend-$safe-stderr.log"
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:MYSQL_HOST = '127.0.0.1'
    $env:MYSQL_PORT = "$DbPort"
    $env:MYSQL_DATABASE = $AppDb
    $env:MYSQL_USERNAME = $AppUser
    $env:MYSQL_PASSWORD = $AppPw
    $env:JWT_SECRET = $script:JwtSecret
    $env:DEMO_ACCOUNT = 'true'
    $env:LLM_ENABLED = 'false'
    $env:RATE_LIMIT_ENABLED = 'true'
    $env:WEATHER_REAL = 'false'
    $env:INVOICE_DIR = (Join-Path $ToolsRoot 'app\data\invoices')
    $env:PHOTOS_DIR = (Join-Path $ToolsRoot 'app\data\photos')
    $start = Get-Date
    $p = Start-Process -FilePath $JavaExe -ArgumentList '-jar', $Jar, "--server.port=$BackendPort" `
        -WorkingDirectory (Join-Path $ToolsRoot 'app') `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    return [pscustomobject]@{ proc = $p; started = $start; log = $stdout }
}

function Wait-Healthy([int]$timeoutSec = 300) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $h = Http "http://127.0.0.1:$BackendPort/actuator/health"
        if ($h.code -eq 200 -and $h.json.status -eq 'UP') { return $true }
        if (-not (Get-Process -Id $script:backend.proc.Id -ErrorAction SilentlyContinue)) { return $false }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Stop-Backend {
    if ($script:backend -and $script:backend.proc) {
        Stop-Process -Id $script:backend.proc.Id -Force -ErrorAction SilentlyContinue
        $deadline = (Get-Date).AddSeconds(45)
        while ((Get-Date) -lt $deadline -and (Get-Process -Id $script:backend.proc.Id -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 400 }
    }
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and (PortOpen $BackendPort)) { Start-Sleep -Milliseconds 400 }
}

function Restart-Backend([string]$label) {
    Say "`n### $label"
    Stop-Backend
    $script:backend = Start-Backend -Tag $label
    if (-not (Wait-Healthy 300)) {
        $tail = Get-Content $script:backend.log -ErrorAction SilentlyContinue | Select-Object -Last 60
        Save "backend-fail-$($label -replace '[^A-Za-z0-9]','_').log" ($tail -join "`n")
        throw "${label}: backend did not become healthy"
    }
    $secs = [math]::Round(((Get-Date) - $script:backend.started).TotalSeconds, 1)
    Say "- backend healthy after ${secs}s (pid=$($script:backend.proc.Id))"
    return $secs
}

function Login([string]$user = 'demo', [string]$pass = 'demo123') {
    $r = Http "http://127.0.0.1:$BackendPort/api/auth/login" 'POST' @{ username = $user; password = $pass }
    if ($r.code -ne 200) { throw "login failed: HTTP $($r.code) $($r.raw)" }
    return $r.json.data.token
}

function Snapshot([string]$token) {
    $trips = Http "http://127.0.0.1:$BackendPort/api/trips" -Token $token
    $runs = Http "http://127.0.0.1:$BackendPort/api/agent/runs?limit=50" -Token $token
    return [pscustomobject]@{
        trips     = @($trips.json.data).Count
        runs      = @($runs.json.data).Count
        tripIds   = (@($trips.json.data) | ForEach-Object { $_.id } | Sort-Object) -join ','
        runIds    = (@($runs.json.data) | ForEach-Object { $_.runId } | Sort-Object) -join ','
    }
}

# ── 0. instance ─────────────────────────────────────────────────────
Say "# MySQL 8.4.10 native acceptance ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))"
Say "`n## 0. 实例准备（独立 datadir / :$DbPort）"
if (-not (Test-Path $MyIni)) { throw "my.ini missing: $MyIni" }
if (-not (PortOpen $DbPort)) { & $Setup -Action start -TimeoutSec 90 | ForEach-Object { Say "- $_" } }
if (-not (PortOpen $DbPort)) { throw "mysql not listening on $DbPort" }
$version = DbAdmin 'SELECT VERSION();'
$datadir = DbAdmin "SELECT @@datadir;"
$port = DbAdmin 'SELECT @@port;'
$basedir = DbAdmin 'SELECT @@basedir;'
Say "- SELECT VERSION(): $version"
Say "- @@datadir: $datadir"
Say "- @@basedir: $basedir  @@port: $port"
Assert 'M1:版本=8.4.10' ($version -eq '8.4.10') "VERSION()=$version"
Assert 'M2:端口=3307' ($port -eq '3307') "port=$port"
$datadirNorm = $datadir -replace '\\\\', '/'
Assert 'M3:独立datadir' ($datadirNorm -match 'mysql8410-3307/instance/data/?$') "datadir=$datadir"

# database + app user (idempotent)
DbAdmin "CREATE DATABASE IF NOT EXISTS $AppDb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
DbAdmin "CREATE USER IF NOT EXISTS '$AppUser'@'%' IDENTIFIED BY '$AppPw';"
DbAdmin "CREATE USER IF NOT EXISTS '$AppUser'@'localhost' IDENTIFIED BY '$AppPw';"
DbAdmin "GRANT ALL PRIVILEGES ON $AppDb.* TO '$AppUser'@'%';"
DbAdmin "GRANT ALL PRIVILEGES ON $AppDb.* TO '$AppUser'@'localhost';"
DbAdmin "FLUSH PRIVILEGES;"
$empty = DbAdmin "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$AppDb';"
Say "- 全新库表数（启动前）: $empty"
Assert 'M4:全新库干净' ($empty -eq '0') "tables=$empty"

# ── 1. backend boot + Flyway ────────────────────────────────────────
Say "`n## 1. 启动后端 → Flyway 迁移 + ddl-auto=validate"
$backend = Start-Backend -Tag 'boot'
if (-not (Wait-Healthy 360)) {
    $tail = Get-Content $backend.log -ErrorAction SilentlyContinue | Select-Object -Last 80
    $tail | ForEach-Object { Say "  $_" }
    throw "backend failed to start; 见 $EvidenceDir\backend-boot-stdout.log"
}
$bootSecs = [math]::Round(((Get-Date) - $backend.started).TotalSeconds, 1)
Say "- 启动成功，耗时 ${bootSecs}s（pid=$($backend.proc.Id)）"

$bootLog = Get-Content $backend.log -Raw -ErrorAction SilentlyContinue
$flywayLines = (($bootLog -split "`n") | Select-String -Pattern 'Flyway|Migrating schema|Successfully applied|now at version|validate|Started TravelAgent' | ForEach-Object { $_.Line.Trim() }) -join "`n"
Save 'flyway-boot-lines.txt' $flywayLines
Say "- Flyway 日志关键行："
($flywayLines -split "`n" | Where-Object { $_ } | Select-Object -First 12) | ForEach-Object { Say "    $_" }
Assert 'F1:后端启动成功' ($bootLog -match 'Started TravelAgent') "boot=${bootSecs}s"

$applied = DbQuery 'SELECT COUNT(*) FROM flyway_schema_history;'
$latest = DbQuery 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;'
$failed = DbQuery 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;'
$tables = DbQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$AppDb';"
Say "- flyway_schema_history: applied=$applied latest=$latest failed=$failed；业务表=$tables"
$historyDump = DbDump 'SELECT installed_rank,version,description,type,success,execution_time FROM flyway_schema_history ORDER BY installed_rank;'
Save 'flyway-history.tsv' $historyDump
$tableDump = DbQuery "SELECT table_name FROM information_schema.tables WHERE table_schema='$AppDb' ORDER BY table_name;"
Save 'tables.txt' $tableDump
Assert 'F2:迁移全部成功' ($applied -ge '17' -and $failed -eq '0') "applied=$applied failed=$failed"
Assert 'F3:迁移到V18' ($latest -eq '18') "latest=$latest"
Assert 'F4:validate通过(启动即证明)' ($bootLog -match 'Started TravelAgent') 'ddl-auto=validate 未报错'
Assert 'F5:业务表建成' ([int]$tables -ge 21) "tables=$tables"

# ── 2. core chain ───────────────────────────────────────────────────
Say "`n## 2. 核心链路（登录 → 规划 → 确认 → 追踪 → 路线段）"
$t0 = Get-Date
$token = Login
Say "- 登录: OK"
$plan = Http "http://127.0.0.1:$BackendPort/api/agent/chat" 'POST' @{ message = '上海三日游，两个人，预算5000元' } -Token $token
Save 'plan.json' ($plan.raw)
if ($plan.code -ne 200) { throw "plan failed HTTP $($plan.code): $($plan.raw)" }
$p = $plan.json.data
Say "- 规划: status=$($p.status) intent=$($p.intent) changes=$(@($p.diff.changes).Count) tripId=$($p.tripId)"
Assert 'C1:规划进入待确认' ($p.status -eq 'NEED_CONFIRMATION' -and $p.confirmation.planId) "status=$($p.status)"
Assert 'C2:带内容指纹' ([bool]($p.confirmation.contentHash -and $p.confirmation.contentHash.Length -eq 64)) "contentHash=$($p.confirmation.contentHash)"

$confirmBody = @{ planId = $p.confirmation.planId; contentHash = $p.confirmation.contentHash }
if ($p.confirmation.requiresAcknowledgement) { $confirmBody.acknowledgeIssues = $true }
$confirm = Http "http://127.0.0.1:$BackendPort/api/agent/confirm" 'POST' $confirmBody -Token $token
Save 'confirm.json' ($confirm.raw)
if ($confirm.code -ne 200) { throw "confirm failed HTTP $($confirm.code): $($confirm.raw)" }
$c = $confirm.json.data
$confirmStatus = if ($c.status) { $c.status } else { 'COMPLETED' }
Say "- 确认: status=$confirmStatus tripId=$($c.tripId) execution=$($c.execution.executionId)"
Say "- 三模块: $((($c.execution.modules | ForEach-Object { $_.module + ':' + $_.status }) -join ','))"
$tripId = $c.tripId
Assert 'C3:确认落库生成行程' ([bool]($tripId -and $tripId -gt 0)) "tripId=$tripId"
Assert 'C4:三模块同步成功' (@($c.execution.modules | Where-Object { $_.status -ne 'SUCCEEDED' }).Count -eq 0) (($c.execution.modules | ForEach-Object { "$($_.module):$($_.status)" }) -join ',')

$runs = Http "http://127.0.0.1:$BackendPort/api/agent/runs?limit=8" -Token $token
$runList = @($runs.json.data)
$runId = $runList[0].runId
Assert 'C5:run已落库' ([bool]$runId) "runs=$($runList.Count) runId=$runId"
$runDetail = Http "http://127.0.0.1:$BackendPort/api/agent/runs/$runId" -Token $token
Save 'run-detail.json' ($runDetail.raw)
$taskChain = (($runDetail.json.data.tasks | ForEach-Object { $_.taskType }) -join ' → ')
$toolCalls = @($runDetail.json.data.toolCalls).Count
Say "- 追踪: 任务链=$taskChain；工具调用=$toolCalls 次"
Assert 'C6:任务链完整' ($taskChain -match 'INTENT' -and $taskChain -match 'PLAN' -and $taskChain -match 'PREVIEW' -and $taskChain -match 'REVIEW' -and $taskChain -match 'COMMIT') "chain=$taskChain"
Assert 'C7:工具调用有trace' ($toolCalls -gt 0) "toolCalls=$toolCalls"

$itinerary = Http "http://127.0.0.1:$BackendPort/api/trips/$tripId/itinerary" -Token $token
Save 'itinerary.json' ($itinerary.raw)
$itCount = @($itinerary.json.data).Count
$legs = Http "http://127.0.0.1:$BackendPort/api/trips/$tripId/route-legs" -Token $token
Save 'route-legs.json' ($legs.raw)
$legCount = @($legs.json.data).Count
$legSources = (@($legs.json.data) | ForEach-Object { $_.source } | Sort-Object -Unique) -join ','
Say "- 行程条目=$itCount 路线段=$legCount sources=$legSources"
Assert 'C8:行程可读' ($itCount -gt 0) "itineraryItems=$itCount"
Assert 'C9:路线段可读' ($legCount -gt 0) "legs=$legCount sources=$legSources"

$sys = Http "http://127.0.0.1:$BackendPort/api/system/status" -Token $token
Save 'system-status.json' ($sys.raw)
Say "- system/status: routeProvider=$($sys.json.data.routeProvider) geoProvider=$($sys.json.data.geoProvider) amapConfigured=$($sys.json.data.amapConfigured)"
Assert 'C10:状态接口如实上报' ([bool]$sys.json.data.routeProvider) "routeProvider=$($sys.json.data.routeProvider)"

$baseline = Snapshot $token
$itineraryHash = (Get-FileHash (Join-Path $EvidenceDir 'itinerary.json') -Algorithm SHA256).Hash
$dbBaseline = (DbQuery "SELECT CONCAT((SELECT COUNT(*) FROM trip),'/',(SELECT COUNT(*) FROM agent_run),'/',(SELECT COUNT(*) FROM tool_call_trace),'/',(SELECT COALESCE(SUM(amount),0) FROM expense));")
$itDb = (DbQuery "SELECT CONCAT(COUNT(*),'/',IFNULL(MD5(GROUP_CONCAT(CONCAT_WS('|',id,IFNULL(title,''),day_index,IFNULL(start_time,''),IFNULL(cost,0)) ORDER BY id SEPARATOR ';')),'-')) FROM itinerary_item;")
Say "- 基线: trips=$($baseline.trips) runs=$($baseline.runs) db(trip/run/tool/expense)=$dbBaseline"
Assert 'C11:数据已入MySQL' ([int]$dbBaseline.Split('/')[0] -ge 1 -and [int]$dbBaseline.Split('/')[1] -ge 1) "db=$dbBaseline"

if ($SkipRestart) {
    Say "`n(已跳过重启测试)"
} else {
    # ── 3. backend restart ─────────────────────────────────────────
    $secs = Restart-Backend '3.1 仅重启后端（MySQL 不动）'
    $token2 = Login
    $after = Snapshot $token2
    $traces = ($after.tripIds -eq $baseline.tripIds) -and ($after.runIds -eq $baseline.runIds)
    $dbAfter = (DbQuery "SELECT CONCAT((SELECT COUNT(*) FROM trip),'/',(SELECT COUNT(*) FROM agent_run),'/',(SELECT COUNT(*) FROM tool_call_trace),'/',(SELECT COALESCE(SUM(amount),0) FROM expense));")
    $historyAfter = DbQuery 'SELECT COUNT(*) FROM flyway_schema_history;'
    $failedAfter = DbQuery 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;'
    $itAfter = Http "http://127.0.0.1:$BackendPort/api/trips/$tripId/itinerary" -Token $token2
    $itHashAfter = (($itAfter.raw | ConvertFrom-Json) | ConvertTo-Json -Depth 10 -Compress)
    $itSame = ((Get-Content (Join-Path $EvidenceDir 'itinerary.json') -Raw -Encoding UTF8 | ConvertFrom-Json) | ConvertTo-Json -Depth 10 -Compress) -eq $itHashAfter
    $itDbAfter = (DbQuery "SELECT CONCAT(COUNT(*),'/',IFNULL(MD5(GROUP_CONCAT(CONCAT_WS('|',id,IFNULL(title,''),day_index,IFNULL(start_time,''),IFNULL(cost,0)) ORDER BY id SEPARATOR ';')),'-')) FROM itinerary_item;")
    Say "- 重启后: trips=$($after.trips) runs=$($after.runs) db=$dbAfter flyway=$historyAfter failed=$failedAfter itineraryDb=$itDbAfter"
    Assert 'R1:后端重启后数据不丢' ($traces -and $itSame -and $itDbAfter -eq $itDb) "tripIds=$($after.tripIds) itinerarySame=$itSame itDb=$itDbAfter (was $itDb)"
    Assert 'R2:重启未重跑迁移' ($historyAfter -eq $applied -and $failedAfter -eq '0') "history=$historyAfter (was $applied)"
    Assert 'R3:后端重启后健康' (PortOpen $BackendPort) "port=$BackendPort"
    $newPlan = Http "http://127.0.0.1:$BackendPort/api/agent/chat" 'POST' @{ message = '杭州两日游，两个人，预算2000元' } -Token $token2
    Assert 'R4:重启后写链路可用' ($newPlan.code -eq 200 -and $newPlan.json.data.status -eq 'NEED_CONFIRMATION') "status=$($newPlan.code)/$($newPlan.json.data.status)"
    # 这次未确认的规划会新增一条 run（以及工具 trace），后续重启对比用新基线
    $afterWrite = Snapshot $token2
    $dbBaseline2 = (DbQuery "SELECT CONCAT((SELECT COUNT(*) FROM trip),'/',(SELECT COUNT(*) FROM agent_run),'/',(SELECT COUNT(*) FROM tool_call_trace),'/',(SELECT COALESCE(SUM(amount),0) FROM expense));")
    Say "- 新基线（重启后规划请求之后）: trips=$($afterWrite.trips) runs=$($afterWrite.runs) db=$dbBaseline2"

    # ── 4. mysql-only restart ─────────────────────────────────────
    Say "`n### 4.1 仅重启 MySQL（后端进程不动，验证连接池恢复）"
    Stop-Mysql
    Assert 'R5:MySQL已停止' (-not (PortOpen $DbPort)) "port closed"
    Start-Sleep -Seconds 2
    & $Setup -Action start -TimeoutSec 120 | ForEach-Object { Say "- $_" }
    $ping = (Invoke-MysqlAdmin @("--defaults-extra-file=$ClientCnf", 'ping')).out
    Say "- MySQL 重启后 ping: $($ping.Trim())"
    $recovered = $false
    $recoverSecs = $null
    $rw = Get-Date
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 3
        $probe = Http "http://127.0.0.1:$BackendPort/api/trips" -Token $token2
        if ($probe.code -eq 200) { $recovered = $true; $recoverSecs = [math]::Round(((Get-Date) - $rw).TotalSeconds, 1); break }
    }
    Say "- 后端在 MySQL 重启后的恢复: $recovered（耗时 ${recoverSecs}s）"
    Assert 'R6:连接池自动恢复' $recovered "recovered=${recoverSecs}s"
    $dbAfter2 = (DbQuery "SELECT CONCAT((SELECT COUNT(*) FROM trip),'/',(SELECT COUNT(*) FROM agent_run),'/',(SELECT COUNT(*) FROM tool_call_trace),'/',(SELECT COALESCE(SUM(amount),0) FROM expense));")
    $token3 = Login
    $after2 = Snapshot $token3
    $write = Http "http://127.0.0.1:$BackendPort/api/memos" 'POST' @{ content = '写入发生在本机 MySQL 8.4.10 重启之后' } -Token $token3
    $memoCount = (DbQuery 'SELECT COUNT(*) FROM memo;')
    Say "- 重启后: db=$dbAfter2 memos=$memoCount writeHTTP=$($write.code)"
    Assert 'R7:MySQL重启后数据一致' ($dbAfter2 -eq $dbBaseline2 -and $after2.tripIds -eq $baseline.tripIds) "db=$dbAfter2 baseline2=$dbBaseline2"
    Assert 'R8:MySQL重启后写链路可用' ($write.code -eq 200 -and [int]$memoCount -ge 1) "memoHTTP=$($write.code) memos=$memoCount"

    # ── 5. full stack restart ─────────────────────────────────────
    Say "`n### 5.1 整栈重启（先停后端，再停 MySQL；反向启动）"
    Stop-Backend
    Stop-Mysql
    $deadline = (Get-Date).AddSeconds(60); while ((Get-Date) -lt $deadline -and (PortOpen $DbPort)) { Start-Sleep -Milliseconds 500 }
    Say "- 已全部停止（backend alive=$(PortOpen $BackendPort) mysql alive=$(PortOpen $DbPort)）"
    & $Setup -Action start -TimeoutSec 120 | ForEach-Object { Say "- $_" }
    $backend = Start-Backend -Tag 'full-restart'
    if (-not (Wait-Healthy 360)) { throw '整栈重启后后端未恢复' }
    $fullSecs = [math]::Round(((Get-Date) - $backend.started).TotalSeconds, 1)
    Say "- 整栈重启后端健康，耗时 ${fullSecs}s"
    $token4 = Login
    $after3 = Snapshot $token4
    $dbAfter3 = (DbQuery "SELECT CONCAT((SELECT COUNT(*) FROM trip),'/',(SELECT COUNT(*) FROM agent_run),'/',(SELECT COUNT(*) FROM tool_call_trace),'/',(SELECT COALESCE(SUM(amount),0) FROM expense));")
    $history3 = DbQuery 'SELECT COUNT(*) FROM flyway_schema_history;'
    $failed3 = DbQuery 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;'
    $it3 = Http "http://127.0.0.1:$BackendPort/api/trips/$tripId/itinerary" -Token $token4
    $it3Count = @($it3.json.data).Count
    Say "- 整栈重启后: trips=$($after3.trips) runs=$($after3.runs) db=$dbAfter3 flyway=$history3 failed=$failed3 itinerary=$it3Count"
    Assert 'R9:整栈重启数据不丢' ($dbAfter3 -eq $dbBaseline2 -and $after3.tripIds -eq $baseline.tripIds -and $it3Count -eq $itCount) "db=$dbAfter3 baseline2=$dbBaseline2"
    Assert 'R10:整栈重启未重跑迁移' ($history3 -eq $applied -and $failed3 -eq '0') "history=$history3 (was $applied)"
}

# ── 6. summary ──────────────────────────────────────────────────────
$pass = @($results | Where-Object { $_.ok }).Count
$fail = @($results | Where-Object { -not $_.ok }).Count
Say "`n## 6. 结论"
Say "- 断言: $pass PASS / $fail FAIL（共 $($results.Count)）"
$verdict = if ($fail -eq 0) { 'PASS' } else { 'FAIL' }
Say "- 判定: $verdict"
$results | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $EvidenceDir 'results.json') -Encoding utf8
Save 'summary.md' ($summary -join "`n")

if (-not $KeepRunning) {
    Stop-Backend
    Stop-Mysql
    Say "- 已停止后端与 MySQL（-KeepRunning 可保留）"
}
Write-Host "`n证据目录: $EvidenceDir"
if ($fail -gt 0) { exit 1 }