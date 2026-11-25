#requires -Version 5.1
# 注意：本文件必须以 **UTF-8 with BOM** 保存。Windows PowerShell 5.1 对无 BOM 的文件按系统 ANSI（本机 GBK）
# 解码，中文注释会被拆成非法字节并吞掉换行，报出一堆与真正原因无关的 ParseError（2026-11-25 踩过）。
<#
.SYNOPSIS
    告警路由与送达演练：起本地 Alertmanager + Prometheus + Webhook Sink，把「规则 firing → 按 severity 路由 →
    抑制 → 送达 → 恢复」整条链跑一遍并落盘证据。

.DESCRIPTION
    背景：2026-11-24 的三次演练证明的是「Prometheus 规则会 firing」，没有证明「有人会收到」——
    仓库里没有 Alertmanager 配置、没有接收人、没有送达证据（见 docs/operations/alert-routing.md §1）。
    2026-11-25 把路由配置与本地接收端补齐后，本脚本把剩下的「送达」做成一条可重复执行的命令。

    它验证的是**配置与链路**，不是人的手机：接收端是本地 Sink（scripts/alert-webhook-sink.mjs），
    证据标 LOCAL-SINK。真实渠道（IM/电话/邮箱）的确认必须由值班人自己看到消息后回填
    docs/review/<日期>-alert-routing-drill.md 的人工确认列。

    验证项（每项 PASS/FAIL 并写入 -OutDir）：
      0) 工具链与配置语法：promtool check rules（生产 alerts.yml 18 条）+ amtool check-config（渲染后的路由）
      1) critical 路由：TravelAgentServerDown → oncall-critical，规则 firing 后 ≤120s 送达，
         且 payload 含 summary / severity / runbook_url
      2) 抑制：同实例的 TravelPlanOutcomeFailed（critical）被 TravelAgentServerDown 抑制，不单独通知
      3) warning 路由：TravelRouteProbeWarning → oncall-group
      4) info 路由：TravelRouteProbeInfo → daily-digest
      5) 兜底路由：漏打 severity 的 TravelRouteProbeUnlabeled → oncall-group（不被静默丢弃）
      6) 恢复：critical / warning 收到 status=resolved（resolved 受 group_interval 5m 约束，实测延迟写进证据）
      7) info 不发送 resolved（daily-digest 的 send_resolved: false 生效）

    两种模式：
      -Mode drill（默认）：本地 Sink 收消息，全自动判定，零成本、不碰业务、对外无请求。
      -Mode synthetic：用**真实 webhook**（从环境变量渲染生产配置）起本地 Alertmanager，注入一条合成
        critical 告警，验证 Alertmanager 是否把请求送到了真实端点（HTTP 2xx），再推一条 endsAt 并等
        resolved 的第二次投递（受 group_interval 5m 约束，这一步要等约 5 分钟）。送达"到人"仍需人工确认。

    进程安全：只停本脚本启动的 PID 及其子树（taskkill /PID <pid> /T /F），不枚举其它 java/node/prometheus 进程。

.PARAMETER Mode
    drill（默认）或 synthetic。synthetic 需要先设置 ALERT_WEBHOOK_CRITICAL/WARNING/INFO 三个环境变量。

.PARAMETER AlertmanagerBin / AmtoolBin / PrometheusBin
    可执行文件路径。默认依次取环境变量 ALERTMANAGER_BIN / AMTOOL_BIN / PROMETHEUS_BIN，
    再退回 .tools 下的本地解包目录（见 docs/operations/alert-routing.md §3.1 的下载命令）。

.PARAMETER SinkPort
    本地 Sink 端口，默认 19099。

.PARAMETER AlertmanagerPort
    Alertmanager 监听端口，默认 19093。

.PARAMETER PrometheusPort
    演练用 Prometheus 监听端口，默认 19090。

.PARAMETER WaitSeconds
    等待首次 firing 送达的上限，默认 240 秒（warning 组的 group_wait 就是 60s，不能太小）。

.PARAMETER ResolveWaitSeconds
    等待 resolved 送达的上限，默认 360 秒。Alertmanager 的 group_interval（5m）同时约束恢复通知：
    刚发过 firing 的组要等 group_interval 到点才外发 resolved，所以 30s 之类的短超时必然误判为 FAIL。
    drill 模式用在 C5，synthetic 模式用在 S4。

.PARAMETER OutDir
    证据目录，默认 .evidence\alert-routing-<yyyyMMdd-HHmmss>。

.PARAMETER DryRun
    只检查参数、工具链与端口，不启动任何进程。

.EXAMPLE
    # 全链路演练（本地 Sink，约 2 分钟）
    powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1

.EXAMPLE
    # 真实渠道确认（先给三个 webhook；脚本会注入一条合成 critical 告警并等 Alertmanager 的 Notify success）
    $env:ALERT_WEBHOOK_CRITICAL='https://...'; $env:ALERT_WEBHOOK_WARNING='https://...'; $env:ALERT_WEBHOOK_INFO='https://...'
    powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1 -Mode synthetic

.NOTES
    退出码：0 全部 PASS；2 参数/工具链/端口问题；3 演练存在 FAIL；1 未预期异常。
    证据目录结构：alertmanager.yml / prometheus.yml / rules*.yml / alerts.jsonl / deliveries/ /
                  sink.log / alertmanager.log / prometheus.log / checks.json / summary.md
#>
[CmdletBinding()]
param(
    [ValidateSet('drill', 'synthetic')]
    [string]$Mode = 'drill',
    [string]$AlertmanagerBin = '',
    [string]$AmtoolBin = '',
    [string]$PrometheusBin = '',
    [int]$SinkPort = 19099,
    [int]$AlertmanagerPort = 19093,
    [int]$PrometheusPort = 19090,
    [int]$WaitSeconds = 240,
    [int]$ResolveWaitSeconds = 360,
    [string]$OutDir = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:Checks = New-Object System.Collections.ArrayList
$script:Procs = New-Object System.Collections.ArrayList
$script:ExitCode = 0
$script:LocalSink = ($Mode -eq 'drill')
$script:TFire = $null
$script:TResolve = $null

function Write-Step([string]$title) {
    Write-Host ''
    Write-Host ('== ' + $title) -ForegroundColor Cyan
}
function Write-Info([string]$msg) { Write-Host ('        ' + $msg) -ForegroundColor DarkGray }
function Write-Warn([string]$msg) { Write-Host ('  WARN  ' + $msg) -ForegroundColor Yellow }
function Write-Utf8NoBom([string]$path, [string]$text) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $enc)
}
function Add-Check([string]$id, [string]$name, [string]$status, [string]$detail) {
    [void]$script:Checks.Add([pscustomobject]@{ id = $id; name = $name; status = $status; detail = $detail })
    $color = 'Gray'
    if ($status -eq 'PASS') { $color = 'Green' }
    if ($status -eq 'FAIL') { $color = 'Red' }
    if ($status -eq 'SKIP') { $color = 'Yellow' }
    Write-Host ('  ' + $status + '  ' + $id + ' ' + $name + '：' + $detail) -ForegroundColor $color
}

function Resolve-Tool([string]$explicit, [string]$envName, [string]$fallback, [string]$displayName) {
    if (-not [string]::IsNullOrWhiteSpace($explicit)) {
        if (Test-Path $explicit) { return (Resolve-Path $explicit).Path }
        throw "指定的可执行文件不存在：$explicit"
    }
    $fromEnv = [Environment]::GetEnvironmentVariable($envName)
    if (-not [string]::IsNullOrWhiteSpace($fromEnv)) {
        if (Test-Path $fromEnv) { return (Resolve-Path $fromEnv).Path }
        throw "$envName 指向的文件不存在：$fromEnv"
    }
    $candidate = Join-Path $script:RepoRoot $fallback
    if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    throw "找不到 $displayName。下载与放置方式见 docs/operations/alert-routing.md §3.1；也可用环境变量 $envName 指定路径。"
}

function Test-PortFree([int]$port) {
    $listening = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return ($null -eq $listening)
}

function Start-OwnedProcess([string]$file, [string]$argLine, [string]$label, [string]$stdout, [string]$stderr) {
    $p = Start-Process -FilePath $file -ArgumentList $argLine -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    [void]$script:Procs.Add([pscustomobject]@{ Label = $label; Process = $p })
    Write-Info ("{0} 启动：PID {1}" -f $label, $p.Id)
    return $p
}

function Stop-OwnedProcesses {
    for ($i = $script:Procs.Count - 1; $i -ge 0; $i--) {
        $entry = $script:Procs[$i]
        $p = $entry.Process
        try {
            if ($p -and -not $p.HasExited) {
                [void](Invoke-Native 'taskkill.exe' @('/PID', "$($p.Id)", '/T', '/F'))
                Write-Info ("{0} 已停止：PID {1}" -f $entry.Label, $p.Id)
            }
        } catch {
            Write-Warn ("停止 {0}（PID {1}）失败：{2}" -f $entry.Label, $p.Id, $_.Exception.Message)
        }
    }
}

# 原生命令（promtool / amtool / node / taskkill）会把进度写到 stderr。PS 5.1 在
# $ErrorActionPreference='Stop' 下会把 stderr 变成终止性错误（NativeCommandError），
# 所以统一走这个包装：局部放宽 EAP、合并两条流、显式拿退出码。
function Invoke-Native([string]$exe, [string[]]$argList) {
    $previous = $ErrorActionPreference
    $previousEncoding = [Console]::OutputEncoding
    $ErrorActionPreference = 'Continue'
    try {
        # 子进程（node / amtool / promtool）输出 UTF-8，而 PS 5.1 默认按控制台代码页（本机 GBK）解码，
        # 中文会被解成乱码并原样写进证据。这里显式按 UTF-8 解码，收尾恢复。
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        $lines = & $exe @argList 2>&1
        $code = $LASTEXITCODE
    } finally {
        [Console]::OutputEncoding = $previousEncoding
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ ExitCode = $code; Output = (($lines | Out-String).Trim()) }
}

function Wait-HttpReady([string]$url, [int]$timeoutSec, [string]$label) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { return $true }
        } catch {
            # 未就绪：继续等
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Warn ("{0} 在 {1}s 内未就绪：{2}" -f $label, $timeoutSec, $url)
    return $false
}

function Get-SinkDeliveries {
    $path = Join-Path $script:OutDir 'alerts.jsonl'
    if (-not (Test-Path $path)) { return @() }
    $out = @()
    foreach ($line in @(Get-Content -Path $path -Encoding UTF8)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $out += ($line | ConvertFrom-Json) } catch { Write-Warn 'alerts.jsonl 有一行无法解析，已跳过' }
    }
    return $out
}

function Get-DeliveryAlertNames($delivery) {
    if ($null -eq $delivery.alerts) { return @() }
    return @($delivery.alerts | ForEach-Object { $_.alertname })
}

function Get-DeliveryTime($delivery) {
    return [datetimeoffset]::Parse($delivery.receivedAt).UtcDateTime
}

function Wait-Until([scriptblock]$Condition, [int]$timeoutSec, [string]$label) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { return $true }
        Start-Sleep -Milliseconds 1000
    }
    Write-Warn ("等待超时（{0}s）：{1}" -f $timeoutSec, $label)
    return $false
}

function Invoke-Json([string]$method, [string]$url, [string]$body) {
    $params = @{ Uri = $url; Method = $method; UseBasicParsing = $true; TimeoutSec = 15 }
    if (-not [string]::IsNullOrWhiteSpace($body)) {
        $params['ContentType'] = 'application/json'
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($body)
    }
    return Invoke-WebRequest @params
}

# ---------------------------------------------------------------- 演练用的 Prometheus 配置与规则

function New-PrometheusConfig([string]$alertmanagerTarget) {
    return @"
# 由 scripts/alert-routing-drill.ps1 生成：只在演练期使用，不是生产抓取配置。
global:
  scrape_interval: 5s
  evaluation_interval: 5s
  external_labels:
    cluster: alert-routing-drill
rule_files:
  - rules.yml
alerting:
  alertmanagers:
    - timeout: 10s
      static_configs:
        - targets: ['$alertmanagerTarget']
scrape_configs: []
"@
}

function New-DrillRules([string]$expr) {
    # 同一条规则文件生成 firing / resolved 两版：resolved 版把表达式换成恒空（vector(1) > 2），
    # 这样 Post /-/reload 后告警原样消失，走的是 Prometheus 的"恢复"路径而不是伪造状态。
    return @"
# 由 scripts/alert-routing-drill.ps1 生成：合成告警，非真实故障。
groups:
  - name: alert-routing-drill
    interval: 5s
    rules:
      - alert: TravelAgentServerDown
        expr: $expr
        for: 10s
        labels:
          severity: critical
          instance: 'drill-travel-agent:18088'
          service: travel-agent
        annotations:
          summary: '路由演练：后端整体不可用（合成 critical，非真实故障）'
          description: '由 alert-routing-drill.ps1 注入，用于验证 critical 路由、送达与抑制源。'
          runbook_url: 'https://github.com/Lukas-Ga06/-/blob/main/docs/operations/runbook.md'

      - alert: TravelPlanOutcomeFailed
        expr: $expr
        for: 10s
        labels:
          severity: critical
          instance: 'drill-travel-agent:18088'
          service: travel-agent
        annotations:
          summary: '路由演练：应被 TravelAgentServerDown 抑制（合成告警）'
          description: '与抑制源同 instance；若它单独出现在 critical 通知里，说明 inhibit_rules 没生效。'
          runbook_url: 'https://github.com/Lukas-Ga06/-/blob/main/docs/operations/runbook.md'

      - alert: TravelRouteProbeWarning
        expr: $expr
        for: 10s
        labels:
          severity: warning
          instance: 'drill-travel-agent:18088'
          service: travel-agent
        annotations:
          summary: '路由演练：warning 通道（合成告警，非真实故障）'
          description: '验证 severity=warning 进 oncall-group，且 group_wait 为 1m。'
          runbook_url: 'https://github.com/Lukas-Ga06/-/blob/main/docs/operations/runbook.md'

      - alert: TravelRouteProbeInfo
        expr: $expr
        for: 10s
        labels:
          severity: info
          instance: 'drill-travel-agent:18088'
          service: travel-agent
        annotations:
          summary: '路由演练：info 日报通道（合成告警）'
          description: '验证 severity=info 进 daily-digest，且 send_resolved: false 生效。'
          runbook_url: 'https://github.com/Lukas-Ga06/-/blob/main/docs/operations/runbook.md'

      - alert: TravelRouteProbeUnlabeled
        expr: $expr
        for: 10s
        labels:
          instance: 'drill-travel-agent:18088'
          service: travel-agent
        annotations:
          summary: '路由演练：漏打 severity 的告警（合成）'
          description: '验证兜底路由：新规则忘写 severity 时进值班群，而不是被静默丢弃。'
          runbook_url: 'https://github.com/Lukas-Ga06/-/blob/main/docs/operations/runbook.md'
"@
}

# ---------------------------------------------------------------- 渲染 + 语法校验

function Invoke-AmConfigRender([string]$outFile) {
    $node = (Get-Command node -ErrorAction SilentlyContinue)
    if (-not $node) { throw '找不到 node（渲染脚本需要 Node 18+）。' }
    $render = Join-Path $script:RepoRoot 'scripts/render-alertmanager-config.mjs'
    if (-not (Test-Path $render)) { throw "找不到渲染脚本：$render" }
    $run = Invoke-Native $node.Path @($render, '-Out', $outFile)
    foreach ($line in ($run.Output -split "`r?`n")) { if (-not [string]::IsNullOrWhiteSpace($line)) { Write-Info $line } }
    if ($run.ExitCode -ne 0) { throw "渲染 Alertmanager 配置失败（exit $($run.ExitCode)）" }
}

function Invoke-AmtoolCheck([string]$configFile) {
    $run = Invoke-Native $script:Amtool @('check-config', $configFile)
    return [pscustomobject]@{ Ok = ($run.ExitCode -eq 0); Text = $run.Output }
}

# ---------------------------------------------------------------- 主流程

try {
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $OutDir = Join-Path $script:RepoRoot ('.evidence/alert-routing-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    $script:OutDir = $OutDir
    if (-not (Test-Path $script:OutDir)) { [void](New-Item -ItemType Directory -Path $script:OutDir -Force) }

    Write-Host ''
    Write-Host '告警路由与送达演练（drill 模式证据为 LOCAL-SINK，不等于真实渠道已送达）' -ForegroundColor White
    Write-Info ("模式：{0} ｜ 证据目录：{1}" -f $Mode, $script:OutDir)

    # ---- 0) 工具链
    Write-Step '0/8 工具链与配置语法'
    $script:Amtool = Resolve-Tool $AmtoolBin 'AMTOOL_BIN' '.tools/alertmanager/alertmanager-0.28.1.windows-amd64/amtool.exe' 'amtool'
    $amBin = Resolve-Tool $AlertmanagerBin 'ALERTMANAGER_BIN' '.tools/alertmanager/alertmanager-0.28.1.windows-amd64/alertmanager.exe' 'alertmanager'
    $promBin = Resolve-Tool $PrometheusBin 'PROMETHEUS_BIN' '.tools/prometheus/prometheus-3.16.0.windows-amd64/prometheus.exe' 'prometheus'
    $promtool = Join-Path (Split-Path -Parent $promBin) 'promtool.exe'
    if (-not (Test-Path $promtool)) { throw "找不到 promtool.exe（应与 prometheus.exe 同目录）：$promtool" }
    Write-Info ("amtool = {0}" -f $script:Amtool)
    Write-Info ("alertmanager = {0}" -f $amBin)
    Write-Info ("prometheus = {0}" -f $promBin)

    # 生产规则文件本身要能被同版本 Prometheus 解析（18 条规则，语法错会让整条告警链静默失效）。
    # alerts.yml 顶层是 PrometheusRule CRD（apiVersion/kind/spec），裸 Prometheus 只认 groups: ——
    # 先抽出 spec.groups 再校验；这一步以前靠手抄，少抄一条就是一条永久静默的告警。
    $extractedRules = Join-Path $script:OutDir 'alerts-rules.yml'
    $extractRun = Invoke-Native (Get-Command node).Path @((Join-Path $script:RepoRoot 'scripts/alerts-rules-extract.mjs'), '-Out', $extractedRules)
    $rulesText = $extractRun.Output
    $rulesOk = $false
    if ($extractRun.ExitCode -eq 0) {
        $rulesCheck = Invoke-Native $promtool @('check', 'rules', $extractedRules)
        $rulesText = ($rulesText + "`r`n" + $rulesCheck.Output).Trim()
        $rulesOk = ($rulesCheck.ExitCode -eq 0) -and ($rulesText -notmatch 'FAILED')
    }
    Add-Check 'C0a' '生产告警规则（抽出 spec.groups 后）可被 Prometheus 解析' $(if ($rulesOk) { 'PASS' } else { 'FAIL' }) ($rulesText -replace "`r?`n", ' ')
    Write-Utf8NoBom (Join-Path $script:OutDir 'promtool-check-rules.log') ($rulesText + "`r`n")

    # ---- 1) 渲染 Alertmanager 配置
    Write-Step '1/8 渲染 Alertmanager 配置'
    $amConfig = Join-Path $script:OutDir 'alertmanager.yml'
    if ($script:LocalSink) {
        $sinkUrl = "http://127.0.0.1:$SinkPort/alert"
        $env:ALERT_WEBHOOK_CRITICAL = $sinkUrl
        $env:ALERT_WEBHOOK_WARNING = $sinkUrl
        $env:ALERT_WEBHOOK_INFO = $sinkUrl
        Write-Info ("三个 receiver 全指向本地 Sink：{0}" -f $sinkUrl)
    } else {
        foreach ($name in @('ALERT_WEBHOOK_CRITICAL', 'ALERT_WEBHOOK_WARNING', 'ALERT_WEBHOOK_INFO')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
                throw "synthetic 模式需要 $name（真实 webhook）；见 docs/operations/alert-routing.md §3.2。"
            }
        }
        Write-Info '三个 receiver 使用环境变量里的真实 webhook（脚本不打印 URL 明文）'
    }
    Invoke-AmConfigRender $amConfig
    $amtoolResult = Invoke-AmtoolCheck $amConfig
    Add-Check 'C0b' '渲染后的 Alertmanager 配置通过 check-config' $(if ($amtoolResult.Ok) { 'PASS' } else { 'FAIL' }) ($amtoolResult.Text -replace "`r?`n", ' ')
    Write-Utf8NoBom (Join-Path $script:OutDir 'amtool-check-config.log') ($amtoolResult.Text + "`r`n")

    if ($DryRun) {
        Write-Step 'DryRun：跳过启动进程'
        $script:ExitCode = 0
        return
    }

    # ---- 2) 端口与工具自检
    Write-Step '2/8 端口可用性'
    # synthetic 模式只用 Alertmanager：Sink 与演练 Prometheus 不启动，也就不该因为它们被占用而拒绝执行
    # （本地 Sink 可能正开着收别的演练消息）。
    $portsToCheck = @(@{ Port = $AlertmanagerPort; Name = 'Alertmanager'; Param = '-AlertmanagerPort' })
    if ($Mode -eq 'drill') {
        $portsToCheck += @{ Port = $PrometheusPort; Name = 'Prometheus（演练实例）'; Param = '-PrometheusPort' }
        $portsToCheck += @{ Port = $SinkPort; Name = '本地 Sink'; Param = '-SinkPort' }
    }
    foreach ($pair in $portsToCheck) {
        if (-not (Test-PortFree $pair.Port)) {
            throw ("端口 {0} 已被占用（{1}）；换端口重跑，例：{2} 19xxx" -f $pair.Port, $pair.Name, $pair.Param)
        }
        Write-Info ("{0} 端口 {1} 空闲" -f $pair.Name, $pair.Port)
    }

    if ($Mode -eq 'drill') {
        $promConfig = Join-Path $script:OutDir 'prometheus.yml'
        $rulesFiring = Join-Path $script:OutDir 'rules-firing.yml'
        $rulesResolved = Join-Path $script:OutDir 'rules-resolved.yml'
        $rulesLive = Join-Path $script:OutDir 'rules.yml'
        Write-Utf8NoBom $promConfig (New-PrometheusConfig ("127.0.0.1:$AlertmanagerPort"))
        Write-Utf8NoBom $rulesFiring (New-DrillRules 'vector(1)')
        Write-Utf8NoBom $rulesResolved (New-DrillRules 'vector(1) > 2')
        Copy-Item -LiteralPath $rulesFiring -Destination $rulesLive -Force
        $promRun = Invoke-Native $promtool @('check', 'config', $promConfig)
        $promText = $promRun.Output
        Write-Utf8NoBom (Join-Path $script:OutDir 'promtool-check-config.log') ($promText + "`r`n")
        Add-Check 'C0c' '演练用 Prometheus 配置通过 promtool check config' $(if ($promRun.ExitCode -eq 0) { 'PASS' } else { 'FAIL' }) ($promText -replace "`r?`n", ' ')

        # ---- 3) 启动 Sink + Alertmanager + Prometheus
        Write-Step '3/8 启动 Sink / Alertmanager / Prometheus'
        $sinkScript = Join-Path $script:RepoRoot 'scripts/alert-webhook-sink.mjs'
        [void](Start-OwnedProcess (Get-Command node).Source "`"$sinkScript`" --out `"$($script:OutDir)`" --port $SinkPort" 'webhook-sink' `
                (Join-Path $script:OutDir 'sink.log') (Join-Path $script:OutDir 'sink.err.log'))
        [void](Start-OwnedProcess $amBin "--config.file=`"$amConfig`" --storage.path=`"$((Join-Path $script:OutDir 'am-data'))`" --web.listen-address=127.0.0.1:$AlertmanagerPort --cluster.listen-address= --log.level=debug" 'alertmanager' `
                (Join-Path $script:OutDir 'alertmanager.log') (Join-Path $script:OutDir 'alertmanager.err.log'))
        [void](Start-OwnedProcess $promBin "--config.file=`"$promConfig`" --storage.tsdb.path=`"$((Join-Path $script:OutDir 'prom-data'))`" --web.listen-address=127.0.0.1:$PrometheusPort --web.enable-lifecycle --log.level=info" 'prometheus' `
                (Join-Path $script:OutDir 'prometheus.log') (Join-Path $script:OutDir 'prometheus.err.log'))

        $sinkReady = Wait-HttpReady "http://127.0.0.1:$SinkPort/healthz" 20 '本地 Sink'
        $amReady = Wait-HttpReady "http://127.0.0.1:$AlertmanagerPort/-/ready" 40 'Alertmanager'
        $promReady = Wait-HttpReady "http://127.0.0.1:$PrometheusPort/-/ready" 40 'Prometheus'
        Add-Check 'C0d' '三个进程按顺序就绪' $(if ($sinkReady -and $amReady -and $promReady) { 'PASS' } else { 'FAIL' }) ("sink={0} alertmanager={1} prometheus={2}" -f $sinkReady, $amReady, $promReady)
        if (-not ($sinkReady -and $amReady -and $promReady)) {
            throw '本地进程没有全部就绪，见证据目录里的 *.log / *.err.log'
        }

        # ---- 4) 等规则 firing
        Write-Step '4/8 等待合成告警 firing（for: 10s）'
        $script:TFire = $null
        $fireOk = Wait-Until {
            try {
                $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$PrometheusPort/api/v1/alerts" -TimeoutSec 3
                foreach ($alert in @($resp.data.alerts)) {
                    if ($alert.labels.alertname -eq 'TravelAgentServerDown' -and $alert.state -eq 'firing') {
                        $script:TFire = Get-Date
                        return $true
                    }
                }
            } catch { }
            return $false
        } 60 '规则进入 firing'
        if ($fireOk) { Write-Info ("TravelAgentServerDown 于 {0:HH:mm:ss} 进入 firing" -f $script:TFire) }
        Add-Check 'C0e' '合成规则从 pending 进入 firing' $(if ($fireOk) { 'PASS' } else { 'FAIL' }) ($(if ($fireOk) { 'Prometheus /api/v1/alerts 观察到 state=firing' } else { '60s 内没有观察到 firing' }))

        # ---- 5) 等四路 firing 送达
        Write-Step '5/8 等待 firing 通知送达（warning / 兜底组 group_wait=1m）'
        $expectedAlertNames = @('TravelAgentServerDown', 'TravelRouteProbeWarning', 'TravelRouteProbeInfo', 'TravelRouteProbeUnlabeled')
        $deliverOk = Wait-Until {
            $seen = @{}
            foreach ($d in (Get-SinkDeliveries)) {
                if ($d.status -ne 'firing') { continue }
                foreach ($n in (Get-DeliveryAlertNames $d)) { $seen[$n] = $true }
            }
            foreach ($n in $expectedAlertNames) { if (-not $seen.ContainsKey($n)) { return $false } }
            return $true
        } $WaitSeconds '四路 firing 通知送达'
        Add-Check 'C0f' '四路 firing 通知全部到达本地 Sink' $(if ($deliverOk) { 'PASS' } else { 'FAIL' }) ("已收 {0} 条 delivery（上限 {1}s）" -f (@(Get-SinkDeliveries).Count), $WaitSeconds)

        # ---- 6) 判定 routing / 抑制 / 字段
        Write-Step '6/8 判定：severity 路由、抑制、通知字段、送达延迟'
        $all = @(Get-SinkDeliveries)
        $firing = @($all | Where-Object { $_.status -eq 'firing' })
        $resolved = @($all | Where-Object { $_.status -eq 'resolved' })

        $critical = @($firing | Where-Object { $_.receiver -eq 'oncall-critical' })
        $criticalNames = @()
        foreach ($d in $critical) { $criticalNames += (Get-DeliveryAlertNames $d) }
        $criticalDown = @($critical | Where-Object { (Get-DeliveryAlertNames $_) -contains 'TravelAgentServerDown' })
        $criticalFieldOk = $false
        $criticalLatency = $null
        if ($criticalDown.Count -gt 0) {
            $first = $criticalDown[0]
            $alert = @($first.alerts | Where-Object { $_.alertname -eq 'TravelAgentServerDown' })[0]
            $criticalFieldOk = ($alert.severity -eq 'critical') -and $alert.hasSummary -and $alert.hasRunbookUrl
            if ($script:TFire) {
                $criticalLatency = [math]::Round(((Get-DeliveryTime $first) - $script:TFire.ToUniversalTime()).TotalSeconds, 1)
            }
        }
        $latencyOk = ($null -ne $criticalLatency) -and ($criticalLatency -le 120)
        Add-Check 'C1' 'critical 路由到 oncall-critical 并在 120s 内送达' `
            $(if ($criticalDown.Count -gt 0 -and $latencyOk -and $criticalFieldOk) { 'PASS' } else { 'FAIL' }) `
            ("receiver=oncall-critical；送达延迟={0}s；summary/severity/runbook_url={1}" -f $criticalLatency, $(if ($criticalFieldOk) { '齐全' } else { '缺失' }))

        $inhibitedSeen = @($firing | Where-Object { (Get-DeliveryAlertNames $_) -contains 'TravelPlanOutcomeFailed' })
        $inhibitOk = ($inhibitedSeen.Count -eq 0) -and ($criticalNames -notcontains 'TravelPlanOutcomeFailed')
        Add-Check 'C2' '抑制规则生效（同实例 critical 不重复通知）' $(if ($inhibitOk) { 'PASS' } else { 'FAIL' }) `
            $(if ($inhibitOk) { 'TravelPlanOutcomeFailed 未出现在任何通知里' } else { '被抑制的告警仍被通知，核查 inhibit_rules.equal' })

        $warning = @($firing | Where-Object { $_.receiver -eq 'oncall-group' -and ((Get-DeliveryAlertNames $_) -contains 'TravelRouteProbeWarning') })
        Add-Check 'C3' 'warning 路由到 oncall-group' $(if ($warning.Count -gt 0) { 'PASS' } else { 'FAIL' }) ("命中 {0} 条" -f $warning.Count)

        $info = @($firing | Where-Object { $_.receiver -eq 'daily-digest' -and ((Get-DeliveryAlertNames $_) -contains 'TravelRouteProbeInfo') })
        Add-Check 'C4' 'info 路由到 daily-digest' $(if ($info.Count -gt 0) { 'PASS' } else { 'FAIL' }) ("命中 {0} 条" -f $info.Count)

        $fallback = @($firing | Where-Object { $_.receiver -eq 'oncall-group' -and ((Get-DeliveryAlertNames $_) -contains 'TravelRouteProbeUnlabeled') })
        Add-Check 'C7' '漏打 severity 的告警走兜底路由（进值班群）' $(if ($fallback.Count -gt 0) { 'PASS' } else { 'FAIL' }) ("命中 {0} 条" -f $fallback.Count)

        # ---- 7) 恢复路径
        Write-Step '7/8 恢复路径：规则恒空后 Post /-/reload，等 resolved'
        Copy-Item -LiteralPath $rulesResolved -Destination $rulesLive -Force
        $reloadOk = $false
        try {
            $reload = Invoke-Json 'POST' "http://127.0.0.1:$PrometheusPort/-/reload" $null
            $reloadOk = ($reload.StatusCode -eq 200)
        } catch {
            Write-Warn ('Post /-/reload 失败：' + $_.Exception.Message)
        }
        Write-Info ("Prometheus reload：{0}" -f $(if ($reloadOk) { '200' } else { '失败' }))
        # Alertmanager 的 group_interval（critical / warning 组都是 5m）同时约束恢复通知：
        # 刚发过 firing 的组，resolved 要等 group_interval 到点才会外发。所以这里不是"等 30s"能完事的，
        # 默认最多等 6 分钟，并把实测延迟写进证据 —— 这是聚合语义，不是丢消息。
        $script:TResolve = Get-Date
        $resolvedOk = Wait-Until {
            $r = @((Get-SinkDeliveries) | Where-Object { $_.status -eq 'resolved' })
            return ($r.Count -ge 3)
        } $ResolveWaitSeconds 'critical / warning / 兜底 三路 resolved 送达'

        $allResolved = @((Get-SinkDeliveries) | Where-Object { $_.status -eq 'resolved' })
        $critResolved = @($allResolved | Where-Object { $_.receiver -eq 'oncall-critical' -and ((Get-DeliveryAlertNames $_) -contains 'TravelAgentServerDown') })
        $warnResolved = @($allResolved | Where-Object { $_.receiver -eq 'oncall-group' -and ((Get-DeliveryAlertNames $_) -contains 'TravelRouteProbeWarning') })
        $resolveLatency = $null
        if ($critResolved.Count -gt 0) {
            $resolveLatency = [math]::Round(((Get-DeliveryTime $critResolved[0]) - $script:TResolve.ToUniversalTime()).TotalSeconds, 1)
        }
        Add-Check 'C5' 'critical / warning 收到 resolved' $(if ($critResolved.Count -gt 0 -and $warnResolved.Count -gt 0 -and $resolvedOk) { 'PASS' } else { 'FAIL' }) `
            ("critical resolved={0} 条；warning resolved={1} 条；reload→critical resolved 实测 {2}s（受 group_interval 5m 约束）" -f $critResolved.Count, $warnResolved.Count, $resolveLatency)

        $infoResolved = @($allResolved | Where-Object { $_.receiver -eq 'daily-digest' })
        Add-Check 'C6' 'info 不发送 resolved（send_resolved: false）' $(if ($infoResolved.Count -eq 0) { 'PASS' } else { 'FAIL' }) ("daily-digest resolved={0} 条" -f $infoResolved.Count)
    }
    else {
        # ---- synthetic：用真实 webhook 验证 Alertmanager 能送到真实端点
        Write-Step '2/4 启动 Alertmanager（真实 webhook）'
        if (-not (Test-PortFree $AlertmanagerPort)) { throw "端口 $AlertmanagerPort 已被占用" }
        [void](Start-OwnedProcess $amBin "--config.file=`"$amConfig`" --storage.path=`"$((Join-Path $script:OutDir 'am-data'))`" --web.listen-address=127.0.0.1:$AlertmanagerPort --cluster.listen-address= --log.level=debug" 'alertmanager' `
                (Join-Path $script:OutDir 'alertmanager.log') (Join-Path $script:OutDir 'alertmanager.err.log'))
        $amReady = Wait-HttpReady "http://127.0.0.1:$AlertmanagerPort/-/ready" 40 'Alertmanager'
        Add-Check 'S1' 'Alertmanager 用真实路由配置启动' $(if ($amReady) { 'PASS' } else { 'FAIL' }) ("ready={0}" -f $amReady)
        if (-not $amReady) { throw 'Alertmanager 未就绪，见 alertmanager.err.log' }

        Write-Step '3/4 注入合成 critical 告警'
        $amApi = "http://127.0.0.1:$AlertmanagerPort/api/v2/alerts"
        $startsAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        $probeLabels = [ordered]@{
            alertname = 'TravelRouteProbe'
            severity  = 'critical'
            instance  = 'alert-routing-verify'
            service   = 'travel-agent'
        }
        $probeAnnotations = [ordered]@{
            summary     = '值班路由人工确认：合成 critical 告警，非真实故障'
            description = '由 scripts/alert-routing-drill.ps1 -Mode synthetic 注入；收到即说明路由与渠道可用。'
            runbook_url = 'https://github.com/Lukas-Ga06/-/blob/main/docs/operations/runbook.md'
        }
        # generatorURL 必须是合法绝对 URL（写成相对路径会被 Alertmanager 以 422 拒绝）。
        $generatorUrl = 'https://github.com/Lukas-Ga06/-/blob/main/scripts/alert-routing-drill.ps1'
        $probe = [ordered]@{
            labels       = $probeLabels
            annotations  = $probeAnnotations
            startsAt     = $startsAt
            generatorURL = $generatorUrl
        }
        # /api/v2/alerts 只接受数组，而 PS 5.1 的 ConvertTo-Json 会把单元素数组拆成对象，所以手工补外层 [ ]。
        $alertBody = '[' + (ConvertTo-Json -InputObject $probe -Depth 6 -Compress) + ']'
        try {
            $push = Invoke-Json 'POST' $amApi $alertBody
            Add-Check 'S2' 'Alertmanager 接受合成告警' $(if ($push.StatusCode -eq 200) { 'PASS' } else { 'FAIL' }) ("HTTP {0}" -f $push.StatusCode)
        } catch {
            $detail = $_.Exception.Message
            $response = $_.Exception.Response
            if ($null -ne $response) {
                try {
                    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                    $detail = $detail + '；响应：' + $reader.ReadToEnd()
                } catch {
                    # 读不到响应体就只留状态码
                }
            }
            Add-Check 'S2' 'Alertmanager 接受合成告警' 'FAIL' $detail
            throw
        }

        Write-Step '4/4 等待 Alertmanager 把请求送到真实端点（Notify success）'
        # Alertmanager 的日志走 stderr（go-kit），Start-Process 把 stdout / stderr 分到了两个文件，两处都要查。
        $amLogs = @((Join-Path $script:OutDir 'alertmanager.log'), (Join-Path $script:OutDir 'alertmanager.err.log'))
        $notifyOk = Wait-Until {
            foreach ($amLog in $amLogs) {
                if (-not (Test-Path $amLog)) { continue }
                $hit = Select-String -Path $amLog -Pattern 'Notify success' -SimpleMatch -ErrorAction SilentlyContinue
                if ($null -ne $hit) { return $true }
            }
            return $false
        } 90 'Notify success'
        Add-Check 'S3' 'Alertmanager 向真实 webhook 投递成功（HTTP 2xx）' $(if ($notifyOk) { 'PASS' } else { 'FAIL' }) `
            $(if ($notifyOk) { '日志出现 Notify success' } else { '90s 内没有 Notify success：端点不可达/非 2xx，核查 webhook 与出网' })

        # 让真实渠道上也收到一条 resolved，避免演练消息永远挂在群里
        $resolvedProbe = [ordered]@{
            labels       = $probeLabels
            annotations  = $probeAnnotations
            startsAt     = $startsAt
            endsAt       = (Get-Date).AddSeconds(-1).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            generatorURL = $generatorUrl
        }
        $resolveBody = '[' + (ConvertTo-Json -InputObject $resolvedProbe -Depth 6 -Compress) + ']'
        try {
            [void](Invoke-Json 'POST' $amApi $resolveBody)
            Write-Info '已推送同一告警的 endsAt（渠道会收到 resolved）'
        } catch {
            Write-Warn ('推送 resolved 失败：' + $_.Exception.Message)
        }
        # resolved 同样受 group_interval（5m）约束：刚发过 firing 的组要等 5 分钟才外发恢复通知。
        # 所以这里必须继续等——脚本一退出就停 Alertmanager，等不到 resolved 就等于没验到恢复路径。
        $resolvedNotifyOk = Wait-Until {
            $hits = 0
            foreach ($amLog in $amLogs) {
                if (-not (Test-Path $amLog)) { continue }
                $hits += @(Select-String -Path $amLog -Pattern 'Notify success' -SimpleMatch -ErrorAction SilentlyContinue).Count
            }
            return ($hits -ge 2)
        } $ResolveWaitSeconds 'resolved 的 Notify success'
        Add-Check 'S4' '真实渠道收到 resolved 投递（HTTP 2xx）' $(if ($resolvedNotifyOk) { 'PASS' } else { 'FAIL' }) `
            $(if ($resolvedNotifyOk) { '日志出现第二条 Notify success（受 group_interval 约束，可能等 5 分钟）' } else { "{$ResolveWaitSeconds}s 内没有第二条 Notify success" })
    }

    # ---- 结果
    $failed = @($script:Checks | Where-Object { $_.status -eq 'FAIL' })
    $script:ExitCode = $(if ($failed.Count -eq 0) { 0 } else { 3 })
}
catch {
    Write-Host ''
    Write-Host ('演练中断：' + $_.Exception.Message) -ForegroundColor Red
    [void]$script:Checks.Add([pscustomobject]@{ id = 'X'; name = '演练执行'; status = 'FAIL'; detail = $_.Exception.Message })
    $script:ExitCode = 1
}
finally {
    if (-not $DryRun) {
        Write-Step '收尾：只停本脚本启动的进程'
        Stop-OwnedProcesses
    }

    # ---- 写证据
    if (-not [string]::IsNullOrWhiteSpace($script:OutDir) -and (Test-Path $script:OutDir)) {
        $checksJson = @($script:Checks) | ConvertTo-Json -Depth 5
        Write-Utf8NoBom (Join-Path $script:OutDir 'checks.json') ($checksJson + "`r`n")

        $deliveries = @()
        if ($Mode -eq 'drill') { $deliveries = @(Get-SinkDeliveries) }
        $lines = New-Object System.Collections.ArrayList
        [void]$lines.Add('# 告警路由与送达演练证据（' + $Mode.ToUpper() + '）')
        [void]$lines.Add('')
        [void]$lines.Add('> 生成时间：' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') + ' ｜ 脚本：`scripts/alert-routing-drill.ps1` ｜ 退出码：' + $script:ExitCode)
        if ($Mode -eq 'drill') {
            [void]$lines.Add('> **LOCAL-SINK**：接收端是本地 Sink，证明的是「配置与链路正确」，不等于真实渠道（IM/电话/邮箱）已送达；')
            [void]$lines.Add('> 真实渠道确认必须由值班人看到消息后回填 `docs/review/<日期>-alert-routing-drill.md` 的人工确认列。')
        }
        else {
            [void]$lines.Add('> **REAL-ENDPOINT**：使用环境变量里的真实 webhook；PASS 表示 Alertmanager 收到 HTTP 2xx，')
            [void]$lines.Add('> 「人收到了」仍需值班人确认。')
        }
        [void]$lines.Add('')
        [void]$lines.Add('## 1. 检查项')
        [void]$lines.Add('')
        [void]$lines.Add('| # | 检查 | 结果 | 说明 |')
        [void]$lines.Add('| --- | --- | --- | --- |')
        foreach ($c in $script:Checks) {
            [void]$lines.Add('| ' + $c.id + ' | ' + $c.name + ' | ' + $c.status + ' | ' + ($c.detail -replace '\|', '\|') + ' |')
        }
        [void]$lines.Add('')
        if ($Mode -eq 'drill') {
            [void]$lines.Add('## 2. 送达记录（本地 Sink 收到的时间顺序）')
            [void]$lines.Add('')
            [void]$lines.Add('| # | 收到时间（UTC） | receiver | status | 告警 |')
            [void]$lines.Add('| --- | --- | --- | --- | --- |')
            foreach ($d in $deliveries) {
                [void]$lines.Add('| ' + $d.seq + ' | ' + $d.receivedAt + ' | ' + $d.receiver + ' | ' + $d.status + ' | ' + ((Get-DeliveryAlertNames $d) -join ', ') + ' |')
            }
            [void]$lines.Add('')
            [void]$lines.Add('首次 firing 时间（UTC）：' + $(if ($script:TFire) { $script:TFire.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') } else { '(未观察到)' }))
            [void]$lines.Add('')
            if ($script:TResolve) {
                $firstResolved = @((Get-SinkDeliveries) | Where-Object { $_.status -eq 'resolved' }) | Sort-Object seq | Select-Object -First 1
                if ($firstResolved) {
                    $firstResolveLatency = [math]::Round(((Get-DeliveryTime $firstResolved) - $script:TResolve.ToUniversalTime()).TotalSeconds, 1)
                    [void]$lines.Add('reload → 首条 resolved 实测：' + $firstResolveLatency + ' s（受 group_interval 5m 约束，不是丢消息）')
                    [void]$lines.Add('')
                }
            }
            [void]$lines.Add('## 3. 证据文件')
            [void]$lines.Add('')
            foreach ($f in @('alertmanager.yml', 'alerts-rules.yml', 'prometheus.yml', 'rules-firing.yml', 'rules-resolved.yml', 'alerts.jsonl',
                    'sink.log', 'sink.err.log', 'alertmanager.log', 'alertmanager.err.log',
                    'prometheus.log', 'prometheus.err.log', 'promtool-check-rules.log',
                    'amtool-check-config.log', 'promtool-check-config.log', 'checks.json')) {
                if (Test-Path (Join-Path $script:OutDir $f)) { [void]$lines.Add('- `' + $f + '`') }
            }
            if (Test-Path (Join-Path $script:OutDir 'deliveries')) { [void]$lines.Add('- `deliveries/`（每个 webhook POST 的原始 payload）') }
            [void]$lines.Add('')
            [void]$lines.Add('复跑：`powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1`')
        }
        else {
            [void]$lines.Add('## 2. 人工确认（必须由值班人填）')
            [void]$lines.Add('')
            [void]$lines.Add('| 项 | 值 |')
            [void]$lines.Add('| --- | --- |')
            [void]$lines.Add('| 收到 critical 消息的人 |  |')
            [void]$lines.Add('| 收到时间 |  |')
            [void]$lines.Add('| 消息含 summary / severity / runbook_url |  |')
            [void]$lines.Add('| 是否收到 resolved |  |')
            [void]$lines.Add('| 截图/消息 ID |  |')
        }
        $verdict = $(if ($script:ExitCode -eq 0) { 'PASS' } else { 'FAIL' })
        [void]$lines.Add('')
        [void]$lines.Add('**判定：' + $verdict + '**')
        Write-Utf8NoBom (Join-Path $script:OutDir 'summary.md') (($lines -join "`r`n") + "`r`n")
        Write-Host ''
        Write-Host ('证据：' + (Join-Path $script:OutDir 'summary.md')) -ForegroundColor Green
    }
}

exit $script:ExitCode