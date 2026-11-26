<#
.SYNOPSIS
    高德 POI / 地理编码「质量」验收：用固定评测集验证真实 Key 返回的是不是「对的点」。

.DESCRIPTION
    与 .e2e/amap-real-key-probe.ps1 的分工：
      - amap-real-key-probe.ps1 证明「链路通」（起服务、规划、路线段 source=amap、坐标系统一）；
      - 本脚本证明「数据对」（POI 是否命中目标、城市是否一致、坐标是否落在中国境内、地址地理编码是否对城）。
    只直连高德 REST，不启动后端、不写库、不读业务数据；一次运行 8 个请求（5 个 POI + 3 个地理编码），
    对免费配额的影响可以忽略。

    评测集刻意选「容易同名/跨城」的地标（西湖、故宫、外滩、宽窄巷子、兵马俑），
    断言的是：top5 内命中期望关键词、PoI 的城市名与目标城市一致、坐标落在合理包围盒内。
    只认「命中了没有」，不评价排序好坏（排序会随高德侧模型变化，不适合当硬门禁）。

.PARAMETER Key
    高德开放平台「Web 服务」类型 Key。缺省依次读环境变量 AMAP_KEY、
    travel-agent-server\.env.local 的 AMAP_KEY。为了不把 Key 写进日志，脚本只打印指纹前 8 位。

.PARAMETER OutDir
    证据输出目录，默认 <仓库根>\.e2e\poi-quality-<yyyyMMdd-HHmmss>。

.PARAMETER DryRun
    只校验 Key 是否存在与参数格式，不发起任何外网请求。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\amap-poi-quality.ps1
#>
[CmdletBinding()]
param(
    [string]$Key = '',
    [string]$OutDir = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repoRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = 'https://restapi.amap.com/v3'
$chinaBox = @{ minLng = 73.0; maxLng = 135.0; minLat = 3.0; maxLat = 54.0 }

function Resolve-AmapKey {
    param([string]$Explicit)
    if ($Explicit -and $Explicit.Trim()) { return $Explicit.Trim() }
    if ($env:AMAP_KEY -and $env:AMAP_KEY.Trim()) { return $env:AMAP_KEY.Trim() }
    $envFile = Join-Path $repoRoot 'travel-agent-server\.env.local'
    if (Test-Path $envFile) {
        foreach ($line in Get-Content -Path $envFile -Encoding UTF8) {
            if ($line -match '^\s*AMAP_KEY\s*=\s*(.+?)\s*$') {
                $value = $Matches[1].Trim().Trim('"').Trim("'")
                if ($value) { return $value }
            }
        }
    }
    return ''
}

function Get-MaskedKey([string]$value) {
    if (-not $value) { return '<missing>' }
    if ($value.Length -le 8) { return '****' }
    return $value.Substring(0, 8) + '****'
}

function Invoke-AmapJson {
    param([string]$Path, [hashtable]$Query)
    $pairs = foreach ($name in $Query.Keys) {
        '{0}={1}' -f $name, [uri]::EscapeDataString([string]$Query[$name])
    }
    $url = '{0}/{1}?{2}&key={3}' -f $baseUrl, $Path, ($pairs -join '&'), [uri]::EscapeDataString($Key)
    # 不打印完整 URL：Key 在 query 里，日志留指纹即可。
    # 10021 = CUQPS_HAS_EXCEEDED_THE_LIMIT（并发/QPS 超限）：与生产 AmapDataService 同策略退避重试一次，
    # 否则「连续请求的评测脚本」会把限流误判成「POI 数据质量差」。
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $resp = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 20
        if ($resp.status -eq '1' -or $resp.infocode -ne '10021' -or $attempt -eq 2) { return $resp }
        Start-Sleep -Milliseconds 1200
    }
}

function ConvertTo-Point([string]$location) {
    if (-not $location -or $location -eq '[]') { return $null }
    $parts = $location.Split(',')
    if ($parts.Count -ne 2) { return $null }
    $lng = 0.0; $lat = 0.0
    if (-not [double]::TryParse($parts[0], [ref]$lng)) { return $null }
    if (-not [double]::TryParse($parts[1], [ref]$lat)) { return $null }
    return @{ lng = $lng; lat = $lat }
}

function Test-ChinaBox($point) {
    if ($null -eq $point) { return $false }
    return ($point.lng -ge $chinaBox.minLng -and $point.lng -le $chinaBox.maxLng -and
            $point.lat -ge $chinaBox.minLat -and $point.lat -le $chinaBox.maxLat)
}

$cases = @(
    @{ id = 'POI-1'; city = '杭州'; query = '西湖';     keywords = @('西湖') },
    @{ id = 'POI-2'; city = '北京'; query = '故宫';     keywords = @('故宫', '博物院') },
    @{ id = 'POI-3'; city = '上海'; query = '外滩';     keywords = @('外滩') },
    @{ id = 'POI-4'; city = '成都'; query = '宽窄巷子'; keywords = @('宽窄', '巷子') },
    @{ id = 'POI-5'; city = '西安'; query = '兵马俑';   keywords = @('兵马俑', '秦始皇') }
)

$geoCases = @(
    @{ id = 'GEO-1'; city = '杭州'; address = '西湖风景区' },
    @{ id = 'GEO-2'; city = '北京'; address = '故宫博物院' },
    @{ id = 'GEO-3'; city = '上海'; address = '外滩' }
)

$Key = Resolve-AmapKey -Explicit $Key
if (-not $Key) {
    Write-Host '[FAIL] 未找到 AMAP_KEY：请设置环境变量 AMAP_KEY，或写入 travel-agent-server\.env.local' -ForegroundColor Red
    exit 2
}
Write-Host ("[INFO] 使用 Key 指纹：{0}" -f (Get-MaskedKey $Key))
if ($DryRun) {
    Write-Host '[DRY] 参数与 Key 检查通过；未发起外网请求。' -ForegroundColor Yellow
    exit 0
}

if (-not $OutDir) {
    $OutDir = Join-Path $repoRoot ('.e2e\poi-quality-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$results = New-Object System.Collections.Generic.List[object]
$rawLog = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param([string]$Id, [string]$Item, [bool]$Pass, [string]$Detail)
    $results.Add([pscustomobject]@{ Id = $Id; Item = $Item; Pass = $Pass; Detail = $Detail })
    $color = if ($Pass) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1} {2} — {3}" -f $(if ($Pass) { 'PASS' } else { 'FAIL' }), $Id, $Item, $Detail) -ForegroundColor $color
}

foreach ($case in $cases) {
    try {
        $resp = Invoke-AmapJson -Path 'place/text' -Query @{
            keywords = $case.query; city = $case.city; offset = 5; page = 1; extensions = 'base'
        }
        $rawLog.Add([pscustomobject]@{ id = $case.id; kind = 'poi'; response = $resp })
        if ($resp.status -ne '1') {
            Add-Result $case.id ("POI[{0}/{1}]" -f $case.query, $case.city) $false ("status={0} info={1} infocode={2}" -f $resp.status, $resp.info, $resp.infocode)
            continue
        }
        $pois = @($resp.pois)
        if ($pois.Count -eq 0) {
            Add-Result $case.id ("POI[{0}/{1}]" -f $case.query, $case.city) $false '返回 0 条 POI'
            continue
        }
        $matchedRank = -1
        for ($i = 0; $i -lt $pois.Count; $i++) {
            $name = [string]$pois[$i].name
            foreach ($keyword in $case.keywords) {
                if ($name.Contains($keyword)) { $matchedRank = $i + 1; break }
            }
            if ($matchedRank -gt 0) { break }
        }
        $first = $pois[0]
        $point = ConvertTo-Point ([string]$first.location)
        $sameCity = ([string]$first.cityname).Contains($case.city)
        $inBox = Test-ChinaBox $point
        $pass = ($matchedRank -gt 0 -and $sameCity -and $inBox)
        $detail = ('top1="{0}" city="{1}" 期望关键词排名={2} 同城={3} 坐标合规={4}' -f $first.name, $first.cityname,
            $(if ($matchedRank -gt 0) { $matchedRank } else { '未命中' }), $sameCity, $inBox)
        Add-Result $case.id ("POI[{0}/{1}]" -f $case.query, $case.city) $pass $detail
    } catch {
        Add-Result $case.id ("POI[{0}/{1}]" -f $case.query, $case.city) $false ("请求异常：{0}" -f $_.Exception.Message)
    }
}

foreach ($case in $geoCases) {
    try {
        $resp = Invoke-AmapJson -Path 'geocode/geo' -Query @{ address = $case.address; city = $case.city }
        $rawLog.Add([pscustomobject]@{ id = $case.id; kind = 'geocode'; response = $resp })
        if ($resp.status -ne '1') {
            Add-Result $case.id ("GEO[{0}]" -f $case.address) $false ("status={0} info={1} infocode={2}" -f $resp.status, $resp.info, $resp.infocode)
            continue
        }
        $geos = @($resp.geocodes)
        if ($geos.Count -eq 0) {
            Add-Result $case.id ("GEO[{0}]" -f $case.address) $false '返回 0 条地理编码'
            continue
        }
        $first = $geos[0]
        $point = ConvertTo-Point ([string]$first.location)
        $sameCity = ([string]$first.city).Contains($case.city) -or ([string]$first.province).Contains($case.city)
        $inBox = Test-ChinaBox $point
        $pass = ($sameCity -and $inBox)
        Add-Result $case.id ("GEO[{0}/{1}]" -f $case.address, $case.city) $pass ('formatted="{0}" city="{1}" 同城={2} 坐标合规={3}' -f $first.formatted_address, $first.city, $sameCity, $inBox)
    } catch {
        Add-Result $case.id ("GEO[{0}]" -f $case.address) $false ("请求异常：{0}" -f $_.Exception.Message)
    }
}

$passCount = @($results | Where-Object { $_.Pass }).Count
$total = $results.Count
$allPass = ($passCount -eq $total)
$generatedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')

$summaryLines = New-Object System.Collections.Generic.List[string]
$summaryLines.Add('# 高德 POI / 地理编码质量验收')
$summaryLines.Add('')
$summaryLines.Add(('> 生成时间：{0} ｜ Key 指纹：{1} ｜ 数据源：高德 Web 服务 REST' -f $generatedAt, (Get-MaskedKey $Key)))
$summaryLines.Add(('> 结论：**{0}/{1} PASS**（只认「命中/同城/坐标合规」，不评价排序）' -f $passCount, $total))
$summaryLines.Add('')
$summaryLines.Add('| 编号 | 项目 | 结果 | 详情 |')
$summaryLines.Add('| --- | --- | --- | --- |')
foreach ($row in $results) {
    $mark = if ($row.Pass) { 'PASS' } else { 'FAIL' }
    $summaryLines.Add(('| {0} | {1} | {2} | {3} |' -f $row.Id, $row.Item, $mark, ($row.Detail -replace '\|', '/')))
}
$summaryLines.Add('')
$summaryLines.Add('请求原文（已脱敏：不含 Key 与完整 URL）：')
$summaryLines.Add('')
$summaryLines.Add('```json')
$summaryLines.Add(($rawLog | ConvertTo-Json -Depth 8))
$summaryLines.Add('```')

$summaryPath = Join-Path $OutDir 'summary.md'
$summaryLines | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host ''
Write-Host ("[SUMMARY] {0}/{1} PASS -> {2}" -f $passCount, $total, $summaryPath) -ForegroundColor $(if ($allPass) { 'Green' } else { 'Red' })
if ($allPass) { exit 0 } else { exit 1 }