# ============================================================================
# MySQL 8.4 旧库升级 + 三态重启恢复 正式回归
#
# 与已有两个脚本的分工：
#   mysql-local-acceptance.ps1      全新库 → 全量迁移（Docker 不可用时的等价方案）
#   mysql-native-3307-acceptance.ps1 全新库 → 全量迁移 + 三态重启
#   本脚本                          「旧库升级」：旧 schema 真实业务数据 → 增量迁移 → 三态重启
#
# 流程（每一步都有真实断言，失败记 FAIL 并保留已完成的证据）：
#   0. 预检：端口隔离 / 进程清理 / 构建（当前 jar + 旧版本 jar）
#   1. 全新 datadir 起 MySQL 8.4.10（独立端口，默认 3318，占用则顺延）
#   2. 旧部署模拟：
#      2a. 旧版发布代码 + spring.flyway.target=15 → 停在 V15；记录「V15 无法作为可运行旧基线」的真实证据
#      2b. 旧版发布代码（提交 9918a4f，其最高迁移=V16）+ target=16 → 可运行的真实旧部署
#   3. 通过真实 HTTP API 灌业务数据（登录/建行程/写条目/记支出/规划 run+确认/待确认方案/备忘/日程），
#      再补少量直接 SQL（覆盖后续迁移新增列所在的 trip / agent_task 旧行）
#   4. 以最新代码（target=latest）启动 → 断言增量迁移只跑 V17/V18、停在 V18、ddl-auto=validate 通过、
#      旧数据逐表行数与旧列内容哈希一致、新列默认值回填正确、flyway_schema_history 无重复/不重跑
#   5. 三态重启：仅重启后端 / 仅重启 MySQL / 整栈重启，各自验证旧 runId 可查 + 可写新数据 + 迁移不重跑
#   6. 清理：杀进程、删临时 datadir、确认端口释放、确认 3306 既有 MySQL 未被影响
#
# 环境事实（本机）：
#   * Windows + 无 Docker：全程原生 mysqld，不涉及 docker compose。
#   * 3306 已有 MySQL 8.0 服务，绝不占用/改动；3307/3317 残留实例也不复用。
#   * 便携 MySQL 8.4.10 在 D:\travel-agent\.tools\mysql84\mysql-8.4.10-winx64（.tools 已 gitignore）。
#
# 用法（PowerShell 5.1；脚本须为 UTF-8 with BOM，否则中文 HTTP 载荷会被按 ANSI 解析）：
#   powershell -ExecutionPolicy Bypass -File .e2e\acceptance\mysql-upgrade-acceptance.ps1
#   ... -KeepRunning   保留后端/MySQL（调试用，不删 datadir）
#   ... -KeepTemp      保留 .tmp（复跑免重新构建旧版 jar）
#   ... -Rebuild       强制重建当前与旧版 jar
# ============================================================================
param(
    [string]$EvidenceDir = "",
    [int]$PreferredDbPort = 3318,
    [int]$PreferredBackendPort = 18094,
    [string]$MysqlBase = 'D:\travel-agent\.tools\mysql84\mysql-8.4.10-winx64',
    [string]$OldCommit = '9918a4f',
    [switch]$KeepRunning,
    [switch]$KeepTemp,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ServerDir = Join-Path $RepoRoot 'travel-agent-server'
$CurrentJar = Join-Path $ServerDir 'target\travel-agent-server-0.1.0.jar'
$TmpRoot = Join-Path $PSScriptRoot '.tmp\mysql-upgrade'
$OldSrcDir = Join-Path $TmpRoot 'old-src'
$OldJar = Join-Path $OldSrcDir 'travel-agent-server\target\travel-agent-server-0.1.0.jar'
$Datadir = Join-Path $TmpRoot 'data'
$ClientCnf = Join-Path $TmpRoot 'client.cnf'
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $PSScriptRoot 'evidence' }
$LogDir = Join-Path $EvidenceDir 'mysql84-upgrade-logs'
$ResultJson = Join-Path $EvidenceDir 'mysql84-upgrade-results.json'
$SummaryMd = Join-Path $EvidenceDir 'mysql84-upgrade-summary.md'

$Mysqld = Join-Path $MysqlBase 'bin\mysqld.exe'
$MysqlCli = Join-Path $MysqlBase 'bin\mysql.exe'
$MysqlAdmin = Join-Path $MysqlBase 'bin\mysqladmin.exe'
$JavaExe = 'C:\Program Files\Java\jdk-17.0.1\bin\java.exe'
if (-not (Test-Path $JavaExe)) { $JavaExe = 'java' }
$Mvn = 'mvn'

$AppDb = 'travel_agent'
$AppUser = 'travel'
$AppPw = 'travel_pw'
$Script:JwtSecret = [Convert]::ToBase64String([byte[]](1..48 | ForEach-Object { Get-Random -Maximum 256 }))
$Script:Backend = $null
$Script:MysqlProc = $null
$Script:DbPort = 0
$Script:BackendPort = 0
$Script:Results = New-Object System.Collections.Generic.List[object]
$Script:Summary = New-Object System.Collections.Generic.List[string]
$Script:PhaseTimes = New-Object System.Collections.Generic.List[object]
$Script:Fatal = $null
$pid3306 = ''
$was3307 = $false
$was3317 = $false
$tables16 = '0'
$tables18 = '0'
$countsDigest18 = ''
$countsDigestAfterR1 = ''
$countsDigestAfterR2 = ''
$oldRunId = ''
$taskCount = 0
$tripAId = ''
$oldRunTaskDbCount = 0

function Say([string]$line) { Write-Host $line; $Script:Summary.Add($line) }
function Save([string]$name, [string]$text) {
    $path = Join-Path $LogDir $name
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}
function Assert([string]$id, [bool]$ok, [string]$detail) {
    $Script:Results.Add([pscustomobject]@{ id = $id; ok = $ok; detail = $detail })
    $mark = 'FAIL'; if ($ok) { $mark = 'PASS' }
    Say ("  [{0}] {1} — {2}" -f $mark, $id, $detail)
}
function Native([scriptblock]$sb) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $sb 2>&1
        return [pscustomobject]@{ code = $LASTEXITCODE; out = (($out | Out-String).TrimEnd()) }
    } finally { $ErrorActionPreference = $prev }
}
function PortOpen([int]$port) {
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $port); $c.Close(); return $true } catch { return $false }
}
function Get-PortPids([int]$port) {
    $pids = @()
    $lines = @()
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $lines = & netstat -ano 2>$null } finally { $ErrorActionPreference = $prev }
    foreach ($line in $lines) {
        if ($line -match ('^\s*TCP\s+\S+:' + $port + '\s+\S+\s+LISTENING\s+(\d+)')) { $pids += [int]$Matches[1] }
    }
    return @($pids | Sort-Object -Unique)
}
function Wait-PortClosed([int]$port, [int]$timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (PortOpen $port)) { return $true }
        Start-Sleep -Milliseconds 400
    }
    return (-not (PortOpen $port))
}
function Stop-PortOwners([int]$port) {
    foreach ($procId in (Get-PortPids $port)) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p -and ($p.ProcessName -match '^(mysqld|java)$')) {
            Say "  - 端口 $port 被残留 $($p.ProcessName)（PID $procId）占用，停止它"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
    }
    return (Wait-PortClosed $port 45)
}
function Select-Port([int]$preferred, [int[]]$fallbacks, [string]$kind) {
    foreach ($p in (@($preferred) + $fallbacks)) {
        if (-not (PortOpen $p)) { return $p }
        $reclaim = Stop-PortOwners $p
        if ($reclaim) { return $p }
    }
    throw "没有可用的 $kind 端口（首选 $preferred，回退 $($fallbacks -join ',') 均被占用且无法回收）"
}
function Phase([string]$title) {
    if ($Script:PhaseStart) {
        $Script:PhaseTimes.Add([pscustomobject]@{ phase = $Script:PhaseName; seconds = [math]::Round(((Get-Date) - $Script:PhaseStart).TotalSeconds, 1) })
    }
    $Script:PhaseName = $title
    $Script:PhaseStart = Get-Date
    Say ("`n## {0}   ({1})" -f $title, (Get-Date -Format 'HH:mm:ss'))
}

# ── SQL ────────────────────────────────────────────────────────────────
function DbRaw([string]$sql, [string]$db = "") {
    $argList = @("--defaults-extra-file=$ClientCnf", '-N', '-B')
    if ($db) { $argList += @('-D', $db) }
    $argList += @('-e', $sql)
    return (Native { & $MysqlCli @argList })
}
function DbQuery([string]$sql) {
    $r = DbRaw $sql $AppDb
    if ($r.code -ne 0) { throw "SQL 失败（exit=$($r.code)）：$sql`n$($r.out)" }
    return $r.out
}
function DbTry([string]$sql, [string]$db = "") {
    $r = DbRaw $sql $db
    return [pscustomobject]@{ code = $r.code; out = $r.out }
}
function DbAdmin([string]$sql) {
    $r = DbRaw $sql ''
    if ($r.code -ne 0) { throw "SQL 失败（exit=$($r.code)）：$sql`n$($r.out)" }
    return $r.out
}
function DbExec([string]$sql) {
    $r = DbRaw $sql $AppDb
    if ($r.code -ne 0) { throw "SQL 执行失败（exit=$($r.code)）：$sql`n$($r.out)" }
}
function Rows([string]$sql) {
    $text = DbQuery $sql
    if (-not $text) { return @() }
    return @($text -split "`r?`n" | Where-Object { $_ -ne '' } | ForEach-Object { ,($_ -split "`t") })
}
function HexToText([string]$hex) {
    if (-not $hex) { return '' }
    $bytes = New-Object System.Collections.Generic.List[byte]
    for ($i = 0; $i -lt $hex.Length; $i += 2) { $bytes.Add([Convert]::ToByte($hex.Substring($i, 2), 16)) }
    return [System.Text.Encoding]::UTF8.GetString($bytes.ToArray())
}

# ── MySQL lifecycle ────────────────────────────────────────────────────
function Write-ClientCnf {
    New-Item -ItemType Directory -Force -Path $TmpRoot | Out-Null
    $cnf = "[client]`nhost=127.0.0.1`nport=$Script:DbPort`nuser=root`ndefault-character-set=utf8mb4`n"
    [System.IO.File]::WriteAllText($ClientCnf, $cnf, (New-Object System.Text.UTF8Encoding($false)))
}
function Wait-MysqlReady([int]$timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($Script:MysqlProc -and $Script:MysqlProc.HasExited) { return $false }
        $r = Native { & $MysqlAdmin "--defaults-extra-file=$ClientCnf" ping }
        if ($r.out -match 'mysqld is alive') { return $true }
        Start-Sleep -Milliseconds 800
    }
    return $false
}
function Start-Mysql {
    $myLog = Join-Path $LogDir 'mysqld.log'
    $args = @("--basedir=$MysqlBase", "--datadir=$Datadir", "--port=$Script:DbPort",
        '--bind-address=127.0.0.1', '--mysqlx=OFF',
        '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci',
        "--log-error=$myLog", "--pid-file=$Datadir\mysqld.pid")
    $Script:MysqlProc = Start-Process -FilePath $Mysqld -ArgumentList $args -PassThru -WindowStyle Hidden
    return (Wait-MysqlReady 180)
}
function Stop-Mysql {
    if (-not (Test-Path $ClientCnf)) { return }
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $null = & $MysqlAdmin "--defaults-extra-file=$ClientCnf" shutdown 2>&1 } finally { $ErrorActionPreference = $prev }
    $closed = Wait-PortClosed $Script:DbPort 60
    if (-not $closed) { $null = Stop-PortOwners $Script:DbPort }
    if ($Script:MysqlProc) { Stop-Process -Id $Script:MysqlProc.Id -Force -ErrorAction SilentlyContinue }
}

# ── HTTP / backend lifecycle ───────────────────────────────────────────
function BaseUrl { return "http://127.0.0.1:$Script:BackendPort" }
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
        $text = $r.Content
        if ($text -is [byte[]]) {
            $text = [System.Text.Encoding]::UTF8.GetString($text)
        } elseif ([string]$r.Headers['Content-Type'] -notmatch 'charset=') {
            # PS 5.1 对无 charset 的 JSON 按 ISO-8859-1 解码；这里还原为 UTF-8
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
        return [pscustomobject]@{ code = $(if ($resp) { [int]$resp.StatusCode } else { -1 }); json = $null; raw = $raw; error = $_.Exception.Message }
    }
}
function Start-Backend([string]$tag, [string]$jar, [string[]]$extraArgs = @()) {
    if (-not (Test-Path $jar)) { throw "jar 不存在：$jar" }
    $appDir = Join-Path $TmpRoot "app-$tag"
    New-Item -ItemType Directory -Force -Path $appDir | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $appDir 'data\invoices') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $appDir 'data\photos') | Out-Null
    $out = Join-Path $LogDir "backend-$tag.log"
    $err = Join-Path $LogDir "backend-$tag-stderr.log"
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:MYSQL_HOST = '127.0.0.1'
    $env:MYSQL_PORT = "$Script:DbPort"
    $env:MYSQL_DATABASE = $AppDb
    $env:MYSQL_USERNAME = $AppUser
    $env:MYSQL_PASSWORD = $AppPw
    $env:JWT_SECRET = $Script:JwtSecret
    $env:DEMO_ACCOUNT = 'true'
    $env:LLM_ENABLED = 'false'
    $env:RATE_LIMIT_ENABLED = 'false'
    $env:WEATHER_SIMULATION = 'true'
    $env:AMAP_KEY = ''
    $env:EMBEDDING_ENABLED = 'false'
    $env:INVOICE_DIR = (Join-Path $appDir 'data\invoices')
    $env:PHOTOS_DIR = (Join-Path $appDir 'data\photos')
    Remove-Item Env:SPRING_FLYWAY_TARGET -ErrorAction SilentlyContinue
    $args = @('-jar', $jar, "--server.port=$Script:BackendPort") + $extraArgs
    $p = Start-Process -FilePath $JavaExe -ArgumentList $args -WorkingDirectory $appDir `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    return [pscustomobject]@{ proc = $p; started = (Get-Date); log = $out; tag = $tag; dir = $appDir }
}
function Wait-Healthy($backend, [int]$timeoutSec = 360) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($backend.proc.HasExited) { return $false }
        $h = Http "$(BaseUrl)/actuator/health" 'GET' $null $null 5
        if ($h.code -eq 200 -and $h.json -and $h.json.status -eq 'UP') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}
function Stop-Backend {
    if ($Script:Backend -and $Script:Backend.proc) {
        Stop-Process -Id $Script:Backend.proc.Id -Force -ErrorAction SilentlyContinue
    }
    # javapath 包装器可能留下真正的 JVM，按监听端口兜底
    $null = Stop-PortOwners $Script:BackendPort
    $null = Wait-PortClosed $Script:BackendPort 45
    $Script:Backend = $null
}
function Start-BackendChecked([string]$tag, [string]$jar, [string[]]$extraArgs = @(), [int]$timeoutSec = 360) {
    Stop-Backend
    $b = Start-Backend $tag $jar $extraArgs
    $Script:Backend = $b
    if (-not (Wait-Healthy $b $timeoutSec)) {
        $tail = ''
        if (Test-Path $b.log) { $tail = ((Get-Content $b.log -ErrorAction SilentlyContinue | Select-Object -Last 60) -join "`n") }
        Save "backend-fail-$tag.log" $tail
        throw "后端 $tag 未在 ${timeoutSec}s 内健康；日志：$($b.log)"
    }
    $secs = [math]::Round(((Get-Date) - $b.started).TotalSeconds, 1)
    Say "- 后端 [$tag] 健康，耗时 ${secs}s（pid=$($b.proc.Id)）"
    return [pscustomobject]@{ backend = $b; seconds = $secs }
}
function Login([string]$user = 'demo', [string]$pass = 'demo123') {
    $r = Http "$(BaseUrl)/api/auth/login" 'POST' @{ username = $user; password = $pass }
    if ($r.code -ne 200 -or -not $r.json.data.token) { throw "登录失败：HTTP $($r.code) $($r.raw)" }
    return $r.json.data.token
}
function FlywayHistoryDigest {
    return (DbQuery "SELECT CONCAT(COUNT(*),'|',IFNULL(MD5(GROUP_CONCAT(CONCAT_WS('|',installed_rank,version,checksum,installed_on) ORDER BY installed_rank SEPARATOR ';')),'EMPTY')) FROM flyway_schema_history;")
}
function FlywayOldDigest {
    return (DbQuery "SELECT CONCAT(COUNT(*),'|',IFNULL(MD5(GROUP_CONCAT(CONCAT_WS('|',installed_rank,version,checksum,installed_on) ORDER BY installed_rank SEPARATOR ';')),'EMPTY')) FROM flyway_schema_history WHERE CAST(version AS UNSIGNED) <= 16;")
}
function CountsDigest {
    return (DbQuery ("SELECT CONCAT(" +
        "'trip=',(SELECT COUNT(*) FROM trip),'|item=',(SELECT COUNT(*) FROM itinerary_item)," +
        "'|expense=',(SELECT COUNT(*) FROM expense),'|memo=',(SELECT COUNT(*) FROM memo)," +
        "'|schedule=',(SELECT COUNT(*) FROM schedule_item),'|pending=',(SELECT COUNT(*) FROM pending_plan)," +
        "'|exec=',(SELECT COUNT(*) FROM plan_execution),'|run=',(SELECT COUNT(*) FROM agent_run)," +
        "'|task=',(SELECT COUNT(*) FROM agent_task),'|tool=',(SELECT COUNT(*) FROM tool_call_trace)," +
        "'|user=',(SELECT COUNT(*) FROM users));"))
}

# ── data snapshots ─────────────────────────────────────────────────────
function Get-TableSnapshot($columnsByTable) {
    $tableRows = Rows "SELECT table_name FROM information_schema.tables WHERE table_schema='$AppDb' AND table_type='BASE TABLE' ORDER BY table_name;"
    $out = @()
    foreach ($tr in $tableRows) {
        $t = [string]$tr[0]
        if ($t -eq 'flyway_schema_history') { continue }
        $colsRaw = (DbQuery "SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') FROM information_schema.columns WHERE table_schema='$AppDb' AND table_name='$t';").Trim()
        $cols = @($colsRaw -split ',' | Where-Object { $_ -ne '' })
        if ($columnsByTable -and $columnsByTable.ContainsKey($t)) { $cols = @($columnsByTable[$t] -split ',') }
        $pk = (DbQuery "SELECT column_name FROM information_schema.key_column_usage WHERE table_schema='$AppDb' AND table_name='$t' AND constraint_name='PRIMARY' LIMIT 1;").Trim()
        if (-not $pk) { $pk = $cols[0] }
        $expr = ($cols | ForEach-Object { "COALESCE(CAST(``$_`` AS CHAR),'~NULL~')" }) -join ','
        $sql = "SET SESSION group_concat_max_len=67108864; SELECT CONCAT(COUNT(*),'|',IFNULL(MD5(GROUP_CONCAT(rh ORDER BY ``$pk`` SEPARATOR '')),'EMPTY')) FROM (SELECT MD5(CONCAT_WS('|',$expr)) AS rh, ``$pk`` FROM ``$t``) x;"
        $parts = (DbQuery $sql).Split('|')
        $out += [pscustomobject]@{ table = $t; rows = [int64]$parts[0]; hash = $parts[1]; columns = ($cols -join ',') }
    }
    return $out
}
function SnapshotColumns {
    $raw = Rows "SELECT table_name, GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') FROM information_schema.columns WHERE table_schema='$AppDb' GROUP BY table_name;"
    $map = @{}
    foreach ($r in $raw) { $map[[string]$r[0]] = [string]$r[1] }
    return $map
}
function KeyFieldSnapshot {
    $snap = [ordered]@{}
    $snap.trips = @(Rows "SELECT id, user_id, HEX(COALESCE(name,'')), HEX(COALESCE(destination,'')), start_date, end_date, budget, status, version, created_at FROM trip ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; user_id = $_[1]; name = (HexToText $_[2]); destination = (HexToText $_[3]); start_date = $_[4]; end_date = $_[5]; budget = $_[6]; status = $_[7]; version = $_[8]; created_at = $_[9] } })
    $snap.itinerary_items = @(Rows "SELECT id, trip_id, day_index, item_type, HEX(COALESCE(title,'')), start_time, cost FROM itinerary_item ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; trip_id = $_[1]; day_index = $_[2]; item_type = $_[3]; title = (HexToText $_[4]); start_time = $_[5]; cost = $_[6] } })
    $snap.expenses = @(Rows "SELECT id, trip_id, HEX(COALESCE(category,'')), amount, HEX(COALESCE(note,'')), spent_at, HEX(COALESCE(payment_method,'')) FROM expense ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; trip_id = $_[1]; category = (HexToText $_[2]); amount = $_[3]; note = (HexToText $_[4]); spent_at = $_[5]; payment_method = (HexToText $_[6]) } })
    $snap.memos = @(Rows "SELECT id, user_id, HEX(COALESCE(content,'')), done FROM memo ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; user_id = $_[1]; content = (HexToText $_[2]); done = $_[3] } })
    $snap.schedule_items = @(Rows "SELECT id, user_id, HEX(COALESCE(title,'')), sched_date, sched_time, done FROM schedule_item ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; user_id = $_[1]; title = (HexToText $_[2]); sched_date = $_[3]; sched_time = $_[4]; done = $_[5] } })
    $snap.agent_runs = @(Rows "SELECT id, run_id, user_id, trip_id, intent, status, started_at FROM agent_run ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; run_id = $_[1]; user_id = $_[2]; trip_id = $_[3]; intent = $_[4]; status = $_[5]; started_at = $_[6] } })
    $snap.agent_tasks = @(Rows "SELECT id, task_id, run_id, task_type, status, started_at FROM agent_task ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; task_id = $_[1]; run_id = $_[2]; task_type = $_[3]; status = $_[4]; started_at = $_[5] } })
    $snap.pending_plans = @(Rows "SELECT plan_id, user_id, trip_id, status, plan_revision, created_at FROM pending_plan ORDER BY created_at, plan_id;" | ForEach-Object {
        [ordered]@{ plan_id = $_[0]; user_id = $_[1]; trip_id = $_[2]; status = $_[3]; plan_revision = $_[4]; created_at = $_[5] } })
    $snap.plan_executions = @(Rows "SELECT execution_id, plan_id, user_id, trip_id, status, created_at FROM plan_execution ORDER BY id;" | ForEach-Object {
        [ordered]@{ execution_id = $_[0]; plan_id = $_[1]; user_id = $_[2]; trip_id = $_[3]; status = $_[4]; created_at = $_[5] } })
    $snap.users = @(Rows "SELECT id, username, nickname FROM users ORDER BY id;" | ForEach-Object {
        [ordered]@{ id = $_[0]; username = $_[1]; nickname = $_[2] } })
    return $snap
}
function JsonOut($obj) { return ($obj | ConvertTo-Json -Depth 12) }

# ── build artifacts ────────────────────────────────────────────────────
function Ensure-Artifacts {
    New-Item -ItemType Directory -Force -Path $TmpRoot | Out-Null
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    if (-not (Test-Path $Mysqld)) { throw "找不到便携 mysqld：$Mysqld（预期在 .tools\mysql84\mysql-8.4.10-winx64）" }
    if (-not (Test-Path $MysqlCli)) { throw "找不到便携 mysql：$MysqlCli" }
    $needCur = ((-not (Test-Path $CurrentJar)) -or $Rebuild)
    if ($needCur) {
        Say "- 构建当前版本 jar（mvn -B -DskipTests package）..."
        $r = Native { & $Mvn -B -DskipTests package -f (Join-Path $ServerDir 'pom.xml') }
        [System.IO.File]::WriteAllText((Join-Path $LogDir 'build-current.log'), $r.out, (New-Object System.Text.UTF8Encoding($false)))
        if ($r.code -ne 0 -or -not (Test-Path $CurrentJar)) { throw "当前版本构建失败（exit=$($r.code)），见 build-current.log" }
        Say "- 当前版本 jar 构建完成：$CurrentJar"
    } else {
        Say "- 复用已构建的当前版本 jar：$CurrentJar"
    }
    $needOld = ((-not (Test-Path $OldJar)) -or $Rebuild)
    if ($needOld) {
        if (-not (Test-Path $OldSrcDir)) {
            Say "- 从 git 归档旧版本提交 $OldCommit 的源码（模拟旧部署，不改动主仓/当前 worktree 的 src）..."
            New-Item -ItemType Directory -Force -Path $TmpRoot | Out-Null
            $zip = Join-Path $TmpRoot "old-$OldCommit.zip"
            $git = Native { & git -C $RepoRoot archive --format=zip -o $zip $OldCommit }
            if ($git.code -ne 0) { throw "git archive $OldCommit 失败：$($git.out)" }
            Expand-Archive -Path $zip -DestinationPath $OldSrcDir -Force
        }
        Say "- 构建旧版本 jar（提交 $OldCommit，其最高迁移=V16）..."
        $r2 = Native { & $Mvn -B -DskipTests package -f (Join-Path $OldSrcDir 'travel-agent-server\pom.xml') }
        [System.IO.File]::WriteAllText((Join-Path $LogDir 'build-old.log'), $r2.out, (New-Object System.Text.UTF8Encoding($false)))
        if ($r2.code -ne 0 -or -not (Test-Path $OldJar)) { throw "旧版本构建失败（exit=$($r2.code)），见 build-old.log" }
        Say "- 旧版本 jar 构建完成：$OldJar"
    } else {
        Say "- 复用已构建的旧版本 jar：$OldJar"
    }
}

# ============================================================================
# 主流程
# ============================================================================
$scriptStart = Get-Date
try {
    New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    Say "# MySQL 8.4 旧库升级 + 三态重启 正式回归"
    Say "- 开始时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    Say "- worktree：$RepoRoot"
    Say "- 便携 MySQL：$MysqlBase"
    Say "- 证据目录：$EvidenceDir"
    Say "- 本机：Windows $([System.Environment]::OSVersion.Version)，无 Docker（原生 mysqld，不涉及 docker compose）"

    # ── 0. 预检 ────────────────────────────────────────────────
    Phase '0. 预检（端口隔离 / 残留清理 / 构建）'
    $p3306 = Get-PortPids 3306
    $pid3306 = ''
    if ($p3306.Count -gt 0) { $pid3306 = [string]$p3306[0] }
    Say "- 3306 既有 MySQL 监听 PID：$pid3306"
    Assert 'E1:3306 既有 MySQL 服务在监听' ([bool]$pid3306) "pid=$pid3306"
    $was3307 = PortOpen 3307
    $was3317 = PortOpen 3317
    Say "- 预检时 3307/3317 是否在监听：$was3307 / $was3317（本脚本不会使用它们）"

    # 清理上一次可能残留的实例（只针对本脚本专用端口，绝不碰 3306/3307/3317）
    foreach ($p in 3318, 3319, 3320, 18094, 18095, 18096) {
        if (PortOpen $p) { $null = Stop-PortOwners $p }
    }
    if (Test-Path $Datadir) { Remove-Item $Datadir -Recurse -Force }
    if (Test-Path $LogDir) { Get-ChildItem $LogDir -File | Remove-Item -Force }
    New-Item -ItemType Directory -Force -Path $Datadir | Out-Null

    Ensure-Artifacts

    $Script:DbPort = Select-Port $PreferredDbPort @(3319, 3320) 'mysql'
    $Script:BackendPort = Select-Port $PreferredBackendPort @(18095, 18096) 'backend'
    Say "- 实例端口：MySQL=$Script:DbPort，后端=$Script:BackendPort（首选 3318/18094，占用则顺延）"
    Assert 'E2:端口与既有 MySQL 隔离' ($Script:DbPort -ne 3306 -and $Script:DbPort -ne 3307 -and $Script:DbPort -ne 3317) "dbPort=$Script:DbPort"
    Assert 'E3:后端端口非 8080/既有服务' ($Script:BackendPort -ne 8080) "backendPort=$Script:BackendPort"
    Write-ClientCnf

    # ── 1. 全新实例 ────────────────────────────────────────────
    Phase '1. 初始化全新 MySQL 8.4 实例（独立 datadir）'
    $initLog = Join-Path $LogDir 'mysqld-initialize.log'
    $initErr = Join-Path $LogDir 'mysqld-initialize-stderr.log'
    $initArgs = @("--initialize-insecure", "--basedir=$MysqlBase", "--datadir=$Datadir", '--console')
    $init = Native { & $Mysqld @initArgs }
    [System.IO.File]::WriteAllText($initLog, $init.out, (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::WriteAllText($initErr, '', (New-Object System.Text.UTF8Encoding($false)))
    $initExit = $init.code
    $initOk = Test-Path (Join-Path $Datadir 'mysql')
    Say "- mysqld --initialize-insecure 结束（exit=$initExit），datadir 系统表存在=$initOk"
    Assert 'M1:全新 datadir 初始化成功' ($initExit -eq 0 -and $initOk) "exit=$initExit systemTables=$initOk"
    if (-not (Start-Mysql)) { throw "mysqld 未能在 $Script:DbPort 就绪，见 $LogDir\mysqld.log" }
    Say "- mysqld 已就绪（pid=$($Script:MysqlProc.Id)）"
    $ver = DbAdmin 'SELECT VERSION();'
    $dport = DbAdmin 'SELECT @@port;'
    $ddir = DbAdmin 'SELECT @@datadir;'
    $bdir = DbAdmin 'SELECT @@basedir;'
    Say "- SELECT VERSION(): $ver"
    Say "- @@port=$dport  @@datadir=$ddir"
    Assert 'M2:MySQL 版本=8.4.10' ($ver -eq '8.4.10') "VERSION()=$ver"
    Assert 'M3:实例端口正确' ($dport -eq [string]$Script:DbPort) "port=$dport"
    $ddirNorm = $ddir
    $ddirNorm = $ddirNorm -replace '\\\\', '/'
    $ddirNorm = $ddirNorm -replace '\\', '/'
    $ddirNorm = $ddirNorm -replace '//', '/'
    Assert 'M4:独立 datadir' ($ddirNorm -match 'mysql-upgrade/data/?$') "datadir=$ddirNorm"
    # 建库 + 应用账号（此时库还不存在，不能用 -D）
    DbAdmin "CREATE DATABASE IF NOT EXISTS $AppDb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '$AppUser'@'%' IDENTIFIED BY '$AppPw'; CREATE USER IF NOT EXISTS '$AppUser'@'localhost' IDENTIFIED BY '$AppPw'; GRANT ALL PRIVILEGES ON $AppDb.* TO '$AppUser'@'%'; GRANT ALL PRIVILEGES ON $AppDb.* TO '$AppUser'@'localhost'; FLUSH PRIVILEGES;"
    $empty = DbAdmin "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$AppDb';"
    Assert 'M5:迁移前是空库' ($empty -eq '0') "tables=$empty"

    # ── 2a. target=15 探针 ─────────────────────────────────────
    Phase '2a. 旧部署探针：spring.flyway.target=15（验证 V15 能否作为可运行旧基线）'
    $v15 = Start-Backend 'old-v15' $OldJar @('--spring.flyway.target=15')
    $v15deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $v15deadline -and -not $v15.proc.HasExited) { Start-Sleep -Milliseconds 500 }
    $v15Exited = $v15.proc.HasExited
    if (-not $v15Exited) { Stop-Process -Id $v15.proc.Id -Force -ErrorAction SilentlyContinue }
    Say "- target=15 启动进程已退出=$v15Exited"
    $v15log = ''
    if (Test-Path $v15.log) { $v15log = Get-Content $v15.log -Raw -ErrorAction SilentlyContinue }
    $v15logErr = ''
    if (Test-Path (Join-Path $LogDir 'backend-old-v15-stderr.log')) { $v15logErr = Get-Content (Join-Path $LogDir 'backend-old-v15-stderr.log') -Raw -ErrorAction SilentlyContinue }
    $v15lines = (($v15log + "`n" + $v15logErr) -split "`n" | Select-String -Pattern 'Migrating schema|Successfully applied|now at version|Schema-validation|WARN.*MySQ|missing column|missing table|APPLICATION FAILED' | ForEach-Object { $_.Line.Trim() }) -join "`n"
    Save 'flyway-v15-probe.txt' $v15lines
    ($v15lines -split "`n" | Where-Object { $_ } | Select-Object -First 10) | ForEach-Object { Say "    $_" }
    $v15hist = DbTry 'SELECT CONCAT(COUNT(*),''|'',IFNULL(MAX(CAST(version AS UNSIGNED)),0),''|'',IFNULL(SUM(success=0),0)) FROM flyway_schema_history;' $AppDb
    Assert 'O1:target=15 时库停在 V15' ($v15hist.code -eq 0 -and $v15hist.out -match '^14\|15\|0$') "history=$($v15hist.out)（MySQL 无 V14，V14 仅 H2；V1..V13+V15=14 条）"
    $v15Missing = [bool]($v15lines -match 'Schema-validation: missing column')
    Assert 'O2:V15 不能作为可运行旧基线（校验缺列，预期）' ($v15Exited -and $v15Missing) "启动失败且报告 Schema-validation 缺列（V16 实体列，如 pending_plan.content_hash）"

    # ── 2b. 真实旧部署 V16 ─────────────────────────────────────
    Phase '2b. 旧部署模拟：旧版发布代码（提交 9918a4f，最高迁移=V16）+ target=16'
    $oldBoot = Start-BackendChecked 'old-v16' $OldJar @('--spring.flyway.target=16') 360
    Say "- 旧版后端启动耗时 $($oldBoot.seconds)s"
    $oldLog = Get-Content (Join-Path $LogDir 'backend-old-v16.log') -Raw -ErrorAction SilentlyContinue
    $oldFlyway = (($oldLog -split "`n") | Select-String -Pattern 'Migrating schema|Successfully applied|now at version|Schema-validation|Flyway upgrade recommended|Started TravelAgent' | ForEach-Object { $_.Line.Trim() }) -join "`n"
    Save 'flyway-old-v16.txt' $oldFlyway
    ($oldFlyway -split "`n" | Where-Object { $_ }) | ForEach-Object { Say "    $_" }
    $h16 = DbQuery "SELECT CONCAT(COUNT(*),'|',IFNULL(MAX(CAST(version AS UNSIGNED)),0),'|',IFNULL(SUM(success=0),0)) FROM flyway_schema_history;"
    $dup16 = DbQuery "SELECT COUNT(*) FROM (SELECT version FROM flyway_schema_history GROUP BY version HAVING COUNT(*)>1) d;"
    $tables16 = DbQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$AppDb' AND table_type='BASE TABLE';"
    Say "- V16 历史：$h16；表数=$tables16；重复版本行=$dup16"
    DbQuery "SELECT installed_rank,version,description,type,success,installed_on,execution_time FROM flyway_schema_history ORDER BY installed_rank;" | Set-Content -Path (Join-Path $LogDir 'flyway-history-v16.tsv') -Encoding UTF8
    Assert 'F1:旧版后端启动成功' ([bool]($oldLog -match 'Started TravelAgent')) "target=16，耗时 $($oldBoot.seconds)s"
    Assert 'F2:迁移停在 V16' ($h16 -match '^15\|16\|0$') "history=$h16（V1..V13+V15+V16=15 条）"
    Assert 'F3:旧 schema 无重复版本行' ($dup16 -eq '0') "duplicates=$dup16"
    Assert 'F4:ddl-auto=validate 通过（无 Schema-validation）' ([bool](-not ($oldLog -match 'Schema-validation'))) '旧实体与 V16 schema 一致'
    $warnLine = (($oldLog -split "`n") | Select-String -Pattern 'Flyway upgrade recommended: MySQL 8\.4' | ForEach-Object { $_.Line.Trim() } | Select-Object -First 1)
    Assert 'F5:MySQL 8.4 仅 WARN（Flyway 支持到 8.1）' ([bool]$warnLine) $(if ($warnLine) { '仅 WARN，迁移与 validate 通过：' + $warnLine } else { '未捕获到预期 WARN' })
    $partyColPre = DbQuery "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$AppDb' AND table_name='trip' AND column_name='party_size';"
    $taskColPre = DbQuery "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$AppDb' AND table_name='agent_task' AND column_name='depends_on';"
    Assert 'F6:旧 schema 确实缺后续迁移的列' ($partyColPre -eq '0' -and $taskColPre -eq '0') "trip.party_size=$partyColPre agent_task.depends_on=$taskColPre"
    Assert 'F7:V16 表数量已记录' ([int]$tables16 -ge 20) "tables=$tables16"

    # ── 3. 真实业务数据 ────────────────────────────────────────
    Phase '3. 通过真实 HTTP API 灌入业务数据（旧 schema V16）'
    $token = Login
    Assert 'D1:登录成功（demo/demo123）' ([bool]$token) "tokenLen=$($token.Length)"
    $tripA = Http "$(BaseUrl)/api/trips" 'POST' @{ name = '旧库升级基线-苏州五日'; destination = '苏州'; startDate = '2026-12-01'; endDate = '2026-12-05'; budget = 8000 } $token
    if ($tripA.code -ne 200) { throw "建行程失败：HTTP $($tripA.code) $($tripA.raw)" }
    $tripAId = $tripA.json.data.id
    Save 'api-trip-create.json' $tripA.raw
    Assert 'D2:HTTP 建行程成功' ([bool]$tripAId) "tripId=$tripAId destination=$($tripA.json.data.destination) budget=$($tripA.json.data.budget) status=$($tripA.json.data.status)"

    $itemDefs = @(
        @{ dayIndex = 1; itemType = 'attraction'; title = '拙政园'; startTime = '09:00'; endTime = '11:00'; cost = 90; latitude = 31.3242; longitude = 120.6290; tags = @('室外'); description = '江南古典园林' },
        @{ dayIndex = 2; itemType = 'food'; title = '平江路午餐'; startTime = '12:00'; endTime = '13:30'; cost = 128.5; latitude = 31.3140; longitude = 120.6320; tags = @('室内'); description = '苏帮菜' },
        @{ dayIndex = 3; itemType = 'hotel'; title = '金鸡湖酒店'; startTime = '15:00'; endTime = '18:00'; cost = 680; latitude = 31.3170; longitude = 120.6740; tags = @('室内'); description = '湖景房' }
    )
    $itemIds = @()
    foreach ($d in $itemDefs) {
        $r = Http "$(BaseUrl)/api/trips/$tripAId/itinerary" 'POST' $d $token
        if ($r.code -ne 200) { throw "写行程条目失败：HTTP $($r.code) $($r.raw)" }
        $itemIds += $r.json.data.id
    }
    Save 'api-itinerary-add.json' ($itemIds -join ',')
    Assert 'D3:HTTP 写行程条目成功' ($itemIds.Count -eq 3) "itemIds=$($itemIds -join ',')"

    $exp1 = Http "$(BaseUrl)/api/trips/$tripAId/expenses" 'POST' @{ category = '餐饮'; amount = 128.50; note = '平江路午饭'; spentAt = '2026-12-02'; paymentMethod = '微信支付' } $token
    $exp2 = Http "$(BaseUrl)/api/trips/$tripAId/expenses" 'POST' @{ category = '门票'; amount = 90.00; note = '拙政园门票'; spentAt = '2026-12-01'; paymentMethod = '支付宝' } $token
    if ($exp1.code -ne 200 -or $exp2.code -ne 200) { throw "记支出失败：$($exp1.code)/$($exp2.code) $($exp1.raw) $($exp2.raw)" }
    Save 'api-expense-add.json' ($exp1.raw + "`n" + $exp2.raw)
    Assert 'D4:HTTP 记支出成功（预算口径）' ($exp1.code -eq 200 -and $exp2.code -eq 200) "amounts=128.50/90.00"

    $memo1 = Http "$(BaseUrl)/api/memos" 'POST' @{ content = '升级前备忘：旧库里的真实数据' } $token
    $memo2 = Http "$(BaseUrl)/api/memos" 'POST' @{ content = '升级前备忘：第二条' } $token
    if ($memo1.code -ne 200 -or $memo2.code -ne 200) { throw "写备忘失败：$($memo1.code)/$($memo2.code)" }
    $sch1 = Http "$(BaseUrl)/api/schedule" 'POST' @{ title = '升级回归提醒'; date = '2026-12-01'; time = '09:30'; note = '旧库日程'; recurrence = 'NONE' } $token
    if ($sch1.code -ne 200) { throw "写日程失败：HTTP $($sch1.code) $($sch1.raw)" }
    Assert 'D5:HTTP 写备忘/日程成功' ($memo1.code -eq 200 -and $memo2.code -eq 200 -and $sch1.code -eq 200) "memos=2 schedule=1"

    $chat = Http "$(BaseUrl)/api/agent/chat" 'POST' @{ message = '杭州两日游，两个人，预算5000元' } $token 180
    Save 'api-agent-chat.json' $chat.raw
    if ($chat.code -ne 200) { throw "规划失败：HTTP $($chat.code) $($chat.raw)" }
    $planId = $chat.json.data.planId
    $contentHash = ''
    if ($chat.json.data.confirmation) { $contentHash = $chat.json.data.confirmation.contentHash }
    $chatStatus = $chat.json.data.status
    Say "- 规划：status=$chatStatus planId=$planId contentHash=$contentHash changes=$(@($chat.json.data.diff.changes).Count)"
    $confirmBody = @{ planId = $planId; contentHash = $contentHash }
    if ($chat.json.data.confirmation -and $chat.json.data.confirmation.requiresAcknowledgement) { $confirmBody.acknowledgeIssues = $true }
    $confirm = Http "$(BaseUrl)/api/agent/confirm" 'POST' $confirmBody $token 180
    Save 'api-agent-confirm.json' $confirm.raw
    if ($confirm.code -ne 200) { throw "确认失败：HTTP $($confirm.code) $($confirm.raw)" }
    $tripBId = $confirm.json.data.tripId
    $execId = ''
    if ($confirm.json.data.execution) { $execId = $confirm.json.data.execution.executionId }
    Assert 'D6:HTTP 触发规划 run 并待确认' ($chat.code -eq 200 -and $chatStatus -eq 'NEED_CONFIRMATION' -and [bool]$planId -and [bool]$contentHash) "status=$chatStatus planId=$planId hashLen=$($contentHash.Length)"
    Assert 'D7:确认落库生成行程与执行台账' ([bool]$tripBId -and [bool]$execId) "confirmStatus=$($confirm.json.data.status) tripId=$tripBId executionId=$execId"
    $runs = Http "$(BaseUrl)/api/agent/runs?limit=20" 'GET' $null $token
    $runList = @($runs.json.data)
    $oldRunId = ''
    if ($runList.Count -gt 0) { $oldRunId = [string]$runList[0].runId }
    Assert 'D8:run 已落库且可回查' ($runList.Count -ge 1 -and [bool]$oldRunId) "runs=$($runList.Count) runId=$oldRunId"
    $taskCount = 0
    $toolCount = 0
    if ($oldRunId) {
        $runDetail = Http "$(BaseUrl)/api/agent/runs/$oldRunId" 'GET' $null $token
        Save 'api-run-detail.json' $runDetail.raw
        $taskCount = @($runDetail.json.data.tasks).Count
        $toolCount = @($runDetail.json.data.toolCalls).Count
    }
    Assert 'D9:旧 run 的任务链/工具 trace 已落库' ($oldRunId -and $taskCount -gt 0 -and $toolCount -gt 0) "tasks=$taskCount toolCalls=$toolCount"

    # 再留一份待确认方案，确保 pending_plan 有旧行
    $chat2 = Http "$(BaseUrl)/api/agent/chat" 'POST' @{ message = '杭州两日游，两个人，预算2000元' } $token 180
    if ($chat2.code -ne 200) { throw "第二次规划失败：HTTP $($chat2.code) $($chat2.raw)" }
    $pendCount = DbQuery "SELECT COUNT(*) FROM pending_plan;"
    Assert 'D10:pending_plan 有旧行' ([int]$pendCount -ge 1) "pendingPlanRows=$pendCount chat2Status=$($chat2.json.data.status)"

    # 直接 SQL 补写：覆盖后续迁移新增列（trip.party_size / agent_task 的 DAG 列）所在的旧行
    $userId = (DbQuery "SELECT id FROM users WHERE username='demo';").Trim()
    DbExec "INSERT INTO trip (user_id, name, destination, start_date, end_date, budget, status, version, created_at) VALUES ($userId, 'SQL-SEED-OLD-TRIP', 'SQL-SEED-DEST', '2026-12-10', '2026-12-12', 1234.56, 'PLANNING', 0, NOW());"
    $sqlTripId = (DbQuery "SELECT id FROM trip WHERE name='SQL-SEED-OLD-TRIP' ORDER BY id DESC LIMIT 1;").Trim()
    DbExec "INSERT INTO agent_task (task_id, run_id, user_id, receiver, task_type, status, summary, started_at) VALUES ('sql-seed-old-task', '$oldRunId', $userId, 'sql-seed-agent', 'SQL_SEED', 'SUCCEEDED', 'old schema seed row', NOW());"
    $sqlTaskId = (DbQuery "SELECT task_id FROM agent_task WHERE task_id='sql-seed-old-task';").Trim()
    $oldRunTaskDbCount = [int](DbQuery "SELECT COUNT(*) FROM agent_task WHERE run_id='$oldRunId';")
    Assert 'D11:直接 SQL 补写覆盖后续迁移列' ([bool]$sqlTripId -and $sqlTaskId -eq 'sql-seed-old-task') "tripId=$sqlTripId agentTask=$sqlTaskId（挂在旧 run 下，故该 run 任务数 $taskCount→$oldRunTaskDbCount）"

    $preTables = @(Get-TableSnapshot $null)
    $preColumns = SnapshotColumns
    $preKey = KeyFieldSnapshot
    $preCounts = CountsDigest
    $preHistory = FlywayHistoryDigest
    $preOldHistory = FlywayOldDigest
    $preKeyTables = @('users', 'trip', 'itinerary_item', 'expense', 'memo', 'schedule_item', 'pending_plan', 'plan_execution', 'agent_run', 'agent_task', 'tool_call_trace')
    $preTableMap = @{}
    foreach ($t in $preTables) { $preTableMap[$t.table] = $t }
    $mustHave = @('trip', 'itinerary_item', 'expense', 'memo', 'schedule_item', 'pending_plan', 'plan_execution', 'agent_run', 'agent_task', 'tool_call_trace', 'users')
    $missing = @($mustHave | Where-Object { -not $preTableMap.ContainsKey($_) })
    Assert 'D12:关键表齐全' ($missing.Count -eq 0) "tables=$($preTables.Count) missing=$($missing -join ',')"
    $zeroTables = @($mustHave | Where-Object { $preTableMap[$_].rows -le 0 })
    Assert 'D13:关键表均有真实数据' ($zeroTables.Count -eq 0) (($mustHave | ForEach-Object { "$_=$($preTableMap[$_].rows)" }) -join ' ') 
    [System.IO.File]::WriteAllText((Join-Path $LogDir 'snapshot-pre-upgrade.json'), (JsonOut ([ordered]@{
        capturedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'); counts = $preCounts; history = $preHistory;
        tables = $preTables; keyFields = $preKey })), (New-Object System.Text.UTF8Encoding($false)))
    Say "- 升级前快照：$($preTables.Count) 张表；关键表行数：$(($mustHave | ForEach-Object { "$_=$($preTableMap[$_].rows)" }) -join ' ')"
    Say "- 升级前关键字段快照已保存（pre-upgrade keyFields：trips=$($preKey.trips.Count) items=$($preKey.itinerary_items.Count) runs=$($preKey.agent_runs.Count) tasks=$($preKey.agent_tasks.Count)）"

    # ── 4. 升级到 latest ──────────────────────────────────────
    Phase '4. 旧库增量升级：停旧后端 → 最新代码（target=latest）启动'
    Stop-Backend
    Assert 'U0:旧后端已停止' (-not (PortOpen $Script:BackendPort)) "backendPort=$Script:BackendPort closed"
    $newBoot = Start-BackendChecked 'new-v18' $CurrentJar @('--spring.flyway.target=latest') 360
    $newLog = Get-Content (Join-Path $LogDir 'backend-new-v18.log') -Raw -ErrorAction SilentlyContinue
    $newFlyway = (($newLog -split "`n") | Select-String -Pattern 'Migrating schema|Successfully applied|now at version|Schema-validation|Flyway upgrade recommended|Started TravelAgent' | ForEach-Object { $_.Line.Trim() }) -join "`n"
    Save 'flyway-upgrade-v18.txt' $newFlyway
    ($newFlyway -split "`n" | Where-Object { $_ }) | ForEach-Object { Say "    $_" }
    $h18 = DbQuery "SELECT CONCAT(COUNT(*),'|',IFNULL(MAX(CAST(version AS UNSIGNED)),0),'|',IFNULL(SUM(success=0),0)) FROM flyway_schema_history;"
    $dup18 = DbQuery "SELECT COUNT(*) FROM (SELECT version FROM flyway_schema_history GROUP BY version HAVING COUNT(*)>1) d;"
    $tables18 = DbQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$AppDb' AND table_type='BASE TABLE';"
    Say "- V18 历史：$h18；表数=$tables18；重复版本行=$dup18"
    DbQuery "SELECT installed_rank,version,description,type,success,installed_on,execution_time FROM flyway_schema_history ORDER BY installed_rank;" | Set-Content -Path (Join-Path $LogDir 'flyway-history-v18.tsv') -Encoding UTF8
    Assert 'U1:最新代码启动成功且 validate 通过' ([bool]($newLog -match 'Started TravelAgent') -and -not ($newLog -match 'Schema-validation')) "启动 $($newBoot.seconds)s"
    $migrated = @(($newLog -split "`n") | Select-String -Pattern 'Migrating schema' | ForEach-Object { $_.Line.Trim() })
    $onlyNew = ($migrated.Count -eq 2) -and ($migrated -join ' ') -match 'version "17' -and ($migrated -join ' ') -match 'version "18'
    Assert 'U2:增量迁移只应用 V17/V18' $onlyNew ("migrated=" + (($migrated | ForEach-Object { ($_ -split 'to version ')[1] }) -join ','))
    Assert 'U3:迁移到 V18 且全部成功' ($h18 -match '^17\|18\|0$') "history=$h18"
    Assert 'U4:flyway_schema_history 无重复版本行' ($dup18 -eq '0') "duplicates=$dup18"
    Assert 'U5:升级未新增业务表（只加列）' ($tables18 -eq $tables16) "tablesBefore=$tables16 tablesAfter=$tables18"
    $partyCol = DbQuery "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$AppDb' AND table_name='trip' AND column_name='party_size';"
    $taskCols = DbQuery "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$AppDb' AND table_name='agent_task' AND column_name IN ('depends_on','attempt','replaces_task_id');"
    Assert 'U6:后续迁移的新列已建成' ($partyCol -eq '1' -and $taskCols -eq '3') "trip.party_size=$partyCol agent_task(depends_on,attempt,replaces_task_id)=$taskCols"
    $historyUnchanged = ((FlywayOldDigest) -eq $preOldHistory)
    # 旧版本行不应重跑：<=V16 行的 installed_on / checksum 不变
    $historyOldRows = DbQuery "SELECT COUNT(*) FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=16;"
    Assert 'U7:旧迁移未重跑（V1..V16 历史摘要与升级前一致）' $historyUnchanged "pre=$preOldHistory post=$(FlywayOldDigest) oldRows=$historyOldRows"

    # 4.1 数据一致性
    $postTables = @(Get-TableSnapshot $preColumns)
    $postTableMap = @{}
    foreach ($t in $postTables) { $postTableMap[$t.table] = $t }
    $postKey = KeyFieldSnapshot
    foreach ($tbl in $preKeyTables) {
        $a = $preTableMap[$tbl]; $b = $postTableMap[$tbl]
        $ok = ($a -and $b -and $a.rows -eq $b.rows -and $a.hash -eq $b.hash)
        Assert "U8-${tbl}:行数与旧列内容哈希一致" $ok "table=$tbl rows=$($a.rows)->$($b.rows) hash=$($a.hash)->$($b.hash)"
    }
    $allMismatch = @($preTables | Where-Object {
        $p = $postTableMap[$_.table]
        (-not $p) -or ($p.rows -ne $_.rows) -or ($p.hash -ne $_.hash)
    })
    Assert 'U9:全部表逐表行数/内容一致' ($allMismatch.Count -eq 0) "tables=$($preTables.Count) mismatches=$($allMismatch.table -join ',')"
    $preKeyJson = JsonOut $preKey
    $postKeyJson = JsonOut $postKey
    Assert 'U10:关键字段快照未变（HTTP 数据/旧列原值）' ($preKeyJson -eq $postKeyJson) "trips=$($postKey.trips.Count) items=$($postKey.itinerary_items.Count) runs=$($postKey.agent_runs.Count) tasks=$($postKey.agent_tasks.Count)"
    $badParty = DbQuery "SELECT COUNT(*) FROM trip WHERE party_size IS NULL OR party_size<>1;"
    Assert 'U11:trip.party_size 对旧行回填=1' ($badParty -eq '0') "badRows=$badParty"
    $badAttempt = DbQuery "SELECT COUNT(*) FROM agent_task WHERE attempt IS NULL OR attempt<>0;"
    $badDag = DbQuery "SELECT COUNT(*) FROM agent_task WHERE depends_on IS NOT NULL OR replaces_task_id IS NOT NULL;"
    Assert 'U12:agent_task DAG 列默认值正确' ($badAttempt -eq '0' -and $badDag -eq '0') "attemptBad=$badAttempt dagNonNull=$badDag"
    [System.IO.File]::WriteAllText((Join-Path $LogDir 'snapshot-post-upgrade.json'), (JsonOut ([ordered]@{
        capturedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'); counts = (CountsDigest); history = (FlywayHistoryDigest);
        tables = $postTables; keyFields = $postKey })), (New-Object System.Text.UTF8Encoding($false)))

    # 4.2 升级后 HTTP 读旧数据
    $token18 = Login
    $tripsAfter = Http "$(BaseUrl)/api/trips" 'GET' $null $token18
    $tripIdsAfter = @($tripsAfter.json.data | ForEach-Object { [string]$_.id } | Sort-Object)
    $tripIdsPre = @($preKey.trips | ForEach-Object { [string]$_.id } | Sort-Object)
    Assert 'U13:升级后 HTTP 行程列表与旧库一致' (($tripIdsAfter -join ',') -eq ($tripIdsPre -join ',')) "pre=$($tripIdsPre -join ',') post=$($tripIdsAfter -join ',')"
    $itAfter = Http "$(BaseUrl)/api/trips/$tripAId/itinerary" 'GET' $null $token18
    $itAfterCount = @($itAfter.json.data).Count
    $itPreCount = @($preKey.itinerary_items | Where-Object { [string]$_.trip_id -eq [string]$tripAId }).Count
    Assert 'U14:升级后旧行程条目可读且数量一致' ($itAfter.code -eq 200 -and $itAfterCount -eq $itPreCount) "pre=$itPreCount post=$itAfterCount"
    $oldRunAfter = Http "$(BaseUrl)/api/agent/runs/$oldRunId" 'GET' $null $token18
    $oldRunTasksAfter = 0
    if ($oldRunAfter.code -eq 200) { $oldRunTasksAfter = @($oldRunAfter.json.data.tasks).Count }
    Assert 'U15:升级后旧 runId 仍可查' ($oldRunAfter.code -eq 200 -and $oldRunTasksAfter -eq $oldRunTaskDbCount) "HTTP=$($oldRunAfter.code) tasks=$oldRunTasksAfter（DB 旧 run 任务数=$oldRunTaskDbCount，含 D11 SQL 种子 1 条）"
    $budget = Http "$(BaseUrl)/api/trips/$tripAId/budget" 'GET' $null $token18
    Assert 'U16:升级后预算接口可读旧数据' ($budget.code -eq 200) "budget=$($budget.json.data.budget) planned=$($budget.json.data.planned) actual=$($budget.json.data.actual)"
    $histDigest18 = FlywayHistoryDigest
    $countsDigest18 = CountsDigest

    # ── 5. 三态重启 ────────────────────────────────────────────
    Phase '5.1 仅重启后端（MySQL 不动）'
    $restartStart = Get-Date
    $rb = Start-BackendChecked 'restart-backend' $CurrentJar @('--spring.flyway.target=latest') 360
    Assert 'R1:仅重启后端后健康' ([bool]$rb) "耗时 $($rb.seconds)s"
    $tk = Login
    $runOk = Http "$(BaseUrl)/api/agent/runs/$oldRunId" 'GET' $null $tk
    Assert 'R2:仅重启后端后旧 runId 可查' ($runOk.code -eq 200) "HTTP=$($runOk.code)"
    $histR1 = FlywayHistoryDigest
    Assert 'R3:仅重启后端后迁移未重跑' ($histR1 -eq $histDigest18) "history=$histR1"
    $c1 = CountsDigest
    Assert 'R4:仅重启后端后旧数据一致' ($c1 -eq $countsDigest18) "counts=$c1"
    $memoR1 = Http "$(BaseUrl)/api/memos" 'POST' @{ content = '重启后端后新写入' } $tk
    Assert 'R5:仅重启后端后可写新数据' ($memoR1.code -eq 200) "HTTP=$($memoR1.code) memoId=$($memoR1.json.data.id)"
    $countsDigestAfterR1 = CountsDigest

    Phase '5.2 仅重启 MySQL（后端进程不动，验证连接池恢复）'
    Stop-Mysql
    Assert 'R6:MySQL 已停止' (-not (PortOpen $Script:DbPort)) "port=$Script:DbPort closed"
    if (-not (Start-Mysql)) { throw 'MySQL 重启失败' }
    $recovered = $false
    $recoverSecs = $null
    $rw = Get-Date
    for ($i = 0; $i -lt 45; $i++) {
        $probe = Http "$(BaseUrl)/api/trips" 'GET' $null $tk 5
        if ($probe.code -eq 200) { $recovered = $true; $recoverSecs = [math]::Round(((Get-Date) - $rw).TotalSeconds, 1); break }
        Start-Sleep -Seconds 2
    }
    Assert 'R7:MySQL 重启后后端连接池自动恢复' $recovered "耗时 ${recoverSecs}s"
    $tk2 = Login
    $runOk2 = Http "$(BaseUrl)/api/agent/runs/$oldRunId" 'GET' $null $tk2
    Assert 'R8:MySQL 重启后旧 runId 可查' ($runOk2.code -eq 200) "HTTP=$($runOk2.code)"
    $histR2 = FlywayHistoryDigest
    Assert 'R9:MySQL 重启后迁移未重跑' ($histR2 -eq $histDigest18) "history=$histR2"
    $c2 = CountsDigest
    Assert 'R10:MySQL 重启后数据一致' ($c2 -eq $countsDigestAfterR1) "counts=$c2"
    $memoR2 = Http "$(BaseUrl)/api/memos" 'POST' @{ content = 'MySQL 重启后新写入' } $tk2
    Assert 'R11:MySQL 重启后可写新数据' ($memoR2.code -eq 200) "HTTP=$($memoR2.code) memoId=$($memoR2.json.data.id)"
    $countsDigestAfterR2 = CountsDigest

    Phase '5.3 整栈重启（先停后端，再停 MySQL；反向启动）'
    Stop-Backend
    Stop-Mysql
    Assert 'R12:整栈已完全停止' ((-not (PortOpen $Script:BackendPort)) -and (-not (PortOpen $Script:DbPort))) "backend/mysql ports closed"
    if (-not (Start-Mysql)) { throw '整栈重启：MySQL 启动失败' }
    $fb = Start-BackendChecked 'restart-full' $CurrentJar @('--spring.flyway.target=latest') 360
    Assert 'R13:整栈重启后后端健康' ([bool]$fb) "耗时 $($fb.seconds)s"
    $tk3 = Login
    $runOk3 = Http "$(BaseUrl)/api/agent/runs/$oldRunId" 'GET' $null $tk3
    $trips3 = Http "$(BaseUrl)/api/trips" 'GET' $null $tk3
    $tripIds3 = @($trips3.json.data | ForEach-Object { [string]$_.id } | Sort-Object)
    Assert 'R14:整栈重启后旧 runId/旧行程可查' ($runOk3.code -eq 200 -and ($tripIds3 -join ',') -eq ($tripIdsPre -join ',')) "runHTTP=$($runOk3.code) trips=$($tripIds3 -join ',')"
    $histR3 = FlywayHistoryDigest
    Assert 'R15:整栈重启后迁移未重跑' ($histR3 -eq $histDigest18) "history=$histR3"
    $c3 = CountsDigest
    Assert 'R16:整栈重启后数据一致' ($c3 -eq $countsDigestAfterR2) "counts=$c3"
    $memoR3 = Http "$(BaseUrl)/api/memos" 'POST' @{ content = '整栈重启后新写入' } $tk3
    Assert 'R17:整栈重启后可写新数据' ($memoR3.code -eq 200) "HTTP=$($memoR3.code) memoId=$($memoR3.json.data.id)"

} catch {
    $Script:Fatal = $_.Exception.Message
    Say "`n!! 未捕获异常/中止：$($Script:Fatal)"
    if ($_.ScriptStackTrace) { Say "   堆栈：$($_.ScriptStackTrace)" }
    [System.IO.File]::WriteAllText((Join-Path $LogDir 'fatal.txt'), ($_.Exception.ToString() + "`n" + $_.ScriptStackTrace), (New-Object System.Text.UTF8Encoding($false)))
} finally {
    # ── 6. 清理 ────────────────────────────────────────────────
    try {
        Phase '6. 清理与收尾'
        if (-not $KeepRunning) {
            Stop-Backend
            Stop-Mysql
            $backendClosed = Wait-PortClosed $Script:BackendPort 45
            $mysqlClosed = Wait-PortClosed $Script:DbPort 45
            Assert 'C1:后端端口已释放' $backendClosed "backendPort=$Script:BackendPort"
            Assert 'C2:MySQL 端口已释放' $mysqlClosed "dbPort=$Script:DbPort"
            if (Test-Path $Datadir) { Remove-Item $Datadir -Recurse -Force -ErrorAction SilentlyContinue }
            Assert 'C3:临时 datadir 已删除' (-not (Test-Path $Datadir)) "path=$Datadir"
            $leftPids = @()
            if ($Script:MysqlProc) { if (Get-Process -Id $Script:MysqlProc.Id -ErrorAction SilentlyContinue) { $leftPids += $Script:MysqlProc.Id } }
            Assert 'C4:无本脚本残留进程' ($leftPids.Count -eq 0 -and (Get-PortPids $Script:DbPort).Count -eq 0 -and (Get-PortPids $Script:BackendPort).Count -eq 0) "pids=$($leftPids -join ',') dbPortOwners=$((Get-PortPids $Script:DbPort) -join ',')"
            $p3306After = Get-PortPids 3306
            $pid3306After = ''
            if ($p3306After.Count -gt 0) { $pid3306After = [string]$p3306After[0] }
            Assert 'C5:3306 既有 MySQL 未被影响' ($pid3306After -eq $pid3306) "before=$pid3306 after=$pid3306After"
            $now3307 = PortOpen 3307
            $now3317 = PortOpen 3317
            Assert 'C6:3307/3317 未被本脚本使用/改动' (($now3307 -eq $was3307) -and ($now3317 -eq $was3317)) "3307=$now3307(was $was3307) 3317=$now3317(was $was3317)"
            if (-not $KeepTemp) {
                if (Test-Path $TmpRoot) { Remove-Item $TmpRoot -Recurse -Force -ErrorAction SilentlyContinue }
            }
            Say "- 清理完成（-KeepRunning 可保留现场；-KeepTemp 可保留 .tmp 构建缓存）"
        } else {
            Say "- -KeepRunning：保留后端与 MySQL（端口 $Script:BackendPort / $Script:DbPort），跳过清理断言"
        }
    } catch {
        Say "!! 清理阶段异常：$($_.Exception.Message)"
    }

    # ── 7. 结论与证据落盘 ───────────────────────────────────────
    try {
        if ($Script:PhaseStart) {
            $Script:PhaseTimes.Add([pscustomobject]@{ phase = $Script:PhaseName; seconds = [math]::Round(((Get-Date) - $Script:PhaseStart).TotalSeconds, 1) })
        }
        $pass = @($Script:Results | Where-Object { $_.ok }).Count
        $fail = @($Script:Results | Where-Object { -not $_.ok }).Count
        $totalSecs = [math]::Round(((Get-Date) - $scriptStart).TotalSeconds, 1)
        Say "`n## 结论"
        Say "- 断言：$pass PASS / $fail FAIL（共 $($Script:Results.Count)）"
        Say "- 总耗时：${totalSecs}s"
        foreach ($pt in $Script:PhaseTimes) { Say ("  - {0}: {1}s" -f $pt.phase, $pt.seconds) }
        if ($fail -eq 0 -and -not $Script:Fatal) { $verdict = 'PASS' } else { $verdict = 'FAIL' }
        Say "- 判定：$verdict"
        if ($fail -gt 0) {
            Say "- 失败断言："
            @($Script:Results | Where-Object { -not $_.ok }) | ForEach-Object { Say "  - [$($_.id)] $($_.detail)" }
        }
        Say "`n## 环境与已知约束"
        Say "- Windows（无 Docker）：未运行、也未假装运行 docker compose；MySQL 为原生便携实例 8.4.10。"
        Say "- 3306 既有 MySQL 8.0 监听 PID：$pid3306（全程未占用/未改动）；3307/3317 未复用。"
        Say "- Flyway 对 MySQL 8.4 仅告警（已捕获 WARN 行）：支持到 8.1，但迁移与 ddl-auto=validate 均通过。"
        Say "- 旧部署基线：V15/V16 由同一提交（b8c9b89）引入，不存在「以 V15 为最新」的可构建发布；用旧版提交 $OldCommit（其最高迁移=V16）模拟旧部署。"
        Say "  target=15 的真实结果见 O1/O2：库确实停在 V15，但任何发布代码都无法通过 ddl-auto=validate（缺 pending_plan.content_hash 等 V16 列）。"
        Say "- 升级增量：V17（trip.party_size）、V18（agent_task.depends_on/attempt/replaces_task_id），恰为「给已有表补 NOT NULL DEFAULT 列」的风险路径。"

        $meta = [ordered]@{
            generatedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
            host = $env:COMPUTERNAME
            os = [System.Environment]::OSVersion.VersionString
            docker = 'not available (native mysqld, no docker compose)'
            mysqlBase = $MysqlBase
            mysqlVersion = '8.4.10'
            dbPort = $Script:DbPort
            backendPort = $Script:BackendPort
            oldCommit = $OldCommit
            oldSchemaVersion = 16
            currentSchemaVersion = 18
            flywayWarning = 'Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.'
            totalSeconds = $totalSecs
            phases = $Script:PhaseTimes
            pass = $pass
            fail = $fail
            verdict = $verdict
            fatal = $Script:Fatal
        }
        $payload = [ordered]@{ meta = $meta; assertions = $Script:Results.ToArray() }
        [System.IO.File]::WriteAllText($ResultJson, (JsonOut $payload), (New-Object System.Text.UTF8Encoding($false)))
        $summaryText = ($Script:Summary -join "`n")
        [System.IO.File]::WriteAllText($SummaryMd, $summaryText, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "`n证据：$ResultJson"
        Write-Host "摘要：$SummaryMd"
        Write-Host "日志：$LogDir"
        if ($fail -gt 0 -or $Script:Fatal) { exit 1 } else { exit 0 }
    } catch {
        Write-Host "!! 收尾落盘失败：$($_.Exception.Message)"
        exit 1
    }
}