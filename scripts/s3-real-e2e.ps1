#requires -Version 5.1
<#
.SYNOPSIS
    真实 S3 兼容对象存储验收：起一个隔离实例（PHOTOS_STORE=s3），跑「上传 → 读回 → 删除」并落盘证据。

.DESCRIPTION
    背景：2026-11-24 只用本地 S3 协议桩（.evidence/s3-stub.mjs）验过 S3PhotoStore 的 HTTP 链路，
    真实供应商（MinIO / AWS S3 / 阿里 OSS / 腾讯 COS）的签名、鉴权、HTTPS、版本控制/生命周期仍未验。
    本脚本把同一套业务流程参数化，凭证与端点只从参数/环境变量进，不写盘、不进仓库。

    验证项（每项打印 PASS/FAIL 并写入 -OutDir）：
      0) 启动隔离实例（独立 H2 + 独立端口），等待 /actuator/health = UP
      1) 注册/登录探针账号
      2) 创建行程
      3) 上传 68 B PNG（POST /api/trips/{id}/photos）
      4) 读回原图（GET /api/photos/{id}/raw），sha256 必须与上传一致
      5) 删除照片（DELETE /api/photos/{id}），raw 必须 404
      6) 删除行程（DELETE /api/trips/{id}）

    端点守卫：localhost / 127.0.0.1 默认**拒绝**（桩不算真实端点验收），要跑桩自测必须显式加
    -AllowLocalStub；此时 summary 标 STUB-ONLY，退出码 0 也不代表真端点通过。

    进程安全：只停本脚本启动的 PID 及其子树（taskkill /PID <pid> /T /F），不枚举其它 java 进程。

.PARAMETER Endpoint
    S3 端点，例：https://minio.example.com:9000、https://oss-cn-hangzhou.aliyuncs.com。
    AWS S3 可留空（走 SDK 默认端点，用 -Region 指定区域）。

.PARAMETER Bucket
    桶名。**必须已存在**（脚本不做 UI 外建桶；S3 的建桶需要签名，curl 做不了）。

.PARAMETER AccessKey / SecretKey
    AK/SK。也接受环境变量 S3_ACCESS_KEY / S3_SECRET_KEY（参数优先）。

.PARAMETER Region
    区域，默认 us-east-1（MinIO 通常任意值均可）。

.PARAMETER PathStyle
    MinIO / 多数兼容网关需要 path-style；AWS S3 不要加。

.PARAMETER ChecksumValidation
    默认 true（AWS/MinIO 的完整性校验）。返回头不带校验的网关需显式 -ChecksumValidation false。

.PARAMETER AllowLocalStub
    允许 http://127.0.0.1:* 作为端点（只用于验证本脚本自身与桩兼容），结果标 STUB-ONLY。

.PARAMETER OutDir
    证据目录，默认 .evidence\s3-real-<yyyyMMdd-HHmmss>。

.PARAMETER DryRun
    只校验参数与工具链，不启动服务、不上传。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\s3-real-e2e.ps1 `
      -Endpoint https://minio.example.com:9000 -Bucket travel-photos -PathStyle `
      -AccessKey "$env:S3_ACCESS_KEY" -SecretKey "$env:S3_SECRET_KEY"

.EXAMPLE
    # 桩自测（不构成真实端点验收）
    # 本地桩不返回 CRC32/MD5 校验头，SDK 默认校验会抛 ArrayIndexOutOfBoundsException，
    # 因此桩自测必须用 -ChecksumValidation false（真实 AWS/MinIO 保持默认 true）。
    powershell -ExecutionPolicy Bypass -File scripts\s3-real-e2e.ps1 `
      -Endpoint http://127.0.0.1:9090 -Bucket travel-photos -PathStyle -AllowLocalStub `
      -AccessKey test -SecretKey test -ChecksumValidation false

.NOTES
    退出码：0 全通过（桩自测也返回 0 但标 STUB-ONLY）；2 参数/工具链问题；3 验收 FAIL；
    4 实例启动失败；1 未预期异常。Key 只注入子进程环境变量，不写日志、不写证据。
#>
[CmdletBinding()]
param(
    [string]$Endpoint = '',
    [Parameter(Mandatory = $true)][string]$Bucket,
    [string]$AccessKey = $env:S3_ACCESS_KEY,
    [string]$SecretKey = $env:S3_SECRET_KEY,
    [string]$Region = 'us-east-1',
    [switch]$PathStyle,
    [ValidateSet('true', 'false', '0', '1')][string]$ChecksumValidation = 'true',
    [switch]$AllowLocalStub,
    [int]$Port = 18082,
    [string]$OutDir,
    [string]$JarPath,
    [int]$StartupTimeoutSec = 150,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:ServerDir = Join-Path $script:RepoRoot 'travel-agent-server'
$script:BaseUrl = 'http://127.0.0.1:' + $Port
$script:Checks = New-Object System.Collections.ArrayList
$script:StartedProc = $null
$script:EnvBackup = @{}
$script:IsStub = $false
$script:TripId = $null
$script:PhotoId = $null
$script:CookieJar = $null
$script:RunDir = $null
$script:ExitCode = 0
$script:ProbePng = $null

function Write-Step([string]$title) {
    Write-Host ''
    Write-Host ('== ' + $title) -ForegroundColor Cyan
}
function Add-Check([string]$id, [string]$name, [string]$status, [string]$detail) {
    [void]$script:Checks.Add([pscustomobject]@{ id = $id; name = $name; status = $status; detail = $detail })
    $color = 'Gray'
    if ($status -eq 'PASS') { $color = 'Green' }
    if ($status -eq 'FAIL') { $color = 'Red' }
    if ($status -eq 'SKIP') { $color = 'Yellow' }
    Write-Host ('  ' + $status + '  ' + $id + ' ' + $name + '：' + $detail) -ForegroundColor $color
}
function Write-Info([string]$msg) { Write-Host ('        ' + $msg) -ForegroundColor DarkGray }
function Write-Warn([string]$msg) { Write-Host ('  WARN  ' + $msg) -ForegroundColor Yellow }
function Write-Utf8NoBom([string]$path, [string]$text) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $enc)
}
function Get-HttpHost([string]$url) {
    if ([string]::IsNullOrWhiteSpace($url)) { return '' }
    try { return ([System.Uri]$url).Host } catch { return '' }
}
function Test-StubHost([string]$hostName) {
    if ([string]::IsNullOrWhiteSpace($hostName)) { return $false }
    return ($hostName -in @('localhost', '127.0.0.1', '::1', '[::1]'))
}

# 68 字节 1x1 PNG：足够触发上传与类型校验，体积小便于比对 sha256
function New-ProbePng([string]$path) {
    $b64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
    [System.IO.File]::WriteAllBytes($path, [Convert]::FromBase64String($b64))
    return $path
}

function Resolve-ServerJar {
    if ($JarPath) {
        if (-not (Test-Path -LiteralPath $JarPath)) { throw ('-JarPath 不存在：' + $JarPath) }
        return (Resolve-Path -LiteralPath $JarPath).Path
    }
    $target = Join-Path $script:ServerDir 'target'
    $jar = $null
    if (Test-Path -LiteralPath $target) {
        $jar = Get-ChildItem -LiteralPath $target -Filter 'travel-agent-server-*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*.original' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if ($jar) { return $jar.FullName }
    throw '找不到后端 jar：请先执行 mvn -B -DskipTests package，或用 -JarPath 指定。'
}

function Set-ProbeEnv([hashtable]$vars) {
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

function Start-Backend([string]$jar) {
    $runDir = Join-Path $script:OutDir 'server-run'
    foreach ($d in @((Join-Path $runDir 'data'), (Join-Path $runDir 'photos'))) {
        if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
    }
    $script:RunDir = $runDir
    $dataSlash = (Join-Path $runDir 'data').Replace('\', '/')
    $photoSlash = (Join-Path $runDir 'photos').Replace('\', '/')

    $jwtBytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($jwtBytes) } finally { $rng.Dispose() }
    $jwtSecret = [Convert]::ToBase64String($jwtBytes)

    $pathStyleText = 'false'
    if ($PathStyle) { $pathStyleText = 'true' }
    $checksumText = 'false'
    if ($script:ChecksumOn) { $checksumText = 'true' }

    $vars = @{
        'PHOTOS_STORE'                       = 's3'
        'TRAVEL_PHOTOS_S3_ENDPOINT'          = $Endpoint
        'TRAVEL_PHOTOS_S3_REGION'            = $Region
        'TRAVEL_PHOTOS_S3_BUCKET'            = $Bucket
        'TRAVEL_PHOTOS_S3_ACCESS_KEY'        = $AccessKey
        'TRAVEL_PHOTOS_S3_SECRET_KEY'        = $SecretKey
        'TRAVEL_PHOTOS_S3_PATH_STYLE'        = $pathStyleText
        'TRAVEL_PHOTOS_S3_CHECKSUM_VALIDATION' = $checksumText
        'JWT_SECRET'                         = $jwtSecret
        'RATE_LIMIT_ENABLED'                 = 'false'
        'LLM_ENABLED'                        = 'false'
        'WEATHER_SIMULATION'                 = 'true'
        'MCP_ENABLED'                        = 'false'
        'DEMO_ACCOUNT'                       = 'true'
        'SERVER_PORT'                        = [string]$Port
        'SPRING_DATASOURCE_URL'              = ('jdbc:h2:file:' + $dataSlash + '/travel-agent;MODE=MySQL;AUTO_SERVER=TRUE')
        'PHOTOS_DIR'                         = $photoSlash
    }
    Set-ProbeEnv $vars

    $stdout = Join-Path $script:OutDir 'server-stdout.log'
    $stderr = Join-Path $script:OutDir 'server-stderr.log'
    foreach ($f in @($stdout, $stderr)) { if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force } }

    Write-Info ('端点：' + $(if ($Endpoint) { $Endpoint } else { '(AWS 默认)' }) + '  bucket=' + $Bucket + '  region=' + $Region +
        '  pathStyle=' + $pathStyleText + '  checksum=' + $checksumText)
    $script:StartedProc = Start-Process -FilePath 'java' `
        -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $jar + '"')) `
        -WorkingDirectory $script:ServerDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -PassThru -WindowStyle Hidden
    if (-not $script:StartedProc) { throw 'java 进程启动失败' }
    Write-Info ('后端 PID = ' + $script:StartedProc.Id + '（结束时只停该 PID 及其子树）')
}

function Stop-Backend {
    if (-not $script:StartedProc) { return }
    $pid0 = $script:StartedProc.Id
    try { & taskkill.exe /PID $pid0 /T /F *> $null } catch { }
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $script:StartedProc.Refresh()
            if ($script:StartedProc.HasExited) { break }
        } catch { break }
        Start-Sleep -Milliseconds 250
    }
    $alive = $false
    try { $script:StartedProc.Refresh(); $alive = -not $script:StartedProc.HasExited } catch { $alive = $false }
    Write-Utf8NoBom (Join-Path $script:OutDir 'cleanup.txt') ("pid=$pid0`r`nprocess_alive_after_stop=$alive`r`nnote=只停止本脚本记录 PID 及其子树`r`n")
    if ($alive) { Write-Warn ('PID ' + $pid0 + ' 仍存活，见 cleanup.txt') } else { Write-Info ('已停止 PID ' + $pid0) }
}

function Wait-Health([int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $healthFile = Join-Path $script:OutDir 'health.json'
    $last = 'no-response'
    while ((Get-Date) -lt $deadline) {
        if ($script:StartedProc) {
            try {
                $script:StartedProc.Refresh()
                if ($script:StartedProc.HasExited) { return 'exited' }
            } catch { }
        }
        # 用 curl.exe 而不是 Invoke-WebRequest：后者会读系统代理设置，本机 127.0.0.1 也可能被代理劫持
        $code = '000'
        try { $code = [string](& curl.exe -s -o $healthFile -w '%{http_code}' --max-time 3 ($script:BaseUrl + '/actuator/health')) } catch { $code = '000' }
        $body = ''
        if (Test-Path -LiteralPath $healthFile) {
            try { $body = [System.IO.File]::ReadAllText($healthFile, [System.Text.Encoding]::UTF8) } catch { $body = '' }
        }
        $last = 'HTTP ' + $code
        if ($code -eq '200' -and $body -match '"status"\s*:\s*"UP"') { return 'up' }
        Start-Sleep -Seconds 2
    }
    return ('timeout（最后状态：' + $last + '）')
}

# 统一用 curl.exe：multipart 与二进制读回都要，Invoke-WebRequest 在 5.1 下不好处理
function Invoke-Curl {
    param(
        [string]$Method = 'GET',
        [string]$Url,
        [string[]]$ExtraArgs = @(),
        [int]$MaxTime = 60
    )
    $out = Join-Path $script:OutDir ('resp-' + [guid]::NewGuid().ToString('N') + '.out')
    $cargs = @('-s', '-S', '--max-time', [string]$MaxTime, '-o', $out, '-w', '%{http_code}')
    if ($Method -ne 'GET') { $cargs += @('-X', $Method) }
    if ($script:CookieJar) {
        if (Test-Path -LiteralPath $script:CookieJar) { $cargs += @('-b', $script:CookieJar) }
        $cargs += @('-c', $script:CookieJar)
    }
    $cargs += $ExtraArgs
    $cargs += $Url
    $codeText = ''
    try { $codeText = (& curl.exe @cargs) } catch { $codeText = '' }
    $http = -1
    if (-not [int]::TryParse(([string]$codeText).Trim(), [ref]$http)) { $http = -1 }
    $bytes = $null
    if (Test-Path -LiteralPath $out) { $bytes = [System.IO.File]::ReadAllBytes($out) }
    $text = ''
    if ($bytes) {
        try { $text = [System.Text.Encoding]::UTF8.GetString($bytes) } catch { $text = '' }
    }
    return [pscustomobject]@{ http = $http; text = $text; bytes = $bytes; out = $out }
}
function Get-JsonField($obj, [string]$path) {
    $cur = $obj
    foreach ($seg in $path.Split('.')) {
        if ($null -eq $cur) { return $null }
        $prop = $cur.PSObject.Properties[$seg]
        if (-not $prop) { return $null }
        $cur = $prop.Value
    }
    return $cur
}
function Write-JsonBody([string]$name, [string]$json) {
    $p = Join-Path $script:OutDir $name
    Write-Utf8NoBom $p $json
    return $p
}

function Invoke-Flow {
    # 1) 注册 + 登录（唯一账号，避免和历史库冲突）
    Write-Step '1) 注册/登录探针账号'
    $user = 's3probe' + (Get-Date -Format 'HHmmss')
    $pwd = 'S3Probe123!'
    $regBody = Write-JsonBody 'request-register.json' (('{"username":"' + $user + '","password":"' + $pwd + '","nickname":"S3 Real E2E"}'))
    $reg = Invoke-Curl -Method 'POST' -Url ($script:BaseUrl + '/api/auth/register') -ExtraArgs @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $regBody))
    Write-Info ('register HTTP ' + $reg.http)
    if ($reg.http -notin @(200, 201, 400)) {
        Add-Check '1' '注册' 'FAIL' ('HTTP ' + $reg.http + ' ' + $reg.text.Substring(0, [Math]::Min(160, $reg.text.Length)))
        return $false
    }
    $loginBody = Write-JsonBody 'request-login.json' (('{"username":"' + $user + '","password":"' + $pwd + '"}'))
    $login = Invoke-Curl -Method 'POST' -Url ($script:BaseUrl + '/api/auth/login') -ExtraArgs @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $loginBody))
    if ($login.http -ne 200) {
        Add-Check '1' '登录' 'FAIL' ('HTTP ' + $login.http + ' ' + $login.text.Substring(0, [Math]::Min(160, $login.text.Length)))
        return $false
    }
    # 证据里不留 token 明文
    $sanitized = [regex]::Replace($login.text, '"token"\s*:\s*"[^"]*"', '"token":"<redacted>"')
    Write-Utf8NoBom (Join-Path $script:OutDir '01-login.json') $sanitized
    Add-Check '1' '登录' 'PASS' ('username=' + $user + '，Cookie 只写临时 jar')
    return $true
}

function Invoke-TripAndPhoto {
    # 2) 行程
    Write-Step '2) 创建行程'
    $tripBody = Write-JsonBody 'request-trip.json' '{"name":"S3 real E2E","destination":"杭州","startDate":"2026-12-01","endDate":"2026-12-03","budget":1000,"partySize":1}'
    $trip = Invoke-Curl -Method 'POST' -Url ($script:BaseUrl + '/api/trips') -ExtraArgs @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $tripBody))
    if ($trip.http -ne 200) {
        Add-Check '2' '创建行程' 'FAIL' ('HTTP ' + $trip.http + ' ' + $trip.text.Substring(0, [Math]::Min(160, $trip.text.Length)))
        return $false
    }
    Write-Utf8NoBom (Join-Path $script:OutDir '02-trip.json') $trip.text
    $tripJson = $null
    try { $tripJson = $trip.text | ConvertFrom-Json } catch { }
    $tripId = Get-JsonField $tripJson 'data.id'
    if (-not $tripId) {
        Add-Check '2' '创建行程' 'FAIL' 'HTTP 200 但响应里没有 data.id'
        return $false
    }
    $script:TripId = $tripId
    Add-Check '2' '创建行程' 'PASS' ('tripId=' + $tripId)

    # 3) 上传
    Write-Step '3) 上传照片（POST /api/trips/{id}/photos）'
    $upload = Invoke-Curl -Method 'POST' -Url ($script:BaseUrl + '/api/trips/' + $tripId + '/photos') `
        -ExtraArgs @('-F', ('file=@' + $script:ProbePng + ';type=image/png')) -MaxTime 120
    if ($upload.http -ne 200) {
        Add-Check '3' '上传照片' 'FAIL' ('HTTP ' + $upload.http + ' ' + $upload.text.Substring(0, [Math]::Min(200, $upload.text.Length)) + '（500 排查：桶不存在/签名/Endpoint 不可达；若日志是 AWS SDK 的 ArrayIndexOutOfBoundsException/校验失败，说明网关不返回 CRC32 头，加 -ChecksumValidation false）')
        return $false
    }
    Write-Utf8NoBom (Join-Path $script:OutDir '03-upload.json') $upload.text
    $uploadJson = $null
    try { $uploadJson = $upload.text | ConvertFrom-Json } catch { }
    $photoId = Get-JsonField $uploadJson 'data.id'
    $sizeBytes = Get-JsonField $uploadJson 'data.sizeBytes'
    if (-not $photoId) {
        Add-Check '3' '上传照片' 'FAIL' 'HTTP 200 但响应里没有 data.id'
        return $false
    }
    $script:PhotoId = $photoId
    Add-Check '3' '上传照片' 'PASS' ('photoId=' + $photoId + '  sizeBytes=' + $sizeBytes)

    # 4) 读回 + sha256
    Write-Step '4) 读回原图并比对 sha256'
    $raw = Invoke-Curl -Url ($script:BaseUrl + '/api/photos/' + $photoId + '/raw') -MaxTime 120
    if ($raw.http -ne 200) {
        Add-Check '4' '读回照片' 'FAIL' ('HTTP ' + $raw.http)
        return $false
    }
    $rawPath = Join-Path $script:OutDir '04-raw.png'
    [System.IO.File]::WriteAllBytes($rawPath, $raw.bytes)
    $shaA = (Get-FileHash -LiteralPath $script:ProbePng -Algorithm SHA256).Hash.ToLowerInvariant()
    $shaB = (Get-FileHash -LiteralPath $rawPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Info ('sha256 上传=' + $shaA)
    Write-Info ('sha256 读回=' + $shaB)
    if ($shaA -ne $shaB) {
        Add-Check '4' '读回照片' 'FAIL' ('sha256 不一致（上传 ' + $shaA + ' / 读回 ' + $shaB + '）')
        return $false
    }
    Add-Check '4' '读回照片' 'PASS' ('sha256 一致：' + $shaA)

    # 5) 删除照片 + 失效校验
    Write-Step '5) 删除照片并确认读不到'
    $del = Invoke-Curl -Method 'DELETE' -Url ($script:BaseUrl + '/api/photos/' + $photoId)
    if ($del.http -ne 200) {
        Add-Check '5' '删除照片' 'FAIL' ('HTTP ' + $del.http + ' ' + $del.text.Substring(0, [Math]::Min(160, $del.text.Length)))
        return $false
    }
    $after = Invoke-Curl -Url ($script:BaseUrl + '/api/photos/' + $photoId + '/raw')
    if ($after.http -ne 404) {
        Add-Check '5' '删除照片' 'FAIL' ('删除后 raw 仍返回 HTTP ' + $after.http + '（期望 404；对象可能未删或票据仍有效）')
        return $false
    }
    Add-Check '5' '删除照片' 'PASS' ('DELETE 200，raw 再读 404')
    return $true
}

function Invoke-Teardown {
    Write-Step '6) 删除行程'
    if (-not $script:TripId) {
        Add-Check '6' '删除行程' 'SKIP' '没有 tripId'
        return
    }
    $r = Invoke-Curl -Method 'DELETE' -Url ($script:BaseUrl + '/api/trips/' + $script:TripId)
    if ($r.http -eq 200) {
        Add-Check '6' '删除行程' 'PASS' ('tripId=' + $script:TripId + ' 已删除')
    } else {
        Add-Check '6' '删除行程' 'FAIL' ('HTTP ' + $r.http)
    }
}

function Write-Summary([string]$verdict) {
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add('# 真实 S3 验收证据（' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '）')
    [void]$lines.Add('')
    [void]$lines.Add('| 项 | 值 |')
    [void]$lines.Add('| --- | --- |')
    [void]$lines.Add('| 结论 | **' + $verdict + '** |')
    [void]$lines.Add('| 端点 | ' + $(if ($Endpoint) { $Endpoint } else { '(AWS 默认)' }) + ' |')
    [void]$lines.Add('| bucket / region | ' + $Bucket + ' / ' + $Region + ' |')
    [void]$lines.Add('| pathStyle / checksum | ' + $(if ($PathStyle) { 'true' } else { 'false' }) + ' / ' + $(if ($script:ChecksumOn) { 'true' } else { 'false' }) + ' |')
    [void]$lines.Add('| 本地桩标记 | ' + $(if ($script:IsStub) { 'STUB-ONLY（不算真实端点验收）' } else { '否' }) + ' |')
    [void]$lines.Add('')
    [void]$lines.Add('| # | 检查 | 结果 | 说明 |')
    [void]$lines.Add('| --- | --- | --- | --- |')
    foreach ($c in $script:Checks) {
        [void]$lines.Add('| ' + $c.id + ' | ' + $c.name + ' | ' + $c.status + ' | ' + ($c.detail -replace '\|', '/') + ' |')
    }
    [void]$lines.Add('')
    [void]$lines.Add('> Key 只经环境变量注入子进程，未写入本目录。')
    Write-Utf8NoBom (Join-Path $script:OutDir 'summary.md') (($lines -join "`r`n") + "`r`n")
}

# ============================== main ==============================

$hostName = Get-HttpHost $Endpoint
$script:ChecksumOn = ($ChecksumValidation -notin @('false', '0'))
if (Test-StubHost $hostName) {
    $script:IsStub = $true
    if (-not $AllowLocalStub) {
        Write-Host ('拒绝：端点 ' + $Endpoint + ' 是 localhost/回环地址，协议桩的结果不能当作真实 S3 验收。') -ForegroundColor Red
        Write-Host '要验证脚本自身（桩自测），请显式加 -AllowLocalStub；真实验收请换真实端点与凭证。' -ForegroundColor Yellow
        exit 2
    }
    Write-Warn '端点指向本机回环地址：本次结果标 STUB-ONLY，不构成真实端点验收。'
}
if ([string]::IsNullOrWhiteSpace($Bucket)) { Write-Host '-Bucket 必填' -ForegroundColor Red; exit 2 }
if ([string]::IsNullOrWhiteSpace($AccessKey) -or [string]::IsNullOrWhiteSpace($SecretKey)) {
    Write-Host '缺少凭证：用 -AccessKey/-SecretKey 或环境变量 S3_ACCESS_KEY/S3_SECRET_KEY。' -ForegroundColor Red
    exit 2
}
if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { Write-Host '找不到 curl.exe（Windows 10+ 自带）。' -ForegroundColor Red; exit 2 }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Write-Host '找不到 java。' -ForegroundColor Red; exit 2 }

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $script:RepoRoot ('.evidence\s3-real-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path (Get-Location).Path $OutDir }
if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$script:OutDir = (Resolve-Path -LiteralPath $OutDir).Path
$script:CookieJar = Join-Path $script:OutDir 'cookies.txt'
$script:ProbePng = New-ProbePng (Join-Path $script:OutDir 'probe.png')

try {
    $jar = Resolve-ServerJar
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 2
}

if ($DryRun) {
    Add-Check '0' '参数与工具链' 'PASS' ('jar=' + (Split-Path $jar -Leaf) + '  curl/java 可用  out=' + $script:OutDir)
    Write-Summary 'DRY-RUN（未启动服务）'
    Write-Host ''
    Write-Host 'DRY-RUN 完成，未发起任何上传。' -ForegroundColor Cyan
    exit 0
}

try {
    Write-Step '0) 启动隔离实例（PHOTOS_STORE=s3）'
    Start-Backend $jar
    $health = Wait-Health $StartupTimeoutSec
    if ($health -ne 'up') {
        Add-Check '0' '实例启动' 'FAIL' ($health + '（看 server-stdout.log / server-stderr.log）')
        Write-Summary 'FAIL（实例未就绪）'
        $script:ExitCode = 4
    } else {
        Add-Check '0' '实例启动' 'PASS' ('/actuator/health=UP，端口 ' + $Port)

        $ok = Invoke-Flow
        if ($ok) { $ok = Invoke-TripAndPhoto }
        if ($ok) { Invoke-Teardown }

        $failed = @($script:Checks | Where-Object { $_.status -eq 'FAIL' })
        if ($failed.Count -eq 0) {
            $verdict = 'PASS'
            if ($script:IsStub) { $verdict = 'STUB-ONLY（桩自测通过，不是真实端点验收）' }
            $script:ExitCode = 0
        } else {
            $verdict = 'FAIL（' + $failed.Count + ' 项）'
            $script:ExitCode = 3
        }
        Write-Summary $verdict
        Write-Host ''
        Write-Host ('结论：' + $verdict + '　证据：' + $script:OutDir) -ForegroundColor Cyan
    }
} catch {
    Add-Check 'X' '未预期异常' 'FAIL' $_.Exception.Message
    Write-Summary 'FAIL（异常）'
    $script:ExitCode = 1
} finally {
    Stop-Backend
    Restore-ProbeEnv
    if (Test-Path -LiteralPath $script:CookieJar) { Remove-Item -LiteralPath $script:CookieJar -Force -ErrorAction SilentlyContinue }
}

exit $script:ExitCode